package org.fossify.phone.activities

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.OmnioColors
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.OmnioAppTags
import org.fossify.phone.helpers.OmnioCallInfo
import org.fossify.phone.helpers.OmnioCallStyle
import org.fossify.phone.helpers.OmnioCalls
import org.fossify.phone.helpers.OmnioSheet

/**
 * Omnio One call screen, written fresh.
 * Incoming calls use one of five styles (Classic, Poster, Minimal, Context, Pulse); once
 * connected, every style shares the same in-call screen (mute, keypad, speaker, hold, add call).
 * Open with EXTRA_PREVIEW to try a style with a pretend caller ("Test call").
 */
class OmnioCallActivity : SimpleActivity() {

    companion object {
        const val EXTRA_PREVIEW = "omnio_call_preview"
        const val EXTRA_STYLE = "omnio_call_style"

        fun startIntent(context: Context): Intent = Intent(context, OmnioCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var style = OmnioCallStyle.CONTEXT
    private var preview = false
    private var info: OmnioCallInfo? = null
    private var loadedNumber: String? = null
    private var shownState: OmnioCalls.State? = null
    private var previewState = OmnioCalls.State.RINGING
    private var previewConnectedAt = 0L
    private var previewMuted = false
    private var previewSpeaker = false
    private var statusText: TextView? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var pulseAnimators = ArrayList<ObjectAnimator>()
    private var finishing = false

    private val listener: () -> Unit = { render() }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        style = if (intent.hasExtra(EXTRA_STYLE)) intent.getIntExtra(EXTRA_STYLE, OmnioCallStyle.CONTEXT) else OmnioCallStyle.get(this)
        if (!preview && !OmnioCalls.hasCalls()) {
            finish()
            return
        }
        showOverLockScreen()
        root = FrameLayout(this).apply { setBackgroundColor(OmnioColors.BACKGROUND) }
        setContentView(root)
        if (preview) info = OmnioCallInfo.preview()
        render()
    }

    override fun onStart() {
        super.onStart()
        OmnioCalls.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        OmnioCalls.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        releaseProximity()
        pulseAnimators.forEach { it.cancel() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- state ----

    private fun currentState(): OmnioCalls.State = if (preview) previewState else OmnioCalls.stateOf(OmnioCalls.primary())

    private fun currentNumber(): String = if (preview) info?.number.orEmpty() else OmnioCalls.numberOf(OmnioCalls.primary())

    private fun render() {
        if (finishing) return
        if (!preview && !OmnioCalls.hasCalls()) {
            endAndClose()
            return
        }
        val number = currentNumber()
        if (!preview && number != loadedNumber) {
            loadedNumber = number
            info = OmnioCallInfo(number, "", "", "", null, null, null, null, emptyList())
            ensureBackgroundThread {
                val loaded = OmnioCallInfo.load(this, number)
                runOnUiThread {
                    if (loadedNumber == number) {
                        info = loaded
                        shownState = null
                        render()
                    }
                }
            }
        }
        val state = currentState()
        if (state == OmnioCalls.State.ENDED) {
            if (!preview && OmnioCalls.count() > 1) return
            endAndClose()
            return
        }
        val isRinging = state == OmnioCalls.State.RINGING
        if (shownState == null || state != shownState || (!isRinging && OmnioCalls.count() > 1)) {
            root.removeAllViews()
            pulseAnimators.forEach { it.cancel() }
            pulseAnimators.clear()
            if (isRinging) buildRinging() else buildInCall()
        }
        shownState = state
        updateStatus()
        updateProximity(state)
    }

    private fun endAndClose() {
        if (finishing) return
        finishing = true
        statusText?.text = getString(R.string.omnio_call_ended)
        releaseProximity()
        handler.postDelayed({ finish() }, if (preview) 300 else 1100)
    }

    private fun updateStatus() {
        val label = statusText ?: return
        val state = currentState()
        label.text = when (state) {
            OmnioCalls.State.RINGING -> getString(R.string.omnio_call_incoming)
            OmnioCalls.State.DIALING -> getString(R.string.omnio_call_calling)
            OmnioCalls.State.HOLDING -> getString(R.string.omnio_call_on_hold)
            OmnioCalls.State.ENDED -> getString(R.string.omnio_call_ended)
            OmnioCalls.State.ACTIVE -> {
                val since = if (preview) previewConnectedAt else OmnioCalls.connectedAt(OmnioCalls.primary())
                if (since <= 0) getString(R.string.omnio_call_connected) else formatDuration((System.currentTimeMillis() - since) / 1000)
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    // ---- actions ----

    private fun answer() {
        if (preview) {
            previewState = OmnioCalls.State.ACTIVE
            previewConnectedAt = System.currentTimeMillis()
            render()
        } else {
            OmnioCalls.answer()
        }
    }

    private fun decline() {
        if (preview) endAndClose() else OmnioCalls.decline()
    }

    private fun hangUp() {
        if (preview) endAndClose() else OmnioCalls.hangUp()
    }

    @SuppressLint("MissingPermission")
    private fun silence() {
        if (preview) return
        try {
            getSystemService(TelecomManager::class.java)?.silenceRinger()
        } catch (ignored: Exception) {
        }
    }

    /** Decline and send a quick text instead. */
    private fun replyWithText() {
        val replies = listOf(
            getString(R.string.omnio_reply_1), getString(R.string.omnio_reply_2),
            getString(R.string.omnio_reply_3), getString(R.string.omnio_reply_4)
        )
        val items = replies.map { OmnioSheet.Item(it) } + OmnioSheet.Item(getString(R.string.omnio_reply_custom), labelColor = OmnioColors.CONTACTS)
        OmnioSheet.list(this, getString(R.string.omnio_reply_title), getString(R.string.omnio_reply_desc), items) { picked ->
            if (picked < replies.size) {
                sendReply(replies[picked])
            } else {
                OmnioSheet.input(this, getString(R.string.omnio_reply_title), positive = getString(R.string.omnio_send), multiLine = true) { text ->
                    if (text.isNotBlank()) sendReply(text.trim())
                }
            }
        }
    }

    private fun sendReply(text: String) {
        val number = currentNumber()
        decline()
        if (preview || number.isEmpty()) return
        try {
            sendMessageCompat(text, listOf(number), null, emptyList())
            toast(R.string.omnio_reply_sent)
        } catch (e: Exception) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }

    // ---- shared pieces ----

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, alpha: Float = 1f, color: Int = OmnioColors.TEXT) =
        TextView(this).apply {
            text = value
            setTextColor(color)
            this.alpha = alpha
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun photo(sizeDp: Int): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val who = info
        SimpleContactsHelper(this@OmnioCallActivity).loadContactImage(who?.photoUri.orEmpty(), this, who?.displayName ?: "?")
    }

    private fun column(gravity: Int = Gravity.CENTER_HORIZONTAL) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    private fun subtitle(): String {
        val who = info ?: return ""
        return when {
            !who.isSaved -> ""
            who.label.isNotEmpty() -> "${who.number} · ${who.label}"
            else -> who.number
        }
    }

    private fun statusLabel(alpha: Float = 0.7f) = text("", 16f, alpha = alpha).also { statusText = it }

    private fun roundButton(icon: Int, colour: Int, label: String?, sizeDp: Int = 72, onClick: () -> Unit) = column().apply {
        addView(ImageView(this@OmnioCallActivity).apply {
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

    /** Message and Silence, shown on every incoming style. */
    private fun quickActions() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        listOf(
            Triple(R.drawable.ic_sms_vector, R.string.omnio_call_message) { replyWithText() },
            Triple(R.drawable.ic_bell_vector, R.string.omnio_call_silence) { silence() },
        ).forEach { (icon, label, action) ->
            addView(LinearLayout(this@OmnioCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(18), dp(10))
                background = getDrawable(R.drawable.omnio_round_bg)
                backgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6); marginEnd = dp(6)
                }
                addView(ImageView(this@OmnioCallActivity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                })
                addView(text(getString(label), 14f))
                setOnClickListener { action() }
            })
        }
    }

    private fun acceptDeclineRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(roundButton(R.drawable.ic_call_end, 0xFFE5484D.toInt(), getString(R.string.omnio_call_decline)) { decline() })
        addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(dp(110), 1))
        addView(roundButton(R.drawable.ic_call_accept, OmnioColors.MESSAGES, getString(R.string.omnio_call_accept)) { answer() })
    }

    /** Fills the screen: top content, flexible gap, bottom content. */
    private fun screen(top: View, bottom: View, padTop: Int = 72) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(padTop), dp(24), dp(48))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(top)
        addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, 0, 1f))
        addView(bottom)
    }

    // ---- incoming styles ----

    private fun buildRinging() {
        when (style) {
            OmnioCallStyle.POSTER -> buildPoster()
            OmnioCallStyle.MINIMAL -> buildMinimal()
            OmnioCallStyle.PULSE -> buildPulse()
            OmnioCallStyle.CLASSIC -> buildClassic()
            else -> buildContext()
        }
    }

    private fun nameAndNumber(nameSize: Float, into: LinearLayout) {
        into.addView(text(info?.displayName.orEmpty(), nameSize, bold = true).apply { setPadding(0, dp(18), 0, 0) })
        subtitle().takeIf { it.isNotEmpty() }?.let { into.addView(text(it, 16f, alpha = 0.75f).apply { setPadding(0, dp(6), 0, 0) }) }
        into.addView(statusLabel().apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun bottomBlock() = column().apply {
        addView(quickActions())
        addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, dp(32)))
        addView(acceptDeclineRow())
    }

    /** 1. Classic: round photo, name, two big buttons. */
    private fun buildClassic() {
        val top = column().apply {
            addView(photo(150))
            nameAndNumber(32f, this)
        }
        root.addView(screen(top, bottomBlock()))
    }

    /** 2. Poster: photo fills the screen, swipe up to answer. */
    private fun buildPoster() {
        val who = info
        val posterImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        if (!who?.photoUri.isNullOrEmpty()) {
            Glide.with(this).load(who?.photoUri).centerCrop().into(posterImage)
        } else {
            posterImage.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2B3A67.toInt(), 0xFF101828.toInt()))
            posterImage.setImageBitmap(null)
        }
        root.addView(posterImage)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xEE0F1115.toInt(), 0x660F1115, 0x220F1115))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        if (who?.photoUri.isNullOrEmpty()) {
            root.addView(text((who?.displayName ?: "?").take(1).uppercase(), 140f, bold = true, alpha = 0.18f).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(80) }
            })
        }
        val top = column(Gravity.START).apply {
            addView(statusLabel(0.85f).apply { gravity = Gravity.START })
        }
        val bottom = column(Gravity.START).apply {
            addView(text(who?.displayName.orEmpty(), 42f, bold = true).apply { gravity = Gravity.START })
            subtitle().takeIf { it.isNotEmpty() }?.let { addView(text(it, 16f, alpha = 0.8f).apply { gravity = Gravity.START; setPadding(0, dp(4), 0, dp(20)) }) }
            addView(quickActions().apply { gravity = Gravity.START })
            addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, dp(28)))
            addView(swipeUpToAnswer())
        }
        root.addView(screen(top, bottom, padTop = 56))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun swipeUpToAnswer() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(roundButton(R.drawable.ic_call_end, 0xFFE5484D.toInt(), getString(R.string.omnio_call_decline), sizeDp = 60) { decline() })
        addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(0, 1, 1f))
        val hint = text(getString(R.string.omnio_call_swipe_up), 14f, alpha = 0.8f)
        val handle = ImageView(this@OmnioCallActivity).apply {
            setImageResource(R.drawable.ic_call_accept)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OmnioColors.MESSAGES) }
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
            contentDescription = getString(R.string.omnio_call_accept)
        }
        var startY = 0f
        handle.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> { view.translationY = (event.rawY - startY).coerceAtMost(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (view.translationY < -dp(110)) {
                        answer()
                    } else {
                        view.animate().translationY(0f).setDuration(180).start()
                        if (kotlin.math.abs(event.rawY - startY) < dp(8)) hint.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction {
                            hint.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        }.start()
                    }
                    true
                }
                else -> false
            }
        }
        // screen readers: double tap answers
        handle.setOnClickListener { answer() }
        addView(column().apply {
            addView(handle)
            addView(hint.apply { setPadding(0, dp(8), 0, 0) })
        })
    }

    /** 3. Minimal: big name on a plain colour, slide to answer or decline. */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildMinimal() {
        val top = column().apply {
            addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, dp(60)))
            addView(text(info?.displayName.orEmpty(), 46f, bold = true))
            subtitle().takeIf { it.isNotEmpty() }?.let { addView(text(it, 17f, alpha = 0.7f).apply { setPadding(0, dp(8), 0, 0) }) }
            addView(statusLabel().apply { setPadding(0, dp(8), 0, 0) })
        }
        val track = FrameLayout(this).apply {
            background = getDrawable(R.drawable.omnio_round_bg)
            backgroundTintList = ColorStateList.valueOf(OmnioColors.CARD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76))
        }
        track.addView(text("‹  " + getString(R.string.omnio_call_decline), 14f, alpha = 0.6f, color = 0xFFFF8A8A.toInt()).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START).apply { marginStart = dp(20) }
        })
        track.addView(text(getString(R.string.omnio_call_answer) + "  ›", 14f, alpha = 0.6f, color = OmnioColors.MESSAGES_SOFT).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply { marginEnd = dp(20) }
        })
        val knob = ImageView(this).apply {
            setImageResource(R.drawable.ic_call_accept)
            imageTintList = ColorStateList.valueOf(OmnioColors.ON_ACCENT)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OmnioColors.TEXT) }
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            contentDescription = getString(R.string.omnio_call_answer)
        }
        var startX = 0f
        knob.setOnTouchListener { view, event ->
            val limit = (track.width - view.width) / 2f
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; true }
                MotionEvent.ACTION_MOVE -> { view.translationX = (event.rawX - startX).coerceIn(-limit, limit); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when {
                        view.translationX > limit * 0.7f -> answer()
                        view.translationX < -limit * 0.7f -> decline()
                        else -> view.animate().translationX(0f).setDuration(180).start()
                    }
                    true
                }
                else -> false
            }
        }
        knob.setOnClickListener { answer() }
        track.addView(knob)
        val bottom = column().apply {
            addView(quickActions())
            addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, dp(28)))
            addView(track)
        }
        root.addView(screen(top, bottom))
    }

    /** 4. Context: who's calling plus your last text, a birthday and notes. */
    private fun buildContext() {
        val who = info
        val top = column().apply {
            addView(photo(104))
            nameAndNumber(30f, this)
            val lines = listOfNotNull(who?.lastText, who?.birthday, who?.notes?.let { "📝 $it" })
            if (lines.isNotEmpty() || who?.apps?.isNotEmpty() == true) {
                addView(column(Gravity.START).apply {
                    background = getDrawable(R.drawable.omnio_round_bg)
                    backgroundTintList = ColorStateList.valueOf(OmnioColors.CARD)
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) }
                    lines.forEachIndexed { index, line ->
                        addView(text(line, 15f, alpha = 0.9f).apply {
                            gravity = Gravity.START
                            maxLines = 3
                            if (index > 0) setPadding(0, dp(10), 0, 0)
                        })
                    }
                    who?.apps?.takeIf { it.isNotEmpty() }?.let { apps ->
                        addView(LinearLayout(this@OmnioCallActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, if (lines.isEmpty()) 0 else dp(12), 0, 0)
                            apps.forEach { badge ->
                                addView(ImageView(this@OmnioCallActivity).apply {
                                    setImageDrawable(OmnioAppTags.icon(this@OmnioCallActivity, badge))
                                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) }
                                })
                            }
                        })
                    }
                })
            }
        }
        root.addView(screen(top, bottomBlock(), padTop = 64))
    }

    /** 5. Pulse: photo with rings in Omnio's four colours on a soft gradient. */
    private fun buildPulse() {
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B2450.toInt(), OmnioColors.BACKGROUND))
        val ringBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        listOf(OmnioColors.CALLS, OmnioColors.MESSAGES, OmnioColors.CONTACTS, OmnioColors.SOCIALS).forEachIndexed { index, colour ->
            val ring = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(3), colour)
                }
                layoutParams = FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER)
                alpha = 0f
            }
            ringBox.addView(ring)
            val scaleX = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.7f)
            val scaleY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.7f)
            val fade = android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
            ObjectAnimator.ofPropertyValuesHolder(ring, scaleX, scaleY, fade).apply {
                duration = 2400
                startDelay = index * 600L
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
                pulseAnimators.add(this)
            }
        }
        ringBox.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER)
            SimpleContactsHelper(this@OmnioCallActivity).loadContactImage(info?.photoUri.orEmpty(), this, info?.displayName ?: "?")
        })
        val top = column().apply {
            addView(ringBox)
            nameAndNumber(32f, this)
        }
        root.addView(screen(top, bottomBlock(), padTop = 40))
    }

    // ---- connected call (shared by every style) ----

    private fun buildInCall() {
        root.background = null
        root.setBackgroundColor(OmnioColors.BACKGROUND)
        val who = info
        val top = column().apply {
            addView(photo(96))
            addView(text(who?.displayName.orEmpty(), 30f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
            addView(statusLabel(0.75f).apply { setPadding(0, dp(6), 0, 0) })
            // a second call waiting on hold
            if (!preview && OmnioCalls.count() > 1) {
                val other = OmnioCalls.secondary()
                addView(text(getString(R.string.omnio_call_other_on_hold, OmnioCalls.numberOf(other)), 14f).apply {
                    background = getDrawable(R.drawable.omnio_round_bg)
                    backgroundTintList = ColorStateList.valueOf(OmnioColors.CARD)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL
                    }
                    setOnClickListener { OmnioCalls.swap() }
                })
            }
        }
        val grid = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val muted = if (preview) previewMuted else OmnioCalls.isMuted()
        val route = if (preview) (if (previewSpeaker) OmnioCalls.Route.SPEAKER else OmnioCalls.Route.EARPIECE) else OmnioCalls.currentRoute()
        val onHold = !preview && OmnioCalls.stateOf(OmnioCalls.primary()) == OmnioCalls.State.HOLDING
        val routeIcon = when (route) {
            OmnioCalls.Route.BLUETOOTH -> R.drawable.ic_bluetooth_audio_vector
            OmnioCalls.Route.HEADSET -> R.drawable.ic_headset_vector
            else -> R.drawable.ic_volume_up_vector
        }
        listOf(
            InCallButton(if (muted) R.drawable.ic_microphone_off_vector else R.drawable.ic_microphone_vector, R.string.omnio_call_mute, muted) { toggleMute() },
            InCallButton(R.drawable.ic_dialpad_vector, R.string.omnio_call_keypad, false) { showKeypad() },
            InCallButton(routeIcon, routeLabel(route), route != OmnioCalls.Route.EARPIECE) { chooseAudio() },
            InCallButton(R.drawable.ic_pause_vector, R.string.omnio_call_hold, onHold) { if (!preview) OmnioCalls.toggleHold() },
            InCallButton(R.drawable.ic_add_call_vector, R.string.omnio_call_add, false) { addCall() },
            InCallButton(R.drawable.ic_person_vector, R.string.omnio_call_contact, false) { openContact() },
        ).forEach { grid.addView(inCallButton(it)) }
        val bottom = column().apply {
            addView(grid)
            addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, dp(34)))
            addView(roundButton(R.drawable.ic_call_end, 0xFFE5484D.toInt(), null, sizeDp = 76) { hangUp() })
        }
        root.addView(screen(top, bottom, padTop = 64))
    }

    private data class InCallButton(val icon: Int, val label: Int, val on: Boolean, val onClick: () -> Unit)

    private fun inCallButton(button: InCallButton) = column().apply {
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp(100)
            setMargins(0, dp(10), 0, dp(10))
        }
        addView(ImageView(this@OmnioCallActivity).apply {
            setImageResource(button.icon)
            imageTintList = ColorStateList.valueOf(if (button.on) OmnioColors.ON_ACCENT else OmnioColors.TEXT)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (button.on) OmnioColors.TEXT else OmnioColors.CARD) }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            contentDescription = getString(button.label)
            setOnClickListener { button.onClick() }
        })
        addView(text(getString(button.label), 13f, alpha = 0.8f).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun routeLabel(route: OmnioCalls.Route) = when (route) {
        OmnioCalls.Route.BLUETOOTH -> R.string.omnio_call_bluetooth
        OmnioCalls.Route.HEADSET -> R.string.omnio_call_headset
        else -> R.string.omnio_call_speaker
    }

    private fun rebuildInCall() {
        shownState = null
        render()
    }

    private fun toggleMute() {
        if (preview) previewMuted = !previewMuted else OmnioCalls.setMuted(!OmnioCalls.isMuted())
        handler.postDelayed({ rebuildInCall() }, 150)
    }

    /** Speaker on/off, or a choice when Bluetooth or a headset is connected. */
    private fun chooseAudio() {
        if (preview) {
            previewSpeaker = !previewSpeaker
            rebuildInCall()
            return
        }
        val routes = OmnioCalls.availableRoutes()
        if (routes.size <= 2 && routes.all { it == OmnioCalls.Route.EARPIECE || it == OmnioCalls.Route.SPEAKER }) {
            OmnioCalls.setRoute(if (OmnioCalls.currentRoute() == OmnioCalls.Route.SPEAKER) OmnioCalls.Route.EARPIECE else OmnioCalls.Route.SPEAKER)
            handler.postDelayed({ rebuildInCall() }, 250)
            return
        }
        val current = OmnioCalls.currentRoute()
        OmnioSheet.list(
            this,
            getString(R.string.omnio_call_audio),
            null,
            routes.map { route ->
                val name = getString(
                    when (route) {
                        OmnioCalls.Route.EARPIECE -> R.string.omnio_call_phone
                        OmnioCalls.Route.SPEAKER -> R.string.omnio_call_speaker
                        OmnioCalls.Route.BLUETOOTH -> R.string.omnio_call_bluetooth
                        OmnioCalls.Route.HEADSET -> R.string.omnio_call_headset
                    }
                )
                OmnioSheet.Item(name, trailing = if (route == current) "✓" else null)
            }
        ) { picked ->
            OmnioCalls.setRoute(routes[picked])
            handler.postDelayed({ rebuildInCall() }, 250)
        }
    }

    private fun addCall() {
        if (preview) return
        try {
            startActivity(Intent(this, DialpadActivity::class.java))
        } catch (ignored: Exception) {
        }
    }

    private fun openContact() {
        if (preview) return
        val id = info?.contactId ?: run {
            toast(R.string.omnio_not_saved_contact_call)
            return
        }
        ensureBackgroundThread {
            val rawId = org.fossify.commons.helpers.OmnioContactData.firstRawId(this, id) ?: return@ensureBackgroundThread
            runOnUiThread {
                startActivity(Intent(this, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, rawId.toInt()))
            }
        }
    }

    /** Number keys for menus ("press 1 for…"). */
    private fun showKeypad() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xF20F1115.toInt())
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val typed = text("", 34f).apply { setPadding(0, dp(90), 0, dp(20)); maxLines = 1 }
        val keys = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        "123456789*0#".forEach { digit ->
            keys.addView(text(digit.toString(), 30f).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OmnioColors.CARD) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(76); height = dp(76)
                    setMargins(dp(12), dp(10), dp(12), dp(10))
                }
                setOnClickListener {
                    typed.text = (typed.text.toString() + digit).takeLast(18)
                    if (!preview) OmnioCalls.keypadTone(digit)
                }
            })
        }
        overlay.addView(column().apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(typed)
            addView(keys)
            addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(LinearLayout(this@OmnioCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(48))
                addView(text(getString(R.string.omnio_call_hide_keypad), 16f, color = OmnioColors.CALLS).apply {
                    setPadding(dp(20), dp(12), dp(28), dp(12))
                    setOnClickListener { root.removeView(overlay) }
                })
                addView(roundButton(R.drawable.ic_call_end, 0xFFE5484D.toInt(), null, sizeDp = 68) { hangUp() })
            })
        })
        root.addView(overlay)
    }

    // ---- screen off near the ear ----

    @Suppress("DEPRECATION")
    private fun updateProximity(state: OmnioCalls.State) {
        val wanted = !preview && !config.disableProximitySensor &&
            (state == OmnioCalls.State.ACTIVE || state == OmnioCalls.State.DIALING) &&
            OmnioCalls.currentRoute() == OmnioCalls.Route.EARPIECE
        if (wanted && proximityLock?.isHeld != true) {
            val power = getSystemService(PowerManager::class.java) ?: return
            if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "omnioone:proximity").apply {
                acquire(60 * 60 * 1000L)
            }
        } else if (!wanted) {
            releaseProximity()
        }
    }

    private fun releaseProximity() {
        try {
            if (proximityLock?.isHeld == true) proximityLock?.release()
        } catch (ignored: Exception) {
        }
        proximityLock = null
    }
}
