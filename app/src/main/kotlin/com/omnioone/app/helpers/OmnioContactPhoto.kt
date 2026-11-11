package com.omnioone.app.helpers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * Omnio One contact photos: setting and removing them reliably.
 *
 * A contact you see is often made of several linked entries (phone, Google, WhatsApp…), and any of
 * them can carry a photo. So removing a photo clears it from every entry, and setting one marks it
 * as the contact's main photo so an older one from another entry can't win. Run on a background thread.
 */
object OmnioContactPhoto {

    private const val MAX_SIZE = 720

    /** Every linked entry (raw contact) that makes up this contact. */
    private fun rawIdsOf(context: Context, contactId: Long): List<Long> {
        val ids = ArrayList<Long>()
        context.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ?", arrayOf(contactId.toString()), null)
            ?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        return ids
    }

    private fun contactIdOf(context: Context, rawId: Long): Long? =
        context.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ?", arrayOf(rawId.toString()), null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun deletePhotoRows(context: Context, rawIds: List<Long>): Int {
        if (rawIds.isEmpty()) return 0
        return context.contentResolver.delete(
            Data.CONTENT_URI,
            "${Data.MIMETYPE} = ? AND ${Data.RAW_CONTACT_ID} IN (${rawIds.joinToString(",")})",
            arrayOf(Photo.CONTENT_ITEM_TYPE)
        )
    }

    /** True if the contact still shows a photo. */
    fun hasPhoto(context: Context, contactId: Long): Boolean =
        context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(ContactsContract.Contacts.PHOTO_ID), null, null, null
        )?.use { cursor -> cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) > 0 } ?: false

    /**
     * Removes the photo from the contact and every entry linked to it. Returns true when no photo is left.
     * (An app like WhatsApp may add its own photo back the next time it syncs.)
     */
    fun remove(context: Context, rawId: Long): Boolean = try {
        val contactId = contactIdOf(context, rawId)
        val raws = if (contactId != null) rawIdsOf(context, contactId).ifEmpty { listOf(rawId) } else listOf(rawId)
        deletePhotoRows(context, raws)
        OmnioDisplay.photosChanged(context)
        contactId == null || !hasPhoto(context, contactId)
    } catch (e: Exception) {
        false
    }

    /** Sets a full-size photo on [rawId] and makes it the contact's main photo. */
    fun set(context: Context, rawId: Long, bitmap: Bitmap): Boolean = try {
        // one photo per entry: clear any old ones first so the new one isn't hidden behind them
        deletePhotoRows(context, listOf(rawId))
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        context.contentResolver.openAssetFileDescriptor(uri, "rw")?.use { descriptor ->
            descriptor.createOutputStream().use { scaled(bitmap).compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
        // make it the main photo even if another linked entry (WhatsApp, Google…) has one
        context.contentResolver.update(
            Data.CONTENT_URI,
            ContentValues().apply {
                put(Data.IS_PRIMARY, 1)
                put(Data.IS_SUPER_PRIMARY, 1)
            },
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE)
        )
        OmnioDisplay.photosChanged(context)
        true
    } catch (e: Exception) {
        false
    }

    /** Sets a picture chosen from the gallery (turned the right way up and resized). */
    fun set(context: Context, rawId: Long, picture: Uri): Boolean {
        val bitmap = load(context, picture) ?: return false
        return set(context, rawId, bitmap)
    }

    private fun load(context: Context, picture: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, picture)) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > MAX_SIZE * 2) decoder.setTargetSampleSize(largest / (MAX_SIZE * 2))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(picture)?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** Square, centred, at most [MAX_SIZE] pixels. */
    private fun scaled(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        return if (side > MAX_SIZE) Bitmap.createScaledBitmap(square, MAX_SIZE, MAX_SIZE, true) else square
    }
}
