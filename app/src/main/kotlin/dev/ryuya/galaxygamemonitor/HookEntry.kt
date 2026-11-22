package dev.ryuya.galaxygamemonitor

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {
    private var processName: String? = null
    private val attached = mutableMapOf<Method, GameBoosterDisplaySettings>()
    @Volatile private var hostLoader: ClassLoader? = null
    private var hostContext: Context? = null
    private var connectedProxy: Class<*>? = null
    @Volatile private var retired = false
    private val loggedCpu = AtomicBoolean()
    private val loggedGpu = AtomicBoolean()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val booster = DisplaySettings.GAME_BOOSTER
        if (!param.isFirstPackage || param.packageName != booster || processName != booster) return

        installConnectionHook(param.classLoader)
    }

    @Synchronized
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val loader = hostLoader ?: return false
        param.setSavedInstanceState(arrayOf(loader, hostContext, connectedProxy))
        retired = true
        attached.values.forEach(GameBoosterDisplaySettings::close)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        onModuleLoaded(param)
        val state = param.savedInstanceState as? Array<*> ?: return
        val loader = state.getOrNull(0) as? ClassLoader ?: return
        if (param.processName != DisplaySettings.GAME_BOOSTER) return
        if (!installConnectionHook(loader)) return
        val context = state.getOrNull(1) as? Context
        val proxy = state.getOrNull(2) as? Class<*>
        if (context != null && proxy != null) {
            val rebound = runCatching { installRequestHook(proxy, context) }.onFailure {
                Log.w(TAG, "Waiting for Game Booster to reconnect its telemetry service", it)
            }.getOrDefault(false)
            if (!rebound) return
        }
        param.oldHookHandles.forEach { it.unhook() }
        Log.i(TAG, "ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_CODE} pid=${android.os.Process.myPid()}")
    }

    private fun installConnectionHook(loader: ClassLoader): Boolean {
        val booster = DisplaySettings.GAME_BOOSTER
        return runCatching {
            val manager = loader.loadClass("$booster.data.GOSBindingManager")
            val gosInterface = loader.loadClass("com.samsung.android.game.gos.IGosService")
            val binderField = manager.declaredFields.single { it.type == gosInterface }
                .apply { isAccessible = true }
            val contextField = manager.declaredFields.single { it.type == Context::class.java }
                .apply { isAccessible = true }
            val connected = manager.getDeclaredMethod(
                "onServiceConnected",
                ComponentName::class.java,
                IBinder::class.java,
            )
            hook(connected).setId("gos-connected").intercept { chain ->
                val result = chain.proceed()
                runCatching tryInstall@{
                    val receiver = chain.thisObject ?: return@tryInstall
                    val binder = binderField.get(receiver) ?: return@tryInstall
                    val context = contextField.get(receiver) as? Context ?: return@tryInstall
                    installRequestHook(binder.javaClass, context.applicationContext)
                }.onFailure {
                    Log.w(TAG, "GOS telemetry hook unavailable", it)
                }
                result
            }
            hostLoader = loader
        }.onFailure {
            Log.w(TAG, "Game Booster telemetry interface unavailable", it)
        }.isSuccess
    }

    @Synchronized
    private fun installRequestHook(proxy: Class<*>, context: Context): Boolean {
        if (retired) return false
        val method = proxy.getDeclaredMethod(
            "requestWithJson",
            String::class.java,
            String::class.java,
        )
        if (method.returnType != String::class.java) return false
        if (method in attached) return true
        val capacity = DeviceCapacity(context)
        val displaySettings = GameBoosterDisplaySettings(context, capacity::resetCpuSample)
        val users = context.getSystemService(UserManager::class.java)

        hook(method).setId("gos-monitor-request").intercept { chain ->
            val arguments = chain.args
            val input = arguments.getOrNull(1) as? String
            if (arguments.firstOrNull() != PERF_REQUEST || input == null ||
                users?.isUserUnlocked != true
            ) return@intercept chain.proceed()

            if (!displaySettings.isPeakRelative()) return@intercept chain.proceed()

            val request = runCatching { MonitorTelemetry.readMonitorRequest(input) }.getOrNull()
                ?: return@intercept chain.proceed()
            val gpuPeak = capacity.gpuPeakHz
            val addGpuClock = gpuPeak != null && MonitorTelemetry.addGpuClock(request)
            val raw = if (addGpuClock) {
                chain.proceed(arrayOf(PERF_REQUEST, request.toString()))
            } else {
                chain.proceed()
            }
            if (raw !is String || !displaySettings.isPeakRelative()) return@intercept raw

            runCatching {
                val transformed = MonitorTelemetry.normalize(raw, capacity, gpuPeak, addGpuClock)
                    ?: return@runCatching raw
                if (transformed.cpu != null && loggedCpu.compareAndSet(false, true)) {
                    Log.i(TAG, "CPU peak-relative utilization active")
                }
                if (transformed.gpu != null && loggedGpu.compareAndSet(false, true)) {
                    Log.i(TAG, "GPU peak-relative utilization active")
                }
                transformed.output
            }.getOrDefault(raw)
        }
        attached[method] = displaySettings
        hostContext = context
        connectedProxy = proxy
        Log.i(TAG, "Game Booster system-monitor hook installed")
        return true
    }

    companion object {
        private const val TAG = "GalaxyGameMonitor"
        private const val PERF_REQUEST = "perf_data_simple_request"
    }
}

