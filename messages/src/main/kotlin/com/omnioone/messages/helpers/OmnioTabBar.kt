package com.omnioone.messages.helpers

import com.omnioone.commons.helpers.OmnioColors
import android.app.Activity
import android.content.Intent
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import com.omnioone.commons.extensions.applyColorFilter
import com.omnioone.commons.extensions.getProperTextColor
import com.omnioone.messages.R

/**
 * Omnio One: the same bottom tab bar as the phone screens, shown on the Messages screen,
 * so Contacts / Favourites / Recents / Messages feel like one app.
 */
object OmnioTabBar {
    const val EXTRA_OPEN_TAB = "omnio_open_tab"
    private const val PHONE_MAIN = "com.omnioone.app.activities.MainActivity"

    private data class Tab(val label: Int, val icon: Int, val color: Int)

    private val tabs = listOf(
        Tab(com.omnioone.commons.R.string.contacts_tab, com.omnioone.commons.R.drawable.ic_person_vector, OmnioColors.CONTACTS),
        Tab(com.omnioone.commons.R.string.favorites_tab, com.omnioone.commons.R.drawable.ic_star_vector, OmnioColors.FAVOURITES),
        Tab(com.omnioone.commons.R.string.call_history_tab, com.omnioone.commons.R.drawable.ic_clock_filled_vector, OmnioColors.CALLS),
        Tab(R.string.omnio_messages_label, R.drawable.msg_ic_omnio_chat, OmnioColors.MESSAGES),
    )

    fun attach(activity: Activity, tabLayout: TabLayout) {
        tabLayout.removeAllTabs()
        val textColor = activity.getProperTextColor()
        tabs.forEachIndexed { index, tab ->
            val selected = index == tabs.lastIndex
            tabLayout.newTab().setCustomView(com.omnioone.commons.R.layout.bottom_tablayout_item).apply {
                customView?.findViewById<ImageView>(com.omnioone.commons.R.id.tab_item_icon)?.apply {
                    setImageResource(tab.icon)
                    applyColorFilter(tab.color)
                    alpha = if (selected) 1f else 0.75f
                }
                customView?.findViewById<TextView>(com.omnioone.commons.R.id.tab_item_label)?.apply {
                    text = activity.getString(tab.label)
                    setTextColor(if (selected) tab.color else textColor)
                }
                tabLayout.addTab(this, selected)
            }
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position != tabs.lastIndex) openPhoneTab(activity, tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    @Suppress("DEPRECATION")
    private fun openPhoneTab(activity: Activity, position: Int) {
        try {
            activity.startActivity(Intent().apply {
                setClassName(activity.packageName, PHONE_MAIN)
                putExtra(EXTRA_OPEN_TAB, position)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            activity.finish()
            activity.overridePendingTransition(0, 0)
        } catch (ignored: Exception) {
        }
    }
}
