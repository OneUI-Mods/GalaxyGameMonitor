package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.HardwarePropertiesManager
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.StringTokenizer

internal class DeviceCapacity(private val context: Context) {
    data class CpuSample(val percentage: Double, val source: String, val peakRelative: Boolean)
    data class BatterySample(val temperatureTenthsC: Int?, val percent: Int?)

    private data class Counters(
        val timeMs: Long,
        val fromHardwareManager: Boolean,
        val cpu: Map<Int, CpuTime>,
        val clocks: List<List<ClockTime>>,
    )

    private val samsungS26UltraJapan = Build.MODEL == "SM-S948Q" && Build.SOC_MODEL == "SM8850"
    private val cpuDirectory = File("/sys/devices/system/cpu")
    private val policies by lazy(::readPolicies)
    private val gpu = GpuUsage()
    private val batteryTempFile = File("/sys/class/power_supply/battery/temp")
    private val canReadBatteryTemp by lazy(batteryTempFile::canRead)
    private val batteryCapacityFile = File("/sys/class/power_supply/battery/capacity")
    private val canReadBatteryCapacity by lazy(batteryCapacityFile::canRead)
    private val procStat = File("/proc/stat")
    private val canReadProcStat by lazy(procStat::canRead)
    private val hardwareManager by lazy { context.getSystemService(HardwarePropertiesManager::class.java) }
    private var previous: Counters? = null
    private var retryHardwareManagerAfterMs = 0L
    private var nextBatteryReadingMs = 0L
    private var lastBattery = BatterySample(null, null)

    @Synchronized
    fun resetCpuSample() {
        previous = null
    }

    @Synchronized
    fun cpuUsage(peakRelative: Boolean): CpuSample? {
        val now = SystemClock.elapsedRealtime()
        val before = previous
        if (before != null && now - before.timeMs < MIN_SAMPLE_MS) return null

        val kernelTimes = if (canReadProcStat) procTimes() else null
        val times = kernelTimes ?: hardwareTimes(now) ?: return null
        val fromHardwareManager = kernelTimes == null
        val clockTimes = if (peakRelative) policies.map(::clockTimes) else emptyList()
        previous = Counters(now, fromHardwareManager, times, clockTimes)
        if (before == null || now - before.timeMs > MAX_SAMPLE_MS ||
            before.fromHardwareManager != fromHardwareManager
        ) return null

        val source = if (fromHardwareManager) "Android hardware service" else "/proc/stat"
        if (peakRelative) {
            peakCpuUsage(before, times, clockTimes)?.let { return CpuSample(it, source, true) }
        }
        return CpuTime.usage(before.cpu, times)?.let { CpuSample(it, source, false) }
    }

    private fun peakCpuUsage(
        before: Counters,
        times: Map<Int, CpuTime>,
        clockTimes: List<List<ClockTime>>,
    ): Double? {
        val fractions = policies.mapIndexed { index, policy ->
            PeakLoad.averageClock(before.clocks.getOrElse(index) { emptyList() }, clockTimes[index], policy.peakKHz)
                ?: currentClockFraction(policy)
                ?: return null
        }
        return PeakLoad.cpu(policies, before.cpu, times, fractions)
    }

    private fun readPolicies(): List<CpuPolicy> {
        val directories = File(cpuDirectory, "cpufreq").listFiles()
            ?.filter { it.name.matches(POLICY_NAME) }
            ?.sortedBy { it.name.removePrefix("policy").toInt() }
        val discovered = directories?.mapNotNull { directory ->
            val maximum = number(File(directory, "cpuinfo_max_freq")) ?: return@mapNotNull null
            val cores = read(File(directory, "related_cpus"))?.split(WHITESPACE)
                ?.mapNotNull(String::toIntOrNull)?.distinct().orEmpty()
            if (cores.isEmpty() || maximum <= 0) return@mapNotNull null
            val capacities = cores.associateWith { core ->
                number(File(cpuDirectory, "cpu$core/cpu_capacity"))?.takeIf { it > 0 } ?: maximum
            }
            CpuPolicy(directory, maximum, capacities)
        }.orEmpty()
        if (discovered.isNotEmpty() && discovered.size == directories?.size) return discovered
        if (!samsungS26UltraJapan) return emptyList()

        // The Japanese S26 Ultra's exposed hardware ceilings and scheduler capacity.
        return listOf(
            CpuPolicy(File(cpuDirectory, "cpufreq/policy0"), 3_628_800, (0..5).associateWith { 741L }),
            CpuPolicy(File(cpuDirectory, "cpufreq/policy6"), 4_742_400, (6..7).associateWith { 1024L }),
        )
    }

