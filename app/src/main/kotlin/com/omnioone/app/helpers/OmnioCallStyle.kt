package com.omnioone.app.helpers

import android.content.Context
import com.omnioone.commons.helpers.OmnioColors
import com.omnioone.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Omnio One call screen designs.
 *
 * Every call screen is drawn from a [Design]. The five built-in styles are ready-made designs,
 * the customiser saves your own, and you can pick one for everyone, one for unknown callers,
 * and one per person. Choices are stored as keys: "builtin:3" or "custom:<id>".
 */
object OmnioCallStyle {
    const val CLASSIC = 0
    const val POSTER = 1
    const val MINIMAL = 2
    const val CONTEXT = 3
    const val PULSE = 4
    const val ORBIT = 5

    val all = listOf(CLASSIC, POSTER, MINIMAL, CONTEXT, PULSE, ORBIT)

    enum class Background { SOLID, GRADIENT, PHOTO_BLUR, PHOTO_FULL }
    enum class PhotoShape { CIRCLE, ROUNDED, HIDDEN }
    enum class Answer { BUTTONS, SLIDE, SWIPE_UP }

    data class Design(
        var id: String = "",
        var name: String = "",
        var background: Background = Background.SOLID,
        var backgroundColor: Int = OmnioColors.BACKGROUND,
        var gradientColor: Int = 0xFF1B2450.toInt(),
        var photoShape: PhotoShape = PhotoShape.CIRCLE,
        var photoSize: Int = 140,
        var pulse: Boolean = false,
        var orbit: Boolean = false,
        var answer: Answer = Answer.BUTTONS,
        var answerVariant: Int = 0,
        var acceptColor: Int = OmnioColors.MESSAGES,
        var declineColor: Int = 0xFFE5484D.toInt(),
        var textColor: Int = OmnioColors.TEXT,
        var nameSize: Int = 32,
        var showNumber: Boolean = true,
        var showLastText: Boolean = false,
        var showBirthday: Boolean = false,
        var showNotes: Boolean = false,
        var showApps: Boolean = false,
        var quickMessage: Boolean = true,
        var quickSilence: Boolean = true,
    ) {
        val showsCard get() = showLastText || showBirthday || showNotes || showApps

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("name", name)
            put("background", background.name); put("backgroundColor", backgroundColor); put("gradientColor", gradientColor)
            put("photoShape", photoShape.name); put("photoSize", photoSize); put("pulse", pulse); put("orbit", orbit)
            put("answer", answer.name); put("answerVariant", answerVariant)
            put("acceptColor", acceptColor); put("declineColor", declineColor); put("textColor", textColor); put("nameSize", nameSize)
            put("showNumber", showNumber); put("showLastText", showLastText); put("showBirthday", showBirthday)
            put("showNotes", showNotes); put("showApps", showApps)
            put("quickMessage", quickMessage); put("quickSilence", quickSilence)
        }

