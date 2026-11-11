package com.omnioone.app.helpers

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import java.io.File

/**
 * Omnio One: profile photos caught by the Link helper. When you open someone's profile (to link it,
 * or from their contact page), the photo on screen is saved here, then offered as their contact photo.
 */
object OmnioPhotoCapture {

    data class Pending(val contactId: Int, val rawId: Int)

    private const val TIMEOUT_MS = 10 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences("omnio", Context.MODE_PRIVATE)

    /** Watch for this contact's profile photo for the next few minutes. */
    fun setPending(context: Context, contactId: Int, rawId: Int) {
        prefs(context).edit()
            .putInt("photo_contact", contactId)
            .putInt("photo_raw", rawId)
            .putLong("photo_time", System.currentTimeMillis())
            .apply()
    }

    fun getPending(context: Context): Pending? {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("photo_time", 0) > TIMEOUT_MS) return null
        val contactId = p.getInt("photo_contact", -1)
        return if (contactId < 0) null else Pending(contactId, p.getInt("photo_raw", 0))
    }

    fun clearPending(context: Context) = prefs(context).edit().remove("photo_time").remove("photo_contact").apply()

    private fun file(context: Context, contactId: Int) = File(context.cacheDir, "omnio_photo_$contactId.jpg")

    /** Keeps a caught photo until it's used or turned down. */
    fun store(context: Context, contactId: Int, bitmap: Bitmap) {
        file(context, contactId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    fun captured(context: Context, contactId: Int): Bitmap? {
        val f = file(context, contactId)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    fun discard(context: Context, contactId: Int) {
        file(context, contactId).delete()
    }

    /** Sets the full-size contact photo and makes it the main one. Run on a background thread. */
    fun saveToContact(context: Context, rawId: Int, bitmap: Bitmap): Boolean = OmnioContactPhoto.set(context, rawId.toLong(), bitmap)

    /** What the last photo attempt did, shown in Settings so problems can be reported. */
    fun note(context: Context, text: String) {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        prefs(context).edit().putString("photo_note", "$time · $text").apply()
    }

    fun lastNote(context: Context): String? = prefs(context).getString("photo_note", null)
}
