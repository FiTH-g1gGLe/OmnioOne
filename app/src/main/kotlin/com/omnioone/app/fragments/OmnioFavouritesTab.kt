package com.omnioone.app.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.omnioone.app.R
import com.omnioone.app.adapters.OmnioContactsAdapter
import com.omnioone.app.databinding.FragmentFavoritesBinding
import com.omnioone.app.databinding.OmnioContactsListBinding
import com.omnioone.app.phone.config
import com.omnioone.app.helpers.OmnioContactActions
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioFavourites
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.app.core.baseConfig
import com.omnioone.app.core.hasPermission
import com.omnioone.app.core.ON_CLICK_CALL_CONTACT
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.PERMISSION_READ_CONTACTS
import com.omnioone.app.core.VIEW_TYPE_GRID
import com.omnioone.app.core.ensureBackgroundThread

/**
 * Omnio One Favourites tab: your favourites as a grid or a list. Hold and drag to put them in
 * your own order; hold without moving for the contact menu. Updates the moment favourites change.
 */
class OmnioFavouritesTab(context: Context, attributeSet: AttributeSet) :
    OmnioTab(context, attributeSet) {

    private lateinit var binding: OmnioContactsListBinding
    private var adapter: OmnioContactsAdapter? = null
    private var everyone = listOf<OmnioPeople.Person>()
    private var searchQuery = ""
    private var textColor = OmnioColors.TEXT
    private var loading = false
    private val handler = Handler(Looper.getMainLooper())
    private val favouritesListener: () -> Unit = { show() }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentFavoritesBinding.bind(this).favouritesList
        binding.letterBar.visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        OmnioFavourites.addListener(favouritesListener)
    }

    override fun onDetachedFromWindow() {
        OmnioFavourites.removeListener(favouritesListener)
        super.onDetachedFromWindow()
    }

    override fun setupFragment() {
        binding.fragmentPlaceholder2.visibility = View.GONE
        buildAdapter()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        this.textColor = textColor
        binding.fragmentPlaceholder.setTextColor(textColor)
        adapter?.textColor = textColor
        adapter?.notifyDataSetChanged()
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS) || loading) {
            callback?.invoke()
            return
        }
        loading = true
        val ignored = context.baseConfig.ignoredContactSources.toSet()
        ensureBackgroundThread {
            val people = OmnioPeople.load(context, ignored)
            handler.post {
                loading = false
                everyone = people
                buildAdapter()
                show()
                callback?.invoke()
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

    /** Grid columns changed from the menu. */
    fun columnCountChanged() {
        (binding.fragmentList.layoutManager as? GridLayoutManager)?.spanCount = context.config.contactsGridColumnCount.coerceAtLeast(1)
        adapter?.notifyDataSetChanged()
    }

    // ---- the list ----

    private val isGrid get() = context.config.viewType == VIEW_TYPE_GRID

    private fun buildAdapter() {
        val host = activity ?: return
        val grid = isGrid
        val previous = adapter
        if (previous != null && previous.isGrid == grid) return
        adapter = OmnioContactsAdapter(
            style = if (grid) OmnioDisplay.STYLE_CIRCLES else OmnioDisplay.STYLE_LIST,
            onClick = { person ->
                if (context.config.onContactClick == ON_CLICK_CALL_CONTACT) OmnioContactActions.call(host, person) else OmnioContactActions.open(host, person)
            },
            onPhoto = { OmnioContactActions.open(host, it) },
            onLongClick = { _, _ -> },
        ).apply {
            textColor = this@OmnioFavouritesTab.textColor
            surnameFirst = context.baseConfig.startNameWithSurname
        }
        val list = binding.fragmentList
        list.layoutManager = if (grid) GridLayoutManager(context, context.config.contactsGridColumnCount.coerceAtLeast(1)) else LinearLayoutManager(context)
        list.setPadding(context.resources.displayMetrics.density.let { (it * if (grid) 10 else 0).toInt() }, list.paddingTop, context.resources.displayMetrics.density.let { (it * if (grid) 10 else 0).toInt() }, list.paddingBottom)
        list.itemAnimator = null
        list.adapter = adapter
        dragHelper.attachToRecyclerView(null)
        dragHelper.attachToRecyclerView(list)
    }

    private fun favourites() = OmnioPeople.favourites(context, everyone)

    private fun show() {
        val current = adapter ?: return
        val all = favourites()
        val visible = if (searchQuery.isEmpty()) all else all.filter { OmnioPeople.matches(it, searchQuery) }
        current.surnameFirst = context.baseConfig.startNameWithSurname
        current.submit(visible, { it.letter(it.name) }, showHeaders = false, highlight = searchQuery)
        binding.fragmentPlaceholderHolder.visibility = if (visible.isEmpty() && !loading) View.VISIBLE else View.GONE
        binding.fragmentPlaceholder.text = context.getString(
            when {
                !context.hasPermission(PERMISSION_READ_CONTACTS) -> R.string.could_not_access_contacts
                searchQuery.isNotEmpty() -> R.string.omnio_no_contact_matches
                else -> R.string.omnio_no_favourites
            }
        )
    }

    private fun saveOrder() {
        val ids = adapter?.people()?.map { it.contactId.toString() } ?: return
        context.config.favoritesContactsOrder = Gson().toJson(ids)
        context.config.isCustomOrderSelected = true
        OmnioFavourites.notifyChanged()
    }

    /** Hold and drag to reorder; hold and let go without moving to open the menu. */
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        private var moved = false
        private var held: OmnioPeople.Person? = null

        override fun getMovementFlags(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
            val directions = if (isGrid) ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            else ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(if (searchQuery.isEmpty()) directions else 0, 0)
        }

        override fun isLongPressDragEnabled() = true

        override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            adapter?.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            moved = true
            return true
        }

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(holder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                moved = false
                held = (adapter?.items?.getOrNull(holder.bindingAdapterPosition) as? OmnioContactsAdapter.Item.Row)?.person
                holder.itemView.animate().scaleX(1.05f).scaleY(1.05f).alpha(0.9f).setDuration(120).start()
            }
        }

        override fun clearView(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, holder)
            holder.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            if (moved) {
                saveOrder()
            } else {
                val host = activity
                val person = held
                if (host != null && person != null) OmnioContactActions.showMenu(host, person)
            }
            held = null
            moved = false
        }
    })
}
