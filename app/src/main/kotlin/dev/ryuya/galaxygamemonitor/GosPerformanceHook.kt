package dev.ryuya.galaxygamemonitor

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.StrictMode
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule

/** In GOS, before its frequency requests reach Samsung's shared frequency service. */
internal class GosPerformanceHook(private val module: XposedModule) {
    private val main = Handler(Looper.getMainLooper())
    private val requests = mutableMapOf<Int, FrequencyRequest>()
    private val sending = ThreadLocal<Boolean>()
    private val stateLock = Any()
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = sync()
    }
    private val retry = Runnable { sync() }
    private val connection = Binder()
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = sync()
    }
    private var context: Context? = null
    private var gameState: SamsungGameState? = null
    private var retired = false
    private var game = false
    private var matched = false
    private var bypass = false
    @Volatile private var frequencyService: IBinder? = null
    private var acquireCode = 0
    private var releaseCode = 0

    fun install(loader: ClassLoader) {
        val ipm = loader.loadClass("${GpuPerformanceSettings.GOS}.ipm.Ipm")
        val stub = Class.forName("android.os.ICustomFrequencyManager\$Stub")
        acquireCode = stub.getDeclaredField("TRANSACTION_acquire").apply { isAccessible = true }.getInt(null)
        releaseCode = stub.getDeclaredField("TRANSACTION_release").apply { isAccessible = true }.getInt(null)
        val transact = Class.forName("android.os.BinderProxy").getDeclaredMethod(
            "transact", Int::class.javaPrimitiveType, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType,
        )
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        val start = ipm.getDeclaredMethod("start", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val stop = ipm.getDeclaredMethod("stop")
        val started = ipm.getDeclaredField("mShouldRun").apply { isAccessible = true }
        module.hook(transact).setId("gos-gpu-frequency-request").intercept(::transact)
        module.hook(attach).setId("gos-performance-application").intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? Application)?.let(::attach)
            result
        }
        module.hook(start).setId("gos-performance-game-start").intercept { chain ->
            setGame(true)
            try { chain.proceed() } finally { setGame(started.getBoolean(chain.thisObject)) }
        }
        module.hook(stop).setId("gos-performance-game-stop").intercept { chain ->
            try { chain.proceed() } finally { setGame(false) }
        }
        Log.i(TAG, "GOS GPU performance hooks installed")
    }

    fun attach(context: Context, saved: Bundle? = null) {
        val userManager = context.getSystemService(android.os.UserManager::class.java)
        if (userManager?.isSystemUser != true) return
        synchronized(stateLock) {
            if (retired || this.context != null) return
            this.context = context.applicationContext ?: context
            gameState = SamsungGameState(this.context ?: context)
            game = saved?.getBoolean(GpuPerformanceSettings.KEY_GAME) == true
            saved?.getParcelableArrayList("requests", Bundle::class.java)?.forEach { entry ->
                val binder = entry.getBinder("binder") ?: return@forEach
                val tag = entry.getString("tag") ?: return@forEach
                val resources = entry.getIntArray("resources") ?: return@forEach
                val token = entry.getInt("token")
                requests[token] = FrequencyRequest(binder, token, tag, entry.getInt("hint"), resources)
            }
        }
        frequencyService = runCatching {
            Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                .invoke(null, "CustomFrequencyManagerService") as? IBinder
        }.onFailure { Log.w(TAG, "Could not resolve Samsung's frequency service", it) }.getOrNull()
        runCatching { context.contentResolver.registerContentObserver(GpuPerformanceSettings.uri, false, observer) }
            .onFailure { Log.w(TAG, "Could not observe GPU performance settings", it) }
        runCatching {
            val actions = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            context.registerReceiver(screenState, actions, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "Could not observe screen power changes", it) }
        main.post(retry)
    }

    fun retire(): Array<Any?> {
        val saved = synchronized(stateLock) {
            retired = true
            // Replay stock values before the next generation installs its own filters.
            setBypass(false)
            val state = Bundle().apply {
                putBoolean(GpuPerformanceSettings.KEY_GAME, game)
                putParcelableArrayList("requests", ArrayList(requests.values.map { request ->
                    Bundle().apply {
                        putBinder("binder", request.binder)
                        putInt("token", request.token)
                        putString("tag", request.tag)
                        putInt("hint", request.hint)
                        putIntArray("resources", request.resources)
                    }
                }))
            }
            requests.clear()
            arrayOf(context, state)
        }
        main.removeCallbacksAndMessages(null)
        (saved[0] as? Context)?.let { owner ->
            runCatching { owner.contentResolver.unregisterContentObserver(observer) }
            runCatching { owner.unregisterReceiver(screenState) }
        }
        return saved
    }

    private fun setGame(active: Boolean) {
        synchronized(stateLock) {
            if (retired || game == active) return
            game = active
            if (!active) {
                requests.clear()
                matched = false
            }
        }
        main.post(retry)
    }

    private fun sync() {
        main.removeCallbacks(retry)
        val owner = synchronized(stateLock) { if (retired) null else context } ?: return
        val snapshot = runCatching { GpuPerformanceSettings.readRemote(owner) }.getOrNull()
        val bypassRequested = snapshot?.getBoolean(GpuPerformanceSettings.KEY_BYPASS) == true
        val foreground = gameState?.isActive() == true
        val active = synchronized(stateLock) {
            if (retired) return
            if (game && !foreground) requests.clear()
            game = foreground
            setBypass(game && bypassRequested)
            Bundle().apply {
                putBinder(GpuPerformanceSettings.KEY_GOS_TOKEN, connection)
                putBoolean(GpuPerformanceSettings.KEY_GAME, game)
                putBoolean(GpuPerformanceSettings.KEY_MATCHED, matched && bypass)
                if (game) requests.values.mapNotNull { it.ceiling }.minOrNull()?.let {
                    putInt(GpuPerformanceSettings.KEY_GOS_MAX_MHZ, it)
                }
            }
        }
        runCatching {
            owner.contentResolver.call(GpuPerformanceSettings.uri, GpuPerformanceSettings.GOS_SESSION, null, active)
        }.onFailure {
            if (snapshot != null) Log.w(TAG, "Could not report the GOS session", it)
        }
        if (synchronized(stateLock) { !retired && (game || snapshot == null) }) {
            main.postDelayed(retry, 2_000L)
        }
    }

    private fun setBypass(enabled: Boolean) {
        if (bypass == enabled) return
        bypass = enabled
        matched = false
        for (request in requests.values) {
            runCatching { send(request, enabled) }.onSuccess { success ->
                if (enabled && success) reportBypassed()
            }.onFailure { Log.w(TAG, "Could not update a GOS GPU request", it) }
        }
    }

    private fun transact(chain: Chain): Any? {
        val code = chain.args[0] as Int
        if (sending.get() == true || (code != acquireCode && code != releaseCode)) return chain.proceed()
        if (chain.args[2] != null || chain.args[3] != IBinder.FLAG_ONEWAY) return chain.proceed()
        val binder = frequencyService ?: return chain.proceed()
        if (chain.thisObject !== binder) return chain.proceed()
        val data = chain.args[1] as? Parcel ?: return chain.proceed()
        val request = decode(binder, data, acquire = code == acquireCode) ?: return chain.proceed()
        synchronized(stateLock) {
            if (retired) return chain.proceed()
            if (request.resources == null) {
                requests.remove(request.token)
                return chain.proceed()
            }
            if (request.ceiling == null) {
                requests.remove(request.token)
                return chain.proceed()
            }
            if (requests.size < 32 || requests.containsKey(request.token)) {
                val first = requests.isEmpty()
                requests[request.token] = request
                if (first) main.post(retry)
            }
            if (!game || !bypass) return chain.proceed()
            if (!runCatching { send(request, true) }.getOrDefault(false)) return chain.proceed()
            reportBypassed()
            return true
        }
    }

    private fun reportBypassed() {
        if (matched) return
        matched = true
        Log.i(TAG, "Filtered a GOS GPUMaxFreq request")
        main.post(retry)
    }

    private fun decode(binder: IBinder, data: Parcel, acquire: Boolean): FrequencyRequest? {
        if (data.dataSize() !in 64..2048) return null
        val position = data.dataPosition()
        val policy = StrictMode.getThreadPolicy()
        return try {
            data.setDataPosition(0)
            data.enforceInterface(DESCRIPTOR)
            if (data.readInt() != Process.myPid()) return null
            val token = data.readInt()
            if (!acquire) {
                return FrequencyRequest(binder, token, null, 0, null).takeIf { data.dataAvail() == 0 }
            }
            val tag = data.readString() ?: return null
            if (!isGosTag(tag)) return null
            val hint = data.readInt()
            val resources = data.createIntArray() ?: return null
            if (data.dataAvail() != 0 || resources.size % 2 != 0) return null
            FrequencyRequest(binder, token, tag, hint, resources)
        } catch (_: RuntimeException) {
            null
        } finally {
            data.setDataPosition(position)
            StrictMode.setThreadPolicy(policy)
        }
    }

    private fun send(request: FrequencyRequest, suppressGpuMax: Boolean): Boolean {
        val packet = Parcel.obtain(request.binder)
        sending.set(true)
        return try {
            packet.writeInterfaceToken(DESCRIPTOR)
            packet.writeInt(Process.myPid())
            packet.writeInt(request.token)
            val resources = requireNotNull(request.resources)
            val output = if (suppressGpuMax) withoutGpuMax(resources) else resources
            if (output.isEmpty() && request.hint == NO_HINT) {
                request.binder.transact(releaseCode, packet, null, IBinder.FLAG_ONEWAY)
            } else {
                packet.writeString(request.tag)
                packet.writeInt(request.hint)
                packet.writeIntArray(output)
                request.binder.transact(acquireCode, packet, null, IBinder.FLAG_ONEWAY)
            }
        } finally {
            sending.remove()
            packet.recycle()
        }
    }

    private data class FrequencyRequest(val binder: IBinder, val token: Int, val tag: String?, val hint: Int, val resources: IntArray?) {
        val ceiling = resources?.indices?.step(2)?.mapNotNull { index ->
            if (resources[index] == GPU_MAX) GpuHardwareReadings.toMhz(resources[index + 1].toLong()) else null
        }?.minOrNull()
    }

    companion object {
        private const val TAG = "GalaxyGameMonitor"
        private const val DESCRIPTOR = "android.os.ICustomFrequencyManager"
        private const val GPU_MAX = 536875010
        private const val NO_HINT = -999

        private fun isGosTag(tag: String): Boolean {
            val normalized = tag.replace('/', '.')
            return normalized == GpuPerformanceSettings.GOS || normalized.startsWith("${GpuPerformanceSettings.GOS}.")
        }

        internal fun withoutGpuMax(resources: IntArray): IntArray = resources.indices.step(2)
            .filter { resources[it] != GPU_MAX }
            .flatMap { listOf(resources[it], resources[it + 1]) }
            .toIntArray()
    }
}
