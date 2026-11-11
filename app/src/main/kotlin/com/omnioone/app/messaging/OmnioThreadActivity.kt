package com.omnioone.app.messaging

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioContactActivity
import com.omnioone.app.activities.OmnioEditContactActivity
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.extensions.startCallWithConfirmationCheck
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.helpers.OmnioColors
import com.omnioone.commons.helpers.OmnioContactData
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.extensions.deleteMessage
import com.omnioone.messages.extensions.deleteSmsDraft
import com.omnioone.messages.extensions.getSmsDraft
import com.omnioone.messages.extensions.markThreadMessagesRead
import com.omnioone.messages.extensions.saveSmsDraft
import com.omnioone.messages.helpers.OmnioEmail
import com.omnioone.messages.helpers.OmnioReactions
import com.omnioone.messages.helpers.THREAD_ID
import com.omnioone.messages.helpers.THREAD_TITLE
import com.omnioone.messages.messaging.sendMessageCompat
import java.util.Calendar

/**
 * Omnio One conversation screen (Messages rewrite, stage 3): message bubbles with day dividers,
 * reactions shown on the message they're for, email chips, photos, and a reply box.
 * Sending still goes through the current sending code until stage 4.
 */
class OmnioThreadActivity : SimpleActivity() {

    companion object {
        private val QUICK_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "‼️", "❓", "👎")
    }

    private var threadId = 0L
    private var conversation: OmnioMessageStore.Conversation? = null
    private var messages = listOf<OmnioMessageStore.Message>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var avatar: ImageView
    private lateinit var callButton: ImageView
    private val adapter = BubbleAdapter()
    private var offeredEmail: String? = null
    private var loading = false

    private val reloadSoon = Runnable { load() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadSoon)
            handler.postDelayed(reloadSoon, 300)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra(THREAD_ID, 0L)
        if (threadId == 0L) {
            finish()
            return
        }
        setContentView(buildScreen())
        titleView.text = intent.getStringExtra(THREAD_TITLE).orEmpty()
        input.setText(getSmsDraft(threadId))
        contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
        load()
    }

    override fun onResume() {
        super.onResume()
        markRead()
    }

    override fun onPause() {
        val text = input.text.toString()
        ensureBackgroundThread {
            if (text.isNotBlank()) saveSmsDraft(text, threadId) else deleteSmsDraft(threadId)
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (ignored: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun markRead() {
        ensureBackgroundThread {
            try {
                markThreadMessagesRead(threadId)
            } catch (ignored: Exception) {
            }
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(threadId.hashCode())
        } catch (ignored: Exception) {
        }
    }

    // ---- loading ----

    private fun load() {
        if (loading) return
        loading = true
        ensureBackgroundThread {
            val info = OmnioMessageStore.conversations(this, onlyThread = threadId).firstOrNull()
            val loaded = OmnioMessageStore.messages(this, threadId, limit = 400)
            runOnUiThread {
                loading = false
                if (isFinishing) return@runOnUiThread
                val atBottom = !list.canScrollVertically(1)
                conversation = info
                messages = loaded
                showHeader()
                adapter.submit(loaded, info)
                if (atBottom || adapter.itemCount <= 1) list.scrollToPosition(adapter.itemCount - 1)
                offerEmailIfAsked(loaded)
            }
        }
    }

    private fun showHeader() {
        val info = conversation ?: return
        titleView.text = info.title
        val person = info.people.singleOrNull()
        subtitleView.text = when {
            info.isGroup -> getString(R.string.omnio_thread_people, info.people.size)
            person?.name != null -> person.address
            else -> ""
        }
        subtitleView.visibility = if (subtitleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        OmnioDisplay.loadAvatar(avatar, info.photo, info.title, dp(20))
        callButton.visibility = if (person != null) View.VISIBLE else View.GONE
    }

    /** They replied with an email after you asked for it: offer to save it (once per address). */
    private fun offerEmailIfAsked(loaded: List<OmnioMessageStore.Message>) {
        val number = conversation?.people?.singleOrNull()?.address ?: return
        val asked = OmnioEmail.askedAt(this, number) ?: return
        val reply = loaded.lastOrNull { it.incoming && it.date >= asked } ?: return
        val email = OmnioEmail.findEmail(reply.body) ?: return
        if (email == offeredEmail) return
        offeredEmail = email
        OmnioEmail.offerFromReply(this, email, number)
    }

    // ---- actions ----

    private fun openContact() {
        val person = conversation?.people?.singleOrNull() ?: return
        val contactId = person.contactId
        if (contactId == null) {
            startActivity(Intent(this, OmnioEditContactActivity::class.java).putExtra(OmnioEditContactActivity.EXTRA_NUMBER, person.address))
            return
        }
        ensureBackgroundThread {
            val rawId = OmnioContactData.firstRawId(this, contactId) ?: return@ensureBackgroundThread
            runOnUiThread { startActivity(Intent(this, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, rawId.toInt())) }
        }
    }

    private fun addresses() = conversation?.people?.map { it.address }.orEmpty()

    private fun send(text: String, retryOf: Long? = null) {
        val to = addresses()
        if (text.isBlank() || to.isEmpty()) return
        ensureBackgroundThread {
            try {
                sendMessageCompat(text, to, null, emptyList(), retryOf)
                deleteSmsDraft(threadId)
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.omnio_thread_send_failed) }
            }
            handler.postDelayed(reloadSoon, 400)
        }
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        send(text)
    }

    private fun showMessageMenu(message: OmnioMessageStore.Message, reactions: List<OmnioReactions.Reaction> = emptyList()) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (message.body.isNotBlank()) {
            items += getString(R.string.omnio_thread_copy) to {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", message.body))
                toast(R.string.omnio_copied)
            }
            items += getString(R.string.omnio_thread_forward) to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message.body), getString(R.string.omnio_thread_forward)))
            }
        }
        if (message.failed) items += getString(R.string.omnio_thread_retry) to { send(message.body, message.id) }
        items += getString(R.string.omnio_thread_delete) to {
            OmnioSheet.confirm(this, getString(R.string.omnio_thread_delete_question), null, positive = getString(com.omnioone.commons.R.string.delete), mainColor = OmnioColors.MISSED) {
                ensureBackgroundThread {
                    deleteMessage(message.id, message.isMms)
                    handler.post { load() }
                }
            }
        }
        val time = android.text.format.DateFormat.getMediumDateFormat(this).format(message.date) + " " + android.text.format.DateFormat.getTimeFormat(this).format(message.date)
        val top: ((() -> Unit) -> View)? = if (message.body.isNotBlank()) { dismiss -> emojiRow(message, reactions, dismiss) } else null
        OmnioSheet.list(this, getString(if (message.incoming) R.string.omnio_thread_received else R.string.omnio_thread_sent), time, items.map { OmnioSheet.Item(it.first) }, top) { picked ->
            items[picked].second()
        }
    }

    /** The emoji row at the top of the long-press sheet. Your own reactions are ringed: tap one again to take it back. */
    private fun emojiRow(message: OmnioMessageStore.Message, reactions: List<OmnioReactions.Reaction>, dismiss: () -> Unit): View {
        val mine = reactions.filter { it.mine }.map { it.emoji }.toSet()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(OmnioColors.CARD)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            QUICK_EMOJIS.forEach { emoji ->
                addView(TextView(this@OmnioThreadActivity).apply {
                    text = emoji
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    if (emoji in mine) background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(OmnioColors.CARD_SELECTED)
                        setStroke(dp(2), OmnioColors.MESSAGES)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                    setOnClickListener {
                        dismiss()
                        send(if (emoji in mine) OmnioReactions.removalText(emoji, message.body) else OmnioReactions.reactionText(emoji, message.body))
                    }
                })
            }
        }
    }

    private fun openPart(part: OmnioMessageStore.Part) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(part.uri, part.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(com.omnioone.commons.R.string.no_app_found)
        }
    }

    // ---- screen ----

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        // top bar
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(8), dp(6))
            addView(icon(com.omnioone.commons.R.drawable.ic_arrow_left_vector, OmnioColors.TEXT) { finish() })
            avatar = ImageView(this@OmnioThreadActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { openContact() }
            }
            addView(avatar)
            addView(LinearLayout(this@OmnioThreadActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openContact() }
                titleView = TextView(this@OmnioThreadActivity).apply {
                    setTextColor(OmnioColors.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                subtitleView = TextView(this@OmnioThreadActivity).apply {
                    setTextColor(OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                }
                addView(titleView)
                addView(subtitleView)
            })
            callButton = icon(com.omnioone.commons.R.drawable.ic_phone_vector, OmnioColors.CALLS) {
                conversation?.people?.singleOrNull()?.let { startCallWithConfirmationCheck(it.address, it.label) }
            }
            addView(callButton)
        })

        // messages
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OmnioThreadActivity).apply { stackFromEnd = true }
            adapter = this@OmnioThreadActivity.adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(list)

        // reply box
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            addView(icon(R.drawable.ic_attach_file_vector, OmnioColors.TEXT_MUTED) {
                // photos and files: the current conversation screen handles them until stage 4
                startActivity(Intent(this@OmnioThreadActivity, com.omnioone.messages.activities.ThreadActivity::class.java).putExtra(THREAD_ID, threadId))
            })
            input = EditText(this@OmnioThreadActivity).apply {
                hint = getString(R.string.omnio_thread_type)
                setTextColor(OmnioColors.TEXT)
                setHintTextColor(OmnioColors.TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 5
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(OmnioColors.CARD)
                }
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(8) }
            }
            addView(input)
            addView(ImageView(this@OmnioThreadActivity).apply {
                setImageResource(com.omnioone.commons.R.drawable.ic_send_vector)
                imageTintList = ColorStateList.valueOf(OmnioColors.ON_ACCENT)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(OmnioColors.MESSAGES)
                }
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                contentDescription = getString(R.string.omnio_thread_send)
                setOnClickListener { sendTyped() }
            })
        })
        return root
    }

    private fun icon(res: Int, colour: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(colour)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        background = this@OmnioThreadActivity.getDrawable(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    // ---- bubbles ----

    private sealed class Row {
        data class Day(val label: String) : Row()
        data class Bubble(val message: OmnioMessageStore.Message, val reactions: List<OmnioReactions.Reaction>, val showTime: Boolean, val sender: String?) : Row()
    }

    private inner class BubbleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows = listOf<Row>()

        fun submit(loaded: List<OmnioMessageStore.Message>, info: OmnioMessageStore.Conversation?) {
            val (hidden, reactions) = OmnioReactions.processThread(loaded.map { OmnioReactions.Entry(it.key, it.body, it.isMms && it.body.isEmpty(), !it.incoming) })
            val visible = loaded.filter { it.key !in hidden }
            val names = info?.people?.associate { it.address.filter(Char::isDigit).takeLast(9) to it.label }.orEmpty()
            val out = ArrayList<Row>()
            var lastDay = ""
            visible.forEachIndexed { index, message ->
                val day = dayLabel(message.date)
                if (day != lastDay) {
                    out += Row.Day(day)
                    lastDay = day
                }
                val next = visible.getOrNull(index + 1)
                val showTime = next == null || next.incoming != message.incoming || next.date - message.date > 5 * 60 * 1000 || dayLabel(next.date) != day
                val previous = visible.getOrNull(index - 1)
                val sender = if (info?.isGroup == true && message.incoming && (previous == null || !previous.incoming || previous.address != message.address)) {
                    names[message.address.filter(Char::isDigit).takeLast(9)] ?: message.address
                } else null
                out += Row.Bubble(message, reactions[message.key].orEmpty(), showTime, sender)
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Day) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setTextColor(OmnioColors.TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(8))
            }) {} else BubbleHolder(LinearLayout(parent.context))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Day -> (holder.itemView as TextView).text = row.label
                is Row.Bubble -> (holder as BubbleHolder).bind(row)
            }
        }
    }

    private inner class BubbleHolder(val outer: LinearLayout) : RecyclerView.ViewHolder(outer) {
        private val column = LinearLayout(outer.context)

        init {
            outer.orientation = LinearLayout.VERTICAL
            outer.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            outer.setPadding(dp(12), dp(2), dp(12), dp(2))
            column.orientation = LinearLayout.VERTICAL
            outer.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun wrap(topMargin: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }

        fun bind(row: Row.Bubble) {
            val message = row.message
            val mine = !message.incoming
            column.removeAllViews()
            (column.layoutParams as LinearLayout.LayoutParams).gravity = if (mine) Gravity.END else Gravity.START
            column.gravity = if (mine) Gravity.END else Gravity.START
            val bubbleWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()

            row.sender?.let { name ->
                column.addView(TextView(outer.context).apply {
                    text = name
                    setTextColor(OmnioColors.MESSAGES_SOFT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(dp(12), dp(6), 0, dp(2))
                }, wrap())
            }

            // photos, videos and other attachments
            message.parts.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }.forEach { part ->
                column.addView(ImageView(outer.context).apply {
                    adjustViewBounds = true
                    setMaxWidth((bubbleWidth * 0.85f).toInt())
                    maxHeight = dp(320)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) }
                    Glide.with(this).load(part.uri).transform(RoundedCorners(dp(16))).override(dp(260)).into(this)
                    if (part.mime.startsWith("video/")) foreground = this@OmnioThreadActivity.getDrawable(android.R.drawable.ic_media_play)
                    setOnClickListener { openPart(part) }
                    setOnLongClickListener { showMessageMenu(message, row.reactions); true }
                })
            }
            message.parts.filter { !it.mime.startsWith("image/") && !it.mime.startsWith("video/") && it.mime != "text/plain" }.forEach { part ->
                column.addView(chip("📎  " + part.mime.substringAfter("/").uppercase(), OmnioColors.TEXT) { openPart(part) })
            }

            if (message.body.isNotBlank()) {
                column.addView(TextView(outer.context).apply {
                    text = message.body
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(if (mine) OmnioColors.ON_ACCENT else OmnioColors.TEXT)
                    setLinkTextColor(if (mine) OmnioColors.ON_ACCENT else OmnioColors.MESSAGES_SOFT)
                    setMaxWidth(bubbleWidth)
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        setColor(if (mine) (if (message.failed) OmnioColors.MISSED else OmnioColors.MESSAGES) else OmnioColors.CARD)
                    }
                    Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
                    setOnLongClickListener { showMessageMenu(message, row.reactions); true }
                }, wrap())
                OmnioEmail.findEmail(message.body)?.let { email ->
                    val number = if (message.incoming) message.address.ifEmpty { conversation?.people?.singleOrNull()?.address.orEmpty() } else ""
                    column.addView(chip("✉  $email", OmnioColors.MESSAGES_SOFT) { OmnioEmail.showActions(this@OmnioThreadActivity, email, number) })
                }
            }

            if (row.reactions.isNotEmpty()) {
                column.addView(TextView(outer.context).apply {
                    text = row.reactions.map { it.emoji }.distinct().joinToString(" ")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(OmnioColors.CARD_SELECTED)
                        setStroke(dp(2), OmnioColors.BACKGROUND)
                    }
                    setOnClickListener { showMessageMenu(message, row.reactions) }
                }, wrap(-dp(6)))
            }

            if (row.showTime || message.failed || message.pending) {
                val status = when {
                    message.failed -> getString(R.string.omnio_thread_not_sent)
                    message.pending -> getString(R.string.omnio_thread_sending)
                    else -> android.text.format.DateFormat.getTimeFormat(outer.context).format(message.date)
                }
                column.addView(TextView(outer.context).apply {
                    text = status
                    setTextColor(if (message.failed) OmnioColors.MISSED_SOFT else OmnioColors.TEXT_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(dp(8), dp(3), dp(8), dp(6))
                    if (message.failed) setOnClickListener { send(message.body, message.id) }
                }, wrap())
            }
        }

        private fun chip(label: String, colour: Int, onClick: () -> Unit) = TextView(outer.context).apply {
            text = label
            setTextColor(colour)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(OmnioColors.CARD_DEEP)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    private fun dayLabel(time: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(now, then) -> getString(R.string.omnio_today)
            same(yesterday, then) -> getString(R.string.omnio_yesterday)
            else -> android.text.format.DateFormat.format(
                android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "EEEE d MMMM" else "d MMMM yyyy"),
                then
            ).toString()
        }
    }
}
