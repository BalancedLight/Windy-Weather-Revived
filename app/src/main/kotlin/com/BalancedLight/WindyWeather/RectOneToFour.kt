package com.BalancedLight.WindyWeather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.opengles.GL10

class RectOneToFour(context: Context?, strName: String?) {
    private val indexBuffer: ShortBuffer
    private val mName: String?
    private val textureBuffer: FloatBuffer
    private val vertexBuffer: FloatBuffer
    var textureLoaded: Boolean = false
        private set
    private var mLastResolvedTextureId = 0
    private var mLastBlur = false
    private var blendDayId = 0
    private var blendNightId = 0
    private var dayPixels: IntArray? = null
    private var nightPixels: IntArray? = null
    private var blendPixels: IntArray? = null
    private var blendBitmap: Bitmap? = null
    private var lastBlendStep = -1
    private val textures = IntArray(1)
    private val vertices =
        floatArrayOf(-4.0f, -1.0f, 0.0f, 4.0f, -1.0f, 0.0f, 4.0f, 1.0f, 0.0f, -4.0f, 1.0f, 0.0f)
    private val texture = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f)
    private val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

    init {
        val vbb: ByteBuffer = ByteBuffer.allocateDirect(this.vertices.size * 4)
        vbb.order(ByteOrder.nativeOrder())
        this.vertexBuffer = vbb.asFloatBuffer()
        this.vertexBuffer.put(this.vertices)
        this.vertexBuffer.position(0)
        val ibb: ByteBuffer = ByteBuffer.allocateDirect(this.indices.size * 2)
        ibb.order(ByteOrder.nativeOrder())
        this.indexBuffer = ibb.asShortBuffer()
        this.indexBuffer.put(this.indices)
        this.indexBuffer.position(0)
        val byteBuf: ByteBuffer = ByteBuffer.allocateDirect(this.texture.size * 4)
        byteBuf.order(ByteOrder.nativeOrder())
        this.textureBuffer = byteBuf.asFloatBuffer()
        this.textureBuffer.put(this.texture)
        this.textureBuffer.position(0)
        this.mName = strName
    }

    fun deleteGLTexture(gl: GL10?, context: Context?) {
        if (this.textureLoaded && gl != null) {
            gl.glDeleteTextures(1, this.textures, 0)
            this.textureLoaded = false
            this.textures[0] = 0
        }
        this.mLastResolvedTextureId = 0
        this.mLastBlur = false
        clearBlend()
    }

    private fun clearBlend() {
        blendBitmap?.recycle()
        blendBitmap = null
        dayPixels = null
        nightPixels = null
        blendPixels = null
        blendDayId = 0
        blendNightId = 0
        lastBlendStep = -1
    }

    /** Retain both source images so crossing sunrise/sunset never replaces the ground. */
    fun loadDayNightTextures(gl: GL10?, context: Context?, dayId: Int, nightId: Int, daylight: Float) {
        if (gl == null || context == null) return
        val resolvedDay = SecretWallpaperService.resolveTextureResource(context, dayId)
        val resolvedNight = SecretWallpaperService.resolveTextureResource(context, nightId)
        if (textureLoaded && blendDayId == resolvedDay && blendNightId == resolvedNight) {
            updateDaylight(gl, daylight)
            return
        }
        val day = decodeTextureBitmap(context, resolvedDay, false) ?: return
        val night = decodeTextureBitmap(context, resolvedNight, false)
        if (night == null) {
            day.recycle()
            return
        }
        // Texture packs may have different source dimensions; sample both onto the day grid.
        val scaledNight = if (night.width == day.width && night.height == day.height) night
            else Bitmap.createScaledBitmap(night, day.width, day.height, true)
        deleteGLTexture(gl, context)
        val width = day.width
        val height = day.height
        dayPixels = IntArray(width * height).also { day.getPixels(it, 0, width, 0, 0, width, height) }
        nightPixels = IntArray(width * height).also { scaledNight.getPixels(it, 0, width, 0, 0, width, height) }
        blendPixels = IntArray(width * height)
        blendBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        day.recycle()
        if (scaledNight !== night) scaledNight.recycle()
        night.recycle()
        blendDayId = resolvedDay
        blendNightId = resolvedNight
        gl.glGenTextures(1, textures, 0)
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[0])
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE.toFloat())
        updateDaylight(gl, daylight)
        textureLoaded = true
    }

    /** Reuse storage and upload only when the 10-bit blend level changes. */
    private fun updateDaylight(gl: GL10, daylight: Float) {
        val day = dayPixels ?: return
        val night = nightPixels ?: return
        val pixels = blendPixels ?: return
        val bitmap = blendBitmap ?: return
        val step = ForegroundLighting.blendStep(daylight)
        if (step == lastBlendStep) return
        when (step) {
            0 -> night.copyInto(pixels)
            ForegroundLighting.BLEND_STEPS -> day.copyInto(pixels)
            else -> for (i in pixels.indices) pixels[i] = ForegroundLighting.blendPixel(day[i], night[i], step)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[0])
        if (lastBlendStep < 0) GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bitmap, 0)
        else GLUtils.texSubImage2D(GL10.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        lastBlendStep = step
    }

    fun loadGLTexture(gl: GL10?, context: Context?, texture_id: Int, bBlur: Boolean) {
        if (gl == null || context == null || texture_id == 0) {
            return
        }
        clearBlend()
        val resolvedTextureId: Int =
            SecretWallpaperService.resolveTextureResource(context, texture_id)
        if (this.textureLoaded && resolvedTextureId == this.mLastResolvedTextureId && bBlur == this.mLastBlur) {
            return
        }
        if (this.textureLoaded) {
            gl.glDeleteTextures(1, this.textures, 0)
            this.textureLoaded = false
            this.textures[0] = 0
        }
        val bitmap: Bitmap? = decodeTextureBitmap(context, resolvedTextureId, bBlur)
        if (bitmap == null) {
            Log.d("WindyWeather", "Bitmap is null - Object Name : " + this.mName)
            return
        }
        gl.glGenTextures(1, this.textures, 0)
        gl.glBindTexture(3553, this.textures[0])
        val filter = 9729.0f
        gl.glTexParameterf(3553, 10240, filter)
        gl.glTexParameterf(3553, 10241, filter)
        gl.glTexParameterf(3553, 10242, 33071.0f)
        gl.glTexParameterf(3553, 10243, 33071.0f)
        GLUtils.texImage2D(3553, 0, bitmap, 0)
        bitmap.recycle()
        this.textureLoaded = true
        this.mLastResolvedTextureId = resolvedTextureId
        this.mLastBlur = bBlur
    }

    private fun decodeTextureBitmap(
        context: Context,
        resolvedTextureId: Int,
        bBlur: Boolean
    ): Bitmap? {
        val boundsOptions: BitmapFactory.Options = BitmapFactory.Options()
        boundsOptions.inJustDecodeBounds = true
        boundsOptions.inScaled = false
        boundsOptions.inDensity = 0
        boundsOptions.inTargetDensity = 0
        BitmapFactory.decodeResource(context.resources, resolvedTextureId, boundsOptions)
        val maxTexture: Int = SecretWallpaperService.maxTextureSize
        var inSampleSize = 1
        if (maxTexture > 0) {
            while ((boundsOptions.outWidth / inSampleSize) > maxTexture || (boundsOptions.outHeight / inSampleSize) > maxTexture) {
                inSampleSize *= 2
            }
        }
        if (bBlur && inSampleSize < 2) {
            inSampleSize = 2
        }
        val decodeOptions: BitmapFactory.Options = BitmapFactory.Options()
        decodeOptions.inSampleSize = inSampleSize
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
        decodeOptions.inScaled = false
        decodeOptions.inDensity = 0
        decodeOptions.inTargetDensity = 0
        if (inSampleSize > 1) {
            Log.d(
                "WindyWeather",
                "Downsample texture " + this.mName + " sample=" + inSampleSize + " size=" + boundsOptions.outWidth + "x" + boundsOptions.outHeight + " max=" + maxTexture
            )
        }
        val decoded: Bitmap? =
            BitmapFactory.decodeResource(context.resources, resolvedTextureId, decodeOptions)
        if (decoded != null) {
            Log.d(
                "WindyWeather",
                "Decode texture " + this.mName + " src=" + boundsOptions.outWidth + "x" + boundsOptions.outHeight + " decoded=" + decoded.width + "x" + decoded.height + " sample=" + inSampleSize + " scaled=false blur=" + bBlur
            )
        }
        return decoded
    }

    fun shortdraw(gl: GL10, fColor: Float, opacity: Float) {
        shortdraw(gl, fColor, fColor, fColor, opacity)
    }

    fun shortdraw(gl: GL10, red: Float, green: Float, blue: Float, opacity: Float, daylight: Float? = null) {
        if (daylight != null) updateDaylight(gl, daylight)
        if (this.textureLoaded) {
            gl.glVertexPointer(3, 5126, 0, this.vertexBuffer)
            gl.glTexCoordPointer(2, 5126, 0, this.textureBuffer)
            gl.glColor4f(red, green, blue, opacity)
            gl.glBindTexture(3553, this.textures[0])
            gl.glDrawElements(4, this.indices.size, 5123, this.indexBuffer)
        }
    }
}

