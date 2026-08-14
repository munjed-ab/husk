package app.olauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import app.olauncher.data.Constants
import app.olauncher.data.Prefs

// ponytail: fixed palettes instead of a colour wheel. Tapping the same option cycles to the
// next preset, so no picker dialog and no new dependency. Swap to a wheel if 8 is not enough.
val BG_COLORS = intArrayOf(
    0xFF000000.toInt(), // black
    0xFF12141C.toInt(), // ink
    0xFF1B2A20.toInt(), // moss
    0xFF2B1B2E.toInt(), // plum
    0xFF0E2A33.toInt(), // deep sea
    0xFF33200E.toInt(), // umber
    0xFFE8E2D4.toInt(), // paper
    0xFFFFFFFF.toInt(), // white
)

val BG_GRADIENTS = arrayOf(
    intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()), // slate
    intArrayOf(0xFF200122.toInt(), 0xFF6F0000.toInt()), // ember
    intArrayOf(0xFF000000.toInt(), 0xFF434343.toInt()), // carbon
    intArrayOf(0xFF1A2A6C.toInt(), 0xFFB21F1F.toInt()), // dusk
    intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt()), // fern
    intArrayOf(0xFFF7F7F7.toInt(), 0xFFB8C6DB.toInt()), // fog
)

/**
 * Paints [root] behind the whole nav host. [image] is the picked-photo layer, hidden for every
 * other type. Falls back to the system wallpaper when the picked image is gone or unreadable.
 */
fun applyHomeBackground(root: View, image: ImageView, prefs: Prefs) {
    when (prefs.homeBgType) {
        Constants.HomeBackground.COLOR -> {
            clearImage(image)
            root.setBackgroundColor(BG_COLORS[prefs.homeBgIndex.mod(BG_COLORS.size)])
        }

        Constants.HomeBackground.GRADIENT -> {
            clearImage(image)
            root.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                BG_GRADIENTS[prefs.homeBgIndex.mod(BG_GRADIENTS.size)]
            )
        }

        Constants.HomeBackground.IMAGE -> {
            root.background = null
            val bitmap = decodeSampled(root.context, prefs.homeBgImage)
            if (bitmap == null) {
                // uri revoked or file deleted, drop back to wallpaper rather than showing black
                clearImage(image)
                prefs.homeBgType = Constants.HomeBackground.WALLPAPER
            } else {
                image.setImageBitmap(bitmap)
                image.isVisible = true
            }
        }

        else -> { // WALLPAPER: transparent window already shows it, see styles.xml
            clearImage(image)
            root.background = null
        }
    }
}

private fun clearImage(image: ImageView) {
    image.isVisible = false
    image.setImageDrawable(null)
}

private fun decodeSampled(context: Context, uriString: String): Bitmap? {
    if (uriString.isEmpty()) return null
    val uri = Uri.parse(uriString)
    val metrics = context.resources.displayMetrics
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outHeight / sample > metrics.heightPixels || bounds.outWidth / sample > metrics.widthPixels)
            sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
