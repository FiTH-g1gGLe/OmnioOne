package com.omnioone.app.adapters

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import com.omnioone.app.R
import com.omnioone.app.core.OmnioActivity
import com.omnioone.app.core.TAB_CALL_HISTORY
import com.omnioone.app.core.TAB_CONTACTS
import com.omnioone.app.core.TAB_FAVORITES
import com.omnioone.app.fragments.OmnioTab
import com.omnioone.app.phone.config

/** The pages of the main screen: the phone tabs you've chosen to show, then Messages. */
class OmnioTabsAdapter(private val activity: OmnioActivity) : PagerAdapter() {

    private val layouts: List<Int> = buildList {
        val shown = activity.config.showTabs
        if (shown and TAB_CONTACTS != 0) add(R.layout.fragment_contacts)
        if (shown and TAB_FAVORITES != 0) add(R.layout.fragment_favorites)
        if (shown and TAB_CALL_HISTORY != 0) add(R.layout.fragment_recents)
        add(R.layout.fragment_omnio_messages)
    }

    override fun getCount() = layouts.size

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val page = activity.layoutInflater.inflate(layouts[position.coerceIn(0, layouts.lastIndex)], container, false)
        container.addView(page)
        (page as? OmnioTab)?.setupFragment(activity)
        return page
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) = container.removeView(item as View)

    override fun isViewFromObject(view: View, item: Any) = view == item
}
