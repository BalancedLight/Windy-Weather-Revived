package com.BalancedLight.WindyWeather

import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

open class GLWallpaperService : WallpaperService() {
    interface Renderer {
        val targetFrameRate: Int

        val powerSaveTargetFrameRate: Int

        val isPowerSaveModeEnabled: Boolean

        var sceneDrawStatus: Boolean

        fun onDrawFrame(gl10: GL10?)

        fun onSurfaceChanged(gl10: GL10?, i: Int, i2: Int)

        fun onSurfaceCreated(gl10: GL10?, eGLConfig: EGLConfig?)
    }

    override fun onCreateEngine(): WallpaperService.Engine {
        return GLEngine()
    }

    open inner class GLEngine : WallpaperService.Engine() {
        private var mEGLConfigChooser: EGLConfigChooser? = null
        private var mEGLContextFactory: EGLContextFactory? = null
        private var mEGLWindowSurfaceFactory: EGLWindowSurfaceFactory? = null
        private var mGLThread: GLThread? = null
        private val mGLWrapper: GLWrapper? = null

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                onResume()
            } else {
                onPause()
            }
            super.onVisibilityChanged(visible)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            if (surfaceHolder.surface == null) {
                Log.w(
                    "GLWallpaperService",
                    "Surface not ready in onCreate; waiting for onSurfaceCreated"
                )
            }
        }

        override fun onDestroy() {
            this.mGLThread?.requestExitAndWait()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.d("GLWallpaperService", "onSurfaceChanged()")
            this.mGLThread?.onWindowResize(width, height)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            Log.d("GLWallpaperService", "onSurfaceCreated()")
            this.mGLThread?.surfaceCreated(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            Log.d("GLWallpaperService", "onSurfaceDestroyed()")
            this.mGLThread?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        open fun setRenderer(renderer: Renderer?) {
            checkRenderThreadState()
            if (this.mEGLConfigChooser == null) {
                this.mEGLConfigChooser = BaseConfigChooser.SimpleEGLConfigChooser(true)
            }
            if (this.mEGLContextFactory == null) {
                this.mEGLContextFactory = DefaultContextFactory()
            }
            if (this.mEGLWindowSurfaceFactory == null) {
                this.mEGLWindowSurfaceFactory = DefaultWindowSurfaceFactory()
            }
            this.mGLThread = GLThread(
                renderer,
                this.mEGLConfigChooser,
                this.mEGLContextFactory,
                this.mEGLWindowSurfaceFactory,
                this.mGLWrapper
            )
            this.mGLThread?.start()
        }

        open fun setRenderMode(renderMode: Int) {
            this.mGLThread?.setRenderMode(renderMode)
        }

        open fun onPause() {
            this.mGLThread?.onPause()
        }

        open fun onResume() {
            this.mGLThread?.onResume()
        }

        private fun checkRenderThreadState() {
            kotlin.check(this.mGLThread == null) { "setRenderer has already been called for this instance." }
        }
    }
}

