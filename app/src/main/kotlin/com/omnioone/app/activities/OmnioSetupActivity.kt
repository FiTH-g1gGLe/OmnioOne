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

    private enum class Step { WELCOME, PEOPLE, CALLS, MESSAGES, PERMISSIONS, LOOK, TABS, KEYPAD, TEXTING, LINK, DONE }

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
            Step.LOOK -> lookPage()
            Step.TABS -> tabsPage()
            Step.KEYPAD -> keypadPage()
            Step.TEXTING -> textingPage()
            Step.LINK -> linkPage()
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

    private fun LinearLayout.yoursHeader(title: Int, body: Int) {
        addView(text(getString(R.string.omnio_setup_yours_kicker), 13f, OmnioColors.CONTACTS, bold = true).apply { letterSpacing = 0.1f })
        addView(text(getString(title), 26f, OmnioColors.TEXT, bold = true, top = 4))
        addView(text(getString(body), 15f, OmnioColors.TEXT_MUTED, top = 8))
    }

    /** A "live preview" frame: what you pick shows up here straight away. */
    private fun previewFrame(content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(OmnioColors.SHEET, 22).apply { setStroke(dp(1), OmnioColors.CARD) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        addView(TextView(context).apply {
            text = "👀  " + getString(R.string.omnio_setup_live_preview)
            setTextColor(OmnioColors.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(8))
        })
        addView(content)
    }

    // contacts display

    private val samplePeople by lazy {
        listOf("Amelia Clarke", "Ben Okafor", "Chloe Patel", "Daniel Hughes", "Ella Morgan", "Finn O'Brien", "Grace Lee", "Harry Wilson", "Isla Ahmed")
            .mapIndexed { i, name ->
                com.omnioone.app.helpers.OmnioPeople.Person(
                    contactId = -100 - i, rawId = -100 - i, lookupKey = "", name = name, surnameFirst = name.substringAfter(" ") + ", " + name.substringBefore(" "),
                    photo = samplePhoto(i), starred = i < 3,
                    numbers = listOf(com.omnioone.app.helpers.OmnioPeople.Number("07700 9001${10 + i}", "Mobile", true)),
                    emails = if (i % 3 == 0) listOf("hello@example.com") else emptyList(), nickname = "", company = "", jobTitle = "",
                )
            }
    }

    private val sampleBadges by lazy {
        fun tag(name: String, pkg: String, colour: Long) = com.omnioone.app.helpers.OmnioAppTags.Badge(name, pkg, colour.toInt())
        val whatsapp = tag("WhatsApp", "com.whatsapp", 0xFF25D366)
        val instagram = tag("Instagram", "com.instagram.android", 0xFFE1306C)
        val facebook = tag("Facebook", "com.facebook.katana", 0xFF1877F2)
        val messenger = tag("Messenger", "com.facebook.orca", 0xFF0084FF)
        val snapchat = tag("Snapchat", "com.snapchat.android", 0xFFFFFC00)
        val tiktok = tag("TikTok", "com.zhiliaoapp.musically", 0xFF111111)
        val telegram = tag("Telegram", "org.telegram.messenger", 0xFF229ED9)
        val x = tag("X", "com.twitter.android", 0xFF111111)
        val email = com.omnioone.app.helpers.OmnioAppTags.EMAIL
        // a different mix for each pretend person, like real contacts
        val mixes = listOf(
            listOf(whatsapp, instagram, facebook, messenger),
            listOf(whatsapp, snapchat, tiktok),
            listOf(whatsapp, instagram, email),
            listOf(whatsapp, facebook, messenger, x),
            listOf(telegram, instagram, snapchat),
            listOf(whatsapp, tiktok, instagram, email),
            listOf(whatsapp, facebook),
            listOf(whatsapp, snapchat, instagram, x),
            listOf(telegram, whatsapp, email),
        )
        samplePeople.mapIndexed { i, person -> person.contactId to mixes[i % mixes.size] }.toMap()
    }

    /** A pretend profile photo: a cartoon face on a bright gradient, drawn on the phone (no real people). */
    private fun samplePhoto(index: Int): String {
        val file = java.io.File(cacheDir, "omnio_setup_face_$index.png")
        if (file.exists()) return file.absolutePath
        val faces = listOf("👩🏻‍🦰", "👨🏾", "👩🏽", "🧔🏻", "👱🏼‍♀️", "👨🏻‍🦱", "👩🏻", "🧑🏼‍🦲", "👩🏾‍🦱")
        val gradients = listOf(
            0xFFFF8A3D to 0xFFEC4899, 0xFF4F7CFF to 0xFF22C55E, 0xFF8B5CF6 to 0xFFEC4899,
            0xFF14B8A6 to 0xFF4F7CFF, 0xFFFFC85A to 0xFFFF8A3D, 0xFF22C55E to 0xFF14B8A6,
            0xFFEC4899 to 0xFF8B5CF6, 0xFF4F7CFF to 0xFF8B5CF6, 0xFFFF6B6B to 0xFFFFC85A,
        )
        return try {
            val size = 256
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val (from, to) = gradients[index % gradients.size]
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), from.toInt(), to.toInt(), android.graphics.Shader.TileMode.CLAMP)
            })
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * 0.62f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f + size * 0.06f
            canvas.drawText(faces[index % faces.size], size / 2f, y, paint)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    private fun lookPage() = page {
        yoursHeader(R.string.omnio_setup_look_title, R.string.omnio_setup_look_text)
        val styles = listOf(
            OmnioDisplay.STYLE_LIST to R.string.omnio_style_list, OmnioDisplay.STYLE_COMPACT to R.string.omnio_style_compact,
            OmnioDisplay.STYLE_DETAILED to R.string.omnio_style_detailed, OmnioDisplay.STYLE_CARDS to R.string.omnio_style_cards,
            OmnioDisplay.STYLE_CIRCLES to R.string.omnio_style_circles, OmnioDisplay.STYLE_SQUARES to R.string.omnio_style_squares,
            OmnioDisplay.STYLE_TILES to R.string.omnio_style_tiles, OmnioDisplay.STYLE_SPEED_DIAL to R.string.omnio_style_speed_dial,
            OmnioDisplay.STYLE_BUBBLES to R.string.omnio_style_bubbles, OmnioDisplay.STYLE_TIMELINE to R.string.omnio_style_timeline,
            OmnioDisplay.STYLE_MINIMAL to R.string.omnio_style_minimal, OmnioDisplay.STYLE_BUSINESS to R.string.omnio_style_business,
            OmnioDisplay.STYLE_SPOTLIGHT to R.string.omnio_style_spotlight, OmnioDisplay.STYLE_PILLS to R.string.omnio_style_pills,
            OmnioDisplay.STYLE_POLAROID to R.string.omnio_style_polaroid, OmnioDisplay.STYLE_ORBIT to R.string.omnio_style_orbit,
            OmnioDisplay.STYLE_HONEYCOMB to R.string.omnio_style_honeycomb, OmnioDisplay.STYLE_MONOGRAM to R.string.omnio_style_monogram,
        )
        val list = androidx.recyclerview.widget.RecyclerView(this@OmnioSetupActivity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330))
            isNestedScrollingEnabled = true
            itemAnimator = null
        }
        fun show(style: Int) {
            val adapter = com.omnioone.app.adapters.OmnioContactsAdapter(style, onClick = {}, onPhoto = {}, onLongClick = { _, _ -> }).apply {
                appTags = sampleBadges
                columns = OmnioDisplay.getColumns(this@OmnioSetupActivity, style)
            }
            list.layoutManager = if (OmnioDisplay.isGrid(style)) {
                androidx.recyclerview.widget.GridLayoutManager(this@OmnioSetupActivity, OmnioDisplay.getColumns(this@OmnioSetupActivity, style))
            } else {
                androidx.recyclerview.widget.LinearLayoutManager(this@OmnioSetupActivity)
            }
            list.adapter = adapter
            adapter.submit(samplePeople, { it.letter(it.name) }, OmnioDisplay.hasHeaders(style))
        }
        val current = OmnioDisplay.getStyle(this@OmnioSetupActivity)
        addView(scrollingChips(styles.map { getString(it.second) }, styles.indexOfFirst { it.first == current }.coerceAtLeast(0)) { picked ->
            OmnioDisplay.setStyle(this@OmnioSetupActivity, styles[picked].first)
            show(styles[picked].first)
        })
        addView(previewFrame(list))
        show(current)
    }

    // tabs

    private fun tabsPage() = page {
        yoursHeader(R.string.omnio_setup_tabs_title, R.string.omnio_setup_tabs_text)
        val bar = LinearLayout(this@OmnioSetupActivity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = rounded(OmnioColors.BACKGROUND, 16)
        }
        val openOnHolder = LinearLayout(this@OmnioSetupActivity).apply { orientation = LinearLayout.VERTICAL }
        val keypad = -1
        val phoneTabs = listOf(
            Triple(TAB_CONTACTS, R.string.contacts_tab, R.drawable.ic_person_vector to OmnioColors.CONTACTS),
            Triple(TAB_FAVORITES, R.string.favorites_tab, R.drawable.ic_star_vector to OmnioColors.FAVOURITES),
            Triple(TAB_CALL_HISTORY, R.string.call_history_tab, R.drawable.ic_clock_filled_vector to OmnioColors.CALLS),
        )
        lateinit var refresh: () -> Unit
        fun openOnChoices(): List<Pair<Int, String>> =
            phoneTabs.filter { config.showTabs and it.first != 0 }.map { it.first to getString(it.second) } +
                listOf(keypad to getString(R.string.omnio_open_on_keypad), TAB_LAST_USED to getString(R.string.last_used_tab))
        fun openOn() = if (config.openDialPadAtLaunch) keypad else config.defaultTab
        refresh = {
            // the pretend tab bar, as it'll look at the bottom of the app
            bar.removeAllViews()
            val shown = phoneTabs.filter { config.showTabs and it.first != 0 }.map { Triple(it.first, getString(it.second), it.third) } +
                Triple(-2, getString(R.string.omnio_messages_tab), R.drawable.ic_omnio_chat to OmnioColors.MESSAGES)
            val open = openOn().let { tab -> if (tab == keypad || tab == TAB_LAST_USED) TAB_CONTACTS else tab }
            shown.forEach { (kind, label, look) ->
                val lit = kind == open || (shown.none { it.first == open } && kind == shown.first().first)
                bar.addView(LinearLayout(this@OmnioSetupActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(ImageView(context).apply {
                        setImageResource(look.first)
                        setColorFilter(if (lit) look.second else OmnioColors.TEXT_MUTED)
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    })
                    addView(TextView(context).apply {
                        text = label
                        setTextColor(if (lit) look.second else OmnioColors.TEXT_MUTED)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(0, dp(4), 0, 0)
                    })
                    alpha = 0f
                    animate().alpha(1f).setDuration(200).start()
                })
            }
            if (openOn() == keypad) bar.addView(TextView(this@OmnioSetupActivity).apply {
                text = "🔢"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(8), 0, dp(4), 0)
            })
            // open-on choices follow which tabs are showing
            openOnHolder.removeAllViews()
            val choices = openOnChoices()
            openOnHolder.addView(chips(choices.map { it.second }, choices.indexOfFirst { it.first == openOn() }) { picked ->
                val choice = choices[picked].first
                config.openDialPadAtLaunch = choice == keypad
                config.defaultTab = if (choice == keypad) TAB_CONTACTS else choice
                refresh()
            })
        }
        addView(label(R.string.omnio_setup_tabs_shown))
        phoneTabs.forEach { (kind, labelRes, _) ->
            addView(toggleRow(getString(labelRes), null, config.showTabs and kind != 0) { on ->
                config.showTabs = if (on) config.showTabs or kind else config.showTabs and kind.inv()
                // the opening tab can't be one that's hidden
                if (!on && !config.openDialPadAtLaunch && config.defaultTab == kind) config.defaultTab = TAB_LAST_USED
                refresh()
            })
        }
        addView(text(getString(R.string.omnio_setup_tabs_messages_note), 13f, OmnioColors.TEXT_MUTED, top = 8))
        addView(label(R.string.omnio_setup_open_on))
        addView(openOnHolder)
        addView(previewFrame(bar))
        refresh()
    }

    // keypad

    private fun keypadPage() = page {
        yoursHeader(R.string.omnio_setup_keypad_title, R.string.omnio_setup_keypad_text_plain)
        addView(toggleRow(getString(R.string.omnio_settings_dialpad_beeps), getString(R.string.omnio_setup_keypad_tones_desc), config.dialpadBeeps) { config.dialpadBeeps = it })
        addView(toggleRow(getString(R.string.omnio_settings_dialpad_vibration), getString(R.string.omnio_setup_keypad_vibration_desc), config.dialpadVibration) { config.dialpadVibration = it })
    }

    // texting

    private fun textingPage() = page {
        yoursHeader(R.string.omnio_setup_texting_title, R.string.omnio_setup_texting_text)
        addView(label(R.string.omnio_undo_send))
        val choices = listOf(0, 3, 5, 10)
        val names = choices.map { if (it == 0) getString(R.string.omnio_undo_send_off) else getString(R.string.omnio_undo_send_seconds, it) }
        addView(chips(names, choices.indexOf(com.omnioone.app.messaging.OmnioSender.undoSeconds(this@OmnioSetupActivity)).coerceAtLeast(0)) { picked ->
            com.omnioone.app.messaging.OmnioSender.setUndoSeconds(this@OmnioSetupActivity, choices[picked])
        })
        addView(text(getString(R.string.omnio_setup_undo_desc), 13f, OmnioColors.TEXT_MUTED, top = 8))
        addView(toggleRow(getString(R.string.omnio_settings_copy_codes), getString(R.string.omnio_settings_copy_codes_desc), com.omnioone.app.messaging.OmnioCodes.isOn(this@OmnioSetupActivity)) {
            com.omnioone.app.messaging.OmnioCodes.setOn(this@OmnioSetupActivity, it)
        })
        addView(previewFrame(sendDemo()))
    }

    /** A pretend chat: tap send to see what happens with your undo setting. */
    private fun sendDemo(): View {
        val chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun bubble(textValue: String, mine: Boolean, status: String? = null) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            addView(TextView(context).apply {
                text = textValue
                setTextColor(if (mine) OmnioColors.ON_ACCENT else OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = rounded(if (mine) OmnioColors.MESSAGES else OmnioColors.CARD, 18)
            })
            status?.let {
                addView(TextView(context).apply {
                    text = it
                    tag = "status"
                    setTextColor(OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(dp(6), dp(3), dp(6), 0)
                })
            }
        }
        chat.addView(bubble(getString(R.string.omnio_setup_demo_incoming), mine = false))
        val undoBar = TextView(this).apply {
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(OmnioColors.CARD_SELECTED, 14)
            visibility = View.GONE
        }
        val send = TextView(this).apply {
            text = "➤  " + getString(R.string.omnio_setup_demo_send)
            setTextColor(OmnioColors.ON_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(OmnioColors.MESSAGES, 20)
        }
        val handler = android.os.Handler(mainLooper)
        send.setOnClickListener {
            // only one pretend message at a time
            while (chat.childCount > 1) chat.removeViewAt(1)
            val seconds = com.omnioone.app.messaging.OmnioSender.undoSeconds(this)
            val mine = bubble(getString(R.string.omnio_setup_demo_reply), mine = true, status = getString(R.string.omnio_thread_sending))
            chat.addView(mine)
            val status = mine.findViewWithTag<TextView>("status")
            if (seconds <= 0) {
                handler.postDelayed({ status?.text = getString(R.string.omnio_setup_demo_sent) }, 500)
                return@setOnClickListener
            }
            var left = seconds
            undoBar.visibility = View.VISIBLE
            val tick = object : Runnable {
                override fun run() {
                    if (left <= 0) {
                        undoBar.visibility = View.GONE
                        status?.text = getString(R.string.omnio_setup_demo_sent)
                        return
                    }
                    undoBar.text = getString(R.string.omnio_setup_demo_undo, left)
                    left--
                    handler.postDelayed(this, 1000)
                }
            }
            undoBar.setOnClickListener {
                handler.removeCallbacks(tick)
                undoBar.visibility = View.GONE
                chat.removeView(mine)
            }
            tick.run()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chat)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(0, dp(12), 0, 0)
                addView(undoBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
                addView(send)
            })
        }
    }

    // link helper

    private fun linkPage() = page {
        yoursHeader(R.string.omnio_setup_link_title, R.string.omnio_setup_link_text)
        val prefs = getSharedPreferences("omnio", MODE_PRIVATE)
        val on = com.omnioone.app.services.OmnioLinkHelperService.isEnabled(this@OmnioSetupActivity)
        addView(LinearLayout(this@OmnioSetupActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(OmnioColors.CARD, 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            addView(TextView(context).apply {
                text = getString(R.string.omnio_settings_link_helper)
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = getString(if (on) R.string.omnio_setup_link_on else R.string.omnio_setup_link_turn_on)
                setTextColor(if (on) OmnioColors.MESSAGES else OmnioColors.ON_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                if (!on) background = rounded(OmnioColors.SOCIALS, 16)
            })
            setOnClickListener { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        addView(toggleRow(getString(R.string.omnio_settings_offer_helper), getString(R.string.omnio_settings_offer_helper_desc), !prefs.getBoolean("link_helper_dont_ask", false)) {
            prefs.edit().putBoolean("link_helper_dont_ask", !it).apply()
        })
        addView(toggleRow(getString(R.string.omnio_photo_full_size), getString(R.string.omnio_photo_full_size_desc), prefs.getBoolean("photo_full_size", false)) {
            prefs.edit().putBoolean("photo_full_size", it).apply()
        })
        // how it works, in three steps
        addView(previewFrame(LinearLayout(this@OmnioSetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            listOf(
                "1" to R.string.omnio_setup_link_step1, "2" to R.string.omnio_setup_link_step2, "3" to R.string.omnio_setup_link_step3,
            ).forEach { (number, res) ->
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    addView(TextView(context).apply {
                        text = number
                        setTextColor(OmnioColors.ON_ACCENT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(OmnioColors.SOCIALS)
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                    })
                    addView(TextView(context).apply {
                        text = getString(res)
                        setTextColor(OmnioColors.TEXT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(dp(12), 0, 0, 0)
                    })
                })
            }
        }))
    }

    /** A setting with a switch, like the ones in Settings. */
    private fun toggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(12))
        background = rounded(OmnioColors.CARD, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = title
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            subtitle?.let {
                addView(TextView(context).apply {
                    text = it
                    setTextColor(OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(2), dp(8), 0)
                })
            }
        })
        val toggle = androidx.appcompat.widget.SwitchCompat(context).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(OmnioColors.TEXT, OmnioColors.TEXT_MUTED))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(OmnioColors.MESSAGES, 0xFF3A404C.toInt()))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        addView(toggle)
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    /** Chips in one line that scrolls sideways (for long lists, like the 18 styles). */
    private fun scrollingChips(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        fun paint(chosen: Int) {
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i) as TextView
                chip.setTextColor(if (i == chosen) OmnioColors.ON_ACCENT else OmnioColors.TEXT)
                chip.background = rounded(if (i == chosen) OmnioColors.CONTACTS else OmnioColors.CARD, 20)
            }
        }
        options.forEachIndexed { i, option ->
            row.addView(TextView(this).apply {
                text = option
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener {
                    paint(i)
                    onPick(i)
                }
            })
        }
        paint(selected)
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            addView(row)
            post { row.getChildAt(selected)?.let { smoothScrollTo((it.left - dp(24)).coerceAtLeast(0), 0) } }
        }
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
