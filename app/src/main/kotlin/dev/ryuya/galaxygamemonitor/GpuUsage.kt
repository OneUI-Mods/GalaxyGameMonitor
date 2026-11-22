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
    private val drmPercentageFiles by lazy {
        File(sysfs, "class/drm").listFiles().orEmpty()
            .filter { DRM_CARD.matches(it.name) }
            .sortedBy { it.name }
            .mapNotNull { card ->
                val device = File(card, "device")
                val driver = File(device, "driver").canonicalOrNull()?.name
                if (driver != "sgpu" && driver != "amdgpu") return@mapNotNull null
                File(device, "gpu_busy_percent").takeIf(File::canRead)
            }
    }
    private val canReadKgslCounters by lazy(kgslCounters::canRead)

    fun read(): Sample? {
        if (canReadKgslCounters) {
            val usage = kgslCounters.contents()?.let(::busyPercentage)
            if (usage != null) return Sample(kgslCounters.name, usage)
        }
        return percentageFiles.firstNotNullOfOrNull(::readPercentage)
            ?: drmPercentageFiles.firstNotNullOfOrNull(::readPercentage)
    }

    private fun readPercentage(file: File): Sample? {
        val percentage = file.contents()?.trim()?.removeSuffix("%")?.trim()?.toIntOrNull()
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

    private fun File.contents(): String? = try {
        readText()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
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
