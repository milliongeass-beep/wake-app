package com.wakequest.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.wakequest.app.logic.ImageMatch
import java.io.File

object PhotoUtil {
    const val MIN_CONTRAST = 10.0

    /** Decodes a camera photo at reduced size and rotates it upright using its EXIF data. */
    fun loadUpright(file: File, maxDim: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val rotation = try {
                when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            if (rotation == 0f) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        } catch (e: Throwable) {
            null
        }
    }

    /** Returns (perceptual hash, contrast) or null if the file can't be read. */
    fun analyze(file: File): Pair<String, Double>? {
        val bmp = loadUpright(file, 256) ?: return null
        val small = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        val px = IntArray(64 * 64)
        small.getPixels(px, 0, 64, 0, 0, 64, 64)
        val gray = IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return ImageMatch.hash(gray, 64, 64) to ImageMatch.contrast(gray)
    }
}
