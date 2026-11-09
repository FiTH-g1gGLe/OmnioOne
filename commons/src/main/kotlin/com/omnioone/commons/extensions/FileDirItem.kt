package com.omnioone.commons.extensions

import android.content.Context
import com.omnioone.commons.models.FileDirItem

fun FileDirItem.isRecycleBinPath(context: Context): Boolean {
    return path.startsWith(context.recycleBinPath)
}
