package dev.ryuya.galaxygamemonitor

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpuUsageTest {
    @get:Rule val sysfs = TemporaryFolder()

    @Test fun adrenoUsesBusyCountersBeforeIntegerPercentage() {
        val counters = write("class/kgsl/kgsl-3d0/gpubusy", " 799000 1000000\n")
        write("class/kgsl/kgsl-3d0/gpu_busy_percentage", "79 %")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("gpubusy", 79.9), driverUsage(reader))
        counters.writeText("0 1000000")
        assertEquals(GpuUsage.Sample("gpubusy", 0.0), driverUsage(reader))
        counters.writeText("0 0")
        assertEquals(GpuUsage.Sample("gpu_busy_percentage", 79.0), driverUsage(reader))
    }

    @Test fun samsungKernelReportsBothBareAndSuffixedPercentages() {
        val busy = write("kernel/gpu/gpu_busy", "77\n")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("gpu_busy", 77.0), driverUsage(reader))
        busy.writeText(" 12%\n")
        assertEquals(GpuUsage.Sample("gpu_busy", 12.0), driverUsage(reader))
    }

    @Test fun invalidSamsungUsageFallsThroughToMaliDevice() {
        write("kernel/gpu/gpu_busy", "101")
        val mali = write("class/misc/mali0/device/utilization", "84")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("utilization", 84.0), driverUsage(reader))
        mali.writeText("1 2")
        assertNull(driverUsage(reader))
    }

    @Test fun drmFallbackSelectsTheGpuDriverEvenOnANonzeroCard() {
        write("class/drm/card0/device/gpu_busy_percent", "3")
        write("class/drm/card2/device/gpu_busy_percent", "100")
        val driver = File(sysfs.root, "bus/platform/drivers/sgpu").apply { mkdirs() }
        Files.createSymbolicLink(File(sysfs.root, "class/drm/card2/device/driver").toPath(), driver.toPath())
        assertEquals(GpuUsage.Sample("gpu_busy_percent", 100.0), driverUsage(GpuUsage(sysfs.root)))
    }

    @Test fun gpuClockUsesLiveValuesAndHandlesDriverUnits() {
        val kgsl = write("class/kgsl/kgsl-3d0/gpuclk", "422000000\n")
        val exynos = write("kernel/gpu/gpu_clock", "650000")
        val reader = GpuUsage(sysfs.root)
        assertEquals(422, clockMhz(reader))
        kgsl.writeText("191000000")
        assertEquals(191, clockMhz(reader))
        kgsl.writeText("0")
        assertEquals(650, clockMhz(reader))
        exynos.writeText("846")
        assertEquals(846, clockMhz(reader))
        exynos.writeText("frequency unavailable")
        assertNull(clockMhz(reader))
    }

    @Test fun scalarReadsKeepDataBeyondTheSmallBuffer() {
        val counters = write("class/kgsl/kgsl-3d0/gpubusy", " ".repeat(127) + "80 100\n")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("gpubusy", 80.0), driverUsage(reader))
        counters.writeText("80 100" + " ".repeat(122) + "3")
        assertNull(driverUsage(reader))
    }

    @Test fun adrenoWeightsTheBusyTimeAtEachClockAndIgnoresThermalCapForTheDenominator() {
        write("class/kgsl/kgsl-3d0/gpubusy", "90 100")
        write("class/kgsl/kgsl-3d0/gpu_available_frequencies", "1200000000 600000000")
        write("class/kgsl/kgsl-3d0/max_gpuclk", "600000000")
        write("class/kgsl/kgsl-3d0/gpuclk", "600000000")
        val stats = write("class/kgsl/kgsl-3d0/gpu_clock_stats", "10 20")
        val reader = GpuUsage(sysfs.root)
        val first = reader.sample(includeUsage = true, peakRelative = true, includeClock = true)
        assertEquals(GpuUsage.Reading(GpuUsage.Sample("gpubusy", 45.0, true), 600), first)
        stats.writeText("40 50")
        val weighted = reader.sample(includeUsage = true, peakRelative = true, includeClock = true)
        assertEquals(GpuUsage.Reading(GpuUsage.Sample("gpubusy", 67.5, true), 600), weighted)
        assertEquals(GpuUsage.Sample("gpubusy", 90.0), driverUsage(reader))
        stats.writeText("90 50")
        assertEquals(first, reader.sample(includeUsage = true, peakRelative = true, includeClock = true))
    }

    @Test fun samsungUsesLiveClockWithItsFrequencyTableAndIgnoresTheCurrentUpperLimit() {
        write("kernel/gpu/gpu_busy", "100")
        write("kernel/gpu/gpu_freq_table", "400000 800000")
        write("kernel/gpu/gpu_max_clock", "400000")
        val clock = write("kernel/gpu/gpu_clock", "400000")
        val reader = GpuUsage(sysfs.root)
        val sample = reader.sample(includeUsage = true, peakRelative = true, includeClock = false)
        assertEquals(GpuUsage.Reading(GpuUsage.Sample("gpu_busy", 50.0, true), null), sample)
        clock.writeText("0")
        val unavailable = reader.sample(includeUsage = true, peakRelative = true, includeClock = true)
        assertEquals(GpuUsage.Reading(GpuUsage.Sample("gpu_busy", 100.0), null), unavailable)
    }

    @Test fun rawUsageIsRetainedIfOnlyAThermalCapIsAvailable() {
        write("class/kgsl/kgsl-3d0/gpubusy", "90 100")
        write("class/kgsl/kgsl-3d0/max_gpuclk", "600000000")
        write("class/kgsl/kgsl-3d0/gpuclk", "600000000")
        val sample = GpuUsage(sysfs.root).sample(includeUsage = true, peakRelative = true, includeClock = true)
        assertEquals(GpuUsage.Reading(GpuUsage.Sample("gpubusy", 90.0), 600), sample)
    }

    private fun driverUsage(reader: GpuUsage) =
        reader.sample(includeUsage = true, peakRelative = false, includeClock = false).usage

    private fun clockMhz(reader: GpuUsage) =
        reader.sample(includeUsage = false, peakRelative = false, includeClock = true).clockMhz

    private fun write(path: String, contents: String): File = File(sysfs.root, path).apply {
        parentFile?.mkdirs()
        writeText(contents)
    }
}
