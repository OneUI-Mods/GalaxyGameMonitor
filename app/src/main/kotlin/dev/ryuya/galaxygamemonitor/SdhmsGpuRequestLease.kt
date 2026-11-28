package dev.ryuya.galaxygamemonitor

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Gives SDHMS's own GPU request a HAL timeout while preserving its requested value. */
internal class SdhmsGpuRequestLease(
    private val module: XposedModule,
    loader: ClassLoader,
    limiterType: Class<*>,
    private val held: Field,
    private val maximum: Method,
    private val handler: () -> Handler,
) {
    private val dvfsType = loader.loadClass("com.samsung.android.os.SemDvfsManager")
    private val hyper = loader.loadClass("com.samsung.android.os.SemDvfsHyPerManager")
    private val dvfsField = limiterType.getDeclaredField("d").apply {
        check(type == dvfsType)
        isAccessible = true
    }
    private val acquire = dvfsType.getDeclaredMethod("acquire", Int::class.javaPrimitiveType).apply {
        check(returnType == Void.TYPE)
    }
    private val type = dvfsType.getDeclaredField("mType").apply {
        check(type == Int::class.javaPrimitiveType)
        isAccessible = true
    }

    private class NativeCall(val dvfs: Any) {
        var sentAt: Long? = null
    }

    private val activeCall = ThreadLocal<NativeCall>()
    private val lock = Any()
    private var renewal: Renewal? = null
    @Volatile private var retired = false

    fun install(applyLimit: Method) {
        // Ensure existing compiled SDHMS callers dispatch through the timed acquire hook.
        module.deoptimize(applyLimit)
        module.deoptimize(hyper.getDeclaredMethod("acquire"))
        module.hook(acquire).setId("sdhms-gpu-timed-acquire").intercept { chain ->
            val call = activeCall.get()
            if (retired || call == null || chain.thisObject !== call.dvfs ||
                chain.args.firstOrNull() != STOCK_NO_TIMEOUT
            ) {
                chain.proceed()
            } else {
                chain.proceed(arrayOf(TIMEOUT_MS)).also { call.sentAt = SystemClock.uptimeMillis() }
            }
        }
    }

    fun apply(limiter: Any, chain: Chain): Any? = synchronized(limiter) {
        val dvfs = if (retired) null else runCatching { owner(limiter) }.getOrNull()
        if (dvfs == null) return@synchronized chain.proceed()
        val call = NativeCall(dvfs)
        val previous = activeCall.get()
        activeCall.set(call)
        val result = try {
            chain.proceed()
        } finally {
            if (previous == null) activeCall.remove() else activeCall.set(previous)
        }
        followSafely(limiter, dvfs, call.sentAt)
        result
    }

    fun adopt(limiter: Any) = synchronized(limiter) {
        if (retired) return@synchronized
        runCatching { owner(limiter) }.getOrNull()?.let { followSafely(limiter, it, null) }
    }

    fun retire() = synchronized(lock) {
        retired = true
        clearRenewal()
    }

    private fun owner(limiter: Any): Any? = dvfsField.get(limiter)?.takeIf {
        it.javaClass == hyper && type.getInt(it) == GPU_MAX
    }

    private fun followSafely(limiter: Any, dvfs: Any, sentAt: Long?) {
        runCatching { follow(limiter, dvfs, sentAt) }
            .onFailure { Log.w(TAG, "Could not track the SDHMS GPU request timeout", it) }
    }

    private fun follow(limiter: Any, dvfs: Any, sentAt: Long?) = synchronized(lock) {
        if (retired) return@synchronized
        val current = renewal
        if (!held.getBoolean(limiter)) {
            if (current?.limiter === limiter) clearRenewal()
            return@synchronized
        }
        val requested = maximum.invoke(null) as Int
        if (current?.limiter === limiter) {
            val changed = current.ceiling != requested
            current.ceiling = requested
            if (sentAt != null) current.sentAt = sentAt
            if (sentAt == null && changed) {
                // An optimized native call may have bypassed the acquire hook.
                current.sentAt = null
                current.schedule(0)
            }
            return@synchronized
        }
        clearRenewal()
        val next = Renewal(handler(), limiter, dvfs, requested, sentAt)
        renewal = next
        next.schedule(if (sentAt == null) 0 else RENEW_MS)
    }

    private fun clearRenewal() {
        renewal?.let { it.worker.removeCallbacks(it) }
        renewal = null
    }

    private inner class Renewal(
        val worker: Handler,
        val limiter: Any,
        val dvfs: Any,
        var ceiling: Int,
        var sentAt: Long?,
    ) : Runnable {
        fun schedule(wait: Long) {
            worker.removeCallbacks(this)
            if (!worker.postDelayed(this, wait)) clearRenewal()
        }

        override fun run() {
            synchronized(limiter) {
                synchronized(lock) {
                    if (retired || renewal !== this) return
                    if (!held.getBoolean(limiter)) {
                        clearRenewal()
                        return
                    }
                    val now = SystemClock.uptimeMillis()
                    val wait = sentAt?.let { RENEW_MS - (now - it) } ?: 0L
                    if (wait > 0L) {
                        schedule(wait)
                        return
                    }
                    // This lock is still active. Renew through the original Samsung client and
                    // its existing request ID, including the last GPU value SDHMS chose.
                    val renewed = runCatching { acquire.invoke(dvfs, TIMEOUT_MS) }
                        .onFailure { Log.w(TAG, "Could not renew the SDHMS GPU request timeout", it) }
                        .isSuccess
                    if (renewed) sentAt = SystemClock.uptimeMillis()
                    schedule(if (renewed) RENEW_MS else RETRY_MS)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GalaxyGameMonitor"
        private const val GPU_MAX = 536875010
        private const val STOCK_NO_TIMEOUT = -999
        private const val TIMEOUT_MS = 30_000
        private const val RENEW_MS = 10_000L
        private const val RETRY_MS = 1_000L
    }
}
