package app.ytune.glyph

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import kotlin.math.ceil

/**
 * Detects a Glyph Matrix (Phone (3): 25×25, Phone (4a) Pro: 13×13). The Nothing SDK is only
 * touched on Nothing phones running Android 14+, so other devices never load it.
 */
object GlyphSupport {

    val matrixSize: Int by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@lazy 0
        if (!Build.MANUFACTURER.equals("Nothing", ignoreCase = true)) return@lazy 0
        runCatching {
            when {
                Common.is23112() -> 25
                Common.is25111p() -> 13
                else -> 0
            }
        }.getOrDefault(0)
    }

    val isSupported: Boolean get() = matrixSize > 0

    fun deviceId(): String? = runCatching {
        when {
            Common.is23112() -> Glyph.DEVICE_23112
            Common.is25111p() -> Glyph.DEVICE_25111p
            else -> null
        }
    }.getOrNull()

    /** Opens the system "Manage Glyph Toys" screen so the user can add the YTune toy. */
    fun openToysManager(context: Context): Boolean = try {
        context.startActivity(
            Intent()
                .setComponent(
                    ComponentName(
                        "com.nothing.thirdparty",
                        "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity",
                    )
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/**
 * Fallback for characters the dot font doesn't have (CJK, Cyrillic, Arabic…): draw them with
 * the system font at matrix height and threshold the result into dot columns.
 */
object Raster {
    private val paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = DotFont.HEIGHT.toFloat()
        }
    }

    fun columns(text: String): IntArray? {
        val width = ceil(paint.measureText(text)).toInt()
        if (width <= 0) return null
        val bitmap = Bitmap.createBitmap(width, DotFont.HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 0f, (DotFont.BASELINE + 1).toFloat(), paint)
        val cols = IntArray(width) { x ->
            var mask = 0
            for (y in 0 until DotFont.HEIGHT) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 120) mask = mask or (1 shl y)
            }
            mask
        }
        bitmap.recycle()
        val first = cols.indexOfFirst { it != 0 }
        val last = cols.indexOfLast { it != 0 }
        return if (first < 0) null else cols.copyOfRange(first, last + 1)
    }
}
