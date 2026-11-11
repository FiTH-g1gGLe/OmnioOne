package com.omnioone.app.messaging

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.commons.helpers.OmnioColors
import java.util.Calendar

/** Omnio One Messages: one row per conversation, pinned first, unread in bold with a count. */
class OmnioConversationsAdapter(
    private val onOpen: (OmnioMessageStore.Conversation) -> Unit,
    private val onPhoto: (OmnioMessageStore.Conversation) -> Unit,
    private val onMenu: (OmnioMessageStore.Conversation) -> Unit,
    private val onArchived: () -> Unit,
    private val onBin: () -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val label: String) : Item()
        data class Row(val conversation: OmnioMessageStore.Conversation) : Item()
        data class ArchivedRow(val count: Int, val label: String, val bin: Boolean = false) : Item()
    }

    var items: List<Item> = emptyList()
        private set
    var textColor = OmnioColors.TEXT
    private var highlight = ""

    fun submit(list: List<Item>, highlight: String) {
        this.highlight = highlight
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = when (items[position]) {
        is Item.Header -> 0
        is Item.Row -> 1
        is Item.ArchivedRow -> 2
    }

    private fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        0 -> object : RecyclerView.ViewHolder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.05f
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }) {}
        2 -> object : RecyclerView.ViewHolder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }) {}
        else -> RowHolder(LinearLayout(parent.context))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder.itemView as TextView).apply {
                text = item.label.uppercase()
                setTextColor(textColor)
                alpha = 0.6f
            }
            is Item.ArchivedRow -> (holder.itemView as TextView).apply {
                text = item.label
                setTextColor(OmnioColors.MESSAGES)
                val ripple = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setTextColor(if (item.bin) OmnioColors.TEXT_MUTED else OmnioColors.MESSAGES)
                setOnClickListener { if (item.bin) onBin() else onArchived() }
            }
            is Item.Row -> (holder as RowHolder).bind(item.conversation)
        }
    }

    inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val avatar = ImageView(row.context)
        private val title = TextView(row.context)
        private val time = TextView(row.context)
        private val snippet = TextView(row.context)
        private val badge = TextView(row.context)

        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = row.dp(76)
            row.setPadding(row.dp(18), row.dp(10), row.dp(18), row.dp(10))
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val ripple = TypedValue()
            row.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            row.setBackgroundResource(ripple.resourceId)

            avatar.layoutParams = LinearLayout.LayoutParams(row.dp(52), row.dp(52))
            row.addView(avatar)

            val texts = LinearLayout(row.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(row.dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val top = LinearLayout(row.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            title.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            top.addView(title)
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            time.setPadding(row.dp(8), 0, 0, 0)
            top.addView(time)
            texts.addView(top)

            val bottom = LinearLayout(row.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, row.dp(3), 0, 0)
            }
            snippet.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            bottom.addView(snippet)
            badge.apply {
                setTextColor(OmnioColors.ON_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = row.dp(22)
                setPadding(row.dp(7), row.dp(2), row.dp(7), row.dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = row.dp(11).toFloat()
                    setColor(OmnioColors.MESSAGES)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = row.dp(8) }
            }
            bottom.addView(badge)
            texts.addView(bottom)
            row.addView(texts)
        }

        fun bind(conversation: OmnioMessageStore.Conversation) {
            val unread = conversation.unread > 0
            OmnioDisplay.loadAvatar(avatar, conversation.photo, conversation.title, row.dp(26))
            avatar.setOnClickListener { onPhoto(conversation) }

            val name = (if (conversation.isPinned) "📌 " else "") + conversation.title
            title.text = highlighted(name)
            title.setTextColor(textColor)
            title.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)

            time.text = whenText(conversation.date, row)
            time.setTextColor(if (unread) OmnioColors.MESSAGES else textColor)
            time.alpha = if (unread) 1f else 0.6f

            snippet.text = highlighted(OmnioMessageText.preview(conversation.snippet, conversation.hasAttachment))
            snippet.setTextColor(textColor)
            snippet.alpha = if (unread) 0.95f else 0.6f
            snippet.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)

            badge.visibility = if (unread) View.VISIBLE else View.GONE
            badge.text = if (conversation.unread > 99) "99+" else conversation.unread.toString()

            row.setOnClickListener { onOpen(conversation) }
            row.setOnLongClickListener { onMenu(conversation); true }
        }

        private fun highlighted(text: String): CharSequence {
            if (highlight.isEmpty()) return text
            val start = text.indexOf(highlight, ignoreCase = true)
            if (start < 0) return text
            return SpannableString(text).apply {
                setSpan(ForegroundColorSpan(OmnioColors.MESSAGES), start, start + highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    companion object {
        /** 14:32 today, a weekday this week, otherwise a short date. */
        fun whenText(time: Long, view: View): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = time }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            return when {
                sameDay -> android.text.format.DateFormat.getTimeFormat(view.context).format(time)
                now.timeInMillis - time < 6L * 24 * 3600 * 1000 -> then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault()).orEmpty()
                else -> android.text.format.DateFormat.format(
                    android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "d MMM" else "d MMM yy"),
                    then
                ).toString()
            }
        }
    }
}

/** Turns raw message text into a tidy one-line preview. */
object OmnioMessageText {
    fun preview(text: String, hasAttachment: Boolean): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return when {
            clean.isNotEmpty() -> clean
            hasAttachment -> "📷 Photo"
            else -> ""
        }
    }
}
