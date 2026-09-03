package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

internal class DefaultContextFactory : EGLContextFactory {
    override fun createContext(egl10: EGL10, eGLDisplay: EGLDisplay?, eGLConfig: EGLConfig?): EGLContext {
        return egl10.eglCreateContext(eGLDisplay, eGLConfig, EGL10.EGL_NO_CONTEXT, null)
    }

    override fun destroyContext(egl10: EGL10, eGLDisplay: EGLDisplay?, eGLContext: EGLContext?) {
        egl10.eglDestroyContext(eGLDisplay, eGLContext)
    }
}

