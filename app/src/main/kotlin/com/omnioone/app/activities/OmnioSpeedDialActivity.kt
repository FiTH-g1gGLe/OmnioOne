package com.omnioone.app.activities

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.google.gson.Gson
import com.omnioone.app.R
import com.omnioone.app.phone.config
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.phone.OmnioSpeedDial
import com.omnioone.app.core.baseConfig
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.ensureBackgroundThread

/** Omnio One speed dial: put someone on keys 1–9, then hold that key on the keypad to call them. */
class OmnioSpeedDialActivity : OmnioActivity() {

    private lateinit var screen: OmnioScreen
    private var people = listOf<OmnioPeople.Person>()
    private var dials = ArrayList<OmnioSpeedDial>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = OmnioScreen(this, getString(R.string.omnio_speed_dial))
        setContentView(screen.root)
        dials = config.speedDials()
        build()
        val ignored = baseConfig.ignoredContactSources.toSet()
        ensureBackgroundThread {
            val loaded = OmnioPeople.sort(OmnioPeople.load(this, ignored), config.sorting)
            runOnUiThread { people = loaded }
        }
    }

    private fun save() {
        config.saveSpeedDials(dials)
    }

    private fun build() {
        screen.content.removeAllViews()
        screen.note(getString(R.string.omnio_speed_dial_help))
        val rows = (1..9).map { key ->
            val dial = dials.firstOrNull { it.id == key }
            val set = dial?.isValid() == true
            val subtitle = when {
                set -> dial!!.number
                key == 1 -> getString(R.string.omnio_speed_dial_voicemail)
                else -> getString(R.string.omnio_speed_dial_empty)
            }
            screen.row(
                title = if (set) dial!!.displayName.ifEmpty { dial.number } else getString(R.string.omnio_speed_dial_add),
                subtitle = subtitle,
                leading = keyBadge(key, set),
                trailing = if (set) screen.iconButton(com.omnioone.app.R.drawable.ic_cross_vector, OmnioColors.TEXT_MUTED, getString(R.string.omnio_speed_dial_remove)) {
                    dial!!.apply { number = ""; displayName = ""; type = null; label = null }
                    save()
                    build()
                } else null,
                onClick = { pick(key) }
            )
        }
        screen.card(rows)
    }

    private fun keyBadge(key: Int, set: Boolean) = TextView(this).apply {
        text = key.toString()
        setTextColor(if (set) OmnioColors.ON_ACCENT else OmnioColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (set) OmnioColors.CALLS else OmnioColors.CARD_SELECTED)
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(screen.dp(40), screen.dp(40))
    }

    private fun pick(key: Int) {
        if (people.isEmpty()) return
        OmnioSheet.list(this, getString(R.string.omnio_speed_dial_pick, key), null, people.map { OmnioSheet.Item(it.name, trailing = it.mainNumber, trailingColor = OmnioColors.TEXT_MUTED) }, searchHint = getString(R.string.omnio_search_people_hint)) { picked ->
            val person = people[picked]
            if (person.numbers.size == 1) {
                set(key, person, person.numbers.first())
            } else {
                OmnioSheet.list(this, person.name, null, person.numbers.map { OmnioSheet.Item(it.value, trailing = it.label, trailingColor = OmnioColors.TEXT_MUTED) }) { which ->
                    set(key, person, person.numbers[which])
                }
            }
        }
    }

    private fun set(key: Int, person: OmnioPeople.Person, number: OmnioPeople.Number) {
        val dial = dials.firstOrNull { it.id == key } ?: OmnioSpeedDial(key, "", "").also { dials.add(it) }
        dial.number = number.value
        dial.displayName = person.name
        dial.type = null
        dial.label = null
        save()
        build()
    }
}
