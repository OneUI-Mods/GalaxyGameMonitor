package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Removes single-resource GPU requests left behind by terminated SDHMS processes. */
internal object SdhmsGpuOrphanCleanup {
    enum class Status { CHECKING, CLEAN, CLEARED, ROOT_REQUIRED, FAILED }

    private const val TAG = "GalaxyGameMonitor"
    private const val SERVICE = "vendor.samsung.hardware.hyper.ISehHyPer/default"
    private const val HAL_PROCESS = "vendor.samsung.hardware.hyper-service"
    private const val ROOT_MARKER = "SDHMS_GPU_ROOT_OK"
    private const val DUMP_MARKER = "****HyPerDump****"
    private const val MAX_DUMP_BYTES = 256 * 1024
    private const val MAX_CANDIDATES = 16

    // The release transaction and request-ID encoding were checked against this HAL.
    private val verifiedFiles = mapOf(
        "/vendor/lib64/vendor.samsung.hardware.hyper-V2-ndk.so" to "318402fb62bef265421af04b225d949438dd7a04b4c0aa4777c89b62a0b4c130",
        "/vendor/lib64/libSamsungHyper.so" to "893aa6e25db0c40ad00a00f3aa5e7b04797f2034026500395001f0ecc9f409d1",
        "/vendor/bin/hw/vendor.samsung.hardware.hyper-service" to "736c5169194e09ab6812a867fe5c983db2c60cb2847d3ba0882f490edda16aac",
    )

    private val activeRow = Regex(
        """\d{2}/\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*N/A\(\d+\)\s*=>\s*\[GPUMaxFreq\s+(\d+)]\s+#com\.sec\.android\.sdhms\[N/A]""",
    )

    internal data class Request(val pid: Int, val id: Int, val mhz: Int)
    private data class Snapshot(val halPid: Int, val requests: List<Request>)
    private data class RootResult(val exitCode: Int, val output: String) {
        val elevated: Boolean get() = output.lineSequence().firstOrNull() == ROOT_MARKER
    }

    fun run(context: Context, sdhmsPid: Int): Status {
        if (sdhmsPid <= 0 || !SdhmsGpuThermalHook.supportsDevice()) return Status.FAILED
        return runCatching { inspectAndRelease(context, sdhmsPid) }.getOrElse {
            Log.w(TAG, "Could not check for orphaned SDHMS GPU requests: ${it.javaClass.simpleName}")
            Status.FAILED
        }
    }

    private fun inspectAndRelease(context: Context, sdhmsPid: Int): Status {
        val before = readDump(context, sdhmsPid)
        if (!before.elevated) return Status.ROOT_REQUIRED
        val snapshot = parseSnapshot(before) ?: return Status.FAILED
        val candidates = snapshot.requests.filter { it.pid != sdhmsPid }
        if (candidates.isEmpty()) return Status.CLEAN
        if (candidates.size > MAX_CANDIDATES) return Status.FAILED

        val release = buildString {
            appendLine("set -e")
            for ((path, hash) in verifiedFiles) {
                appendLine("digest=$(/system/bin/sha256sum '$path')")
                appendLine("[ \"${'$'}{digest%% *}\" = '$hash' ]")
            }
            for (request in candidates) {
                val slot = releaseSlot(request.pid, request.id)
                appendLine("if [ ! -d /proc/${request.pid} ] && ! kill -0 ${request.pid} 2>/dev/null; then")
                appendLine(processGuard(sdhmsPid, snapshot.halPid))
                if (slot == null) {
                    appendLine("  exit 3")
                } else {
                    // Release one request only, after checking that the old PID still does not exist.
                    appendLine("  [ ! -e /proc/${request.pid}/stat ]")
                    appendLine("  /system/bin/service call '$SERVICE' 2 i32 ${request.pid} i32 $slot >/dev/null")
                    appendLine("  echo 'RELEASED ${request.id}'")
                }
                appendLine("fi")
            }
        }
        val result = root(context, release) ?: return Status.ROOT_REQUIRED
        if (!result.elevated) return Status.ROOT_REQUIRED
        val attempted = result.output.lineSequence().mapNotNull {
            if (it.startsWith("RELEASED ")) it.removePrefix("RELEASED ").toIntOrNull() else null
        }.toSet()
        if (attempted.isEmpty()) return if (result.exitCode == 0) Status.CLEAN else Status.FAILED

        // The HAL queues the release. Read back its active list before reporting success.
        val after = parseSnapshot(readDump(context, sdhmsPid)) ?: return Status.FAILED
        if (result.exitCode != 0 || after.halPid != snapshot.halPid ||
            after.requests.any { it.id in attempted }
        ) return Status.FAILED
        Log.i(TAG, "Released ${attempted.size} orphaned SDHMS GPU request(s)")
        return Status.CLEARED
    }

