package com.omnioone.app.helpers

import com.omnioone.commons.helpers.OmnioColors
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.omnioone.app.R

/**
 * Omnio One bottom sheets: one look for every pop-up (lists, questions and text boxes),
 * so they feel part of the app instead of a separate system dialog.
 */
object OmnioSheet {

    const val BACKGROUND = OmnioColors.SHEET
    const val CARD = OmnioColors.CARD
    const val TEXT = OmnioColors.TEXT
    const val ORANGE = OmnioColors.CONTACTS
    const val GREEN = OmnioColors.MESSAGES
    const val BLUE = OmnioColors.CALLS
    const val RED = OmnioColors.MISSED

    data class Item(val label: String, val icon: Drawable? = null, val trailing: String? = null, val trailingColor: Int = GREEN, val labelColor: Int = TEXT)

    private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** The sheet frame: handle, title and optional subtitle. Content goes in [body]. */
    private class Frame(val activity: Activity, title: String, subtitle: String?) {
        val sheet = BottomSheetDialog(activity)
        private val root: LinearLayout = buildRoot(activity, title, subtitle)
        val body: LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(16), 0, 0)
        }

        init {
            root.addView(body)
        }

        fun show() {
            sheet.setContentView(NestedScrollView(activity).apply { addView(root) })
            sheet.setOnShowListener {
                sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    BottomSheetBehavior.from(this).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                    }
                }
            }
            sheet.show()
        }

        /** A row of buttons at the bottom: the last one is the filled main button. */
        fun buttons(vararg buttons: Pair<String, () -> Unit>, mainColor: Int = BLUE) {
            with(activity) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(20), 0, 0)
                }
                buttons.forEachIndexed { index, (label, action) ->
                    val main = index == buttons.lastIndex
                    row.addView(TextView(this).apply {
                        text = label
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(18), 0, dp(18), 0)
                        setTextColor(if (main) OmnioColors.ON_ACCENT else TEXT)
                        background = getDrawable(R.drawable.omnio_round_bg)
                        backgroundTintList = ColorStateList.valueOf(if (main) mainColor else CARD)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)).apply {
                            marginStart = dp(8)
                        }
                        setOnClickListener {
                            sheet.dismiss()
                            action()
                        }
                    })
                }
                body.addView(row)
            }
        }
    }

    private fun buildRoot(activity: Activity, title: String, subtitle: String?): LinearLayout = with(activity) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x55FFFFFF)
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        })
        root.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!subtitle.isNullOrEmpty()) {
            root.addView(TextView(this).apply {
                text = subtitle
                setTextColor(TEXT)
                alpha = 0.7f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, 0)
            })
        }
        root
    }

    /** A list of choices in a rounded card, like the Apps card. */
    fun list(activity: Activity, title: String, subtitle: String? = null, items: List<Item>, onPick: (Int) -> Unit) {
        val frame = Frame(activity, title, subtitle)
        with(activity) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(CARD)
                setPadding(dp(16), dp(4), dp(8), dp(4))
            }
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    card.addView(View(this).apply {
                        setBackgroundColor(0x1FFFFFFF)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            marginStart = if (item.icon != null) dp(50) else 0
                        }
                    })
                }
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    background = getDrawable(android.R.drawable.list_selector_background)
                    if (item.icon != null) {
                        addView(ImageView(this@with).apply {
                            setImageDrawable(item.icon)
                            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(16) }
                        })
                    }
                    addView(TextView(this@with).apply {
                        text = item.label
                        setTextColor(item.labelColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (item.trailing != null) {
                        addView(TextView(this@with).apply {
                            text = item.trailing
                            setTextColor(item.trailingColor)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, 0, dp(6), 0)
                        })
                    }
                    addView(ImageView(this@with).apply {
                        setImageResource(com.omnioone.commons.R.drawable.ic_chevron_right_vector)
                        imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                    })
                    setOnClickListener {
                        frame.sheet.dismiss()
                        onPick(index)
                    }
                })
            }
            frame.body.addView(card)
        }
        frame.show()
    }

    /** A question with buttons, e.g. "Add this profile to Lee?". */
    fun confirm(
        activity: Activity,
        title: String,
        message: String? = null,
        positive: String,
        negative: String = activity.getString(com.omnioone.commons.R.string.cancel),
        neutral: String? = null,
        onNeutral: () -> Unit = {},
        onNegative: () -> Unit = {},
        mainColor: Int = BLUE,
        onPositive: () -> Unit
    ) {
        val frame = Frame(activity, title, message)
        if (neutral != null) {
            frame.buttons(neutral to onNeutral, negative to onNegative, positive to onPositive, mainColor = mainColor)
        } else {
            frame.buttons(negative to onNegative, positive to onPositive, mainColor = mainColor)
        }
        frame.show()
    }

    /** A picture (e.g. a caught profile photo) with buttons. */
    fun photo(
        activity: Activity,
        title: String,
        message: String?,
        bitmap: android.graphics.Bitmap,
        positive: String,
        negative: String = activity.getString(com.omnioone.commons.R.string.cancel),
        onNegative: () -> Unit = {},
        onPositive: () -> Unit
    ) {
        val frame = Frame(activity, title, message)
        val size = activity.dp(150)
        frame.body.addView(android.widget.ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = activity.dp(8)
            }
            com.bumptech.glide.Glide.with(activity).load(bitmap).circleCrop().into(this)
        })
        frame.buttons(negative to onNegative, positive to onPositive, mainColor = GREEN)
        frame.show()
    }

    /** A text box with buttons. Returns the EditText so callers can tweak it. */
    fun input(
        activity: Activity,
        title: String,
        message: String? = null,
        hint: String = "",
        initial: String = "",
        positive: String = activity.getString(com.omnioone.commons.R.string.ok),
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        multiLine: Boolean = false,
        neutral: String? = null,
        onNeutral: () -> Unit = {},
        negative: String = activity.getString(com.omnioone.commons.R.string.cancel),
        onPositive: (String) -> Unit
    ): EditText {
        val frame = Frame(activity, title, message)
        val input = with(activity) {
            EditText(this).apply {
                this.hint = hint
                setText(initial)
                setSelection(text.length)
                setTextColor(TEXT)
                setHintTextColor(0x66FFFFFF)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                this.inputType = inputType or if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                if (multiLine) {
                    minLines = 3
                    gravity = Gravity.TOP or Gravity.START
                } else {
                    isSingleLine = true
                }
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(CARD)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
        }
        frame.body.addView(input)
        val ok = { onPositive(input.text.toString()) }
        if (neutral != null) {
            frame.buttons(neutral to onNeutral, negative to {}, positive to ok)
        } else {
            frame.buttons(negative to {}, positive to ok)
        }
        frame.sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        frame.show()
        input.requestFocus()
        return input
    }
}
