package com.omnioone.app.activities

import com.omnioone.app.core.OmnioColors
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.ViewPager
import com.google.android.material.snackbar.Snackbar
import me.grantland.widget.AutofitHelper
import com.omnioone.app.core.*
import com.omnioone.app.core.RadioItem
import com.omnioone.app.core.Contact
import com.omnioone.app.BuildConfig
import com.omnioone.app.R
import com.omnioone.app.adapters.ViewPagerAdapter
import com.omnioone.app.databinding.ActivityMainBinding
import com.omnioone.app.dialogs.ChangeSortingDialog
import com.omnioone.app.extensions.clearMissedCalls
import com.omnioone.app.extensions.config
import com.omnioone.app.extensions.handleFullScreenNotificationsPermission
import com.omnioone.app.extensions.launchCreateNewContactIntent
import com.omnioone.app.fragments.ContactsFragment
import com.omnioone.app.fragments.FavoritesFragment
import com.omnioone.app.fragments.MyViewPagerFragment
import com.omnioone.app.fragments.RecentsFragment
import com.omnioone.app.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import com.omnioone.app.helpers.tabsList
import com.omnioone.app.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

private const val OMNIO_MESSAGES_GREEN = OmnioColors.MESSAGES

class MainActivity : SimpleActivity() {
    private var lastDisplaySignature: String? = null
    
    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var launchedDialer = false
    private var storedShowTabs = 0
    private var storedFontSize = 0
    private var storedStartNameWithSurname = false

    override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()
        setupEdgeToEdge(padTopSystem = listOf(binding.mainMenu), padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        EventBus.getDefault().register(this)
        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        if (isDefaultDialer()) {
            checkContactPermissions()

            if (!config.wasOverlaySnackbarConfirmed && !Settings.canDrawOverlays(this)) {
                val snackbar = Snackbar.make(
                    binding.mainHolder,
                    R.string.allow_displaying_over_other_apps,
                    Snackbar.LENGTH_INDEFINITE
                ).setAction(R.string.ok) {
                    config.wasOverlaySnackbarConfirmed = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }

                snackbar.setBackgroundTint(getProperBackgroundColor().darkenColor())
                snackbar.setTextColor(getProperTextColor())
                snackbar.setActionTextColor(getProperTextColor())
                snackbar.show()
            }

            handleFullScreenNotificationsPermission { granted ->
                if (!granted) {
                    toast(com.omnioone.app.R.string.notifications_disabled)
                }
            }
        } else {
            launchSetDefaultDialerIntent()
        }

        if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
            setDefaultCallerIdApp()
        }

