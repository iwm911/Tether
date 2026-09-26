package app.tether.ui.newagent

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import app.tether.core.ImageAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A downscaled attachment plus a small thumbnail for the composer strip. */
class ProcessedImage(val attachment: ImageAttachment, val thumbnail: Bitmap)

/**
 * Prepares a picked photo for Claude: long edge ≤ [MAX_EDGE] px (Claude's own sweet spot),
 * EXIF rotation applied, transparency flattened on white, JPEG q[QUALITY].
 * Blocking — call from Dispatchers.Default.
 */
object ImageProcessor {
    const val MAX_EDGE = 1568
    const val QUALITY = 85
    private const val THUMB_EDGE = 256

    fun process(resolver: ContentResolver, uri: Uri): ProcessedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Couldn't open the image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file isn't an image Tether can read")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Couldn't decode the image")

        val rotation = readRotation(resolver, uri)
        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > MAX_EDGE) MAX_EDGE.toFloat() / longEdge else 1f
        var bmp = decoded
        if (scale < 1f || rotation != 0) {
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (bmp !== decoded) decoded.recycle()
        }
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bmp, 0f, 0f, null)
            }
            bmp.recycle()
            bmp = flat
        }

        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IOException("Couldn't compress the image")

        val tScale = THUMB_EDGE.toFloat() / max(bmp.width, bmp.height)
        val thumb = if (tScale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * tScale).roundToInt().coerceAtLeast(1), (bmp.height * tScale).roundToInt().coerceAtLeast(1), true)
        } else bmp
        if (thumb !== bmp) bmp.recycle()

        val name = displayName(resolver, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "image"
        return ProcessedImage(ImageAttachment(out.toByteArray(), "image/jpeg", "$name.jpg"), thumb)
    }

    private fun readRotation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) {
        0
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
