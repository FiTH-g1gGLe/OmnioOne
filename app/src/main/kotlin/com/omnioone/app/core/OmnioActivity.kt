package com.omnioone.app.core

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

const val REQUEST_CODE_SET_DEFAULT_CALLER_ID = 1010

/**
 * Omnio One's base screen: dark Omnio background drawn edge to edge, permission requests,
 * the "make Omnio your phone app" prompts, and back-button handling.
 */
open class OmnioActivity : AppCompatActivity() {

    private var onPermission: ((Boolean) -> Unit)? = null
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        window.setBackgroundDrawable(ColorDrawable(OmnioColors.BACKGROUND))
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!onBackPressedCompat()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    /** Return true if the screen dealt with the back button itself. */
    protected open fun onBackPressedCompat(): Boolean = false

    // ---- edge to edge ----

    /** Pads views clear of the status bar, navigation bar and keyboard. */
    fun setupEdgeToEdge(
        padTopSystem: List<View> = emptyList(),
        padBottomSystem: List<View> = emptyList(),
        padBottomImeAndSystem: List<View> = emptyList(),
    ) {
        val base = HashMap<View, IntArray>()
        (padTopSystem + padBottomSystem + padBottomImeAndSystem).distinct().forEach {
            base[it] = intArrayOf(it.paddingTop, it.paddingBottom)
        }
        val root = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val system = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val imeAndSystem = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
            padTopSystem.forEach { it.updatePadding(top = base[it]!![0] + system.top) }
            padBottomSystem.forEach { it.updatePadding(bottom = base[it]!![1] + system.bottom) }
            padBottomImeAndSystem.forEach { it.updatePadding(bottom = base[it]!![1] + imeAndSystem.bottom) }
            insets
        }
        root.requestApplyInsets()
    }

    // ---- permissions ----

    /** Asks for a permission if needed, then tells [callback] whether it was given. */
    fun handlePermission(permissionId: Int, callback: (granted: Boolean) -> Unit) {
        if (hasPermission(permissionId)) {
            callback(true)
            return
        }
        onPermission = callback
        ActivityCompat.requestPermissions(this, arrayOf(permissionName(permissionId)), PERMISSION_REQUEST)
    }

    fun handleNotificationPermission(callback: (granted: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) callback(true) else handlePermission(PERMISSION_POST_NOTIFICATIONS, callback)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val callback = onPermission
            onPermission = null
            callback?.invoke(grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    // ---- default apps ----

    /** Asks Android to make Omnio One the phone's calling app. */
    @Suppress("DEPRECATION")
    protected fun launchSetDefaultDialerIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_DIALER) && !roles.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQUEST_CODE_SET_DEFAULT_DIALER)
            }
        } else {
            try {
                startActivityForResult(
                    Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName),
                    REQUEST_CODE_SET_DEFAULT_DIALER
                )
            } catch (e: Exception) {
                toast(com.omnioone.app.R.string.omnio_no_app_found)
            }
        }
    }

    /** Asks Android to let Omnio One screen calls (caller ID and blocking). */
    @Suppress("DEPRECATION")
    fun setDefaultCallerIdApp() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQUEST_CODE_SET_DEFAULT_CALLER_ID)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 4242
    }
}

/** Omnio One settings shared by every part of the app (kept in the same settings file as before). */
open class OmnioBaseConfig(val context: Context) {
    protected val prefs = context.applicationContext.getSharedPreferences("Prefs", Context.MODE_PRIVATE)

    var textColor: Int
        get() = OmnioColors.TEXT
        set(@Suppress("UNUSED_PARAMETER") value) {}
    var backgroundColor: Int
        get() = OmnioColors.BACKGROUND
        set(@Suppress("UNUSED_PARAMETER") value) {}
    var primaryColor: Int
        get() = OmnioColors.CONTACTS
        set(@Suppress("UNUSED_PARAMETER") value) {}

    var defaultTab: Int
        get() = prefs.getInt("default_tab", TAB_CONTACTS)
        set(value) = prefs.edit().putInt("default_tab", value).apply()

    var sorting: Int
        get() = prefs.getInt("sort_order", SORT_BY_FULL_NAME)
        set(value) = prefs.edit().putInt("sort_order", value).apply()

    var startNameWithSurname: Boolean
        get() = prefs.getBoolean("start_name_with_surname", false)
        set(value) = prefs.edit().putBoolean("start_name_with_surname", value).apply()

    var onContactClick: Int
        get() = prefs.getInt("on_contact_click", ON_CLICK_VIEW_CONTACT)
        set(value) = prefs.edit().putInt("on_contact_click", value).apply()

    var showCallConfirmation: Boolean
        get() = prefs.getBoolean("show_call_confirmation", false)
        set(value) = prefs.edit().putBoolean("show_call_confirmation", value).apply()

    var blockUnknownNumbers: Boolean
        get() = prefs.getBoolean("block_unknown_numbers", false)
        set(value) = prefs.edit().putBoolean("block_unknown_numbers", value).apply()

    var blockHiddenNumbers: Boolean
        get() = prefs.getBoolean("block_hidden_numbers", false)
        set(value) = prefs.edit().putBoolean("block_hidden_numbers", value).apply()

    /** Accounts whose contacts are hidden ("name:type"). */
    var ignoredContactSources: HashSet<String>
        get() = HashSet(prefs.getStringSet("ignored_contact_sources_2", hashSetOf(".")).orEmpty())
        set(value) = prefs.edit().remove("ignored_contact_sources_2").putStringSet("ignored_contact_sources_2", value).apply()

    var favoritesContactsOrder: String
        get() = prefs.getString("favorites_contacts_order", "").orEmpty()
        set(value) = prefs.edit().putString("favorites_contacts_order", value).apply()

    var isCustomOrderSelected: Boolean
        get() = prefs.getBoolean("favorites_custom_order_selected", false)
        set(value) = prefs.edit().putBoolean("favorites_custom_order_selected", value).apply()

    var viewType: Int
        get() = prefs.getInt("view_type", VIEW_TYPE_LIST)
        set(value) = prefs.edit().putInt("view_type", value).apply()

    var contactsGridColumnCount: Int
        get() = prefs.getInt("contacts_grid_column_count", 4)
        set(value) = prefs.edit().putInt("contacts_grid_column_count", value).apply()

    var lastUsedViewPagerPage: Int
        get() = prefs.getInt("last_used_view_pager_page", 0)
        set(value) = prefs.edit().putInt("last_used_view_pager_page", value).apply()

    var speedDial: String
        get() = prefs.getString("speed_dial", "").orEmpty()
        set(value) = prefs.edit().putString("speed_dial", value).apply()

    var fontSize: Int
        get() = prefs.getInt("font_size", 2)
        set(value) = prefs.edit().putInt("font_size", value).apply()
}

val Context.baseConfig get() = OmnioBaseConfig(this)

fun Context.getProperTextColor() = OmnioColors.TEXT
fun Context.getProperBackgroundColor() = OmnioColors.BACKGROUND
fun Context.getProperPrimaryColor() = OmnioColors.CONTACTS
