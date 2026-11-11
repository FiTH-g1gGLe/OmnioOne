package com.omnioone.app.activities

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioAppTags
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioFacebookFriends
import com.omnioone.app.helpers.OmnioFacebookFriends.Match
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.helpers.OmnioSocials
import com.omnioone.app.services.OmnioLinkHelperService
import com.omnioone.app.core.baseConfig
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.ensureBackgroundThread
import java.text.DateFormat

/**
 * Omnio One "Link Facebook friends": import your Facebook friends list, see which friends are in
 * your contacts, and link them one after another. Tap Link, tap the right person in Facebook, and
 * the Link helper brings you straight back to the next one.
 */
class OmnioFacebookMatchActivity : SimpleActivity() {

    companion object {
        /** A profile link caught by the Link helper (or shared) while linking. */
        const val EXTRA_LINK = "omnio_fb_link"
    }

    private lateinit var screen: OmnioScreen
    private val handler = Handler(Looper.getMainLooper())
    private var result: OmnioFacebookFriends.Result? = null
    private var loading = false

    /** Matches still to link in this run, and the one Facebook is open for now. */
    private val queue = ArrayList<Match>()
    private var current: Match? = null
    private var linkedThisRun = 0
    private var gotLink = false
    private var lastLinked: String? = null

    private val facebook get() = OmnioSocials.network("facebook")

