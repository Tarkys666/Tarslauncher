package com.tarslauncher.app.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import java.io.ByteArrayOutputStream

import android.graphics.Rect

private const val DEFAULT_ICON_SIZE = 192
private const val MAX_ICON_SIZE = 384

fun drawableToBitmap(drawable: Drawable): Bitmap {
    // 1. Fast-path: Reuse existing bitmap directly if within safe dimensions
    if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
        val existing = drawable.bitmap
        if (existing.width in 48..MAX_ICON_SIZE && existing.height in 48..MAX_ICON_SIZE) {
            return existing
        }
    }

    val rawWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
    val rawHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE

    // 2. Clamp dimensions to guard against runaway OOM allocations
    val width = rawWidth.coerceIn(48, MAX_ICON_SIZE)
    val height = rawHeight.coerceIn(48, MAX_ICON_SIZE)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 3. Preserve original bounds
    val oldBounds = Rect(drawable.bounds)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    drawable.bounds = oldBounds

    return bitmap
}

fun bitmapToBase64(bitmap: Bitmap): String {
    // Pre-allocate buffer to prevent repeated array re-allocation
    val initialCapacity = (bitmap.byteCount / 4).coerceIn(8192, 131072)
    val outputStream = ByteArrayOutputStream(initialCapacity)
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

fun base64ToBitmap(base64Str: String, targetSize: Int = 192): Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
        if (decodedBytes.isEmpty()) return null

        // 1. Decode bounds only to prevent OOM
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, options)

        // 2. Calculate inSampleSize
        var inSampleSize = 1
        if (options.outHeight > targetSize || options.outWidth > targetSize) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= targetSize && (halfWidth / inSampleSize) >= targetSize) {
                inSampleSize *= 2
            }
        }

        // 3. Decode downsampled bitmap
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, decodeOptions)
    } catch (_: Throwable) {
        // Catches OutOfMemoryError safely
        null
    }
}

fun base64ToDrawable(context: Context, base64Str: String): Drawable? {
    val bitmap = base64ToBitmap(base64Str) ?: return null
    return BitmapDrawable(context.resources, bitmap)
}

