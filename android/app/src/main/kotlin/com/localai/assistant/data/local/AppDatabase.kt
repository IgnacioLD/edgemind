package com.localai.assistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.localai.assistant.data.local.dao.ConversationDao
import com.localai.assistant.data.local.model.ConversationEntity
import com.localai.assistant.data.local.model.MessageEntity

/**
 * Room database for local persistence
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val DATABASE_NAME = "local_ai_assistant_db"
    }
}
