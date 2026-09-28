package com.jsalinas.grokwatch

import android.app.Application
import com.jsalinas.grokwatch.data.AppDatabase
import com.jsalinas.grokwatch.data.ConversationRepository

class GrokWatchApp : Application() {
    val repository: ConversationRepository by lazy {
        ConversationRepository(AppDatabase.get(this).conversationDao())
    }
}
