package dev.ryuya.galaxygamemonitor

import java.io.File

internal data class CpuPolicy(
    val path: File,
    val peakKHz: Long,
    val coreCapacities: Map<Int, Long>,
) {
    val timeInState = File(path, "stats/time_in_state")
    val currentFrequency = File(path, "scaling_cur_freq")
}

internal data class CpuTime(val busy: Long, val total: Long) {
    fun since(previous: CpuTime): CpuTime? {
        val busyDelta = busy - previous.busy
        val totalDelta = total - previous.total
        return CpuTime(busyDelta, totalDelta).takeIf { totalDelta > 0 && busyDelta in 0..totalDelta }
    }

    companion object {
        fun usage(before: Map<Int, CpuTime>, after: Map<Int, CpuTime>): Double? {
            var busy = 0L
            var total = 0L
            for ((core, current) in after) {
                val delta = current.since(before[core] ?: continue) ?: continue
                busy += delta.busy
                total += delta.total
            }
            return if (total > 0) 100.0 * busy / total else null
        }
    }
}

internal data class ClockTime(val frequencyKHz: Long, val ticks: Long)

internal object PeakLoad {
    fun cpu(
        policies: List<CpuPolicy>,
        before: Map<Int, CpuTime>,
        after: Map<Int, CpuTime>,
        clockFractions: List<Double>,
    ): Double? {
        if (policies.isEmpty() || policies.size != clockFractions.size) return null
        var work = 0.0
        var totalCapacity = 0L
        var measuredCores = 0
        for ((index, policy) in policies.withIndex()) {
            val clock = clockFractions[index]
            if (!clock.isFinite() || clock !in 0.0..1.0) return null
            for ((core, capacity) in policy.coreCapacities) {
                totalCapacity += capacity
                val previous = before[core] ?: continue
                val current = after[core] ?: continue
                val delta = current.since(previous) ?: continue
                work += capacity * (delta.busy.toDouble() / delta.total) * clock
                measuredCores++
            }
        }
        if (measuredCores == 0 || totalCapacity <= 0) return null
        return (100 * work / totalCapacity).coerceIn(0.0, 100.0)
    }

    fun averageClock(before: List<ClockTime>, after: List<ClockTime>, peakKHz: Long): Double? {
        if (peakKHz <= 0 || before.isEmpty() || before.size != after.size) return null
        var ticks = 0L
        var weightedKHz = 0.0
        for ((index, current) in after.withIndex()) {
            val previous = before[index]
            val delta = current.ticks - previous.ticks
            if (current.frequencyKHz != previous.frequencyKHz || delta < 0) return null
            ticks += delta
            weightedKHz += current.frequencyKHz.toDouble() * delta
        }
        return if (ticks > 0) (weightedKHz / ticks / peakKHz).coerceIn(0.0, 1.0) else null
    }
}
