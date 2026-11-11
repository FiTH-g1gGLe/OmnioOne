package com.omnioone.app.core

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.viewbinding.ViewBinding
import com.google.android.material.tabs.TabLayout
import com.omnioone.app.R
import java.util.Locale

/*
 * Omnio One basics: the small everyday helpers the whole app uses
 * (messages on screen, showing and hiding views, permissions, opening other apps…).
 */

const val OMNIO_PACKAGE = "com.omnioone.app"
const val OMNIO_GITHUB_URL = "https://github.com/FiTH-g1gGLe/OmnioOne"

// ---- what the app asks Android for (the numbers stay the same as before so saved choices still match) ----

const val PERMISSION_READ_CONTACTS = 5
const val PERMISSION_WRITE_CONTACTS = 6
const val PERMISSION_CALL_PHONE = 9
const val PERMISSION_READ_CALL_LOG = 10
const val PERMISSION_WRITE_CALL_LOG = 11
const val PERMISSION_READ_SMS = 13
const val PERMISSION_SEND_SMS = 14
const val PERMISSION_READ_PHONE_STATE = 15
const val PERMISSION_POST_NOTIFICATIONS = 17

fun permissionName(id: Int): String = when (id) {
    PERMISSION_READ_CONTACTS -> Manifest.permission.READ_CONTACTS
    PERMISSION_WRITE_CONTACTS -> Manifest.permission.WRITE_CONTACTS
    PERMISSION_CALL_PHONE -> Manifest.permission.CALL_PHONE
    PERMISSION_READ_CALL_LOG -> Manifest.permission.READ_CALL_LOG
    PERMISSION_WRITE_CALL_LOG -> Manifest.permission.WRITE_CALL_LOG
    PERMISSION_READ_SMS -> Manifest.permission.READ_SMS
    PERMISSION_SEND_SMS -> Manifest.permission.SEND_SMS
    PERMISSION_READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
    PERMISSION_POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else ""
    else -> ""
}

fun Context.hasPermission(id: Int): Boolean {
    val name = permissionName(id)
    return name.isEmpty() || ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
}

// ---- tabs, sorting and other saved choices (same values as before) ----

const val TAB_LAST_USED = 0
const val TAB_CONTACTS = 1
const val TAB_FAVORITES = 2
const val TAB_CALL_HISTORY = 4

const val SORT_BY_FIRST_NAME = 128
const val SORT_BY_MIDDLE_NAME = 256
const val SORT_BY_SURNAME = 512
const val SORT_DESCENDING = 1024
const val SORT_BY_FULL_NAME = 65536
const val SORT_BY_CUSTOM = 131072
const val SORT_BY_DATE_CREATED = 262144

const val VIEW_TYPE_GRID = 1
const val VIEW_TYPE_LIST = 2

const val ON_CLICK_CALL_CONTACT = 1
const val ON_CLICK_VIEW_CONTACT = 2
const val ON_CLICK_EDIT_CONTACT = 3

const val PHOTO_ADDED = 1
const val PHOTO_REMOVED = 2
const val PHOTO_CHANGED = 3
const val PHOTO_UNCHANGED = 4

const val KEY_PHONE = "phone"
const val REQUEST_CODE_SET_DEFAULT_DIALER = 1007

// ---- background work ----

fun isOnMainThread() = Looper.myLooper() == Looper.getMainLooper()

/** Runs [work] off the main thread (straight away if already off it). */
fun ensureBackgroundThread(work: () -> Unit) {
    if (isOnMainThread()) Thread { work() }.start() else work()
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

// ---- messages on screen ----

fun Context.toast(text: String, length: Int = Toast.LENGTH_SHORT) {
    val show = { Toast.makeText(applicationContext, text, length).show() }
    if (isOnMainThread()) show() else mainHandler.post(show)
}

fun Context.toast(id: Int, length: Int = Toast.LENGTH_SHORT) = toast(getString(id), length)

fun Context.showErrorToast(message: String, length: Int = Toast.LENGTH_LONG) = toast(getString(R.string.omnio_error, message), length)

fun Context.showErrorToast(error: Throwable, length: Int = Toast.LENGTH_LONG) = showErrorToast(error.message ?: error.toString(), length)

fun Context.copyToClipboard(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Omnio One", text))
    toast(getString(R.string.omnio_copied_text, text))
}

// ---- showing and hiding ----

fun View.beVisible() {
    visibility = View.VISIBLE
}

fun View.beGone() {
    visibility = View.GONE
}

fun View.beVisibleIf(show: Boolean) = if (show) beVisible() else beGone()

fun View.beGoneIf(hide: Boolean) = beVisibleIf(!hide)

fun View.isVisible() = visibility == View.VISIBLE

/** Runs [callback] once, after the view has been measured and laid out. */
fun View.onGlobalLayout(callback: () -> Unit) {
    viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            viewTreeObserver?.removeOnGlobalLayoutListener(this)
            callback()
        }
    })
}

/** A short tap of vibration, like a keyboard key. */
fun View.performHapticFeedback() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

fun ImageView.applyColorFilter(color: Int) = setColorFilter(color, PorterDuff.Mode.SRC_IN)

fun Resources.getColoredDrawableWithColor(id: Int, color: Int, alpha: Int = 255): Drawable =
    getDrawable(id, null).mutate().apply {
        setTint(color)
        this.alpha = alpha
    }

fun Drawable.convertToBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    return bitmap
}

fun Activity.hideKeyboard() {
    val hide = {
        val view = currentFocus ?: View(this)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        Unit
    }
    if (isOnMainThread()) hide() else mainHandler.post { hide() }
}

