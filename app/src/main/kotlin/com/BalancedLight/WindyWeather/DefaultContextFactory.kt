package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

internal class DefaultContextFactory : EGLContextFactory {
    override fun createContext(egl: EGL10, display: EGLDisplay?, config: EGLConfig?): EGLContext {
        return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, null)
    }

    override fun destroyContext(egl: EGL10, display: EGLDisplay?, context: EGLContext?) {
        egl.eglDestroyContext(display, context)
    }
}

