package com.omnioone.app.adapters

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioAppTags
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.commons.helpers.OmnioColors

/**
 * Omnio One contacts list: draws every contact display style (list, compact, detailed, cards,
 * circles, squares, tiles, speed dial), with letter headers for the list styles.
 */
class OmnioContactsAdapter(
    private val style: Int,
    private val onClick: (OmnioPeople.Person) -> Unit,
    private val onPhoto: (OmnioPeople.Person) -> Unit,
    private val onLongClick: (OmnioPeople.Person, View) -> Unit,
    private val onCall: (OmnioPeople.Person) -> Unit = {},
    private val onText: (OmnioPeople.Person) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val letter: String) : Item()
        data class Row(val person: OmnioPeople.Person) : Item()
    }

    var items: List<Item> = emptyList()
        private set
    var textColor = OmnioColors.TEXT
    var appTags: Map<Int, List<OmnioAppTags.Badge>> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var density = OmnioDisplay.DENSITY_NORMAL
    var firstNameOnly = false
    var surnameFirst = false
    private var highlight = ""

    val isGrid get() = OmnioDisplay.isGrid(style)

    /** Shows [people] in order; letter headers are added for list styles when not searching. */
    fun submit(people: List<OmnioPeople.Person>, letterOf: (OmnioPeople.Person) -> String, showHeaders: Boolean, highlight: String = "") {
        this.highlight = highlight
        val list = ArrayList<Item>()
        var last = ""
        people.forEach { person ->
            if (showHeaders && !isGrid && highlight.isEmpty()) {
                val letter = letterOf(person)
                if (letter != last) {
                    list += Item.Header(letter)
                    last = letter
                }
            }
            list += Item.Row(person)
        }
        items = list
        notifyDataSetChanged()
    }

    /** Moves a row (favourites reordering). */
    fun move(from: Int, to: Int) {
        val list = items.toMutableList()
        list.add(to, list.removeAt(from))
        items = list
        notifyItemMoved(from, to)
    }

    fun people() = items.filterIsInstance<Item.Row>().map { it.person }

    /** Where a letter starts in the list, for the A–Z strip. */
    fun positionOf(letter: String, letterOf: (OmnioPeople.Person) -> String): Int =
        items.indexOfFirst { (it is Item.Header && it.letter == letter) || (it is Item.Row && letterOf(it.person) == letter) }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

    private fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = if (viewType == 0) {
        HeaderHolder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(14), dp(22), dp(4))
        })
    } else if (isGrid) {
        GridHolder(FrameLayout(parent.context))
    } else {
        RowHolder(LinearLayout(parent.context))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderHolder).bind(item.letter)
            is Item.Row -> when (holder) {
                is RowHolder -> holder.bind(item.person)
                is GridHolder -> holder.bind(item.person)
            }
        }
    }

    private fun name(person: OmnioPeople.Person): CharSequence {
        val full = OmnioPeople.displayName(person, surnameFirst)
        val text = if (firstNameOnly && highlight.isEmpty()) full.trim().substringBefore(" ") else full
        if (highlight.isEmpty()) return text
        val start = OmnioPeople.plain(text).indexOf(OmnioPeople.plain(highlight), ignoreCase = true)
        if (start < 0 || start + highlight.length > text.length) return text
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(OmnioColors.CALLS), start, start + highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun ripple(view: View) {
        val value = TypedValue()
        view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        view.setBackgroundResource(value.resourceId)
    }

    private inner class HeaderHolder(val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(letter: String) {
            view.text = letter
            view.setTextColor(OmnioColors.CONTACTS)
        }
    }

    /** List, compact, detailed and card styles. */
    inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val avatar = ImageView(row.context)
        private val nameView = TextView(row.context)
        private val numberView = TextView(row.context)
        private val icons = LinearLayout(row.context)
        private val callButton = ImageView(row.context)
        private val textButton = ImageView(row.context)
        private val compact = style == OmnioDisplay.STYLE_COMPACT
        private val card = style == OmnioDisplay.STYLE_CARDS

        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (card) setMargins(row.dp(14), row.dp(5), row.dp(14), row.dp(5))
            }
            val size = row.dp(if (compact) 36 else if (card) 52 else 48)
            avatar.layoutParams = LinearLayout.LayoutParams(size, size)
            row.addView(avatar)

            val texts = LinearLayout(row.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(row.dp(14), 0, row.dp(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15.5f else if (card) 17f else 16.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            texts.addView(nameView)
            numberView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 1
                alpha = 0.7f
                setPadding(0, row.dp(2), 0, 0)
            }
            texts.addView(numberView)
            icons.apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, row.dp(5), 0, 0)
            }
            texts.addView(icons)
            row.addView(texts)

            if (card) {
                fun roundButton(view: ImageView, icon: Int, colour: Int) = view.apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(colour)
                    setPadding(row.dp(11), row.dp(11), row.dp(11), row.dp(11))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor((colour and 0x00FFFFFF) or 0x26000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(row.dp(42), row.dp(42)).apply { marginStart = row.dp(8) }
                }
                row.addView(roundButton(textButton, R.drawable.ic_omnio_chat, OmnioColors.MESSAGES))
                row.addView(roundButton(callButton, R.drawable.ic_call_accept, OmnioColors.CALLS))
            }
        }

        fun bind(person: OmnioPeople.Person) {
            val vertical = when {
                compact -> row.dp(4)
                card -> row.dp(12)
                density == OmnioDisplay.DENSITY_COMPACT -> row.dp(4)
                density == OmnioDisplay.DENSITY_COMFY -> row.dp(14)
                else -> row.dp(9)
            }
            row.setPadding(row.dp(if (card) 14 else 20), vertical, row.dp(if (card) 12 else 20), vertical)
            if (card) {
                row.background = GradientDrawable().apply {
                    cornerRadius = row.dp(20).toFloat()
                    setColor(OmnioColors.CARD_DEEP)
                }
                row.foreground = null
            } else {
                ripple(row)
            }

            OmnioDisplay.loadAvatar(avatar, person.photo, person.name, avatar.layoutParams.width / 2)
            avatar.setOnClickListener { onPhoto(person) }

            nameView.text = name(person)
            nameView.setTextColor(textColor)
            nameView.setTypeface(null, if (compact) Typeface.NORMAL else Typeface.BOLD)

            val showNumber = style == OmnioDisplay.STYLE_DETAILED || card
            numberView.visibility = if (showNumber) View.VISIBLE else View.GONE
            numberView.text = person.mainNumber
            numberView.setTextColor(textColor)

            icons.removeAllViews()
            val badges = if (compact) emptyList() else appTags[person.contactId].orEmpty()
            icons.visibility = if (badges.isEmpty()) View.GONE else View.VISIBLE
            val iconSize = row.dp(16)
            badges.forEach { badge ->
                icons.addView(ImageView(row.context).apply {
                    setImageDrawable(OmnioAppTags.icon(row.context, badge))
                    contentDescription = badge.name
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = iconSize / 3 }
                })
            }

            if (card) {
                callButton.setOnClickListener { onCall(person) }
                textButton.setOnClickListener { onText(person) }
            }
            row.setOnClickListener { onClick(person) }
            row.setOnLongClickListener { onLongClick(person, row); true }
        }
    }

    /** Circles, squares, tiles and speed dial. */
    inner class GridHolder(val cell: FrameLayout) : RecyclerView.ViewHolder(cell) {
        private val tiles = style == OmnioDisplay.STYLE_TILES
        private val avatar = ImageView(cell.context)
        private val nameView = TextView(cell.context)

        init {
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cell.setPadding(cell.dp(6), cell.dp(8), cell.dp(6), cell.dp(8))
            ripple(cell)
            if (tiles) {
                val frame = FrameLayout(cell.context).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                avatar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                avatar.adjustViewBounds = true
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                frame.addView(avatar)
                frame.addView(View(cell.context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000)).apply {
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cell.dp(18).toFloat(), cell.dp(18).toFloat(), cell.dp(18).toFloat(), cell.dp(18).toFloat())
                    }
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell.dp(56), Gravity.BOTTOM)
                })
                nameView.apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(cell.dp(12), 0, cell.dp(12), cell.dp(10))
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                }
                frame.addView(nameView)
                cell.addView(frame)
            } else {
                val column = LinearLayout(cell.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                avatar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = cell.dp(4)
                    marginEnd = cell.dp(4)
                }
                avatar.adjustViewBounds = true
                column.addView(avatar)
                nameView.apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (style == OmnioDisplay.STYLE_SPEED_DIAL) 14f else 13f)
                    setPadding(0, cell.dp(6), 0, 0)
                }
                column.addView(nameView)
                cell.addView(column)
            }
        }

        fun bind(person: OmnioPeople.Person) {
            // square image whatever the column width
            avatar.post {
                val width = avatar.width
                if (width > 0 && avatar.layoutParams.height != width) {
                    avatar.layoutParams = avatar.layoutParams.apply { height = width }
                }
            }
            val radius = when (style) {
                OmnioDisplay.STYLE_SQUARES -> cell.dp(16)
                OmnioDisplay.STYLE_TILES -> cell.dp(18)
                else -> cell.dp(200)
            }
            OmnioDisplay.loadAvatar(avatar, person.photo, person.name, radius)
            nameView.text = name(person)
            if (!tiles) nameView.setTextColor(textColor)
            cell.setOnClickListener { onClick(person) }
            cell.setOnLongClickListener { onLongClick(person, cell); true }
        }
    }
}
