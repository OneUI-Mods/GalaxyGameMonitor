package dev.ryuya.galaxygamemonitor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Leaves the normal Samsung GPU limiter in its native stack during game scenarios. */
internal class SdhmsGpuThermalHook(private val module: XposedModule, private val loader: ClassLoader) {
    private val stackType = loader.loadClass("j5.q3")
    private val limiterType = loader.loadClass("j5.z")
    private val temporaryType = loader.loadClass("j5.z4")
    private val scenarioType = loader.loadClass("j5.r4")
    private val baseType = loader.loadClass("j5.d4")
    private val managerType = loader.loadClass("j5.m4")
    private val limitType = loader.loadClass("j5.e2")
    private val limitsType = loader.loadClass("j5.o3")
    private val applyLimit = limitType.getDeclaredMethod("k", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val maximumHeld = limitType.checkedField("k", Boolean::class.javaPrimitiveType!!)
    private val requestedMaximum = loader.loadClass("j5.t4").getMethod("l")
    private val limits = limitsType.checkedField("I", limitsType)
    private val getLimit = limitsType.getMethod("b0", String::class.java)
    private val add = stackType.getDeclaredMethod("c", limiterType).apply { isAccessible = true }
    private val remove = stackType.getDeclaredMethod("e", limiterType).apply { isAccessible = true }
    private val monitor = stackType.checkedField("b", Any::class.java)
    private val stacks = stackType.checkedField("a", HashMap::class.java)
    private val limiterName = limiterType.checkedField("c", String::class.java)
    private val limiterScenario = limiterType.checkedField("g", loader.loadClass("j5.a"))
    private val releaseValues = temporaryType.checkedField("m", IntArray::class.java)
    private val temporary = temporaryType.checkedField("q", Boolean::class.javaPrimitiveType!!)
    private val scenarioName = scenarioType.getMethod("e")
    private val scenarioActive = scenarioType.getMethod("g")
    private val manager = managerType.getDeclaredMethod("o")
    private val initialize = managerType.getDeclaredMethod("v", Context::class.java, Handler::class.java)
    private val managerContext = managerType.checkedField("a", Context::class.java)
    private val handlerField = managerType.checkedField("b", Handler::class.java)
    private val stackField = managerType.checkedField("c", stackType)

    // Each entry is a stock limiter that still belongs to a running scenario. Key order is
    // preserved so the native priority sort gets the same order when the option is turned off.
    private val omitted = LinkedHashMap<Any, Any>()
    private val main = Handler(Looper.getMainLooper())
    private val token = Binder()
    @Volatile private var context: Context? = null
    @Volatile private var ceiling = GpuPerformanceSettings.SDHMS_UNKNOWN
    private var loggedConnection = false
    private val publishPending = AtomicBoolean()
    private val publish = Runnable {
        publishPending.set(false)
        publish()
    }
    private var observing = false
    private var receiving = false
    @Volatile private var enabled = false
    @Volatile private var retired = false
    private var requestLease: SdhmsGpuRequestLease? = null
    private val sync = Runnable { sync() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = sync()
    }
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = sync()
    }

    fun install() {
        check(add.returnType == Void.TYPE && remove.returnType == Void.TYPE)
        check(initialize.returnType == Void.TYPE && applyLimit.returnType == Void.TYPE)
        check(requestedMaximum.returnType == Int::class.javaPrimitiveType)
        check(scenarioName.returnType == String::class.java && scenarioActive.returnType == Boolean::class.javaPrimitiveType)
        module.hook(add).setId("sdhms-gpu-stack-add").intercept(::addRule)
        module.hook(remove).setId("sdhms-gpu-stack-remove").intercept(::removeRule)
        requestLease = runCatching {
            SdhmsGpuRequestLease(module, loader, limitType, maximumHeld, requestedMaximum) {
                (handlerField.get(manager.invoke(null)) as? Handler) ?: main
            }.also { it.install(applyLimit) }
        }.onFailure { Log.w(TAG, "SDHMS GPU request timeout unavailable", it) }.getOrNull()
        module.hook(applyLimit).setId("sdhms-gpu-request").intercept { chain ->
            val limiter = chain.thisObject ?: return@intercept chain.proceed()
            val lease = requestLease
            val result = if (!retired && lease != null) lease.apply(limiter, chain) else chain.proceed()
            if (!retired) {
                runCatching { sampleRequest(limiter) }
                    .onFailure { Log.w(TAG, "Could not sample the SDHMS GPU request", it) }
            }
            result
        }
        module.hook(initialize).setId("sdhms-gpu-manager").intercept { chain ->
            val result = chain.proceed()
            (chain.args.firstOrNull() as? Context)?.let { app ->
                main.post { attach(app) }
            }
            result
        }
        Log.i(TAG, "SDHMS GPU thermal hooks installed")
    }

