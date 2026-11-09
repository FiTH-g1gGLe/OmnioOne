package com.omnioone.messages.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import androidx.appcompat.app.AlertDialog
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.showKeyboard
import com.omnioone.commons.extensions.toast
import com.omnioone.messages.R
import com.omnioone.messages.databinding.DialogRenameConversationBinding
import com.omnioone.messages.models.Conversation

class RenameConversationDialog(
    private val activity: Activity,
    private val conversation: Conversation,
    private val callback: (name: String) -> Unit,
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogRenameConversationBinding.inflate(activity.layoutInflater).apply {
            renameConvEditText.apply {
                if (conversation.usesCustomTitle) {
                    setText(conversation.title)
                }

                hint = conversation.title
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok, null)
            .setNegativeButton(com.omnioone.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.rename_conversation) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.renameConvEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).apply {
                        setOnClickListener {
                            val newTitle = binding.renameConvEditText.text.toString()
                            if (newTitle.isEmpty()) {
                                activity.toast(com.omnioone.commons.R.string.empty_name)
                                return@setOnClickListener
                            }

                            callback(newTitle)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
