package com.winlator.cmod.shared.framegen

import android.opengl.GLSurfaceView
import android.util.Log
import android.view.SurfaceHolder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

object FrameGenGlSurface {
    private const val TAG = "WnFrameGen"

    fun attach(view: GLSurfaceView): Boolean {
        if (!FrameGen.requested) return false

        return runCatching {
            val threadField = GLSurfaceView::class.java.getDeclaredField("mGLThread")
            threadField.isAccessible = true
            val running = threadField.get(view)
            threadField.set(view, null)
            try {
                view.setEGLWindowSurfaceFactory(Factory(view))
            } finally {
                threadField.set(view, running)
            }
            true
        }.getOrElse {
            Log.w(TAG, "GL surface could not be redirected: ${it.message}")
            false
        }
    }

    private class Factory(private val view: GLSurfaceView) : GLSurfaceView.EGLWindowSurfaceFactory {
        override fun createWindowSurface(
            egl: EGL10,
            display: EGLDisplay,
            config: EGLConfig,
            nativeWindow: Any?,
        ): EGLSurface? {
            val holder = nativeWindow as? SurfaceHolder
            var target: Any? = nativeWindow
            if (holder != null) {
                val frame = holder.surfaceFrame
                val width = if (frame.width() > 0) frame.width() else view.width
                val height = if (frame.height() > 0) frame.height() else view.height
                val producer = FrameGen.wrap(holder.surface, width, height)
                if (producer != null) target = producer
            }
            return runCatching { egl.eglCreateWindowSurface(display, config, target, null) }
                .getOrElse {
                    Log.w(TAG, "eglCreateWindowSurface failed: ${it.message}")
                    null
                }
        }

        override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
            egl.eglDestroySurface(display, surface)
        }
    }
}
