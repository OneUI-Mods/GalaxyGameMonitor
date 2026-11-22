package dev.ryuya.galaxygamemonitor

import java.io.File
import java.io.IOException
import java.util.StringTokenizer
import kotlin.math.roundToInt

internal class GpuUsage(private val sysfs: File = File("/sys")) {
    data class Sample(val source: String, val percentage: Double, val peakRelative: Boolean = false)
    data class Reading(val usage: Sample?, val clockMhz: Int?)

    private val kgslCounters = File(sysfs, "class/kgsl/kgsl-3d0/gpubusy")
    private val percentageFiles by lazy {
        listOf(
            File(sysfs, "class/kgsl/kgsl-3d0/gpu_busy_percentage"),
            File(sysfs, "kernel/gpu/gpu_busy"),
            File(sysfs, "class/misc/mali0/device/utilization"),
        ).filter(File::canRead)
    }
    private val drmDevices by lazy {
        File(sysfs, "class/drm").listFiles().orEmpty()
            .filter { DRM_CARD.matches(it.name) }
            .sortedBy { it.name }
            .map { File(it, "device") }
            .filter { device ->
                val driver = File(device, "driver").canonicalOrNull()?.name
                driver == "sgpu" || driver == "amdgpu"
            }
    }
    private val drmPercentageFiles by lazy { drmDevices.map { File(it, "gpu_busy_percent") }.filter(File::canRead) }
    private val clockFiles by lazy {
        listOf(
            File(sysfs, "class/kgsl/kgsl-3d0/gpuclk"),
            File(sysfs, "class/kgsl/kgsl-3d0/clock_mhz"),
            File(sysfs, "kernel/gpu/gpu_clock"),
            File(sysfs, "class/misc/mali0/device/clock"),
        ).filter(File::canRead)
    }
    private val gpuDevfreq by lazy {
        val drm = drmDevices.flatMap { File(it, "devfreq").listFiles().orEmpty().asList() }
        drm + File(sysfs, "class/misc/mali0/device/devfreq").listFiles().orEmpty()
    }
    private val devfreqClockFiles by lazy { gpuDevfreq.map { File(it, "cur_freq") }.filter(File::canRead) }
    private val canReadKgslCounters by lazy(kgslCounters::canRead)
    private val kgslTable by lazy {
        readFrequencyTable(File(kgslCounters.parentFile, "gpu_available_frequencies"))
            ?: readFrequencyTable(File(kgslCounters.parentFile, "freq_table_mhz"))
    }
    private val peakClockHz by lazy {
        kgslTable?.maxOrNull()
            ?: readFrequencyTable(File(sysfs, "kernel/gpu/gpu_freq_table"))?.maxOrNull()
            ?: gpuDevfreq.firstNotNullOfOrNull { readFrequencyTable(File(it, "available_frequencies"))?.maxOrNull() }
    }
    private val kgslBusyClocks by lazy {
        val stats = File(kgslCounters.parentFile, "gpu_clock_stats")
        kgslTable?.takeIf { stats.canRead() }?.let { BusyClocks(stats, it) }
    }
    private var activeBusyClocks: BusyClocks? = null

    @Synchronized
    fun sample(includeUsage: Boolean, peakRelative: Boolean, includeClock: Boolean): Reading {
        val usage = if (includeUsage) read() else null
        val peak = if (peakRelative && usage != null) peakClockHz else null
        val busyClocks = if (peak != null) kgslBusyClocks else null
        if (activeBusyClocks !== busyClocks) {
            activeBusyClocks?.reset()
            activeBusyClocks = busyClocks
        }
        val average = busyClocks?.readAverageHz()
        val live = if (includeClock || (peak != null && average == null)) readClockHz() else null
        val adjusted = if (peak != null && usage != null) {
            val frequency = average ?: live?.toDouble()
            if (frequency == null) usage else {
                usage.copy(percentage = usage.percentage * (frequency / peak).coerceIn(0.0, 1.0), peakRelative = true)
            }
        } else usage

        return Reading(adjusted, if (includeClock) live?.let { (it / 1_000_000.0).roundToInt() } else null)
    }

