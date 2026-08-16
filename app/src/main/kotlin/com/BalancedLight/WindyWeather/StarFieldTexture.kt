package com.BalancedLight.WindyWeather

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.opengles.GL10

/**
 * The generated star field, uploaded once and then faded by the draw call's alpha.
 *
 * Unlike the sky gradient this never needs regenerating -- [StarField] is deterministic and the
 * night/twilight fade is carried entirely by `shortdraw`'s alpha, so one upload covers the whole
 * cycle.
 *
 * Generation is deliberately lazy: filling and uploading 4 MB takes long enough to stall a frame,
 * and the wallpaper picker's preview would otherwise pay that cost before its first frame even
 * though a preview may never show stars.
 */
internal class StarFieldTexture : GeneratedQuad() {
    private var size = 0

    fun ensureGenerated(gl: GL10?, maxTextureSize: Int) {
        if (gl == null || this.isReady) {
            return
        }
        val targetSize = resolveSize(DEFAULT_SIZE, MIN_SIZE, maxTextureSize)
        if (this.size != targetSize) {
            discardStorage(gl)
            this.size = targetSize
        }
        val pixels = ByteBuffer
            .allocateDirect(targetSize * targetSize * 4)
            .order(ByteOrder.nativeOrder())
        StarField.generateInto(pixels, targetSize, targetSize)
        allocate(gl, targetSize, targetSize, pixels)
        this.isReady = true
    }

    companion object {
        const val DEFAULT_SIZE = 1024
        const val MIN_SIZE = 256
    }
}
