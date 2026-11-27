package dev.ryuya.galaxygamemonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuHardwareReadingsTest {
    @Test
    fun normalizesGosRequestsAndKernelCeilings() {
        assertEquals(222, GpuHardwareReadings.toMhz(222))
        assertEquals(222, GpuHardwareReadings.toMhz(222_000))
        assertEquals(1100, GpuHardwareReadings.toMhz(1_100_000_000))
        assertNull(GpuHardwareReadings.toMhz(-1))
    }

    @Test
    fun readsOnlyAnActiveFirmwareTarget() {
        val current = """
            penalty_up: 90          Status: Active
             target_fps:        60          Status: Active
          num_samples_up: 5          Status: Active
        """.trimIndent()
        assertEquals(60, GpuHardwareReadings.parseActiveGmuFps(current))
        assertNull(GpuHardwareReadings.parseActiveGmuFps(current.replace("60          Status: Active", "60          Status: Inactive")))
    }
}
