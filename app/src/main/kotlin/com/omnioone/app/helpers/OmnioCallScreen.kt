package com.omnioone.app.helpers

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.omnioone.app.core.OmnioColors
import com.omnioone.commons.helpers.SimpleContactsHelper
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioCallStyle.Answer
import com.omnioone.app.helpers.OmnioCallStyle.Background
import com.omnioone.app.helpers.OmnioCallStyle.Design
import com.omnioone.app.helpers.OmnioCallStyle.PhotoShape

/**
 * Draws an incoming-call screen from a [Design]. Used by the real call screen and by the
 * designer's live preview, so what you design is exactly what rings.
 */
class OmnioCallScreen(
    private val activity: Activity,
    private val design: Design,
    private val info: OmnioCallInfo?,
    private val onAnswer: () -> Unit,
    private val onDecline: () -> Unit,
    private val onMessage: () -> Unit,
    private val onSilence: () -> Unit,
) {
    /** "Incoming call" label, so the activity can keep it up to date. */
    var statusView: TextView? = null
        private set

    /** Running animations (pulse rings), to stop when the screen goes. */
    val animators = ArrayList<Animator>()

    private val isPoster get() = design.background == Background.PHOTO_FULL
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    // ---- public ----

    fun build(): FrameLayout {
        val root = FrameLayout(activity)
        applyBackground(root)
        root.addView(if (isPoster) posterContent() else centredContent())
        return root
    }

    /** Paints the design's background (also used behind the connected-call screen). */
    fun applyBackground(root: FrameLayout) {
        root.background = null
        root.setBackgroundColor(design.backgroundColor)
        val photo = info?.photoUri.orEmpty()
        when (design.background) {
            Background.SOLID -> {}
            Background.GRADIENT -> root.background = gradient()
            Background.PHOTO_BLUR, Background.PHOTO_FULL -> {
                if (photo.isEmpty()) {
                    root.background = gradient()
                    if (design.background == Background.PHOTO_FULL) {
                        root.addView(text((info?.displayName ?: "?").take(1).uppercase(), 140f, bold = true, alpha = 0.18f).apply {
                            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(80) }
                        })
                    }
                } else {
                    val image = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        if (design.background == Background.PHOTO_BLUR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
                        }
                    }
                    Glide.with(activity).load(photo).centerCrop().into(image)
                    root.addView(image)
                    root.addView(View(activity).apply {
                        background = if (design.background == Background.PHOTO_FULL) {
                            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEE0F1115.toInt(), 0x660F1115, 0x220F1115))
                        } else {
                            GradientDrawable().apply { setColor(0x990F1115.toInt()) }
                        }
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    })
                }
            }
        }
    }

    private fun gradient() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(design.gradientColor, design.backgroundColor))

    // ---- building blocks ----

    fun text(value: String, sizeSp: Float, bold: Boolean = false, alpha: Float = 1f, color: Int = design.textColor) =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            this.alpha = alpha
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun column(gravity: Int = Gravity.CENTER_HORIZONTAL) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    private fun subtitle(): String {
        val who = info ?: return ""
        if (!design.showNumber || !who.isSaved) return ""
        return if (who.label.isNotEmpty()) "${who.number} · ${who.label}" else who.number
    }

    private fun status(alpha: Float = 0.7f) = text(activity.getString(R.string.omnio_call_incoming), 16f, alpha = alpha).also { statusView = it }

    fun roundButton(icon: Int, colour: Int, label: String?, sizeDp: Int = 72, onClick: () -> Unit) = column().apply {
        addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colour) }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            contentDescription = label
            setOnClickListener { onClick() }
        })
        if (label != null) addView(text(label, 14f, alpha = 0.85f).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun photo(): View {
        val size = dp(design.photoSize)
        val image = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            val name = info?.displayName ?: "?"
            if (design.photoShape == PhotoShape.ROUNDED) {
                OmnioDisplay.loadAvatar(this, info?.photoUri.orEmpty(), name, dp(design.photoSize / 5))
            } else {
                SimpleContactsHelper(activity).loadContactImage(info?.photoUri.orEmpty(), this, name)
            }
        }
        val box = FrameLayout(activity).apply {
            val boxSize = when {
                design.pulse -> (size * 1.8f).toInt()
                design.orbit -> size + dp(40)
                else -> size
            }
            layoutParams = LinearLayout.LayoutParams(boxSize, boxSize).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        if (design.pulse) {
            listOf(OmnioColors.CALLS, OmnioColors.MESSAGES, OmnioColors.CONTACTS, OmnioColors.SOCIALS).forEachIndexed { index, colour ->
                val ring = View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = if (design.photoShape == PhotoShape.ROUNDED) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                        if (design.photoShape == PhotoShape.ROUNDED) cornerRadius = dp(design.photoSize / 5).toFloat()
                        setStroke(dp(3), colour)
                    }
                    layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                    alpha = 0f
                }
                box.addView(ring)
                ObjectAnimator.ofPropertyValuesHolder(
                    ring,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.7f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.7f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
                ).apply {
                    duration = 2400
                    startDelay = index * 600L
                    repeatCount = ValueAnimator.INFINITE
                    start()
                    animators.add(this)
                }
            }
        }
        if (design.orbit) {
            val ring = OrbitRing(activity, dp(6).toFloat()).apply {
                layoutParams = FrameLayout.LayoutParams(size + dp(32), size + dp(32), Gravity.CENTER)
            }
            box.addView(ring)
            ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                duration = 3200
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
                animators.add(this)
            }
        }
        box.addView(image)
        return box
    }

    /** Last text, birthday, notes and chat apps: only the parts the design shows and that exist. */
    private fun contextCard(alignStart: Boolean): View? {
        val who = info ?: return null
        val lines = listOfNotNull(
            who.lastText.takeIf { design.showLastText },
            who.birthday.takeIf { design.showBirthday },
            who.notes?.let { "📝 $it" }.takeIf { design.showNotes }
        )
        val apps = if (design.showApps) who.apps else emptyList()
        if (lines.isEmpty() && apps.isEmpty()) return null
        return column(Gravity.START).apply {
            background = activity.getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(if (isPoster || design.background == Background.PHOTO_BLUR) 0x66000000 else OmnioColors.CARD)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (alignStart) 14 else 26)
            }
            lines.forEachIndexed { index, line ->
                addView(text(line, 15f, alpha = 0.9f).apply {
                    gravity = Gravity.START
                    maxLines = 3
                    if (index > 0) setPadding(0, dp(10), 0, 0)
                })
            }
            if (apps.isNotEmpty()) {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, if (lines.isEmpty()) 0 else dp(12), 0, 0)
                    apps.forEach { badge ->
                        addView(ImageView(activity).apply {
                            setImageDrawable(OmnioAppTags.icon(activity, badge))
                            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) }
                        })
                    }
                })
            }
        }
    }

    private fun quickActions(alignStart: Boolean): View? {
        val actions = listOfNotNull(
            Triple(R.drawable.ic_sms_vector, R.string.omnio_call_message, onMessage).takeIf { design.quickMessage },
            Triple(R.drawable.ic_bell_vector, R.string.omnio_call_silence, onSilence).takeIf { design.quickSilence },
        )
        if (actions.isEmpty()) return null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (alignStart) Gravity.START else Gravity.CENTER
            actions.forEach { (icon, label, action) ->
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(10), dp(18), dp(10))
                    background = activity.getDrawable(R.drawable.omnio_round_bg)
                    backgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = if (alignStart) 0 else dp(6); marginEnd = dp(if (alignStart) 10 else 6)
                    }
                    addView(ImageView(activity).apply {
                        setImageResource(icon)
                        imageTintList = ColorStateList.valueOf(design.textColor)
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                    })
                    addView(text(activity.getString(label), 14f))
                    setOnClickListener { action() }
                })
            }
        }
    }

    // ---- answer controls ----

    private fun answerControl(): View = OmnioAnswerControls(
        activity, this, design, darkTrack = isPoster || design.background == Background.PHOTO_BLUR, animators, onAnswer, onDecline
    ).build()

    // ---- layouts ----

    /** Fills the screen: top content, flexible gap, bottom content. */
    private fun screen(top: View, bottom: View, padTop: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(padTop), dp(24), dp(48))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(top)
        addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        addView(bottom)
    }

    private fun centredContent(): View {
        val top = column().apply {
            if (design.photoShape != PhotoShape.HIDDEN) addView(photo())
            else addView(View(activity), LinearLayout.LayoutParams(1, dp(60)))
            addView(text(info?.displayName.orEmpty(), design.nameSize.toFloat(), bold = true).apply { setPadding(0, dp(18), 0, 0) })
            subtitle().takeIf { it.isNotEmpty() }?.let { addView(text(it, 16f, alpha = 0.75f).apply { setPadding(0, dp(6), 0, 0) }) }
            addView(status().apply { setPadding(0, dp(6), 0, 0) })
            contextCard(alignStart = false)?.let { addView(it) }
        }
        val bottom = column().apply {
            quickActions(alignStart = false)?.let {
                addView(it)
                addView(View(activity), LinearLayout.LayoutParams(1, dp(30)))
            }
            addView(answerControl())
        }
        return screen(top, bottom, padTop = if (design.pulse || design.orbit) 40 else 64)
    }

    private fun posterContent(): View {
        val top = column(Gravity.START).apply { addView(status(0.85f).apply { gravity = Gravity.START }) }
        val bottom = column(Gravity.START).apply {
            addView(text(info?.displayName.orEmpty(), design.nameSize.toFloat(), bold = true).apply { gravity = Gravity.START })
            subtitle().takeIf { it.isNotEmpty() }?.let { addView(text(it, 16f, alpha = 0.8f).apply { gravity = Gravity.START; setPadding(0, dp(4), 0, 0) }) }
            contextCard(alignStart = true)?.let { addView(it) }
            quickActions(alignStart = true)?.let {
                addView(View(activity), LinearLayout.LayoutParams(1, dp(18)))
                addView(it)
            }
            addView(View(activity), LinearLayout.LayoutParams(1, dp(26)))
            addView(answerControl())
        }
        return screen(top, bottom, padTop = 56)
    }
}

/** The Omnio logo ring: four coloured arcs (calls, messages, contacts, socials) with small gaps. */
@SuppressLint("ViewConstructor")
class OrbitRing(context: android.content.Context, private val stroke: Float) : View(context) {
    private val colours = intArrayOf(OmnioColors.CALLS, OmnioColors.MESSAGES, OmnioColors.CONTACTS, OmnioColors.SOCIALS)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val oval = android.graphics.RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val half = stroke / 2 + 1
        oval.set(half, half, width - half, height - half)
        colours.forEachIndexed { index, colour ->
            paint.color = colour
            canvas.drawArc(oval, -90f + index * 90f + 9f, 72f, false, paint)
        }
    }
}
