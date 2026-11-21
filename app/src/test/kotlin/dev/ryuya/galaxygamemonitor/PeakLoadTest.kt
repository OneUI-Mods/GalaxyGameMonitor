package dev.ryuya.galaxygamemonitor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeakLoadTest {
    private val policies = listOf(
        CpuPolicy(File("policy0"), 3_628_800, mapOf(0 to 741, 1 to 741)),
        CpuPolicy(File("policy6"), 4_742_400, mapOf(2 to 1024)),
    )
    private val before = (0..2).associateWith { CpuTime(100, 200) }
    private val fullyBusy = (0..2).associateWith { CpuTime(200, 300) }

    @Test fun allCoresAtTheirPeakAreOneHundredPercent() {
        assertEquals(100.0, PeakLoad.cpu(policies, before, fullyBusy, listOf(1.0, 1.0))!!, 0.001)
    }

    @Test fun throttledAndOfflineCoresStillCountTowardThePeak() {
        val after = mapOf(0 to CpuTime(150, 300), 1 to CpuTime(170, 300))
        val expected = 100 * (741 * 0.5 * 0.5 + 741 * 0.7 * 0.5) / (741 + 741 + 1024)
        assertEquals(expected, PeakLoad.cpu(policies, before, after, listOf(0.5, 1.0))!!, 0.001)
    }

    @Test fun clockResidencyUsesCounterDeltas() {
        val before = listOf(ClockTime(1_000, 80), ClockTime(2_000, 20))
        val after = listOf(ClockTime(1_000, 90), ClockTime(2_000, 50))
        assertEquals(0.875, PeakLoad.averageClock(before, after, 2_000)!!, 0.001)
        assertNull(PeakLoad.averageClock(after, before, 2_000))
    }

    @Test fun gpuBusyAtHalfThePeakRepresentsHalfThePeakWork() {
        val peak = 1_300_000_000L
        assertEquals(45.0, PeakLoad.gpu(90.0, 650.0, peak)!!, 0.001)
        assertEquals(45.0, PeakLoad.gpu(90.0, 650_000.0, peak)!!, 0.001)
        assertEquals(45.0, PeakLoad.gpu(90.0, 650_000_000.0, peak)!!, 0.001)
        assertEquals(0.0, PeakLoad.gpu(0.0, 0.0, peak)!!, 0.001)
        assertNull(PeakLoad.gpu(90.0, 0.0, peak))
    }
}