    fun gpuUsage(): GpuUsage.Sample? = gpu.read()

    fun gpuClockMhz(): Int? = gpu.clockMhz()

    @Synchronized
    fun battery(): BatterySample {
        val now = SystemClock.elapsedRealtime()
        if (now < nextBatteryReadingMs) return lastBattery
        nextBatteryReadingMs = now + BATTERY_SAMPLE_MS
        val temperature = if (canReadBatteryTemp) number(batteryTempFile)
            ?.takeIf { it in -400..1000 }?.toInt() else null
        val percent = if (canReadBatteryCapacity) number(batteryCapacityFile)
            ?.takeIf { it in 0..100 }?.toInt() else null
        val broadcast = if (temperature == null || percent == null) batteryBroadcast() else null
        val reportedTemperature = broadcast
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it in -400..1000 }
        val reportedPercent = broadcast?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            if (scale > 0 && level in 0..scale) ((100L * level + scale / 2) / scale).toInt() else null
        }
        lastBattery = BatterySample(temperature ?: reportedTemperature, percent ?: reportedPercent)
        return lastBattery
    }

    private fun batteryBroadcast(): Intent? = runCatching {
        context.registerReceiver(null, BATTERY_CHANGED, Context.RECEIVER_NOT_EXPORTED)
    }.getOrNull()?.takeIf { it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) }

    private fun hardwareTimes(now: Long): Map<Int, CpuTime>? {
        if (now < retryHardwareManagerAfterMs) return null
        val times = try {
            hardwareManager?.cpuUsages?.mapIndexedNotNull { index, value ->
                value?.let { index to CpuTime(it.active, it.total) }
            }?.toMap()?.takeIf { it.values.any { sample -> sample.total > 0 } }
        } catch (_: RuntimeException) {
            null
        }
        if (times == null) retryHardwareManagerAfterMs = now + 10_000
        return times
    }

    private fun procTimes(): Map<Int, CpuTime>? = try {
        procStat.useLines { lines ->
            val cores = mutableMapOf<Int, CpuTime>()
            for (line in lines) {
                if (!line.startsWith("cpu")) break
                if (line.length < 4 || line[3] !in '0'..'9') continue
                val fields = StringTokenizer(line)
                val core = fields.nextToken().substring(3).toIntOrNull() ?: continue
                readCpuTime(fields)?.let { cores[core] = it }
            }
            cores.takeIf { it.isNotEmpty() }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readCpuTime(fields: StringTokenizer): CpuTime? {
        var total = 0L
        var idle = 0L
        // Ignore guest counters. Their time is already in user and nice.
        for (index in 0 until 8) {
            if (!fields.hasMoreTokens()) return null
            val ticks = fields.nextToken().toLongOrNull() ?: return null
            total += ticks
            if (index == 3 || index == 4) idle += ticks
        }
        return CpuTime(total - idle, total)
    }

    private fun clockTimes(policy: CpuPolicy): List<ClockTime> = try {
        policy.timeInState.useLines { lines ->
            buildList {
                for (line in lines) {
                    val fields = StringTokenizer(line)
                    if (!fields.hasMoreTokens()) continue
                    val frequency = fields.nextToken().toLongOrNull() ?: continue
                    if (!fields.hasMoreTokens()) continue
                    val ticks = fields.nextToken().toLongOrNull() ?: continue
                    add(ClockTime(frequency, ticks))
                }
            }
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun currentClockFraction(policy: CpuPolicy): Double? =
        number(policy.currentFrequency)?.takeIf { it > 0 }
            ?.let { (it.toDouble() / policy.peakKHz).coerceAtMost(1.0) }

    private fun number(file: File): Long? = read(file)?.toLongOrNull()

    private fun read(file: File): String? = file.readKernelText()?.trim()

    companion object {
        private const val MIN_SAMPLE_MS = 150
        private const val MAX_SAMPLE_MS = 4_000
        private const val BATTERY_SAMPLE_MS = 3_000L
        private val BATTERY_CHANGED = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        private val WHITESPACE = Regex("\\s+")
        private val POLICY_NAME = Regex("policy[0-9]+")
    }
}
