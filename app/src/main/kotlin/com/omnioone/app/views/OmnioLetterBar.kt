package com.omnioone.app.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.omnioone.app.core.OmnioColors

/** Omnio One: the A–Z strip down the side of the contacts list. Slide along it to jump. */
class OmnioLetterBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var letters: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var textColor = OmnioColors.TEXT
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the letter under your finger. */
    var onLetter: (String) -> Unit = {}

    /** A big letter bubble shown while sliding (optional). */
    var bubble: TextView? = null

    private var active: String? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun slot(): Float = if (letters.isEmpty()) 0f else minOf(height.toFloat() / letters.size, 20 * density)
    private fun top(): Float = (height - slot() * letters.size) / 2f

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val slot = slot()
        val start = top()
        letters.forEachIndexed { index, letter ->
            val selected = letter == active
            paint.color = if (selected) OmnioColors.CALLS else textColor
            paint.alpha = if (selected) 255 else 170
            val y = start + slot * index + slot / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, width / 2f, y, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = ((event.y - top()) / slot()).toInt().coerceIn(0, letters.size - 1)
                val letter = letters[index]
                if (letter != active) {
                    active = letter
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetter(letter)
                    invalidate()
                }
                bubble?.apply {
                    text = letter
                    visibility = VISIBLE
                    translationY = (top() + slot() * index + slot() / 2f - height / 2f).coerceAtLeast(0f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                invalidate()
                bubble?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                    bubble?.visibility = GONE
                    bubble?.alpha = 1f
                }?.start()
                return true
            }
        }
        return false
    }
}
