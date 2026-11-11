package com.omnioone.app.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.R
import com.omnioone.app.activities.OmnioContactActivity
import com.omnioone.app.activities.OmnioEditContactActivity
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.adapters.OmnioRecentsAdapter
import com.omnioone.app.databinding.FragmentRecentsBinding
import com.omnioone.app.extensions.areMultipleSIMsAvailable
import com.omnioone.app.extensions.callContactWithSimWithConfirmationCheck
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.startCallWithConfirmationCheck
import com.omnioone.app.helpers.OmnioCallLog
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.interfaces.RefreshItemsListener
import com.omnioone.app.core.hasPermission
import com.omnioone.app.core.launchSendSMSIntent
import com.omnioone.app.core.toast
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.OmnioContactData
import com.omnioone.app.core.PERMISSION_READ_CALL_LOG
import com.omnioone.app.core.PERMISSION_WRITE_CALL_LOG
import com.omnioone.app.core.ensureBackgroundThread
import java.util.Calendar
import java.util.Locale

/**
 * Omnio One Recents: your call history grouped by day, with filters, search, swipe to call or
 * text, and a menu for everything else. Updates by itself whenever a call is logged.
 */
class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private enum class Filter { ALL, MISSED, INCOMING, OUTGOING, UNKNOWN }

    private lateinit var binding: FragmentRecentsBinding
    private var adapter: OmnioRecentsAdapter? = null
    private var allEntries = listOf<OmnioCallLog.Entry>()
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
            handler.postDelayed(reloadSoon, 400)
        }
    }
    private var observing = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
        chips().forEach { (chip, chipFilter) -> chip.setOnClickListener { selectFilter(chipFilter) } }
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
        if (observing || !context.hasPermission(PERMISSION_READ_CALL_LOG)) return
        try {
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        } catch (ignored: Exception) {
        }
    }

    // ---- MyViewPagerFragment ----

    override fun setupFragment() {
        val allowed = context.hasPermission(PERMISSION_READ_CALL_LOG)
        binding.recentsPlaceholder.text = context.getString(if (allowed) R.string.no_previous_calls else R.string.could_not_access_the_call_history)
        binding.recentsPlaceholder2.setOnClickListener { requestPermission() }

        val list = binding.recentsList
        adapter = OmnioRecentsAdapter(
            onCall = { call(it) },
            onText = { text(it) },
            onOpen = { open(it) },
            onMore = { showActions(it) },
        ).also { it.textColor = textColor }
        list.adapter = adapter
        list.itemAnimator = null
        ItemTouchHelper(swipe).attachToRecyclerView(list)
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        this.textColor = textColor
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(OmnioColors.ON_ACCENT)
        binding.recentsPlaceholder2.backgroundTintList = ColorStateList.valueOf(OmnioColors.CALLS)
        adapter?.textColor = textColor
        styleChips()
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
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
        val group = context.config.groupSubsequentCalls
        ensureBackgroundThread {
            val entries = OmnioCallLog.load(context, group = group)
            handler.post {
                loading = false
                allEntries = entries
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

    private fun matches(call: OmnioCallLog.Call) = when (filter) {
        Filter.ALL, Filter.UNKNOWN -> true
        Filter.MISSED -> call.isMissed
        Filter.INCOMING -> call.isIncoming
        Filter.OUTGOING -> call.isOutgoing
    }

    private fun matchesSearch(entry: OmnioCallLog.Entry): Boolean {
        if (searchQuery.isEmpty()) return true
        if (entry.name.contains(searchQuery, ignoreCase = true)) return true
        val digits = searchQuery.filter { it.isDigit() }
        return digits.isNotEmpty() && entry.number.filter { it.isDigit() }.contains(digits)
    }

    private fun visibleEntries() = allEntries
        .filter { filter != Filter.UNKNOWN || !it.isSaved }
        .mapNotNull { entry ->
            if (filter == Filter.ALL || filter == Filter.UNKNOWN) entry
            else entry.calls.filter { matches(it) }.takeIf { it.isNotEmpty() }?.let { entry.copy(calls = it) }
        }
        .filter { matchesSearch(it) }

    private fun show() {
        binding.progressIndicator.hide()
        val allowed = context.hasPermission(PERMISSION_READ_CALL_LOG)
        val entries = if (allowed) visibleEntries() else emptyList()
        adapter?.submit(entries, ::dayLabel, searchQuery)
        binding.recentsList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        binding.recentsPlaceholder.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.recentsPlaceholder.text = context.getString(
            when {
                !allowed -> R.string.could_not_access_the_call_history
                searchQuery.isNotEmpty() -> R.string.omnio_no_recent_matches
                filter == Filter.MISSED -> R.string.omnio_no_missed_calls
                filter == Filter.UNKNOWN -> R.string.omnio_no_unknown_calls
                filter == Filter.INCOMING -> R.string.omnio_no_incoming_calls
                filter == Filter.OUTGOING -> R.string.omnio_no_outgoing_calls
                else -> R.string.no_previous_calls
            }
        )
        binding.recentsPlaceholder2.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun dayLabel(time: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            OmnioRecentsAdapter.sameDay(time, now.timeInMillis) -> context.getString(R.string.omnio_today)
            OmnioRecentsAdapter.sameDay(time, yesterday.timeInMillis) -> context.getString(R.string.omnio_yesterday)
            now.timeInMillis - time < 6L * 24 * 60 * 60 * 1000 ->
                then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty()
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE d MMM"), then).toString()
            else -> android.text.format.DateFormat.getMediumDateFormat(context).format(then.time)
        }
    }

    private fun chips(): List<Pair<TextView, Filter>> = listOf(
        binding.recentsFilterAll to Filter.ALL,
        binding.recentsFilterMissed to Filter.MISSED,
        binding.recentsFilterIncoming to Filter.INCOMING,
        binding.recentsFilterOutgoing to Filter.OUTGOING,
        binding.recentsFilterUnknown to Filter.UNKNOWN,
    )

    private fun styleChips() {
        chips().forEach { (chip, chipFilter) ->
            val selected = chipFilter == filter
            val accent = if (chipFilter == Filter.MISSED) OmnioColors.MISSED else OmnioColors.CALLS
            chip.backgroundTintList = ColorStateList.valueOf(if (selected) accent else OmnioColors.CARD)
            chip.setTextColor(if (selected) OmnioColors.ON_ACCENT else textColor)
        }
    }

    private fun selectFilter(newFilter: Filter) {
        if (filter == newFilter) return
        filter = newFilter
        styleChips()
        show()
        binding.recentsList.scrollToPosition(0)
    }

    private fun requestPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) { granted ->
            if (granted) {
                setupFragment()
                refreshItems()
            }
        }
    }

    // ---- actions ----

    private fun call(entry: OmnioCallLog.Entry) {
        if (entry.isPrivate) {
            context.toast(R.string.omnio_private_number)
            return
        }
        activity?.startCallWithConfirmationCheck(entry.number, entry.name)
    }

    private fun text(entry: OmnioCallLog.Entry) {
        if (!entry.isPrivate) activity?.launchSendSMSIntent(entry.number)
    }

    private fun open(entry: OmnioCallLog.Entry) {
        val host = activity ?: return
        val contactId = entry.person?.contactId
        if (contactId == null) {
            if (entry.isPrivate) return
            host.startActivity(Intent(host, OmnioEditContactActivity::class.java).putExtra(OmnioEditContactActivity.EXTRA_NUMBER, entry.number))
            return
        }
        ensureBackgroundThread {
            val rawId = OmnioContactData.firstRawId(host, contactId) ?: return@ensureBackgroundThread
            host.runOnUiThread {
                host.startActivity(Intent(host, OmnioContactActivity::class.java).putExtra(OmnioContactActivity.EXTRA_CONTACT_ID, rawId.toInt()))
            }
        }
    }

    private fun showActions(entry: OmnioCallLog.Entry) {
        val host = activity ?: return
        val actions = ArrayList<Pair<OmnioSheet.Item, () -> Unit>>()
        fun add(label: Int, icon: Int, colour: Int, labelColour: Int = OmnioSheet.TEXT, action: () -> Unit) {
            val drawable = ContextCompat.getDrawable(host, icon)?.mutate()?.apply { setTint(colour) }
            actions += OmnioSheet.Item(host.getString(label), icon = drawable, labelColor = labelColour) to action
        }
        if (!entry.isPrivate) {
            add(R.string.omnio_call, com.omnioone.app.R.drawable.ic_phone_vector, OmnioColors.CALLS) { call(entry) }
            if (host.areMultipleSIMsAvailable()) {
                add(R.string.omnio_call_sim_1, com.omnioone.app.R.drawable.ic_phone_vector, OmnioColors.CALLS) {
                    host.callContactWithSimWithConfirmationCheck(entry.number, entry.name, useMainSIM = true)
                }
                add(R.string.omnio_call_sim_2, com.omnioone.app.R.drawable.ic_phone_vector, OmnioColors.CALLS) {
                    host.callContactWithSimWithConfirmationCheck(entry.number, entry.name, useMainSIM = false)
                }
            }
            add(R.string.omnio_text, R.drawable.ic_omnio_chat, OmnioColors.MESSAGES) { text(entry) }
            if (entry.isSaved) {
                add(R.string.omnio_open_contact, com.omnioone.app.R.drawable.ic_person_vector, OmnioColors.CONTACTS) { open(entry) }
            } else {
                add(R.string.omnio_add_to_contacts, com.omnioone.app.R.drawable.ic_add_person_vector, OmnioColors.CONTACTS) { open(entry) }
            }
            add(R.string.omnio_copy_number, com.omnioone.app.R.drawable.ic_copy_vector, OmnioColors.TEXT_MUTED) { copy(entry) }
        }
        add(R.string.omnio_call_history, com.omnioone.app.R.drawable.ic_info_outline_vector, OmnioColors.TEXT_MUTED) { showHistory(entry) }
        if (!entry.isPrivate) {
            add(R.string.omnio_block_number, com.omnioone.app.R.drawable.ic_block_vector, OmnioColors.MISSED, OmnioColors.MISSED) { confirmBlock(entry) }
        }
        add(R.string.omnio_delete_from_recents, com.omnioone.app.R.drawable.ic_delete_vector, OmnioColors.MISSED, OmnioColors.MISSED) { confirmDelete(entry) }

        val subtitle = listOfNotNull(
            if (entry.isSaved && !entry.isPrivate) entry.number else null,
            entry.person?.label?.ifEmpty { null }
        ).joinToString("  ·  ").ifEmpty { null }
        OmnioSheet.list(host, entry.name, subtitle, actions.map { it.first }) { picked -> actions[picked].second() }
    }

    private fun copy(entry: OmnioCallLog.Entry) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, entry.number))
        context.toast(R.string.omnio_copied)
    }

    private fun showHistory(entry: OmnioCallLog.Entry) {
        val host = activity ?: return
        val timeFormat = android.text.format.DateFormat.getTimeFormat(host)
        val items = entry.calls.map { call ->
            val (label, colour) = when {
                call.isMissed -> R.string.omnio_call_missed to OmnioColors.MISSED
                call.isDeclined -> R.string.omnio_call_declined to OmnioColors.TEXT_MUTED
                call.isOutgoing -> R.string.omnio_call_outgoing to OmnioColors.CALLS
                else -> R.string.omnio_call_incoming_log to OmnioColors.MESSAGES
            }
            OmnioSheet.Item(
                host.getString(label) + "  ·  " + dayLabel(call.date) + " " + timeFormat.format(call.date),
                trailing = if (call.duration > 0 && !call.isMissed) OmnioRecentsAdapter.duration(call.duration) else null,
                trailingColor = OmnioSheet.TEXT,
                labelColor = colour
            )
        }
        OmnioSheet.list(host, entry.name, host.getString(R.string.omnio_calls_count, entry.calls.size), items) {}
    }

    private fun confirmBlock(entry: OmnioCallLog.Entry) {
        val host = activity ?: return
        OmnioSheet.confirm(
            host,
            title = host.getString(R.string.omnio_block_question, entry.name),
            message = host.getString(R.string.omnio_block_desc),
            positive = host.getString(R.string.omnio_block_number),
            mainColor = OmnioColors.MISSED
        ) {
            ensureBackgroundThread {
                val blocked = try {
                    host.contentResolver.insert(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, entry.number) }
                    ) != null
                } catch (e: Exception) {
                    false
                }
                host.runOnUiThread { host.toast(if (blocked) R.string.omnio_blocked else R.string.omnio_block_failed) }
            }
        }
    }

    private fun confirmDelete(entry: OmnioCallLog.Entry) {
        val host = activity ?: return
        OmnioSheet.confirm(
            host,
            title = host.getString(R.string.omnio_delete_recents_question),
            message = host.getString(R.string.omnio_delete_recents_desc, entry.calls.size, entry.name),
            positive = host.getString(com.omnioone.app.R.string.delete),
            mainColor = OmnioColors.MISSED
        ) {
            host.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
                if (granted) ensureBackgroundThread {
                    OmnioCallLog.delete(host, entry.calls.map { it.id })
                    host.runOnUiThread { refreshItems() }
                }
            }
        }
    }

    // ---- swipe right to call, left to text ----

    private val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun entryAt(holder: RecyclerView.ViewHolder) =
            (adapter?.items?.getOrNull(holder.bindingAdapterPosition) as? OmnioRecentsAdapter.Item.Row)?.entry

        override fun getSwipeDirs(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
            val entry = entryAt(holder) ?: return 0
            return if (entry.isPrivate) 0 else super.getSwipeDirs(recyclerView, holder)
        }

        override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = 0.35f

        override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            val position = holder.bindingAdapterPosition
            val entry = entryAt(holder)
            adapter?.notifyItemChanged(position)
            if (entry != null) {
                if (direction == ItemTouchHelper.RIGHT) call(entry) else text(entry)
            }
        }

        override fun onChildDraw(canvas: Canvas, recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            val view = holder.itemView
            if (dX != 0f) {
                val right = dX > 0
                paint.color = if (right) OmnioColors.CALLS else OmnioColors.MESSAGES
                val rect = if (right) RectF(view.left.toFloat(), view.top.toFloat(), view.left + dX, view.bottom.toFloat())
                else RectF(view.right + dX, view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())
                canvas.drawRect(rect, paint)
                val icon = ContextCompat.getDrawable(view.context, if (right) com.omnioone.app.R.drawable.ic_phone_vector else R.drawable.ic_omnio_chat)?.mutate()
                if (icon != null) {
                    val size = (26 * view.resources.displayMetrics.density).toInt()
                    val margin = (26 * view.resources.displayMetrics.density).toInt()
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
