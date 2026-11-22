package dev.ryuya.galaxygamemonitor

import java.io.IOException
import java.util.concurrent.TimeUnit

internal object DetailedGameMemory {
    // This is the same process summary that adb dumpsys meminfo reports. Collect it inside
    // system_server so the game does not have to handle a dump on its own main thread.
    fun readKiB(pids: IntArray): Long? {
        require(pids.isNotEmpty() && pids.size <= MAX_PROCESSES && pids.all { it > 0 })
        val processIds = pids.distinct().joinToString(" ")
        val dump = "/system/bin/dumpsys -t 2 meminfo --local -s"
        val command = if (pids.size == 1) {
            // The single-process summary fits in the pipe. Replace the shell with dumpsys.
            "exec $dump $processIds 2>/dev/null"
        } else {
            // Bound the combined output when a game has multiple processes.
            """
                for pid in $processIds; do
                    $dump "${'$'}pid" 2>/dev/null |
                        /system/bin/grep -E 'MEMINFO in pid|TOTAL PSS:'
                done
            """.trimIndent()
        }
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        return try {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { parseResidentPss(it.lineSequence(), pids) }
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun parseResidentPss(lines: Sequence<String>, pids: IntArray): Long? {
        val expected = pids.toSet()
        val samples = mutableMapOf<Int, Long>()
        var pid: Int? = null
        for (line in lines) {
            val header = pidHeader.find(line)
            if (header != null) {
                pid = header.groupValues[1].toIntOrNull()?.takeIf(expected::contains)
                continue
            }

            val current = pid ?: continue
            val total = totalPss.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            // The alternative TOTAL SWAP (KB) field is already excluded from TOTAL PSS.
            val swap = swapPss.find(line)?.groupValues?.get(1)?.toLongOrNull()
                ?: if (swapSize.containsMatchIn(line)) 0L else continue
            if (total >= swap) samples[current] = total - swap
            pid = null
        }
        return samples.values.sum().takeIf { samples.keys == expected && it > 0L }
    }

    const val MAX_PROCESSES = 16
    private val pidHeader = Regex("^\\*\\* MEMINFO in pid (\\d+) \\[")
    private val totalPss = Regex("^\\s*TOTAL PSS:\\s+(\\d+)")
    private val swapPss = Regex("TOTAL SWAP PSS:\\s+(\\d+)")
    private val swapSize = Regex("TOTAL SWAP \\(KB\\):\\s+\\d+")
}
