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

class Square(context: Context?, strName: String?) {
    private val indexBuffer: ShortBuffer
    private val mName: String?
    private var textureBuffer: FloatBuffer? = null
    private val vertexBuffer: FloatBuffer
    var textureLoaded: Boolean = false
        private set
    private var mLastResolvedTextureId = 0
    private var mLastBlur = false
    private var mLastReflect = false
    private val textures = IntArray(1)
    private val vertices =
        floatArrayOf(-8.0f, -8.0f, 0.0f, 8.0f, -8.0f, 0.0f, 8.0f, 8.0f, 0.0f, -8.0f, 8.0f, 0.0f)
    private val texture = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f)
    private val textureR = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)
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
        this.mLastReflect = false
    }

    fun loadGLTexture(gl: GL10?, context: Context?, texture_id: Int, bBlur: Boolean) {
        loadGLTexture(gl, context, texture_id, bBlur, false)
    }

    fun loadGLTexture(
        gl: GL10?,
        context: Context?,
        texture_id: Int,
        bBlur: Boolean,
        bReflect: Boolean
    ) {
        if (gl == null || context == null || texture_id == 0) {
            return
        }
        val resolvedTextureId: Int =
            SecretWallpaperService.resolveTextureResource(context, texture_id)
        if (this.textureLoaded
            && resolvedTextureId == this.mLastResolvedTextureId && bBlur == this.mLastBlur && bReflect == this.mLastReflect
        ) {
            return
        }
        if (this.textureLoaded) {
            gl.glDeleteTextures(1, this.textures, 0)
            this.textureLoaded = false
            this.textures[0] = 0
        }
        if (this.textureBuffer == null || bReflect != this.mLastReflect) {
            val tex = if (bReflect) this.textureR else this.texture
            val byteBuf: ByteBuffer = ByteBuffer.allocateDirect(tex.size * 4)
            byteBuf.order(ByteOrder.nativeOrder())
            val buffer = byteBuf.asFloatBuffer()
            buffer.put(tex)
            buffer.position(0)
            this.textureBuffer = buffer
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
        this.mLastReflect = bReflect
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

    fun shortdraw(gl: GL10, red: Float, green: Float, blue: Float, opacity: Float) {
        if (this.textureLoaded) {
            gl.glColor4f(red, green, blue, opacity)
            gl.glVertexPointer(3, 5126, 0, this.vertexBuffer)
            gl.glTexCoordPointer(2, 5126, 0, this.textureBuffer ?: return)
            gl.glBindTexture(3553, this.textures[0])
            gl.glDrawElements(4, this.indices.size, 5123, this.indexBuffer)
        }
    }
}

