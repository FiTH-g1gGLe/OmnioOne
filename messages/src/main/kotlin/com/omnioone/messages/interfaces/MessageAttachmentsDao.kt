package com.omnioone.messages.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.omnioone.messages.models.MessageAttachment

@Dao
interface MessageAttachmentsDao {
    @Query("SELECT * FROM message_attachments")
    fun getAll(): List<MessageAttachment>
}
