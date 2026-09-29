package com.jsalinas.grokwatch

import android.app.Application
import com.jsalinas.grokwatch.data.AppDatabase
import com.jsalinas.grokwatch.data.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GrokWatchApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: ConversationRepository by lazy {
        ConversationRepository(AppDatabase.get(this).conversationDao())
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            repository.pruneToLimit()
        }
    }
}
