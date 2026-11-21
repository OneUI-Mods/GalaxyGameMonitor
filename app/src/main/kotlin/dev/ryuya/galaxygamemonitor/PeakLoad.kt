package dev.ryuya.galaxygamemonitor

internal data class CpuPolicy(
    val path: java.io.File,
    val peakKHz: Long,
    val coreCapacities: Map<Int, Long>,
)

internal data class CpuTime(val busy: Long, val total: Long)

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
                val elapsed = current.total - previous.total
                val busy = current.busy - previous.busy
                if (elapsed <= 0 || busy !in 0..elapsed) continue
                work += capacity * (busy.toDouble() / elapsed) * clock
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

    fun gpu(load: Double, clock: Double, peakHz: Long): Double? {
        if (!load.isFinite() || load !in 0.0..100.0 || !clock.isFinite() || clock < 0 || peakHz <= 0) return null
        if (load == 0.0) return 0.0
        if (clock <= 0) return null
        val hz = when {
            clock >= 10_000_000 -> clock
            clock >= 10_000 -> clock * 1_000
            else -> clock * 1_000_000
        }
        if (hz > peakHz * 1.05) return null
        return load * (hz / peakHz).coerceAtMost(1.0)
    }
}
