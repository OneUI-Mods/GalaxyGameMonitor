package dev.ryuya.galaxygamemonitor

import java.io.File

internal object GpuHardwareReadings {
    private val dcvs = File("/sys/class/kgsl/kgsl-3d0/gmu/dcvs")
    private val aggregate = File(dcvs, "aggregated_max_gpuclk")
    private val samsungLimit = File("/sys/kernel/gpu/gpu_max_clock")
    private val tunables = File(dcvs, "dcvs_tunables_cur")
    private val activeFps = Regex("(?m)^\\s*target_fps:[ \\t]*(\\d+)[ \\t]+Status:[ \\t]+Active[ \\t]*$")

    data class Ceiling(val mhz: Int, val aggregated: Boolean)

    fun ceiling(): Ceiling? = readClock(aggregate)?.let { Ceiling(it, true) }
        ?: readClock(samsungLimit)?.let { Ceiling(it, false) }

    fun effectiveGmuFps(): Int? = tunables.readKernelText()?.let(::parseActiveGmuFps)

    internal fun parseActiveGmuFps(reading: String): Int? =
        activeFps.find(reading)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    private fun readClock(file: File): Int? = file.readKernelText()?.trim()?.toLongOrNull()?.let(::toMhz)

    fun toMhz(value: Long): Int? = when (value) {
        in 1..9_999 -> value.toInt()
        in 10_000..9_999_999 -> (value / 1_000).toInt()
        in 10_000_000..9_999_999_999 -> (value / 1_000_000).toInt()
        else -> null
    }
}
