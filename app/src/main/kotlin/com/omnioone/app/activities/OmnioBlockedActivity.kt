package com.omnioone.app.activities

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.BlockedNumberContract.BlockedNumbers
import android.text.InputType
import androidx.appcompat.widget.SwitchCompat
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.ensureBackgroundThread

/** Omnio One blocked numbers: block unknown or hidden callers, and manage your block list. */
class OmnioBlockedActivity : SimpleActivity() {

    private lateinit var screen: OmnioScreen
    private var blocked = listOf<Pair<Long, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = OmnioScreen(this, getString(R.string.omnio_settings_blocked))
        setContentView(screen.root)
        build()
        load()
    }

    private fun load() {
        ensureBackgroundThread {
            val list = ArrayList<Pair<Long, String>>()
            try {
                contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) list += cursor.getLong(0) to cursor.getString(1).orEmpty()
                }
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.omnio_blocked_needs_default) }
            }
            runOnUiThread {
                blocked = list
                build()
            }
        }
    }

    private fun toggle(checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        isChecked = checked
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(OmnioColors.TEXT, 0xFF9AA3AF.toInt()))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(OmnioColors.MISSED, 0xFF3A404C.toInt()))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    /** Blocking unknown or hidden callers needs Omnio One as the call screening app. */
    private fun askForScreening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            try {
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 4242)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun build() {
        screen.content.removeAllViews()
        screen.section("🛡️", getString(R.string.omnio_blocked_who))
        val unknown = toggle(baseConfig.blockUnknownNumbers) {
            baseConfig.blockUnknownNumbers = it
            if (it) askForScreening()
        }
        val hidden = toggle(baseConfig.blockHiddenNumbers) {
            baseConfig.blockHiddenNumbers = it
            if (it) askForScreening()
        }
        screen.card(listOf(
            screen.row(getString(R.string.omnio_block_unknown), getString(R.string.omnio_block_unknown_desc), trailing = unknown) { unknown.toggle() },
            screen.row(getString(R.string.omnio_block_hidden), getString(R.string.omnio_block_hidden_desc), trailing = hidden) { hidden.toggle() },
        ))

        screen.section("🚫", getString(R.string.omnio_blocked_list, blocked.size))
        if (blocked.isEmpty()) {
            screen.note(getString(R.string.omnio_blocked_none))
        } else {
            screen.card(blocked.map { (id, number) ->
                screen.row(number, null, trailing = screen.iconButton(com.omnioone.commons.R.drawable.ic_cross_vector, OmnioColors.TEXT_MUTED, getString(R.string.omnio_unblock)) {
                    unblock(id)
                }, onClick = null)
            })
        }
        screen.button(getString(R.string.omnio_block_a_number), OmnioColors.MISSED) { addNumber() }
    }

    private fun addNumber() {
        OmnioSheet.input(
            this,
            title = getString(R.string.omnio_block_a_number),
            hint = getString(R.string.omnio_phone_number),
            inputType = InputType.TYPE_CLASS_PHONE,
            positive = getString(R.string.omnio_block_number)
        ) { typed ->
            val number = typed.trim()
            if (number.isEmpty()) return@input
            ensureBackgroundThread {
                try {
                    contentResolver.insert(BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) })
                } catch (e: Exception) {
                    runOnUiThread { toast(R.string.omnio_block_failed) }
                }
                load()
            }
        }
    }

    private fun unblock(id: Long) {
        ensureBackgroundThread {
            try {
                contentResolver.delete(BlockedNumbers.CONTENT_URI, "${BlockedNumbers.COLUMN_ID} = ?", arrayOf(id.toString()))
            } catch (ignored: Exception) {
            }
            load()
        }
    }
}
