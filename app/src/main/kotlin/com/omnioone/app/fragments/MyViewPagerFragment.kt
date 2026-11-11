package com.omnioone.app.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.omnioone.app.core.getProperPrimaryColor
import com.omnioone.app.core.getProperTextColor
import com.omnioone.app.core.SORT_BY_FIRST_NAME
import com.omnioone.app.core.SORT_BY_SURNAME
import androidx.recyclerview.widget.RecyclerView
import com.omnioone.app.activities.MainActivity
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.databinding.FragmentRecentsBinding
import com.omnioone.app.extensions.config
import com.omnioone.app.helpers.Config

abstract class MyViewPagerFragment<BINDING : MyViewPagerFragment.InnerBinding>(context: Context, attributeSet: AttributeSet) :
    RelativeLayout(context, attributeSet) {
    protected var activity: SimpleActivity? = null
    protected lateinit var innerBinding: BINDING
    private lateinit var config: Config

    fun setupFragment(activity: SimpleActivity) {
        config = activity.config
        if (this.activity == null) {
            this.activity = activity

            setupFragment()
            setupColors(activity.getProperTextColor(), activity.getProperPrimaryColor(), activity.getProperPrimaryColor())
        }
    }

    fun startNameWithSurnameChanged(startNameWithSurname: Boolean) {
        if (this !is RecentsFragment) {
            config.sorting = if (startNameWithSurname) SORT_BY_SURNAME else SORT_BY_FIRST_NAME
            (activity as? MainActivity)?.refreshFragments()
        }
    }

    /** Kept for screens that still call it; Omnio lists have no selection mode to close. */
    fun finishActMode() {}

    fun fontSizeChanged() {
        innerBinding.fragmentList?.adapter?.notifyDataSetChanged()
    }

    abstract fun setupFragment()

    abstract fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int)

    abstract fun onSearchClosed()

    abstract fun onSearchQueryChanged(text: String)

    interface InnerBinding {
        val fragmentList: RecyclerView?
        val recentsList: RecyclerView?
    }

    class ListInnerBinding(override val fragmentList: RecyclerView) : InnerBinding {
        override val recentsList = null
    }

    class RecentsInnerBinding(val binding: FragmentRecentsBinding) : InnerBinding {
        override val fragmentList = null
        override val recentsList = binding.recentsList
    }
}
