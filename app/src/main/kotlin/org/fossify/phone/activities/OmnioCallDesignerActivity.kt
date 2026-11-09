package org.fossify.phone.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.OmnioColors
import org.fossify.phone.R
import org.fossify.phone.helpers.OmnioCallInfo
import org.fossify.phone.helpers.OmnioCallScreen
import org.fossify.phone.helpers.OmnioCallStyle
import org.fossify.phone.helpers.OmnioCallStyle.Answer
import org.fossify.phone.helpers.OmnioCallStyle.Background
import org.fossify.phone.helpers.OmnioCallStyle.Design
import org.fossify.phone.helpers.OmnioCallStyle.PhotoShape
import org.fossify.phone.helpers.OmnioSheet

/**
 * Omnio One call screen designer: change any part of the incoming-call screen and watch a
 * live mini preview; save it as your own style.
 */
class OmnioCallDesignerActivity : SimpleActivity() {

    companion object {
        /** Key of a saved custom design to edit; leave out to start a new one. */
        const val EXTRA_EDIT_KEY = "omnio_design_key"
    }

    private lateinit var design: Design
    private var editingKey: String? = null
    private lateinit var previewHolder: FrameLayout
    private lateinit var form: LinearLayout
    private var previewAnimators = ArrayList<android.animation.Animator>()

