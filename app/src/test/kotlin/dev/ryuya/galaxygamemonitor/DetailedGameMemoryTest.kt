package dev.ryuya.galaxygamemonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailedGameMemoryTest {
    @Test fun subtractsProportionalSwapWithoutAddingGraphicsAgain() {
        val output = """
            ** MEMINFO in pid 17437 [com.sega.pjsekai] **
                        Graphics:    642024                        642028
                       TOTAL PSS:   2397042           TOTAL RSS:  1835408       TOTAL SWAP PSS:   673679
        """.trimIndent()
        assertEquals(1_723_363L, DetailedGameMemory.parseResidentPss(output.lineSequence(), intArrayOf(17437)))
    }

    @Test fun sumsMatchingProcessesAndIgnoresRawSwapAlreadyExcludedByAndroid() {
        val output = """
            ** MEMINFO in pid 123 [game] **
                       TOTAL PSS: 1002 TOTAL RSS: 1200 TOTAL SWAP (KB): 500
            ** MEMINFO in pid 456 [game:renderer] **
                       TOTAL PSS: 348 TOTAL RSS: 269 TOTAL SWAP PSS: 148
            ** MEMINFO in pid 789 [another.app] **
                       TOTAL PSS: 600 TOTAL RSS: 900 TOTAL SWAP PSS: 300
        """.trimIndent()
        assertEquals(1_202L, DetailedGameMemory.parseResidentPss(output.lineSequence(), intArrayOf(456, 123, 123)))
        assertNull(DetailedGameMemory.parseResidentPss(output.lineSequence(), intArrayOf(123, 999)))
    }
}
