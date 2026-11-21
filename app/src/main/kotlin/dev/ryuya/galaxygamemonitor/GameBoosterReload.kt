package dev.ryuya.galaxygamemonitor

import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object GameBoosterReload {
    enum class State(val message: Int) {
        RUNNING(R.string.reload_running),
        REQUESTED(R.string.reload_requested),
        ROOT_REQUIRED(R.string.reload_root_required),
        PRIMARY_USER_ONLY(R.string.reload_primary_user_only),
        UNAVAILABLE(R.string.reload_unavailable),
        FAILED(R.string.reload_failed),
    }

    private val status = MutableLiveData<State?>(null)
    val state: LiveData<State?> = status

    @MainThread
    fun request(primaryUser: Boolean) {
        if (status.value == State.RUNNING) return
        if (!primaryUser) {
            status.value = State.PRIMARY_USER_ONLY
            return
        }

        status.value = State.RUNNING
        thread(name = "ReloadGameBooster") {
            status.postValue(reload())
        }
    }

    private fun reload(): State {
        // Android restarts the package through its usual game foreground notification.
        val command = """
            [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 10
            current_user=${'$'}(/system/bin/am get-current-user) || exit 13
            [ "${'$'}current_user" = 0 ] || exit 11
            /system/bin/pm path --user 0 ${DisplaySettings.GAME_BOOSTER} >/dev/null || exit 12
            /system/bin/am stop-app --user 0 ${DisplaySettings.GAME_BOOSTER} || exit 13
            exit 20
        """.trimIndent()
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
        } catch (_: IOException) {
            return State.ROOT_REQUIRED
        } catch (_: SecurityException) {
            return State.ROOT_REQUIRED
        }

        return try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) return State.FAILED
            when (process.exitValue()) {
                20 -> State.REQUESTED
                11 -> State.PRIMARY_USER_ONLY
                12 -> State.UNAVAILABLE
                13 -> State.FAILED
                else -> State.ROOT_REQUIRED
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            State.FAILED
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