/** Reads a screen's layout once, the first time it's needed. */
inline fun <T : ViewBinding> Activity.viewBinding(crossinline inflate: (LayoutInflater) -> T) =
    lazy(LazyThreadSafetyMode.NONE) { inflate(layoutInflater) }

fun TabLayout.onTabSelectionChanged(
    tabUnselectedAction: ((TabLayout.Tab) -> Unit)? = null,
    tabSelectedAction: ((TabLayout.Tab) -> Unit)? = null,
) = addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
    override fun onTabSelected(tab: TabLayout.Tab) {
        tabSelectedAction?.invoke(tab)
    }

    override fun onTabUnselected(tab: TabLayout.Tab) {
        tabUnselectedAction?.invoke(tab)
    }

    override fun onTabReselected(tab: TabLayout.Tab) {
        tabSelectedAction?.invoke(tab)
    }
})

// ---- colours ----

/** White or dark grey, whichever reads better on top of this colour. */
fun Int.getContrastColor(): Int = if (ColorUtils.calculateLuminance(this) > 0.5) 0xFF333333.toInt() else Color.WHITE

/** A slightly darker version of this colour. */
fun Int.darkenColor(percent: Int = 8): Int {
    if (this == Color.WHITE || this == Color.BLACK) return this
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this, hsl)
    hsl[2] = (hsl[2] - percent / 100f).coerceAtLeast(0f)
    return ColorUtils.HSLToColor(hsl)
}

// ---- numbers and time ----

/** 75 → "01:15", 3725 → "01:02:05". */
fun Int.getFormattedDuration(forceShowHours: Boolean = false): String {
    val hours = this / 3600
    val minutes = this % 3600 / 60
    val seconds = this % 60
    val start = when {
        this >= 3600 -> String.format(Locale.getDefault(), "%02d:", hours)
        forceShowHours -> "0:"
        else -> ""
    }
    return start + String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

/** Calls [callback] with this number, then once a second (or [intervalMillis]) counting down to 0. */
fun Int.countdown(intervalMillis: Long, callback: (Int) -> Unit) {
    callback(this)
    if (this <= 0) return
    mainHandler.postDelayed({ (this - 1).countdown(intervalMillis, callback) }, intervalMillis)
}

fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to || from !in indices || to !in indices) return
    add(to, removeAt(from))
}

fun <T> MutableList<T>.swap(first: Int, second: Int) {
    if (first == second) return
    val held = this[first]
    this[first] = this[second]
    this[second] = held
}

// ---- phone and system ----

val Context.telecomManager: TelecomManager get() = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

val Context.shortcutManager: ShortcutManager get() = getSystemService(ShortcutManager::class.java)

val Context.notificationManager: NotificationManager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

/** Is Omnio One the phone's calling app? */
fun Context.isDefaultDialer(): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roles = getSystemService(RoleManager::class.java)
        roles.isRoleAvailable(RoleManager.ROLE_DIALER) && roles.isRoleHeld(RoleManager.ROLE_DIALER)
    } else {
        telecomManager.defaultDialerPackage == packageName
    }
} catch (e: Exception) {
    false
}

fun Context.canUseFullScreenIntent(): Boolean = Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()

fun Context.openNotificationSettings() {
    launchActivityIntent(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

fun Context.openFullScreenIntentSettings(appId: String) {
    if (Build.VERSION.SDK_INT >= 34) {
        launchActivityIntent(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.fromParts("package", appId, null)))
    }
}

/** "Mobile", "Home", "Work"… for a phone number type. */
fun Context.getPhoneNumberTypeText(type: Int, label: String): String = if (type == Phone.TYPE_CUSTOM) label else getString(
    when (type) {
        Phone.TYPE_MOBILE -> R.string.omnio_number_mobile
        Phone.TYPE_HOME -> R.string.omnio_number_home
        Phone.TYPE_WORK -> R.string.omnio_number_work
        Phone.TYPE_MAIN -> R.string.omnio_number_main
        Phone.TYPE_FAX_WORK -> R.string.omnio_number_work_fax
        Phone.TYPE_FAX_HOME -> R.string.omnio_number_home_fax
        Phone.TYPE_PAGER -> R.string.omnio_number_pager
        else -> R.string.omnio_number_other
    }
)

// ---- opening other things ----

fun Context.launchActivityIntent(intent: Intent) {
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(R.string.omnio_no_app_found)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

/** Opens a text to [recipient] in Omnio One's own Messages. */
fun Context.launchSendSMSIntent(recipient: String) {
    launchActivityIntent(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", recipient, null)).setPackage(packageName))
}

fun Context.sendEmailIntent(recipient: String) {
    launchActivityIntent(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", recipient, null)))
}

fun Context.launchViewContactIntent(uri: Uri) {
    launchActivityIntent(Intent(ContactsContract.QuickContact.ACTION_QUICK_CONTACT).setData(uri))
}

// ---- Small extras used by the main screen ----

const val CONTACTS_GRID_MAX_COLUMNS_COUNT = 10

fun isQPlus() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

fun Drawable.applyColorFilter(color: Int) {
    mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
}

/** Sets a bottom tab's icon and dims its label when it is not the open tab. */
fun Context.updateBottomTabItemColors(view: View?, isActive: Boolean, drawableId: Int? = null) {
    val color = if (isActive) OmnioColors.CONTACTS else OmnioColors.TEXT_MUTED
    view?.findViewById<ImageView>(R.id.tab_item_icon)?.apply {
        if (drawableId != null) setImageDrawable(ContextCompat.getDrawable(context, drawableId))
        setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
    view?.findViewById<android.widget.TextView>(R.id.tab_item_label)?.setTextColor(color)
}

fun Context.getBottomNavigationBackgroundColor() = OmnioColors.SHEET