internal object MonitorTelemetry {
    data class Result(val output: String, val cpu: Double?, val gpu: Double?)

    private const val GPU_LOAD = "gpu_load"
    private const val GPU_CLOCK = "gpu_f"
    private const val CPU_LOAD = "cpu_load_total"

    fun readMonitorRequest(input: String): JSONObject? {
        val request = JSONObject(input)
        if (request.optInt("session") != -1 || request.optLong("start") >= 0 ||
            request.optLong("end") != 0L
        ) return null

        val entries = request.optJSONArray("params") ?: return null
        val keys = (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.optString("param") }
        return request.takeIf {
            keys.containsAll(listOf(CPU_LOAD, GPU_LOAD, "temp_lrpst", "game_fps", "gpu_utilization"))
        }
    }

    fun addGpuClock(request: JSONObject): Boolean {
        val entries = request.getJSONArray("params")
        val metrics = (0 until entries.length()).mapNotNull(entries::optJSONObject)
        if (metrics.any { it.optString("param") == GPU_CLOCK }) return false
        val load = metrics.first { it.optString("param") == GPU_LOAD }
        val clock = JSONObject(load.toString()).put("param", GPU_CLOCK)
        entries.put(clock)
        return true
    }

    fun normalize(
        raw: String,
        capacity: DeviceCapacity,
        gpuPeak: Long?,
        addedGpuClock: Boolean,
    ): Result? {
        val reply = JSONObject(raw)
        if (!reply.optBoolean("successful")) return null

        // Normalize only nonempty monitor values and let the stock UI round and aggregate them.
        val cpu = reply.optJSONArray(CPU_LOAD)?.takeIf { it.length() > 0 }?.let { capacity.cpuUsage() }
        if (cpu != null) reply.put(CPU_LOAD, JSONArray().put(cpu.roundForDisplay()))

        val gpu = if (gpuPeak != null) gpuSamples(reply, gpuPeak) else null
        if (gpu != null) reply.put(GPU_LOAD, gpu)
        if (addedGpuClock) reply.remove(GPU_CLOCK)
        if (cpu == null && gpu == null && !addedGpuClock) return null
        return Result(reply.toString(), cpu, gpu?.optDouble(0))
    }

    private fun gpuSamples(reply: JSONObject, peakHz: Long): JSONArray? {
        val load = reply.optJSONArray(GPU_LOAD) ?: return null
        val clock = reply.optJSONArray(GPU_CLOCK) ?: return null
        if (load.length() == 0 || load.length() != clock.length()) return null
        val values = JSONArray()
        for (index in 0 until load.length()) {
            val value = PeakLoad.gpu(load.optDouble(index), clock.optDouble(index), peakHz) ?: return null
            values.put(value)
        }
        return values
    }

    private fun Double.roundForDisplay() = kotlin.math.round(this)
}
