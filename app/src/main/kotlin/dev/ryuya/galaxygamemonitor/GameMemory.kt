package dev.ryuya.galaxygamemonitor

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class GameMemory(private val context: Context) {
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val running = AtomicBoolean()
    private val executor = ThreadPoolExecutor(
        0, 1, 3L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "GameMonitorMemory")
    }
    @Volatile private var residentPssKiB: Long? = null
    private var nextCheckMs = 0L
    private var lastForeground: String? = null
    private var gameUid: Int? = null
    private var nextMemorySampleMs = 0L
    private var loggedSample = false

    @Synchronized
    fun readKiB(): Long? {
        val now = SystemClock.elapsedRealtime()
        if (manager != null && !executor.isShutdown && now >= nextCheckMs && running.compareAndSet(false, true)) {
            nextCheckMs = now + FOREGROUND_CHECK_MS
            executor.execute {
                try {
                    sample(now)
                } catch (_: RuntimeException) {
                    residentPssKiB = null
                } finally {
                    running.set(false)
                }
            }
        }
        return residentPssKiB
    }

    @Synchronized
    fun close() {
        executor.shutdownNow()
    }

    @Suppress("DEPRECATION")
    private fun sample(now: Long) {
        val activityManager = manager ?: return
        val packageName = activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        if (packageName != lastForeground) {
            lastForeground = packageName
            residentPssKiB = null
            gameUid = packageName?.let(::getGameUid)
            nextMemorySampleMs = 0
        }
        val uid = gameUid ?: return
        if (now < nextMemorySampleMs) return
        nextMemorySampleMs = now + MEMORY_SAMPLE_MS

        val foregroundPids = mutableSetOf<Int>()
        for (process in activityManager.runningAppProcesses.orEmpty()) {
            if (process.uid == uid && process.pkgList?.contains(packageName) == true) {
                foregroundPids.add(process.pid)
            }
        }
        val pids = foregroundPids.toIntArray()
        if (pids.isEmpty()) {
            residentPssKiB = null
            return
        }
        val measured = DisplaySettings.readGameMemory(context, pids)
        residentPssKiB = measured
        if (measured != null && !loggedSample) {
            loggedSample = true
            Log.i(TAG, "Game RAM: $measured KiB detailed resident PSS across ${pids.size} process(es)")
        }
        if (measured == null) nextMemorySampleMs = now + RETRY_MS
    }

    @Suppress("DEPRECATION")
    private fun getGameUid(packageName: String): Int? = runCatching {
        val app = context.packageManager.getApplicationInfo(packageName, 0)
        app.uid.takeIf { app.category == ApplicationInfo.CATEGORY_GAME || app.flags and ApplicationInfo.FLAG_IS_GAME != 0 }
    }.getOrNull()

    private companion object {
        const val TAG = "GalaxyGameMonitor"
        const val FOREGROUND_CHECK_MS = 2_000L
        const val MEMORY_SAMPLE_MS = 12_000L
        const val RETRY_MS = 60_000L
    }
}
