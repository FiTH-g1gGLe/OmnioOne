package com.omnioone.messages.dialogs

import androidx.appcompat.app.AlertDialog
import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.showKeyboard
import com.omnioone.commons.extensions.value
import com.omnioone.messages.databinding.DialogAddBlockedKeywordBinding
import com.omnioone.messages.extensions.config

class AddBlockedKeywordDialog(val activity: BaseSimpleActivity, private val originalKeyword: String? = null, val callback: () -> Unit) {
    init {
        val binding = DialogAddBlockedKeywordBinding.inflate(activity.layoutInflater).apply {
            if (originalKeyword != null) {
                addBlockedKeywordEdittext.setText(originalKeyword)
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok, null)
            .setNegativeButton(com.omnioone.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.showKeyboard(binding.addBlockedKeywordEdittext)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newBlockedKeyword = binding.addBlockedKeywordEdittext.value
                        if (originalKeyword != null && newBlockedKeyword != originalKeyword) {
                            activity.config.removeBlockedKeyword(originalKeyword)
                        }

                        if (newBlockedKeyword.isNotEmpty()) {
                            activity.config.addBlockedKeyword(newBlockedKeyword)
                        }

                        callback()
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
