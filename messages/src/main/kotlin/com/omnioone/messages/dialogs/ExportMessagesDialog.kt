package com.omnioone.messages.dialogs

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.DocumentsContract
import androidx.appcompat.app.AlertDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.getCurrentFormattedDateTime
import com.omnioone.commons.extensions.getProperPrimaryColor
import com.omnioone.commons.extensions.isAValidFilename
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.showErrorToast
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.extensions.value
import com.omnioone.commons.helpers.MEDIUM_ALPHA
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.R
import com.omnioone.messages.activities.SimpleActivity
import com.omnioone.messages.databinding.DialogExportMessagesBinding
import com.omnioone.messages.extensions.config
import com.omnioone.messages.helpers.MessagesReader

class ExportMessagesDialog(
    private val activity: SimpleActivity,
    private val callback: (fileName: String) -> Unit,
) {
    private val config = activity.config
    private var dialog: AlertDialog? = null

    @SuppressLint("SetTextI18n")
    private val binding = DialogExportMessagesBinding.inflate(activity.layoutInflater).apply {
        exportSmsCheckbox.isChecked = config.exportSms
        exportMmsCheckbox.isChecked = config.exportMms
        exportMessagesFilename.setText(
            "${activity.getString(R.string.messages)}_${activity.getCurrentFormattedDateTime()}"
        )
    }

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok, null)
            .setNegativeButton(com.omnioone.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = R.string.export_messages
                ) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        config.exportSms = binding.exportSmsCheckbox.isChecked
                        config.exportMms = binding.exportMmsCheckbox.isChecked
                        val filename = binding.exportMessagesFilename.value
                        when {
                            filename.isEmpty() -> activity.toast(com.omnioone.commons.R.string.empty_name)
                            filename.isAValidFilename() -> callback(filename)
                            else -> activity.toast(com.omnioone.commons.R.string.invalid_name)
                        }
                    }
                }
            }
    }

    fun exportMessages(uri: Uri) {
        dialog!!.apply {
            setCanceledOnTouchOutside(false)
            arrayOf(
                binding.exportMmsCheckbox,
                binding.exportSmsCheckbox,
                getButton(AlertDialog.BUTTON_POSITIVE),
                getButton(AlertDialog.BUTTON_NEGATIVE)
            ).forEach {
                it.isEnabled = false
                it.alpha = MEDIUM_ALPHA
            }

            binding.exportProgress.setIndicatorColor(activity.getProperPrimaryColor())
            binding.exportProgress.post {
                binding.exportProgress.show()
            }
            export(uri)
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun export(uri: Uri) {
        ensureBackgroundThread {
            var success = false
            try {
                MessagesReader(activity).getMessagesToExport(
                    getSms = config.exportSms,
                    getMms = config.exportMms
                ) { messagesToExport ->
                    if (messagesToExport.isEmpty()) {
                        activity.toast(com.omnioone.commons.R.string.no_entries_for_exporting)
                        dismiss()
                        return@getMessagesToExport
                    }
                    val json = Json { encodeDefaults = true }
                    activity.contentResolver.openOutputStream(uri)!!.buffered()
                        .use { outputStream ->
                            json.encodeToStream(messagesToExport, outputStream)
                        }
                    success = true
                    activity.toast(com.omnioone.commons.R.string.exporting_successful)
                }
            } catch (e: Throwable) {
                activity.showErrorToast(e.toString())
            } finally {
                if (!success) {
                    // delete the file to avoid leaving behind an empty/corrupt file
                    try {
                        DocumentsContract.deleteDocument(activity.contentResolver, uri)
                    } catch (_: Exception) {
                        // ignored because we don't want to show two error messages
                    }
                }

                dismiss()
            }
        }
    }

    private fun dismiss() = dialog?.dismiss()
}
