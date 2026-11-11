package com.omnioone.app.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.omnioone.commons.adapters.MyRecyclerViewAdapter
import com.omnioone.commons.extensions.getProperPrimaryColor
import com.omnioone.commons.extensions.getProperTextColor
import com.omnioone.commons.extensions.getTextSize
import com.omnioone.app.core.SORT_BY_FIRST_NAME
import com.omnioone.app.core.SORT_BY_SURNAME
import com.omnioone.commons.views.MyRecyclerView
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

    fun finishActMode() {
        (innerBinding.fragmentList?.adapter as? MyRecyclerViewAdapter)?.finishActMode()
        (innerBinding.recentsList?.adapter as? MyRecyclerViewAdapter)?.finishActMode()
    }

    fun fontSizeChanged() {
        innerBinding.fragmentList?.adapter?.notifyDataSetChanged()
    }

    abstract fun setupFragment()

    abstract fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int)

    abstract fun onSearchClosed()

    abstract fun onSearchQueryChanged(text: String)

    interface InnerBinding {
        val fragmentList: MyRecyclerView?
        val recentsList: MyRecyclerView?
    }

    class ListInnerBinding(override val fragmentList: MyRecyclerView) : InnerBinding {
        override val recentsList = null
    }

    class RecentsInnerBinding(val binding: FragmentRecentsBinding) : InnerBinding {
        override val fragmentList = null
        override val recentsList = binding.recentsList
    }
}
