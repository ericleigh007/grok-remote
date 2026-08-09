package com.xai.grokremote.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xai.grokremote.data.BridgeClient
import com.xai.grokremote.data.BridgeEvent
import com.xai.grokremote.data.ConnState
import com.xai.grokremote.data.Prefs
import com.xai.grokremote.data.SessionState
import com.xai.grokremote.data.SpeechServices
import com.xai.grokremote.data.TimelineItem
import com.xai.grokremote.data.UiState
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GrokViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val bridge = BridgeClient()
    private val speech = SpeechServices(app)

    private val _state = MutableStateFlow(
        UiState(
            needsPairing = !prefs.hasPairing(),
            ttsEnabled = prefs.ttsEnabled,
            selectedVoiceName = prefs.ttsVoiceName.ifBlank { null },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var reconnectJob: Job? = null

    init {
        speech.onVoicesReady = { voices ->
            val preferred = prefs.ttsVoiceName.ifBlank { null }
                ?: voices.firstOrNull()?.name
            if (preferred != null && prefs.ttsVoiceName.isBlank()) {
                prefs.ttsVoiceName = preferred
                speech.setVoice(preferred)
            }
            _state.update {
                it.copy(
                    ttsVoices = voices,
                    selectedVoiceName = preferred ?: it.selectedVoiceName,
                )
            }
        }
        speech.initTts(prefs.ttsVoiceName.ifBlank { null })
        speech.onPartial = { partial ->
            _state.update { it.copy(draft = partial) }
        }
        speech.onFinal = { text ->
            _state.update { it.copy(draft = text, listening = false) }
            // Auto-send on final like web mic
            send(interruptIfBusy = true)
        }
        speech.onListeningChanged = { listening ->
            _state.update { it.copy(listening = listening) }
        }
        speech.onError = { msg ->
            _state.update { it.copy(errorBanner = msg, listening = false) }
        }

        viewModelScope.launch {
            bridge.connection.collect { c ->
                _state.update {
                    it.copy(
                        conn = c,
                        connDetail = when (c) {
                            ConnState.Online -> "online"
                            ConnState.Connecting -> "connecting…"
                            ConnState.Error -> "error"
                            ConnState.Disconnected -> "offline"
                        },
                    )
                }
                if (c == ConnState.Disconnected || c == ConnState.Error) {
                    scheduleReconnect()
                }
            }
        }
        viewModelScope.launch {
            bridge.events.collect { ev -> handleEvent(ev) }
        }

        if (prefs.hasPairing()) {
            connect()
        }
    }

    fun applyPairUri(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        // grokremote://pair?base=https://...&token=...
        // or https://host/?token=...
        try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "grokremote" -> {
                    val base = uri.getQueryParameter("base") ?: return
                    val token = uri.getQueryParameter("token") ?: return
                    savePairing(base, token)
                }
                "http", "https" -> {
                    val token = uri.getQueryParameter("token") ?: return
                    val base = "${uri.scheme}://${uri.authority}"
                    savePairing(base, token)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun savePairing(baseUrl: String, token: String) {
        prefs.baseUrl = baseUrl.trim().trimEnd('/')
        prefs.token = token.trim()
        _state.update { it.copy(needsPairing = false, errorBanner = null) }
        connect()
    }

    fun clearPairing() {
        prefs.clearPairing()
        bridge.disconnect()
        _state.update {
            UiState(needsPairing = true, ttsEnabled = prefs.ttsEnabled)
        }
    }

    fun connect() {
        if (!prefs.hasPairing()) {
            _state.update { it.copy(needsPairing = true) }
            return
        }
        bridge.connect(prefs.baseUrl, prefs.token)
    }

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text) }
    }

    fun selectSession(id: String) {
        _state.update { it.copy(activeSessionId = id) }
    }

    fun toggleThought(itemId: String) {
        val sid = _state.value.activeSessionId ?: return
        _state.update { st ->
            val s = st.sessions[sid] ?: return@update st
            val items = s.items.map {
                if (it is TimelineItem.Thought && it.id == itemId) {
                    it.copy(collapsed = !it.collapsed)
                } else {
                    it
                }
            }
            st.copy(sessions = st.sessions + (sid to s.copy(items = items)))
        }
    }

    /**
     * @param interruptIfBusy if session is busy, cancel current turn then send
     *   (desktop-style midstream new instruction).
     */
    fun send(interruptIfBusy: Boolean = false) {
        val st = _state.value
        val sid = st.activeSessionId ?: return
        val text = st.draft.trim()
        if (text.isEmpty()) return
        val session = st.sessions[sid] ?: return
        speech.stopSpeaking()
        _state.update { it.copy(draft = "") }

        // Optimistic user bubble
        appendItem(
            sid,
            TimelineItem.User(id = UUID.randomUUID().toString(), text = text),
            busy = true,
        )

        if (interruptIfBusy && session.busy) {
            bridge.interruptAndPrompt(sid, text)
        } else {
            bridge.sendPrompt(sid, text)
        }
    }

    fun cancel() {
        val sid = _state.value.activeSessionId ?: return
        bridge.cancel(sid)
        markNotBusy(sid)
    }

    fun newSession() {
        val st = _state.value
        val cwd = st.projects.firstOrNull()?.cwd ?: st.defaultCwd
        val title = st.projects.firstOrNull()?.name
        bridge.newSession(cwd.ifBlank { null }, title)
    }

    fun toggleTts() {
        val next = !_state.value.ttsEnabled
        prefs.ttsEnabled = next
        if (!next) speech.stopSpeaking()
        _state.update { it.copy(ttsEnabled = next) }
    }

    fun openVoicePicker() {
        // Refresh list in case engine populated late
        val voices = speech.listVoiceOptions()
        _state.update {
            it.copy(
                showVoicePicker = true,
                ttsVoices = voices.ifEmpty { it.ttsVoices },
                selectedVoiceName = speech.currentVoiceName() ?: it.selectedVoiceName,
            )
        }
    }

    fun dismissVoicePicker() {
        _state.update { it.copy(showVoicePicker = false) }
    }

    fun selectVoice(name: String) {
        prefs.ttsVoiceName = name
        prefs.ttsEnabled = true
        val ok = speech.setVoice(name)
        _state.update {
            it.copy(
                selectedVoiceName = name,
                ttsEnabled = true,
                // Refresh labels after engine settles
                ttsVoices = speech.listVoiceOptions().ifEmpty { it.ttsVoices },
                errorBanner = if (!ok) {
                    "Voice not applied by the TTS engine — try an On-device voice, or install Google speech data"
                } else {
                    null
                },
            )
        }
        if (ok) {
            // previewVoice re-applies + delays speak so Google TTS picks up the change
            speech.previewVoice(name)
        }
    }

    fun previewSelectedVoice() {
        val name = _state.value.selectedVoiceName
        if (name.isNullOrBlank()) {
            _state.update { it.copy(errorBanner = "Pick a voice first") }
            return
        }
        // Re-apply then preview so we don't speak with a stale engine voice
        if (!speech.setVoice(name)) {
            _state.update {
                it.copy(errorBanner = "Could not switch voice — engine rejected it")
            }
            return
        }
        speech.previewVoice(name)
    }

    fun toggleMic() {
        if (_state.value.listening) {
            speech.stopListening()
        } else {
            speech.stopSpeaking()
            speech.startListening()
        }
    }

    fun unpair() {
        clearPairing()
    }

    fun dismissError() {
        _state.update { it.copy(errorBanner = null) }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        if (!prefs.hasPairing()) return
        reconnectJob = viewModelScope.launch {
            delay(1500)
            if (isActive && _state.value.conn != ConnState.Online) {
                connect()
            }
        }
    }

    private fun handleEvent(ev: BridgeEvent) {
        when (ev) {
            is BridgeEvent.Hello -> {
                val map = ev.sessions.associateBy { it.sessionId }
                _state.update {
                    it.copy(
                        sessions = map,
                        activeSessionId = it.activeSessionId?.takeIf { id -> id in map }
                            ?: map.keys.firstOrNull(),
                        projects = ev.projects,
                        defaultCwd = ev.defaultCwd,
                        needsPairing = false,
                    )
                }
            }
            is BridgeEvent.SessionUpsert -> {
                val existing = _state.value.sessions[ev.sessionId]
                val session = SessionState(
                    sessionId = ev.sessionId,
                    title = ev.title,
                    cwd = ev.cwd,
                    busy = existing?.busy ?: false,
                    items = if (ev.messages.isNotEmpty()) ev.messages else existing?.items.orEmpty(),
                )
                _state.update {
                    it.copy(
                        sessions = it.sessions + (ev.sessionId to session),
                        activeSessionId = it.activeSessionId ?: ev.sessionId,
                    )
                }
            }
            is BridgeEvent.UserMessage -> {
                // may duplicate optimistic — skip if last user matches
                val s = _state.value.sessions[ev.sessionId] ?: return
                val last = s.items.lastOrNull()
                if (last is TimelineItem.User && last.text == ev.text) {
                    markBusy(ev.sessionId, true)
                    return
                }
                appendItem(
                    ev.sessionId,
                    TimelineItem.User(UUID.randomUUID().toString(), ev.text),
                    busy = true,
                )
            }
            is BridgeEvent.AssistantChunk -> {
                appendStream(ev.sessionId, kind = "assistant", chunk = ev.text)
            }
            is BridgeEvent.ThoughtChunk -> {
                appendStream(ev.sessionId, kind = "thought", chunk = ev.text)
            }
            is BridgeEvent.ToolCall -> {
                appendItem(
                    ev.sessionId,
                    TimelineItem.Tool(
                        id = UUID.randomUUID().toString(),
                        toolCallId = ev.toolCallId,
                        title = ev.title,
                        kind = ev.kind,
                        status = ev.status ?: "in_progress",
                    ),
                    busy = true,
                )
            }
            is BridgeEvent.ToolUpdate -> {
                val sid = ev.sessionId
                _state.update { st ->
                    val s = st.sessions[sid] ?: return@update st
                    val items = s.items.map {
                        if (it is TimelineItem.Tool &&
                            (ev.toolCallId == null || it.toolCallId == ev.toolCallId)
                        ) {
                            it.copy(status = ev.status ?: it.status)
                        } else {
                            it
                        }
                    }
                    st.copy(sessions = st.sessions + (sid to s.copy(items = items, busy = true)))
                }
            }
            is BridgeEvent.TurnComplete -> {
                finalizeStreams(ev.sessionId)
                markBusy(ev.sessionId, false)
                if (_state.value.ttsEnabled) {
                    val s = _state.value.sessions[ev.sessionId]
                    val lastAssistant = s?.items?.lastOrNull { it is TimelineItem.Assistant } as? TimelineItem.Assistant
                    lastAssistant?.text?.let { speech.speak(it) }
                }
            }
            is BridgeEvent.AgentStatus -> {
                _state.update {
                    it.copy(
                        agentAlive = ev.alive,
                        agentTransport = ev.transport,
                        errorBanner = if (ev.reconnecting) "Agent reconnecting…" else it.errorBanner,
                    )
                }
            }
            is BridgeEvent.Error -> {
                _state.update { it.copy(errorBanner = ev.message) }
                ev.sessionId?.let { markBusy(it, false) }
            }
            BridgeEvent.AuthFailed -> {
                clearPairing()
                _state.update { it.copy(errorBanner = "Invalid token — re-pair from PC /pair QR") }
            }
        }
    }

    private fun appendStream(sessionId: String, kind: String, chunk: String) {
        if (chunk.isEmpty()) return
        _state.update { st ->
            val s = st.sessions[sessionId] ?: SessionState(sessionId, "Session", "")
            val items = s.items.toMutableList()
            val last = items.lastOrNull()
            when (kind) {
                "assistant" -> {
                    if (last is TimelineItem.Assistant && last.streaming) {
                        items[items.lastIndex] = last.copy(text = last.text + chunk)
                    } else {
                        items += TimelineItem.Assistant(UUID.randomUUID().toString(), chunk, streaming = true)
                    }
                }
                "thought" -> {
                    if (last is TimelineItem.Thought && last.streaming) {
                        items[items.lastIndex] = last.copy(text = last.text + chunk)
                    } else {
                        items += TimelineItem.Thought(
                            id = UUID.randomUUID().toString(),
                            text = chunk,
                            streaming = true,
                            collapsed = false, // show live thinking
                        )
                    }
                }
            }
            st.copy(
                sessions = st.sessions + (sessionId to s.copy(items = items, busy = true)),
                activeSessionId = st.activeSessionId ?: sessionId,
            )
        }
    }

    private fun finalizeStreams(sessionId: String) {
        _state.update { st ->
            val s = st.sessions[sessionId] ?: return@update st
            val items = s.items.map {
                when (it) {
                    is TimelineItem.Assistant -> it.copy(streaming = false)
                    is TimelineItem.Thought -> it.copy(streaming = false, collapsed = true)
                    else -> it
                }
            }
            st.copy(sessions = st.sessions + (sessionId to s.copy(items = items)))
        }
    }

    private fun appendItem(sessionId: String, item: TimelineItem, busy: Boolean) {
        _state.update { st ->
            val s = st.sessions[sessionId] ?: SessionState(sessionId, "Session", "")
            st.copy(
                sessions = st.sessions + (sessionId to s.copy(items = s.items + item, busy = busy)),
                activeSessionId = st.activeSessionId ?: sessionId,
            )
        }
    }

    private fun markBusy(sessionId: String, busy: Boolean) {
        _state.update { st ->
            val s = st.sessions[sessionId] ?: return@update st
            st.copy(sessions = st.sessions + (sessionId to s.copy(busy = busy)))
        }
    }

    private fun markNotBusy(sessionId: String) = markBusy(sessionId, false)

    override fun onCleared() {
        super.onCleared()
        speech.shutdown()
        bridge.disconnect()
    }
}
