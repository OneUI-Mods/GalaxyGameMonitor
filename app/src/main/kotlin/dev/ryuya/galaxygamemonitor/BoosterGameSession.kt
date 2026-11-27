package dev.ryuya.galaxygamemonitor

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager

/** The game classification comes from Samsung's game service, independently of the GOS hook. */
internal class SamsungGameState(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val samsung by lazy {
        runCatching {
            val type = Class.forName("com.samsung.android.game.SemGameManager")
            type.getConstructor().newInstance() to type.getMethod("isForegroundGame")
        }.getOrNull()
    }

    fun isActive(): Boolean {
        if (power?.isInteractive != true) return false
        samsung?.let { (service, method) ->
            runCatching { method.invoke(service) as? Boolean }.getOrNull()?.let { return it }
        }
        return manifestGameInForeground()
    }

    @Suppress("DEPRECATION")
    private fun manifestGameInForeground() = runCatching {
        val packageName = context.getSystemService(ActivityManager::class.java)
            ?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName ?: return false
        val app = context.packageManager.getApplicationInfo(packageName, 0)
        app.category == ApplicationInfo.CATEGORY_GAME || app.flags and ApplicationInfo.FLAG_IS_GAME != 0
    }.getOrDefault(false)
}

internal class BoosterGameSession {
    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { sync() }
    private var context: Context? = null
    private var gameState: SamsungGameState? = null
    private var controllingGmu = false
    @Volatile private var retired = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = sync()
    }

    fun attach(owner: Context) {
        handler.post {
            if (retired || context != null || owner.getSystemService(UserManager::class.java)?.isSystemUser != true) return@post
            val app = owner.applicationContext ?: owner
            context = app
            gameState = SamsungGameState(app)
            app.contentResolver.registerContentObserver(GpuPerformanceSettings.uri, false, observer)
            sync()
        }
    }

    fun close() {
        retired = true
        handler.removeCallbacks(poll)
        handler.post {
            context?.contentResolver?.unregisterContentObserver(observer)
            context = null
            gameState = null
        }
    }

    private fun sync() {
        handler.removeCallbacks(poll)
        val app = context ?: return
        if (retired) return
        val config = runCatching { GpuPerformanceSettings.readRemote(app) }.getOrNull()
        val selected = config?.let { it.getInt(GpuPerformanceSettings.KEY_MODE) != 0 }
        if (selected == true || controllingGmu) {
            val active = selected == true && gameState?.isActive() == true
            val args = Bundle().apply { putBoolean(GpuPerformanceSettings.KEY_GAME, active) }
            runCatching {
                app.contentResolver.call(GpuPerformanceSettings.uri, GpuPerformanceSettings.BOOSTER_SESSION, null, args)
            }
            controllingGmu = selected ?: controllingGmu
        }
        if (controllingGmu) handler.postDelayed(poll, 2_000L)
    }
}
