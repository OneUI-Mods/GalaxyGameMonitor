package dev.ryuya.galaxygamemonitor

internal data class CpuPolicy(
    val path: java.io.File,
    val peakKHz: Long,
    val coreCapacities: Map<Int, Long>,
)

internal data class CpuTime(val busy: Long, val total: Long)

internal data class ClockTime(val frequencyKHz: Long, val ticks: Long)

internal object PeakLoad {
    private val WHITESPACE = Regex("\\s+")

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

    fun gpuBusyPercentage(counters: String): Double? {
        val times = counters.trim().split(WHITESPACE, limit = 3)
        if (times.size != 2) return null
        val busy = times[0].toLongOrNull() ?: return null
        val total = times[1].toLongOrNull() ?: return null
        if (total <= 0 || busy !in 0..total) return null
        return 100.0 * busy / total
    }
}
