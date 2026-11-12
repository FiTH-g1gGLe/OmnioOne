package com.omnioone.app.activities

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.omnioone.app.R
import com.omnioone.app.adapters.OmnioTabsAdapter
import com.omnioone.app.core.Contact
import com.omnioone.app.core.ContactSource
import com.omnioone.app.core.OmnioActivity
import com.omnioone.app.core.OmnioColors
import com.omnioone.app.core.OmnioContactStore
import com.omnioone.app.core.PERMISSION_READ_CONTACTS
import com.omnioone.app.core.PERMISSION_WRITE_CALL_LOG
import com.omnioone.app.core.REQUEST_CODE_SET_DEFAULT_CALLER_ID
import com.omnioone.app.core.REQUEST_CODE_SET_DEFAULT_DIALER
import com.omnioone.app.core.SORT_BY_CUSTOM
import com.omnioone.app.core.SORT_BY_DATE_CREATED
import com.omnioone.app.core.SORT_BY_FIRST_NAME
import com.omnioone.app.core.SORT_BY_FULL_NAME
import com.omnioone.app.core.SORT_BY_MIDDLE_NAME
import com.omnioone.app.core.SORT_BY_SURNAME
import com.omnioone.app.core.SORT_DESCENDING
import com.omnioone.app.core.CONTACTS_GRID_MAX_COLUMNS_COUNT
import com.omnioone.app.core.TAB_CALL_HISTORY
import com.omnioone.app.core.TAB_CONTACTS
import com.omnioone.app.core.TAB_FAVORITES
import com.omnioone.app.core.TAB_LAST_USED
import com.omnioone.app.core.VIEW_TYPE_GRID
import com.omnioone.app.core.VIEW_TYPE_LIST
import com.omnioone.app.core.baseConfig
import com.omnioone.app.core.beGoneIf
import com.omnioone.app.core.ensureBackgroundThread
import com.omnioone.app.core.hideKeyboard
import com.omnioone.app.core.isDefaultDialer
import com.omnioone.app.core.isQPlus
import com.omnioone.app.core.toast
import com.omnioone.app.core.viewBinding
import com.omnioone.app.databinding.ActivityMainBinding
import com.omnioone.app.dialogs.OmnioDisplayDialog
import com.omnioone.app.fragments.OmnioContactsTab
import com.omnioone.app.fragments.OmnioFavouritesTab
import com.omnioone.app.fragments.OmnioMessagesTab
import com.omnioone.app.fragments.OmnioRecentsTab
import com.omnioone.app.fragments.OmnioTab
import com.omnioone.app.helpers.OmnioCallLog
import com.omnioone.app.helpers.OmnioDisplay
import com.omnioone.app.helpers.OmnioFavourites
import com.omnioone.app.helpers.OmnioSheet
import com.omnioone.app.messaging.OmnioNewMessageActivity
import com.omnioone.app.phone.OPEN_DIAL_PAD_AT_LAUNCH
import com.omnioone.app.phone.clearMissedCalls
import com.omnioone.app.phone.config
import com.omnioone.app.phone.handleFullScreenNotificationsPermission
import com.omnioone.app.phone.omnioPhoneTabs
import com.omnioone.app.phone.openNewContact
import me.grantland.widget.AutofitHelper

/**
 * Omnio One's main screen: the search bar, the pages (Contacts, Favourites, Recents, Messages)
 * with their tab bar, and the round button that opens the keypad or a new message.
 */
class OmnioMainActivity : OmnioActivity() {

    /** One tab in the bar: what it is, its icon, its name and its colour. */
    private data class TabSpec(val kind: Int, val icon: Int, val label: Int, val colour: Int)

    private val binding by viewBinding(ActivityMainBinding::inflate)
    private var tabs = listOf<TabSpec>()

