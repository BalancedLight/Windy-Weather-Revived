package com.BalancedLight.WindyWeather

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.opengles.GL10

/**
 * A [Square]-shaped quad whose texture is generated at runtime rather than decoded from a
 * drawable.
 *
 * Geometry deliberately matches [Square] exactly -- the same +/-8 vertices, texture coordinates
 * and indices -- so subclasses drop into the existing `glTranslatef`/`glScalef` transforms in
 * `drawObjects` with no change to the placement maths.
 *
 * Pixels are uploaded straight from a direct [ByteBuffer] rather than through
 * `GLUtils.texImage2D(bitmap)`.  Uploads happen on the GL thread inside the draw loop, and a
 * per-upload `Bitmap` allocation there would be steady GC pressure on exactly the wrong thread.
 */
internal abstract class GeneratedQuad {
    private val vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer
    private val indexCount: Int

    protected val textures = IntArray(1)
    protected var textureAllocated = false

    var isReady: Boolean = false
        protected set

    init {
        val vertices =
            floatArrayOf(-8.0f, -8.0f, 0.0f, 8.0f, -8.0f, 0.0f, 8.0f, 8.0f, 0.0f, -8.0f, 8.0f, 0.0f)
        val texture = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f)
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        this.indexCount = indices.size

        val vbb: ByteBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
        vbb.order(ByteOrder.nativeOrder())
        this.vertexBuffer = vbb.asFloatBuffer()
        this.vertexBuffer.put(vertices)
        this.vertexBuffer.position(0)

        val tbb: ByteBuffer = ByteBuffer.allocateDirect(texture.size * 4)
        tbb.order(ByteOrder.nativeOrder())
        this.textureBuffer = tbb.asFloatBuffer()
        this.textureBuffer.put(texture)
        this.textureBuffer.position(0)

        val ibb: ByteBuffer = ByteBuffer.allocateDirect(indices.size * 2)
        ibb.order(ByteOrder.nativeOrder())
        this.indexBuffer = ibb.asShortBuffer()
        this.indexBuffer.put(indices)
        this.indexBuffer.position(0)
    }

    open fun release(gl: GL10?) {
        if (this.textureAllocated && gl != null) {
            gl.glDeleteTextures(1, this.textures, 0)
        }
        this.textures[0] = 0
        this.textureAllocated = false
        this.isReady = false
    }

    /** Allocates texture storage and uploads the first image. */
    protected fun allocate(gl: GL10, width: Int, height: Int, pixels: ByteBuffer) {
        gl.glGenTextures(1, this.textures, 0)
        gl.glBindTexture(GL_TEXTURE_2D, this.textures[0])
        gl.glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR.toFloat())
        gl.glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR.toFloat())
        gl.glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE.toFloat())
        gl.glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE.toFloat())
        gl.glTexImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels
        )
        this.textureAllocated = true
    }

    /** Replaces the pixels of already-allocated storage of the same size. */
    protected fun replace(gl: GL10, width: Int, height: Int, pixels: ByteBuffer) {
        gl.glBindTexture(GL_TEXTURE_2D, this.textures[0])
        gl.glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels
        )
    }

    protected fun discardStorage(gl: GL10) {
        if (this.textureAllocated) {
            gl.glDeleteTextures(1, this.textures, 0)
            this.textures[0] = 0
            this.textureAllocated = false
        }
    }

    fun shortdraw(gl: GL10, fColor: Float, opacity: Float) {
        shortdraw(gl, fColor, fColor, fColor, opacity)
    }

    fun shortdraw(gl: GL10, red: Float, green: Float, blue: Float, opacity: Float) {
        if (!this.isReady) {
            return
        }
        gl.glColor4f(red, green, blue, opacity)
        gl.glVertexPointer(3, GL_FLOAT, 0, this.vertexBuffer)
        gl.glTexCoordPointer(2, GL_FLOAT, 0, this.textureBuffer)
        gl.glBindTexture(GL_TEXTURE_2D, this.textures[0])
        gl.glDrawElements(GL_TRIANGLES, this.indexCount, GL_UNSIGNED_SHORT, this.indexBuffer)
    }

    /**
     * Largest power-of-two at or below [preferred] that the driver will accept, floored at
     * [minimum].
     */
    protected fun resolveSize(preferred: Int, minimum: Int, maxTextureSize: Int): Int {
        if (maxTextureSize <= 0) {
            return preferred
        }
        var resolved = preferred
        while (resolved > minimum && resolved > maxTextureSize) {
            resolved /= 2
        }
        return resolved
    }

    companion object {
        const val GL_TEXTURE_2D = 3553
        const val GL_TEXTURE_MAG_FILTER = 10240
        const val GL_TEXTURE_MIN_FILTER = 10241
        const val GL_TEXTURE_WRAP_S = 10242
        const val GL_TEXTURE_WRAP_T = 10243
        const val GL_LINEAR = 9729
        const val GL_CLAMP_TO_EDGE = 33071
        const val GL_RGBA = 6408
        const val GL_UNSIGNED_BYTE = 5121
        const val GL_UNSIGNED_SHORT = 5123
        const val GL_FLOAT = 5126
        const val GL_TRIANGLES = 4
    }
}
