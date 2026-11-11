package com.omnioone.app.activities

import android.content.Context

/** Messages rewrite: switches between the new Messages tab and the current Messages screen. */
object OmnioMessagesSwitch {
    fun isOn(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).getBoolean("new_messages", false)
    fun set(context: Context, on: Boolean) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).edit().putBoolean("new_messages", on).apply()
    fun threadOn(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).getBoolean("new_thread", true)
    fun setThread(context: Context, on: Boolean) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE).edit().putBoolean("new_thread", on).apply()
}
