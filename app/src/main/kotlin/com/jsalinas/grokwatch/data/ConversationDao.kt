package com.jsalinas.grokwatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateMessageText(id: Long, text: String)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    /**
     * Keep only the [limit] most recently updated conversations.
     * Double subquery is required: SQLite disallows DELETE … WHERE id NOT IN (SELECT … FROM same table).
     */
    @Query(
        """
        DELETE FROM conversations
        WHERE id NOT IN (
            SELECT id FROM (
                SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT :limit
            )
        )
        """,
    )
    suspend fun pruneOldConversations(limit: Int)
}
