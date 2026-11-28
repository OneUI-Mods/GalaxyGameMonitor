package dev.ryuya.galaxygamemonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdhmsGpuPolicyTest {
    @Test fun enablesOnGosBypassTransition() {
        assertTrue(SdhmsGpuPolicy.afterUpdate(false, false, true, null))
    }

    @Test fun preservesManualOverrideUntilNextTransition() {
        assertFalse(SdhmsGpuPolicy.afterUpdate(true, true, true, false))
        assertFalse(SdhmsGpuPolicy.afterUpdate(true, false, true, null))
        assertTrue(SdhmsGpuPolicy.afterUpdate(true, false, true, true))
    }

    @Test fun restoresStockSdhmsGamePolicyWhenGosControlsTheCeiling() {
        assertFalse(SdhmsGpuPolicy.afterUpdate(true, true, false, null))
        assertFalse(SdhmsGpuPolicy.afterUpdate(false, false, false, true))
        assertFalse(SdhmsGpuPolicy.afterUpdate(true, true, false, true))
    }

    @Test fun removesOnlyVerifiedGameGpuRelease() {
        assertTrue(SdhmsGpuPolicy.isGameRelease("SPA", "GPUFreqMax", true, intArrayOf(-1)))
        assertTrue(SdhmsGpuPolicy.isGameRelease("GameSDK", "GPUFreqMax", true, intArrayOf(-1)))
        assertFalse(SdhmsGpuPolicy.isGameRelease("Camera", "GPUFreqMax", true, intArrayOf(-1)))
        assertFalse(SdhmsGpuPolicy.isGameRelease("SPA", "CPUFreqMax", true, intArrayOf(-1)))
        assertFalse(SdhmsGpuPolicy.isGameRelease("SPA", "GPUFreqMax", false, intArrayOf(-1)))
        assertFalse(SdhmsGpuPolicy.isGameRelease("GameSDK", "GPUFreqMax", true, intArrayOf(-1, 578)))
    }
}
