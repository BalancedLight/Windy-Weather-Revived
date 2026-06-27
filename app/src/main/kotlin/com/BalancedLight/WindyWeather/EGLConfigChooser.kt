package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

internal interface EGLConfigChooser {
    fun chooseConfig(egl10: EGL10, eGLDisplay: EGLDisplay?): EGLConfig?
}

