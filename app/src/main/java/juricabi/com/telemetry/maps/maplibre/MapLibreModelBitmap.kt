package juricabi.com.telemetry.maps.maplibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.drawable.DrawableCompat
import juricabi.com.telemetry.R

/** Build the square aircraft texture consumed by the custom map renderer. */
internal fun mapLibreModelBitmap(context: Context, icon: Int, color: Int?): Bitmap {
    val body = context.resources.getDrawable(icon).mutate()
    if (color != null) DrawableCompat.setTint(body, color)
    val outlineIcon = when (icon) {
        R.drawable.ic_plane -> R.drawable.ic_plane_outline
        R.drawable.ic_fixedwing -> R.drawable.ic_fixedwing_outline
        R.drawable.ic_heli -> R.drawable.ic_heli_outline
        else -> null
    }
    val drawable: Drawable = if (outlineIcon == null) {
        body
    } else {
        LayerDrawable(
            arrayOf(context.resources.getDrawable(outlineIcon).mutate(), body)
        )
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
    val side = Math.max(width, height)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(
        (side - width) / 2, (side - height) / 2,
        (side + width) / 2, (side + height) / 2
    )
    drawable.draw(canvas)
    return bitmap
}
