package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

internal interface EGLWindowSurfaceFactory {
    fun createWindowSurface(
        egl10: EGL10,
        eGLDisplay: EGLDisplay?,
        eGLConfig: EGLConfig?,
        obj: Any?
    ): EGLSurface?

    fun destroySurface(egl10: EGL10, eGLDisplay: EGLDisplay?, eGLSurface: EGLSurface?)
}

