package com.omnioone.app.fragments

import android.content.Context
import android.util.AttributeSet
import com.google.gson.Gson
import com.omnioone.commons.adapters.MyRecyclerViewAdapter
import com.omnioone.commons.extensions.areSystemAnimationsEnabled
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.commons.extensions.beGone
import com.omnioone.commons.extensions.beVisible
import com.omnioone.commons.extensions.beVisibleIf
import com.omnioone.commons.extensions.getColorStateList
import com.omnioone.commons.extensions.getContrastColor
import com.omnioone.commons.extensions.getMyContactsCursor
import com.omnioone.commons.extensions.hasPermission
import com.omnioone.commons.helpers.ContactsHelper
import com.omnioone.commons.helpers.Converters
import com.omnioone.commons.helpers.MyContactsContentProvider
import com.omnioone.commons.helpers.PERMISSION_READ_CONTACTS
import com.omnioone.commons.helpers.SMT_PRIVATE
import com.omnioone.commons.helpers.VIEW_TYPE_GRID
import com.omnioone.commons.models.contacts.Contact
import com.omnioone.commons.views.MyGridLayoutManager
import com.omnioone.commons.views.MyLinearLayoutManager
import com.omnioone.app.R
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.adapters.ContactsAdapter
import com.omnioone.app.databinding.FragmentFavoritesBinding
import com.omnioone.app.databinding.FragmentLettersLayoutBinding
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.handleGenericContactClick
import com.omnioone.app.extensions.setupWithContacts
import com.omnioone.app.extensions.startContactDetailsIntent
import com.omnioone.app.interfaces.RefreshItemsListener

class FavoritesFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private var allContacts = ArrayList<Contact>()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentLettersLayoutBinding.bind(FragmentFavoritesBinding.bind(this).favoritesFragment)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)
        binding.fragmentPlaceholder2.beGone()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.apply {
            fragmentPlaceholder.setTextColor(textColor)
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.updateTextColor(textColor)

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = properPrimaryColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
        }
    }

    /** Omnio One: everyone with a number; the favourites shown are picked from this. */
    private var everyone = ArrayList<Contact>()

    private val favouritesListener: () -> Unit = { showFavouritesNow() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        com.omnioone.app.helpers.OmnioFavourites.addListener(favouritesListener)
    }

    override fun onDetachedFromWindow() {
        com.omnioone.app.helpers.OmnioFavourites.removeListener(favouritesListener)
        super.onDetachedFromWindow()
    }

    private fun isFavourite(contact: Contact) =
        if (contact.isPrivate() || !com.omnioone.app.helpers.OmnioFavourites.isLoaded) {
            contact.starred == 1
        } else {
            com.omnioone.app.helpers.OmnioFavourites.isFavourite(contact.contactId)
        }

    /** Re-pick favourites from the contacts already loaded: instant, no reload. */
    private fun showFavouritesNow() {
        val activity = activity ?: return
        val favorites = everyone.filter { isFavourite(it) }.distinctBy { it.contactId } as? ArrayList<Contact>
            ?: ArrayList(everyone.filter { isFavourite(it) }.distinctBy { it.contactId })
        allContacts = if (activity.config.isCustomOrderSelected) sortByCustomOrder(favorites) else ArrayList(favorites)
        gotContacts(allContacts)
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            allContacts = contacts

            if (SMT_PRIVATE !in context.baseConfig.ignoredContactSources) {
                val privateCursor = context?.getMyContactsCursor(favoritesOnly = true, withPhoneNumbersOnly = true)
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor).map {
                    it.copy(starred = 1)
                }
                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }
            }
            everyone = ArrayList(allContacts)
            val favorites = ArrayList(allContacts.filter { isFavourite(it) }.distinctBy { it.contactId })

            allContacts = if (activity!!.config.isCustomOrderSelected) {
                sortByCustomOrder(favorites)
            } else {
                favorites
            }

            activity?.runOnUiThread {
                gotContacts(allContacts)
                callback?.invoke()
            }
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller(contacts)
        binding.apply {
            if (contacts.isEmpty()) {
                fragmentPlaceholder.beVisible()
                fragmentList.beGone()
            } else {
                fragmentPlaceholder.beGone()
                fragmentList.beVisible()

                updateListAdapter()
            }
        }
    }

    private fun updateListAdapter() {
        val viewType = context.config.viewType
        setViewType(viewType)

        val currAdapter = binding.fragmentList.adapter as ContactsAdapter?
        if (currAdapter == null) {
            ContactsAdapter(
                activity = activity as SimpleActivity,
                contacts = allContacts,
                recyclerView = binding.fragmentList,
                refreshItemsListener = this,
                viewType = viewType,
                showDeleteButton = false,
                enableDrag = true,
                itemClick = {
                    activity?.handleGenericContactClick(it as Contact)
                },
                profileIconClick = {
                    activity?.startContactDetailsIntent(it as Contact)
                }).apply {
                binding.fragmentList.adapter = this

                onDragEndListener = {
                    val adapter = binding.fragmentList.adapter
                    if (adapter is ContactsAdapter) {
                        val items = adapter.contacts
                        saveCustomOrderToPrefs(items)
                        setupLetterFastScroller(items)
                    }
                }

                onSpanCountListener = { newSpanCount ->
                    context.config.contactsGridColumnCount = newSpanCount
                }
            }

            if (context.areSystemAnimationsEnabled) {
                binding.fragmentList.scheduleLayoutAnimation()
            }
        } else {
            currAdapter.viewType = viewType
            currAdapter.updateItems(allContacts)
        }
    }

    fun columnCountChanged() {
        (binding.fragmentList.layoutManager as? MyGridLayoutManager)?.spanCount = context!!.config.contactsGridColumnCount
        binding.fragmentList.adapter?.apply {
            notifyItemRangeChanged(0, allContacts.size)
        }
    }

    private fun sortByCustomOrder(favorites: List<Contact>): ArrayList<Contact> {
        val favoritesOrder = activity!!.config.favoritesContactsOrder

        if (favoritesOrder.isEmpty()) {
            return ArrayList(favorites)
        }

        val orderList = Converters().jsonToStringList(favoritesOrder)
        val map = orderList.withIndex().associate { it.value to it.index }
        val sorted = favorites.sortedBy { map[it.contactId.toString()] }

        return ArrayList(sorted)
    }

    private fun saveCustomOrderToPrefs(items: List<Contact>) {
        activity?.apply {
            val orderIds = items.map { it.contactId }
            val orderGsonString = Gson().toJson(orderIds)
            config.favoritesContactsOrder = orderGsonString
            allContacts = ArrayList(items)
        }
    }

    private fun setupLetterFastScroller(contacts: List<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
        setupLetterFastScroller(allContacts)
    }

    override fun onSearchQueryChanged(text: String) {
        val fixedText = text.trim().replace("\\s+".toRegex(), " ")
        val contacts = allContacts.filter {
            it.name.contains(fixedText, true) || (text.toLongOrNull() != null && it.doesContainPhoneNumber(fixedText))
        }.sortedByDescending {
            it.name.startsWith(fixedText, true)
        }.toMutableList() as ArrayList<Contact>

        binding.fragmentPlaceholder.beVisibleIf(contacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(contacts, fixedText)
        setupLetterFastScroller(contacts)
    }

    private fun setViewType(viewType: Int) {
        val spanCount = context.config.contactsGridColumnCount

        val layoutManager = if (viewType == VIEW_TYPE_GRID) {
            binding.letterFastscroller.beGone()
            MyGridLayoutManager(context, spanCount)
        } else {
            binding.letterFastscroller.beVisible()
            MyLinearLayoutManager(context)
        }
        binding.fragmentList.layoutManager = layoutManager
    }
}
