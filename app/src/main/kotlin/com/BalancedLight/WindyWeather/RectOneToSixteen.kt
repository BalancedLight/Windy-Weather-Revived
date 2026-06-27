package com.BalancedLight.WindyWeather

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.opengles.GL10

class RectOneToSixteen(context: Context?, strName: String?) {
    private val indexBuffer: ShortBuffer
    private val mName: String?
    private val textureBuffer: FloatBuffer
    private val vertexBuffer: FloatBuffer
    var textureLoaded: Boolean = false
        private set
    private var mLastBitmapIdentity = 0
    private var mLastBitmapWidth = 0
    private var mLastBitmapHeight = 0
    private val textures = IntArray(1)
    private val vertices =
        floatArrayOf(-16.0f, -1.0f, 0.0f, 16.0f, -1.0f, 0.0f, 16.0f, 1.0f, 0.0f, -16.0f, 1.0f, 0.0f)
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
        this.mLastBitmapIdentity = 0
        this.mLastBitmapWidth = 0
        this.mLastBitmapHeight = 0
    }

    fun loadGLTexture(gl: GL10?, context: Context?, bitmap: Bitmap?) {
        if (bitmap == null) {
            return
        }
        if (gl == null) {
            bitmap.recycle()
            return
        }
        val bitmapIdentity: Int = System.identityHashCode(bitmap)
        val bitmapWidth: Int = bitmap.width
        val bitmapHeight: Int = bitmap.height
        if (this.textureLoaded
            && bitmapIdentity == this.mLastBitmapIdentity && bitmapWidth == this.mLastBitmapWidth && bitmapHeight == this.mLastBitmapHeight
        ) {
            bitmap.recycle()
            return
        }
        if (this.textureLoaded) {
            gl.glDeleteTextures(1, this.textures, 0)
            this.textureLoaded = false
            this.textures[0] = 0
        }
        val maxTexture: Int = SecretWallpaperService.maxTextureSize
        var uploadBitmap: Bitmap? = bitmap
        if (maxTexture > 0 && (bitmap.width > maxTexture || bitmap.height > maxTexture)) {
            val scale: Float = Math.min(
                (maxTexture.toFloat()) / bitmap.width,
                (maxTexture.toFloat()) / bitmap.height
            )
            val targetWidth: Int = Math.max(1, java.lang.Math.round(bitmap.width * scale))
            val targetHeight: Int = Math.max(1, java.lang.Math.round(bitmap.height * scale))
            uploadBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            Log.d(
                "WindyWeather",
                "Downsample dynamic texture " + this.mName + " from " + bitmap.width + "x" + bitmap.height + " to " + targetWidth + "x" + targetHeight + " max=" + maxTexture
            )
        }
        gl.glGenTextures(1, this.textures, 0)
        gl.glBindTexture(3553, this.textures[0])
        gl.glTexParameterf(3553, 10240, 9729.0f)
        gl.glTexParameterf(3553, 10241, 9728.0f)
        val finalUploadBitmap = uploadBitmap ?: bitmap
        GLUtils.texImage2D(3553, 0, finalUploadBitmap, 0)
        if (finalUploadBitmap !== bitmap) {
            finalUploadBitmap.recycle()
        }
        bitmap.recycle()
        this.textureLoaded = true
        this.mLastBitmapIdentity = bitmapIdentity
        this.mLastBitmapWidth = bitmapWidth
        this.mLastBitmapHeight = bitmapHeight
    }

    fun shortdraw(gl: GL10, fColor: Float, opacity: Float) {
        if (this.textureLoaded) {
            gl.glVertexPointer(3, 5126, 0, this.vertexBuffer)
            gl.glTexCoordPointer(2, 5126, 0, this.textureBuffer)
            gl.glColor4f(fColor, fColor, fColor, opacity)
            gl.glBindTexture(3553, this.textures[0])
            gl.glDrawElements(4, this.indices.size, 5123, this.indexBuffer)
        }
    }
}

