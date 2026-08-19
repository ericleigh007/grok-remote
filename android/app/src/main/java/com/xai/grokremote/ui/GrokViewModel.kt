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
            thinkingSoundEnabled = prefs.thinkingSoundEnabled,
            selectedVoiceName = prefs.ttsVoiceName.ifBlank { null },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var reconnectJob: Job? = null
    private var thinkingCueJob: Job? = null
    /** Assistant bubble already spoken for a session — avoid re-reading it while the next turn thinks. */
    private val spokenAssistantIds = mutableMapOf<String, String>()

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
            UiState(
                needsPairing = true,
                ttsEnabled = prefs.ttsEnabled,
                thinkingSoundEnabled = prefs.thinkingSoundEnabled,
            )
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
        if (_state.value.sessions.containsKey(id)) {
            prefs.lastSessionId = id
            _state.update { it.copy(activeSessionId = id, showSessionPicker = false) }
            return
        }
        val avail = _state.value.availableSessions.firstOrNull { it.sessionId == id }
        if (avail?.sessionId != null) {
            enterAvailable(avail)
        }
    }

    fun enterAvailable(item: com.xai.grokremote.data.AvailableSession) {
        _state.update { it.copy(openingSession = true, showSessionPicker = false, errorBanner = null) }
        if (!item.sessionId.isNullOrBlank()) {
            if (_state.value.sessions.containsKey(item.sessionId)) {
                prefs.lastSessionId = item.sessionId
                _state.update {
                    it.copy(activeSessionId = item.sessionId, openingSession = false)
                }
                return
            }
            bridge.openSession(item.sessionId, item.cwd, item.title)
        } else {
            bridge.newSession(item.cwd, item.title)
        }
    }

    fun openSessionPicker() {
        _state.update { it.copy(showSessionPicker = true) }
    }

    fun dismissSessionPicker() {
        if (_state.value.sessions.isNotEmpty()) {
            _state.update { it.copy(showSessionPicker = false) }
        }
    }

    fun showAllSessions() {
        bridge.listSessions(showAll = true)
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
        stopThinkingCue()
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
        startThinkingCue()
    }

    fun cancel() {
        val sid = _state.value.activeSessionId ?: return
        stopThinkingCue()
        speech.stopSpeaking()
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

    fun toggleThinkingSound() {
        val next = !_state.value.thinkingSoundEnabled
        prefs.thinkingSoundEnabled = next
        _state.update { it.copy(thinkingSoundEnabled = next) }
        if (next) {
            val sid = _state.value.activeSessionId
            if (sid != null && _state.value.sessions[sid]?.busy == true && !assistantStartedThisTurn(sid)) {
                startThinkingCue()
            }
        } else {
            stopThinkingCue()
        }
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
                val last = prefs.lastSessionId.ifBlank { ev.lastSessionId ?: "" }
                val lastLive = last.takeIf { it.isNotBlank() && it in map }
                val lastAvail = ev.available.firstOrNull { it.sessionId == last }
                    ?: last.takeIf { it.isNotBlank() }?.let {
                        com.xai.grokremote.data.AvailableSession(
                            title = "Last session",
                            cwd = ev.defaultCwd,
                            sessionId = it,
                        )
                    }
                _state.update {
                    it.copy(
                        sessions = map,
                        availableSessions = ev.available,
                        availableTotal = ev.availableTotal,
                        catalogTruncated = ev.catalogTruncated,
                        projects = ev.projects,
                        defaultCwd = ev.defaultCwd,
                        needsPairing = false,
                        activeSessionId = lastLive
                            ?: it.activeSessionId?.takeIf { id -> id in map }
                            ?: map.keys.firstOrNull(),
                        showSessionPicker = lastLive == null && lastAvail == null && map.isEmpty(),
                        openingSession = lastLive == null && lastAvail != null,
                    )
                }
                if (lastLive == null && lastAvail != null) {
                    enterAvailable(lastAvail)
                }
            }
            is BridgeEvent.SessionCatalog -> {
                _state.update {
                    it.copy(
                        availableSessions = ev.available,
                        availableTotal = ev.availableTotal,
                        catalogTruncated = ev.catalogTruncated,
                        showSessionPicker = true,
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
                prefs.lastSessionId = ev.sessionId
                _state.update {
                    it.copy(
                        sessions = it.sessions + (ev.sessionId to session),
                        activeSessionId = ev.sessionId,
                        showSessionPicker = false,
                        openingSession = false,
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
                stopThinkingCue()
                appendStream(ev.sessionId, kind = "assistant", chunk = ev.text)
            }
            is BridgeEvent.ThoughtChunk -> {
                // New thinking = a new turn. Stop leftover TTS of the previous reply.
                if (!assistantStartedThisTurn(ev.sessionId)) {
                    speech.stopSpeaking()
                    startThinkingCue()
                }
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
                stopThinkingCue()
                finalizeStreams(ev.sessionId)
                markBusy(ev.sessionId, false)
                maybeSpeakThisTurn(ev.sessionId)
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
                _state.update {
                    it.copy(
                        errorBanner = ev.message,
                        openingSession = false,
                        showSessionPicker = it.sessions.isEmpty() || it.showSessionPicker,
                    )
                }
                ev.sessionId?.let { markBusy(it, false) }
            }
            BridgeEvent.AuthFailed -> {
                clearPairing()
                _state.update { it.copy(errorBanner = "Invalid token — re-pair from PC /pair QR") }
            }
        }
    }

    private fun startThinkingCue() {
        if (!_state.value.thinkingSoundEnabled) return
        if (thinkingCueJob?.isActive == true) return
        thinkingCueJob = viewModelScope.launch {
            delay(2_500)
            while (isActive) {
                if (!_state.value.thinkingSoundEnabled) break
                val sid = _state.value.activeSessionId ?: break
                val busy = _state.value.sessions[sid]?.busy == true
                if (!busy || assistantStartedThisTurn(sid)) break
                speech.playThinkingBeep()
                delay(10_000)
            }
        }
    }

    private fun stopThinkingCue() {
        thinkingCueJob?.cancel()
        thinkingCueJob = null
    }

    private fun assistantStartedThisTurn(sessionId: String): Boolean {
        val s = _state.value.sessions[sessionId] ?: return false
        val lastUser = s.items.indexOfLast { it is TimelineItem.User }
        if (lastUser < 0) return false
        return s.items.drop(lastUser + 1).any { it is TimelineItem.Assistant && it.text.isNotBlank() }
    }

    /** Speak only the reply after the latest user message, once. Never re-read an older bubble. */
    private fun maybeSpeakThisTurn(sessionId: String) {
        if (!_state.value.ttsEnabled) return
        val s = _state.value.sessions[sessionId] ?: return
        val lastUser = s.items.indexOfLast { it is TimelineItem.User }
        if (lastUser < 0) return
        val reply = s.items
            .drop(lastUser + 1)
            .filterIsInstance<TimelineItem.Assistant>()
            .lastOrNull()
            ?: return
        if (reply.text.isBlank()) return
        if (spokenAssistantIds[sessionId] == reply.id) return
        spokenAssistantIds[sessionId] = reply.id
        speech.speak(reply.text)
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
