package com.BalancedLight.WindyWeather

import android.graphics.Rect
import android.view.SurfaceHolder
import java.util.ArrayList
import javax.microedition.khronos.opengles.GL10

internal class GLThread(
    renderer: GLWallpaperService.Renderer?,
    chooser: EGLConfigChooser?,
    contextFactory: EGLContextFactory?,
    surfaceFactory: EGLWindowSurfaceFactory?,
    wrapper: GLWrapper?
) : Thread() {
    private val mEGLConfigChooser: EGLConfigChooser?
    private val mEGLContextFactory: EGLContextFactory?
    private val mEGLWindowSurfaceFactory: EGLWindowSurfaceFactory?
    private var mEglHelper: EglHelper? = null
    private val mGLWrapper: GLWrapper?
    private var mHasSurface = false
    private var mHaveEgl = false
    var mHolder: SurfaceHolder? = null
    private var mPaused = false
    private val mRenderer: GLWallpaperService.Renderer?
    private var mWaitingForSurface = false
    private var mSizeChanged = true
    private val mEventQueue: ArrayList<Runnable?> = ArrayList()
    var mDone: Boolean = false
    private var mWidth = 0
    private var mHeight = 0
    private var mRequestRender = true
    private var mRenderMode = 1

    init {
        this.mRenderer = renderer
        this.mEGLConfigChooser = chooser
        this.mEGLContextFactory = contextFactory
        this.mEGLWindowSurfaceFactory = surfaceFactory
        this.mGLWrapper = wrapper
    }

    override fun run() {
        name = "GLThread $id"
        try {
            guardedRun()
        } catch (e: InterruptedException) {
            interrupt()
        } finally {
            sGLThreadManager.threadExiting(this)
        }
    }

    private fun stopEglLocked() {
        if (this.mHaveEgl) {
            this.mHaveEgl = false
            this.mEglHelper?.destroySurface()
            sGLThreadManager.releaseEglSurface(this)
        }
    }

    @Throws(InterruptedException::class)
    private fun guardedRun() {
        this.mEglHelper = EglHelper(
            this.mEGLConfigChooser ?: throw IllegalStateException("EGLConfigChooser not set"),
            this.mEGLContextFactory ?: throw IllegalStateException("EGLContextFactory not set"),
            this.mEGLWindowSurfaceFactory ?: throw IllegalStateException("EGLWindowSurfaceFactory not set"),
            this.mGLWrapper
        )
        var gl: GL10? = null
        var needStart = false
        var needCreateSurface = false
        var width = 0
        var height = 0

        while (true) {
            val event: Runnable?
            kotlin.synchronized(this) {
                event = if (this.mEventQueue.size > 0) this.mEventQueue.removeAt(0) else null
            }
            if (event != null) {
                event.run()
                continue
            }

            var shouldDraw = false
            kotlin.synchronized(sGLThreadManager) {
                while (true) {
                    if (this.mDone) {
                        cleanupEgl()
                        return
                    }
                    if (!this.mHasSurface) {
                        if (!this.mWaitingForSurface) {
                            this.mWaitingForSurface = true
                            sGLThreadManager.notifyThreads()
                        }
                        stopEglLocked()
                        sGLThreadManager.waitForState()
                        continue
                    }
                    this.mWaitingForSurface = false

                    if (this.mPaused) {
                        stopEglLocked()
                        sGLThreadManager.waitForState()
                        continue
                    }

                    if (!this.mHaveEgl) {
                        if (sGLThreadManager.tryAcquireEglSurface(this)) {
                            this.mHaveEgl = true
                            needStart = true
                            this.mSizeChanged = true
                        } else {
                            sGLThreadManager.waitForState()
                            continue
                        }
                    }

                    if (this.mSizeChanged) {
                        width = this.mWidth
                        height = this.mHeight
                        needCreateSurface = true
                        this.mSizeChanged = false
                    }

                    if (this.mRenderMode == 1 || this.mRequestRender) {
                        shouldDraw = true
                        this.mRequestRender = this.mRenderMode == 1
                        break
                    }

                    sGLThreadManager.waitForState()
                }
            }

            if (needStart) {
                this.mEglHelper?.start()
                needStart = false
                gl = null
            }

            if (needCreateSurface) {
                val holder = this.mHolder
                val eglHelper = this.mEglHelper
                val renderer = this.mRenderer
                if (holder != null && eglHelper != null && renderer != null) {
                    var surfaceWidth = width
                    var surfaceHeight = height
                    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                        val frame: Rect? = holder.surfaceFrame
                        if (frame != null) {
                            surfaceWidth = frame.width()
                            surfaceHeight = frame.height()
                        }
                    }
                    gl = eglHelper.createSurface(holder) as GL10?
                    renderer.onSurfaceCreated(gl, eglHelper.mEglConfig)
                    renderer.onSurfaceChanged(
                        gl,
                        Math.max(1, surfaceWidth),
                        Math.max(1, surfaceHeight)
                    )
                }
                needCreateSurface = false
            }

            // Always render when requested; scene status becomes true only after first draw.
            val renderer = this.mRenderer
            val eglHelper = this.mEglHelper
            if (shouldDraw && gl != null && renderer != null && eglHelper != null) {
                val frameStartNs: Long = System.nanoTime()
                renderer.onDrawFrame(gl)
                if (!eglHelper.swap()) {
                    kotlin.synchronized(sGLThreadManager) {
                        stopEglLocked()
                        this.mSizeChanged = true
                    }
                }
                if (this.mRenderMode == 1) {
                    var targetFps: Int =
                        renderer.targetFrameRate
                    if (targetFps <= 0) {
                        targetFps =
                            com.BalancedLight.WindyWeather.GLThread.Companion.DEFAULT_TARGET_FPS
                    }
                    if (renderer.isPowerSaveModeEnabled) {
                        val powerSaveTargetFps: Int = renderer.powerSaveTargetFrameRate
                        if (powerSaveTargetFps > 0) {
                            targetFps = powerSaveTargetFps
                        }
                    }
                    val targetFrameNs = 1000000000L / targetFps
                    val elapsedNs: Long = System.nanoTime() - frameStartNs
                    val sleepNs = targetFrameNs - elapsedNs
                    if (sleepNs > 0L) {
                        val sleepMs = sleepNs / 1000000L
                        val sleepExtraNs = (sleepNs % 1000000L).toInt()
                        Thread.sleep(sleepMs, sleepExtraNs)
                    } else {
                        Thread.yield()
                    }
                }
            }
        }
    }

    private fun cleanupEgl() {
        kotlin.synchronized(sGLThreadManager) {
            stopEglLocked()
        }
        this.mEglHelper?.finish()
    }

    private val isDone: Boolean
        get() {
            val z: Boolean
            kotlin.synchronized(sGLThreadManager) {
                z = this.mDone
            }
            return z
        }

    fun setRenderMode(renderMode: Int) {
        kotlin.require(!(renderMode < 0 || renderMode > 1)) { "renderMode" }
        kotlin.synchronized(sGLThreadManager) {
            this.mRenderMode = renderMode
            this.mRequestRender = true
            sGLThreadManager.notifyThreads()
        }
    }

    fun surfaceCreated(holder: SurfaceHolder?) {
        this.mHolder = holder
        kotlin.synchronized(sGLThreadManager) {
            this.mHasSurface = true
            this.mRequestRender = true
            sGLThreadManager.notifyThreads()
        }
    }

    fun surfaceDestroyed() {
        kotlin.synchronized(sGLThreadManager) {
            this.mHasSurface = false
            sGLThreadManager.notifyThreads()
            while (!this.mWaitingForSurface && isAlive && !this.mDone) {
                try {
                    sGLThreadManager.waitForState()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    fun onPause() {
        kotlin.synchronized(sGLThreadManager) {
            this.mPaused = true
            sGLThreadManager.notifyThreads()
        }
    }

    fun onResume() {
        kotlin.synchronized(sGLThreadManager) {
            this.mPaused = false
            this.mRequestRender = true
            sGLThreadManager.notifyThreads()
        }
    }

    fun onWindowResize(w: Int, h: Int) {
        kotlin.synchronized(sGLThreadManager) {
            this.mWidth = w
            this.mHeight = h
            this.mSizeChanged = true
            this.mRequestRender = true
            sGLThreadManager.notifyThreads()
        }
    }

    fun requestExitAndWait() {
        kotlin.synchronized(sGLThreadManager) {
            this.mDone = true
            sGLThreadManager.notifyThreads()
        }
        try {
            join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private class GLThreadManager {
        private var eglOwner: GLThread? = null

        @Synchronized
        fun threadExiting(thread: GLThread) {
            thread.mDone = true
            if (eglOwner === thread) {
                eglOwner = null
            }
            notifyThreads()
        }

        @Synchronized
        fun tryAcquireEglSurface(thread: GLThread?): Boolean {
            val z: Boolean
            if (eglOwner === thread || eglOwner == null) {
                eglOwner = thread
                notifyThreads()
                z = true
            } else {
                z = false
            }
            return z
        }

        @Synchronized
        fun releaseEglSurface(thread: GLThread?) {
            if (eglOwner === thread) {
                eglOwner = null
            }
            notifyThreads()
        }

        fun waitForState() {
            (this as java.lang.Object).wait()
        }

        fun notifyThreads() {
            (this as java.lang.Object).notifyAll()
        }
    }

    companion object {
        private const val DEFAULT_TARGET_FPS = 60
        private val sGLThreadManager = GLThreadManager()
    }
}
