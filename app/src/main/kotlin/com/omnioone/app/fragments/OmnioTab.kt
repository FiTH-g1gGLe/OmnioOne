package com.omnioone.app.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.omnioone.app.core.OmnioActivity

/**
 * One page of Omnio One's main screen (Contacts, Favourites, Recents or Messages).
 * The main screen hands each page its activity once, then tells it when to reload
 * and what's being searched for.
 */
abstract class OmnioTab(context: Context, attributeSet: AttributeSet) : RelativeLayout(context, attributeSet) {

    protected var activity: OmnioActivity? = null

    /** Called once, when the page is first shown. */
    fun setupFragment(host: OmnioActivity) {
        if (activity != null) return
        activity = host
        setupFragment()
    }

    abstract fun setupFragment()

    /** Reload the page's list; [callback] runs once it's showing. */
    abstract fun refreshItems(invalidate: Boolean = false, callback: (() -> Unit)? = null)

    abstract fun onSearchQueryChanged(text: String)

    abstract fun onSearchClosed()

    /** Pages that colour themselves can override this; Omnio's colours are fixed, so most don't need to. */
    open fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {}
}
