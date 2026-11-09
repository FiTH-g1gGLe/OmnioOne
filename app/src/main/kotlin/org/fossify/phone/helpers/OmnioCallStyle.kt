package org.fossify.phone.helpers

import android.content.Context
import org.fossify.phone.R

/** Omnio One: which incoming-call style is used. */
object OmnioCallStyle {
    const val CLASSIC = 0
    const val POSTER = 1
    const val MINIMAL = 2
    const val CONTEXT = 3
    const val PULSE = 4

    val all = listOf(CLASSIC, POSTER, MINIMAL, CONTEXT, PULSE)

    fun name(style: Int) = when (style) {
        POSTER -> R.string.omnio_call_style_poster
        MINIMAL -> R.string.omnio_call_style_minimal
        CONTEXT -> R.string.omnio_call_style_context
        PULSE -> R.string.omnio_call_style_pulse
        else -> R.string.omnio_call_style_classic
    }

    fun description(style: Int) = when (style) {
        POSTER -> R.string.omnio_call_style_poster_desc
        MINIMAL -> R.string.omnio_call_style_minimal_desc
        CONTEXT -> R.string.omnio_call_style_context_desc
        PULSE -> R.string.omnio_call_style_pulse_desc
        else -> R.string.omnio_call_style_classic_desc
    }

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    fun get(context: Context) = prefs(context).getInt("call_style", CONTEXT)

    fun set(context: Context, style: Int) = prefs(context).edit().putInt("call_style", style).apply()
}
