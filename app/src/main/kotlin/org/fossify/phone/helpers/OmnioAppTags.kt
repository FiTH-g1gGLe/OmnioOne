package org.fossify.phone.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Website

/**
 * Omnio One: which chat apps and socials each contact has, shown as small app icons under
 * their name in the Contacts list.
 */
object OmnioAppTags {

    data class Badge(val name: String, val pkg: String, val color: Int)

    private val iconCache = HashMap<String, Drawable>()

    /** Contact id -> badges, chat apps first, then socials. Run on a background thread. */
    fun load(context: Context): Map<Int, List<Badge>> {
        val found = HashMap<Int, LinkedHashSet<Badge>>()

        // chat apps add their own rows to the phone's contacts
        val chatByMime = OmnioSocials.chatApps.flatMap { app -> app.actions.map { it.mimeType to app } }.toMap()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE),
                "${ContactsContract.Data.MIMETYPE} IN (${chatByMime.keys.joinToString { "?" }})",
                chatByMime.keys.toTypedArray(),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val app = chatByMime[cursor.getString(1)] ?: continue
                    found.getOrPut(cursor.getInt(0)) { LinkedHashSet() }.add(Badge(app.name, app.pkg, app.color))
                }
            }
        } catch (ignored: Exception) {
        }

        // socials are saved as profile links
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, Website.URL),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(Website.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val url = cursor.getString(1) ?: continue
                    val network = OmnioSocials.networkFor(url) ?: continue
                    found.getOrPut(cursor.getInt(0)) { LinkedHashSet() }.add(Badge(network.name, network.pkg, network.color))
                }
            }
        } catch (ignored: Exception) {
        }

        return found.mapValues { it.value.toList() }
    }

    /** The app's real icon, or a coloured letter if it isn't installed. Cached. */
    fun icon(context: Context, badge: Badge): Drawable = iconCache.getOrPut(badge.pkg) {
        try {
            context.packageManager.getApplicationIcon(badge.pkg)
        } catch (e: Exception) {
            val size = (context.resources.displayMetrics.density * 36).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint().apply { isAntiAlias = true; color = badge.color })
            val paint = Paint().apply {
                isAntiAlias = true
                color = if (androidx.core.graphics.ColorUtils.calculateLuminance(badge.color) > 0.6) 0xFF0F1115.toInt() else 0xFFFFFFFF.toInt()
                textSize = size * 0.5f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(badge.name.take(1), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
            BitmapDrawable(context.resources, bitmap)
        }
    }
}
