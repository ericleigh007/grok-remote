package com.xai.grokremote.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket client for the Grok Remote bridge (not raw agent serve).
 * Protocol matches web/app.js + server/main.py.
 */
class BridgeClient {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val wsRef = AtomicReference<WebSocket?>(null)

    private val _events = MutableSharedFlow<BridgeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    private val _conn = MutableSharedFlow<ConnState>(replay = 1, extraBufferCapacity = 8)
    val connection: SharedFlow<ConnState> = _conn.asSharedFlow()

    fun connect(baseUrl: String, token: String) {
        disconnect()
        _conn.tryEmit(ConnState.Connecting)
        val http = baseUrl.trimEnd('/')
        val wsBase = when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> "wss://$http"
        }
        val url = "$wsBase/ws?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        val req = Request.Builder().url(url).build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _conn.tryEmit(ConnState.Online)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmit(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parseAndEmit(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _conn.tryEmit(if (code == 4401) ConnState.Error else ConnState.Disconnected)
                if (code == 4401) {
                    _events.tryEmit(BridgeEvent.AuthFailed)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _conn.tryEmit(ConnState.Error)
                _events.tryEmit(BridgeEvent.Error(t.message ?: "connection failed"))
            }
        })
        wsRef.set(ws)
    }

    fun disconnect() {
        wsRef.getAndSet(null)?.close(1000, "bye")
        _conn.tryEmit(ConnState.Disconnected)
    }

    fun sendPrompt(sessionId: String, text: String) {
        send(JSONObject().put("type", "prompt").put("sessionId", sessionId).put("text", text))
    }

    fun cancel(sessionId: String) {
        send(JSONObject().put("type", "cancel").put("sessionId", sessionId))
    }

    /** Cancel in-flight turn then send (midstream new thought). */
    fun interruptAndPrompt(sessionId: String, text: String) {
        cancel(sessionId)
        sendPrompt(sessionId, text)
    }

    fun newSession(cwd: String?, title: String?) {
        val o = JSONObject().put("type", "new_session")
        if (!cwd.isNullOrBlank()) o.put("cwd", cwd)
        if (!title.isNullOrBlank()) o.put("title", title)
        send(o)
    }

    fun openSession(sessionId: String, cwd: String?, title: String?) {
        val o = JSONObject()
            .put("type", "open_session")
            .put("sessionId", sessionId)
        if (!cwd.isNullOrBlank()) o.put("cwd", cwd)
        if (!title.isNullOrBlank()) o.put("title", title)
        send(o)
    }

    fun listSessions(showAll: Boolean) {
        send(
            JSONObject()
                .put("type", "list_sessions")
                .put("showAll", showAll)
                .put("all", showAll),
        )
    }

    fun ping() {
        send(JSONObject().put("type", "ping"))
    }

    private fun send(obj: JSONObject) {
        wsRef.get()?.send(obj.toString())
    }

    private fun parseAndEmit(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "hello" -> {
                    val sessions = o.optJSONArray("sessions").toSessionList()
                    val projects = o.optJSONArray("projects").toProjectList()
                    _events.tryEmit(
                        BridgeEvent.Hello(
                            sessions = sessions,
                            projects = projects,
                            defaultCwd = o.optString("default_cwd", ""),
                            available = o.optJSONArray("availableSessions").toAvailableList(),
                            lastSessionId = o.optString("lastSessionId", "").ifBlank { null },
                            availableTotal = o.optInt("availableTotal", 0),
                            catalogTruncated = o.optBoolean("catalogTruncated", false),
                        ),
                    )
                }
                "session_catalog" -> {
                    _events.tryEmit(
                        BridgeEvent.SessionCatalog(
                            available = o.optJSONArray("availableSessions").toAvailableList(),
                            availableTotal = o.optInt("availableTotal", 0),
                            catalogTruncated = o.optBoolean("catalogTruncated", false),
                        ),
                    )
                }
                "session_created", "session_loaded" -> {
                    _events.tryEmit(
                        BridgeEvent.SessionUpsert(
                            sessionId = o.getString("sessionId"),
                            title = o.optString("title", "Session"),
                            cwd = o.optString("cwd", ""),
                            messages = o.optJSONArray("messages").toLegacyMessages(),
                            reconnected = o.optBoolean("reconnected", false),
                        ),
                    )
                }
                "user_message" -> {
                    _events.tryEmit(
                        BridgeEvent.UserMessage(
                            sessionId = o.getString("sessionId"),
                            text = o.optString("text", ""),
                        ),
                    )
                }
                "session_update" -> {
                    val updateType = o.optString("updateType", "")
                    val sid = o.optString("sessionId", "")
                    when (updateType) {
                        "agent_message_chunk" ->
                            _events.tryEmit(
                                BridgeEvent.AssistantChunk(sid, o.optString("text", "")),
                            )
                        "agent_thought_chunk" -> {
                            val thought = o.optString("thought", null)
                                ?: o.optJSONObject("update")
                                    ?.optJSONObject("content")
                                    ?.optString("text")
                                ?: ""
                            _events.tryEmit(BridgeEvent.ThoughtChunk(sid, thought))
                        }
                        "tool_call" -> {
                            val tool = o.optJSONObject("tool") ?: o.optJSONObject("update")
                            _events.tryEmit(
                                BridgeEvent.ToolCall(
                                    sessionId = sid,
                                    toolCallId = tool?.optString("toolCallId"),
                                    title = tool?.optString("title") ?: "tool",
                                    kind = tool?.optString("kind"),
                                    status = tool?.optString("status"),
                                ),
                            )
                        }
                        "tool_call_update" -> {
                            val tool = o.optJSONObject("tool") ?: o.optJSONObject("update")
                            _events.tryEmit(
                                BridgeEvent.ToolUpdate(
                                    sessionId = sid,
                                    toolCallId = tool?.optString("toolCallId"),
                                    status = tool?.optString("status"),
                                ),
                            )
                        }
                    }
                }
                "turn_complete" -> {
                    _events.tryEmit(
                        BridgeEvent.TurnComplete(
                            sessionId = o.optString("sessionId"),
                            stopReason = o.optString("stopReason", null),
                        ),
                    )
                }
                "agent_status" -> {
                    _events.tryEmit(
                        BridgeEvent.AgentStatus(
                            alive = o.optBoolean("alive", false),
                            transport = o.optString("transport", null),
                            reconnecting = o.optBoolean("reconnecting", false),
                            error = o.optString("error", null),
                        ),
                    )
                }
                "error" -> {
                    _events.tryEmit(
                        BridgeEvent.Error(
                            o.optString("message", "error"),
                            o.optString("sessionId", null),
                        ),
                    )
                }
                "pong" -> Unit
            }
        } catch (e: Exception) {
            _events.tryEmit(BridgeEvent.Error("bad message: ${e.message}"))
        }
    }

    private fun JSONArray?.toSessionList(): List<SessionState> {
        if (this == null) return emptyList()
        val out = mutableListOf<SessionState>()
        for (i in 0 until length()) {
            val s = optJSONObject(i) ?: continue
            val id = s.optString("sessionId")
            val items = s.optJSONArray("messages").toLegacyMessages()
            out += SessionState(
                sessionId = id,
                title = s.optString("title", "Session"),
                cwd = s.optString("cwd", ""),
                busy = s.optBoolean("busy", false),
                items = items,
            )
        }
        return out
    }

    private fun JSONArray?.toProjectList(): List<ProjectOption> {
        if (this == null) return emptyList()
        val out = mutableListOf<ProjectOption>()
        for (i in 0 until length()) {
            val p = optJSONObject(i) ?: continue
            out += ProjectOption(
                name = p.optString("name", p.optString("cwd", "project")),
                cwd = p.optString("cwd", ""),
                sessionId = p.optString("session_id", p.optString("sessionId", "")).ifBlank { null },
            )
        }
        return out
    }

    private fun JSONArray?.toAvailableList(): List<AvailableSession> {
        if (this == null) return emptyList()
        val out = mutableListOf<AvailableSession>()
        for (i in 0 until length()) {
            val p = optJSONObject(i) ?: continue
            out += AvailableSession(
                title = p.optString("title", p.optString("name", "Session")),
                cwd = p.optString("cwd", ""),
                sessionId = p.optString("sessionId", "").ifBlank { null },
                updatedAt = p.optString("updatedAt", "").ifBlank { null },
                messageCount = p.optInt("messageCount", 0),
                preview = p.optString("preview", "").ifBlank { null },
            )
        }
        return out
    }

    /** Bridge hello still uses role/text messages — map into timeline items. */
    private fun JSONArray?.toLegacyMessages(): List<TimelineItem> {
        if (this == null) return emptyList()
        val out = mutableListOf<TimelineItem>()
        for (i in 0 until length()) {
            val m = optJSONObject(i) ?: continue
            val role = m.optString("role", "")
            val text = m.optString("text", "")
            val id = "hist-$i-${text.hashCode()}"
            when (role) {
                "user" -> out += TimelineItem.User(id, text)
                "assistant" ->
                    out += TimelineItem.Assistant(id, text, streaming = m.optBoolean("streaming", false))
                "thought" ->
                    out += TimelineItem.Thought(
                        id = id,
                        text = text,
                        streaming = m.optBoolean("streaming", false),
                        collapsed = true,
                    )
                "system" -> out += TimelineItem.System(id, text)
                else -> out += TimelineItem.System(id, text)
            }
        }
        return out
    }
}

