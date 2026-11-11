package com.omnioone.app.adapters

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.helpers.OmnioCallLog
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.core.OmnioColors
import java.util.Calendar

/** Omnio One Recents list: day headers and one row per person, newest first. */
class OmnioRecentsAdapter(
    private val onCall: (OmnioCallLog.Entry) -> Unit,
    private val onText: (OmnioCallLog.Entry) -> Unit,
    private val onOpen: (OmnioCallLog.Entry) -> Unit,
    private val onMore: (OmnioCallLog.Entry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val label: String) : Item()
        data class Row(val entry: OmnioCallLog.Entry) : Item()
    }

    var items: List<Item> = emptyList()
        private set
    var textColor = OmnioColors.TEXT
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    private var highlight = ""

    fun submit(entries: List<OmnioCallLog.Entry>, labelFor: (Long) -> String, highlight: String = "") {
        this.highlight = highlight
        val list = ArrayList<Item>()
        var lastDay = ""
        entries.forEach { entry ->
            val day = labelFor(entry.latest.date)
            if (day != lastDay) {
                list += Item.Header(day)
                lastDay = day
            }
            list += Item.Row(entry)
        }
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1
    override fun getItemId(position: Int) = when (val item = items[position]) {
        is Item.Header -> -position.toLong() - 1
        is Item.Row -> item.entry.key
    }

    private fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            HeaderHolder(TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.05f
                setPadding(dp(20), dp(18), dp(20), dp(6))
            })
        } else {
            RowHolder(LinearLayout(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderHolder).bind(item.label)
            is Item.Row -> (holder as RowHolder).bind(item.entry)
        }
    }

    private inner class HeaderHolder(val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(label: String) {
            view.text = label.uppercase()
            view.setTextColor(textColor)
            view.alpha = 0.6f
        }
    }

    inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val avatar = ImageView(row.context)
        private val name = TextView(row.context)
        private val typeIcon = ImageView(row.context)
        private val details = TextView(row.context)
        private val textButton = ImageView(row.context)

        init {
            row.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(70)
                setPadding(dp(16), dp(8), dp(8), dp(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val ripple = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
            }
            avatar.layoutParams = LinearLayout.LayoutParams(row.dp(48), row.dp(48))
            row.addView(avatar)

            val texts = LinearLayout(row.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(row.dp(14), 0, row.dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            name.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            texts.addView(name)
            val line = LinearLayout(row.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, row.dp(3), 0, 0)
            }
            typeIcon.layoutParams = LinearLayout.LayoutParams(row.dp(16), row.dp(16)).apply { marginEnd = row.dp(5) }
            line.addView(typeIcon)
            details.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            line.addView(details)
            texts.addView(line)
            row.addView(texts)

            textButton.apply {
                setImageResource(R.drawable.ic_omnio_chat)
                imageTintList = ColorStateList.valueOf(OmnioColors.MESSAGES)
                setPadding(row.dp(11), row.dp(11), row.dp(11), row.dp(11))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x2622C55E)
                }
                layoutParams = LinearLayout.LayoutParams(row.dp(42), row.dp(42)).apply { marginEnd = row.dp(8) }
                contentDescription = context.getString(R.string.omnio_text)
            }
            row.addView(textButton)
        }

        fun bind(entry: OmnioCallLog.Entry) {
            val context = row.context
            OmnioDisplay.loadAvatar(avatar, entry.person?.photoUri.orEmpty(), entry.name, row.dp(24))
            avatar.setOnClickListener { onOpen(entry) }

            val latest = entry.latest
            val missed = latest.isMissed
            val title = entry.name + if (entry.calls.size > 1) "  (${entry.calls.size})" else ""
            val spannable = SpannableString(title)
            if (highlight.isNotEmpty()) {
                val start = title.indexOf(highlight, ignoreCase = true)
                if (start >= 0) {
                    spannable.setSpan(ForegroundColorSpan(OmnioColors.CALLS), start, start + highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), start, start + highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            name.text = spannable
            name.setTextColor(if (missed) OmnioColors.MISSED_SOFT else textColor)

            val (icon, colour) = when {
                latest.isMissed -> R.drawable.ic_call_missed_vector to OmnioColors.MISSED
                latest.isDeclined -> R.drawable.ic_call_missed_vector to OmnioColors.TEXT_MUTED
                latest.isOutgoing -> R.drawable.ic_call_made_vector to OmnioColors.CALLS
                else -> R.drawable.ic_call_received_vector to OmnioColors.MESSAGES
            }
            typeIcon.setImageResource(icon)
            typeIcon.imageTintList = ColorStateList.valueOf(colour)

            val parts = ArrayList<String>()
            when {
                entry.isPrivate -> parts += context.getString(R.string.omnio_private_number)
                entry.person?.label?.isNotEmpty() == true -> parts += entry.person.label
                !entry.isSaved -> parts += entry.number
            }
            parts += android.text.format.DateFormat.getTimeFormat(context).format(latest.date)
            if (latest.duration > 0 && !latest.isMissed) parts += duration(latest.duration)
            details.text = parts.joinToString("  ·  ")
            details.setTextColor(textColor)
            details.alpha = 0.65f

            textButton.visibility = if (entry.isPrivate) View.INVISIBLE else View.VISIBLE
            textButton.setOnClickListener { onText(entry) }
            row.setOnClickListener { onCall(entry) }
            row.setOnLongClickListener { onMore(entry); true }
        }
    }

    companion object {
        fun duration(seconds: Long): String = when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }

        fun sameDay(a: Long, b: Long): Boolean {
            val first = Calendar.getInstance().apply { timeInMillis = a }
            val second = Calendar.getInstance().apply { timeInMillis = b }
            return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
        }
    }
}
