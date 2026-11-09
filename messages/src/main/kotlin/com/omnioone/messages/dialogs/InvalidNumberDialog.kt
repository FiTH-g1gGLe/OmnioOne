package com.omnioone.messages.dialogs

import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.messages.databinding.DialogInvalidNumberBinding

class InvalidNumberDialog(val activity: BaseSimpleActivity, val text: String) {
    init {
        val binding = DialogInvalidNumberBinding.inflate(activity.layoutInflater).apply {
            dialogInvalidNumberDesc.text = text
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok) { _, _ -> }
            .apply {
                activity.setupDialogStuff(binding.root, this)
            }
    }
}
