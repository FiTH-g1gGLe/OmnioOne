package com.omnioone.app.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Opens Omnio One's main screen straight away. */
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
