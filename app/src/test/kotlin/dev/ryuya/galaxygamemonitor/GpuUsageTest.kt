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
        assertEquals(GpuUsage.Sample("gpubusy", 79.9), reader.read())
        counters.writeText("0 1000000")
        assertEquals(GpuUsage.Sample("gpubusy", 0.0), reader.read())
        counters.writeText("0 0")
        assertEquals(GpuUsage.Sample("gpu_busy_percentage", 79.0), reader.read())
    }

    @Test fun samsungKernelReportsBothBareAndSuffixedPercentages() {
        val busy = write("kernel/gpu/gpu_busy", "77\n")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("gpu_busy", 77.0), reader.read())
        busy.writeText(" 12%\n")
        assertEquals(GpuUsage.Sample("gpu_busy", 12.0), reader.read())
    }

    @Test fun invalidSamsungUsageFallsThroughToMaliDevice() {
        write("kernel/gpu/gpu_busy", "101")
        val mali = write("class/misc/mali0/device/utilization", "84")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("utilization", 84.0), reader.read())
        mali.writeText("1 2")
        assertNull(reader.read())
    }

    @Test fun drmFallbackSelectsTheGpuDriverEvenOnANonzeroCard() {
        write("class/drm/card0/device/gpu_busy_percent", "3")
        write("class/drm/card2/device/gpu_busy_percent", "100")
        val driver = File(sysfs.root, "bus/platform/drivers/sgpu").apply { mkdirs() }
        Files.createSymbolicLink(File(sysfs.root, "class/drm/card2/device/driver").toPath(), driver.toPath())
        assertEquals(GpuUsage.Sample("gpu_busy_percent", 100.0), GpuUsage(sysfs.root).read())
    }

    @Test fun gpuClockUsesLiveValuesAndHandlesDriverUnits() {
        val kgsl = write("class/kgsl/kgsl-3d0/gpuclk", "422000000\n")
        val exynos = write("kernel/gpu/gpu_clock", "650000")
        val reader = GpuUsage(sysfs.root)
        assertEquals(422, reader.clockMhz())
        kgsl.writeText("191000000")
        assertEquals(191, reader.clockMhz())
        kgsl.writeText("0")
        assertEquals(650, reader.clockMhz())
        exynos.writeText("846")
        assertEquals(846, reader.clockMhz())
        exynos.writeText("frequency unavailable")
        assertNull(reader.clockMhz())
    }

    @Test fun scalarReadsKeepDataBeyondTheSmallBuffer() {
        val counters = write("class/kgsl/kgsl-3d0/gpubusy", " ".repeat(127) + "80 100\n")
        val reader = GpuUsage(sysfs.root)
        assertEquals(GpuUsage.Sample("gpubusy", 80.0), reader.read())
        counters.writeText("80 100" + " ".repeat(122) + "3")
        assertNull(reader.read())
    }

    private fun write(path: String, contents: String): File = File(sysfs.root, path).apply {
        parentFile?.mkdirs()
        writeText(contents)
    }
}
