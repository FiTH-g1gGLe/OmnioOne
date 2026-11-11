package com.omnioone.app.activities

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.omnioone.app.R
import com.omnioone.app.core.OmnioColors

/** Omnio One: the shared look of simple screens (top bar with back arrow, scrolling cards). */
class OmnioScreen(private val activity: Activity, title: String) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(OmnioColors.BACKGROUND)
    }
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(40))
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(ImageView(activity).apply {
                setImageResource(com.omnioone.app.R.drawable.ic_arrow_left_vector)
                imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { activity.finish() }
            })
            addView(TextView(activity).apply {
                text = title
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            })
        })
        root.addView(NestedScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(content)
        })
    }

    fun note(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(OmnioColors.TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }.also { content.addView(it) }

    fun section(emoji: String, title: String) {
        content.addView(TextView(activity).apply {
            text = "$emoji  ${title.uppercase()}"
            setTextColor(OmnioColors.TEXT)
            alpha = 0.75f
            letterSpacing = 0.05f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(22), 0, dp(8))
        })
    }

    fun card(rows: List<View>): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioColors.CARD)
            setPadding(dp(16), dp(2), dp(12), dp(2))
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(View(activity).apply {
                setBackgroundColor(0x1FFFFFFF)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            card.addView(row)
        }
        content.addView(card)
        return card
    }

    /** A row: optional leading view, title + subtitle, optional trailing view. */
    fun row(title: String, subtitle: String?, leading: View? = null, trailing: View? = null, subtitleColor: Int = OmnioColors.TEXT_MUTED, onClick: (() -> Unit)?) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(0, dp(8), 0, dp(8))
            if (onClick != null) {
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }
            leading?.let { addView(it) }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (leading != null) dp(14) else 0, 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = title
                    setTextColor(OmnioColors.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                if (!subtitle.isNullOrEmpty()) addView(TextView(activity).apply {
                    text = subtitle
                    setTextColor(subtitleColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                })
            })
            trailing?.let { addView(it) }
        }

    fun iconButton(icon: Int, colour: Int, description: String, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(colour)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        background = activity.getDrawable(android.R.drawable.list_selector_background)
        contentDescription = description
        setOnClickListener { onClick() }
    }

    fun button(label: String, colour: Int, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        setTextColor(OmnioColors.ON_ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = activity.getDrawable(R.drawable.omnio_round_bg)
        backgroundTintList = ColorStateList.valueOf(colour)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) }
        setOnClickListener { onClick() }
    }.also { content.addView(it) }
}
