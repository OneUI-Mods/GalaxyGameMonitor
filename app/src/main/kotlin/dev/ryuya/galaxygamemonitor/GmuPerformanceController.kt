package dev.ryuya.galaxygamemonitor

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.ryuya.galaxygamemonitor.GpuPerformanceSettings.Status
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns the temporary GMU setting in the module app, where KernelSU can grant root independently. */
internal object GmuPerformanceController {
    data class State(
        val game: Boolean = false,
        val gosMatched: Boolean = false,
        val status: Status = Status.IDLE,
        val effective: Int? = null,
    )

    private const val HEARTBEAT_TIMEOUT = 9_000L
    private const val TAG = "GalaxyGameMonitor"
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var expiry: ScheduledFuture<*>? = null
    private var lastHeartbeat = 0L
    private var attemptedAt = Long.MIN_VALUE
    private var root: GmuRootSession? = null
    @Volatile var state = State()
        private set

    fun report(context: Context, game: Boolean, gosMatched: Boolean, configuredFps: Int?) {
        worker.execute {
            lastHeartbeat = SystemClock.elapsedRealtime()
            val next = try {
                reconcile(context, game, gosMatched, configuredFps)
            } catch (error: IOException) {
                Log.w(TAG, "Could not update the GMU target", error)
                release()
                attemptedAt = SystemClock.elapsedRealtime()
                State(game, gosMatched, Status.FAILED)
            }
            publish(context, next)
            expiry?.cancel(false)
            if (game) expiry = worker.schedule({
                if (SystemClock.elapsedRealtime() - lastHeartbeat >= HEARTBEAT_TIMEOUT) {
                    release()
                    publish(context, State())
                }
            }, HEARTBEAT_TIMEOUT, TimeUnit.MILLISECONDS)
        }
    }

    private fun reconcile(context: Context, game: Boolean, gosMatched: Boolean, fps: Int?): State {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!game || !interactive || fps == null) {
            release()
            return State(game && interactive, gosMatched && game && interactive)
        }
        val now = SystemClock.elapsedRealtime()
        if (root?.alive != true) {
            root?.close()
            root = null
            if (attemptedAt != Long.MIN_VALUE && now - attemptedAt < 10_000L) {
                return State(true, gosMatched, state.status)
            }
            attemptedAt = now
            val (session, status) = GmuRootSession.open()
            root = session ?: return State(true, gosMatched, status)
        }
        val effective = checkNotNull(root).apply(fps)
        val status = if (fps == effective) Status.ACTIVE else Status.WAITING
        if (status == Status.ACTIVE && (state.effective != effective || state.status != Status.ACTIVE)) {
            Log.i(TAG, "GMU target effective at $effective FPS")
        }
        return State(true, gosMatched, status, effective)
    }

    private fun release() {
        if (root != null) Log.i(TAG, "Releasing GMU target override")
        root?.close()
        root = null
        attemptedAt = Long.MIN_VALUE
    }

    private fun publish(context: Context, next: State) {
        if (state == next) return
        state = next
        GpuPerformanceSettings.notifyChange(context)
    }
}

private class GmuRootSession(private val process: Process) : Closeable {
    private val input = process.outputStream.bufferedWriter()
    private val output = process.inputStream.bufferedReader()
    private val pending = StringBuilder()
    val alive get() = process.isAlive

    fun apply(fps: Int): Int? {
        require(fps == 60 || fps == 120)
        input.write("$fps\n")
        input.flush()
        val reply = readReply()
        if (!reply.startsWith("effective ")) throw IOException("GMU target write was rejected")
        return reply.substringAfter(' ').toIntOrNull()
    }

    private fun readReply(): String {
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (output.ready()) {
                val character = output.read()
                if (character < 0) break
                if (character == '\n'.code) return pending.toString().also { pending.clear() }
                if (character != '\r'.code && pending.length < 128) pending.append(character.toChar())
            } else {
                if (!process.isAlive) break
                SystemClock.sleep(20L)
            }
        }
        throw IOException("No response from the GMU root helper")
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { process.waitFor(500L, TimeUnit.MILLISECONDS) }
        runCatching { output.close() }
        // EOF or the child's own inactivity timeout runs its restore trap.
    }

    companion object {
        private const val GMU = "/sys/class/kgsl/kgsl-3d0/gmu"
        private const val TARGET = "$GMU/dcvs_tunables/target_fps"

        fun open(): Pair<GmuRootSession?, Status> {
            val child = try {
                ProcessBuilder("su", "-c", COMMAND).redirectError(File("/dev/null")).start()
            } catch (_: IOException) {
                return null to Status.ROOT_REQUIRED
            } catch (_: SecurityException) {
                return null to Status.ROOT_REQUIRED
            }
            val session = GmuRootSession(child)
            val reply = runCatching { session.readReply() }.getOrNull()
            if (reply == "ready") return session to Status.WAITING
            session.close()
            val status = when (reply) {
                "unsupported" -> Status.UNSUPPORTED
                "busy" -> Status.WAITING
                else -> Status.ROOT_REQUIRED
            }
            return null to status
        }

        // This helper owns the original raw value and restores it even if the app dies.
        // The firmware can expose the requested value in sysfs before accepting it.
        private val COMMAND = """
            [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 1
            target='$TARGET'
            current='$GMU/dcvs/dcvs_tunables_cur'
            [ -r "${'$'}current" ] && [ -w "${'$'}target" ] || { echo unsupported; exit 2; }
            exec 3< "${'$'}target" || exit 2
            /system/bin/flock -n 3 || { echo busy; exit 3; }
            read -r original < "${'$'}target" || exit 2
            case "${'$'}original" in
                -1) ;;
                *[!0-9]*|'') exit 2 ;;
            esac
            owned=
            restore() {
                [ -n "${'$'}owned" ] || return
                read -r raw < "${'$'}target" || return
                [ "${'$'}raw" = "${'$'}owned" ] && printf '%s\n' "${'$'}original" > "${'$'}target"
            }
            trap restore EXIT
            trap 'exit 0' HUP INT TERM PIPE
            effective() {
                while read -r field value label status rest; do
                    if [ "${'$'}field" = target_fps: ] && [ "${'$'}status" = Active ]; then
                        printf '%s\n' "${'$'}value"
                        return
                    fi
                done < "${'$'}current"
            }
            echo ready
            while read -r -t 9 desired; do
                case "${'$'}desired" in 60|120) ;; *) exit 2 ;; esac
                read -r raw < "${'$'}target" || exit 2
                active=${'$'}(effective)
                if [ "${'$'}raw" != "${'$'}desired" ] || { [ -n "${'$'}active" ] && [ "${'$'}active" != "${'$'}desired" ]; }; then
                    printf '%s\n' "${'$'}desired" > "${'$'}target" || exit 2
                    owned=${'$'}desired
                    active=${'$'}(effective)
                fi
                printf 'effective %s\n' "${'$'}active"
            done
        """.trimIndent()
    }
}