    fun reconnect(previous: Context?, savedRules: Array<*>) {
        val owner = previous ?: runCatching { managerContext.get(manager.invoke(null)) as? Context }.getOrNull()
            ?: runCatching {
                Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
                    .invoke(null) as? Application
            }.getOrNull()
        if (owner != null) main.post { attach(owner, savedRules) }
    }

    @Synchronized
    private fun attach(app: Context, savedRules: Array<*> = emptyArray<Any?>()) {
        if (retired || context != null || Process.myUserHandle() != UserHandle.getUserHandleForUid(0)) return
        val owner = app.applicationContext ?: app
        context = owner
        synchronized(omitted) {
            savedRules.filterIsInstance<Array<*>>().forEach { pair ->
                val rule = pair.getOrNull(0)
                val stack = pair.getOrNull(1)
                if (rule != null && stackType.isInstance(stack) && gameRelease(rule) != null) {
                    omitted[rule] = requireNotNull(stack)
                }
            }
        }
        observing = runCatching {
            val resolver = owner.contentResolver
            resolver.registerContentObserver(GpuPerformanceSettings.uri, false, observer)
            try {
                resolver.registerContentObserver(GpuPerformanceSettings.sdhmsRefreshUri, false, observer)
            } catch (error: RuntimeException) {
                resolver.unregisterContentObserver(observer)
                throw error
            }
        }.onFailure { Log.w(TAG, "Could not observe SDHMS GPU settings", it) }.isSuccess
        receiving = runCatching {
            owner.registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        main.post(sync)
    }

    @Synchronized
    fun retire(): Array<Any?> {
        retired = true
        requestLease?.retire()
        main.removeCallbacks(sync)
        main.removeCallbacks(publish)
        context?.let {
            if (observing) it.contentResolver.unregisterContentObserver(observer)
            if (receiving) it.unregisterReceiver(unlocked)
        }
        return arrayOf(context, synchronized(omitted) {
            omitted.map { (rule, stack) -> arrayOf(rule, stack) }.toTypedArray()
        })
    }

    private fun sync() {
        val owner = context ?: return
        if (retired) return
        runCatching {
            val settings = owner.contentResolver.call(GpuPerformanceSettings.uri, GpuPerformanceSettings.SDHMS_CONFIG, null, null)
                ?: return
            val requested = settings.getBoolean(GpuPerformanceSettings.KEY_SDHMS)
            val nativeManager = manager.invoke(null)
            val stack = stackField.get(nativeManager)
            val nativeHandler = handlerField.get(nativeManager) as? Handler
            if (stack != null && nativeHandler != null) {
                nativeHandler.post {
                    if (retired) return@post
                    runCatching { reconcile(stack, requested) }
                        .onFailure { Log.w(TAG, "Could not update the SDHMS GPU limiter", it) }
                    runCatching {
                        limits.get(null)?.let { getLimit.invoke(it, GPU) }?.let { limiter ->
                            requestLease?.adopt(limiter)
                            sampleRequest(limiter)
                        }
                    }.onFailure { Log.w(TAG, "Could not read the SDHMS GPU request", it) }
                    schedulePublish()
                }
            } else {
                enabled = requested
                schedulePublish()
            }
        }.onFailure { Log.w(TAG, "Could not read SDHMS GPU settings", it) }
    }

    private fun sampleRequest(limiter: Any) = synchronized(limiter) {
        // Samsung publishes the table maximum on release. The held flag distinguishes
        // that default from an explicit request at the same frequency.
        val next = if (maximumHeld.getBoolean(limiter)) {
            (requestedMaximum.invoke(null) as Int).takeIf { it > 0 } ?: GpuPerformanceSettings.SDHMS_UNKNOWN
        } else {
            GpuPerformanceSettings.SDHMS_NO_REQUEST
        }
        if (ceiling != next) {
            ceiling = next
            schedulePublish()
        }
    }

    private fun schedulePublish() {
        if (retired || context == null || !publishPending.compareAndSet(false, true)) return
        main.post(publish)
    }

    private fun publish() {
        val owner = context ?: return
        if (retired) return
        runCatching {
            val result = owner.contentResolver.call(GpuPerformanceSettings.uri, GpuPerformanceSettings.SDHMS_SESSION, null,
                Bundle().apply {
                    putBinder(GpuPerformanceSettings.KEY_SDHMS_TOKEN, token)
                    putInt(GpuPerformanceSettings.KEY_SDHMS_MAX_MHZ, ceiling)
                })
            if (result != null && !loggedConnection) {
                loggedConnection = true
                Log.i(TAG, "SDHMS GPU settings connected")
            }
        }.onFailure { Log.w(TAG, "Could not report the SDHMS GPU request", it) }
    }

    private fun addRule(chain: Chain): Any? {
        val stack = chain.thisObject ?: return chain.proceed()
        val rule = chain.args[0] ?: return chain.proceed()
        if (retired || !enabled) return chain.proceed()
        val scenario = gameRelease(rule) ?: return chain.proceed()
        return synchronized(monitor.get(stack)!!) {
            val rules = gpuRules(stack)
            if (enabled && !retired && rules.any(baseType::isInstance) && rule !in rules) {
                remember(rule, stack, scenario)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun removeRule(chain: Chain): Any? {
        val stack = chain.thisObject ?: return chain.proceed()
        val rule = chain.args[0] ?: return chain.proceed()
        if (retired || gameRelease(rule) == null) return chain.proceed()
        return synchronized(monitor.get(stack)!!) {
            val wasOmitted = synchronized(omitted) {
                omitted[rule] === stack && omitted.remove(rule) != null
            }
            if (wasOmitted) null else chain.proceed()
        }
    }

    private fun reconcile(stack: Any, requested: Boolean) = synchronized(monitor.get(stack)!!) {
        enabled = requested
        if (requested) {
            val rules = gpuRules(stack)
            // Without the native baseline limiter, leave the entire stock stack in place.
            if (!rules.any(baseType::isInstance)) return@synchronized
            for (rule in rules.toList()) {
                val scenario = gameRelease(rule) ?: continue
                remove.invoke(stack, rule)
                remember(rule, stack, scenario)
            }
        } else {
            val previous = synchronized(omitted) { omitted.filterValues { it === stack }.keys.toList() }
            for (rule in previous) {
                val scenario = limiterScenario.get(rule)
                if (scenarioActive.invoke(scenario) == true && rule !in gpuRules(stack)) {
                    add.invoke(stack, rule)
                }
                synchronized(omitted) { omitted.remove(rule) }
            }
        }
    }

    private fun gpuRules(stack: Any): List<Any> =
        (stacks.get(stack) as? Map<*, *>)?.get(GPU).let { raw ->
            (raw as? List<*>)?.filterNotNull().orEmpty()
        }

    private fun remember(rule: Any, stack: Any, scenario: String) {
        if (synchronized(omitted) { omitted.put(rule, stack) == null }) {
            Log.i(TAG, "SDHMS normal GPU temperature policy retained during $scenario")
        }
    }

    private fun gameRelease(rule: Any): String? {
        if (!temporaryType.isInstance(rule)) return null
        val scenario = limiterScenario.get(rule)
        if (!scenarioType.isInstance(scenario)) return null
        val name = scenarioName.invoke(scenario) as? String
        return name.takeIf {
            SdhmsGpuPolicy.isGameRelease(it, limiterName.get(rule) as? String, temporary.getBoolean(rule),
                releaseValues.get(rule) as? IntArray)
        }
    }

    private fun Class<*>.checkedField(name: String, expected: Class<*>): Field =
        getDeclaredField(name).apply {
            check(type == expected)
            isAccessible = true
        }

    companion object {
        private const val TAG = "GalaxyGameMonitor"
        private const val GPU = "GPUFreqMax"

        fun supportsDevice(): Boolean =
            Build.VERSION.SDK_INT == 37 && Build.VERSION.INCREMENTAL == "S948QOPU1BZI9"
    }
}

