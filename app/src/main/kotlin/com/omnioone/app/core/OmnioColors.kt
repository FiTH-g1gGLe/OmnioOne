package com.omnioone.app.core

/**
 * Omnio One colour palette: every Omnio screen takes its colours from here, so the theme
 * designer has one place to change them.
 */
object OmnioColors {
    // brand: one colour per part of the app
    const val CALLS = 0xFF4F7CFF.toInt()
    const val MESSAGES = 0xFF22C55E.toInt()
    const val CONTACTS = 0xFFFF8A3D.toInt()
    const val FAVOURITES = 0xFFFFC85A.toInt()
    const val SOCIALS = 0xFFEC4899.toInt()

    // surfaces and text
    const val BACKGROUND = 0xFF0F1115.toInt()
    const val SHEET = 0xFF12151B.toInt()
    const val CARD = 0xFF1A1F27.toInt()
    const val CARD_DEEP = 0xFF171B22.toInt()
    const val CARD_SELECTED = 0xFF22304F.toInt()
    const val DANGER_CARD = 0xFF2A1A1D.toInt()
    const val TEXT = 0xFFECEFF4.toInt()
    const val TEXT_MUTED = 0xFF9AA3AF.toInt()

    /** Dark text/icons drawn on top of a bright brand colour (e.g. on a blue Save button). */
    const val ON_ACCENT = 0xFF0F1115.toInt()

    // states
    const val MISSED = 0xFFFF6B6B.toInt()
    const val MISSED_SOFT = 0xFFFF8A8A.toInt()
    const val MESSAGES_SOFT = 0xFF5DD39E.toInt()
}
