package dev.ryuya.galaxygamemonitor

import java.io.File
import java.io.IOException

internal class GpuUsage(private val sysfs: File = File("/sys")) {
    data class Sample(val source: String, val percentage: Double)

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
    private val drmClockFiles by lazy {
        drmDevices.flatMap { File(it, "devfreq").listFiles().orEmpty().asList() }
            .map { File(it, "cur_freq") }
            .filter(File::canRead)
    }
    private val canReadKgslCounters by lazy(kgslCounters::canRead)

    fun read(): Sample? {
        if (canReadKgslCounters) {
            val usage = kgslCounters.readKernelText()?.let(::busyPercentage)
            if (usage != null) return Sample(kgslCounters.name, usage)
        }
        return percentageFiles.firstNotNullOfOrNull(::readPercentage)
            ?: drmPercentageFiles.firstNotNullOfOrNull(::readPercentage)
    }

    fun clockMhz(): Int? = clockFiles.firstNotNullOfOrNull(::readClockMhz)
        ?: drmClockFiles.firstNotNullOfOrNull(::readClockMhz)

    private fun readClockMhz(file: File): Int? {
        val frequency = file.readKernelText()?.trim()?.toLongOrNull() ?: return null
        // KGSL exposes Hz and MHz. Samsung uses MHz on Mali and kHz or Hz on Xclipse.
        val mhz = when (frequency) {
            in 1..5_000 -> frequency
            in 10_000..5_000_000 -> (frequency + 500) / 1_000
            in 10_000_000..5_000_000_000 -> (frequency + 500_000) / 1_000_000
            else -> return null
        }
        return mhz.toInt()
    }

    private fun readPercentage(file: File): Sample? {
        val percentage = file.readKernelText()?.trim()?.removeSuffix("%")?.trim()?.toIntOrNull()
        return percentage?.takeIf { it in 0..100 }?.let { Sample(file.name, it.toDouble()) }
    }

    private fun busyPercentage(counters: String): Double? {
        val times = counters.trim().split(WHITESPACE, limit = 3)
        if (times.size != 2) return null
        val busy = times[0].toLongOrNull() ?: return null
        val total = times[1].toLongOrNull() ?: return null
        if (total <= 0 || busy !in 0..total) return null
        return 100.0 * busy / total
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
        val WHITESPACE = Regex("\\s+")
    }
}
