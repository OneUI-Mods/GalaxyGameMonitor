package dev.ryuya.galaxygamemonitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GosPerformanceHookTest {
    @Test fun stripsEveryGpuCeilingAndPreservesOtherPairs() {
        val gpuMax = 536875010
        val request = intArrayOf(gpuMax, 500, 123, 2_112_000, gpuMax, 422, 456, -1)
        assertArrayEquals(intArrayOf(123, 2_112_000, 456, -1), GosPerformanceHook.withoutGpuMax(request))
        assertArrayEquals(intArrayOf(), GosPerformanceHook.withoutGpuMax(intArrayOf(gpuMax, 500)))
    }

    @Test fun reusesTheUnchangedArray() {
        val request = intArrayOf(123, 2_112_000, 456, -1)
        assertSame(request, GosPerformanceHook.withoutGpuMax(request))
    }
}
