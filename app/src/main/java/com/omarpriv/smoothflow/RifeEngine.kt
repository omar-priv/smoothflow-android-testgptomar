package com.omarpriv.smoothflow

import android.graphics.Bitmap
import java.io.File

internal object RifeEngine {
    init {
        System.loadLibrary("rifejni")
    }

    @JvmStatic external fun nativeInit(modelPath: String, preferGpu: Boolean): Boolean
    @JvmStatic external fun nativeInterpolate(
        first: ByteArray,
        second: ByteArray,
        width: Int,
        height: Int,
        timestep: Float
    ): ByteArray?
    @JvmStatic external fun nativeRelease()

    fun interpolate(first: Bitmap, second: Bitmap): Bitmap {
        require(first.width == second.width && first.height == second.height) {
            "يجب أن يكون حجم الإطارين متساوياً"
        }
        val width = first.width
        val height = first.height
        val firstRgb = bitmapToRgb(first)
        val secondRgb = bitmapToRgb(second)
        val result = nativeInterpolate(firstRgb, secondRgb, width, height, 0.5f)
            ?: error("فشل محرك RIFE في إنشاء الإطار الوسيط")
        return rgbToBitmap(result, width, height)
    }

    private fun bitmapToRgb(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        var out = 0
        for (pixel in pixels) {
            rgb[out++] = ((pixel shr 16) and 0xFF).toByte()
            rgb[out++] = ((pixel shr 8) and 0xFF).toByte()
            rgb[out++] = (pixel and 0xFF).toByte()
        }
        return rgb
    }

    private fun rgbToBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        var input = 0
        for (i in pixels.indices) {
            val red = rgb[input++].toInt() and 0xFF
            val green = rgb[input++].toInt() and 0xFF
            val blue = rgb[input++].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
