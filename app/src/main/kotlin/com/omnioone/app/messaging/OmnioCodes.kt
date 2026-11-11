package com.omnioone.app.messaging

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Omnio One: spots verification codes in texts ("Your code is 482913"), copies them for you,
 * and tells the conversation screen which part to make tappable.
 */
object OmnioCodes {

    /** Where a code sits in a message. */
    data class Found(val code: String, val start: Int, val end: Int)

    private val keywords = Regex(
        "\\b(code|codes|otp|passcode|pass code|password|pin|verification|verify|verifying|security|login|log in|sign in|sign-in|" +
            "one[- ]time|confirm|confirmation|authenticat\\w*|2fa|token|kód|codigo|código|code de|ihr code)\\b",
        RegexOption.IGNORE_CASE
    )

    // 4–8 digits (optionally split "123 456" or "123-456"), or a letter prefix like "G-123456"
    private val candidate = Regex("(?<![\\w£$€.,/:+-])(?:[A-Z]{1,3}-)?(\\d{3}[ -]\\d{3}|\\d{4,8})(?![\\w%/:]|[.,]\\d)")

    fun isOn(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).getBoolean("copy_codes", true)
    fun setOn(context: Context, on: Boolean) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).edit().putBoolean("copy_codes", on).apply()

    /** The verification code in [text], if it looks like one. */
    fun find(text: String): Found? {
        if (text.isBlank() || !keywords.containsMatchIn(text)) return null
        val matches = candidate.findAll(text).filterNot { looksLikeSomethingElse(text, it) }.toList()
        if (matches.isEmpty()) return null
        // prefer the number closest after a keyword ("code is 1234"), else the first one
        val keywordEnds = keywords.findAll(text).map { it.range.last }.toList()
        val best = matches.minByOrNull { match ->
            keywordEnds.filter { it < match.range.first }.minOfOrNull { match.range.first - it } ?: (10_000 + match.range.first)
        } ?: return null
        val code = best.groupValues[1].filter { it.isDigit() }
        return Found(code, best.range.first, best.range.last + 1)
    }

    private fun looksLikeSomethingElse(text: String, match: MatchResult): Boolean {
        val digits = match.groupValues[1].filter { it.isDigit() }
        if (digits.length !in 4..8) return true
        // years like 2026 next to a month, or times like 12:30
        val around = text.substring(maxOf(0, match.range.first - 12), minOf(text.length, match.range.last + 12)).lowercase()
        if (digits.length == 4 && digits.startsWith("20") && Regex("jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec").containsMatchIn(around)) return true
        // phone numbers ("call 0800 1234 567") are longer runs; amounts ("£1234") are excluded by the pattern
        val before = text.substring(maxOf(0, match.range.first - 6), match.range.first).lowercase()
        if (before.contains("call") || before.contains("tel")) return true
        return false
    }

    /** Copies a code without showing it in Android's clipboard preview where possible. */
    fun copy(context: Context, code: String) {
        val clip = ClipData.newPlainText("Verification code", code)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        } catch (ignored: Exception) {
        }
    }
}
