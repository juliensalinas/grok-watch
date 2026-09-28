package com.jsalinas.grokwatch.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jsalinas.grokwatch.BuildConfig
import com.jsalinas.grokwatch.GrokWatchApp
import com.jsalinas.grokwatch.audio.AudioRouting
import com.jsalinas.grokwatch.audio.MicRecorder
import com.jsalinas.grokwatch.audio.PcmPlayer
import com.jsalinas.grokwatch.realtime.GrokRealtimeClient
import com.jsalinas.grokwatch.realtime.RealtimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

enum class SessionStatus { IDLE, CONNECTING, LISTENING, HEARING, THINKING, SPEAKING, ERROR }

data class ChatMessage(
    val localId: Long,
    val role: Role,
    val text: String,
    val final: Boolean,
    val createdAt: Long,
    val itemId: String? = null,
    val responseId: String? = null,
    val persisted: Boolean = false,
)

data class ChatUiState(
    val status: SessionStatus = SessionStatus.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
    val errorRetryable: Boolean = true,
    val notice: String? = null,
    val conversationId: Long? = null,
    val title: String? = null,
    val voiceBargeIn: Boolean = false,
    val apiKeyMissing: Boolean = BuildConfig.XAI_API_KEY.isBlank(),
)

/**
 * Drives one voice conversation: mic → Grok realtime WebSocket → speaker, with live transcripts
 * and Room persistence. [initialConversationId] < 0 means "new conversation".
 */
class ChatViewModel(app: Application, initialConversationId: Long) : AndroidViewModel(app) {

    private val repo = (app as GrokWatchApp).repository
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val routing = AudioRouting(app)