    private fun readDump(context: Context, sdhmsPid: Int): RootResult =
        root(context, """
            set -e
            ${processGuard(sdhmsPid)}
            printf 'HAL_PID %s\n' "${'$'}(/system/bin/pidof '$HAL_PROCESS')"
            /system/bin/dumpsys -t 4 '$SERVICE'
        """.trimIndent()) ?: RootResult(-1, "")

    private fun processGuard(sdhmsPid: Int, halPid: Int? = null): String = buildString {
        appendLine("[ \"$(/system/bin/pidof '${GpuPerformanceSettings.SDHMS}')\" = '$sdhmsPid' ]")
        appendLine("[ -r /proc/$sdhmsPid/stat ]")
        if (halPid != null) appendLine("[ \"$(/system/bin/pidof '$HAL_PROCESS')\" = '$halPid' ]")
    }

    private fun parseSnapshot(result: RootResult): Snapshot? {
        if (result.exitCode != 0 || !result.elevated) return null
        val halPid = result.output.lineSequence().firstOrNull { it.startsWith("HAL_PID ") }
            ?.removePrefix("HAL_PID ")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val parts = result.output.split(DUMP_MARKER, limit = 3)
        if (parts.size != 3) return null
        val decoded = Base64.decode(parts[1], Base64.DEFAULT).decodeToString(throwOnInvalidSequence = true)
        return Snapshot(halPid, parseActiveRequests(decoded) ?: return null)
    }

    internal fun parseActiveRequests(dump: String): List<Request>? {
        val lines = dump.lineSequence().dropWhile { !it.startsWith("REQ TIME") }.drop(1).toList()
        val end = lines.indexOfFirst { it.startsWith("====") }
        if (end < 0) return null
        return lines.take(end).mapNotNull { line ->
            val match = activeRow.matchEntire(line.trim()) ?: return@mapNotNull null
            val (pid, id, mhz) = match.destructured
            Request(pid.toIntOrNull() ?: return null, id.toIntOrNull() ?: return null, mhz.toIntOrNull() ?: return null)
                .takeIf { it.pid > 0 && it.id > 0 && it.mhz > 0 }
        }.distinct()
    }

    internal fun releaseSlot(pid: Int, id: Int): Int? {
        if (pid <= 0 || id <= 0) return null
        // The HAL pairs the PID and client slot. Reject encodings that would overflow
        // the vendor's signed intermediate multiplication.
        val sum = ((sqrt(8.0 * id + 1.0) - 1.0) / 2.0).toInt()
        val slot = sum - pid
        val product = sum.toLong() * (sum + 1)
        return slot.takeIf { it >= 0 && product <= Int.MAX_VALUE && product / 2 + it == id.toLong() }
    }

    private fun root(context: Context, command: String): RootResult? {
        val output = File.createTempFile("sdhms-gpu", ".txt", context.cacheDir)
        try {
            val process = try {
                ProcessBuilder("su", "-c", "[ \"$(/system/bin/id -u)\" = 0 ] || exit 1\n" +
                    "echo '$ROOT_MARKER'\n$command")
                    .redirectErrorStream(true).redirectOutput(output).start()
            } catch (_: IOException) {
                return null
            }
            try {
                val exited = process.waitFor(8, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
                val contents = output.inputStream().use { it.readNBytes(MAX_DUMP_BYTES + 1) }
                check(contents.size <= MAX_DUMP_BYTES)
                return RootResult(if (exited) process.exitValue() else -1, contents.decodeToString())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            output.delete()
        }
    }
}
