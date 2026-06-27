package com.BalancedLight.WindyWeather

import android.util.Log
import android.view.SurfaceHolder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL

internal class EglHelper(
    chooser: EGLConfigChooser,
    contextFactory: EGLContextFactory,
    surfaceFactory: EGLWindowSurfaceFactory,
    wrapper: GLWrapper?
) {
    private val mEGLConfigChooser: EGLConfigChooser
    private val mEGLContextFactory: EGLContextFactory
    private val mEGLWindowSurfaceFactory: EGLWindowSurfaceFactory
    private var mEgl: EGL10? = null
    var mEglConfig: EGLConfig? = null
    private var mEglContext: EGLContext? = null
    private var mEglDisplay: EGLDisplay? = null
    private var mEglSurface: EGLSurface? = null
    private val mGLWrapper: GLWrapper?
    private var mHolder: SurfaceHolder? = null

    init {
        this.mEGLConfigChooser = chooser
        this.mEGLContextFactory = contextFactory
        this.mEGLWindowSurfaceFactory = surfaceFactory
        this.mGLWrapper = wrapper
    }

    fun start() {
        if (this.mEgl == null) {
            this.mEgl = EGLContext.getEGL() as EGL10
        }
        val egl = this.mEgl ?: throw RuntimeException("EGL not available")
        if (this.mEglDisplay == null) {
            this.mEglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        }
        if (this.mEglConfig == null) {
            val version = IntArray(2)
            egl.eglInitialize(this.mEglDisplay, version)
            this.mEglConfig = this.mEGLConfigChooser.chooseConfig(egl, this.mEglDisplay)
        }
        if (this.mEglContext == null) {
            this.mEglContext =
                this.mEGLContextFactory.createContext(egl, this.mEglDisplay, this.mEglConfig)
            if (this.mEglContext == null || this.mEglContext === EGL10.EGL_NO_CONTEXT) {
                throw RuntimeException("createContext failed")
            }
        }
        this.mEglSurface = null
    }

    fun createSurface(holder: SurfaceHolder?): GL? {
        val egl = this.mEgl ?: throw RuntimeException("EGL not initialized")
        val context = this.mEglContext ?: throw RuntimeException("EGL context not initialized")
        this.mHolder = holder
        if (this.mEglSurface != null && this.mEglSurface !== EGL10.EGL_NO_SURFACE) {
            egl.eglMakeCurrent(
                this.mEglDisplay,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT
            )
            this.mEGLWindowSurfaceFactory.destroySurface(
                egl,
                this.mEglDisplay,
                this.mEglSurface
            )
        }
        this.mEglSurface = this.mEGLWindowSurfaceFactory.createWindowSurface(
            egl,
            this.mEglDisplay,
            this.mEglConfig,
            holder
        )
        if (this.mEglSurface == null || this.mEglSurface === EGL10.EGL_NO_SURFACE) {
            throw RuntimeException("createWindowSurface failed")
        }
        if (!egl.eglMakeCurrent(
                this.mEglDisplay,
                this.mEglSurface,
                this.mEglSurface,
                context
            )
        ) {
            throw RuntimeException("eglMakeCurrent failed.")
        }
        val gl: GL? = context.gl
        if (this.mGLWrapper != null) {
            return this.mGLWrapper.wrap(gl)
        }
        return gl
    }

    fun swap(): Boolean {
        val egl = this.mEgl ?: return false
        if (this.mEglSurface != null && this.mEglDisplay != null) {
            egl.eglSwapBuffers(this.mEglDisplay, this.mEglSurface)
        }
        return egl.eglGetError() != 12302
    }

    fun destroySurface() {
        val egl = this.mEgl ?: return
        if (this.mEglSurface != null && this.mEglSurface !== EGL10.EGL_NO_SURFACE) {
            egl.eglMakeCurrent(
                this.mEglDisplay,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT
            )
            val holder = this.mHolder
            if (holder != null) {
                if (holder.surface.isValid) {
                    this.mEGLWindowSurfaceFactory.destroySurface(
                        egl,
                        this.mEglDisplay,
                        this.mEglSurface
                    )
                } else {
                    Log.d("EglHelper", "Surface is invalid")
                }
            }
            this.mEglSurface = null
        }
    }

    fun finish() {
        val egl = this.mEgl ?: return
        if (this.mEglContext != null) {
            this.mEGLContextFactory.destroyContext(egl, this.mEglDisplay, this.mEglContext)
            this.mEglContext = null
        }
        if (this.mEglDisplay != null) {
            egl.eglTerminate(this.mEglDisplay)
            this.mEglDisplay = null
        }
    }
}

