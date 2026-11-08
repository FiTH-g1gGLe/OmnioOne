package org.fossify.phone.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.helpers.OmnioAppTags
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.getProperText
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.FragmentContactsBinding
import org.fossify.phone.databinding.FragmentLettersLayoutBinding
import org.fossify.phone.extensions.launchCreateNewContactIntent
import org.fossify.phone.extensions.setupWithContacts
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.interfaces.RefreshItemsListener

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private lateinit var outerBinding: FragmentContactsBinding
    private var allContacts = ArrayList<Contact>()
    private var appTags: Map<Int, CharSequence> = emptyMap()
    private var omnioTextColor = 0xFFECEFF4.toInt()

    override fun onFinishInflate() {
        super.onFinishInflate()
        outerBinding = FragmentContactsBinding.bind(this)
        binding = FragmentLettersLayoutBinding.bind(outerBinding.contactsFragment)
        innerBinding = LettersInnerBinding(binding)

        // Omnio One: the list sits under the favourites row and pushes it away when scrolling
        binding.fragmentWrapper.layoutParams = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { behavior = AppBarLayout.ScrollingViewBehavior() }
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)

        val placeholderActionResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.create_new_contact
        } else {
            R.string.request_access
        }

        binding.fragmentPlaceholder2.apply {
            text = context.getString(placeholderActionResId)
            underlineText()
            setOnClickListener {
                if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                    activity?.launchCreateNewContactIntent()
                } else {
                    requestReadContactsPermission()
                }
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.apply {
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.updateTextColor(textColor)
            fragmentPlaceholder.setTextColor(textColor)
            omnioTextColor = textColor
            outerBinding.omnioFavouritesLabel.setTextColor(textColor)
            showFavouritesStrip()
            fragmentPlaceholder2.setTextColor(properPrimaryColor)

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = properPrimaryColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        val privateCursor = context?.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            allContacts = contacts

            if (SMT_PRIVATE !in context.baseConfig.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }
            }
            (activity as MainActivity).cacheContacts()

            activity?.runOnUiThread {
                gotContacts(contacts)
                showFavouritesStrip()
                callback?.invoke()
            }
            loadAppTags()
        }
    }

    /** Omnio One: keep the favourites row in step when a contact is starred or unstarred anywhere. */
    private val starObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            removeCallbacks(refreshStarsRunnable)
            postDelayed(refreshStarsRunnable, 400)
        }
    }

    private val refreshStarsRunnable = Runnable { refreshStars() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            context.contentResolver.registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true, starObserver)
        } catch (ignored: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        try {
            context.contentResolver.unregisterContentObserver(starObserver)
        } catch (ignored: Exception) {
        }
        removeCallbacks(refreshStarsRunnable)
        super.onDetachedFromWindow()
    }

    private fun refreshStars() {
        val ctx = context ?: return
        ensureBackgroundThread {
            val starredIds = HashSet<Int>()
            try {
                ctx.contentResolver.query(
                    android.provider.ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.Contacts._ID),
                    "${android.provider.ContactsContract.Contacts.STARRED} = 1",
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) starredIds.add(cursor.getInt(0))
                }
            } catch (ignored: Exception) {
                return@ensureBackgroundThread
            }
            activity?.runOnUiThread {
                allContacts.filter { !it.isPrivate() }.forEach { it.starred = if (it.contactId in starredIds) 1 else 0 }
                showFavouritesStrip()
            }
        }
    }

    private fun loadAppTags() {
        val ctx = context ?: return
        ensureBackgroundThread {
            val tags = OmnioAppTags.load(ctx)
            activity?.runOnUiThread {
                appTags = tags
                (binding.fragmentList.adapter as? ContactsAdapter)?.appTags = tags
            }
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Omnio One: "New" button plus a scrolling row of favourite contacts above the list. */
    private fun showFavouritesStrip() {
        val holder = outerBinding.omnioFavouritesHolder
        holder.removeAllViews()
        val favourites = allContacts.filter { it.starred == 1 }.distinctBy { it.contactId }
        outerBinding.omnioFavouritesLabel.beVisibleIf(favourites.isNotEmpty())

        holder.addView(makeTile(context.getString(R.string.omnio_new), null) {
            activity?.launchCreateNewContactIntent()
        })
        favourites.forEach { contact ->
            holder.addView(makeTile(contact.getNameToDisplay().substringBefore(" "), contact) {
                activity?.startContactDetailsIntent(contact)
            })
        }
    }

    private fun makeTile(label: String, contact: Contact?, onClick: () -> Unit): LinearLayout {
        val size = dp(56)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                if (contact == null) {
                    setImageResource(org.fossify.commons.R.drawable.ic_plus_vector)
                    imageTintList = ColorStateList.valueOf(0xFF0F1115.toInt())
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = context.getDrawable(R.drawable.omnio_circle_bg)
                    backgroundTintList = ColorStateList.valueOf(0xFFFF8A3D.toInt())
                } else {
                    SimpleContactsHelper(context).loadContactImage(contact.photoUri, this, contact.getNameToDisplay())
                }
            })

            addView(TextView(context).apply {
                text = label
                setTextColor(if (contact == null) 0xFFFF8A3D.toInt() else omnioTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(6), dp(4), 0)
            })
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller(contacts)
        if (contacts.isEmpty()) {
            binding.apply {
                fragmentPlaceholder.beVisible()
                fragmentPlaceholder2.beVisible()
                fragmentList.beGone()
            }
        } else {
            binding.apply {
                fragmentPlaceholder.beGone()
                fragmentPlaceholder2.beGone()
                fragmentList.beVisible()
            }

            if (binding.fragmentList.adapter == null) {
                ContactsAdapter(
                    activity = activity as SimpleActivity,
                    contacts = contacts,
                    recyclerView = binding.fragmentList,
                    refreshItemsListener = this,
                    itemClick = {
                        // Omnio One: tapping a contact opens its contact page
                        activity?.startContactDetailsIntent(it as Contact)
                    },
                    profileIconClick = {
                        activity?.startContactDetailsIntent(it as Contact)
                    },
                    showLetterHeaders = true
                ).apply {
                    appTags = this@ContactsFragment.appTags
                    binding.fragmentList.adapter = this
                }

                if (context.areSystemAnimationsEnabled) {
                    binding.fragmentList.scheduleLayoutAnimation()
                }
            } else {
                (binding.fragmentList.adapter as ContactsAdapter).updateItems(contacts)
            }
        }
    }

    private fun setupLetterFastScroller(contacts: ArrayList<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        outerBinding.omnioFavouritesStrip.beVisible()
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
        setupLetterFastScroller(allContacts)
    }

    override fun onSearchQueryChanged(text: String) {
        outerBinding.omnioFavouritesStrip.beVisibleIf(text.isBlank())
        if (text.isNotBlank()) outerBinding.contactsAppbar.setExpanded(true, false)
        val fixedText = text.trim().replace("\\s+".toRegex(), " ")
        val shouldNormalize = fixedText.normalizeString() == fixedText
        val filtered = allContacts.filter { contact ->
            getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                (fixedText.toLongOrNull() != null && contact.doesContainPhoneNumber(fixedText, true)) ||
                contact.emails.any { it.value.contains(fixedText, true) } ||
                contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                contact.IMs.any { it.value.contains(fixedText, true) } ||
                getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                contact.websites.any { it.contains(fixedText, true) }
        } as ArrayList

        filtered.sortBy {
            val nameToDisplay = it.getNameToDisplay()
            !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
        }

        binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered, fixedText)
        setupLetterFastScroller(filtered)
    }

    private fun requestReadContactsPermission() {
        activity?.handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                binding.fragmentPlaceholder.text = context.getString(R.string.no_contacts_found)
                binding.fragmentPlaceholder2.text = context.getString(R.string.create_new_contact)
                ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
                    activity?.runOnUiThread {
                        gotContacts(contacts)
                    }
                }
            }
        }
    }
}
