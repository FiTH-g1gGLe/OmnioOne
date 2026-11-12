package com.omnioone.app.phone

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.omnioone.app.core.OmnioBaseConfig
import com.omnioone.app.core.TAB_CALL_HISTORY
import com.omnioone.app.core.TAB_CONTACTS
import com.omnioone.app.core.TAB_FAVORITES
import com.omnioone.app.core.getPhoneNumberTypeText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The phone tabs, in the order they appear before Messages. */
val omnioPhoneTabs = listOf(TAB_CONTACTS, TAB_FAVORITES, TAB_CALL_HISTORY)

/** Saved with the screen so a rotation doesn't open the keypad twice. */
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"

val Context.config: OmnioPhoneConfig get() = OmnioPhoneConfig(applicationContext)

/** A number on a speed dial key (1–9). Stored as JSON, so the field names stay as they are. */
data class OmnioSpeedDial(
    val id: Int,
    var number: String = "",
    var displayName: String = "",
    var type: Int? = null,
    var label: String? = null,
) {
    fun isValid() = number.isNotBlank()

    fun getName(context: Context): String {
        val kind = type ?: return displayName
        val custom = label ?: return displayName
        return "$displayName - ${context.getPhoneNumberTypeText(kind, custom)}"
    }
}

/**
 * Omnio One's phone settings: tabs, keypad, call screen behaviour, speed dial and which SIM
 * to use for a number. Saved in the same place and under the same names as always, so
 * nobody's settings change when they update.
 */
class OmnioPhoneConfig(context: Context) : OmnioBaseConfig(context) {

    private val appContext = context.applicationContext

    private fun flag(key: String, default: Boolean) = prefs.getBoolean(key, default)
    private fun setFlag(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    var showTabs: Int
        get() = prefs.getInt("show_tabs", TAB_CONTACTS or TAB_FAVORITES or TAB_CALL_HISTORY)
        set(value) = prefs.edit().putInt("show_tabs", value).apply()

    var groupSubsequentCalls: Boolean
        get() = flag("group_subsequent_calls", true)
        set(value) = setFlag("group_subsequent_calls", value)

    var openDialPadAtLaunch: Boolean
        get() = flag(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(value) = setFlag(OPEN_DIAL_PAD_AT_LAUNCH, value)

    var disableProximitySensor: Boolean
        get() = flag("disable_proximity_sensor", false)
        set(value) = setFlag("disable_proximity_sensor", value)

    var disableSwipeToAnswer: Boolean
        get() = flag("disable_swipe_to_answer", false)
        set(value) = setFlag("disable_swipe_to_answer", value)

    var wasOverlaySnackbarConfirmed: Boolean
        get() = flag("was_overlay_snackbar_confirmed", false)
        set(value) = setFlag("was_overlay_snackbar_confirmed", value)

    var dialpadVibration: Boolean
        get() = flag("dialpad_vibration", true)
        set(value) = setFlag("dialpad_vibration", value)

    var dialpadBeeps: Boolean
        get() = flag("dialpad_beeps", true)
        set(value) = setFlag("dialpad_beeps", value)

    var alwaysShowFullscreen: Boolean
        get() = flag("always_show_fullscreen", false)
        set(value) = setFlag("always_show_fullscreen", value)

    // ---- speed dial ----

    /** Keys 1–9, each with its number (or empty). */
    fun speedDials(): ArrayList<OmnioSpeedDial> {
        val saved = HashMap<Int, OmnioSpeedDial>()
        try {
            val array = JSONArray(speedDial.ifBlank { "[]" })
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optInt("id")
                if (id !in 1..9) continue
                saved[id] = OmnioSpeedDial(
                    id = id,
                    number = item.optString("number"),
                    displayName = item.optString("displayName"),
                    type = if (item.has("type") && !item.isNull("type")) item.optInt("type") else null,
                    label = if (item.has("label") && !item.isNull("label")) item.optString("label") else null,
                )
            }
        } catch (ignored: Exception) {
        }
        return ArrayList((1..9).map { saved[it] ?: OmnioSpeedDial(it) })
    }

    fun saveSpeedDials(dials: List<OmnioSpeedDial>) {
        val array = JSONArray()
        dials.forEach { dial ->
            array.put(JSONObject().apply {
                put("id", dial.id)
                put("number", dial.number)
                put("displayName", dial.displayName)
                dial.type?.let { put("type", it) }
                dial.label?.let { put("label", it) }
            })
        }
        speedDial = array.toString()
    }

    // ---- remembered SIM per number ----

    private val region: String by lazy {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        listOf(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }?.uppercase(Locale.US).orEmpty()
    }

    private fun simKey(number: String): String {
        val plain = Uri.decode(number).removePrefix("tel:")
        return SIM_PREFIX + (PhoneNumberUtils.formatNumberToE164(plain, region) ?: PhoneNumberUtils.normalizeNumber(plain))
    }

    fun rememberSim(number: String, handle: PhoneAccountHandle) {
        val json = JSONObject().apply {
            put("packageName", handle.componentName.packageName)
            put("className", handle.componentName.className)
            put("id", handle.id)
        }
        prefs.edit().putString(simKey(number), json.toString()).apply()
    }

    fun forgetSim(number: String) = prefs.edit().remove(simKey(number)).apply()

    /** The SIM you chose to always use for this number, if any. */
    fun simFor(number: String): PhoneAccountHandle? {
        prefs.getString(simKey(number), null)?.let { return handleFrom(it) }
        // numbers saved in a different format: compare them as phone numbers
        val plain = Uri.decode(number).removePrefix("tel:")
        @Suppress("DEPRECATION")
        val key = prefs.all.keys.firstOrNull { it.startsWith(SIM_PREFIX) && PhoneNumberUtils.compare(it.removePrefix(SIM_PREFIX), plain) }
            ?: return null
        return prefs.getString(key, null)?.let { handleFrom(it) }
    }

    private fun handleFrom(json: String): PhoneAccountHandle? = try {
        val item = JSONObject(json)
        PhoneAccountHandle(ComponentName(item.getString("packageName"), item.getString("className")), item.getString("id"))
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SIM_PREFIX = "remember_sim_"
    }
}
