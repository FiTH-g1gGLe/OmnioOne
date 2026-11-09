package com.omnioone.commons

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.omnioone.commons.extensions.appLockManager
import com.omnioone.commons.extensions.checkUseEnglish

open class FossifyApp : Application() {

    open val isAppLockFeatureAvailable = false

    override fun onCreate() {
        super.onCreate()
        checkUseEnglish()
        setupAppLockManager()
    }

    private fun setupAppLockManager() {
        if (isAppLockFeatureAvailable) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLockManager)
        }
    }
}
