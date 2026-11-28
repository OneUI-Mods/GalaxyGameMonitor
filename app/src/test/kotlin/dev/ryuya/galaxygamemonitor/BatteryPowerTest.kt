package dev.ryuya.galaxygamemonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPowerTest {
    @Test
    fun displaysBatteryDischargeAsPositiveWatts() {
        assertEquals(60, batteryDischargeTenthsW(4_000, -1_500_000))
    }

    @Test
    fun displaysChargingAsNegativeWatts() {
        assertEquals(-128, batteryDischargeTenthsW(4_460, 2_873_437))
    }

    @Test
    fun keepsZeroAndRejectsUnavailableReadings() {
        assertEquals(0, batteryDischargeTenthsW(4_000, 0))
        assertNull(batteryDischargeTenthsW(4_000, Int.MIN_VALUE))
        assertNull(batteryDischargeTenthsW(0, -1_500_000))
    }
}
