package com.omnioone.app.dialogs

import androidx.appcompat.app.AlertDialog
import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.viewBinding
import com.omnioone.app.activities.SimpleActivity
import com.omnioone.app.adapters.RecentCallsAdapter
import com.omnioone.app.databinding.DialogShowGroupedCallsBinding
import com.omnioone.app.models.RecentCall

class ShowGroupedCallsDialog(val activity: BaseSimpleActivity, recentCalls: List<RecentCall>) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogShowGroupedCallsBinding::inflate)

    init {
        activity.runOnUiThread {
            RecentCallsAdapter(
                activity = activity as SimpleActivity,
                recyclerView = binding.selectGroupedCallsList,
                refreshItemsListener = null,
                showOverflowMenu = false,
                itemClick = {}
            ).apply {
                binding.selectGroupedCallsList.adapter = this
                updateItems(recentCalls)
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