    private val _state = MutableStateFlow(
        ChatUiState(voiceBargeIn = prefs.getBoolean(PREF_BARGE_IN, false)),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val lock = Any()
    private val localIds = AtomicLong(1)
    private val persistMutex = Mutex()

    @Volatile private var conversationId: Long? = initialConversationId.takeIf { it > 0 }
    private var historyJob: Job? = null
    private var history: List<Pair<Role, String>> = emptyList()

    // --- per-session state (guarded by [lock]) ---
    @Volatile private var started = false
    @Volatile private var sessionGen = 0
    private var client: GrokRealtimeClient? = null
    private var mic: MicRecorder? = null
    private var player: PcmPlayer? = null
    @Volatile private var wsReady = false
    private val preBuffer = ArrayDeque<ByteArray>()
    private var liveTranscription = true
    private var sessionConfirmed = false
    private var pendingUserLocalId: Long? = null
    private var currentResponseId: String? = null
    private var currentAssistantItemId: String? = null
    @Volatile private var responseActive = false
    private var responseAudioStartFrame: Long? = null
    private var tickerJob: Job? = null
    private var noticeJob: Job? = null

    init {
        conversationId?.let { id ->
            historyJob = viewModelScope.launch(Dispatchers.IO) {
                val conv = repo.getConversation(id)
                val msgs = repo.getMessages(id)
                history = msgs.map { (if (it.role == "user") Role.USER else Role.ASSISTANT) to it.text }
                _state.update { s ->
                    s.copy(
                        conversationId = id,
                        title = conv?.title,
                        messages = msgs.map {
                            ChatMessage(
                                localId = localIds.getAndIncrement(),
                                role = if (it.role == "user") Role.USER else Role.ASSISTANT,
                                text = it.text,
                                final = true,
                                createdAt = it.createdAt,
                                persisted = true,
                            )
                        } + s.messages,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        val gen = ++sessionGen
        _state.update { it.copy(error = null, notice = null) }

        if (BuildConfig.XAI_API_KEY.isBlank()) {
            fail("API key missing. Rebuild the app with XAI_API_KEY set.", retryable = false)
            return
        }
        if (!hasInternet()) {
            fail("No internet connection. Connect the watch to Wi-Fi/LTE or its phone, then retry.", retryable = true)
            return
        }
        _state.update { it.copy(status = SessionStatus.CONNECTING) }

        routing.begin()
        val p = PcmPlayer(OUTPUT_RATE).also { it.start() }
        val m = MicRecorder()
        synchronized(lock) {
            player = p
            mic = m
            wsReady = false
            preBuffer.clear()
            sessionConfirmed = false
            liveTranscription = true
            responseActive = false
            currentResponseId = null
            currentAssistantItemId = null
            pendingUserLocalId = null
        }
        // Start capturing immediately (buffered until the socket is ready) to minimise latency.
        if (!m.start { chunk -> onMicChunk(gen, chunk) }) {
            fail("Microphone unavailable. Close other apps using it and retry.", retryable = true)
            return
        }

        viewModelScope.launch {
            historyJob?.join()
            if (gen != sessionGen || !started) return@launch
            val c = GrokRealtimeClient(BuildConfig.XAI_API_KEY, BuildConfig.GROK_MODEL, listenerFor(gen))
            synchronized(lock) { client = c }
            c.connect()
        }
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                val pl = player ?: continue
                val s = _state.value.status
                if (s == SessionStatus.SPEAKING && !responseActive && !pl.isAudible()) {
                    _state.update { if (it.status == SessionStatus.SPEAKING) it.copy(status = SessionStatus.LISTENING) else it }
                }
            }
        }
    }

    /** Stops mic, playback and the connection (called from onStop / leaving the screen). */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
        }
        sessionGen++
        teardown()
        persistPartials()
        _state.update { if (it.status != SessionStatus.ERROR) it.copy(status = SessionStatus.IDLE) else it }
    }

    fun retry() {
        stop()
        _state.update { it.copy(error = null, status = SessionStatus.IDLE) }
        start()
    }

    private fun teardown() {
        tickerJob?.cancel(); tickerJob = null
        val (c, m, p) = synchronized(lock) {
            val t = Triple(client, mic, player)
            client = null; mic = null; player = null; wsReady = false; preBuffer.clear()
            responseActive = false
            t
        }
        m?.stop()
        c?.close()
        p?.stop()
        routing.end()
    }

    override fun onCleared() {
        stop()
    }

    // ------------------------------------------------------------------ user actions

    fun setVoiceBargeIn(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_BARGE_IN, enabled) }
        _state.update { it.copy(voiceBargeIn = enabled) }
    }

    /** Tap-to-interrupt: stop Grok talking and go back to listening. */
    fun interrupt() {
        synchronized(lock) { interruptLocked(cancelServer = true) }
        _state.update { it.copy(status = SessionStatus.LISTENING) }
    }

    private fun interruptLocked(cancelServer: Boolean) {
        val p = player ?: return
        val c = client
        val start = responseAudioStartFrame
        val playedMs = if (start != null) ((p.playedFrames() - start).coerceAtLeast(0) * 1000 / OUTPUT_RATE) else 0
        p.flush()
        responseAudioStartFrame = null
        if (responseActive && cancelServer) c?.cancelResponse()
        // Tell the server how much the user actually heard so the context matches reality.
        if (!responseActive) currentAssistantItemId?.let { c?.truncate(it, playedMs) }
    }

    // ------------------------------------------------------------------ audio

    private fun onMicChunk(gen: Int, chunk: ByteArray) {
        if (gen != sessionGen) return
        val c: GrokRealtimeClient
        synchronized(lock) {
            if (!wsReady) {
                preBuffer.addLast(chunk)
                while (preBuffer.size > MAX_PREBUFFER_CHUNKS) preBuffer.removeFirst()
                return
            }
            c = client ?: return
        }
        val p = player
        // Half-duplex echo guard: unless voice barge-in is enabled, send silence while Grok is audible
        // so the watch speaker never triggers the server VAD. (AEC is still on in both modes.)
        val gated = !_state.value.voiceBargeIn && p != null && p.isAudible()
        c.appendAudio(if (gated) ByteArray(chunk.size) else chunk)
    }

    private fun onAssistantAudio(bytes: ByteArray) {
        val p = player ?: return
        synchronized(lock) {
            if (responseAudioStartFrame == null) responseAudioStartFrame = p.enqueuedFrames()
        }
        p.enqueue(bytes)
        setStatus(SessionStatus.SPEAKING)
    }

    // ------------------------------------------------------------------ realtime events

    private fun listenerFor(gen: Int) = object : GrokRealtimeClient.Listener {
        override fun onOpen() {
            if (gen != sessionGen) return
            val c = client ?: return
            val m = mic ?: return
            c.sessionUpdate(BuildConfig.GROK_VOICE, INSTRUCTIONS, m.sampleRate, OUTPUT_RATE, liveTranscription)
            // Resume: replay prior turns as text context (bounded).
            var budget = MAX_HISTORY_CHARS
            val seed = history.asReversed().takeWhile { budget -= it.second.length; budget > 0 }
                .take(MAX_HISTORY_MESSAGES).asReversed()
            seed.forEach { (role, text) -> c.addHistoryMessage(if (role == Role.USER) "user" else "assistant", text) }
            synchronized(lock) {
                while (preBuffer.isNotEmpty()) c.appendAudio(preBuffer.removeFirst())
                wsReady = true
            }
        }

        override fun onEvent(type: String, event: JSONObject) {
            if (gen != sessionGen) return
            try {
                handleEvent(type, event)
            } catch (e: Exception) {
                Log.w(TAG, "Error handling $type", e)
            }
        }

        override fun onBinaryAudio(bytes: ByteArray) {
            if (gen == sessionGen) onAssistantAudio(bytes)
        }

        override fun onFailure(error: RealtimeError) {
            if (gen != sessionGen) return
            viewModelScope.launch { failAndStop(error.message, error.retryable) }
        }

        override fun onClosed(code: Int, reason: String) {
            if (gen != sessionGen) return
            viewModelScope.launch {
                failAndStop("Disconnected ($code${if (reason.isNotBlank()) ": $reason" else ""}).", true)
            }
        }
    }

    private fun handleEvent(type: String, e: JSONObject) {
        when (type) {
            "session.updated" -> {
                synchronized(lock) { sessionConfirmed = true }
                _state.update {
                    if (it.status == SessionStatus.CONNECTING) it.copy(status = SessionStatus.LISTENING) else it
                }
            }

            "input_audio_buffer.speech_started" -> {
                synchronized(lock) {
                    val p = player
                    if (p != null && p.isAudible() && _state.value.voiceBargeIn) {
                        // Voice barge-in. The server auto-cancels an in-flight response in VAD mode.
                        interruptLocked(cancelServer = false)
                    }
                    val itemId = e.optString("item_id").ifBlank { null }
                    if (itemId == null || findByItemId(itemId) == null) {
                        val msg = newMessage(Role.USER, "", itemId)
                        if (itemId == null) pendingUserLocalId = msg.localId
                        appendMessage(msg)
                    }
                }
                setStatus(SessionStatus.HEARING)
            }

            "input_audio_buffer.speech_stopped", "input_audio_buffer.committed" ->
                _state.update {
                    if (it.status == SessionStatus.HEARING) it.copy(status = SessionStatus.THINKING) else it
                }

            "conversation.item.input_audio_transcription.updated" ->
                upsertUserTranscript(e.optString("item_id"), e.optString("transcript"), final = false)

            "conversation.item.input_audio_transcription.completed" ->
                upsertUserTranscript(e.optString("item_id"), e.optString("transcript"), final = true)

            "response.created" -> {
                val rid = e.optJSONObject("response")?.optString("id")?.ifBlank { null }
                synchronized(lock) {
                    responseActive = true
                    currentResponseId = rid
                    currentAssistantItemId = null
                    responseAudioStartFrame = null
                    appendMessage(newMessage(Role.ASSISTANT, "", null, rid))
                }
                setStatus(SessionStatus.THINKING)
            }

            "response.output_item.added" -> synchronized(lock) {
                e.optJSONObject("item")?.optString("id")?.ifBlank { null }?.let { currentAssistantItemId = it }
            }

            "response.output_audio_transcript.delta", "response.text.delta", "response.output_text.delta" -> {
                val delta = e.optString("delta")
                if (delta.isNotEmpty()) {
                    val rid = e.optString("response_id").ifBlank { null }
                    synchronized(lock) {
                        if (currentAssistantItemId == null) currentAssistantItemId = e.optString("item_id").ifBlank { null }
                    }
                    updateAssistant(rid) { it.copy(text = it.text + delta) }
                }
            }

            "response.output_audio_transcript.done" -> {
                val full = e.optString("transcript")
                if (full.isNotBlank()) {
                    updateAssistant(e.optString("response_id").ifBlank { null }) {
                        if (it.text.isBlank()) it.copy(text = full) else it
                    }
                }
            }

            "response.output_audio.delta", "response.audio.delta" -> {
                val b64 = e.optString("delta")
                if (b64.isNotEmpty()) onAssistantAudio(Base64.decode(b64, Base64.DEFAULT))
            }

            "response.done" -> {
                val resp = e.optJSONObject("response")
                val rid = resp?.optString("id")?.ifBlank { null }
                val status = resp?.optString("status")
                synchronized(lock) { responseActive = false }
                updateAssistant(rid) {
                    val t = if (status == "cancelled" && it.text.isNotBlank()) it.text.trimEnd() + " …" else it.text
                    it.copy(text = t, final = true)
                }
                finalizeAssistant(rid)
                if (player?.isAudible() != true) {
                    _state.update {
                        if (it.status == SessionStatus.SPEAKING || it.status == SessionStatus.THINKING)
                            it.copy(status = SessionStatus.LISTENING) else it
                    }
                }
            }

            "input_audio_buffer.timeout_triggered" -> Unit

            "error" -> {
                val err = e.optJSONObject("error")
                val code = err?.optString("type").orEmpty().ifBlank { err?.optString("code").orEmpty() }
                val msg = err?.optString("message").orEmpty()
                Log.w(TAG, "Server error event: $code")
                val retryConfig = synchronized(lock) {
                    if (!sessionConfirmed && liveTranscription && msg.contains("transcri", ignoreCase = true)) {
                        liveTranscription = false; true
                    } else false
                }
                when {
                    retryConfig -> {
                        // Live user captions not accepted: fall back to completed transcripts only.
                        val c = client; val m = mic
                        if (c != null && m != null) {
                            c.sessionUpdate(BuildConfig.GROK_VOICE, INSTRUCTIONS, m.sampleRate, OUTPUT_RATE, false)
                        }
                    }
                    code == "timeout" || code == "max_duration" ->
                        viewModelScope.launch { failAndStop("Session ended ($code). Tap Retry to continue.", true) }
                    else -> showNotice(msg.ifBlank { "Server error: $code" })
                }
            }
        }
    }

    // ------------------------------------------------------------------ message helpers

    private fun newMessage(role: Role, text: String, itemId: String?, responseId: String? = null) = ChatMessage(
        localId = localIds.getAndIncrement(),
        role = role,
        text = text,
        final = false,
        createdAt = System.currentTimeMillis(),
        itemId = itemId,
        responseId = responseId,
    )

    private fun appendMessage(msg: ChatMessage) = _state.update { it.copy(messages = it.messages + msg) }

    private fun findByItemId(itemId: String) = _state.value.messages.firstOrNull { it.itemId == itemId }

    private fun upsertUserTranscript(itemId: String, transcript: String, final: Boolean) {
        var toPersist: ChatMessage? = null
        synchronized(lock) {
            val msgs = _state.value.messages.toMutableList()
            var idx = if (itemId.isNotBlank()) msgs.indexOfFirst { it.itemId == itemId && it.role == Role.USER } else -1
            if (idx < 0) {
                val pending = pendingUserLocalId
                idx = if (pending != null) msgs.indexOfFirst { it.localId == pending } else -1
                if (idx >= 0) pendingUserLocalId = null
            }
            if (idx < 0) {
                // No placeholder: insert before an in-progress assistant reply, if any.
                val msg = newMessage(Role.USER, "", itemId.ifBlank { null })
                val last = msgs.lastOrNull()
                if (last != null && last.role == Role.ASSISTANT && !last.final) msgs.add(msgs.size - 1, msg) else msgs.add(msg)
                idx = msgs.indexOf(msg)
            }
            val cur = msgs[idx]
            if (cur.persisted) return
            val updated = cur.copy(
                text = transcript,
                final = final,
                itemId = cur.itemId ?: itemId.ifBlank { null },
                persisted = final && transcript.isNotBlank(),
            )
            if (final && transcript.isBlank()) msgs.removeAt(idx) else msgs[idx] = updated
            _state.update { it.copy(messages = msgs) }
            if (updated.persisted) toPersist = updated
        }
        toPersist?.let { persist(it) }
    }

    private fun updateAssistant(responseId: String?, transform: (ChatMessage) -> ChatMessage) {
        synchronized(lock) {
            val rid = responseId ?: currentResponseId
            _state.update { s ->
                var idx = s.messages.indexOfLast { it.role == Role.ASSISTANT && rid != null && it.responseId == rid }
                if (idx < 0) idx = s.messages.indexOfLast { it.role == Role.ASSISTANT && !it.persisted }
                if (idx < 0) {
                    val m = transform(newMessage(Role.ASSISTANT, "", null, rid))
                    s.copy(messages = s.messages + m)
                } else {
                    s.copy(messages = s.messages.toMutableList().also { it[idx] = transform(it[idx]) })
                }
            }
        }
    }

    private fun finalizeAssistant(responseId: String?) {
        var toPersist: ChatMessage? = null
        synchronized(lock) {
            _state.update { s ->
                val idx = s.messages.indexOfLast {
                    it.role == Role.ASSISTANT && !it.persisted && (responseId == null || it.responseId == responseId)
                }
                if (idx < 0) return@update s
                val m = s.messages[idx]
                if (m.text.isBlank()) {
                    s.copy(messages = s.messages.toMutableList().also { it.removeAt(idx) })
                } else {
                    val done = m.copy(final = true, persisted = true)
                    toPersist = done
                    s.copy(messages = s.messages.toMutableList().also { it[idx] = done })
                }
            }
        }
        toPersist?.let { persist(it) }
    }

    /** On leaving mid-turn, keep whatever was already transcribed. */
    private fun persistPartials() {
        val partials = mutableListOf<ChatMessage>()
        _state.update { s ->
            partials.clear()
            s.copy(
                messages = s.messages.mapNotNull { m ->
                    when {
                        m.persisted -> m
                        m.text.isBlank() -> null
                        else -> m.copy(final = true, persisted = true).also { partials += it }
                    }
                },
            )
        }
        partials.forEach { persist(it) }
    }

    private fun persist(msg: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                val id = conversationId ?: repo.createConversation(msg.text, msg.createdAt).also { newId ->
                    conversationId = newId
                    _state.update { it.copy(conversationId = newId, title = it.title ?: msg.text.take(60)) }
                }
                repo.addMessage(id, if (msg.role == Role.USER) "user" else "assistant", msg.text, msg.createdAt)
            }
        }
    }

    // ------------------------------------------------------------------ status helpers

    private fun setStatus(s: SessionStatus) = _state.update {
        if (it.status == SessionStatus.ERROR || it.status == SessionStatus.IDLE) it else it.copy(status = s)
    }

    private fun fail(message: String, retryable: Boolean) {
        synchronized(lock) { started = false }
        sessionGen++
        teardown()
        _state.update { it.copy(status = SessionStatus.ERROR, error = message, errorRetryable = retryable) }
    }

    private fun failAndStop(message: String, retryable: Boolean) {
        if (!started) return
        persistPartials()
        fail(message, retryable)
    }

    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = text.take(160)) }
        noticeJob = viewModelScope.launch {
            delay(5000)
            _state.update { it.copy(notice = null) }
        }
    }

    private fun hasInternet(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val PREF_BARGE_IN = "voice_barge_in"
        /** 24 kHz PCM16 is the API default/recommended output format. */
        const val OUTPUT_RATE = 24_000
        private const val MAX_PREBUFFER_CHUNKS = 125 // ~5 s of 40 ms chunks
        private const val MAX_HISTORY_MESSAGES = 40
        private const val MAX_HISTORY_CHARS = 16_000

        val INSTRUCTIONS = """
            You are Grok, a witty, helpful assistant speaking with the user through their Pixel Watch.
            Your replies are played on a small watch speaker and shown as captions on a tiny round screen,
            so keep answers short and conversational: usually one to three sentences, and offer to go deeper
            instead of monologuing. Avoid markdown, lists, URLs and emojis. Reply in the language the user
            speaks. Use web or X search when the question needs current information.
        """.trimIndent().replace("\n", " ")
    }
}
