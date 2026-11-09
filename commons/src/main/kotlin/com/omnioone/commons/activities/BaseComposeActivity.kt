package com.omnioone.commons.activities

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.omnioone.commons.extensions.baseConfig
import com.omnioone.commons.helpers.MyContextWrapper
import com.omnioone.commons.helpers.REQUEST_APP_UNLOCK
import com.omnioone.commons.helpers.isTiramisuPlus

abstract class BaseComposeActivity : ComponentActivity() {
    private var useBinaryStorageUnits = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useBinaryStorageUnits = baseConfig.useBinaryStorageUnits
    }

    override fun onResume() {
        super.onResume()
        if (useBinaryStorageUnits != baseConfig.useBinaryStorageUnits) {
            recreate()
            return
        }
        maybeLaunchAppUnlockActivity(REQUEST_APP_UNLOCK)
    }

    override fun attachBaseContext(newBase: Context) {
        if (newBase.baseConfig.useEnglish && !isTiramisuPlus()) {
            super.attachBaseContext(MyContextWrapper(newBase).wrap(newBase, "en"))
        } else {
            super.attachBaseContext(newBase)
        }
    }
}
