package com.omnioone.messages.dialogs

import androidx.appcompat.app.AlertDialog
import com.omnioone.commons.activities.BaseSimpleActivity
import com.omnioone.commons.dialogs.FilePickerDialog
import com.omnioone.commons.extensions.beGone
import com.omnioone.commons.extensions.getAlertDialogBuilder
import com.omnioone.commons.extensions.getCurrentFormattedDateTime
import com.omnioone.commons.extensions.getParentPath
import com.omnioone.commons.extensions.humanizePath
import com.omnioone.commons.extensions.internalStoragePath
import com.omnioone.commons.extensions.isAValidFilename
import com.omnioone.commons.extensions.setupDialogStuff
import com.omnioone.commons.extensions.showKeyboard
import com.omnioone.commons.extensions.toast
import com.omnioone.commons.extensions.value
import com.omnioone.commons.helpers.ensureBackgroundThread
import com.omnioone.messages.R
import com.omnioone.messages.databinding.DialogExportBlockedKeywordsBinding
import com.omnioone.messages.extensions.config
import com.omnioone.messages.helpers.BLOCKED_KEYWORDS_EXPORT_EXTENSION
import java.io.File

class ExportBlockedKeywordsDialog(
    val activity: BaseSimpleActivity,
    val path: String,
    val hidePath: Boolean,
    callback: (file: File) -> Unit,
) {
    private var realPath = path.ifEmpty { activity.internalStoragePath }
    private val config = activity.config

    init {
        val view =
            DialogExportBlockedKeywordsBinding.inflate(activity.layoutInflater, null, false).apply {
                exportBlockedKeywordsFolder.text = activity.humanizePath(realPath)
                exportBlockedKeywordsFilename.setText("${activity.getString(R.string.blocked_keywords)}_${activity.getCurrentFormattedDateTime()}")

                if (hidePath) {
                    exportBlockedKeywordsFolderLabel.beGone()
                    exportBlockedKeywordsFolder.beGone()
                } else {
                    exportBlockedKeywordsFolder.setOnClickListener {
                        FilePickerDialog(activity, realPath, false, showFAB = true) {
                            exportBlockedKeywordsFolder.text = activity.humanizePath(it)
                            realPath = it
                        }
                    }
                }
            }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.omnioone.commons.R.string.ok, null)
            .setNegativeButton(com.omnioone.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = view.root,
                    dialog = this,
                    titleId = R.string.export_blocked_keywords
                ) { alertDialog ->
                    alertDialog.showKeyboard(view.exportBlockedKeywordsFilename)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val filename = view.exportBlockedKeywordsFilename.value
                        when {
                            filename.isEmpty() -> activity.toast(com.omnioone.commons.R.string.empty_name)
                            filename.isAValidFilename() -> {
                                val file =
                                    File(realPath, "$filename$BLOCKED_KEYWORDS_EXPORT_EXTENSION")
                                if (!hidePath && file.exists()) {
                                    activity.toast(com.omnioone.commons.R.string.name_taken)
                                    return@setOnClickListener
                                }

                                ensureBackgroundThread {
                                    config.lastBlockedKeywordExportPath =
                                        file.absolutePath.getParentPath()
                                    callback(file)
                                    alertDialog.dismiss()
                                }
                            }

                            else -> activity.toast(com.omnioone.commons.R.string.invalid_name)
                        }
                    }
                }
            }
    }
}