    private val importFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = OmnioScreen(this, getString(R.string.omnio_fb_title))
        setContentView(screen.root)
        OmnioFacebookFriends.setQueueActive(this, false)
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val link = intent.getStringExtra(EXTRA_LINK) ?: return
        gotLink = true
        saveLink(link)
    }

    override fun onResume() {
        super.onResume()
        val waiting = current ?: return
        if (gotLink) return
        // back from Facebook without the Link helper catching anything: try the clipboard, else ask
        handler.postDelayed({
            if (gotLink || current != waiting || isFinishing) return@postDelayed
            val copied = OmnioSocials.firstUrl(clipboard())?.takeIf { OmnioSocials.networkFor(it)?.key == "facebook" }
            if (copied != null && copied != lastLinked) {
                gotLink = true
                saveLink(copied)
            } else {
                missed(waiting)
            }
        }, 400)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopQueue()
        super.onDestroy()
    }

    private fun clipboard(): CharSequence? = try {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)
    } catch (e: Exception) {
        null
    }

    // ---- loading ----

    private fun refresh() {
        if (!OmnioFacebookFriends.hasImport(this)) {
            showIntro()
            return
        }
        loading = true
        build()
        val ignored = baseConfig.ignoredContactSources.toSet()
        ensureBackgroundThread {
            val people = OmnioPeople.load(this, ignored)
            val facebookName = facebook.name
            val linked = OmnioAppTags.load(this).filterValues { badges -> badges.any { it.name == facebookName } }.keys
            val matched = OmnioFacebookFriends.match(this, people, linked)
            runOnUiThread {
                loading = false
                result = matched
                build()
            }
        }
    }

    private fun runImport(uri: Uri) {
        toast(R.string.omnio_fb_reading)
        ensureBackgroundThread {
            val count = try {
                OmnioFacebookFriends.import(this, uri)
            } catch (e: Exception) {
                0
            }
            runOnUiThread {
                if (count == 0) {
                    OmnioSheet.confirm(this, getString(R.string.omnio_fb_no_friends_title), getString(R.string.omnio_fb_no_friends_desc), positive = getString(com.omnioone.app.R.string.ok)) {}
                } else {
                    toast(getString(R.string.omnio_fb_found, count))
                    refresh()
                }
            }
        }
    }

    // ---- screens ----

    private fun showIntro() {
        screen.content.removeAllViews()
        screen.note(getString(R.string.omnio_fb_intro))
        screen.section("1", getString(R.string.omnio_fb_step1_title))
        screen.note(getString(R.string.omnio_fb_step1_desc))
        screen.button(getString(R.string.omnio_fb_open_facebook), OmnioColors.CALLS) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://accountscenter.facebook.com/info_and_permissions/dyi/")))
            } catch (e: Exception) {
                toast(com.omnioone.app.R.string.no_app_found)
            }
        }
        screen.section("2", getString(R.string.omnio_fb_step2_title))
        screen.note(getString(R.string.omnio_fb_step2_desc))
        screen.button(getString(R.string.omnio_fb_choose_file), OmnioColors.CONTACTS) {
            importFile.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
        }
        screen.section("🔒", getString(R.string.omnio_fb_privacy_title))
        screen.note(getString(R.string.omnio_fb_privacy_desc))
    }

    private fun build() {
        screen.content.removeAllViews()
        val found = result
        if (loading || found == null) {
            screen.note(getString(R.string.omnio_fb_matching))
            return
        }

        if (!OmnioLinkHelperService.isEnabled(this)) {
            screen.card(listOf(screen.row(getString(R.string.omnio_fb_helper_title), getString(R.string.omnio_fb_helper_desc), subtitleColor = OmnioColors.FAVOURITES) {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (ignored: Exception) {
                }
            }))
        }

        val imported = OmnioFacebookFriends.importedAt(this)
        screen.note(
            getString(R.string.omnio_fb_summary, found.sure.size, found.possible.size, found.alreadyLinked, found.notInContacts) +
                if (imported > 0) "\n" + getString(R.string.omnio_fb_imported_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(imported)) else ""
        )

        if (found.sure.isNotEmpty()) {
            screen.button(getString(R.string.omnio_fb_link_all, found.sure.size), OmnioColors.CALLS) { startQueue(found.sure) }
            screen.section("✅", getString(R.string.omnio_fb_sure, found.sure.size))
            screen.card(found.sure.map { matchRow(it) })
        }
        if (found.possible.isNotEmpty()) {
            screen.section("🤔", getString(R.string.omnio_fb_possible, found.possible.size))
            screen.card(found.possible.map { matchRow(it) })
        }
        if (found.sure.isEmpty() && found.possible.isEmpty()) {
            screen.note(getString(R.string.omnio_fb_all_done))
        }

        screen.section("⚙️", getString(R.string.omnio_fb_file))
        screen.card(listOf(
            screen.row(getString(R.string.omnio_fb_new_file), getString(R.string.omnio_fb_new_file_desc)) {
                importFile.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
            },
            screen.row(getString(R.string.omnio_fb_forget), getString(R.string.omnio_fb_forget_desc), subtitleColor = OmnioColors.MISSED) {
                OmnioSheet.confirm(this, getString(R.string.omnio_fb_forget), getString(R.string.omnio_fb_forget_desc), positive = getString(com.omnioone.app.R.string.delete), mainColor = OmnioColors.MISSED) {
                    OmnioFacebookFriends.forget(this)
                    result = null
                    showIntro()
                }
            },
        ))
    }

    private fun matchRow(match: Match): View {
        val avatar = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(screen.dp(44), screen.dp(44)) }
        OmnioDisplay.loadAvatar(avatar, match.person.photo, match.person.name, screen.dp(22))
        val since = if (match.friend.since > 0) " · " + getString(R.string.omnio_fb_since, java.util.Calendar.getInstance().apply { timeInMillis = match.friend.since * 1000 }.get(java.util.Calendar.YEAR)) else ""
        val subtitle = getString(R.string.omnio_fb_on_facebook_as, match.friend.name) + since
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(screen.iconButton(com.omnioone.app.R.drawable.ic_cross_vector, OmnioColors.TEXT_MUTED, getString(R.string.omnio_fb_not_them)) { notThem(match) })
            addView(pill(getString(R.string.omnio_fb_link)) { startQueue(listOf(match)) })
        }
        return screen.row(match.person.name, subtitle, leading = avatar, trailing = buttons) { startQueue(listOf(match)) }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(OmnioColors.ON_ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(screen.dp(16), screen.dp(8), screen.dp(16), screen.dp(8))
        background = GradientDrawable().apply {
            cornerRadius = screen.dp(18).toFloat()
            setColor(0xFF1877F2.toInt())
        }
        setOnClickListener { onClick() }
    }

    private fun notThem(match: Match) {
        OmnioFacebookFriends.markNotThem(this, match)
        result = result?.let { r -> r.copy(sure = r.sure - match, possible = r.possible - match, notInContacts = r.notInContacts + 1) }
        build()
    }

    // ---- linking one after another ----

    private fun startQueue(matches: List<Match>) {
        queue.clear()
        queue.addAll(matches)
        linkedThisRun = 0
        OmnioFacebookFriends.setQueueActive(this, true)
        next()
    }

    private fun stopQueue() {
        queue.clear()
        current = null
        OmnioFacebookFriends.setQueueActive(this, false)
        OmnioSocials.clearPendingLink(this)
    }

    private fun next() {
        val match = queue.removeFirstOrNull()
        if (match == null) {
            val linked = linkedThisRun
            stopQueue()
            if (linked > 0) toast(getString(R.string.omnio_fb_run_done, linked))
            refresh()
            return
        }
        current = match
        gotLink = false
        val person = match.person
        OmnioSocials.setPendingLink(this, OmnioSocials.PendingLink(person.contactId, person.rawId, person.name, "facebook"), expectName = match.friend.name)
        val search = facebook.searchUrl(match.friend.name)
        val opened = search != null && (open(Intent(Intent.ACTION_VIEW, Uri.parse(search)).setPackage(facebook.pkg)) || open(Intent(Intent.ACTION_VIEW, Uri.parse(search))))
        if (!opened) {
            stopQueue()
            toast(com.omnioone.app.R.string.no_app_found)
            return
        }
        val left = queue.size
        toast(
            if (left > 0) getString(R.string.omnio_fb_tap_profile_more, match.friend.name, left) else getString(R.string.omnio_fb_tap_profile, match.friend.name),
            android.widget.Toast.LENGTH_LONG
        )
    }

    private fun open(intent: Intent) = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /** Saves the caught profile (and Messenger, when the profile has a username), then moves on. */
    private fun saveLink(url: String) {
        val match = current ?: return
        if (OmnioSocials.networkFor(url)?.key != "facebook") return
        lastLinked = url
        OmnioSocials.clearPendingLink(this)
        ensureBackgroundThread {
            val profile = OmnioSocials.resolveFacebook(url) ?: url
            val existing = OmnioSocials.findSocials(this, match.person.contactId)
            var ok = existing.any { it.url.equals(profile, true) } || OmnioSocials.addSocial(this, match.person.contactId, profile)
            // the Link helper may have caught their profile photo too: use it if they have none yet
            val photoHelper = com.omnioone.app.helpers.OmnioPhotoCapture
            photoHelper.captured(this, match.person.contactId)?.let { photo ->
                if (match.person.photo.isEmpty()) photoHelper.saveToContact(this, match.person.rawId, photo)
                photoHelper.discard(this, match.person.contactId)
            }
            val messenger = OmnioSocials.messengerFromFacebook(profile)
            if (ok && messenger != null && existing.none { it.network.key == "messenger" }) {
                ok = OmnioSocials.addSocial(this, match.person.contactId, messenger) || ok
            }
            runOnUiThread {
                if (!ok) {
                    toast(com.omnioone.app.R.string.unknown_error_occurred)
                    missed(match)
                    return@runOnUiThread
                }
                linkedThisRun++
                result = result?.let { r -> r.copy(sure = r.sure - match, possible = r.possible - match, alreadyLinked = r.alreadyLinked + 1) }
                current = null
                build()
                showLinked(match)
            }
        }
    }

    /** "Linked ✓", then the next person comes up by itself (press back in Facebook to stop or skip). */
    private fun showLinked(match: Match) {
        val upNext = queue.firstOrNull()
        if (upNext == null) {
            next()
            return
        }
        toast(getString(R.string.omnio_fb_linked, match.person.name) + "  ·  " + getString(R.string.omnio_fb_up_next, upNext.person.name))
        handler.postDelayed({ if (current == null && !isFinishing) next() }, 1200)
    }

    /** Came back without a profile: try again, skip this person, or stop. */
    private fun missed(match: Match) {
        current = null
        OmnioSheet.confirm(
            this,
            title = getString(R.string.omnio_fb_missed_title, match.person.name),
            message = getString(if (OmnioLinkHelperService.isEnabled(this)) R.string.omnio_fb_missed_desc_helper else R.string.omnio_fb_missed_desc),
            positive = getString(R.string.omnio_fb_try_again),
            negative = getString(R.string.omnio_fb_stop),
            neutral = getString(R.string.omnio_fb_skip),
            onNeutral = { next() },
            onNegative = {
                stopQueue()
                refresh()
            },
            mainColor = 0xFF1877F2.toInt()
        ) {
            queue.add(0, match)
            next()
        }
    }
}