    private val swatches = listOf(
        OmnioColors.BACKGROUND, 0xFF101828.toInt(), 0xFF1B2450.toInt(), 0xFF2B3A67.toInt(), 0xFF3B1F4A.toInt(), 0xFF0E3B2E.toInt(),
        0xFF4A1F1F.toInt(), 0xFF2A2A2A.toInt(), OmnioColors.CALLS, OmnioColors.MESSAGES, OmnioColors.CONTACTS, OmnioColors.SOCIALS,
        OmnioColors.FAVOURITES, 0xFFE5484D.toInt(), 0xFF8B5CF6.toInt(), 0xFF14B8A6.toInt(), OmnioColors.TEXT, 0xFF000000.toInt()
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingKey = intent.getStringExtra(EXTRA_EDIT_KEY)
        design = editingKey?.let { OmnioCallStyle.design(this, it)?.copy() } ?: OmnioCallStyle.preset(OmnioCallStyle.CONTEXT).copy(id = "", name = "")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        root.addView(topBar())
        previewHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340))
        }
        root.addView(previewHolder)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(40))
        }
        root.addView(NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(form)
        })
        setContentView(root)
        refresh()
    }

    override fun onDestroy() {
        previewAnimators.forEach { it.cancel() }
        super.onDestroy()
    }

    private fun refresh() {
        showPreview()
        buildForm()
    }

    // ---- live preview: the real call screen, shrunk into a phone frame ----

    private fun showPreview() {
        previewAnimators.forEach { it.cancel() }
        previewAnimators.clear()
        previewHolder.removeAllViews()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val frameH = dp(320)
        val scale = frameH.toFloat() / screenH
        val frameW = (screenW * scale).toInt()
        val phone = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(frameW + dp(8), frameH + dp(8), Gravity.CENTER)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFF2A2F3A.toInt())
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToOutline = true
        }
        val clip = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(frameW, frameH)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
            clipToOutline = true
        }
        val screen = OmnioCallScreen(this, design, OmnioCallInfo.preview(), {}, {}, {}, {})
        val full = screen.build().apply {
            layoutParams = FrameLayout.LayoutParams(screenW, screenH)
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }
        previewAnimators.addAll(screen.animators)
        clip.addView(full)
        phone.addView(clip)
        previewHolder.addView(phone)
    }

    // ---- top bar ----

    private fun topBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(36), dp(16), dp(8))
        addView(ImageView(this@OmnioCallDesignerActivity).apply {
            setImageResource(org.fossify.commons.R.drawable.ic_cross_vector)
            imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { finish() }
        })
        addView(TextView(this@OmnioCallDesignerActivity).apply {
            text = getString(R.string.omnio_designer_title)
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@OmnioCallDesignerActivity).apply {
            text = getString(org.fossify.commons.R.string.save)
            setTextColor(OmnioColors.ON_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            background = getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioColors.CALLS)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42))
            setOnClickListener { save() }
        })
    }

    // ---- options ----

    private fun buildForm() {
        form.removeAllViews()

        section("✨", R.string.omnio_designer_start)
        card(
            action(R.string.omnio_designer_start_from, getString(R.string.omnio_designer_start_from_desc)) { pickStartingPoint() },
            action(R.string.omnio_designer_test, getString(R.string.omnio_settings_test_call_desc)) { testCall() },
        )

        section("🖼️", R.string.omnio_designer_background)
        card(
            action(R.string.omnio_designer_background_type, backgroundName(design.background)) {
                choose(R.string.omnio_designer_background_type, Background.entries.map { backgroundName(it) }) { design.background = Background.entries[it] }
            },
            colourRow(R.string.omnio_designer_background_colour, design.backgroundColor) { design.backgroundColor = it },
            colourRow(R.string.omnio_designer_gradient_colour, design.gradientColor) { design.gradientColor = it },
        )

        section("👤", R.string.omnio_photo)
        card(
            action(R.string.omnio_designer_photo_shape, shapeName(design.photoShape)) {
                choose(R.string.omnio_designer_photo_shape, PhotoShape.entries.map { shapeName(it) }) { design.photoShape = PhotoShape.entries[it] }
            },
            action(R.string.omnio_designer_photo_size, sizeName(design.photoSize)) {
                val sizes = listOf(96, 120, 150, 180)
                choose(R.string.omnio_designer_photo_size, sizes.map { sizeName(it) }) { design.photoSize = sizes[it] }
            },
            switch(R.string.omnio_designer_pulse, design.pulse) { design.pulse = it },
            action(R.string.omnio_designer_name_size, "${design.nameSize}") {
                val sizes = listOf(26, 30, 34, 40, 46)
                choose(R.string.omnio_designer_name_size, sizes.map { "$it" }) { design.nameSize = sizes[it] }
            },
        )

        section("📞", R.string.omnio_designer_answer)
        card(
            action(R.string.omnio_designer_answer_style, answerName(design.answer)) {
                choose(R.string.omnio_designer_answer_style, Answer.entries.map { answerName(it) }) { design.answer = Answer.entries[it] }
            },
            colourRow(R.string.omnio_designer_accept_colour, design.acceptColor) { design.acceptColor = it },
            colourRow(R.string.omnio_designer_decline_colour, design.declineColor) { design.declineColor = it },
            colourRow(R.string.omnio_designer_text_colour, design.textColor) { design.textColor = it },
        )

        section("👀", R.string.omnio_designer_show)
        card(
            switch(R.string.omnio_designer_show_number, design.showNumber) { design.showNumber = it },
            switch(R.string.omnio_designer_show_last_text, design.showLastText) { design.showLastText = it },
            switch(R.string.omnio_designer_show_birthday, design.showBirthday) { design.showBirthday = it },
            switch(R.string.omnio_designer_show_notes, design.showNotes) { design.showNotes = it },
            switch(R.string.omnio_designer_show_apps, design.showApps) { design.showApps = it },
        )

        section("⚡", R.string.omnio_designer_quick)
        card(
            switch(R.string.omnio_call_message, design.quickMessage) { design.quickMessage = it },
            switch(R.string.omnio_call_silence, design.quickSilence) { design.quickSilence = it },
        )

        if (editingKey?.startsWith("custom:") == true) {
            form.addView(TextView(this).apply {
                text = getString(R.string.omnio_designer_delete)
                setTextColor(OmnioColors.MISSED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(OmnioColors.DANGER_CARD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(28) }
                setOnClickListener { confirmDelete() }
            })
        }
    }

    private fun backgroundName(value: Background) = getString(
        when (value) {
            Background.SOLID -> R.string.omnio_designer_bg_solid
            Background.GRADIENT -> R.string.omnio_designer_bg_gradient
            Background.PHOTO_BLUR -> R.string.omnio_designer_bg_blur
            Background.PHOTO_FULL -> R.string.omnio_designer_bg_full
        }
    )

    private fun shapeName(value: PhotoShape) = getString(
        when (value) {
            PhotoShape.CIRCLE -> R.string.omnio_designer_shape_circle
            PhotoShape.ROUNDED -> R.string.omnio_designer_shape_rounded
            PhotoShape.HIDDEN -> R.string.omnio_designer_shape_hidden
        }
    )

    private fun answerName(value: Answer) = getString(
        when (value) {
            Answer.BUTTONS -> R.string.omnio_designer_answer_buttons
            Answer.SLIDE -> R.string.omnio_designer_answer_slide
            Answer.SWIPE_UP -> R.string.omnio_designer_answer_swipe
        }
    )

    private fun sizeName(value: Int) = getString(
        when {
            value <= 96 -> R.string.omnio_designer_size_small
            value <= 120 -> R.string.omnio_designer_size_medium
            value <= 150 -> R.string.omnio_designer_size_large
            else -> R.string.omnio_designer_size_huge
        }
    )

    private fun choose(titleRes: Int, options: List<String>, apply: (Int) -> Unit) {
        OmnioSheet.list(this, getString(titleRes), null, options.map { OmnioSheet.Item(it) }) { picked ->
            apply(picked)
            refresh()
        }
    }

    private fun pickStartingPoint() {
        OmnioSheet.list(
            this,
            getString(R.string.omnio_designer_start_from),
            getString(R.string.omnio_designer_start_from_desc),
            OmnioCallStyle.all.map {
                OmnioSheet.Item(getString(OmnioCallStyle.name(it)) + "  ·  " + getString(OmnioCallStyle.description(it)))
            }
        ) { picked ->
            val keepId = design.id
            val keepName = design.name
            design = OmnioCallStyle.preset(OmnioCallStyle.all[picked]).copy(id = keepId, name = keepName)
            refresh()
        }
    }

    private fun testCall() {
        startActivity(Intent(this, OmnioCallActivity::class.java).apply {
            putExtra(OmnioCallActivity.EXTRA_PREVIEW, true)
            putExtra(OmnioCallActivity.EXTRA_DESIGN, design.toJson().toString())
        })
    }

    private fun save() {
        OmnioSheet.input(
            this,
            title = getString(R.string.omnio_designer_name_title),
            hint = getString(R.string.omnio_designer_name_hint),
            initial = design.name,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ) { typed ->
            design.name = typed.trim().ifEmpty { getString(R.string.omnio_call_style_custom) }
            val key = OmnioCallStyle.saveCustom(this, design)
            OmnioSheet.confirm(
                this,
                title = getString(R.string.omnio_designer_use_title, design.name),
                message = getString(R.string.omnio_designer_use_desc),
                positive = getString(R.string.omnio_designer_use_everyone),
                negative = getString(R.string.omnio_designer_just_save),
                onNegative = { finish() }
            ) {
                OmnioCallStyle.setDefaultKey(this, key)
                toast(R.string.omnio_designer_saved)
                finish()
            }
        }
    }

    private fun confirmDelete() {
        val key = editingKey ?: return
        OmnioSheet.confirm(
            this,
            title = getString(R.string.omnio_designer_delete_question, design.name),
            message = getString(R.string.omnio_designer_delete_desc),
            positive = getString(org.fossify.commons.R.string.delete),
            mainColor = OmnioColors.MISSED
        ) {
            OmnioCallStyle.deleteCustom(this, key)
            finish()
        }
    }

    // ---- building blocks ----

    private fun section(emoji: String, titleRes: Int) {
        form.addView(TextView(this).apply {
            text = emoji + "  " + getString(titleRes).uppercase()
            setTextColor(OmnioColors.TEXT)
            alpha = 0.75f
            letterSpacing = 0.05f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(22), 0, dp(8))
        })
    }

    private fun card(vararg rows: View) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioColors.CARD)
            setPadding(dp(16), dp(2), dp(12), dp(2))
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(View(this).apply {
                setBackgroundColor(0x1FFFFFFF)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            card.addView(row)
        }
        form.addView(card)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        background = getDrawable(android.R.drawable.list_selector_background)
    }

    private fun title(text: String, value: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@OmnioCallDesignerActivity).apply {
            this.text = text
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        if (!value.isNullOrEmpty()) addView(TextView(this@OmnioCallDesignerActivity).apply {
            this.text = value
            setTextColor(OmnioColors.TEXT)
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
    }

    private fun action(titleRes: Int, value: String?, onClick: () -> Unit) = row().apply {
        addView(title(getString(titleRes), value))
        addView(ImageView(this@OmnioCallDesignerActivity).apply {
            setImageResource(org.fossify.commons.R.drawable.ic_chevron_right_vector)
            imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        setOnClickListener { onClick() }
    }

    private fun switch(titleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) = row().apply {
        addView(title(getString(titleRes), null))
        val toggle = SwitchCompat(this@OmnioCallDesignerActivity).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(OmnioColors.TEXT, 0xFF9AA3AF.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(OmnioColors.MESSAGES, 0xFF3A404C.toInt()))
            setOnCheckedChangeListener { _, value ->
                onChange(value)
                showPreview()
            }
        }
        addView(toggle)
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun dot(colour: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(colour)
        setStroke(dp(2), 0x55FFFFFF)
        setSize(dp(sizeDp), dp(sizeDp))
    }

    private fun colourRow(titleRes: Int, colour: Int, apply: (Int) -> Unit) = row().apply {
        addView(title(getString(titleRes), String.format("#%06X", 0xFFFFFF and colour)))
        addView(ImageView(this@OmnioCallDesignerActivity).apply {
            setImageDrawable(dot(colour, 28))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(4) }
        })
        setOnClickListener { pickColour(titleRes, apply) }
    }

    /** Swatches, plus your own colour by hex code. */
    private fun pickColour(titleRes: Int, apply: (Int) -> Unit) {
        val items = swatches.map { OmnioSheet.Item(String.format("#%06X", 0xFFFFFF and it), icon = dot(it, 30)) } +
            OmnioSheet.Item(getString(R.string.omnio_designer_custom_colour), labelColor = OmnioColors.CONTACTS)
        OmnioSheet.list(this, getString(titleRes), null, items) { picked ->
            if (picked < swatches.size) {
                apply(swatches[picked])
                refresh()
            } else {
                OmnioSheet.input(this, getString(R.string.omnio_designer_custom_colour), hint = "#4F7CFF") { typed ->
                    val hex = typed.trim().removePrefix("#")
                    val parsed = hex.toLongOrNull(16)
                    if (parsed == null || (hex.length != 6 && hex.length != 8)) {
                        toast(R.string.omnio_designer_bad_colour)
                    } else {
                        apply((if (hex.length == 6) 0xFF000000 or parsed else parsed).toInt())
                        refresh()
                    }
                }
            }
        }
    }
}
