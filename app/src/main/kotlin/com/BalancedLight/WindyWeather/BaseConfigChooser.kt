package com.BalancedLight.WindyWeather

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

internal abstract class BaseConfigChooser(protected var mConfigSpec: IntArray?) : EGLConfigChooser {
    protected abstract fun chooseConfig(
        egl10: EGL10,
        eGLDisplay: EGLDisplay?,
        eGLConfigArr: Array<EGLConfig?>?
    ): EGLConfig?

    override fun chooseConfig(egl: EGL10, display: EGLDisplay?): EGLConfig {
        val num_config = IntArray(1)
        egl.eglChooseConfig(display, this.mConfigSpec, null, 0, num_config)
        val numConfigs = num_config[0]
        kotlin.require(numConfigs > 0) { "No configs match configSpec" }
        val configs: Array<EGLConfig?> = arrayOfNulls<EGLConfig>(numConfigs)
        egl.eglChooseConfig(display, this.mConfigSpec, configs, numConfigs, num_config)
        return chooseConfig(egl, display, configs) ?: throw IllegalArgumentException("No config chosen")
    }

    open class ComponentSizeChooser(
        redSize: Int,
        greenSize: Int,
        blueSize: Int,
        alphaSize: Int,
        depthSize: Int,
        stencilSize: Int
    ) : BaseConfigChooser(
        intArrayOf(
            12324,
            redSize,
            12323,
            greenSize,
            12322,
            blueSize,
            12321,
            alphaSize,
            12325,
            depthSize,
            12326,
            stencilSize,
            12344
        )
    ) {
        protected var mAlphaSize: Int
        protected var mBlueSize: Int
        protected var mDepthSize: Int
        protected var mGreenSize: Int
        protected var mRedSize: Int
        protected var mStencilSize: Int
        private val mValue: IntArray

        init {
            this.mValue = IntArray(1)
            this.mRedSize = redSize
            this.mGreenSize = greenSize
            this.mBlueSize = blueSize
            this.mAlphaSize = alphaSize
            this.mDepthSize = depthSize
            this.mStencilSize = stencilSize
        }

        override fun chooseConfig(
            egl: EGL10,
            display: EGLDisplay?,
            configs: Array<EGLConfig?>?
        ): EGLConfig? {
            var closestConfig: EGLConfig? = null
            var closestDistance = 1000
            for (config in configs ?: emptyArray()) {
                val d = findConfigAttrib(egl, display, config, 12325, 0)
                val s = findConfigAttrib(egl, display, config, 12326, 0)
                if (d >= this.mDepthSize && s >= this.mStencilSize) {
                    val r = findConfigAttrib(egl, display, config, 12324, 0)
                    val g = findConfigAttrib(egl, display, config, 12323, 0)
                    val b = findConfigAttrib(egl, display, config, 12322, 0)
                    val a = findConfigAttrib(egl, display, config, 12321, 0)
                    val distance: Int =
                        Math.abs(r - this.mRedSize) + Math.abs(g - this.mGreenSize) + Math.abs(b - this.mBlueSize) + Math.abs(
                            a - this.mAlphaSize
                        )
                    if (distance < closestDistance) {
                        closestDistance = distance
                        closestConfig = config
                    }
                }
            }
            return closestConfig
        }

        private fun findConfigAttrib(
            egl: EGL10,
            display: EGLDisplay?,
            config: EGLConfig?,
            attribute: Int,
            defaultValue: Int
        ): Int {
            if (egl.eglGetConfigAttrib(display, config, attribute, this.mValue)) {
                val defaultValue2 = this.mValue[0]
                return defaultValue2
            }
            return defaultValue
        }
    }

    class SimpleEGLConfigChooser(withDepthBuffer: Boolean) :
        ComponentSizeChooser(4, 4, 4, 0, if (withDepthBuffer) 16 else 0, 0) {
        init {
            this.mRedSize = 8
            this.mGreenSize = 8
            this.mBlueSize = 8
            this.mAlphaSize = 8
        }
    }
}