        companion object {
            fun fromJson(json: JSONObject) = Design(
                id = json.optString("id"),
                name = json.optString("name"),
                background = enumOr(json.optString("background"), Background.SOLID),
                backgroundColor = json.optInt("backgroundColor", OmnioColors.BACKGROUND),
                gradientColor = json.optInt("gradientColor", 0xFF1B2450.toInt()),
                photoShape = enumOr(json.optString("photoShape"), PhotoShape.CIRCLE),
                photoSize = json.optInt("photoSize", 140),
                pulse = json.optBoolean("pulse", false),
                orbit = json.optBoolean("orbit", false),
                answer = enumOr(json.optString("answer"), Answer.BUTTONS),
                answerVariant = json.optInt("answerVariant", 0),
                acceptColor = json.optInt("acceptColor", OmnioColors.MESSAGES),
                declineColor = json.optInt("declineColor", 0xFFE5484D.toInt()),
                textColor = json.optInt("textColor", OmnioColors.TEXT),
                nameSize = json.optInt("nameSize", 32),
                showNumber = json.optBoolean("showNumber", true),
                showLastText = json.optBoolean("showLastText", false),
                showBirthday = json.optBoolean("showBirthday", false),
                showNotes = json.optBoolean("showNotes", false),
                showApps = json.optBoolean("showApps", false),
                quickMessage = json.optBoolean("quickMessage", true),
                quickSilence = json.optBoolean("quickSilence", true),
            )

            private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
                enumValues<T>().firstOrNull { it.name == value } ?: fallback
        }
    }

    /** The five built-in styles as designs. */
    fun preset(style: Int): Design = when (style) {
        CLASSIC -> Design(id = "builtin:$CLASSIC", photoSize = 150)
        POSTER -> Design(
            id = "builtin:$POSTER", background = Background.PHOTO_FULL, photoShape = PhotoShape.HIDDEN,
            answer = Answer.SWIPE_UP, nameSize = 42
        )
        MINIMAL -> Design(id = "builtin:$MINIMAL", photoShape = PhotoShape.HIDDEN, answer = Answer.SLIDE, nameSize = 46)
        PULSE -> Design(id = "builtin:$PULSE", background = Background.GRADIENT, pulse = true)
        ORBIT -> Design(id = "builtin:$ORBIT", background = Background.GRADIENT, gradientColor = 0xFF101828.toInt(), photoSize = 150, orbit = true)
        else -> Design(
            id = "builtin:$CONTEXT", photoSize = 104, nameSize = 30,
            showLastText = true, showBirthday = true, showNotes = true, showApps = true
        )
    }.also { it.name = "" }

    fun name(style: Int) = when (style) {
        POSTER -> R.string.omnio_call_style_poster
        MINIMAL -> R.string.omnio_call_style_minimal
        CONTEXT -> R.string.omnio_call_style_context
        PULSE -> R.string.omnio_call_style_pulse
        ORBIT -> R.string.omnio_call_style_orbit
        else -> R.string.omnio_call_style_classic
    }

    fun description(style: Int) = when (style) {
        POSTER -> R.string.omnio_call_style_poster_desc
        MINIMAL -> R.string.omnio_call_style_minimal_desc
        CONTEXT -> R.string.omnio_call_style_context_desc
        PULSE -> R.string.omnio_call_style_pulse_desc
        ORBIT -> R.string.omnio_call_style_orbit_desc
        else -> R.string.omnio_call_style_classic_desc
    }

    // ---- storage ----

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    fun builtinKey(style: Int) = "builtin:$style"

    /** Saved custom designs. */
    fun customDesigns(context: Context): List<Design> = try {
        val array = JSONArray(prefs(context).getString("call_designs", "[]"))
        (0 until array.length()).map { Design.fromJson(array.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    /** Saves (adds or replaces) a custom design; returns its key. */
    fun saveCustom(context: Context, design: Design): String {
        if (design.id.isEmpty() || design.id.startsWith("builtin:")) design.id = "custom:" + UUID.randomUUID().toString().take(8)
        val list = customDesigns(context).filter { it.id != design.id } + design
        prefs(context).edit().putString("call_designs", JSONArray(list.map { it.toJson() }).toString()).apply()
        return design.id
    }

    fun deleteCustom(context: Context, id: String) {
        val list = customDesigns(context).filter { it.id != id }
        prefs(context).edit().putString("call_designs", JSONArray(list.map { it.toJson() }).toString()).apply()
        // anything using it falls back to the default
        val edit = prefs(context).edit()
        prefs(context).all.forEach { (key, value) -> if (value == id && key.startsWith("call_style")) edit.remove(key) }
        edit.apply()
    }

    /** A design by key, or null if it no longer exists. */
    fun design(context: Context, key: String?): Design? {
        if (key.isNullOrEmpty()) return null
        if (key.startsWith("builtin:")) return key.removePrefix("builtin:").toIntOrNull()?.let { preset(it) }
        return customDesigns(context).firstOrNull { it.id == key }
    }

    fun label(context: Context, key: String?): String {
        if (key.isNullOrEmpty()) return context.getString(R.string.omnio_call_style_same)
        if (key.startsWith("builtin:")) return context.getString(name(key.removePrefix("builtin:").toIntOrNull() ?: CONTEXT))
        return design(context, key)?.name?.ifEmpty { null } ?: context.getString(R.string.omnio_call_style_custom)
    }

    /** Style for everyone. */
    fun defaultKey(context: Context): String = prefs(context).getString("call_style_key", null)
        ?: builtinKey(prefs(context).getInt("call_style", CONTEXT))

    fun setDefaultKey(context: Context, key: String) = prefs(context).edit().putString("call_style_key", key).apply()

    /** Style for numbers not in your contacts ("" = same as everyone). */
    fun unknownKey(context: Context): String = prefs(context).getString("call_style_unknown_key", "") ?: ""

    fun setUnknownKey(context: Context, key: String) = prefs(context).edit().putString("call_style_unknown_key", key).apply()

    /** Style for one person ("" = same as everyone). */
    fun personKey(context: Context, contactId: Long): String = prefs(context).getString("call_style_person_$contactId", "") ?: ""

    fun setPersonKey(context: Context, contactId: Long, key: String) {
        val edit = prefs(context).edit()
        if (key.isEmpty()) edit.remove("call_style_person_$contactId") else edit.putString("call_style_person_$contactId", key)
        edit.apply()
    }

    /** The design for a caller: their own style, else unknown-caller style, else everyone's. */
    fun resolve(context: Context, contactId: Long?): Design {
        val personal = contactId?.let { design(context, personKey(context, it)) }
        val unknown = if (contactId == null) design(context, unknownKey(context)) else null
        return personal ?: unknown ?: design(context, defaultKey(context)) ?: preset(CONTEXT)
    }

    /** Every design the user can pick: built-ins then saved ones, as (key, label) pairs. */
    fun choices(context: Context): List<Pair<String, String>> =
        all.map { builtinKey(it) to context.getString(name(it)) } +
            customDesigns(context).map { it.id to it.name.ifEmpty { context.getString(R.string.omnio_call_style_custom) } }
}
