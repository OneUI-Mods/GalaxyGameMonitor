package dev.ryuya.galaxygamemonitor

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

internal class MonitorCapture {
    private val main = Handler(Looper.getMainLooper())
    private val methods by lazy { SurfaceMethods() }
    @Volatile private var retired = false
    private var unavailable = false
    private var appliedLayer: Int? = null
    private var appliedVisibility: Boolean? = null
    private var pending: PendingDraw? = null

    fun onWindowAdded(view: View) {
        if (retired) return
        val visible = runCatching { DisplaySettings.readRemote(view.context)?.showInCapture }.getOrNull()
        if (visible != false || update(view, false)) return
        removePendingDraw()

        val reference = WeakReference(view)
        val listener = ViewTreeObserver.OnPreDrawListener {
            val root = reference.get()
            if (root == null || retired || update(root, false)) removePendingDraw()
            true
        }
        pending = PendingDraw(reference, listener)
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    // Return false only while the window does not have a valid surface yet.
    fun update(view: View, visible: Boolean): Boolean {
        if (retired || unavailable) return true
        return runCatching {
            val source = methods
            val root = source.viewRoot.invoke(view) ?: return false
            val surface = source.surface.invoke(root) as? SurfaceControl ?: return false
            if (!surface.isValid) return false
            val layer = source.layerId.invoke(surface) as Int
            if (appliedLayer == layer && appliedVisibility == visible) return true
            SurfaceControl.Transaction().use { transaction ->
                source.skipScreenshot.invoke(transaction, surface, !visible)
                transaction.apply()
            }
            appliedLayer = layer
            appliedVisibility = visible
            Log.i(TAG, "Monitor capture visibility: $visible")
            true
        }.getOrElse {
            unavailable = true
            Log.w(TAG, "Monitor capture policy unavailable", it)
            true
        }
    }

    fun retire() {
        retired = true
        // Keep the user's capture policy in force while the new module binds to the window.
        if (Looper.myLooper() == Looper.getMainLooper()) removePendingDraw()
        else main.post { removePendingDraw() }
    }

    private fun removePendingDraw() {
        val previous = pending ?: return
        pending = null
        previous.view.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(previous.listener)
    }

    private data class PendingDraw(val view: WeakReference<View>, val listener: ViewTreeObserver.OnPreDrawListener)

    private class SurfaceMethods {
        val viewRoot = View::class.java.getMethod("getViewRootImpl")
        val surface = viewRoot.returnType.getMethod("getSurfaceControl")
        val layerId = SurfaceControl::class.java.getMethod("getLayerId")
        val skipScreenshot = SurfaceControl.Transaction::class.java.getMethod(
            "setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType,
        )
    }

    private companion object {
        const val TAG = "GalaxyGameMonitor"
    }
}
