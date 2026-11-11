package com.omnioone.app.helpers

import com.omnioone.commons.helpers.OmnioColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions

/** Omnio One: how the Contacts tab is laid out (style, columns, spacing, name format). */
object OmnioDisplay {
    const val STYLE_LIST = 0
    const val STYLE_COMPACT = 1
    const val STYLE_DETAILED = 2
    const val STYLE_CARDS = 3
    const val STYLE_CIRCLES = 4
    const val STYLE_SQUARES = 5
    const val STYLE_TILES = 6
    const val STYLE_SPEED_DIAL = 7
    const val STYLE_BUBBLES = 8
    const val STYLE_TIMELINE = 9
    const val STYLE_MINIMAL = 10
    const val STYLE_BUSINESS = 11
    const val STYLE_SPOTLIGHT = 12
    const val STYLE_PILLS = 13
    const val STYLE_POLAROID = 14
    const val STYLE_ORBIT = 15
    const val STYLE_HONEYCOMB = 16
    const val STYLE_MONOGRAM = 17

    const val DENSITY_COMPACT = 0
    const val DENSITY_NORMAL = 1
    const val DENSITY_COMFY = 2

    const val NAMES_FULL = 0
    const val NAMES_SURNAME_FIRST = 1
    const val NAMES_FIRST_ONLY = 2

    fun isGrid(style: Int) = style in setOf(
        STYLE_CIRCLES, STYLE_SQUARES, STYLE_TILES, STYLE_SPEED_DIAL, STYLE_PILLS, STYLE_POLAROID, STYLE_ORBIT, STYLE_HONEYCOMB, STYLE_MONOGRAM
    )

    /** Styles where the row spacing setting applies. */
    fun isList(style: Int) = style in setOf(STYLE_LIST, STYLE_COMPACT, STYLE_DETAILED, STYLE_BUBBLES, STYLE_TIMELINE, STYLE_MINIMAL)

    /** Styles that show A, B, C… letter headers. */
    fun hasHeaders(style: Int) = isList(style) || style == STYLE_BUSINESS

    /** A contact's own colour (the same one their letter tile uses). */
    fun colourFor(name: String) = tileColors[Math.abs(name.hashCode()) % tileColors.size]

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    fun getStyle(context: Context) = prefs(context).getInt("contacts_style", STYLE_LIST)
    fun setStyle(context: Context, value: Int) = prefs(context).edit().putInt("contacts_style", value).apply()

    fun getColumns(context: Context, style: Int): Int {
        val default = when (style) {
            STYLE_TILES, STYLE_PILLS, STYLE_POLAROID -> 2
            STYLE_SPEED_DIAL, STYLE_ORBIT, STYLE_HONEYCOMB -> 3
            else -> 4
        }
        return prefs(context).getInt("contacts_columns_$style", default).coerceIn(2, 6)
    }

    fun setColumns(context: Context, style: Int, value: Int) =
        prefs(context).edit().putInt("contacts_columns_$style", value.coerceIn(2, 6)).apply()

    fun getDensity(context: Context) = prefs(context).getInt("contacts_density", DENSITY_NORMAL)
    fun setDensity(context: Context, value: Int) = prefs(context).edit().putInt("contacts_density", value).apply()

    fun isFirstNameOnly(context: Context) = prefs(context).getBoolean("contacts_first_name_only", false)
    fun setFirstNameOnly(context: Context, value: Boolean) = prefs(context).edit().putBoolean("contacts_first_name_only", value).apply()

    /** Changes whenever any display option changes. */
    fun signature(context: Context): String {
        val style = getStyle(context)
        return "$style/${getColumns(context, style)}/${getDensity(context)}/${isFirstNameOnly(context)}"
    }

    /** Goes up whenever a contact photo is changed in Omnio, so every list shows the new one. */
    fun photoVersion(context: Context) = prefs(context).getLong("photo_version", 0L)

    fun photosChanged(context: Context) = prefs(context).edit().putLong("photo_version", System.currentTimeMillis()).apply()

    /** Loads a contact photo as a circle, or as a rounded square when [cornerRadiusPx] is set. */
    fun loadAvatar(imageView: ImageView, path: String, name: String, cornerRadiusPx: Int) {
        val context = imageView.context
        val placeholder = BitmapDrawable(context.resources, letterTile(context, name, cornerRadiusPx.toFloat()))
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .placeholder(placeholder)
            .error(placeholder)
            .fallback(placeholder)
            .transform(CenterCrop(), RoundedCorners(cornerRadiusPx.coerceAtLeast(1)))
            // contact photo addresses stay the same when the picture changes, so tell the cache which version this is
            .signature(com.bumptech.glide.signature.ObjectKey(photoVersion(context)))
        Glide.with(context).load(path.ifEmpty { null }).apply(options).into(imageView)
    }

    private val tileColors = intArrayOf(
        OmnioColors.CALLS, OmnioColors.MESSAGES, OmnioColors.CONTACTS, OmnioColors.SOCIALS,
        0xFF8B5CF6.toInt(), 0xFF14B8A6.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt()
    )

    /** Coloured rounded square with the contact's first letter, for contacts without a photo. */
    fun letterTile(context: Context, name: String, radius: Float): Bitmap {
        val size = (context.resources.displayMetrics.density * 128).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scaledRadius = radius * size / (context.resources.displayMetrics.density * 96)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), scaledRadius, scaledRadius, Paint().apply {
            isAntiAlias = true
            color = tileColors[Math.abs(name.hashCode()) % tileColors.size]
        })
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.45f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, textPaint)
        return bitmap
    }
}
