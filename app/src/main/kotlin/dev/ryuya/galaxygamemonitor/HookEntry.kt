package dev.ryuya.galaxygamemonitor

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
import java.util.concurrent.atomic.AtomicReference

class HookEntry : XposedModule() {
    private var processName: String? = null
    private val attached = mutableMapOf<Method, GameBoosterDisplaySettings>()
    @Volatile private var hostLoader: ClassLoader? = null
    private var hostContext: Context? = null
    private var connectedProxy: Class<*>? = null
    @Volatile private var monitorOverlay: MonitorOverlay? = null
    private var monitorSettingsEntry: GameBoosterMonitorSettings? = null
    private var gameMemory: GameMemory? = null
    private val monitorCapture = MonitorCapture()
    private val inGameSettings = InGameMonitorSettings()
    private val boosterGameSession = BoosterGameSession()
    private var gosPerformance: GosPerformanceHook? = null
    private var sdhmsGpuThermal: SdhmsGpuThermalHook? = null
    @Volatile private var retired = false
    private val loggedCpu = AtomicBoolean()
    private val loggedGpuMode = AtomicReference<Boolean?>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != processName) return
        if (param.packageName == GpuPerformanceSettings.GOS) {
            installGosHook(param.classLoader)
            return
        }
        if (param.packageName == GpuPerformanceSettings.SDHMS) {
            installSdhmsHook(param.classLoader)
            return
        }
        if (param.packageName != DisplaySettings.GAME_BOOSTER) return

        installConnectionHook(param.classLoader)
        installSettingsHook(param.classLoader)
        installWindowHook()
    }

    @Synchronized
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val loader = hostLoader ?: return false
        if (processName == GpuPerformanceSettings.GOS) {
            val performance = gosPerformance ?: return false
            param.setSavedInstanceState(arrayOf(loader, performance.retire()))
            return true
        }
        if (processName == GpuPerformanceSettings.SDHMS) {
            val thermal = sdhmsGpuThermal ?: return false
            param.setSavedInstanceState(arrayOf(loader, thermal.retire()))
            return true
        }
        param.setSavedInstanceState(arrayOf(loader, hostContext, connectedProxy, monitorSettingsEntry?.activity))
        retired = true
        attached.values.forEach(GameBoosterDisplaySettings::close)
        monitorOverlay?.close()
        monitorSettingsEntry?.close()
        inGameSettings.close()
        boosterGameSession.close()
        gameMemory?.close()
        monitorCapture.retire()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        onModuleLoaded(param)
        val state = param.savedInstanceState as? Array<*> ?: return
        val loader = state.getOrNull(0) as? ClassLoader ?: return
        if (param.processName == GpuPerformanceSettings.GOS) {
            val previous = state.getOrNull(1) as? Array<*>
            val performance = installGosHook(loader) ?: return
            val context = previous?.getOrNull(0) as? Context
            if (context != null) performance.attach(context, previous.getOrNull(1) as? Bundle)
            param.oldHookHandles.forEach { it.unhook() }
            logHotReload()
            return
        }
        if (param.processName == GpuPerformanceSettings.SDHMS) {
            val previous = state.getOrNull(1) as? Array<*>
            val thermal = installSdhmsHook(loader) ?: return
            val context = previous?.getOrNull(0) as? Context
            thermal.reconnect(context, previous?.getOrNull(1) as? Array<*> ?: emptyArray<Any?>())
            param.oldHookHandles.forEach { it.unhook() }
            logHotReload()
            return
        }
        if (param.processName != DisplaySettings.GAME_BOOSTER) return
        if (!installConnectionHook(loader)) return
        installSettingsHook(loader)
        installWindowHook()
        inGameSettings.attachExisting()
        val openSettings = (state.getOrNull(3) as? WeakReference<*>)?.get() as? Activity
        if (openSettings != null) monitorSettingsEntry?.attach(openSettings)
        val context = state.getOrNull(1) as? Context
        val proxy = state.getOrNull(2) as? Class<*>
        if (context != null) boosterGameSession.attach(context)
        if (context != null && proxy != null) {
            val rebound = runCatching { installRequestHook(proxy, context) }.onFailure {
                Log.w(TAG, "Waiting for Game Booster to reconnect its telemetry service", it)
            }.getOrDefault(false)
            if (!rebound) return
        }
        param.oldHookHandles.forEach { it.unhook() }
        logHotReload()
    }

    private fun installGosHook(loader: ClassLoader): GosPerformanceHook? = runCatching {
        GosPerformanceHook(this).also {
            it.install(loader)
            hostLoader = loader
            gosPerformance = it
        }
    }.onFailure { Log.w(TAG, "GOS GPU performance hooks unavailable", it) }.getOrNull()

    private fun installSdhmsHook(loader: ClassLoader): SdhmsGpuThermalHook? {
        if (!SdhmsGpuThermalHook.supportsDevice()) return null
        return runCatching {
            SdhmsGpuThermalHook(this, loader).also {
                it.install()
                hostLoader = loader
                sdhmsGpuThermal = it
            }
        }.onFailure { Log.w(TAG, "SDHMS GPU thermal hooks unavailable", it) }.getOrNull()
    }

    private fun logHotReload() {
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
                        "GameBooster PerformanceMonitor" -> {
                            monitorOverlay?.onWindowAdded(view)
                            runCatching { monitorCapture.onWindowAdded(view) }
                                .onFailure { Log.w(TAG, "Could not initialize monitor capture policy", it) }
                        }
                        InGameMonitorSettings.WINDOW_TITLE -> {
                            boosterGameSession.attach(view.context)
                            inGameSettings.onWindowAdded(view)
                        }
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
                    boosterGameSession.attach(context)
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
        val monitorRequest = MonitorTelemetry.RequestClassifier()

        hook(method).setId("gos-monitor-request").intercept { chain ->
            val arguments = chain.args
            val input = arguments.getOrNull(1) as? String
            if (retired || arguments.firstOrNull() != PERF_REQUEST || input == null ||
                !monitorRequest.matches(input) || users?.isUserUnlocked != true
            ) return@intercept chain.proceed()

            val raw = chain.proceed()
            if (raw !is String || retired) return@intercept raw

            runCatching {
                val settings = displaySettings.settings()
                val showClock = overlay.supported && settings.shows(MonitorItem.GPU_CLOCK)
                val transformed = MonitorTelemetry.update(raw, capacity, settings, showClock)
                if (overlay.supported) {
                    val showPower = settings.shows(MonitorItem.BATTERY_POWER) && overlay.monitorVisible
                    val battery = if (showPower || settings.shows(MonitorItem.BATTERY_TEMPERATURE) ||
                        settings.shows(MonitorItem.BATTERY_LEVEL)
                    ) capacity.battery(includePower = showPower) else null
                    val gameRamKiB = if (settings.shows(MonitorItem.GAME_RAM) && overlay.monitorVisible) {
                        gameRam.readKiB()
                    } else null
                    overlay.submit(MonitorOverlay.Reading(settings, transformed.clockMhz, battery, gameRamKiB))
                }
                if (transformed.cpu != null && loggedCpu.compareAndSet(false, true)) {
                    val sample = transformed.cpu
                    val mode = if (sample.peakRelative) "peak-relative" else "busy time"
                    Log.i(TAG, "CPU usage active (${sample.source}, $mode): ${sample.percentage}%")
                }
                transformed.gpu?.let { sample ->
                    val previous = loggedGpuMode.get()
                    if (previous != sample.peakRelative && loggedGpuMode.compareAndSet(previous, sample.peakRelative)) {
                        val mode = if (sample.peakRelative) "peak-relative" else "busy time"
                        Log.i(TAG, "GPU driver usage active (${sample.source}, $mode): ${sample.percentage}%")
                    }
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
    data class Result(
        val output: String,
        val cpu: DeviceCapacity.CpuSample?,
        val gpu: GpuUsage.Sample?,
        val clockMhz: Int?,
    )

    private const val GPU_LOAD = "gpu_load"
    private const val CPU_LOAD = "cpu_load_total"
    private val monitorMetrics = listOf(CPU_LOAD, GPU_LOAD, "temp_lrpst", "game_fps", "gpu_utilization")

    class RequestClassifier {
        @Volatile private var lastMonitorRequest: String? = null

        fun matches(input: String): Boolean {
            // Game Booster builds the same request JSON on each telemetry tick.
            if (input == lastMonitorRequest) return true
            val matches = runCatching { isMonitorRequest(input) }.getOrDefault(false)
            if (matches) lastMonitorRequest = input
            return matches
        }
    }

    private fun isMonitorRequest(input: String): Boolean {
        val request = JSONObject(input)
        if (request.optInt("session") != -1 || request.optLong("start") >= 0 ||
            request.optLong("end") != 0L
        ) return false

        val entries = request.optJSONArray("params") ?: return false
        val keys = (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.optString("param") }
        return keys.containsAll(monitorMetrics)
    }

    fun update(
        raw: String,
        capacity: DeviceCapacity,
        settings: MonitorSettings,
        showClock: Boolean,
    ): Result {
        val reply = JSONObject(raw)
        val successful = reply.optBoolean("successful")
        val gpu = capacity.gpuReading(
            includeUsage = successful && reply.hasSamples(GPU_LOAD),
            peakRelative = settings.gpuPeakRelative,
            includeClock = showClock,
        )
        val cpu = if (successful && reply.hasSamples(CPU_LOAD)) capacity.cpuUsage(settings.peakRelative) else null
        val cpuChanged = cpu != null && reply.replaceSample(CPU_LOAD, kotlin.math.round(cpu.percentage))
        val gpuChanged = gpu.usage?.let { reply.replaceSample(GPU_LOAD, it.percentage) } == true
        return Result(if (cpuChanged || gpuChanged) reply.toString() else raw, cpu, gpu.usage, gpu.clockMhz)
    }

    private fun JSONObject.replaceSample(key: String, value: Double): Boolean {
        val old = optJSONArray(key)
        // If the existing reply already has the same single numeric sample, keep the original JSON.
        val sample = (old?.opt(0) as? Number)?.toDouble()
        if (old?.length() == 1 && sample == value) return false
        put(key, JSONArray().put(value))
        return true
    }

    private fun JSONObject.hasSamples(key: String): Boolean = (optJSONArray(key)?.length() ?: 0) > 0
}
