package com.jsalinas.grokwatch.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    /** Room row id once saved; used to rewrite user text when a longer final / concat arrives. */
    val dbId: Long? = null,
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
    /** Preferred playback volume 0–100 for STREAM_MUSIC (ASSISTANT AudioTrack). */
    val volumePercent: Int = 70,
    /**
     * True only while the current assistant reply's playback is silenced.
     * Not persisted. Cleared when the next assistant turn starts.
     */
    val responseMuted: Boolean = false,
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
        ChatUiState(
            voiceBargeIn = prefs.getBoolean(PREF_BARGE_IN, false),
            volumePercent = prefs.getInt(PREF_VOLUME, DEFAULT_VOLUME_PERCENT).coerceIn(0, 100),
        ),
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
    /** Local id of the in-progress user turn (may span multiple VAD item_ids). */
    private var openUserLocalId: Long? = null
    /** item_id → user bubble localId (survives turn seal so late completed still merges). */
    private val itemIdToUserLocalId = mutableMapOf<String, Long>()
    /** Ordered item_ids per user bubble (for concatenating multi-VAD segments). */
    private val userBubbleItemOrder = mutableMapOf<Long, MutableList<String>>()
    /** Per-item cumulative transcript (keyed by server item_id or "_pending"). */
    private val itemSegmentText = mutableMapOf<String, String>()
    private var currentResponseId: String? = null
    private var currentAssistantItemId: String? = null
    @Volatile private var responseActive = false
    /** Accept assistant PCM until response.done (trailing deltas after done are dropped). */
    @Volatile private var acceptAssistantAudio = false
    private var responseAudioStartFrame: Long? = null
    private var tickerJob: Job? = null
    private var noticeJob: Job? = null
    /** Reused silence buffer for half-duplex mic gate (avoids per-chunk allocations / GC jank). */
    private var silenceBuf: ByteArray = EMPTY_BYTES

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
        _state.update { it.copy(error = null, notice = null, responseMuted = false) }

        if (BuildConfig.XAI_API_KEY.isBlank()) {
            fail("API key missing. Rebuild the app with XAI_API_KEY set.", retryable = false)
            return
        }
        if (!hasInternet()) {
            fail("No internet connection. Connect the watch to Wi-Fi/LTE or its phone, then retry.", retryable = true)
            return
        }
        _state.update { it.copy(status = SessionStatus.CONNECTING) }

        val preferredVol = _state.value.volumePercent
        routing.begin(preferredVolumePercent = preferredVol)
        routing.startWatchingVolume { pct ->
            // Physical crown / system volume — sync UI and persist preferred level.
            prefs.edit { putInt(PREF_VOLUME, pct) }
            _state.update { it.copy(volumePercent = pct) }
        }
        val applied = routing.getVolumePercent()
        if (applied != preferredVol) {
            prefs.edit { putInt(PREF_VOLUME, applied) }
            _state.update { it.copy(volumePercent = applied) }
        }
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
            acceptAssistantAudio = false
            currentResponseId = null
            currentAssistantItemId = null
            pendingUserLocalId = null
            openUserLocalId = null
            itemIdToUserLocalId.clear()
            userBubbleItemOrder.clear()
            itemSegmentText.clear()
            silenceBuf = EMPTY_BYTES
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
        _state.update {
            val next = if (it.status != SessionStatus.ERROR) it.copy(status = SessionStatus.IDLE) else it
            next.copy(responseMuted = false)
        }
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
            acceptAssistantAudio = false
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

    /** Raise or lower STREAM_MUSIC (matches ASSISTANT AudioTrack playback) by one step. */
    fun adjustVolume(delta: Int) {
        val pct = if (delta == 0) {
            routing.getVolumePercent()
        } else {
            routing.adjustVolume(delta)
        }
        prefs.edit { putInt(PREF_VOLUME, pct) }
        _state.update { it.copy(volumePercent = pct) }
    }

    /** Tap-to-interrupt: stop Grok talking and go back to listening. */
    fun interrupt() {
        synchronized(lock) { interruptLocked(cancelServer = true) }
        _state.update { it.copy(status = SessionStatus.LISTENING, responseMuted = false) }
    }

    /**
     * Silence only the in-flight spoken reply. Captions keep streaming.
     * Tap again to resume this reply. The next assistant turn always starts unmuted.
     */
    fun toggleResponseMute() {
        val mute = !_state.value.responseMuted
        val p = player
        if (p == null) return
        p.setPlaybackMuted(mute)
        _state.update { it.copy(responseMuted = mute) }
    }

    private fun interruptLocked(cancelServer: Boolean) {
        val p = player ?: return
        val c = client
        val start = responseAudioStartFrame
        val playedMs = if (start != null) ((p.playedFrames() - start).coerceAtLeast(0) * 1000 / OUTPUT_RATE) else 0
        acceptAssistantAudio = false
        p.flush() // barge-in / Stop only — never on half-duplex mic silence
        p.setPlaybackMuted(false)
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
        // so the watch speaker never triggers the server VAD. Do NOT flush AudioTrack here — that
        // would create audible gaps. (AEC is still on in both modes.)
        // Stay gated while this reply is muted so ambient noise doesn't cancel it; captions continue.
        val s = _state.value
        val gated = !s.voiceBargeIn && p != null && (p.isAudible() || (s.responseMuted && responseActive))
        if (gated) {
            if (silenceBuf.size != chunk.size) silenceBuf = ByteArray(chunk.size)
            c.appendAudio(silenceBuf)
        } else {
            c.appendAudio(chunk)
        }
    }

    private fun onAssistantAudio(bytes: ByteArray) {
        // Drop empty / torn PCM and late deltas after response.done / barge-in.
        if (bytes.size < 2) return
        if (!acceptAssistantAudio) return
        val p = player ?: return
        synchronized(lock) {
            if (!acceptAssistantAudio) return
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

        override fun onAssistantAudioEnd() {
            if (gen != sessionGen) return
            // Only clear if no newer response has already started (responseActive true again).
            if (!responseActive) acceptAssistantAudio = false
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
                    // Reuse the open user bubble across brief VAD pauses so one spoken question
                    // stays a single caption (segments are concatenated on completed/updated).
                    val openId = openUserLocalId
                    val openMsg = openId?.let { id -> _state.value.messages.firstOrNull { it.localId == id } }
                    // Reuse while this turn is still open (sealed on response.created), even if
                    // an early transcription.completed already persisted partial text.
                    val reuseOpen = openMsg != null && openMsg.role == Role.USER && openUserLocalId == openMsg.localId
                    when {
                        itemId != null && findByItemId(itemId) != null -> {
                            // Already tracking this item.
                        }
                        reuseOpen -> {
                            val lid = openMsg.localId
                            if (itemId != null) rememberUserItem(lid, itemId)
                            if (itemId == null) pendingUserLocalId = lid
                        }
                        else -> {
                            beginOpenUserTurn(itemId)
                        }
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
                var sealPersist: ChatMessage? = null
                synchronized(lock) {
                    // Close the user turn so the next utterance gets a new bubble; late
                    // transcription.completed still finds the bubble via itemIdToUserLocalId.
                    sealPersist = sealOpenUserTurnLocked()
                    responseActive = true
                    acceptAssistantAudio = true
                    currentResponseId = rid
                    currentAssistantItemId = null
                    responseAudioStartFrame = null
                    appendMessage(newMessage(Role.ASSISTANT, "", null, rid))
                    // New assistant turn always starts with audio. Mute never carries over.
                    player?.setPlaybackMuted(false)
                }
                sealPersist?.let { sealed ->
                    if (sealed.dbId != null) persistUpdate(sealed) else persist(sealed)
                }
                _state.update { if (it.responseMuted) it.copy(responseMuted = false) else it }
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
                    // Authoritative final caption — prefer it over possibly incomplete delta text.
                    updateAssistant(e.optString("response_id").ifBlank { null }) {
                        val text = if (full.length >= it.text.length) full else it.text
                        it.copy(text = text)
                    }
                }
            }

            // Audio PCM is delivered via onBinaryAudio (decoded off the OkHttp thread in the client).
            // Ignore the JSON event body here to avoid a second Base64 decode on the reader thread.
            "response.output_audio.delta", "response.audio.delta" -> Unit

            "response.done" -> {
                val resp = e.optJSONObject("response")
                val rid = resp?.optString("id")?.ifBlank { null }
                val status = resp?.optString("status")
                val embedded = extractAssistantTranscript(resp)
                synchronized(lock) { responseActive = false }
                // acceptAssistantAudio cleared via onAssistantAudioEnd (ordered after in-flight deltas)
                updateAssistant(rid) {
                    var t = it.text
                    if (embedded != null && embedded.length >= t.length) t = embedded
                    if (status == "cancelled" && t.isNotBlank()) t = t.trimEnd() + " …"
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

    private fun rememberUserItem(localId: Long, itemId: String) {
        itemIdToUserLocalId[itemId] = localId
        val order = userBubbleItemOrder.getOrPut(localId) { mutableListOf() }
        if (itemId !in order) order += itemId
    }

    private fun joinedUserBubbleText(localId: Long): String {
        val order = userBubbleItemOrder[localId] ?: return ""
        return order.mapNotNull { id -> itemSegmentText[id]?.takeIf { it.isNotBlank() } }
            .joinToString(" ")
    }

    private fun beginOpenUserTurn(itemId: String?) {
        val msg = newMessage(Role.USER, "", itemId)
        openUserLocalId = msg.localId
        userBubbleItemOrder[msg.localId] = mutableListOf()
        if (itemId != null) {
            rememberUserItem(msg.localId, itemId)
        } else {
            pendingUserLocalId = msg.localId
            rememberUserItem(msg.localId, "_pending")
        }
        val msgs = _state.value.messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && !last.final) {
            msgs.add(msgs.size - 1, msg)
            _state.update { it.copy(messages = msgs) }
        } else {
            appendMessage(msg)
        }
    }

    /**
     * Mark the current user turn closed for bubble reuse. Persist if we already have text and
     * have not written Room yet (completed may still arrive later and grow the text).
     */
    private fun sealOpenUserTurnLocked(): ChatMessage? {
        val openId = openUserLocalId ?: return null
        openUserLocalId = null
        pendingUserLocalId = null
        val msgs = _state.value.messages.toMutableList()
        val idx = msgs.indexOfFirst { it.localId == openId }
        if (idx < 0) return null
        val cur = msgs[idx]
        val text = joinedUserBubbleText(openId).ifBlank { cur.text }
        if (text.isBlank()) {
            msgs.removeAt(idx)
            _state.update { it.copy(messages = msgs) }
            return null
        }
        if (cur.dbId != null) {
            if (text.length > cur.text.length) {
                val updated = cur.copy(text = text, final = true, persisted = true)
                msgs[idx] = updated
                _state.update { it.copy(messages = msgs) }
                return updated
            }
            return null
        }
        if (cur.persisted) {
            if (text.length > cur.text.length) {
                msgs[idx] = cur.copy(text = text, final = true)
                _state.update { it.copy(messages = msgs) }
            }
            return null
        }
        val updated = cur.copy(text = text, final = true, persisted = false)
        msgs[idx] = updated
        _state.update { it.copy(messages = msgs) }
        return updated
    }

    /**
     * Merge a cumulative ASR snapshot into one segment:
     * - never shrink on interim updates
     * - prefer a longer authoritative final; keep the longer live text if final is shorter
     */
    private fun mergeSegmentText(current: String, incoming: String, final: Boolean): String {
        if (incoming.isBlank()) return current
        if (current.isBlank()) return incoming
        if (incoming.length >= current.length) return incoming
        return current
    }

    private fun resolveUserMessageIndex(msgs: List<ChatMessage>, idKey: String?): Int {
        if (idKey != null) {
            itemIdToUserLocalId[idKey]?.let { lid ->
                val i = msgs.indexOfFirst { it.localId == lid && it.role == Role.USER }
                if (i >= 0) return i
            }
            val byItem = msgs.indexOfFirst { it.role == Role.USER && it.itemId == idKey }
            if (byItem >= 0) return byItem
        }
        val pending = pendingUserLocalId ?: openUserLocalId
        if (pending != null) {
            val i = msgs.indexOfFirst { it.localId == pending && it.role == Role.USER }
            if (i >= 0) return i
        }
        return -1
    }

    private fun upsertUserTranscript(itemId: String, transcript: String, final: Boolean) {
        var toPersist: ChatMessage? = null
        var toUpdate: ChatMessage? = null
        synchronized(lock) {
            var msgs = _state.value.messages.toMutableList()
            val idKey = itemId.ifBlank { null }

            var idx = resolveUserMessageIndex(msgs, idKey)
            if (idx < 0) {
                // Completed/updated can race ahead of speech_started (esp. completed-only fallback).
                beginOpenUserTurn(idKey)
                msgs = _state.value.messages.toMutableList()
                idx = resolveUserMessageIndex(msgs, idKey)
                if (idx < 0) idx = msgs.indexOfFirst { it.localId == openUserLocalId }
                if (idx < 0) return
            }
            if (idKey != null && pendingUserLocalId != null) pendingUserLocalId = null

            val turnLocalId = msgs[idx].localId
            val segmentKey = idKey ?: "_pending"
            rememberUserItem(turnLocalId, segmentKey)

            val prevSeg = itemSegmentText[segmentKey].orEmpty()
            val mergedSeg = mergeSegmentText(prevSeg, transcript, final)
            if (mergedSeg.isNotBlank()) itemSegmentText[segmentKey] = mergedSeg

            val nextText = joinedUserBubbleText(turnLocalId).ifBlank {
                mergeSegmentText(msgs[idx].text, transcript, final)
            }

            val cur = msgs[idx]
            if (final && nextText.isBlank() && cur.text.isBlank()) {
                msgs.removeAt(idx)
                if (openUserLocalId == cur.localId) openUserLocalId = null
                _state.update { it.copy(messages = msgs) }
                return
            }

            // Never shrink displayed user text when a shorter interim (or short final) arrives.
            val display = if (nextText.length >= cur.text.length) nextText else cur.text

            val updated = cur.copy(
                text = display,
                final = cur.final || final,
                itemId = cur.itemId ?: idKey,
            )
            msgs[idx] = updated
            _state.update { it.copy(messages = msgs) }

            when {
                final && display.isNotBlank() && cur.dbId == null && !cur.persisted ->
                    toPersist = updated
                display.length > cur.text.length && cur.dbId != null ->
                    toUpdate = updated.copy(dbId = cur.dbId, persisted = true)
                else -> Unit
            }
        }
        toPersist?.let { persist(it) }
        toUpdate?.let { persistUpdate(it) }
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
                // Re-read latest text — a longer completed/concat may have landed while we queued.
                val latest = _state.value.messages.firstOrNull { it.localId == msg.localId }
                val textToSave = listOfNotNull(latest?.text, msg.text).maxBy { it.length }
                if (textToSave.isBlank()) return@withLock
                if (latest?.dbId != null) {
                    if (textToSave.length > latest.text.length) {
                        val convId = conversationId ?: return@withLock
                        repo.updateMessageText(latest.dbId, convId, textToSave)
                        _state.update { s ->
                            s.copy(messages = s.messages.map {
                                if (it.localId == msg.localId) it.copy(text = textToSave, persisted = true, final = true) else it
                            })
                        }
                    }
                    return@withLock
                }
                val id = conversationId ?: repo.createConversation(textToSave, msg.createdAt).also { newId ->
                    conversationId = newId
                    _state.update { it.copy(conversationId = newId, title = it.title ?: textToSave.take(60)) }
                }
                val rowId = repo.addMessage(
                    id,
                    if (msg.role == Role.USER) "user" else "assistant",
                    textToSave,
                    msg.createdAt,
                )
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map {
                            if (it.localId == msg.localId) {
                                val best = if (it.text.length >= textToSave.length) it.text else textToSave
                                it.copy(persisted = true, dbId = rowId, text = best, final = true)
                            } else it
                        },
                    )
                }
            }
        }
    }

    /** Rewrite Room text when a longer final / concatenated segment arrives after first persist. */
    private fun persistUpdate(msg: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                val latest = _state.value.messages.firstOrNull { it.localId == msg.localId } ?: return@withLock
                val rowId = latest.dbId ?: msg.dbId
                val convId = conversationId
                if (rowId == null || convId == null) return@withLock
                val textToSave = listOf(latest.text, msg.text).maxBy { it.length }
                repo.updateMessageText(rowId, convId, textToSave)
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map {
                            if (it.localId == msg.localId && textToSave.length >= it.text.length) {
                                it.copy(text = textToSave, dbId = rowId, persisted = true, final = true)
                            } else it
                        },
                    )
                }
            }
        }
    }

    /** Pull the final audio transcript from a response.done payload, if present. */
    private fun extractAssistantTranscript(response: JSONObject?): String? {
        if (response == null) return null
        val output = response.optJSONArray("output") ?: return null
        val parts = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                val t = c.optString("transcript").ifBlank { c.optString("text") }
                if (t.isNotBlank()) {
                    if (parts.isNotEmpty()) parts.append(' ')
                    parts.append(t)
                }
            }
        }
        return parts.toString().ifBlank { null }
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
        private const val PREF_VOLUME = "playback_volume_percent"
        private const val DEFAULT_VOLUME_PERCENT = 70
        /** 24 kHz PCM16 is the API default/recommended output format. */
        const val OUTPUT_RATE = 24_000
        private const val MAX_PREBUFFER_CHUNKS = 125 // ~5 s of 40 ms chunks
        private const val MAX_HISTORY_MESSAGES = 40
        private const val MAX_HISTORY_CHARS = 16_000
        private val EMPTY_BYTES = ByteArray(0)

        val INSTRUCTIONS = """
            You are Grok, a witty, helpful assistant speaking with the user through their Pixel Watch.
            Your replies are played on a small watch speaker and shown as captions on a tiny round screen,
            so keep answers short and conversational: usually one to three sentences, and offer to go deeper
            instead of monologuing. Avoid markdown, lists, URLs and emojis. Reply in the language the user
            speaks. Use web or X search when the question needs current information.
        """.trimIndent().replace("\n", " ")
    }
}
