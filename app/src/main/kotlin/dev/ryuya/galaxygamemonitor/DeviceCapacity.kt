package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import android.os.SystemClock
import java.io.File
import java.io.IOException

internal class DeviceCapacity(private val context: Context) {
    private data class Counters(
        val timeMs: Long,
        val fromHardwareManager: Boolean,
        val cpu: Map<Int, CpuTime>,
        val clocks: List<List<ClockTime>>,
    )

    private val samsungS26UltraJapan = Build.MODEL == "SM-S948Q" && Build.SOC_MODEL == "SM8850"
    private val cpuDirectory = File("/sys/devices/system/cpu")
    private val policies by lazy(::readPolicies)
    val gpuPeakHz by lazy(::readGpuPeak)
    private val hardwareManager by lazy { context.getSystemService(HardwarePropertiesManager::class.java) }
    private var previous: Counters? = null
    private var useHardwareManager = true

    @Synchronized
    fun resetCpuSample() {
        previous = null
    }

    @Synchronized
    fun cpuUsage(): Double? {
        if (policies.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        val before = previous
        if (before != null && now - before.timeMs < MIN_SAMPLE_MS) return null

        val cpu = if (useHardwareManager) hardwareTimes() else null
        if (cpu == null) useHardwareManager = false
        val times = cpu ?: procTimes() ?: return null
        val clockTimes = policies.map(::clockTimes)
        previous = Counters(now, useHardwareManager, times, clockTimes)
        if (before == null || now - before.timeMs > MAX_SAMPLE_MS ||
            before.fromHardwareManager != useHardwareManager
        ) return null

        val fractions = policies.mapIndexed { index, policy ->
            PeakLoad.averageClock(before.clocks[index], clockTimes[index], policy.peakKHz)
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

    private fun readGpuPeak(): Long? {
        // Available frequencies are the hardware table. The scaling limit can change with heat.
        val kgsl = read(File("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies"))
            ?.split(WHITESPACE)?.mapNotNull(String::toLongOrNull)?.maxOrNull()
        if (kgsl != null && kgsl >= 10_000_000) return kgsl
        val samsung = read(File("/sys/kernel/gpu/gpu_freq_table"))
            ?.split(WHITESPACE)?.mapNotNull(String::toLongOrNull)?.maxOrNull()
        if (samsung != null && samsung in 100..5_000) return samsung * 1_000_000
        return if (samsungS26UltraJapan) 1_300_000_000 else null
    }

    private fun hardwareTimes(): Map<Int, CpuTime>? =
        try {
            hardwareManager?.cpuUsages?.mapIndexedNotNull { index, value ->
                value?.let { index to CpuTime(it.active, it.total) }
            }?.toMap()?.takeIf { it.values.any { sample -> sample.total > 0 } }
        } catch (_: RuntimeException) {
            null
        }

    private fun procTimes(): Map<Int, CpuTime>? {
        return read(File("/proc/stat"))?.lineSequence()?.mapNotNull { line ->
            if (!line.startsWith("cpu") || line.length < 4 || !line[3].isDigit()) return@mapNotNull null
            val columns = line.split(WHITESPACE)
            val core = columns.first().removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
            val times = columns.drop(1).take(8).mapNotNull(String::toLongOrNull)
            if (times.size != 8) return@mapNotNull null
            val total = times.sum()
            core to CpuTime(total - times[3] - times[4], total)
        }?.toMap()?.takeIf(Map<Int, CpuTime>::isNotEmpty)
    }

    private fun clockTimes(policy: CpuPolicy): List<ClockTime> =
        read(File(policy.path, "stats/time_in_state"))?.lineSequence()?.mapNotNull { line ->
            val pair = line.trim().split(WHITESPACE)
            val frequency = pair.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val ticks = pair.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            ClockTime(frequency, ticks)
        }?.toList().orEmpty()

    private fun currentClockFraction(policy: CpuPolicy): Double? =
        number(File(policy.path, "scaling_cur_freq"))?.takeIf { it > 0 }
            ?.let { (it.toDouble() / policy.peakKHz).coerceAtMost(1.0) }

    private fun number(file: File): Long? = read(file)?.toLongOrNull()

    private fun read(file: File): String? =
        try {
            file.readText().trim()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    companion object {
        private const val MIN_SAMPLE_MS = 150
        private const val MAX_SAMPLE_MS = 4_000
        private val WHITESPACE = Regex("\\s+")
        private val POLICY_NAME = Regex("policy[0-9]+")
    }
}
