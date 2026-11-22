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

        val pids = activityManager.runningAppProcesses.orEmpty()
            .filter { it.uid == uid && it.pkgList?.contains(packageName) == true }
            .map { it.pid }.distinct().toIntArray()
        if (pids.isEmpty()) {
            residentPssKiB = null
            return
        }
        // totalPss also includes swapped-out pages. Display the resident share of RAM.
        val memory = activityManager.getProcessMemoryInfo(pids)
        residentPssKiB = memory
            .sumOf { it.dalvikPss.toLong() + it.nativePss + it.otherPss }
            .takeIf { it > 0 }
        if (residentPssKiB != null && !loggedSample) {
            loggedSample = true
            val graphicsKiB = memory.sumOf { it.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L }
            Log.i(TAG, "Game RAM: ${residentPssKiB} KiB resident PSS across ${pids.size} process(es), graphics summary $graphicsKiB KiB")
        }
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
    }
}
