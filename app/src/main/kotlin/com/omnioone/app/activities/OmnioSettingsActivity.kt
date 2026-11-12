package com.omnioone.app.activities

import android.app.role.RoleManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import com.omnioone.app.core.isDefaultDialer
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.messaging.LOCK_SCREEN_NOTHING
import com.omnioone.app.messaging.LOCK_SCREEN_SENDER
import com.omnioone.app.messaging.LOCK_SCREEN_SENDER_MESSAGE
import com.omnioone.app.R
import com.omnioone.app.dialogs.OmnioDisplayDialog
import com.omnioone.app.phone.config
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.services.OmnioLinkHelperService
import com.omnioone.app.messaging.messagesConfig

/**
 * Omnio One: one settings page for calls, contacts and messages.
 * Everyday switches live here; the full call and message settings stay one tap away
 * under Advanced, so no option is lost.
 */
class OmnioSettingsActivity : OmnioActivity() {

    private lateinit var form: LinearLayout

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        root.addView(topBar(getString(R.string.omnio_settings)))
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(40))
        }
        root.addView(NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(form)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // rebuilt each time so statuses (default apps, Link helper) are always current
        buildRows()
    }

    private fun buildRows() {
        form.removeAllViews()

        section("🎨", R.string.omnio_settings_appearance)
        card(
            action(R.string.omnio_display_style, getString(R.string.omnio_settings_display_desc)) { OmnioDisplayDialog(this) { buildRows() } },
            action(R.string.omnio_settings_language, getString(R.string.omnio_settings_language_desc)) { openLanguage() },
        )

        section("👥", R.string.omnio_settings_contacts)
        card(
            action(R.string.omnio_settings_contact_tap, getString(if (config.onContactClick == com.omnioone.app.core.ON_CLICK_CALL_CONTACT) R.string.omnio_settings_contact_tap_call else R.string.omnio_settings_contact_tap_open)) {
                OmnioSheet.list(this, getString(R.string.omnio_settings_contact_tap), getString(R.string.omnio_settings_contact_tap_help), listOf(
                    OmnioSheet.Item(getString(R.string.omnio_settings_contact_tap_open), trailing = if (config.onContactClick != com.omnioone.app.core.ON_CLICK_CALL_CONTACT) "✓" else null),
                    OmnioSheet.Item(getString(R.string.omnio_settings_contact_tap_call), trailing = if (config.onContactClick == com.omnioone.app.core.ON_CLICK_CALL_CONTACT) "✓" else null),
                )) { picked ->
                    config.onContactClick = if (picked == 1) com.omnioone.app.core.ON_CLICK_CALL_CONTACT else com.omnioone.app.core.ON_CLICK_VIEW_CONTACT
                    buildRows()
                }
            },
            switch(R.string.omnio_settings_surname_first, null, config.startNameWithSurname) { config.startNameWithSurname = it },
            action(R.string.omnio_settings_default_tab, if (config.openDialPadAtLaunch) getString(R.string.omnio_open_on_keypad) else tabName(config.defaultTab)) { pickDefaultTab() },
            action(R.string.omnio_settings_visible_tabs, visibleTabsText()) { pickVisibleTabs() },
        )

        section("📲", R.string.omnio_settings_call_screen)
        val styles = com.omnioone.app.helpers.OmnioCallStyle
        val designs = styles.customDesigns(this)
        card(
            action(R.string.omnio_settings_call_style, styles.label(this, styles.defaultKey(this))) { pickCallStyle(false) },
            action(R.string.omnio_settings_unknown_style, styles.label(this, styles.unknownKey(this))) { pickCallStyle(true) },
            action(R.string.omnio_settings_design_own, getString(R.string.omnio_settings_design_own_desc)) {
                startActivity(Intent(this, OmnioCallDesignerActivity::class.java))
            },
            *designs.map { saved ->
                action(R.string.omnio_settings_edit_design, saved.name.ifEmpty { getString(R.string.omnio_call_style_custom) }) {
                    startActivity(Intent(this, OmnioCallDesignerActivity::class.java).putExtra(OmnioCallDesignerActivity.EXTRA_EDIT_KEY, saved.id))
                }
            }.toTypedArray(),
            action(R.string.omnio_settings_test_call, getString(R.string.omnio_settings_test_call_desc)) { testCall(styles.defaultKey(this)) },
        )

        section("📱", R.string.omnio_settings_default_apps)
        val isDialer = isDefaultDialer()
        val isSms = Telephony.Sms.getDefaultSmsPackage(this) == packageName
        card(
            action(R.string.omnio_settings_phone_app, statusText(isDialer), statusColor(isDialer)) { requestRole(RoleManager.ROLE_DIALER) },
            action(R.string.omnio_settings_sms_app, statusText(isSms), statusColor(isSms)) { requestRole(RoleManager.ROLE_SMS) },
        )

        section("📞", R.string.omnio_settings_calls)
        card(
            switch(R.string.omnio_settings_confirm_call, R.string.omnio_settings_confirm_call_desc, config.showCallConfirmation) { config.showCallConfirmation = it },
            switch(R.string.omnio_settings_dialpad_vibration, null, config.dialpadVibration) { config.dialpadVibration = it },
            switch(R.string.omnio_settings_dialpad_beeps, null, config.dialpadBeeps) { config.dialpadBeeps = it },
            switch(R.string.omnio_settings_group_calls, R.string.omnio_settings_group_calls_desc, config.groupSubsequentCalls) { config.groupSubsequentCalls = it },
            switch(R.string.omnio_settings_proximity, R.string.omnio_settings_proximity_desc, !config.disableProximitySensor) { config.disableProximitySensor = !it },
            switch(R.string.omnio_settings_swipe_answer, null, !config.disableSwipeToAnswer) { config.disableSwipeToAnswer = !it },
            switch(R.string.omnio_settings_always_fullscreen, R.string.omnio_settings_always_fullscreen_desc, config.alwaysShowFullscreen) { config.alwaysShowFullscreen = it },
            action(R.string.omnio_speed_dial, getString(R.string.omnio_settings_speed_dial_desc)) {
                startActivity(Intent(this, OmnioSpeedDialActivity::class.java))
            },
            action(R.string.omnio_settings_blocked, getString(R.string.omnio_settings_blocked_desc)) {
                startActivity(Intent(this, OmnioBlockedActivity::class.java))
            },
        )

        section("🕘", R.string.omnio_settings_call_history)
        card(
            action(R.string.omnio_settings_export_calls, getString(R.string.omnio_settings_export_calls_desc)) {
                exportCalls.launch("omnio-call-history.json")
            },
            action(R.string.omnio_settings_import_calls, getString(R.string.omnio_settings_import_calls_desc)) {
                importCalls.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
        )

        section("💬", R.string.omnio_settings_messages)
        val messages = messagesConfig
        card(
            switch(R.string.omnio_settings_send_on_enter, null, messages.sendOnEnter) { messages.sendOnEnter = it },
            switch(R.string.omnio_settings_delivery_reports, R.string.omnio_settings_delivery_reports_desc, messages.enableDeliveryReports) {
                messages.enableDeliveryReports = it
            },
            switch(R.string.omnio_settings_char_counter, null, messages.showCharacterCounter) { messages.showCharacterCounter = it },
            action(R.string.omnio_undo_send, undoText(com.omnioone.app.messaging.OmnioSender.undoSeconds(this))) {
                val choices = listOf(0, 3, 5, 10)
                OmnioSheet.list(this, getString(R.string.omnio_undo_send), getString(R.string.omnio_undo_send_help), choices.map { OmnioSheet.Item(undoText(it)) }) { picked ->
                    com.omnioone.app.messaging.OmnioSender.setUndoSeconds(this, choices[picked])
                    buildRows()
                }
            },
            switch(R.string.omnio_settings_copy_codes, R.string.omnio_settings_copy_codes_desc, com.omnioone.app.messaging.OmnioCodes.isOn(this)) {
                com.omnioone.app.messaging.OmnioCodes.setOn(this, it)
            },
            action(R.string.omnio_settings_lock_screen, lockScreenText(messages.lockScreenVisibilitySetting)) { pickLockScreen() },
            action(R.string.omnio_settings_message_sound, getString(R.string.omnio_settings_message_sound_desc)) { openMessageSound() },
        )
        card(
            switch(R.string.omnio_settings_long_mms, R.string.omnio_settings_long_mms_desc, messages.sendLongMessageMMS) { messages.sendLongMessageMMS = it },
            switch(R.string.omnio_settings_group_mms, R.string.omnio_settings_group_mms_desc, messages.sendGroupMessageMMS) { messages.sendGroupMessageMMS = it },
            action(R.string.omnio_settings_mms_limit, mmsLimitText(messages.mmsFileSizeLimit)) { pickMmsLimit() },
        )
        card(
            action(R.string.omnio_archive_title, null) {
                com.omnioone.app.messaging.OmnioChatListActivity.open(this, com.omnioone.app.messaging.OmnioChatListActivity.MODE_ARCHIVE)
            },
            switch(R.string.omnio_settings_keep_archived, R.string.omnio_settings_keep_archived_desc, messages.keepConversationsArchived) { messages.keepConversationsArchived = it },
            switch(R.string.omnio_settings_recycle_bin, R.string.omnio_settings_recycle_bin_desc, messages.useRecycleBin) { messages.useRecycleBin = it },
            action(R.string.omnio_bin_title, null) {
                com.omnioone.app.messaging.OmnioChatListActivity.open(this, com.omnioone.app.messaging.OmnioChatListActivity.MODE_BIN)
            },
            action(R.string.omnio_settings_blocked_words, blockedWordsText()) { manageBlockedWords() },
        )
        card(
            action(R.string.omnio_backup_messages, getString(R.string.omnio_backup_messages_desc)) {
                exportMessages.launch("omnio-messages-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date()) + ".json")
            },
            action(R.string.omnio_restore_messages, getString(R.string.omnio_restore_messages_desc)) {
                importMessages.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
        )

        section("🔗", R.string.omnio_link_helper_name)
        val helperOn = OmnioLinkHelperService.isEnabled(this)
        val prefs = getSharedPreferences("omnio", MODE_PRIVATE)
        card(
            action(R.string.omnio_settings_link_helper, statusOnOff(helperOn), statusColor(helperOn)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            switch(R.string.omnio_settings_offer_helper, R.string.omnio_settings_offer_helper_desc, !prefs.getBoolean("link_helper_dont_ask", false)) {
                prefs.edit().putBoolean("link_helper_dont_ask", !it).apply()
            },
            switch(R.string.omnio_photo_full_size, R.string.omnio_photo_full_size_desc, prefs.getBoolean("photo_full_size", false)) {
                prefs.edit().putBoolean("photo_full_size", it).apply()
            },
            action(R.string.omnio_photo_report, com.omnioone.app.helpers.OmnioPhotoCapture.lastNote(this) ?: getString(R.string.omnio_photo_report_none)) {
                val note = com.omnioone.app.helpers.OmnioPhotoCapture.lastNote(this) ?: return@action
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Omnio photo report", note))
                toast(R.string.omnio_copied)
            },
            action(R.string.omnio_fb_title, getString(R.string.omnio_fb_settings_desc)) {
                startActivity(Intent(this, OmnioFacebookMatchActivity::class.java))
            },
        )

        section("ℹ️", R.string.omnio_about)
        card(action(R.string.omnio_about_title, getString(R.string.omnio_about_row_desc)) {
            startActivity(Intent(this, OmnioAboutActivity::class.java))
        })
    }

    // ---- actions ----

    private val exportCalls = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        com.omnioone.app.core.ensureBackgroundThread {
            val count = try {
                val (json, total) = com.omnioone.app.helpers.OmnioCallLog.exportJson(this)
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                total
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (count < 0) toast(R.string.omnio_export_failed) else toast(getString(R.string.omnio_exported_calls, count))
            }
        }
    }

    private val exportMessages = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        toast(R.string.omnio_backup_working)
        com.omnioone.app.core.ensureBackgroundThread {
            val count = try {
                contentResolver.openOutputStream(uri)?.use { com.omnioone.app.messaging.OmnioMessageBackup.export(this, it) } ?: -1
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                if (count < 0) toast(R.string.omnio_backup_failed) else toast(getString(R.string.omnio_backup_done, count))
            }
        }
    }

    private val importMessages = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val defaultSms = Telephony.Sms.getDefaultSmsPackage(this) == packageName
        if (!defaultSms) {
            toast(R.string.omnio_restore_needs_default)
            return@registerForActivityResult
        }
        toast(R.string.omnio_restore_working)
        com.omnioone.app.core.ensureBackgroundThread {
            val result = try {
                contentResolver.openInputStream(uri)?.use { com.omnioone.app.messaging.OmnioMessageBackup.import(this, it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result == null) toast(R.string.omnio_restore_failed)
                else OmnioSheet.confirm(
                    this,
                    title = getString(R.string.omnio_restore_done_title),
                    message = getString(R.string.omnio_restore_done, result.written, result.skipped) +
                        (if (result.failed > 0) "\n" + getString(R.string.omnio_restore_some_failed, result.failed) else ""),
                    positive = getString(com.omnioone.app.R.string.ok),
                ) {}
            }
        }
    }

    private val importCalls = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        handlePermission(com.omnioone.app.core.PERMISSION_WRITE_CALL_LOG) { granted ->
            if (!granted) return@handlePermission
            com.omnioone.app.core.ensureBackgroundThread {
                val count = try {
                    val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
                    com.omnioone.app.helpers.OmnioCallLog.importJson(this, text)
                } catch (e: Exception) {
                    -1
                }
                runOnUiThread {
                    if (count < 0) toast(R.string.omnio_import_failed) else toast(getString(R.string.omnio_imported_calls, count))
                }
            }
        }
    }

    private val tabs = listOf(
        com.omnioone.app.core.TAB_CONTACTS to R.string.contacts_tab,
        com.omnioone.app.core.TAB_FAVORITES to R.string.favorites_tab,
        com.omnioone.app.core.TAB_CALL_HISTORY to R.string.call_history_tab,
    )

    private fun tabName(tab: Int) = getString(tabs.firstOrNull { it.first == tab }?.second ?: R.string.last_used_tab)

    private fun visibleTabsText() = tabs.filter { config.showTabs and it.first != 0 }.joinToString(", ") { getString(it.second) }

    private fun pickDefaultTab() {
        val keypad = -1
        val options = tabs + (com.omnioone.app.core.TAB_LAST_USED to R.string.last_used_tab) + (keypad to R.string.omnio_open_on_keypad)
        val dialpad = config.openDialPadAtLaunch
        OmnioSheet.list(this, getString(R.string.omnio_settings_default_tab), null, options.map {
            val active = if (it.first == keypad) dialpad else !dialpad && config.defaultTab == it.first
            OmnioSheet.Item(getString(it.second), trailing = if (active) "✓" else null)
        }) { picked ->
            val choice = options[picked].first
            // the keypad opens over the Contacts tab
            config.openDialPadAtLaunch = choice == keypad
            config.defaultTab = if (choice == keypad) com.omnioone.app.core.TAB_CONTACTS else choice
            buildRows()
        }
    }

    /** Tap a tab to show or hide it; at least one stays. The app restarts its tabs when you go back. */
    private fun pickVisibleTabs() {
        OmnioSheet.list(this, getString(R.string.omnio_settings_visible_tabs), getString(R.string.omnio_settings_visible_tabs_help), tabs.map {
            val shown = config.showTabs and it.first != 0
            OmnioSheet.Item(getString(it.second), trailing = if (shown) "✓" else "—", trailingColor = if (shown) OmnioSheet.GREEN else OmnioSheet.TEXT)
        }) { picked ->
            val tab = tabs[picked].first
            val updated = config.showTabs xor tab
            if (tabs.any { updated and it.first != 0 }) config.showTabs = updated
            buildRows()
            pickVisibleTabs()
        }
    }

    private fun pickCallStyle(unknownCallers: Boolean) {
        val styles = com.omnioone.app.helpers.OmnioCallStyle
        val current = if (unknownCallers) styles.unknownKey(this) else styles.defaultKey(this)
        val choices = (if (unknownCallers) listOf("" to getString(R.string.omnio_call_style_same)) else emptyList()) + styles.choices(this)
        val items = choices.map { (key, label) ->
            val desc = if (key.startsWith("builtin:")) key.removePrefix("builtin:").toIntOrNull()?.let { "  ·  " + getString(styles.description(it)) } ?: "" else ""
            OmnioSheet.Item(label + desc, trailing = if (key == current) "✓" else null)
        } + OmnioSheet.Item(getString(R.string.omnio_settings_design_own), labelColor = com.omnioone.app.core.OmnioColors.CONTACTS)
        OmnioSheet.list(
            this,
            getString(if (unknownCallers) R.string.omnio_settings_unknown_style else R.string.omnio_settings_call_style),
            getString(R.string.omnio_settings_call_style_help),
            items
        ) { picked ->
            if (picked >= choices.size) {
                startActivity(Intent(this, OmnioCallDesignerActivity::class.java))
                return@list
            }
            val key = choices[picked].first
            if (unknownCallers) styles.setUnknownKey(this, key) else styles.setDefaultKey(this, key)
            buildRows()
            testCall(key.ifEmpty { styles.defaultKey(this) })
        }
    }

    private fun testCall(key: String) {
        startActivity(Intent(this, OmnioCallActivity::class.java).apply {
            putExtra(OmnioCallActivity.EXTRA_PREVIEW, true)
            putExtra(OmnioCallActivity.EXTRA_STYLE_KEY, key)
        })
    }

    private fun openLanguage() {
        val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && try {
            startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null)))
            true
        } catch (e: Exception) {
            false
        }
        if (!opened) toast(com.omnioone.app.R.string.no_app_found)
    }

    @Suppress("DEPRECATION")
    private fun requestRole(role: String) {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleHeld(role)) {
            toast(R.string.omnio_settings_already_default)
            return
        }
        try {
            startActivityForResult(roleManager.createRequestRoleIntent(role), 4120)
        } catch (e: Exception) {
            toast(com.omnioone.app.R.string.no_app_found)
        }
    }

    private fun lockScreenText(value: Int) = getString(
        when (value) {
            LOCK_SCREEN_SENDER -> R.string.omnio_lock_sender
            LOCK_SCREEN_NOTHING -> R.string.omnio_lock_nothing
            else -> R.string.omnio_lock_sender_message
        }
    )

    private fun pickLockScreen() {
        val values = listOf(LOCK_SCREEN_SENDER_MESSAGE, LOCK_SCREEN_SENDER, LOCK_SCREEN_NOTHING)
        val current = messagesConfig.lockScreenVisibilitySetting
        OmnioSheet.list(
            this,
            getString(R.string.omnio_settings_lock_screen),
            getString(R.string.omnio_settings_lock_screen_desc),
            values.map { OmnioSheet.Item(lockScreenText(it), trailing = if (it == current) "✓" else null) }
        ) { picked ->
            messagesConfig.lockScreenVisibilitySetting = values[picked]
            buildRows()
        }
    }

    private fun statusText(on: Boolean) = getString(if (on) R.string.omnio_settings_is_default else R.string.omnio_settings_not_default)
    private fun statusOnOff(on: Boolean) = getString(if (on) R.string.omnio_on else R.string.omnio_off)
    private fun statusColor(on: Boolean) = if (on) OmnioColors.MESSAGES else OmnioColors.CONTACTS

    // ---- building blocks (shared look with the contact page) ----

    private fun topBar(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(36), dp(16), dp(8))
        addView(ImageView(this@OmnioSettingsActivity).apply {
            setImageResource(com.omnioone.app.R.drawable.ic_arrow_left_vector)
            imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = getDrawable(android.R.drawable.list_selector_background)
            setOnClickListener { finish() }
        })
        addView(TextView(this@OmnioSettingsActivity).apply {
            text = title
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
    }

    // ---- Messages settings ----

    private val mmsLimits = listOf(-1L, 2_097_152L, 1_048_576L, 614_400L, 307_200L, 204_800L, 102_400L)

    private fun mmsLimitText(limit: Long) = when {
        limit <= 0 -> getString(R.string.omnio_settings_mms_limit_none)
        limit >= 1_048_576L -> getString(R.string.omnio_settings_mms_limit_mb, (limit / 1_048_576L).toInt())
        else -> getString(R.string.omnio_settings_mms_limit_kb, (limit / 1024L).toInt())
    }

    private fun pickMmsLimit() {
        val current = messagesConfig.mmsFileSizeLimit
        OmnioSheet.list(this, getString(R.string.omnio_settings_mms_limit), getString(R.string.omnio_settings_mms_limit_help), mmsLimits.map {
            OmnioSheet.Item(mmsLimitText(it), trailing = if (it == current) "✓" else null)
        }) { picked ->
            messagesConfig.mmsFileSizeLimit = mmsLimits[picked]
            buildRows()
        }
    }

    private fun openMessageSound() {
        // make sure the Messages notification category exists, then open Android's settings for it
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (manager.getNotificationChannel("omnio_messages") == null) {
            manager.createNotificationChannel(android.app.NotificationChannel("omnio_messages", getString(R.string.omnio_notify_channel), android.app.NotificationManager.IMPORTANCE_HIGH))
        }
        try {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, "omnio_messages"))
        } catch (e: Exception) {
            toast(com.omnioone.app.R.string.no_app_found)
        }
    }

    private fun blockedWordsText(): String {
        val words = messagesConfig.blockedKeywords
        return if (words.isEmpty()) getString(R.string.omnio_settings_blocked_words_none) else words.sorted().joinToString(", ")
    }

    private fun manageBlockedWords() {
        val words = messagesConfig.blockedKeywords.sorted()
        val items = listOf(OmnioSheet.Item("＋  " + getString(R.string.omnio_settings_blocked_words_add), labelColor = OmnioColors.MESSAGES)) +
            words.map { OmnioSheet.Item(it, trailing = "✕", trailingColor = OmnioColors.MISSED) }
        OmnioSheet.list(this, getString(R.string.omnio_settings_blocked_words), getString(R.string.omnio_settings_blocked_words_help), items) { picked ->
            if (picked == 0) {
                OmnioSheet.input(this, getString(R.string.omnio_settings_blocked_words_add), hint = getString(R.string.omnio_settings_blocked_words_hint)) { typed ->
                    val word = typed.trim()
                    if (word.isNotEmpty()) messagesConfig.addBlockedKeyword(word)
                    buildRows()
                    manageBlockedWords()
                }
            } else {
                messagesConfig.removeBlockedKeyword(words[picked - 1])
                buildRows()
                manageBlockedWords()
            }
        }
    }

    private fun undoText(seconds: Int) = if (seconds <= 0) getString(R.string.omnio_undo_send_off) else getString(R.string.omnio_undo_send_seconds, seconds)

    private fun section(emoji: String, titleRes: Int) {
        form.addView(TextView(this).apply {
            text = emoji + "  " + getString(titleRes).uppercase()
            setTextColor(OmnioColors.TEXT)
            alpha = 0.75f
            letterSpacing = 0.05f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(24), 0, dp(8))
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
            if (index > 0) {
                card.addView(View(this).apply {
                    setBackgroundColor(0x1FFFFFFF)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                })
            }
            card.addView(row)
        }
        form.addView(card)
    }

    private fun texts(title: String, subtitle: String?, subtitleColor: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@OmnioSettingsActivity).apply {
            text = title
            setTextColor(OmnioColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        if (!subtitle.isNullOrEmpty()) {
            addView(TextView(this@OmnioSettingsActivity).apply {
                text = subtitle
                setTextColor(subtitleColor ?: OmnioColors.TEXT)
                if (subtitleColor == null) alpha = 0.6f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(0, dp(10), 0, dp(10))
        background = getDrawable(android.R.drawable.list_selector_background)
    }

    private fun action(titleRes: Int, subtitle: String?, subtitleColor: Int? = null, onClick: () -> Unit) = row().apply {
        addView(texts(getString(titleRes), subtitle, subtitleColor))
        addView(ImageView(this@OmnioSettingsActivity).apply {
            setImageResource(com.omnioone.app.R.drawable.ic_chevron_right_vector)
            imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        setOnClickListener { onClick() }
    }

    private fun switch(titleRes: Int, subtitleRes: Int?, checked: Boolean, onChange: (Boolean) -> Unit) = row().apply {
        addView(texts(getString(titleRes), subtitleRes?.let { getString(it) }))
        val toggle = SwitchCompat(this@OmnioSettingsActivity).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(OmnioColors.TEXT, 0xFF9AA3AF.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(OmnioColors.MESSAGES, 0xFF3A404C.toInt())
            )
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        addView(toggle)
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }
}
