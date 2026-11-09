package org.fossify.phone.dialogs

import org.fossify.commons.helpers.OmnioColors
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.OmnioDisplay

/**
 * Omnio One: pick how the Contacts tab looks - style, columns, spacing and name format.
 * [callback] gets true when the surname-first setting changed (needs a full re-sort).
 */
class OmnioDisplayDialog(private val activity: SimpleActivity, private val callback: (surnameChanged: Boolean) -> Unit) {

    private val orange = OmnioColors.CONTACTS
    private val textColor = activity.getProperTextColor()
    private var style = OmnioDisplay.getStyle(activity)
    private val columnsByStyle = HashMap<Int, Int>()
    private lateinit var columnsRow: View
    private lateinit var columnsValue: TextView
    private lateinit var spacingSection: View

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        // Style
        content.addView(header(R.string.omnio_style))
        val styles = listOf(
            OmnioDisplay.STYLE_LIST to (R.string.omnio_style_list to R.string.omnio_style_list_desc),
            OmnioDisplay.STYLE_COMPACT to (R.string.omnio_style_compact to R.string.omnio_style_compact_desc),
            OmnioDisplay.STYLE_DETAILED to (R.string.omnio_style_detailed to R.string.omnio_style_detailed_desc),
            OmnioDisplay.STYLE_CARDS to (R.string.omnio_style_cards to R.string.omnio_style_cards_desc),
            OmnioDisplay.STYLE_CIRCLES to (R.string.omnio_style_circles to R.string.omnio_style_circles_desc),
            OmnioDisplay.STYLE_SQUARES to (R.string.omnio_style_squares to R.string.omnio_style_squares_desc),
            OmnioDisplay.STYLE_TILES to (R.string.omnio_style_tiles to R.string.omnio_style_tiles_desc),
            OmnioDisplay.STYLE_SPEED_DIAL to (R.string.omnio_style_speed_dial to R.string.omnio_style_speed_dial_desc),
        )
        val styleGroup = RadioGroup(activity)
        styles.forEach { (value, labels) ->
            val label = activity.getString(labels.first)
            val desc = activity.getString(labels.second)
            styleGroup.addView(radio("$label\n$desc", value == style, twoLine = true).apply {
                tag = value
            })
        }
        styleGroup.setOnCheckedChangeListener { group, checkedId ->
            style = group.findViewById<View>(checkedId)?.tag as? Int ?: style
            updateSections()
        }
        content.addView(styleGroup)

        // Columns (grid styles)
        columnsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(R.string.omnio_columns))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stepButton("−") { changeColumns(-1) })
                columnsValue = TextView(activity).apply {
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(44))
                }
                addView(columnsValue)
                addView(stepButton("+") { changeColumns(1) })
            })
        }
        content.addView(columnsRow)

        // Spacing (list styles)
        val density = OmnioDisplay.getDensity(activity)
        val spacingGroup = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(
            OmnioDisplay.DENSITY_COMPACT to R.string.omnio_spacing_tight,
            OmnioDisplay.DENSITY_NORMAL to R.string.omnio_spacing_normal,
            OmnioDisplay.DENSITY_COMFY to R.string.omnio_spacing_roomy
        ).forEach { (value, label) ->
            spacingGroup.addView(radio(activity.getString(label), value == density).apply { tag = value })
        }
        spacingSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(R.string.omnio_spacing))
            addView(spacingGroup)
        }
        content.addView(spacingSection)

        // Names
        content.addView(header(R.string.omnio_names))
        val currentNames = when {
            OmnioDisplay.isFirstNameOnly(activity) -> OmnioDisplay.NAMES_FIRST_ONLY
            activity.config.startNameWithSurname -> OmnioDisplay.NAMES_SURNAME_FIRST
            else -> OmnioDisplay.NAMES_FULL
        }
        val namesGroup = RadioGroup(activity)
        listOf(
            OmnioDisplay.NAMES_FULL to R.string.omnio_names_full,
            OmnioDisplay.NAMES_SURNAME_FIRST to R.string.omnio_names_surname,
            OmnioDisplay.NAMES_FIRST_ONLY to R.string.omnio_names_first
        ).forEach { (value, label) ->
            namesGroup.addView(radio(activity.getString(label), value == currentNames).apply { tag = value })
        }
        content.addView(namesGroup)

        updateSections()

        val scroll = ScrollView(activity).apply { addView(content) }
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                OmnioDisplay.setStyle(activity, style)
                columnsByStyle.forEach { (s, c) -> OmnioDisplay.setColumns(activity, s, c) }
                (spacingGroup.findViewById<View>(spacingGroup.checkedRadioButtonId)?.tag as? Int)?.let {
                    OmnioDisplay.setDensity(activity, it)
                }
                val names = namesGroup.findViewById<View>(namesGroup.checkedRadioButtonId)?.tag as? Int ?: currentNames
                OmnioDisplay.setFirstNameOnly(activity, names == OmnioDisplay.NAMES_FIRST_ONLY)
                val wantSurname = names == OmnioDisplay.NAMES_SURNAME_FIRST
                val surnameChanged = activity.config.startNameWithSurname != wantSurname
                if (surnameChanged) activity.config.startNameWithSurname = wantSurname
                callback(surnameChanged)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(scroll, this, R.string.omnio_display_style)
            }
    }

    private fun currentColumns() = columnsByStyle[style] ?: OmnioDisplay.getColumns(activity, style)

    private fun changeColumns(delta: Int) {
        columnsByStyle[style] = (currentColumns() + delta).coerceIn(2, 6)
        columnsValue.text = currentColumns().toString()
    }

    private fun updateSections() {
        val grid = OmnioDisplay.isGrid(style)
        columnsRow.visibility = if (grid) View.VISIBLE else View.GONE
        spacingSection.visibility = if (OmnioDisplay.isList(style)) View.VISIBLE else View.GONE
        columnsValue.text = currentColumns().toString()
    }

    private fun header(textRes: Int) = TextView(activity).apply {
        text = activity.getString(textRes)
        setTextColor(orange)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isAllCaps = true
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun radio(label: String, checked: Boolean, twoLine: Boolean = false) = RadioButton(activity).apply {
        id = View.generateViewId()
        text = label
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        buttonTintList = ColorStateList.valueOf(orange)
        isChecked = checked
        if (twoLine) {
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            // Second line (the description) smaller and dimmer
            val split = label.indexOf('\n')
            if (split > 0) {
                val span = android.text.SpannableString(label)
                span.setSpan(android.text.style.RelativeSizeSpan(0.82f), split, label.length, 0)
                span.setSpan(android.text.style.ForegroundColorSpan((textColor and 0x00FFFFFF) or 0x99000000.toInt()), split, label.length, 0)
                text = span
            }
        }
        layoutParams = RadioGroup.LayoutParams(
            if (twoLine) RadioGroup.LayoutParams.MATCH_PARENT else RadioGroup.LayoutParams.WRAP_CONTENT,
            RadioGroup.LayoutParams.WRAP_CONTENT
        ).apply { if (!twoLine) marginEnd = dp(8) }
    }

    private fun stepButton(label: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        setTextColor(OmnioColors.ON_ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        background = activity.getDrawable(R.drawable.omnio_circle_bg)
        backgroundTintList = ColorStateList.valueOf(orange)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }
}
