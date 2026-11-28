package dev.ryuya.galaxygamemonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdhmsGpuOrphanCleanupTest {
    @Test fun readsOnlyUntimedSdhmsGpuRequestsFromTheActiveList() {
        val snapshot = """
            ================================================
            REQ TIME           | PID    | REQID         | TIMEOUT => REQ_INFO
            ------------------------------------------------
            01/15 12:30:00.100 | 17500  |    153221270  | N/A(295) => [GPUMaxFreq  1200]     #com.sec.android.sdhms[N/A]
            01/15 12:28:30.100 | 12000  |    72066020  | N/A(90295) => [GPUMaxFreq  422]     #com.sec.android.sdhms[N/A]
            01/15 12:30:00.100 | 12000  |    72066021  | 2000(295) => [GPUMaxFreq  422]     #com.sec.android.sdhms[N/A]
            01/15 12:30:00.100 | 12000  |    72066022  | N/A(295) => [GPUMaxFreq  422, CPUMaxFreq  1000]     #com.sec.android.sdhms[N/A]
            01/15 12:30:00.100 | 12000  |    72066023  | N/A(295) => [GPUMaxFreq  422]     #com.samsung.android.game.gos[N/A]
            ================================================
            01/15 12:30:00.100 | 12000  |    72066024  | N/A(295) => [GPUMaxFreq  422]     #com.sec.android.sdhms[N/A]
        """.trimIndent()
        assertEquals(
            listOf(
                SdhmsGpuOrphanCleanup.Request(17500, 153221270, 1200),
                SdhmsGpuOrphanCleanup.Request(12000, 72066020, 422),
            ),
            SdhmsGpuOrphanCleanup.parseActiveRequests(snapshot),
        )
    }

    @Test fun requiresACompleteActiveList() {
        assertNull(SdhmsGpuOrphanCleanup.parseActiveRequests("REQ TIME | PID"))
    }

    @Test fun derivesReleaseSlotFromConfirmedHalEncoding() {
        assertEquals(5, SdhmsGpuOrphanCleanup.releaseSlot(12000, 72066020))
        assertEquals(5, SdhmsGpuOrphanCleanup.releaseSlot(17500, 153221270))
        assertNull(SdhmsGpuOrphanCleanup.releaseSlot(12000, 72066021))
        assertNull(SdhmsGpuOrphanCleanup.releaseSlot(0, 72066020))
    }
}