    private fun read(): Sample? {
        if (canReadKgslCounters) {
            val usage = kgslCounters.readKernelText()?.let(::busyPercentage)
            if (usage != null) return Sample(kgslCounters.name, usage)
        }
        return percentageFiles.firstNotNullOfOrNull(::readPercentage)
            ?: drmPercentageFiles.firstNotNullOfOrNull(::readPercentage)
    }

    private fun readClockHz(): Long? = clockFiles.firstNotNullOfOrNull(::readClockHz)
        ?: devfreqClockFiles.firstNotNullOfOrNull(::readClockHz)

    private fun readClockHz(file: File): Long? =
        file.readKernelText()?.trim()?.toLongOrNull()?.let(::frequencyHz)

    private fun readFrequencyTable(file: File): LongArray? {
        val text = file.readKernelText(384) ?: return null
        val fields = StringTokenizer(text)
        val table = LongArray(fields.countTokens())
        for (index in table.indices) {
            val raw = fields.nextToken().toLongOrNull() ?: return null
            table[index] = frequencyHz(raw) ?: return null
        }
        return table.takeIf { it.isNotEmpty() }
    }

    private fun frequencyHz(value: Long): Long? {
        // KGSL and Samsung nodes expose Hz, kHz or MHz depending on the driver.
        return when (value) {
            in 1..5_000 -> value * 1_000_000
            in 10_000..5_000_000 -> value * 1_000
            in 10_000_000..5_000_000_000 -> value
            else -> null
        }
    }

    private fun readPercentage(file: File): Sample? {
        val percentage = file.readKernelText()?.trim()?.removeSuffix("%")?.trim()?.toIntOrNull()
        return percentage?.takeIf { it in 0..100 }?.let { Sample(file.name, it.toDouble()) }
    }

    private fun busyPercentage(counters: String): Double? {
        val times = StringTokenizer(counters)
        if (!times.hasMoreTokens()) return null
        val busy = times.nextToken().toLongOrNull() ?: return null
        if (!times.hasMoreTokens()) return null
        val total = times.nextToken().toLongOrNull() ?: return null
        if (times.hasMoreTokens() || total <= 0 || busy !in 0..total) return null
        return 100.0 * busy / total
    }

    private class BusyClocks(private val stats: File, private val frequencies: LongArray) {
        private var before = LongArray(frequencies.size)
        private var current = LongArray(frequencies.size)
        private var sampledAt = 0L
        private var hasSample = false

        fun reset() {
            hasSample = false
        }

        fun readAverageHz(): Double? {
            val text = stats.readKernelText(384)
            if (text == null || !readTicks(text)) {
                reset()
                return null
            }

            val now = System.nanoTime()
            val average = if (hasSample && now - sampledAt <= MAX_INTERVAL_NS) averageHz() else null
            val old = before
            before = current
            current = old
            sampledAt = now
            hasSample = true
            return average
        }

        private fun readTicks(text: String): Boolean {
            val fields = StringTokenizer(text)
            for (index in current.indices) {
                if (!fields.hasMoreTokens()) return false
                val ticks = fields.nextToken().toLongOrNull() ?: return false
                if (ticks < 0) return false
                current[index] = ticks
            }
            return !fields.hasMoreTokens()
        }

        private fun averageHz(): Double? {
            var busyTicks = 0.0
            var weighted = 0.0
            for (index in frequencies.indices) {
                val delta = current[index] - before[index]
                if (delta < 0) return null
                busyTicks += delta
                weighted += delta.toDouble() * frequencies[index]
            }
            return if (busyTicks > 0) weighted / busyTicks else null
        }
    }

    private fun File.canonicalOrNull(): File? = try {
        canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        val DRM_CARD = Regex("card[0-9]+")
        const val MAX_INTERVAL_NS = 4_000_000_000L
    }
}