    private var keypadOpenedAtStart = false
    private var shownTabs = 0
    private var surnameFirst = false
    private var displaySignature: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.mainMenu), padBottomImeAndSystem = listOf(binding.mainTabsHolder))
        setupSearchBar()
        keypadOpenedAtStart = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        Contact.sorting = config.sorting
        buildTabs()
        startPages()

        // straight from setup: everything has just been offered, so don't ask again now
        if (intent.getBooleanExtra(EXTRA_FROM_SETUP, false)) {
            if (isDefaultDialer()) askForContacts {}
        } else if (isDefaultDialer()) {
            // one permission at a time: contacts first, then call notifications
            askForContacts {
                handleFullScreenNotificationsPermission { granted ->
                    if (!granted) toast(R.string.notifications_disabled)
                    offerDrawOverApps()
                }
            }
        } else {
            launchSetDefaultDialerIntent()
        }
        if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) setDefaultCallerIdApp()
    }

    override fun onResume() {
        super.onResume()
        // tabs switched on or off in Settings: build the screen again
        if (shownTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            recreate()
            return
        }
        OmnioFavourites.reload(this)
        val display = OmnioDisplay.signature(this)
        if (displaySignature != null && displaySignature != display) contactsTab()?.displayStyleChanged()
        displaySignature = display
        if (surnameFirst != config.startNameWithSurname) {
            surnameFirst = config.startNameWithSurname
            config.sorting = if (surnameFirst) SORT_BY_SURNAME else SORT_BY_FIRST_NAME
        }
        binding.mainMenu.updateColors()
        paintTabs()
        updateRoundButton()
        if (!binding.mainMenu.isSearchOpen) showPages(openLastTab = true)
        addKeypadShortcut()
    }

    override fun onPause() {
        super.onPause()
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, keypadOpenedAtStart)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        when {
            // Omnio works without being the phone app too, so carry on either way
            requestCode == REQUEST_CODE_SET_DEFAULT_DIALER -> askForContacts {}
            requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK -> {
                toast(R.string.must_make_default_caller_id_app, Toast.LENGTH_LONG)
                baseConfig.blockUnknownNumbers = false
                baseConfig.blockHiddenNumbers = false
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        if (!binding.mainMenu.isSearchOpen) return false
        binding.mainMenu.closeSearch()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_OPEN_TAB, -1).takeIf { it in tabs.indices }?.let { binding.mainTabsHolder.getTabAt(it)?.select() }
    }

    // ---- start-up prompts ----

    private fun askForContacts(then: () -> Unit) = handlePermission(PERMISSION_READ_CONTACTS) { granted ->
        if (granted) reloadPages()
        then()
    }

    /** Calls show more reliably when Omnio may draw over other apps: ask once. */
    private fun offerDrawOverApps() {
        if (config.wasOverlaySnackbarConfirmed || Settings.canDrawOverlays(this)) return
        OmnioSheet.confirm(
            this, getString(R.string.omnio_overlay_title), getString(R.string.allow_displaying_over_other_apps),
            positive = getString(R.string.omnio_overlay_allow),
            negative = getString(R.string.omnio_not_now),
            onNegative = { config.wasOverlaySnackbarConfirmed = true },
        ) {
            config.wasOverlaySnackbarConfirmed = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (ignored: Exception) {
            }
        }
    }

    // ---- search bar and menu ----

    private fun setupSearchBar() = binding.mainMenu.apply {
        requireToolbar().inflateMenu(R.menu.menu)
        setupMenu()
        onSearchClosedListener = { allTabs().forEach { it.onSearchQueryChanged("") } }
        onSearchTextChangedListener = { text -> currentTab()?.onSearchQueryChanged(text) }
        requireToolbar().setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.clear_call_history -> clearCallHistory()
                R.id.create_new_contact -> openNewContact()
                R.id.sort -> pickSorting(withCustomOrder = currentTab() is OmnioFavouritesTab)
                R.id.filter -> pickAccounts()
                R.id.settings -> open(OmnioSettingsActivity::class.java)
                R.id.change_view_type -> if (currentTab() is OmnioContactsTab) pickDisplayStyle() else pickFavouritesView()
                R.id.column_count -> pickColumns()
                R.id.about -> open(OmnioAboutActivity::class.java)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    /** Only the menu items that make sense on the open page. */
    private fun updateMenu() {
        val page = currentTab()
        binding.mainMenu.requireToolbar().menu.apply {
            val people = page is OmnioContactsTab || page is OmnioFavouritesTab
            findItem(R.id.clear_call_history).isVisible = page is OmnioRecentsTab
            findItem(R.id.sort).isVisible = people
            findItem(R.id.filter).isVisible = people
            findItem(R.id.create_new_contact).isVisible = page is OmnioContactsTab
            findItem(R.id.change_view_type).isVisible = people
            findItem(R.id.column_count).isVisible = page is OmnioFavouritesTab && config.viewType == VIEW_TYPE_GRID
        }
    }

    private fun open(screen: Class<*>) {
        hideKeyboard()
        startActivity(Intent(applicationContext, screen))
    }

    // ---- tabs ----

    private fun buildTabs() {
        shownTabs = config.showTabs
        surnameFirst = config.startNameWithSurname
        tabs = omnioPhoneTabs.filter { shownTabs and it != 0 }.map { kind ->
            when (kind) {
                TAB_CONTACTS -> TabSpec(kind, R.drawable.ic_person_vector, R.string.contacts_tab, OmnioColors.CONTACTS)
                TAB_FAVORITES -> TabSpec(kind, R.drawable.ic_star_vector, R.string.favorites_tab, OmnioColors.FAVOURITES)
                else -> TabSpec(kind, R.drawable.ic_clock_filled_vector, R.string.call_history_tab, OmnioColors.CALLS)
            }
        } + TabSpec(TAB_MESSAGES, R.drawable.ic_omnio_chat, R.string.omnio_messages_tab, OmnioColors.MESSAGES)

        binding.viewPager.adapter = null
        binding.mainTabsHolder.removeAllTabs()
        tabs.forEach { spec ->
            binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
                customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageResource(spec.icon)
                customView?.findViewById<TextView>(R.id.tab_item_label)?.let { label ->
                    label.setText(spec.label)
                    AutofitHelper.create(label)
                }
                binding.mainTabsHolder.addTab(this, false)
            }
        }
        binding.mainTabsHolder.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = tabChosen(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = paintTab(tab.position, false)
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.mainTabsHolder.beGoneIf(tabs.size == 1)
        binding.mainTabsHolder.setBackgroundColor(OmnioColors.SHEET)
        paintTabs()
    }

    private fun tabChosen(position: Int) {
        // switching tab clears the search
        if (binding.mainMenu.isSearchOpen || binding.mainMenu.getCurrentQuery().isNotEmpty()) binding.mainMenu.closeSearch()
        binding.viewPager.currentItem = position
        paintTab(position, true)
        updateRoundButton()
        updateMenu()
        if (tabs.getOrNull(position)?.kind == TAB_CALL_HISTORY) clearMissedCalls()
    }

    /** The open tab lights up in its own colour; the rest are dimmed. */
    private fun paintTab(position: Int, selected: Boolean) {
        val spec = tabs.getOrNull(position) ?: return
        val view = binding.mainTabsHolder.getTabAt(position)?.customView ?: return
        view.findViewById<ImageView>(R.id.tab_item_icon)?.apply {
            setColorFilter(if (selected) spec.colour else OmnioColors.TEXT_MUTED)
            alpha = if (selected) 1f else 0.75f
        }
        view.findViewById<TextView>(R.id.tab_item_label)?.setTextColor(if (selected) spec.colour else OmnioColors.TEXT_MUTED)
    }

    private fun paintTabs() = tabs.indices.forEach { paintTab(it, it == binding.viewPager.currentItem) }

    // ---- pages ----

    /** Wires up paging, the round button and the opening tab. */
    private fun startPages() {
        binding.viewPager.offscreenPageLimit = 3
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                binding.mainTabsHolder.getTabAt(position)?.takeIf { !it.isSelected }?.select()
                updateMenu()
                updateRoundButton()
            }
        })
        showPages(openLastTab = false)
        binding.viewPager.post { binding.mainTabsHolder.getTabAt(openingTab())?.select() }
        if (config.openDialPadAtLaunch && !keypadOpenedAtStart) {
            keypadOpenedAtStart = true
            open(OmnioKeypadActivity::class.java)
        }
    }

    private fun showPages(openLastTab: Boolean) {
        if (isDestroyed || isFinishing) return
        if (binding.viewPager.adapter == null) {
            binding.viewPager.adapter = OmnioTabsAdapter(this)
            binding.viewPager.currentItem = if (openLastTab) config.lastUsedViewPagerPage.coerceIn(0, tabs.lastIndex) else openingTab()
            binding.viewPager.post { reloadPages() }
        } else {
            reloadPages()
        }
        updateMenu()
    }

    private fun reloadPages() = allTabs().forEach { it.refreshItems() }

    /** Which tab to open on: a missed-call tap opens Recents, Messages links open Messages, otherwise your "Open on" choice. */
    private fun openingTab(): Int {
        intent.getIntExtra(EXTRA_OPEN_TAB, -1).takeIf { it in tabs.indices }?.let {
            intent.removeExtra(EXTRA_OPEN_TAB)
            return it
        }
        if (intent.action == Intent.ACTION_VIEW) tabs.indexOfFirst { it.kind == TAB_CALL_HISTORY }.takeIf { it >= 0 }?.let { return it }
        return when (val wanted = config.defaultTab) {
            TAB_LAST_USED -> config.lastUsedViewPagerPage.takeIf { it in tabs.indices } ?: 0
            else -> tabs.indexOfFirst { it.kind == wanted }.takeIf { it >= 0 } ?: 0
        }
    }

    private fun allTabs(): List<OmnioTab> = listOfNotNull(contactsTab(), favouritesTab(), recentsTab(), messagesTab())

    private fun currentTab(): OmnioTab? = when (tabs.getOrNull(binding.viewPager.currentItem)?.kind) {
        TAB_CONTACTS -> contactsTab()
        TAB_FAVORITES -> favouritesTab()
        TAB_CALL_HISTORY -> recentsTab()
        TAB_MESSAGES -> messagesTab()
        else -> null
    }

    private fun contactsTab(): OmnioContactsTab? = findViewById(R.id.contacts_fragment)
    private fun favouritesTab(): OmnioFavouritesTab? = findViewById(R.id.favorites_fragment)
    private fun recentsTab(): OmnioRecentsTab? = findViewById(R.id.recents_fragment)
    private fun messagesTab(): OmnioMessagesTab? = findViewById(R.id.messages_fragment)

    /** The round button: keypad on the phone tabs, new message on Messages. */
    private fun updateRoundButton() {
        val onMessages = tabs.getOrNull(binding.viewPager.currentItem)?.kind == TAB_MESSAGES
        binding.mainDialpadButton.setImageResource(if (onMessages) R.drawable.ic_plus_vector else R.drawable.ic_dialpad_vector)
        binding.mainDialpadButton.imageTintList = android.content.res.ColorStateList.valueOf(OmnioColors.TEXT)
        binding.mainDialpadButton.setOnClickListener {
            open(if (onMessages) OmnioNewMessageActivity::class.java else OmnioKeypadActivity::class.java)
        }
    }

    /** Long-press the app icon for a straight-to-keypad shortcut. */
    private fun addKeypadShortcut() {
        try {
            val manager = getSystemService(ShortcutManager::class.java) ?: return
            if (manager.dynamicShortcuts.any { it.id == KEYPAD_SHORTCUT }) return
            manager.removeDynamicShortcuts(listOf("launch_dialpad")) // the old shortcut, which pointed at the old keypad
            val label = getString(R.string.dialpad)
            manager.addDynamicShortcuts(listOf(
                ShortcutInfo.Builder(this, KEYPAD_SHORTCUT)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithResource(this, R.drawable.omnio_keypad_shortcut))
                    .setIntent(Intent(this, OmnioKeypadActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .build()
            ))
        } catch (ignored: Exception) {
        }
    }

    // ---- menu sheets ----

    private fun clearCallHistory() {
        OmnioSheet.confirm(
            this, getString(R.string.clear_call_history), getString(R.string.clear_history_confirmation) + "\n\n" + getString(R.string.cannot_be_undone),
            positive = getString(R.string.omnio_clear), mainColor = OmnioColors.MISSED,
        ) {
            handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
                if (granted) ensureBackgroundThread {
                    OmnioCallLog.clearAll(this)
                    runOnUiThread { recentsTab()?.refreshItems(invalidate = true) }
                }
            }
        }
    }

    /** Sort by first name, surname and so on, then A–Z or Z–A. Favourites can also use your own drag order. */
    private fun pickSorting(withCustomOrder: Boolean) {
        val fields = listOf(
            SORT_BY_FIRST_NAME to R.string.first_name, SORT_BY_MIDDLE_NAME to R.string.middle_name, SORT_BY_SURNAME to R.string.surname,
            SORT_BY_FULL_NAME to R.string.full_name, SORT_BY_DATE_CREATED to R.string.date_created,
        ) + if (withCustomOrder) listOf(SORT_BY_CUSTOM to R.string.custom) else emptyList()
        val current = config.sorting
        val currentField = if (withCustomOrder && config.isCustomOrderSelected) SORT_BY_CUSTOM else fields.firstOrNull { current and it.first != 0 }?.first ?: SORT_BY_DATE_CREATED
        OmnioSheet.list(
            this, getString(R.string.sort_by), null,
            fields.map { OmnioSheet.Item(getString(it.second), trailing = if (it.first == currentField) "✓" else null) }
        ) { picked ->
            val field = fields[picked].first
            if (field == SORT_BY_CUSTOM) {
                config.isCustomOrderSelected = true
                sortingChanged()
                return@list
            }
            val descending = current and SORT_DESCENDING != 0
            val orders = listOf(false to R.string.ascending, true to R.string.descending)
            OmnioSheet.list(
                this, getString(R.string.sort_by), getString(fields[picked].second),
                orders.map { OmnioSheet.Item(getString(it.second), trailing = if (it.first == descending) "✓" else null) }
            ) { order ->
                if (withCustomOrder) config.isCustomOrderSelected = false
                config.sorting = field or (if (orders[order].first) SORT_DESCENDING else 0)
                sortingChanged()
            }
        }
    }

    private fun sortingChanged() {
        Contact.sorting = config.sorting
        val again = { if (binding.mainMenu.isSearchOpen) currentTab()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery()) }
        favouritesTab()?.refreshItems { again() }
        contactsTab()?.refreshItems { again() }
    }

    private fun pickDisplayStyle() {
        OmnioDisplayDialog(this) { surnameChanged ->
            if (surnameChanged) {
                surnameFirst = config.startNameWithSurname
                config.sorting = if (surnameFirst) SORT_BY_SURNAME else SORT_BY_FIRST_NAME
                Contact.sorting = config.sorting
            }
            contactsTab()?.displayStyleChanged()
            favouritesTab()?.refreshItems()
        }
    }

    private fun pickFavouritesView() {
        val choices = listOf(VIEW_TYPE_GRID to R.string.omnio_view_grid, VIEW_TYPE_LIST to R.string.omnio_view_list)
        OmnioSheet.list(
            this, getString(R.string.omnio_view), null,
            choices.map { OmnioSheet.Item(getString(it.second), trailing = if (it.first == config.viewType) "✓" else null) }
        ) { picked ->
            config.viewType = choices[picked].first
            updateMenu()
            favouritesTab()?.refreshItems()
        }
    }

    private fun pickColumns() {
        val counts = (1..CONTACTS_GRID_MAX_COLUMNS_COUNT).toList()
        OmnioSheet.list(
            this, getString(R.string.column_count), null,
            counts.map { OmnioSheet.Item(resources.getQuantityString(R.plurals.column_counts, it, it), trailing = if (it == config.contactsGridColumnCount) "✓" else null) }
        ) { picked ->
            if (config.contactsGridColumnCount == counts[picked]) return@list
            config.contactsGridColumnCount = counts[picked]
            favouritesTab()?.columnCountChanged()
        }
    }

    /** Choose which accounts' contacts to show: tap one to show or hide it. */
    private fun pickAccounts() = ensureBackgroundThread {
        val sources = OmnioContactStore(this).getContactSources().sortedByDescending { it.count }
        runOnUiThread { showAccounts(sources) }
    }

    private fun showAccounts(sources: List<ContactSource>) {
        if (sources.isEmpty()) return
        val hidden = config.ignoredContactSources
        OmnioSheet.list(
            this, getString(R.string.omnio_filter_sources), getString(R.string.omnio_filter_sources_help),
            sources.map {
                val off = it.getFullIdentifier() in hidden
                OmnioSheet.Item(
                    "${it.publicName.ifEmpty { it.name }}  ·  ${it.count}",
                    trailing = if (off) getString(R.string.omnio_hidden) else "✓",
                    trailingColor = if (off) OmnioColors.TEXT_MUTED else OmnioColors.MESSAGES,
                )
            }
        ) { picked ->
            val id = sources[picked].getFullIdentifier()
            val now = HashSet(config.ignoredContactSources)
            // always leave at least one account showing
            if (id in now) now.remove(id) else if (sources.count { it.getFullIdentifier() !in now } > 1) now.add(id)
            config.ignoredContactSources = now
            val again = { if (binding.mainMenu.isSearchOpen) currentTab()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery()) }
            favouritesTab()?.refreshItems { again() }
            contactsTab()?.refreshItems { again() }
            recentsTab()?.refreshItems { again() }
            showAccounts(sources)
        }
    }

    companion object {
        /** Open on a particular tab (used by the Messages screens' tab bar). */
        const val EXTRA_OPEN_TAB = "omnio_open_tab"
        const val EXTRA_FROM_SETUP = "omnio_from_setup"
        private const val TAB_MESSAGES = -1
        private const val KEYPAD_SHORTCUT = "omnio_keypad"
    }
}
