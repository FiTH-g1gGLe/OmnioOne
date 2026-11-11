package com.omnioone.app.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.omnioone.app.R
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.adapters.OmnioContactsAdapter
import com.omnioone.app.databinding.FragmentContactsBinding
import com.omnioone.app.databinding.OmnioContactsListBinding
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.launchCreateNewContactIntent
import com.omnioone.app.helpers.OmnioAppTags
import com.omnioone.app.helpers.OmnioContactActions
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioFavourites
import com.omnioone.app.helpers.OmnioPeople
import com.omnioone.app.interfaces.RefreshItemsListener
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.commons.extensions.hasPermission
import com.omnioone.commons.helpers.OmnioColors
import com.omnioone.commons.helpers.PERMISSION_READ_CONTACTS
import com.omnioone.commons.helpers.ensureBackgroundThread

/**
 * Omnio One Contacts tab: favourites row on top, then everyone in your chosen display style,
 * with search, an A–Z strip and live updates when contacts change anywhere.
 */
class ContactsFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<MyViewPagerFragment.ListInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var outerBinding: FragmentContactsBinding
    private lateinit var binding: OmnioContactsListBinding
    private var adapter: OmnioContactsAdapter? = null
    private var everyone = listOf<OmnioPeople.Person>()
    private var appTags: Map<Int, List<OmnioAppTags.Badge>> = emptyMap()
    private var searchQuery = ""
    private var textColor = OmnioColors.TEXT
    private var loading = false
    private var reloadAgain = false

    private val handler = Handler(Looper.getMainLooper())
    private val reloadSoon = Runnable {
        OmnioFavourites.reload(context)
        refreshItems()
    }
    private val contactsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadSoon)
            handler.postDelayed(reloadSoon, 500)
        }
    }
    private val favouritesListener: () -> Unit = { showFavouritesStrip() }

    private val sorting get() = context.config.sorting
    private fun letterOf(person: OmnioPeople.Person) = person.letter(OmnioPeople.sortName(person, sorting))

    override fun onFinishInflate() {
        super.onFinishInflate()
        outerBinding = FragmentContactsBinding.bind(this)
        binding = outerBinding.contactsList
        innerBinding = ListInnerBinding(binding.fragmentList)
        binding.letterBar.bubble = binding.letterBubble
        binding.letterBubble.backgroundTintList = ColorStateList.valueOf(OmnioColors.CONTACTS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        OmnioFavourites.addListener(favouritesListener)
        OmnioFavourites.reload(context)
        try {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
        } catch (ignored: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        OmnioFavourites.removeListener(favouritesListener)
        try {
            context.contentResolver.unregisterContentObserver(contactsObserver)
        } catch (ignored: Exception) {
        }
        handler.removeCallbacks(reloadSoon)
        super.onDetachedFromWindow()
    }

    // ---- MyViewPagerFragment ----

    override fun setupFragment() {
        binding.fragmentPlaceholder2.setOnClickListener {
            if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                activity?.launchCreateNewContactIntent()
            } else {
                activity?.handlePermission(PERMISSION_READ_CONTACTS) { granted -> if (granted) refreshItems() }
            }
        }
        buildAdapter()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        this.textColor = textColor
        binding.fragmentPlaceholder.setTextColor(textColor)
        binding.fragmentPlaceholder2.setTextColor(OmnioColors.ON_ACCENT)
        binding.fragmentPlaceholder2.backgroundTintList = ColorStateList.valueOf(OmnioColors.CONTACTS)
        binding.letterBar.textColor = textColor
        outerBinding.omnioFavouritesLabel.setTextColor(textColor)
        adapter?.textColor = textColor
        adapter?.notifyDataSetChanged()
        showFavouritesStrip()
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            everyone = emptyList()
            show()
            callback?.invoke()
            return
        }
        if (loading) {
            reloadAgain = true
            callback?.invoke()
            return
        }
        loading = true
        val ignored = context.baseConfig.ignoredContactSources.toSet()
        val sortingNow = sorting
        ensureBackgroundThread {
            val people = OmnioPeople.sort(OmnioPeople.load(context, ignored), sortingNow)
            val tags = OmnioAppTags.load(context)
            handler.post {
                loading = false
                everyone = people
                appTags = tags
                adapter?.appTags = tags
                show()
                showFavouritesStrip()
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
        outerBinding.omnioFavouritesStrip.visibility = if (searchQuery.isEmpty()) View.VISIBLE else View.GONE
        if (searchQuery.isNotEmpty()) outerBinding.contactsAppbar.setExpanded(true, false)
        show()
    }

    override fun onSearchClosed() {
        searchQuery = ""
        outerBinding.omnioFavouritesStrip.visibility = View.VISIBLE
        show()
    }

    /** Rebuilds the list after the display style changes (View button or Settings). */
    fun displayStyleChanged() {
        buildAdapter()
        show()
    }

    // ---- the list ----

    private fun buildAdapter() {
        val host = activity ?: return
        val style = OmnioDisplay.getStyle(context)
        adapter = OmnioContactsAdapter(
            style = style,
            onClick = { person ->
                if (style == OmnioDisplay.STYLE_SPEED_DIAL) OmnioContactActions.call(host, person) else OmnioContactActions.open(host, person)
            },
            onPhoto = { OmnioContactActions.open(host, it) },
            onLongClick = { person, _ -> OmnioContactActions.showMenu(host, person) },
            onCall = { OmnioContactActions.call(host, it) },
            onText = { OmnioContactActions.text(host, it) },
        ).apply {
            textColor = this@ContactsFragment.textColor
            appTags = this@ContactsFragment.appTags
            density = OmnioDisplay.getDensity(context)
            firstNameOnly = OmnioDisplay.isFirstNameOnly(context)
            surnameFirst = context.baseConfig.startNameWithSurname
            columns = OmnioDisplay.getColumns(context, style)
        }
        val list = binding.fragmentList
        list.layoutManager = if (OmnioDisplay.isGrid(style)) {
            GridLayoutManager(context, OmnioDisplay.getColumns(context, style))
        } else {
            LinearLayoutManager(context)
        }
        list.setPadding(dp(if (OmnioDisplay.isGrid(style)) 10 else 0), list.paddingTop, dp(if (OmnioDisplay.isGrid(style)) 10 else 20), list.paddingBottom)
        list.itemAnimator = null
        list.adapter = adapter
        binding.letterBar.onLetter = { letter ->
            val position = adapter?.positionOf(letter, ::letterOf) ?: -1
            if (position >= 0) (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun show() {
        val current = adapter ?: return
        val allowed = context.hasPermission(PERMISSION_READ_CONTACTS)
        val visible = if (searchQuery.isEmpty()) everyone else everyone.filter { OmnioPeople.matches(it, searchQuery) }
            .sortedByDescending { OmnioPeople.plain(it.name).startsWith(OmnioPeople.plain(searchQuery), true) }
        current.surnameFirst = context.baseConfig.startNameWithSurname
        current.submit(visible, ::letterOf, showHeaders = OmnioDisplay.hasHeaders(OmnioDisplay.getStyle(context)), highlight = searchQuery)

        val letters = if (current.isGrid || searchQuery.isNotEmpty()) emptyList() else visible.map(::letterOf).distinct()
        binding.letterBar.letters = letters
        binding.letterBar.visibility = if (letters.size > 1) View.VISIBLE else View.GONE

        val empty = visible.isEmpty() && !loading
        binding.fragmentPlaceholderHolder.visibility = if (empty) View.VISIBLE else View.GONE
        binding.fragmentPlaceholder.text = context.getString(
            when {
                !allowed -> R.string.could_not_access_contacts
                searchQuery.isNotEmpty() -> R.string.omnio_no_contact_matches
                else -> R.string.no_contacts_found
            }
        )
        binding.fragmentPlaceholder2.text = context.getString(if (allowed) R.string.create_new_contact else R.string.request_access)
        binding.fragmentPlaceholder2.visibility = if (searchQuery.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- favourites row ----

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun isFavourite(person: OmnioPeople.Person) =
        if (OmnioFavourites.isLoaded) OmnioFavourites.isFavourite(person.contactId) else person.starred

    /** "New" button plus a scrolling row of favourites; redraws the moment favourites change. */
    private fun showFavouritesStrip() {
        val host = activity as? SimpleActivity ?: return
        val holder = outerBinding.omnioFavouritesHolder
        holder.removeAllViews()
        val favourites = OmnioPeople.favourites(context, everyone)
        outerBinding.omnioFavouritesLabel.visibility = if (favourites.isEmpty()) View.GONE else View.VISIBLE
        holder.addView(tile(context.getString(R.string.omnio_new), null) { host.launchCreateNewContactIntent() })
        favourites.forEach { person -> holder.addView(tile(person.firstName, person) { OmnioContactActions.open(host, person) }) }
    }

    private fun tile(label: String, person: OmnioPeople.Person?, onClick: () -> Unit): LinearLayout {
        val size = dp(56)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
            if (person != null) setOnLongClickListener {
                (activity as? SimpleActivity)?.let { OmnioContactActions.showMenu(it, person) }
                true
            }
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                if (person == null) {
                    setImageResource(com.omnioone.commons.R.drawable.ic_plus_vector)
                    imageTintList = ColorStateList.valueOf(OmnioColors.ON_ACCENT)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = context.getDrawable(R.drawable.omnio_circle_bg)
                    backgroundTintList = ColorStateList.valueOf(OmnioColors.CONTACTS)
                } else {
                    OmnioDisplay.loadAvatar(this, person.photo, person.name, size / 2)
                }
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(if (person == null) OmnioColors.CONTACTS else textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(6), dp(4), 0)
            })
        }
    }
}
