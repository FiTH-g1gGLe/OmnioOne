package com.omnioone.messages.dialogs

import androidx.appcompat.app.AlertDialog
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.getProperPrimaryColor
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.helpers.MEDIUM_ALPHA
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.R
import com.omnioone.messages.activities.SimpleActivity
import com.omnioone.messages.databinding.DialogImportMessagesBinding
import com.omnioone.messages.extensions.config
import com.omnioone.messages.helpers.MessagesImporter
import com.omnioone.messages.models.ImportResult
import com.omnioone.messages.models.MessagesBackup

class ImportMessagesDialog(
    private val activity: SimpleActivity,
    private val messages: List<MessagesBackup>,
) {

    private val config = activity.config

    init {
        var ignoreClicks = false
        val binding = DialogImportMessagesBinding.inflate(activity.layoutInflater).apply {
            importSmsCheckbox.isChecked = config.importSms
            importMmsCheckbox.isChecked = config.importMms
        }

        binding.importProgress.setIndicatorColor(activity.getProperPrimaryColor())

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok, null)
            .setNegativeButton(com.omnioone.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = R.string.import_messages
                ) { alertDialog ->
                    val positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    positiveButton.setOnClickListener {
                        if (ignoreClicks) {
                            return@setOnClickListener
                        }

                        if (!binding.importSmsCheckbox.isChecked && !binding.importMmsCheckbox.isChecked) {
                            activity.toast(R.string.no_option_selected)
                            return@setOnClickListener
                        }

                        ignoreClicks = true
                        activity.toast(com.omnioone.commons.R.string.importing)
                        config.importSms = binding.importSmsCheckbox.isChecked
                        config.importMms = binding.importMmsCheckbox.isChecked

                        alertDialog.setCanceledOnTouchOutside(false)
                        binding.importProgress.show()
                        arrayOf(
                            binding.importMmsCheckbox,
                            binding.importSmsCheckbox,
                            positiveButton,
                            negativeButton
                        ).forEach {
                            it.isEnabled = false
                            it.alpha = MEDIUM_ALPHA
                        }

                        ensureBackgroundThread {
                            MessagesImporter(activity).restoreMessages(messages) {
                                handleParseResult(it)
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    private fun handleParseResult(result: ImportResult) {
        activity.toast(
            when (result) {
                ImportResult.IMPORT_OK -> com.omnioone.commons.R.string.importing_successful
                ImportResult.IMPORT_PARTIAL -> com.omnioone.commons.R.string.importing_some_entries_failed
                ImportResult.IMPORT_FAIL -> com.omnioone.commons.R.string.importing_failed
                else -> com.omnioone.commons.R.string.no_items_found
            }
        )
    }
}
