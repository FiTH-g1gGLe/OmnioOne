package com.omnioone.app.messaging

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.core.OmnioActivity
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.ensureBackgroundThread

/** Omnio One: the Archived chats and Recycle bin screens. */
class OmnioChatListActivity : OmnioActivity() {

    companion object {
        const val EXTRA_MODE = "omnio_mode"
        const val MODE_ARCHIVE = 0
        const val MODE_BIN = 1

        fun open(context: Context, mode: Int) =
            context.startActivity(Intent(context, OmnioChatListActivity::class.java).putExtra(EXTRA_MODE, mode))
    }

    private data class BinChat(val threadId: Long, val title: String, val photo: String, val count: Int, val daysLeft: Int, val preview: String)

    private var mode = MODE_ARCHIVE
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var emptyBinButton: TextView
    private var conversationsAdapter: OmnioConversationsAdapter? = null
    private val binAdapter = BinAdapter()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ load() }, 300)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_ARCHIVE)
        setContentView(buildScreen())
        contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }

    private fun load() {
        ensureBackgroundThread {
            if (mode == MODE_ARCHIVE) {
                val archived = OmnioMessageStore.conversations(this).filter { it.isArchived }
                runOnUiThread {
                    conversationsAdapter?.submit(archived.map { OmnioConversationsAdapter.Item.Row(it) }, "")
                    showEmpty(archived.isEmpty(), R.string.omnio_archive_empty)
                }
            } else {
                OmnioRecycleBin.tidy(this)
                val entries = OmnioRecycleBin.entries(this)
                val keys = entries.map { it.key }.toSet()
                val chats = OmnioMessageStore.conversations(this, showBinned = true).associateBy { it.threadId }
                val now = System.currentTimeMillis()
                val rows = entries.groupBy { it.threadId }.map { (threadId, items) ->
                    val chat = chats[threadId]
                    val oldest = items.minOf { it.deletedAt }
                    val left = OmnioRecycleBin.DAYS - ((now - oldest) / (24 * 3600 * 1000L)).toInt()
                    val preview = OmnioMessageStore.messages(this, threadId, limit = 400, includeBinned = true)
                        .filter { OmnioRecycleBin.keyOf(it.isMms, it.id) in keys }
                        .takeLast(2).joinToString("  ·  ") { it.body.ifBlank { "📎" } }
                    BinChat(threadId, chat?.title ?: "?", chat?.photo.orEmpty(), items.size, left.coerceAtLeast(0), preview)
                }.sortedBy { it.daysLeft }
                runOnUiThread {
                    binAdapter.submit(rows)
                    showEmpty(rows.isEmpty(), R.string.omnio_bin_empty)
                    emptyBinButton.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun showEmpty(empty: Boolean, text: Int) {
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        emptyView.setText(text)
    }

    // ---- archive actions ----

    private fun openChat(conversation: OmnioMessageStore.Conversation) =
        startActivity(OmnioNotifications.threadIntent(this, conversation.threadId).putExtra(com.omnioone.app.messaging.THREAD_TITLE, conversation.title))

    private fun archiveMenu(conversation: OmnioMessageStore.Conversation) {
        val items = listOf(
            sheetItem(R.string.omnio_archive_unarchive, R.drawable.ic_unarchive_vector, OmnioColors.MESSAGES),
            sheetItem(R.string.omnio_messages_delete, com.omnioone.app.R.drawable.ic_delete_vector, OmnioColors.MISSED, OmnioColors.MISSED),
        )
        OmnioSheet.list(this, conversation.title, null, items) { picked ->
            if (picked == 0) {
                ensureBackgroundThread {
                    OmnioArchive.set(this, conversation.threadId, false)
                    runOnUiThread {
                        toast(R.string.omnio_archive_unarchived)
                        load()
                    }
                }
            } else confirmDeleteChat(conversation.threadId, conversation.title)
        }
    }

    private fun confirmDeleteChat(threadId: Long, title: String) {
        val bin = OmnioRecycleBin.isOn(this)
        OmnioSheet.confirm(
            this,
            title = getString(R.string.omnio_messages_delete_question, title),
            message = getString(if (bin) R.string.omnio_bin_moved_desc else R.string.omnio_messages_delete_desc),
            positive = getString(com.omnioone.app.R.string.delete),
            mainColor = OmnioColors.MISSED
        ) {
            ensureBackgroundThread {
                OmnioRecycleBin.deleteChat(this, threadId)
                OmnioArchive.set(this, threadId, false)
                runOnUiThread { load() }
            }
        }
    }

    // ---- bin actions ----

    private fun binMenu(chat: BinChat) {
        val items = listOf(
            sheetItem(R.string.omnio_bin_restore, R.drawable.ic_unarchive_vector, OmnioColors.MESSAGES),
            sheetItem(R.string.omnio_bin_delete_forever, com.omnioone.app.R.drawable.ic_delete_vector, OmnioColors.MISSED, OmnioColors.MISSED),
        )
        OmnioSheet.list(this, chat.title, resources.getQuantityString(R.plurals.omnio_bin_count, chat.count, chat.count), items) { picked ->
            if (picked == 0) {
                ensureBackgroundThread {
                    OmnioRecycleBin.restoreChat(this, chat.threadId)
                    runOnUiThread {
                        toast(R.string.omnio_bin_restored)
                        load()
                    }
                }
            } else {
                OmnioSheet.confirm(this, getString(R.string.omnio_bin_delete_forever_question), getString(R.string.omnio_bin_cant_undo), positive = getString(R.string.omnio_bin_delete_forever), mainColor = OmnioColors.MISSED) {
                    ensureBackgroundThread {
                        OmnioRecycleBin.purgeChat(this, chat.threadId)
                        runOnUiThread { load() }
                    }
                }
            }
        }
    }

    private fun confirmEmptyBin() {
        OmnioSheet.confirm(this, getString(R.string.omnio_bin_empty_question), getString(R.string.omnio_bin_cant_undo), positive = getString(R.string.omnio_bin_empty_action), mainColor = OmnioColors.MISSED) {
            ensureBackgroundThread {
                OmnioRecycleBin.empty(this)
                runOnUiThread { load() }
            }
        }
    }

    private fun sheetItem(label: Int, icon: Int, colour: Int, labelColour: Int = OmnioSheet.TEXT) =
        OmnioSheet.Item(getString(label), icon = ContextCompat.getDrawable(this, icon)?.mutate()?.apply { setTint(colour) }, labelColor = labelColour)

    // ---- screen ----

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OmnioColors.BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
            addView(ImageView(this@OmnioChatListActivity).apply {
                setImageResource(com.omnioone.app.R.drawable.ic_arrow_left_vector)
                imageTintList = ColorStateList.valueOf(OmnioColors.TEXT)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                setOnClickListener { finish() }
            })
            addView(TextView(this@OmnioChatListActivity).apply {
                text = getString(if (mode == MODE_ARCHIVE) R.string.omnio_archive_title else R.string.omnio_bin_title)
                setTextColor(OmnioColors.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            emptyBinButton = TextView(this@OmnioChatListActivity).apply {
                text = getString(R.string.omnio_bin_empty_action)
                visibility = View.GONE
                setTextColor(OmnioColors.MISSED_SOFT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { confirmEmptyBin() }
            }
            addView(emptyBinButton)
        })
        if (mode == MODE_BIN) {
            root.addView(TextView(this).apply {
                text = getString(R.string.omnio_bin_note, OmnioRecycleBin.DAYS)
                setTextColor(OmnioColors.TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(20), 0, dp(20), dp(8))
            })
        }
        emptyView = TextView(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setTextColor(OmnioColors.TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(32), dp(48), dp(32), dp(16))
        }
        root.addView(emptyView)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OmnioChatListActivity)
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (mode == MODE_ARCHIVE) {
            conversationsAdapter = OmnioConversationsAdapter(
                onOpen = { openChat(it) },
                onPhoto = { openChat(it) },
                onMenu = { archiveMenu(it) },
                onArchived = {},
            )
            list.adapter = conversationsAdapter
        } else {
            list.adapter = binAdapter
        }
        root.addView(list)
        return root
    }

    private inner class BinAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = listOf<BinChat>()

        fun submit(newRows: List<BinChat>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val chat = rows[position]
            (holder.itemView as LinearLayout).apply {
                removeAllViews()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(14) }
                    OmnioDisplay.loadAvatar(this, chat.photo, chat.title, dp(25))
                    alpha = 0.7f
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = chat.title
                        setTextColor(OmnioColors.TEXT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = chat.preview
                        setTextColor(OmnioColors.TEXT_MUTED)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = resources.getQuantityString(R.plurals.omnio_bin_count, chat.count, chat.count) + "  ·  " +
                            resources.getQuantityString(R.plurals.omnio_bin_days_left, chat.daysLeft, chat.daysLeft)
                        setTextColor(if (chat.daysLeft <= 3) OmnioColors.MISSED_SOFT else OmnioColors.TEXT_MUTED)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                })
                setOnClickListener { binMenu(chat) }
            }
        }
    }
}
