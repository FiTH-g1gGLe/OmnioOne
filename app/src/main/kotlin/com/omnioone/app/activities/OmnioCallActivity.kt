package com.omnioone.app.activities

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
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.ensureBackgroundThread
import com.omnioone.app.R
import com.omnioone.app.extensions.config
import com.omnioone.app.helpers.OmnioAppTags
import com.omnioone.app.helpers.OmnioCallInfo
import com.omnioone.app.helpers.OmnioCallStyle
import com.omnioone.app.helpers.OmnioCalls
import com.omnioone.app.helpers.OmnioCallScreen
import com.omnioone.app.helpers.OmnioSheet

/**
 * Omnio One call screen, written fresh.
 * Incoming calls use one of five styles (Classic, Poster, Minimal, Context, Pulse); once
 * connected, every style shares the same in-call screen (mute, keypad, speaker, hold, add call).
 * Open with EXTRA_PREVIEW to try a style with a pretend caller ("Test call").
 */
class OmnioCallActivity : SimpleActivity() {

    companion object {
        const val EXTRA_PREVIEW = "omnio_call_preview"
        /** Preview a saved style by key ("builtin:3" / "custom:…"). */
        const val EXTRA_STYLE_KEY = "omnio_call_style_key"
        /** Preview an unsaved design (JSON), used by the designer. */
        const val EXTRA_DESIGN = "omnio_call_design"

        fun startIntent(context: Context): Intent = Intent(context, OmnioCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var design: OmnioCallStyle.Design = OmnioCallStyle.preset(OmnioCallStyle.CONTEXT)
    private var previewDesign: OmnioCallStyle.Design? = null
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
    private var pulseAnimators = ArrayList<android.animation.Animator>()
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
        if (preview) {
            previewDesign = intent.getStringExtra(EXTRA_DESIGN)?.let {
                try {
                    OmnioCallStyle.Design.fromJson(org.json.JSONObject(it))
                } catch (e: Exception) {
                    null
                }
            } ?: OmnioCallStyle.design(this, intent.getStringExtra(EXTRA_STYLE_KEY) ?: OmnioCallStyle.defaultKey(this))
        }
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
            design = previewDesign ?: OmnioCallStyle.resolve(this, info?.contactId)
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
            com.omnioone.app.messaging.OmnioSender.send(this, text, listOf(number), -1)
            toast(R.string.omnio_reply_sent)
        } catch (e: Exception) {
            toast(com.omnioone.commons.R.string.unknown_error_occurred)
        }
    }

    // ---- shared pieces ----

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, alpha: Float = 1f, color: Int = design.textColor) =
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
        com.omnioone.app.helpers.OmnioDisplay.loadAvatar(this, who?.photoUri.orEmpty(), who?.displayName ?: "?", 10000)
    }

    private fun column(gravity: Int = Gravity.CENTER_HORIZONTAL) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
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

    /** Fills the screen: top content, flexible gap, bottom content. */
    private fun screen(top: View, bottom: View, padTop: Int = 72) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(padTop), dp(24), dp(48))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(top)
        addView(View(this@OmnioCallActivity), LinearLayout.LayoutParams(1, 0, 1f))
        addView(bottom)
    }

    // ---- incoming call: drawn from the chosen design ----

    private fun buildRinging() {
        val screen = OmnioCallScreen(this, design, info, ::answer, ::decline, ::replyWithText, ::silence)
        root.background = null
        root.addView(screen.build().apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        statusText = screen.statusView
        pulseAnimators.addAll(screen.animators)
    }

    // ---- connected call (shared by every style) ----

    private fun buildInCall() {
        // same background as the chosen design (a full photo is blurred so the buttons stay readable)
        val inCallDesign = design.copy(
            background = if (design.background == OmnioCallStyle.Background.PHOTO_FULL) OmnioCallStyle.Background.PHOTO_BLUR else design.background
        )
        val backdrop = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        OmnioCallScreen(this, inCallDesign, info, {}, {}, {}, {}).applyBackground(backdrop)
        root.background = null
        root.addView(backdrop)
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
            addView(roundButton(R.drawable.ic_phone_down_vector, 0xFFE5484D.toInt(), null, sizeDp = 76) { hangUp() })
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
            val rawId = com.omnioone.app.core.OmnioContactData.firstRawId(this, id) ?: return@ensureBackgroundThread
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
                addView(roundButton(R.drawable.ic_phone_down_vector, 0xFFE5484D.toInt(), null, sizeDp = 68) { hangUp() })
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
