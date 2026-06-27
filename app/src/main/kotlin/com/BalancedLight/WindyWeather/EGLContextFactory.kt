package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

internal interface EGLContextFactory {
    fun createContext(egl10: EGL10, eGLDisplay: EGLDisplay?, eGLConfig: EGLConfig?): EGLContext?

    fun destroyContext(egl10: EGL10, eGLDisplay: EGLDisplay?, eGLContext: EGLContext?)
}

