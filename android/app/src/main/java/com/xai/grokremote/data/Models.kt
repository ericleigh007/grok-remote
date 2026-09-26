package com.xai.grokremote.data

/** Timeline item in a session (desktop-TUI-like stream). */
sealed class TimelineItem {
    abstract val id: String

    data class User(
        override val id: String,
        val text: String,
    ) : TimelineItem()

    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : TimelineItem()

    data class Thought(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
        val collapsed: Boolean = true,
    ) : TimelineItem()

    data class Tool(
        override val id: String,
        val toolCallId: String?,
        val title: String,
        val kind: String?,
        val status: String?,
    ) : TimelineItem()

    data class System(
        override val id: String,
        val text: String,
    ) : TimelineItem()
}

data class SessionState(
    val sessionId: String,
    val title: String,
    val cwd: String,
    val busy: Boolean = false,
    val items: List<TimelineItem> = emptyList(),
)

data class ProjectOption(
    val name: String,
    val cwd: String,
    val sessionId: String? = null,
)

data class QuestionOption(
    val id: String,
    val label: String,
)

data class AgentQuestion(
    val id: String,
    val prompt: String,
    val options: List<QuestionOption> = emptyList(),
    val allowMultiple: Boolean = false,
)

data class PendingUserQuestion(
    val requestId: String,
    val sessionId: String?,
    val questions: List<AgentQuestion>,
)

data class AvailableSession(
    val title: String,
    val cwd: String,
    val sessionId: String? = null,
    val updatedAt: String? = null,
    val messageCount: Int = 0,
    val preview: String? = null,
)

data class ConnectionConfig(
    val baseUrl: String, // https://host or http://100.x:8787
    val token: String,
)

enum class ConnState { Disconnected, Connecting, Online, Error }

data class UiState(
    val conn: ConnState = ConnState.Disconnected,
    val connDetail: String = "",
    val sessions: Map<String, SessionState> = emptyMap(),
    val activeSessionId: String? = null,
    val projects: List<ProjectOption> = emptyList(),
    val defaultCwd: String = "",
    val draft: String = "",
    val ttsEnabled: Boolean = true,
    val thinkingSoundEnabled: Boolean = false,
    val ttsVoices: List<VoiceOption> = emptyList(),
    val selectedVoiceName: String? = null,
    val showVoicePicker: Boolean = false,
    val listening: Boolean = false,
    val agentAlive: Boolean? = null,
    val agentTransport: String? = null,
    val errorBanner: String? = null,
    val needsPairing: Boolean = true,
    val availableSessions: List<AvailableSession> = emptyList(),
    val availableTotal: Int = 0,
    val catalogTruncated: Boolean = false,
    val showSessionPicker: Boolean = false,
    val openingSession: Boolean = false,
    val pendingQuestion: PendingUserQuestion? = null,
    val catchingUp: Boolean = false,
    val sessionQuery: String = "",
    val catalogQuery: String = "",
    val searchingSessions: Boolean = false,
    val showSettings: Boolean = false,
    val pauseTolerantStt: Boolean = true,
    val autoSendVoice: Boolean = true,
    val holdScroll: Boolean = true,
    val voiceIdleSendMs: Long = 5_000L,
) {
    val active: SessionState?
        get() = activeSessionId?.let { sessions[it] }

    val selectedVoiceLabel: String
        get() = ttsVoices.firstOrNull { it.name == selectedVoiceName }?.label
            ?: "Voice"
}
