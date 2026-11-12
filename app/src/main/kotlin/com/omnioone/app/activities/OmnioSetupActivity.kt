package com.omnioone.app.activities

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.omnioone.app.R
import com.omnioone.app.core.OmnioActivity
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.TAB_CALL_HISTORY
import com.omnioone.app.core.TAB_CONTACTS
import com.omnioone.app.core.TAB_FAVORITES
import com.omnioone.app.core.TAB_LAST_USED
import com.omnioone.app.core.canUseFullScreenIntent
import com.omnioone.app.core.openFullScreenIntentSettings
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OrbitRing
import com.omnioone.app.phone.config
import kotlin.math.abs

/**
 * Omnio One's first-run setup: a welcome, a quick tour, the permissions and default apps
 * Omnio needs (each explained, each skippable), a couple of choices to make it yours, then
 * straight into the app. Shown once; it can be run again from Settings.
 */
class OmnioSetupActivity : OmnioActivity() {

    private enum class Step { WELCOME, PEOPLE, CALLS, MESSAGES, PERMISSIONS, PERSONALISE, DONE }

    private val steps = Step.values()
    private var index = 0

    private lateinit var stage: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var backButton: TextView
    private lateinit var nextButton: TextView

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showStep(animate = false) }
    private val askRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterRole() }
    private var roleAsked: String? = null
    private var swipe: GestureDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt(KEY_STEP) ?: 0
        buildFrame()
        showStep(animate = false)
    }

    override fun onResume() {
        super.onResume()
        // back from Android's settings: refresh the ticks
        if (steps[index] == Step.PERMISSIONS) showStep(animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, index)
    }

    override fun onBackPressedCompat(): Boolean {
        if (index == 0) return false
        go(-1)
        return true
    }

    // ---- frame: the page area, the dots and the buttons ----

    private fun buildFrame() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        stage = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        root.addView(stage)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(20))
        }
        dots = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        bottom.addView(dots)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        backButton = TextView(this).apply {
            setTextColor(OmnioColors.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(14), dp(8), dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { go(-1) }
        }
        nextButton = TextView(this).apply {
            setTextColor(OmnioColors.ON_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = rounded(OmnioColors.CONTACTS, 28)
            setOnClickListener { if (steps[index] == Step.DONE) finishSetup() else go(1) }
        }
        buttons.addView(backButton)
        buttons.addView(nextButton)
        bottom.addView(buttons)
        root.addView(bottom)
        setContentView(root)
        setupEdgeToEdge(padTopSystem = listOf(stage), padBottomSystem = listOf(bottom))

        // swipe between the welcome and tour pages too
        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || abs(velocityX) < abs(velocityY) || abs(e2.x - e1.x) < dp(60)) return false
                go(if (e2.x < e1.x) 1 else -1)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (index <= Step.MESSAGES.ordinal) swipe?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun go(delta: Int) {
        val target = (index + delta).coerceIn(0, steps.lastIndex)
        if (target == index) return
        val forward = target > index
        index = target
        showStep(animate = true, forward = forward)
    }

    private fun showStep(animate: Boolean, forward: Boolean = true) {
        val page = when (steps[index]) {
            Step.WELCOME -> welcomePage()
            Step.PEOPLE -> tourPage("👤", R.string.omnio_setup_people_title, R.string.omnio_setup_people_text, OmnioColors.CONTACTS)
            Step.CALLS -> tourPage("📞", R.string.omnio_setup_calls_title, R.string.omnio_setup_calls_text, OmnioColors.CALLS)
            Step.MESSAGES -> tourPage("💬", R.string.omnio_setup_messages_title, R.string.omnio_setup_messages_text, OmnioColors.MESSAGES)
            Step.PERMISSIONS -> permissionsPage()
            Step.PERSONALISE -> personalisePage()
            Step.DONE -> donePage()
        }
        val old = stage.getChildAt(0)
        stage.addView(page)
        if (animate && old != null) {
            val shift = dp(48).toFloat() * if (forward) 1 else -1
            page.alpha = 0f
            page.translationX = shift
            page.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            old.animate().alpha(0f).translationX(-shift).setDuration(200).withEndAction { stage.removeView(old) }.start()
        } else if (old != null) {
            stage.removeView(old)
        }
        updateControls()
    }

    private fun updateControls() {
        val step = steps[index]
        backButton.text = if (index == 0) "" else getString(R.string.omnio_setup_back)
        backButton.isEnabled = index > 0
        nextButton.text = getString(
            when (step) {
                Step.WELCOME -> R.string.omnio_setup_get_started
                Step.DONE -> R.string.omnio_setup_open_app
                Step.PERMISSIONS -> if (permissionsDone()) R.string.omnio_setup_next else R.string.omnio_setup_skip_for_now
                else -> R.string.omnio_setup_next
            }
        )
        dots.removeAllViews()
        steps.indices.forEach { i ->
            dots.addView(View(this).apply {
                val active = i == index
                layoutParams = LinearLayout.LayoutParams(dp(if (active) 22 else 8), dp(8)).apply { marginStart = dp(3); marginEnd = dp(3) }
                background = rounded(if (active) OmnioColors.CONTACTS else OmnioColors.CARD, 4)
            })
        }
    }

    // ---- pages ----

    private fun page(content: LinearLayout.() -> Unit): View = NestedScrollView(this).apply {
        isFillViewport = true
        addView(LinearLayout(this@OmnioSetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            content()
        })
    }

    private fun welcomePage() = page {
        gravity = Gravity.CENTER
        addView(logo())
        addView(text(getString(R.string.app_launcher_name), 32f, OmnioColors.TEXT, bold = true, top = 22))
        addView(text(getString(R.string.omnio_signature_tagline), 18f, OmnioColors.TEXT_MUTED, top = 6).apply { setTypeface(typeface, Typeface.ITALIC) })
        addView(text(getString(R.string.omnio_setup_welcome_text), 16f, OmnioColors.TEXT, top = 28))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
            listOf("👤" to OmnioColors.CONTACTS, "📞" to OmnioColors.CALLS, "💬" to OmnioColors.MESSAGES, "🔗" to OmnioColors.SOCIALS).forEach { (emoji, colour) ->
                addView(badge(emoji, colour, 52).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(6); marginEnd = dp(6) }
                })
            }
        })
    }

    /** The Omnio logo with the ring spinning once around it. */
    private fun logo(): View {
        val size = 140
        val ring = OrbitRing(this, dp(3).toFloat()).apply { layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER) }
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            addView(FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(size - 20), dp(size - 20), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF101828.toInt())
                }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    layoutParams = FrameLayout.LayoutParams(dp(size - 20), dp(size - 20))
                })
            })
            addView(ring)
        }
        ring.postDelayed({ ring.animate().rotationBy(360f).setDuration(1100).setInterpolator(DecelerateInterpolator()).start() }, 300)
        return frame
    }

    private fun tourPage(emoji: String, title: Int, body: Int, colour: Int) = page {
        gravity = Gravity.CENTER
        addView(badge(emoji, colour, 96).apply { (getChildAt(0) as TextView).setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f) })
        addView(text(getString(title), 26f, OmnioColors.TEXT, bold = true, top = 28))
        val points = getString(body).split("\n")
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
            points.forEach { point ->
                addView(TextView(context).apply {
                    text = point
                    setTextColor(OmnioColors.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setLineSpacing(0f, 1.15f)
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    background = rounded(OmnioColors.CARD, 16)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                })
            }
        })
    }

    // ---- permissions ----

    private data class Need(val emoji: String, val title: Int, val why: Int, val colour: Int, val done: () -> Boolean, val optional: Boolean, val ask: () -> Unit)

    private fun needs(): List<Need> = listOf(
        Need("👤", R.string.omnio_setup_perm_contacts, R.string.omnio_setup_perm_contacts_why, OmnioColors.CONTACTS,
            { has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.WRITE_CONTACTS) }, false) {
            askPermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
        },
        Need("📞", R.string.omnio_setup_perm_phone, R.string.omnio_setup_perm_phone_why, OmnioColors.CALLS, { holds(RoleManager.ROLE_DIALER) }, false) {
            askForRole(RoleManager.ROLE_DIALER)
        },
        Need("💬", R.string.omnio_setup_perm_sms, R.string.omnio_setup_perm_sms_why, OmnioColors.MESSAGES, { holds(RoleManager.ROLE_SMS) }, false) {
            askForRole(RoleManager.ROLE_SMS)
        },
        Need("🔔", R.string.omnio_setup_perm_notifications, R.string.omnio_setup_perm_notifications_why, OmnioColors.FAVOURITES, { notificationsReady() }, false) {
            askNotifications()
        },
        Need("🪟", R.string.omnio_setup_perm_overlay, R.string.omnio_setup_perm_overlay_why, OmnioColors.SOCIALS, { Settings.canDrawOverlays(this) }, true) {
            config.wasOverlaySnackbarConfirmed = true
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        },
    )

    private fun permissionsDone() = needs().filter { !it.optional }.all { it.done() }

    private fun permissionsPage() = page {
        addView(text(getString(R.string.omnio_setup_perm_title), 26f, OmnioColors.TEXT, bold = true))
        addView(text(getString(R.string.omnio_setup_perm_text), 15f, OmnioColors.TEXT_MUTED, top = 8))
        needs().forEach { need -> addView(needRow(need)) }
        addView(text(getString(R.string.omnio_setup_perm_restricted), 13f, OmnioColors.TEXT_MUTED, top = 18))
    }

    private fun needRow(need: Need): View {
        val done = need.done()
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(OmnioColors.CARD, 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            addView(badge(need.emoji, need.colour, 44))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val title = getString(need.title) + if (need.optional) "  ·  " + getString(R.string.omnio_setup_optional) else ""
                addView(TextView(context).apply {
                    text = title
                    setTextColor(OmnioColors.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = getString(need.why)
                    setTextColor(OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(TextView(context).apply {
                if (done) {
                    text = "✓"
                    setTextColor(OmnioColors.MESSAGES)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(10), 0, dp(6), 0)
                } else {
                    text = getString(R.string.omnio_setup_allow)
                    setTextColor(OmnioColors.ON_ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = rounded(need.colour, 18)
                }
            })
            if (!done) setOnClickListener { need.ask() }
        }
    }

    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun holds(role: String) = try {
        getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true
    } catch (e: Exception) {
        false
    }

    private fun askForRole(role: String) {
        val manager = getSystemService(RoleManager::class.java) ?: return
        if (!manager.isRoleAvailable(role)) return
        roleAsked = role
        try {
            askRole.launch(manager.createRequestRoleIntent(role))
        } catch (e: Exception) {
            openSettings(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    /** Once Omnio is the phone or SMS app, ask for anything that role didn't already grant. */
    private fun afterRole() {
        val wanted = when (roleAsked) {
            RoleManager.ROLE_DIALER -> listOf(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            )
            RoleManager.ROLE_SMS -> listOf(
                Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS,
            )
            else -> emptyList()
        }.filterNot { has(it) }
        roleAsked = null
        if (wanted.isNotEmpty() && holdsAnyRole()) askPermissions.launch(wanted.toTypedArray()) else showStep(animate = false)
    }

    private fun holdsAnyRole() = holds(RoleManager.ROLE_DIALER) || holds(RoleManager.ROLE_SMS)

    private fun notificationsReady(): Boolean {
        val posting = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.POST_NOTIFICATIONS)
        return posting && canUseFullScreenIntent()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else if (!canUseFullScreenIntent() && Build.VERSION.SDK_INT >= 34) {
            // so incoming calls can fill the screen when the phone is locked
            openFullScreenIntentSettings(packageName)
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (ignored: Exception) {
            }
        }
    }

    // ---- make it yours ----

    private fun personalisePage() = page {
        addView(text(getString(R.string.omnio_setup_yours_title), 26f, OmnioColors.TEXT, bold = true))
        addView(text(getString(R.string.omnio_setup_yours_text), 15f, OmnioColors.TEXT_MUTED, top = 8))

        addView(label(R.string.omnio_setup_open_on))
        val keypad = -1
        val openOn = listOf(
            TAB_CONTACTS to "👤  " + getString(R.string.contacts_tab),
            TAB_FAVORITES to "⭐  " + getString(R.string.favorites_tab),
            TAB_CALL_HISTORY to "🕘  " + getString(R.string.call_history_tab),
            keypad to "🔢  " + getString(R.string.omnio_open_on_keypad),
            TAB_LAST_USED to "↩️  " + getString(R.string.last_used_tab),
        )
        val current = if (config.openDialPadAtLaunch) keypad else config.defaultTab
        addView(chips(openOn.map { it.second }, openOn.indexOfFirst { it.first == current }) { picked ->
            val choice = openOn[picked].first
            config.openDialPadAtLaunch = choice == keypad
            config.defaultTab = if (choice == keypad) TAB_CONTACTS else choice
        })

        addView(label(R.string.omnio_setup_style))
        val styles = listOf(
            OmnioDisplay.STYLE_LIST to "📃  " + getString(R.string.omnio_style_list),
            OmnioDisplay.STYLE_CARDS to "🗂️  " + getString(R.string.omnio_style_cards),
            OmnioDisplay.STYLE_CIRCLES to "⚪  " + getString(R.string.omnio_style_circles),
            OmnioDisplay.STYLE_ORBIT to "🪐  " + getString(R.string.omnio_style_orbit),
            OmnioDisplay.STYLE_HONEYCOMB to "🐝  " + getString(R.string.omnio_style_honeycomb),
            OmnioDisplay.STYLE_POLAROID to "📸  " + getString(R.string.omnio_style_polaroid),
        )
        addView(chips(styles.map { it.second }, styles.indexOfFirst { it.first == OmnioDisplay.getStyle(this@OmnioSetupActivity) }) { picked ->
            OmnioDisplay.setStyle(this@OmnioSetupActivity, styles[picked].first)
        })
        addView(text(getString(R.string.omnio_setup_style_more), 13f, OmnioColors.TEXT_MUTED, top = 12))
    }

    /** A wrap of tappable choices; the chosen one lights up. */
    private fun chips(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val holder = com.google.android.material.chip.ChipGroup(this).apply {
            isSingleSelection = true
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        fun paint() {
            for (i in 0 until holder.childCount) {
                val chip = holder.getChildAt(i) as TextView
                val on = chip.tag == holder.tag
                chip.setTextColor(if (on) OmnioColors.ON_ACCENT else OmnioColors.TEXT)
                chip.background = rounded(if (on) OmnioColors.CONTACTS else OmnioColors.CARD, 20)
            }
        }
        holder.tag = selected
        options.forEachIndexed { i, option ->
            holder.addView(TextView(this).apply {
                text = option
                tag = i
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    holder.tag = i
                    paint()
                    onPick(i)
                }
            })
        }
        paint()
        return holder
    }

    // ---- all set ----

    private fun donePage() = page {
        gravity = Gravity.CENTER
        addView(badge("🎉", OmnioColors.MESSAGES, 96).apply { (getChildAt(0) as TextView).setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f) })
        addView(text(getString(R.string.omnio_setup_done_title), 28f, OmnioColors.TEXT, bold = true, top = 26))
        addView(text(getString(R.string.omnio_setup_done_text), 16f, OmnioColors.TEXT_MUTED, top = 8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, 0)
            needs().filter { !it.optional }.forEach { need ->
                val ok = need.done()
                addView(TextView(context).apply {
                    text = (if (ok) "✅  " else "⏳  ") + getString(need.title) + if (ok) "" else "  ·  " + getString(R.string.omnio_setup_later)
                    setTextColor(if (ok) OmnioColors.TEXT else OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    background = rounded(OmnioColors.CARD, 14)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                })
            }
        })
        addView(text(getString(R.string.omnio_setup_done_tip), 14f, OmnioColors.TEXT_MUTED, top = 18))
    }

    private fun finishSetup() {
        markDone(this)
        startActivity(Intent(this, OmnioMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(OmnioMainActivity.EXTRA_FROM_SETUP, true))
        finish()
    }

    // ---- small builders ----

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(colour: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(colour)
    }

    private fun text(value: String, size: Float, colour: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = value
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.1f)
        setPadding(0, dp(top), 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(res: Int) = TextView(this).apply {
        text = getString(res).uppercase()
        setTextColor(OmnioColors.TEXT_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(dp(4), dp(26), 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** An emoji on a soft circle of its colour. */
    private fun badge(emoji: String, colour: Int, sizeDp: Int) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((colour and 0x00FFFFFF) or 0x33000000)
        }
        addView(TextView(context).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.4f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
    }

    companion object {
        private const val KEY_STEP = "setup_step"
        private const val PREF_DONE = "setup_done"

        private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

        fun isDone(context: Context) = prefs(context).getBoolean(PREF_DONE, false)

        fun markDone(context: Context) = prefs(context).edit().putBoolean(PREF_DONE, true).apply()

        /** People updating from a version before setup existed have already set things up. */
        fun skipForExistingUsers(context: Context) {
            if (prefs(context).contains(PREF_DONE)) return
            val used = prefs(context).contains("old_messages_tidied") || context.getSharedPreferences("Prefs", Context.MODE_PRIVATE).all.isNotEmpty()
            if (used) markDone(context)
        }
    }
}