        setupTabs()
        Contact.sorting = config.sorting
    }

    override fun onResume() {
        super.onResume()
        com.omnioone.app.helpers.OmnioFavourites.reload(this)
        // display style may have been changed from Omnio Settings
        val displayNow = com.omnioone.app.helpers.OmnioDisplay.signature(this)
        if (lastDisplaySignature != null && lastDisplaySignature != displayNow) getContactsFragment()?.displayStyleChanged()
        lastDisplaySignature = displayNow
        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }
        updateMenuColors()
        val properPrimaryColor = getProperPrimaryColor()
        updateFab()

        setupTabColors()

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            getContactsFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            getFavoritesFragment()?.startNameWithSurnameChanged(configStartNameWithSurname)
            storedStartNameWithSurname = config.startNameWithSurname
        }

        if (!binding.mainMenu.isSearchOpen) {
            refreshItems(true)
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        checkShortcuts()
        Handler().postDelayed({
            getRecentsFragment()?.refreshItems()
        }, 2000)
    }

    override fun onPause() {
        super.onPause()
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        binding.mainMenu.requireToolbar().menu.apply {
            val onMessages = currentFragment != null && currentFragment == getMessagesFragment()
            findItem(R.id.clear_call_history).isVisible = currentFragment == getRecentsFragment()
            findItem(R.id.sort).isVisible = currentFragment != getRecentsFragment() && !onMessages
            findItem(R.id.filter).isVisible = currentFragment != getRecentsFragment() && !onMessages
            findItem(R.id.create_new_contact).isVisible = currentFragment == getContactsFragment()
            findItem(R.id.change_view_type).isVisible = currentFragment == getFavoritesFragment() || currentFragment == getContactsFragment()
            findItem(R.id.column_count).isVisible = currentFragment == getFavoritesFragment() && config.viewType == VIEW_TYPE_GRID
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            requireToolbar().inflateMenu(R.menu.menu)
            toggleHideOnScroll(false)
            setupMenu()

            onSearchClosedListener = {
                getAllFragments().forEach {
                    it?.onSearchQueryChanged("")
                }
            }

            onSearchTextChangedListener = { text ->
                getCurrentFragment()?.onSearchQueryChanged(text)
            }

            requireToolbar().setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.clear_call_history -> clearCallHistory()
                    R.id.create_new_contact -> launchCreateNewContactIntent()
                    R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                    R.id.filter -> showFilterDialog()
                    R.id.settings -> launchSettings()
                    R.id.change_view_type -> if (getCurrentFragment() is ContactsFragment) showOmnioDisplayDialog() else changeViewType()
                    R.id.column_count -> changeColumnCount()
                    R.id.about -> launchAbout()
                    else -> return@setOnMenuItemClickListener false
                }
                return@setOnMenuItemClickListener true
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..CONTACTS_GRID_MAX_COLUMNS_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.contactsGridColumnCount
        pickOne(getString(R.string.column_count), items, currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.contactsGridColumnCount = newColumnCount
                getFavoritesFragment()?.columnCountChanged()
            }
        }
    }

    /** Omnio One: contact display styles for the Contacts tab. */
    private fun showOmnioDisplayDialog() {
        com.omnioone.app.dialogs.OmnioDisplayDialog(this) { surnameChanged ->
            if (surnameChanged) {
                storedStartNameWithSurname = config.startNameWithSurname
                getContactsFragment()?.startNameWithSurnameChanged(config.startNameWithSurname)
            }
            getContactsFragment()?.displayStyleChanged()
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun changeViewType() {
        // Favourites: grid or list
        val options = listOf(RadioItem(VIEW_TYPE_GRID, getString(R.string.omnio_view_grid)), RadioItem(VIEW_TYPE_LIST, getString(R.string.omnio_view_list)))
        pickOne(getString(R.string.omnio_view), options, config.viewType) {
            config.viewType = it as Int
            refreshMenuItems()
            getFavoritesFragment()?.refreshItems()
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        confirm(confirmationText) {
            handlePermission(com.omnioone.app.core.PERMISSION_WRITE_CALL_LOG) { granted ->
                if (granted) com.omnioone.app.core.ensureBackgroundThread {
                    com.omnioone.app.helpers.OmnioCallLog.clearAll(this)
                    runOnUiThread {
                        getRecentsFragment()?.refreshItems(invalidate = true)
                    }
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        // the long-press launcher shortcut to the keypad
        try {
            if (shortcutManager.dynamicShortcuts.none { it.id == "launch_dialpad" }) {
                shortcutManager.addDynamicShortcuts(listOf(getLaunchDialpadShortcut(OmnioColors.CALLS)))
            }
        } catch (ignored: Exception) {
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.dialpad)
        val drawable = resources.getDrawable(R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    /** Omnio One: each tab lights up in its own brand colour. */
    private fun tintOmnioTab(view: android.view.View?, position: Int, selected: Boolean = true) {
        val visibleTabs = tabsList.filter { config.showTabs and it != 0 }
        val color = when (visibleTabs.getOrNull(position)) {
            TAB_CONTACTS -> OmnioColors.CONTACTS
            TAB_FAVORITES -> OmnioColors.FAVOURITES
            TAB_CALL_HISTORY -> OmnioColors.CALLS
            else -> if (position == visibleTabs.size) OmnioColors.MESSAGES else return
        }
        view?.findViewById<ImageView>(R.id.tab_item_icon)?.apply {
            applyColorFilter(color)
            alpha = if (selected) 1f else 0.75f
        }
        if (selected) view?.findViewById<TextView>(R.id.tab_item_label)?.setTextColor(color)
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true, getSelectedTabDrawableIds()[binding.viewPager.currentItem])
        tintOmnioTab(activeView, binding.viewPager.currentItem)

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
            tintOmnioTab(inactiveView, index, selected = false)
        }

        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until pageTabCount).filter { it != activeIndex }

    // Omnio One: the Messages tab sits after the phone tabs
    private val pageTabCount: Int
        get() = tabsList.count { config.showTabs and it != 0 } + 1

    private fun addMessagesTab() {
        binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
            customView?.findViewById<ImageView>(R.id.tab_item_icon)?.apply {
                setImageResource(R.drawable.ic_omnio_chat)
                applyColorFilter(OMNIO_MESSAGES_GREEN)
                alpha = 0.75f
            }
            customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getString(R.string.omnio_messages_tab)
            AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
            binding.mainTabsHolder.addTab(this, false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val omnioTab = intent.getIntExtra("omnio_open_tab", -1)
        if (omnioTab in 0 until pageTabCount) {
            binding.mainTabsHolder.getTabAt(omnioTab)?.select()
        }
    }

    private fun getSelectedTabDrawableIds(): List<Int> {
        val showTabs = config.showTabs
        val icons = mutableListOf<Int>()

        if (showTabs and TAB_CONTACTS != 0) {
            icons.add(R.drawable.ic_person_vector)
        }

        if (showTabs and TAB_FAVORITES != 0) {
            icons.add(R.drawable.ic_star_vector)
        }

        if (showTabs and TAB_CALL_HISTORY != 0) {
            icons.add(R.drawable.ic_clock_filled_vector)
        }

        icons.add(R.drawable.ic_omnio_chat)

        return icons
    }

    // Omnio One: always use the flat, filled icons so the tab bar looks like a matching set
    private fun getDeselectedTabDrawableIds(): ArrayList<Int> = ArrayList(getSelectedTabDrawableIds())

    private fun initFragments() {
        binding.viewPager.offscreenPageLimit = 3
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                binding.mainTabsHolder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                refreshMenuItems()
                updateFab()
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        binding.mainTabsHolder.onGlobalLayout {
            Handler().postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    wantedTab = pageTabCount - 1
                }

                // Omnio One: coming back from the Messages screen's tab bar
                val omnioTab = intent.getIntExtra("omnio_open_tab", -1)
                if (omnioTab in 0 until pageTabCount) {
                    wantedTab = omnioTab
                    intent.removeExtra("omnio_open_tab")
                }

                binding.mainTabsHolder.getTabAt(wantedTab)?.select()
                refreshMenuItems()
            }, 100L)
        }

        binding.mainDialpadButton.setOnClickListener {
            launchDialpad()
        }

        binding.viewPager.onGlobalLayout {
            refreshMenuItems()
        }

        if (config.openDialPadAtLaunch && !launchedDialer) {
            launchDialpad()
            launchedDialer = true
        }
    }

    private fun setupTabs() {
        binding.viewPager.adapter = null
        binding.mainTabsHolder.removeAllTabs()
        tabsList.forEachIndexed { index, value ->
            if (config.showTabs and value != 0) {
                binding.mainTabsHolder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
                    customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(index))
                    customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(index)
                    AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                    binding.mainTabsHolder.addTab(this)
                }
            }
        }
        addMessagesTab()

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false, getDeselectedTabDrawableIds()[it.position])
                tintOmnioTab(it.customView, it.position, selected = false)
            },
            tabSelectedAction = {
                // Omnio One: switching tab clears the search
                if (binding.mainMenu.isSearchOpen || binding.mainMenu.getCurrentQuery().isNotEmpty()) {
                    binding.mainMenu.closeSearch()
                }
                getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true, getSelectedTabDrawableIds()[it.position])
                tintOmnioTab(it.customView, it.position)

                updateFab()
                val lastPosition = tabsList.count { tab -> config.showTabs and tab != 0 } - 1
                if (it.position == lastPosition && config.showTabs and TAB_CALL_HISTORY > 0) {
                    clearMissedCalls()
                }
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount == 1)
        storedShowTabs = config.showTabs
        storedStartNameWithSurname = config.startNameWithSurname
    }

    private fun getTabIcon(position: Int): Drawable {
        val drawableId = when (position) {
            0 -> R.drawable.ic_person_vector
            1 -> R.drawable.ic_star_vector
            else -> R.drawable.ic_clock_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(position: Int): String {
        val stringId = when (position) {
            0 -> R.string.contacts_tab
            1 -> R.string.favorites_tab
            else -> R.string.call_history_tab
        }

        return resources.getString(stringId)
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.apply {
            if (viewPager.adapter == null) {
                viewPager.adapter = ViewPagerAdapter(this@MainActivity)
                viewPager.currentItem = if (openLastTab) config.lastUsedViewPagerPage else getDefaultTab()
                viewPager.onGlobalLayout {
                    refreshFragments()
                }
            } else {
                refreshFragments()
            }
        }
    }

    private fun launchDialpad() {
        Intent(applicationContext, DialpadActivity::class.java).apply {
            startActivity(this)
        }
    }

    fun refreshFragments() {
        getContactsFragment()?.refreshItems()
        getFavoritesFragment()?.refreshItems()
        getRecentsFragment()?.refreshItems()
        getMessagesFragment()?.refreshItems()
    }

    private fun getAllFragments(): ArrayList<MyViewPagerFragment<*>?> {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment<*>?>()

        if (showTabs and TAB_CONTACTS > 0) {
            fragments.add(getContactsFragment())
        }

        if (showTabs and TAB_FAVORITES > 0) {
            fragments.add(getFavoritesFragment())
        }

        if (showTabs and TAB_CALL_HISTORY > 0) {
            fragments.add(getRecentsFragment())
        }

        fragments.add(getMessagesFragment())

        return fragments
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? = getAllFragments().getOrNull(binding.viewPager.currentItem)

    private fun getContactsFragment(): ContactsFragment? = findViewById(R.id.contacts_fragment)

    private fun getFavoritesFragment(): FavoritesFragment? = findViewById(R.id.favorites_fragment)

    private fun getRecentsFragment(): RecentsFragment? = findViewById(R.id.recents_fragment)

    private fun getMessagesFragment(): com.omnioone.app.fragments.MessagesFragment? = findViewById(R.id.messages_fragment)

    /** The round button: keypad on the phone tabs, "new message" on Messages. */
    private fun updateFab() {
        val onMessages = getCurrentFragment() == getMessagesFragment() && getMessagesFragment() != null
        val icon = if (onMessages) com.omnioone.app.R.drawable.ic_plus_vector else R.drawable.ic_dialpad_vector
        binding.mainDialpadButton.setImageDrawable(resources.getColoredDrawableWithColor(icon, getProperPrimaryColor().getContrastColor()))
        binding.mainDialpadButton.setOnClickListener {
            if (onMessages) {
                startActivity(Intent(this, com.omnioone.app.messaging.OmnioNewMessageActivity::class.java))
            } else {
                launchDialpad()
            }
        }
    }

    private fun getDefaultTab(): Int {
        val showTabsMask = config.showTabs
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < pageTabCount) config.lastUsedViewPagerPage else 0
            TAB_CONTACTS -> 0
            TAB_FAVORITES -> if (showTabsMask and TAB_CONTACTS > 0) 1 else 0
            else -> {
                if (showTabsMask and TAB_CALL_HISTORY > 0) {
                    if (showTabsMask and TAB_CONTACTS > 0) {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            2
                        } else {
                            1
                        }
                    } else {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            1
                        } else {
                            0
                        }
                    }
                } else {
                    0
                }
            }
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, OmnioSettingsActivity::class.java))
    }

    private fun launchAbout() {
        hideKeyboard()
        startActivity(Intent(applicationContext, OmnioAboutActivity::class.java))
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    /** Choose which accounts' contacts to show: tap one to show or hide it. */
    private fun showFilterDialog() {
        com.omnioone.app.core.ensureBackgroundThread {
            val sources = com.omnioone.app.core.OmnioContactStore(this).getContactSources().sortedByDescending { it.count }
            runOnUiThread { pickContactSources(sources) }
        }
    }

    private fun pickContactSources(sources: List<com.omnioone.app.core.ContactSource>) {
        if (sources.isEmpty()) return
        val hidden = config.ignoredContactSources
        com.omnioone.app.helpers.OmnioSheet.list(
            this, getString(R.string.omnio_filter_sources), getString(R.string.omnio_filter_sources_help),
            sources.map {
                com.omnioone.app.helpers.OmnioSheet.Item(
                    "${it.publicName.ifEmpty { it.name }}  ·  ${it.count}",
                    trailing = if (it.getFullIdentifier() in hidden) getString(R.string.omnio_hidden) else "✓",
                    trailingColor = if (it.getFullIdentifier() in hidden) OmnioColors.TEXT_MUTED else OmnioColors.MESSAGES
                )
            }
        ) { picked ->
            val id = sources[picked].getFullIdentifier()
            val now = HashSet(config.ignoredContactSources)
            if (id in now) now.remove(id) else if (sources.count { it.getFullIdentifier() !in now } > 1) now.add(id)
            config.ignoredContactSources = now
            refreshAfterFilter()
            pickContactSources(sources)
        }
    }

    private fun refreshAfterFilter() {
        run {
            getFavoritesFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getContactsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }

            getRecentsFragment()?.refreshItems {
                if (binding.mainMenu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        getRecentsFragment()?.refreshItems()
    }
}
