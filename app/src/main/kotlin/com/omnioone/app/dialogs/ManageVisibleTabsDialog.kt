package com.omnioone.app.dialogs

import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.viewBinding
import com.omnioone.commons.helpers.TAB_CALL_HISTORY
import com.omnioone.commons.helpers.TAB_CONTACTS
import com.omnioone.commons.helpers.TAB_FAVORITES
import com.omnioone.commons.views.MyAppCompatCheckbox
import com.omnioone.app.R
import com.omnioone.app.databinding.DialogManageVisibleTabsBinding
import com.omnioone.app.extensions.config
import com.omnioone.app.helpers.ALL_TABS_MASK

class ManageVisibleTabsDialog(val activity: BaseSimpleActivity) {
    private val binding by activity.viewBinding(DialogManageVisibleTabsBinding::inflate)
    private val tabs = LinkedHashMap<Int, Int>()

    init {
        tabs.apply {
            put(TAB_CONTACTS, R.id.manage_visible_tabs_contacts)
            put(TAB_FAVORITES, R.id.manage_visible_tabs_favorites)
            put(TAB_CALL_HISTORY, R.id.manage_visible_tabs_call_history)
        }

        val showTabs = activity.config.showTabs
        for ((key, value) in tabs) {
            binding.root.findViewById<MyAppCompatCheckbox>(value).isChecked = showTabs and key != 0
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok) { dialog, which -> dialogConfirmed() }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this)
            }
    }

    private fun dialogConfirmed() {
        var result = 0
        for ((key, value) in tabs) {
            if (binding.root.findViewById<MyAppCompatCheckbox>(value).isChecked) {
                result += key
            }
        }

        if (result == 0) {
            result = ALL_TABS_MASK
        }

        activity.config.showTabs = result
    }
}
