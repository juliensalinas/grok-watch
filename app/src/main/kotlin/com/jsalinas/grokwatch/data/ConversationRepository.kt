package com.jsalinas.grokwatch.data

import kotlinx.coroutines.flow.Flow

class ConversationRepository(private val dao: ConversationDao) {

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    suspend fun getConversation(id: Long): ConversationEntity? = dao.getConversation(id)

    suspend fun getMessages(conversationId: Long): List<MessageEntity> = dao.getMessages(conversationId)

    /** Creates a conversation titled after its first utterance. */
    suspend fun createConversation(firstUtterance: String, now: Long = System.currentTimeMillis()): Long {
        val title = firstUtterance.trim().replace(Regex("\\s+"), " ").let {
            if (it.length > 60) it.take(57) + "…" else it
        }.ifBlank { "Conversation" }
        return dao.insertConversation(ConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun addMessage(conversationId: Long, role: String, text: String, createdAt: Long) {
        dao.insertMessage(MessageEntity(conversationId = conversationId, role = role, text = text, createdAt = createdAt))
        dao.touch(conversationId, System.currentTimeMillis())
    }

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)
}
