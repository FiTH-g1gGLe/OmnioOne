package com.omnioone.app.helpers

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioCallStyle.Answer
import com.omnioone.app.helpers.OmnioCallStyle.Design
import kotlin.math.abs

/**
 * The answer / decline controls of the incoming-call screen: five designs each for
 * buttons, swipe up / down and swipe left / right ([Design.answerVariant] picks one).
 */
class OmnioAnswerControls(
    private val activity: Activity,
    private val screen: OmnioCallScreen,
    private val design: Design,
    private val darkTrack: Boolean,
    private val animators: MutableList<Animator>,
    private val onAnswer: () -> Unit,
    private val onDecline: () -> Unit,
) {
    companion object {
        const val VARIANTS = 5

        fun variantName(answer: Answer, variant: Int) = when (answer) {
            Answer.BUTTONS -> listOf(
                R.string.omnio_answer_buttons_0, R.string.omnio_answer_buttons_1, R.string.omnio_answer_buttons_2,
                R.string.omnio_answer_buttons_3, R.string.omnio_answer_buttons_4
            )
            Answer.SWIPE_UP -> listOf(
                R.string.omnio_answer_vertical_0, R.string.omnio_answer_vertical_1, R.string.omnio_answer_vertical_2,
                R.string.omnio_answer_vertical_3, R.string.omnio_answer_vertical_4
            )
            Answer.SLIDE -> listOf(
                R.string.omnio_answer_sideways_0, R.string.omnio_answer_sideways_1, R.string.omnio_answer_sideways_2,
                R.string.omnio_answer_sideways_3, R.string.omnio_answer_sideways_4
            )
        }[variant.coerceIn(0, VARIANTS - 1)]
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun str(res: Int) = activity.getString(res)
    private val trackColour get() = if (darkTrack) 0x66000000 else OmnioColors.CARD
    private val evaluator = ArgbEvaluator()
    private fun mix(from: Int, to: Int, amount: Float) = evaluator.evaluate(amount.coerceIn(0f, 1f), from, to) as Int
    private fun faded(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha shl 24)

    fun build(): View {
        val variant = design.answerVariant.coerceIn(0, VARIANTS - 1)
        return when (design.answer) {
            Answer.BUTTONS -> when (variant) {
                1 -> pillButtons()
                2 -> stackedButtons()
                3 -> bubbleButtons()
                4 -> tileButtons()
                else -> classicButtons()
            }
            Answer.SWIPE_UP -> when (variant) {
                1 -> centreHandle()
                2 -> verticalTrack()
                3 -> chevronHandle()
                4 -> pullUpBar()
                else -> classicSwipeUp()
            }
            Answer.SLIDE -> when (variant) {
                1 -> slideFromLeft()
                2 -> twoToneTrack()
                3 -> bigCircleSideways()
                4 -> miniPill()
                else -> classicSlide()
            }
        }
    }

    // ---- shared pieces ----

    private fun icon(res: Int, background: Int, sizeDp: Int, tint: Int = 0xFFFFFFFF.toInt(), corner: Int? = null) = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val pad = dp(sizeDp) * 27 / 100
        setPadding(pad, pad, pad, pad)
        this.background = GradientDrawable().apply {
            if (corner == null) shape = GradientDrawable.OVAL else cornerRadius = dp(corner).toFloat()
            setColor(background)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun pill(colour: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(colour)
    }

    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun gap(widthDp: Int, heightDp: Int) = View(activity).apply { layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)) }

    private fun loop(animator: ValueAnimator, reverse: Boolean = true) = animator.apply {
        repeatCount = ValueAnimator.INFINITE
        if (reverse) repeatMode = ValueAnimator.REVERSE
        start()
        animators.add(this)
    }

    /** A small "Decline" text button for swipe designs that only swipe one way. */
    private fun declineLink() = TextView(activity).apply {
        text = str(R.string.omnio_call_decline)
        setTextColor(design.declineColor)
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(10), dp(28), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(2), faded(design.declineColor, 0x99))
        }
        setOnClickListener { onDecline() }
    }

    /**
     * Makes [view] draggable on one axis between [range]. [onMove] gets the offset as it moves;
     * [onRelease] gets the final offset and returns true when it acted (otherwise it snaps back).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun drag(
        view: View,
        vertical: Boolean,
        range: () -> ClosedFloatingPointRange<Float>,
        onMove: (Float) -> Unit = {},
        onTap: () -> Unit = {},
        onRelease: (Float) -> Boolean,
    ) {
        var start = 0f
        view.setOnTouchListener { v, event ->
            val position = if (vertical) event.rawY else event.rawX
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = position
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val allowed = range().let { if (it.isEmpty()) 0f..0f else it }
                    val offset = (position - start).coerceIn(allowed)
                    if (vertical) v.translationY = offset else v.translationX = offset
                    onMove(offset)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val offset = if (vertical) v.translationY else v.translationX
                    val acted = event.actionMasked == MotionEvent.ACTION_UP && onRelease(offset)
                    if (!acted) {
                        v.animate().translationX(0f).translationY(0f).setDuration(180).setUpdateListener { onMove(if (vertical) v.translationY else v.translationX) }.start()
                        if (abs(position - start) < dp(8) && event.actionMasked == MotionEvent.ACTION_UP) onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun nudge(view: View) {
        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    // ---- buttons ----

    /** Two round buttons with labels. */
    private fun classicButtons() = row().apply {
        addView(screen.roundButton(com.omnioone.commons.R.drawable.ic_cross_vector, design.declineColor, str(R.string.omnio_call_decline)) { onDecline() })
        addView(gap(110, 1))
        addView(screen.roundButton(com.omnioone.commons.R.drawable.ic_check_vector, design.acceptColor, str(R.string.omnio_call_accept)) { onAnswer() })
    }

    private fun pillButton(iconRes: Int, label: Int, colour: Int, heightDp: Int, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = pill(colour, heightDp / 2)
        addView(ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        addView(TextView(activity).apply {
            text = str(label)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        })
        setOnClickListener { onClick() }
    }

    /** Two wide pills side by side. */
    private fun pillButtons() = row().apply {
        addView(pillButton(com.omnioone.commons.R.drawable.ic_cross_vector, R.string.omnio_call_decline, design.declineColor, 64) { onDecline() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(8) }
        })
        addView(pillButton(com.omnioone.commons.R.drawable.ic_check_vector, R.string.omnio_call_answer, design.acceptColor, 64) { onAnswer() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(8) }
        })
    }

    /** A big answer bar with decline underneath. */
    private fun stackedButtons() = column().apply {
        addView(pillButton(com.omnioone.commons.R.drawable.ic_check_vector, R.string.omnio_call_answer, design.acceptColor, 68) { onAnswer() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68))
        })
        addView(pillButton(com.omnioone.commons.R.drawable.ic_cross_vector, R.string.omnio_call_decline, faded(design.declineColor, 0xD9), 56) { onDecline() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(14) }
        })
    }

    /** A big breathing answer bubble with a smaller decline button. */
    private fun bubbleButtons() = row().apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        addView(screen.roundButton(com.omnioone.commons.R.drawable.ic_cross_vector, design.declineColor, str(R.string.omnio_call_decline), sizeDp = 60) { onDecline() })
        addView(gap(70, 1))
        val holder = FrameLayout(activity).apply { layoutParams = LinearLayout.LayoutParams(dp(130), dp(130)) }
        val glow = View(activity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(faded(design.acceptColor, 0x55)) }
            layoutParams = FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER)
        }
        val bubble = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.acceptColor, 100).apply {
            layoutParams = FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER)
            contentDescription = str(R.string.omnio_call_answer)
            setOnClickListener { onAnswer() }
        }
        holder.addView(glow)
        holder.addView(bubble)
        addView(holder)
        loop(ObjectAnimator.ofPropertyValuesHolder(glow,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.2f)
        ).apply { duration = 1100 })
        loop(ObjectAnimator.ofPropertyValuesHolder(bubble,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
        ).apply { duration = 1100 })
    }

    private fun tile(iconRes: Int, label: Int, colour: Int, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(icon(iconRes, colour, 78, corner = 26).apply {
            contentDescription = str(label)
            setOnClickListener { onClick() }
        })
        addView(screen.text(str(label), 14f, alpha = 0.85f).apply { setPadding(0, dp(8), 0, 0) })
    }

    /** Rounded-square tiles. */
    private fun tileButtons() = row().apply {
        addView(tile(com.omnioone.commons.R.drawable.ic_cross_vector, R.string.omnio_call_decline, design.declineColor) { onDecline() })
        addView(gap(80, 1))
        addView(tile(com.omnioone.commons.R.drawable.ic_check_vector, R.string.omnio_call_answer, design.acceptColor) { onAnswer() })
    }

    // ---- swipe up / down ----

    /** Decline button on the left, a handle to swipe up on the right. */
    private fun classicSwipeUp() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(screen.roundButton(com.omnioone.commons.R.drawable.ic_cross_vector, design.declineColor, str(R.string.omnio_call_decline), sizeDp = 60) { onDecline() })
        addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        // short, so it fits under the button at the screen edge
        val hint = screen.text("↑ " + str(R.string.omnio_call_answer), 14f, alpha = 0.8f)
        val handle = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.acceptColor, 76).apply { contentDescription = str(R.string.omnio_call_accept) }
        drag(handle, vertical = true, range = { -dp(160).toFloat()..0f }, onTap = { nudge(hint) }) { offset ->
            if (offset < -dp(110)) { onAnswer(); true } else false
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(handle)
            addView(hint.apply { setPadding(0, dp(8), 0, 0) })
        })
    }

    /** One handle in the middle: up to answer, down to decline. */
    private fun centreHandle() = column().apply {
        val up = screen.text("▲  " + str(R.string.omnio_call_answer), 14f, bold = true, color = design.acceptColor)
        val down = screen.text("▼  " + str(R.string.omnio_call_decline), 14f, bold = true, color = design.declineColor)
        val handle = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.textColor, 80, tint = OmnioColors.ON_ACCENT)
        val handleBg = handle.background as GradientDrawable
        drag(
            handle, vertical = true, range = { -dp(110).toFloat()..dp(70).toFloat() },
            onMove = { offset ->
                val amount = abs(offset) / dp(80)
                handleBg.setColor(mix(design.textColor, if (offset < 0) design.acceptColor else design.declineColor, amount))
                up.alpha = 1f - (offset.coerceAtLeast(0f) / dp(70))
                down.alpha = 1f - (-offset.coerceAtMost(0f) / dp(110))
            },
            onTap = { nudge(up) }
        ) { offset ->
            when {
                offset < -dp(85) -> { onAnswer(); true }
                offset > dp(55) -> { onDecline(); true }
                else -> false
            }
        }
        addView(up)
        addView(gap(1, 14))
        addView(handle)
        addView(gap(1, 14))
        addView(down)
    }

    /** A tall pill track: push the knob up to answer or down to decline. */
    private fun verticalTrack() = column().apply {
        val trackH = dp(250)
        val knobSize = dp(70)
        val track = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), trackH)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(faded(design.acceptColor, 0x99), trackColour, trackColour, faded(design.declineColor, 0x99))
            ).apply { cornerRadius = dp(44).toFloat() }
        }
        track.addView(ImageView(activity).apply {
            setImageResource(com.omnioone.commons.R.drawable.ic_check_vector)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            alpha = 0.8f
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(18) }
        })
        track.addView(ImageView(activity).apply {
            setImageResource(com.omnioone.commons.R.drawable.ic_cross_vector)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            alpha = 0.8f
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(18) }
        })
        val knob = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.textColor, 70, tint = OmnioColors.ON_ACCENT).apply {
            layoutParams = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.CENTER)
        }
        val knobBg = knob.background as GradientDrawable
        val limit = (trackH - knobSize) / 2f - dp(6)
        drag(knob, vertical = true, range = { -limit..limit }, onMove = { offset ->
            knobBg.setColor(mix(design.textColor, if (offset < 0) design.acceptColor else design.declineColor, abs(offset) / limit))
        }) { offset ->
            when {
                offset < -limit * 0.75f -> { onAnswer(); true }
                offset > limit * 0.75f -> { onDecline(); true }
                else -> false
            }
        }
        track.addView(knob)
        addView(track)
    }

    /** A handle with arrows drifting up; swipe down on it to decline. */
    private fun chevronHandle() = column().apply {
        val arrows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        repeat(3) { index ->
            val arrow = screen.text("︿", 22f, bold = true, color = design.acceptColor).apply { alpha = 0.2f }
            arrows.addView(arrow, 0)
            loop(ObjectAnimator.ofFloat(arrow, View.ALPHA, 0.15f, 1f).apply {
                duration = 700
                startDelay = index * 230L
            })
        }
        val handle = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.acceptColor, 82)
        val hint = screen.text(str(R.string.omnio_answer_up_down_hint), 13f, alpha = 0.7f)
        drag(handle, vertical = true, range = { -dp(150).toFloat()..dp(60).toFloat() }, onTap = { nudge(arrows) }, onMove = { offset ->
            arrows.alpha = 1f - abs(offset) / dp(100)
        }) { offset ->
            when {
                offset < -dp(100) -> { onAnswer(); true }
                offset > dp(48) -> { onDecline(); true }
                else -> false
            }
        }
        addView(arrows)
        addView(gap(1, 6))
        addView(handle)
        addView(hint.apply { setPadding(0, dp(12), 0, 0) })
    }

    /** A full-width bar with a grip: pull it up to answer. */
    private fun pullUpBar() = column().apply {
        addView(declineLink())
        addView(gap(1, 22))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = pill(design.acceptColor, 30)
            setPadding(0, dp(10), 0, dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(View(activity).apply {
                background = pill(0x99FFFFFF.toInt(), 3)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(5))
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
                addView(ImageView(activity).apply {
                    setImageResource(com.omnioone.commons.R.drawable.ic_check_vector)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                })
                addView(TextView(activity).apply {
                    text = str(R.string.omnio_call_swipe_up)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 17f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(10), 0, 0, 0)
                })
            })
        }
        loop(ObjectAnimator.ofFloat(bar, View.TRANSLATION_Y, 0f, -dp(6).toFloat()).apply { duration = 900 })
        drag(bar, vertical = true, range = { -dp(180).toFloat()..0f }, onMove = { animators.filter { (it as? ObjectAnimator)?.target == bar }.forEach { it.cancel() } }) { offset ->
            if (offset < -dp(110)) { onAnswer(); true } else false
        }
        addView(bar)
    }

    // ---- swipe left / right ----

    /** A track with the knob in the middle: left to decline, right to answer. */
    private fun classicSlide(): View {
        val track = FrameLayout(activity).apply {
            background = activity.getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(trackColour)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76))
        }
        track.addView(screen.text("‹  " + str(R.string.omnio_call_decline), 14f, alpha = 0.75f, color = design.declineColor).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START).apply { marginStart = dp(20) }
        })
        track.addView(screen.text(str(R.string.omnio_call_answer) + "  ›", 14f, alpha = 0.75f, color = design.acceptColor).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply { marginEnd = dp(20) }
        })
        val knob = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.textColor, 64, tint = OmnioColors.ON_ACCENT).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            contentDescription = str(R.string.omnio_call_answer)
        }
        val limit = { (track.width - knob.width) / 2f }
        drag(knob, vertical = false, range = { -limit()..limit() }, onTap = { onAnswer() }) { offset ->
            when {
                offset > limit() * 0.7f -> { onAnswer(); true }
                offset < -limit() * 0.7f -> { onDecline(); true }
                else -> false
            }
        }
        track.addView(knob)
        return track
    }

    /** Knob starts on the left with shimmering "slide to answer"; decline sits above. */
    private fun slideFromLeft() = column().apply {
        addView(declineLink())
        addView(gap(1, 22))
        val track = FrameLayout(activity).apply {
            background = pill(trackColour, 38)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76))
        }
        val label = screen.text(str(R.string.omnio_answer_slide_label) + "   ›››", 17f, bold = true).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(60) }
        }
        loop(ObjectAnimator.ofFloat(label, View.ALPHA, 0.35f, 1f).apply { duration = 1000 })
        track.addView(label)
        val knob = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.acceptColor, 64).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(6) }
        }
        val limit = { (track.width - knob.width - dp(12)).toFloat() }
        drag(knob, vertical = false, range = { 0f..limit() }, onMove = { label.translationX = it / 3; label.scaleX = 1f }, onTap = { nudge(knob) }) { offset ->
            if (offset > limit() * 0.8f) { onAnswer(); true } else false
        }
        track.addView(knob)
        addView(track)
    }

    /** A track that glows red on the left and green on the right; the knob changes colour as you go. */
    private fun twoToneTrack(): View {
        val track = FrameLayout(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(faded(design.declineColor, 0xAA), trackColour, trackColour, faded(design.acceptColor, 0xAA))
            ).apply { cornerRadius = dp(40).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80))
        }
        track.addView(ImageView(activity).apply {
            setImageResource(com.omnioone.commons.R.drawable.ic_cross_vector)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(24) }
        })
        track.addView(ImageView(activity).apply {
            setImageResource(com.omnioone.commons.R.drawable.ic_check_vector)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(24) }
        })
        val knob = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.textColor, 66, tint = OmnioColors.ON_ACCENT).apply {
            layoutParams = FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER)
        }
        val knobBg = knob.background as GradientDrawable
        val limit = { (track.width - knob.width) / 2f - dp(7) }
        drag(knob, vertical = false, range = { -limit()..limit() }, onMove = { offset ->
            knobBg.setColor(mix(design.textColor, if (offset > 0) design.acceptColor else design.declineColor, abs(offset) / limit()))
            knob.setImageResource(if (offset < 0) com.omnioone.commons.R.drawable.ic_cross_vector else com.omnioone.commons.R.drawable.ic_check_vector)
            knob.imageTintList = ColorStateList.valueOf(if (abs(offset) > limit() / 2) 0xFFFFFFFF.toInt() else OmnioColors.ON_ACCENT)
        }, onTap = { nudge(knob) }) { offset ->
            when {
                offset > limit() * 0.75f -> { onAnswer(); true }
                offset < -limit() * 0.75f -> { onDecline(); true }
                else -> false
            }
        }
        track.addView(knob)
        return track
    }

    /** A big circle with arrows pointing both ways; drag it sideways. */
    private fun bigCircleSideways() = column().apply {
        val holder = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110))
        }
        val left = screen.text("‹‹‹", 30f, bold = true, color = design.declineColor).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START).apply { marginStart = dp(18) }
        }
        val right = screen.text("›››", 30f, bold = true, color = design.acceptColor).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply { marginEnd = dp(18) }
        }
        loop(ObjectAnimator.ofFloat(left, View.TRANSLATION_X, 0f, -dp(8).toFloat()).apply { duration = 700 })
        loop(ObjectAnimator.ofFloat(right, View.TRANSLATION_X, 0f, dp(8).toFloat()).apply { duration = 700 })
        holder.addView(left)
        holder.addView(right)
        val circle = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.textColor, 100, tint = OmnioColors.ON_ACCENT).apply {
            layoutParams = FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER)
        }
        val circleBg = circle.background as GradientDrawable
        val limit = dp(120).toFloat()
        drag(circle, vertical = false, range = { -limit..limit }, onMove = { offset ->
            circleBg.setColor(mix(design.textColor, if (offset > 0) design.acceptColor else design.declineColor, abs(offset) / limit))
        }, onTap = { nudge(circle) }) { offset ->
            when {
                offset > limit * 0.75f -> { onAnswer(); true }
                offset < -limit * 0.75f -> { onDecline(); true }
                else -> false
            }
        }
        holder.addView(circle)
        addView(holder)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(screen.text(str(R.string.omnio_call_decline), 13f, alpha = 0.8f, color = design.declineColor))
            addView(gap(150, 1))
            addView(screen.text(str(R.string.omnio_call_answer), 13f, alpha = 0.8f, color = design.acceptColor))
        })
    }

    /** A small, neat pill; the knob turns red or green as you slide. */
    private fun miniPill() = column().apply {
        val track = FrameLayout(activity).apply {
            background = pill(trackColour, 31)
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(62))
        }
        val knob = icon(com.omnioone.commons.R.drawable.ic_check_vector, design.acceptColor, 50).apply {
            layoutParams = FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
        }
        val knobBg = knob.background as GradientDrawable
        val limit = { (track.width - knob.width) / 2f - dp(6) }
        drag(knob, vertical = false, range = { -limit()..limit() }, onMove = { offset ->
            knobBg.setColor(if (offset < 0) mix(design.acceptColor, design.declineColor, -offset / (limit() / 3)) else design.acceptColor)
            knob.setImageResource(if (offset < -limit() / 3) com.omnioone.commons.R.drawable.ic_cross_vector else com.omnioone.commons.R.drawable.ic_check_vector)
        }, onTap = { nudge(knob) }) { offset ->
            when {
                offset > limit() * 0.75f -> { onAnswer(); true }
                offset < -limit() * 0.75f -> { onDecline(); true }
                else -> false
            }
        }
        track.addView(knob)
        addView(track)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            addView(screen.text("‹ " + str(R.string.omnio_call_decline), 13f, alpha = 0.7f))
            addView(gap(90, 1))
            addView(screen.text(str(R.string.omnio_call_answer) + " ›", 13f, alpha = 0.7f))
        })
    }
}
