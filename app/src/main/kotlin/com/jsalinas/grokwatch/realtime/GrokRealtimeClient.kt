package com.jsalinas.grokwatch.realtime

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the xAI Grok Voice Agent (speech-to-speech) API.
 *
 * Protocol (docs.x.ai → Voice → Speech to Speech):
 *  - WebSocket `wss://api.x.ai/v1/realtime?model=<model>`, header `Authorization: Bearer <key>`
 *  - client: `session.update`, `input_audio_buffer.append` (base64 PCM16), `conversation.item.create`,
 *    `response.cancel`, `conversation.item.truncate`
 *  - server: `session.updated`, `input_audio_buffer.speech_started/stopped`,
 *    `conversation.item.input_audio_transcription.updated/completed`, `response.created`,
 *    `response.output_audio_transcript.delta/done`, `response.output_audio.delta`, `response.done`, `error`
 */
class GrokRealtimeClient(
    private val apiKey: String,
    private val model: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onEvent(type: String, event: JSONObject)
        /** Raw audio when the session uses `audio.output.transport = "binary"`. */
        fun onBinaryAudio(bytes: ByteArray)
        fun onFailure(error: RealtimeError)
        fun onClosed(code: Int, reason: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByClient = false

    fun connect() {
        closedByClient = false
        val request = Request.Builder()
            .url("$ENDPOINT?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try { JSONObject(text) } catch (e: Exception) {
                    Log.w(TAG, "Unparseable server event"); return
                }
                listener.onEvent(event.optString("type"), event)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                listener.onBinaryAudio(bytes.toByteArray())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByClient) listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByClient) return
                val body = runCatching { response?.body?.string()?.take(300) }.getOrNull()
                listener.onFailure(RealtimeError.from(t, response?.code, body))
            }
        })
    }

    fun send(event: JSONObject): Boolean = ws?.send(event.toString()) ?: false

    fun sessionUpdate(
        voice: String,
        instructions: String,
        inputRate: Int,
        outputRate: Int,
        withLiveUserTranscription: Boolean,
    ) {
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", inputRate))
        if (withLiveUserTranscription) {
            // Enables streaming `conversation.item.input_audio_transcription.updated` captions.
            input.put("transcription", JSONObject().put("model", "grok-transcribe"))
        }
        val session = JSONObject()
            .put("voice", voice)
            .put("instructions", instructions)
            .put("turn_detection", JSONObject().put("type", "server_vad"))
            .put(
                "audio",
                JSONObject()
                    .put("input", input)
                    .put(
                        "output",
                        JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", outputRate)),
                    ),
            )
            .put(
                "tools",
                JSONArray()
                    .put(JSONObject().put("type", "web_search"))
                    .put(JSONObject().put("type", "x_search")),
            )
        send(JSONObject().put("type", "session.update").put("session", session))
    }

    fun appendAudio(pcm16: ByteArray) {
        send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(pcm16, Base64.NO_WRAP)),
        )
    }

    /** Seeds prior history (text) so a resumed conversation keeps its context. */
    fun addHistoryMessage(role: String, text: String) {
        val contentType = if (role == "user") "input_text" else "text"
        send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", role)
                        .put("content", JSONArray().put(JSONObject().put("type", contentType).put("text", text))),
                ),
        )
    }

    fun cancelResponse() {
        send(JSONObject().put("type", "response.cancel"))
    }

    fun truncate(itemId: String, audioEndMs: Long) {
        send(
            JSONObject()
                .put("type", "conversation.item.truncate")
                .put("item_id", itemId)
                .put("content_index", 0)
                .put("audio_end_ms", audioEndMs),
        )
    }

    fun close() {
        closedByClient = true
        ws?.close(1000, "client closed")
        ws = null
    }

    companion object {
        const val ENDPOINT = "wss://api.x.ai/v1/realtime"
        private const val TAG = "GrokRealtime"
    }
}

/** User-facing classification of connection failures. */
data class RealtimeError(val message: String, val retryable: Boolean) {
    companion object {
        fun from(t: Throwable, httpCode: Int?, body: String?): RealtimeError = when {
            httpCode == 401 -> RealtimeError("Invalid xAI API key (401). Rebuild with a valid XAI_API_KEY.", false)
            httpCode == 403 -> RealtimeError("Access denied (403). Check the key's permissions / team credits.", false)
            httpCode == 429 -> RealtimeError("Rate limited or out of credits (429). Try again later.", true)
            httpCode != null && httpCode >= 500 -> RealtimeError("xAI server error ($httpCode). Try again.", true)
            httpCode != null -> RealtimeError("Connection refused ($httpCode)${body?.let { ": $it" } ?: ""}", true)
            t is java.net.UnknownHostException -> RealtimeError("No internet connection.", true)
            t is java.net.SocketTimeoutException -> RealtimeError("Connection timed out. Check network.", true)
            t is java.net.ConnectException -> RealtimeError("Cannot reach api.x.ai. Check network.", true)
            t is javax.net.ssl.SSLException -> RealtimeError("Secure connection failed. Check network/time.", true)
            else -> RealtimeError("Connection lost: ${t.message ?: t.javaClass.simpleName}", true)
        }
    }
}
