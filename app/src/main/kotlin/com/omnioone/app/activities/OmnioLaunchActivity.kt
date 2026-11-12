package com.omnioone.app.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Opens Omnio One: the setup wizard the first time, the main screen after that. */
class OmnioLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (OmnioSetupActivity.isDone(this)) OmnioMainActivity::class.java else OmnioSetupActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
