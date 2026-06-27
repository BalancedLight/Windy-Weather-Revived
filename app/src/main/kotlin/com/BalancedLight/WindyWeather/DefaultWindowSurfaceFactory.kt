package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

internal class DefaultWindowSurfaceFactory : EGLWindowSurfaceFactory {
    override fun createWindowSurface(
        egl: EGL10,
        display: EGLDisplay?,
        config: EGLConfig?,
        nativeWindow: Any?
    ): EGLSurface? {
        var lastFailure: Throwable? = null
        var eglError: Int = EGL10.EGL_SUCCESS
        for (attempt in 1..com.BalancedLight.WindyWeather.DefaultWindowSurfaceFactory.Companion.MAX_SURFACE_RETRIES) {
            try {
                val eglSurface: EGLSurface? =
                    egl.eglCreateWindowSurface(display, config, nativeWindow, null)
                if (eglSurface != null && eglSurface !== EGL10.EGL_NO_SURFACE) {
                    return eglSurface
                }
            } catch (th: Throwable) {
                lastFailure = th
            }
            eglError = egl.eglGetError()
            if (attempt < com.BalancedLight.WindyWeather.DefaultWindowSurfaceFactory.Companion.MAX_SURFACE_RETRIES) {
                try {
                    Thread.sleep(com.BalancedLight.WindyWeather.DefaultWindowSurfaceFactory.Companion.RETRY_SLEEP_MS)
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val message = ("Unable to create EGL window surface after "
                + com.BalancedLight.WindyWeather.DefaultWindowSurfaceFactory.Companion.MAX_SURFACE_RETRIES
                + " attempts, eglError=0x"
                + Integer.toHexString(eglError))
        if (lastFailure != null) {
            throw RuntimeException(message, lastFailure)
        }
        throw RuntimeException(message)
    }

    override fun destroySurface(egl: EGL10, display: EGLDisplay?, surface: EGLSurface?) {
        egl.eglDestroySurface(display, surface)
    }

    companion object {
        private const val MAX_SURFACE_RETRIES = 50
        private const val RETRY_SLEEP_MS = 10L
    }
}

