package com.omnioone.app.fragments

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioContactActivity
import com.omnioone.app.activities.OmnioEditContactActivity
import com.omnioone.app.databinding.FragmentOmnioMessagesBinding
import com.omnioone.app.phone.startCallWithConfirmationCheck
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.messaging.OmnioConversationsAdapter
import com.omnioone.app.messaging.OmnioConversationsAdapter.Item
import com.omnioone.app.messaging.OmnioMessageStore
import com.omnioone.app.core.hasPermission
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.OmnioContactData
import com.omnioone.app.core.PERMISSION_READ_SMS
import com.omnioone.app.core.ensureBackgroundThread
import com.omnioone.app.messaging.THREAD_ID
import com.omnioone.app.messaging.THREAD_TITLE

/**
 * Omnio One Messages tab (Messages rewrite, stage 2): your conversations inside the main screen,
 * with the same search bar and tab bar as Contacts and Recents. Opening a chat still uses the
 * current conversation screen until stage 3.
 */
class OmnioMessagesTab(context: Context, attributeSet: AttributeSet) :
    OmnioTab(context, attributeSet) {

    private enum class Filter { ALL, UNREAD, UNKNOWN, GROUPS }

    private lateinit var binding: FragmentOmnioMessagesBinding
    private var adapter: OmnioConversationsAdapter? = null
    private var all = listOf<OmnioMessageStore.Conversation>()
    private var binnedChats = 0
    private var filter = Filter.ALL
    private var searchQuery = ""
    private var textColor = OmnioColors.TEXT
    private var loading = false
    private var reloadAgain = false

    private val handler = Handler(Looper.getMainLooper())
    private val reloadSoon = Runnable { refreshItems() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadSoon)
            handler.postDelayed(reloadSoon, 500)
        }
    }
    private var observing = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentOmnioMessagesBinding.bind(this)
        binding.messagesList.letterBar.visibility = View.GONE
        chips().forEach { (chip, chipFilter) -> chip.setOnClickListener { select(chipFilter) } }
        styleChips()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startObserving()
    }

    override fun onDetachedFromWindow() {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        handler.removeCallbacks(reloadSoon)
        super.onDetachedFromWindow()
    }

    private fun startObserving() {
        if (observing || !context.hasPermission(PERMISSION_READ_SMS)) return
        try {
            context.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        } catch (ignored: Exception) {
        }
    }

    // ---- page ----

    // archive, recycle bin and other changes made elsewhere refresh this list
    private val changeListener: () -> Unit = { refreshItems() }

    override fun setupFragment() {
        val host = activity ?: return
        com.omnioone.app.messaging.OmnioMessagesBus.listen(changeListener)
        adapter = OmnioConversationsAdapter(
            onOpen = { open(it) },
            onPhoto = { openPerson(it) },
            onMenu = { showMenu(it) },
            onArchived = { com.omnioone.app.messaging.OmnioChatListActivity.open(host, com.omnioone.app.messaging.OmnioChatListActivity.MODE_ARCHIVE) },
            onBin = { com.omnioone.app.messaging.OmnioChatListActivity.open(host, com.omnioone.app.messaging.OmnioChatListActivity.MODE_BIN) },
        ).also { it.textColor = textColor }
        val list = binding.messagesList.fragmentList
        list.layoutManager = LinearLayoutManager(context)
        list.setPadding(0, list.paddingTop, 0, list.paddingBottom)
        list.itemAnimator = null
        list.adapter = adapter
        ItemTouchHelper(swipe).attachToRecyclerView(list)
        binding.messagesList.fragmentPlaceholder2.setOnClickListener {
            host.handlePermission(PERMISSION_READ_SMS) { granted -> if (granted) refreshItems() }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        this.textColor = textColor
        binding.messagesList.fragmentPlaceholder.setTextColor(textColor)
        binding.messagesList.fragmentPlaceholder2.setTextColor(OmnioColors.ON_ACCENT)
        binding.messagesList.fragmentPlaceholder2.backgroundTintList = ColorStateList.valueOf(OmnioColors.MESSAGES)
        adapter?.textColor = textColor
        adapter?.notifyDataSetChanged()
        styleChips()
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (!context.hasPermission(PERMISSION_READ_SMS)) {
            show()
            callback?.invoke()
            return
        }
        startObserving()
        if (loading) {
            reloadAgain = true
            callback?.invoke()
            return
        }
        loading = true
        ensureBackgroundThread {
            com.omnioone.app.messaging.OmnioRecycleBin.tidy(context)
            val conversations = OmnioMessageStore.conversations(context)
            val bin = com.omnioone.app.messaging.OmnioRecycleBin.entries(context).map { it.threadId }.distinct().size
            handler.post {
                loading = false
                all = conversations
                binnedChats = bin
                show()
                callback?.invoke()
                if (reloadAgain) {
                    reloadAgain = false
                    refreshItems()
                }
            }
        }
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text.trim()
        show()
    }

    override fun onSearchClosed() {
        searchQuery = ""
        show()
    }

    // ---- showing ----

    private fun matches(conversation: OmnioMessageStore.Conversation): Boolean {
        val passes = when (filter) {
            Filter.ALL -> true
            Filter.UNREAD -> conversation.unread > 0
            Filter.UNKNOWN -> conversation.people.none { it.contactId != null }
            Filter.GROUPS -> conversation.isGroup
        }
        if (!passes) return false
        if (searchQuery.isEmpty()) return true
        return conversation.title.contains(searchQuery, true) ||
            conversation.snippet.contains(searchQuery, true) ||
            conversation.people.any { it.address.filter(Char::isDigit).contains(searchQuery.filter(Char::isDigit).ifEmpty { "\u0000" }) }
    }

    private fun show() {
        val current = adapter ?: return
        val allowed = context.hasPermission(PERMISSION_READ_SMS)
        val visible = all.filter { !it.isArchived && matches(it) }
        val pinned = visible.filter { it.isPinned }
        val rest = visible.filter { !it.isPinned }
        val items = ArrayList<Item>()
        if (pinned.isNotEmpty() && searchQuery.isEmpty()) {
            items += Item.Header(context.getString(R.string.omnio_messages_pinned))
            items += pinned.map { Item.Row(it) }
            if (rest.isNotEmpty()) items += Item.Header(context.getString(R.string.omnio_messages_all_chats))
            items += rest.map { Item.Row(it) }
        } else {
            items += visible.map { Item.Row(it) }
        }
        val archivedCount = all.count { it.isArchived }
        if (archivedCount > 0 && searchQuery.isEmpty() && filter == Filter.ALL) {
            items += Item.ArchivedRow(archivedCount, context.getString(R.string.omnio_messages_archived, archivedCount))
        }
        if (binnedChats > 0 && searchQuery.isEmpty() && filter == Filter.ALL) {
            items += Item.ArchivedRow(binnedChats, context.getString(R.string.omnio_messages_bin_row, binnedChats), bin = true)
        }
        current.submit(items, searchQuery)

        val empty = visible.isEmpty() && !loading
        binding.messagesList.fragmentPlaceholderHolder.visibility = if (empty) View.VISIBLE else View.GONE
        binding.messagesList.fragmentPlaceholder.text = context.getString(
            when {
                !allowed -> R.string.omnio_messages_no_access
                searchQuery.isNotEmpty() -> R.string.omnio_messages_no_matches
                filter == Filter.UNREAD -> R.string.omnio_messages_all_read
                else -> R.string.omnio_messages_none
            }
        )
        binding.messagesList.fragmentPlaceholder2.text = context.getString(R.string.request_access)
        binding.messagesList.fragmentPlaceholder2.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun chips(): List<Pair<TextView, Filter>> = listOf(
        binding.messagesFilterAll to Filter.ALL,
        binding.messagesFilterUnread to Filter.UNREAD,
        binding.messagesFilterUnknown to Filter.UNKNOWN,
        binding.messagesFilterGroups to Filter.GROUPS,
    )

    private fun styleChips() {
        chips().forEach { (chip, chipFilter) ->
            val selected = chipFilter == filter
            chip.backgroundTintList = ColorStateList.valueOf(if (selected) OmnioColors.MESSAGES else OmnioColors.CARD)
            chip.setTextColor(if (selected) OmnioColors.ON_ACCENT else textColor)
        }
    }

    private fun select(newFilter: Filter) {
        if (filter == newFilter) return
        filter = newFilter
        styleChips()
        show()
        binding.messagesList.fragmentList.scrollToPosition(0)
    }

    // ---- actions ----

    private fun open(conversation: OmnioMessageStore.Conversation) {
        val host = activity ?: return
        val screen = com.omnioone.app.messaging.OmnioThreadActivity::class.java
        host.startActivity(Intent(host, screen).apply {
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
        })
    }

    private fun openPerson(conversation: OmnioMessageStore.Conversation) {
        val host = activity ?: return
        val person = conversation.people.singleOrNull()
        if (person == null) {
            open(conversation)
            return
        }
        val contactId = person.contactId
        if (contactId == null) {
            host.startActivity(Intent(host, OmnioEditContactActivity::class.java).putExtra(OmnioEditContactActivity.EXTRA_NUMBER, person.address))
            return
        }
        ensureBackgroundThread {
            val rawId = OmnioContactData.firstRawId(host, contactId) ?: return@ensureBackgroundThread
            host.runOnUiThread {
                host.startActivity(Intent(host, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, rawId.toInt()))
            }
        }
    }

    private fun prefs() = context.getSharedPreferences("Prefs", Context.MODE_PRIVATE)

    private fun setPinned(conversation: OmnioMessageStore.Conversation, pinned: Boolean) {
        val current = prefs().getStringSet("pinned_conversations", emptySet()).orEmpty()
        val id = conversation.threadId.toString()
        prefs().edit().putStringSet("pinned_conversations", if (pinned) current + id else current - id).apply()
        refreshItems()
    }

    private fun setRead(conversation: OmnioMessageStore.Conversation, read: Boolean) {
        ensureBackgroundThread {
            if (read) com.omnioone.app.messaging.OmnioNotificationActions.markRead(context, conversation.threadId)
            else com.omnioone.app.messaging.OmnioNotificationActions.markUnread(context, conversation.threadId)
            handler.post { refreshItems() }
        }
    }

    private fun archive(conversation: OmnioMessageStore.Conversation) {
        ensureBackgroundThread {
            com.omnioone.app.messaging.OmnioArchive.set(context, conversation.threadId, true)
            handler.post {
                context.toast(R.string.omnio_messages_archived_done)
                refreshItems()
            }
        }
    }

    private fun showMenu(conversation: OmnioMessageStore.Conversation) {
        val host = activity ?: return
        val actions = ArrayList<Pair<OmnioSheet.Item, () -> Unit>>()
        fun add(label: Int, icon: Int, colour: Int, labelColour: Int = OmnioSheet.TEXT, action: () -> Unit) {
            val drawable = ContextCompat.getDrawable(host, icon)?.mutate()?.apply { setTint(colour) }
            actions += OmnioSheet.Item(host.getString(label), icon = drawable, labelColor = labelColour) to action
        }
        val person = conversation.people.singleOrNull()
        add(if (conversation.isPinned) R.string.omnio_messages_unpin else R.string.omnio_messages_pin, com.omnioone.app.R.drawable.ic_pin_vector, OmnioColors.FAVOURITES) {
            setPinned(conversation, !conversation.isPinned)
        }
        if (conversation.unread > 0) {
            add(R.string.omnio_messages_mark_read, com.omnioone.app.R.drawable.ic_check_vector, OmnioColors.MESSAGES) { setRead(conversation, true) }
        } else {
            add(R.string.omnio_messages_mark_unread, com.omnioone.app.R.drawable.ic_check_vector, OmnioColors.MESSAGES) { setRead(conversation, false) }
        }
        if (person != null) {
            add(R.string.omnio_call, com.omnioone.app.R.drawable.ic_phone_vector, OmnioColors.CALLS) { host.startCallWithConfirmationCheck(person.address, person.label) }
            add(if (person.contactId != null) R.string.omnio_open_contact else R.string.omnio_add_to_contacts, com.omnioone.app.R.drawable.ic_person_vector, OmnioColors.CONTACTS) {
                openPerson(conversation)
            }
        }
        add(R.string.omnio_messages_archive, R.drawable.ic_archive_vector, OmnioColors.TEXT_MUTED) { archive(conversation) }
        if (person != null) {
            add(R.string.omnio_block_number, com.omnioone.app.R.drawable.ic_block_vector, OmnioColors.MISSED, OmnioColors.MISSED) { confirmBlock(person.address, person.label) }
        }
        add(R.string.omnio_messages_delete, com.omnioone.app.R.drawable.ic_delete_vector, OmnioColors.MISSED, OmnioColors.MISSED) { confirmDelete(conversation) }
        OmnioSheet.list(host, conversation.title, person?.address?.takeIf { person.name != null }, actions.map { it.first }) { picked -> actions[picked].second() }
    }

    private fun confirmBlock(number: String, name: String) {
        val host = activity ?: return
        OmnioSheet.confirm(
            host,
            title = host.getString(R.string.omnio_block_question, name),
            message = host.getString(R.string.omnio_block_desc),
            positive = host.getString(R.string.omnio_block_number),
            mainColor = OmnioColors.MISSED
        ) {
            ensureBackgroundThread {
                val blocked = try {
                    host.contentResolver.insert(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
                    ) != null
                } catch (e: Exception) {
                    false
                }
                host.runOnUiThread { host.toast(if (blocked) R.string.omnio_blocked else R.string.omnio_block_failed) }
            }
        }
    }

    private fun confirmDelete(conversation: OmnioMessageStore.Conversation) {
        val host = activity ?: return
        OmnioSheet.confirm(
            host,
            title = host.getString(R.string.omnio_messages_delete_question, conversation.title),
            message = host.getString(if (com.omnioone.app.messaging.OmnioRecycleBin.isOn(host)) R.string.omnio_bin_moved_desc else R.string.omnio_messages_delete_desc),
            positive = host.getString(com.omnioone.app.R.string.delete),
            mainColor = OmnioColors.MISSED
        ) {
            ensureBackgroundThread {
                com.omnioone.app.messaging.OmnioRecycleBin.deleteChat(context, conversation.threadId)
                handler.post { refreshItems() }
            }
        }
    }

    // ---- swipe right: read / unread, swipe left: archive ----

    private val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun at(holder: RecyclerView.ViewHolder) = (adapter?.items?.getOrNull(holder.bindingAdapterPosition) as? Item.Row)?.conversation

        override fun getSwipeDirs(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) =
            if (at(holder) == null) 0 else super.getSwipeDirs(recyclerView, holder)

        override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = 0.35f

        override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            val conversation = at(holder)
            adapter?.notifyItemChanged(holder.bindingAdapterPosition)
            if (conversation != null) {
                if (direction == ItemTouchHelper.RIGHT) setRead(conversation, conversation.unread > 0) else archive(conversation)
            }
        }

        override fun onChildDraw(canvas: Canvas, recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            val view = holder.itemView
            if (dX != 0f) {
                val right = dX > 0
                paint.color = if (right) OmnioColors.MESSAGES else OmnioColors.CARD_SELECTED
                val rect = if (right) RectF(view.left.toFloat(), view.top.toFloat(), view.left + dX, view.bottom.toFloat())
                else RectF(view.right + dX, view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())
                canvas.drawRect(rect, paint)
                val icon = ContextCompat.getDrawable(view.context, if (right) com.omnioone.app.R.drawable.ic_check_vector else R.drawable.ic_archive_vector)?.mutate()
                if (icon != null) {
                    val size = (26 * view.resources.displayMetrics.density).toInt()
                    val margin = size
                    val top = view.top + (view.height - size) / 2
                    val left = if (right) view.left + margin else view.right - margin - size
                    icon.setTint(0xFFFFFFFF.toInt())
                    icon.setBounds(left, top, left + size, top + size)
                    if (kotlin.math.abs(dX) > margin + size) icon.draw(canvas)
                }
            }
            super.onChildDraw(canvas, recyclerView, holder, dX, dY, state, active)
        }
    }
}