sealed class BridgeEvent {
    data class Hello(
        val sessions: List<SessionState>,
        val projects: List<ProjectOption>,
        val defaultCwd: String,
        val available: List<AvailableSession> = emptyList(),
        val lastSessionId: String? = null,
        val availableTotal: Int = 0,
        val catalogTruncated: Boolean = false,
    ) : BridgeEvent()

    data class SessionCatalog(
        val available: List<AvailableSession>,
        val availableTotal: Int,
        val catalogTruncated: Boolean,
    ) : BridgeEvent()

    data class SessionUpsert(
        val sessionId: String,
        val title: String,
        val cwd: String,
        val messages: List<TimelineItem>,
        val reconnected: Boolean,
    ) : BridgeEvent()

    data class UserMessage(val sessionId: String, val text: String) : BridgeEvent()
    data class AssistantChunk(val sessionId: String, val text: String) : BridgeEvent()
    data class ThoughtChunk(val sessionId: String, val text: String) : BridgeEvent()
    data class ToolCall(
        val sessionId: String,
        val toolCallId: String?,
        val title: String,
        val kind: String?,
        val status: String?,
    ) : BridgeEvent()
    data class ToolUpdate(
        val sessionId: String,
        val toolCallId: String?,
        val status: String?,
    ) : BridgeEvent()
    data class TurnComplete(val sessionId: String, val stopReason: String?) : BridgeEvent()
    data class AgentStatus(
        val alive: Boolean,
        val transport: String?,
        val reconnecting: Boolean,
        val error: String?,
    ) : BridgeEvent()
    data class Error(val message: String, val sessionId: String? = null) : BridgeEvent()
    data object AuthFailed : BridgeEvent()
}
