package dev.ryuya.galaxygamemonitor

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {
    private var processName: String? = null
    private val attached = mutableMapOf<Method, GameBoosterDisplaySettings>()
    @Volatile private var hostLoader: ClassLoader? = null
    private var hostContext: Context? = null
    private var connectedProxy: Class<*>? = null
    private var monitorOverlay: MonitorOverlay? = null
    private var monitorSettingsEntry: GameBoosterMonitorSettings? = null
    private var gameMemory: GameMemory? = null
    private val monitorCapture = MonitorCapture()
    private val inGameSettings = InGameMonitorSettings()
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
        installSettingsHook(param.classLoader)
        installWindowHook()
    }

    @Synchronized
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val loader = hostLoader ?: return false
        param.setSavedInstanceState(arrayOf(loader, hostContext, connectedProxy, monitorSettingsEntry?.activity))
        retired = true
        attached.values.forEach(GameBoosterDisplaySettings::close)
        monitorOverlay?.close()
        monitorSettingsEntry?.close()
        inGameSettings.close()
        gameMemory?.close()
        monitorCapture.retire()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        onModuleLoaded(param)
        val state = param.savedInstanceState as? Array<*> ?: return
        val loader = state.getOrNull(0) as? ClassLoader ?: return
        if (param.processName != DisplaySettings.GAME_BOOSTER) return
        if (!installConnectionHook(loader)) return
        installSettingsHook(loader)
        installWindowHook()
        inGameSettings.attachExisting()
        val openSettings = (state.getOrNull(3) as? WeakReference<*>)?.get() as? Activity
        if (openSettings != null) monitorSettingsEntry?.attach(openSettings)
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

    private fun installSettingsHook(loader: ClassLoader) {
        runCatching {
            val activity = loader.loadClass("${DisplaySettings.GAME_BOOSTER}.setting.ui.SettingGameBoosterMainActivity")
            val toolbar = loader.loadClass("androidx.appcompat.widget.Toolbar")
            val entry = GameBoosterMonitorSettings(toolbar)
            hook(activity.getDeclaredMethod("onResume")).setId("monitor-settings-menu").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Activity)?.let(entry::attach)
                result
            }
            monitorSettingsEntry = entry
        }.onFailure { Log.w(TAG, "Game Booster settings hook unavailable", it) }
    }

    private fun installWindowHook() {
        runCatching {
            val addWindow = Class.forName("android.view.WindowManagerImpl")
                .getDeclaredMethod("addView", View::class.java, ViewGroup.LayoutParams::class.java)
            hook(addWindow).setId("monitor-capture-window").intercept { chain ->
                val result = chain.proceed()
                val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                val view = chain.args.firstOrNull() as? View
                if (!retired && view != null) {
                    when (params?.title?.toString()) {
                        "GameBooster PerformanceMonitor" -> runCatching { monitorCapture.onWindowAdded(view) }
                            .onFailure { Log.w(TAG, "Could not initialize monitor capture policy", it) }
                        InGameMonitorSettings.WINDOW_TITLE -> inGameSettings.onWindowAdded(view)
                    }
                }
                result
            }
        }.onFailure { Log.w(TAG, "Game Booster window hook unavailable", it) }
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
        val overlay = monitorOverlay ?: MonitorOverlay(context, monitorCapture).also { monitorOverlay = it }
        val gameRam = gameMemory ?: GameMemory(context).also { gameMemory = it }

        hook(method).setId("gos-monitor-request").intercept { chain ->
            val arguments = chain.args
            val input = arguments.getOrNull(1) as? String
            if (retired || arguments.firstOrNull() != PERF_REQUEST || input == null ||
                users?.isUserUnlocked != true
            ) return@intercept chain.proceed()

            val isMonitorRequest = runCatching { MonitorTelemetry.isMonitorRequest(input) }.getOrDefault(false)
            val raw = chain.proceed()
            if (!isMonitorRequest || raw !is String || retired) return@intercept raw

            runCatching {
                val settings = displaySettings.settings()
                if (overlay.supported) {
                    val clock = if (settings.shows(MonitorItem.GPU_CLOCK)) capacity.gpuClockMhz() else null
                    val battery = if (settings.shows(MonitorItem.BATTERY_TEMPERATURE) ||
                        settings.shows(MonitorItem.BATTERY_LEVEL)
                    ) capacity.battery() else null
                    val gameRamKiB = if (settings.shows(MonitorItem.GAME_RAM)) gameRam.readKiB() else null
                    overlay.submit(MonitorOverlay.Reading(settings, clock, battery, gameRamKiB))
                }
                val transformed = MonitorTelemetry.update(raw, capacity, settings.peakRelative)
                    ?: return@runCatching raw
                if (transformed.cpu != null && loggedCpu.compareAndSet(false, true)) {
                    val sample = transformed.cpu
                    val mode = if (sample.peakRelative) "peak-relative" else "busy time"
                    Log.i(TAG, "CPU usage active (${sample.source}, $mode): ${sample.percentage}%")
                }
                if (transformed.gpu != null && loggedGpu.compareAndSet(false, true)) {
                    val sample = transformed.gpu
                    Log.i(TAG, "GPU driver usage active (${sample.source}): ${sample.percentage}%")
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
    data class Result(val output: String, val cpu: DeviceCapacity.CpuSample?, val gpu: GpuUsage.Sample?)

    private const val GPU_LOAD = "gpu_load"
    private const val CPU_LOAD = "cpu_load_total"

    fun isMonitorRequest(input: String): Boolean {
        val request = JSONObject(input)
        if (request.optInt("session") != -1 || request.optLong("start") >= 0 ||
            request.optLong("end") != 0L
        ) return false

        val entries = request.optJSONArray("params") ?: return false
        val keys = (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.optString("param") }
        return keys.containsAll(listOf(CPU_LOAD, GPU_LOAD, "temp_lrpst", "game_fps", "gpu_utilization"))
    }

    fun update(
        raw: String,
        capacity: DeviceCapacity,
        peakRelativeCpu: Boolean,
    ): Result? {
        val reply = JSONObject(raw)
        if (!reply.optBoolean("successful")) return null

        val cpu = if (reply.hasSamples(CPU_LOAD)) capacity.cpuUsage(peakRelativeCpu) else null
        val gpu = if (reply.hasSamples(GPU_LOAD)) capacity.gpuUsage() else null
        if (cpu == null && gpu == null) return null

        if (cpu != null) reply.put(CPU_LOAD, JSONArray().put(kotlin.math.round(cpu.percentage)))
        if (gpu != null) {
            // Each driver reports one reading for its sampling window.
            reply.put(GPU_LOAD, JSONArray().put(gpu.percentage))
        }
        return Result(reply.toString(), cpu, gpu)
    }

    private fun JSONObject.hasSamples(key: String): Boolean = (optJSONArray(key)?.length() ?: 0) > 0
}
