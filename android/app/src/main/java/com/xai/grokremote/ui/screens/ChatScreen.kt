package com.xai.grokremote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import com.xai.grokremote.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xai.grokremote.data.AvailableSession
import com.xai.grokremote.data.ConnState
import com.xai.grokremote.data.PendingUserQuestion
import com.xai.grokremote.data.TimelineItem
import com.xai.grokremote.data.UiState
import com.xai.grokremote.data.VoiceOption
import com.xai.grokremote.ui.GrokViewModel
import com.xai.grokremote.ui.components.MarkdownText
import com.xai.grokremote.ui.theme.Accent
import com.xai.grokremote.ui.theme.AgentBubble
import com.xai.grokremote.ui.theme.Bg
import com.xai.grokremote.ui.theme.Danger
import com.xai.grokremote.ui.theme.Muted
import com.xai.grokremote.ui.theme.Ok
import com.xai.grokremote.ui.theme.Panel
import com.xai.grokremote.ui.theme.Panel2
import com.xai.grokremote.ui.theme.TextPrimary
import com.xai.grokremote.ui.theme.ThoughtBg
import com.xai.grokremote.ui.theme.ToolBg
import com.xai.grokremote.ui.theme.UserBubble
import com.xai.grokremote.ui.theme.Warn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: UiState, vm: GrokViewModel) {
    val listState = rememberLazyListState()
    val active = state.active
    var followLatest by remember(state.activeSessionId) { mutableStateOf(true) }
    val pin = remember { ProgrammaticScroll() }
    val last = active?.items?.lastOrNull()
    val lastLen = when (last) {
        is TimelineItem.Thought -> last.text.length
        is TimelineItem.Assistant -> last.text.length
        else -> 0
    }
    val itemCount = active?.items?.size ?: 0

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.nearBottom() }
            .collect { (inProgress, near) ->
                if (pin.active) return@collect
                followLatest = if (inProgress) {
                    if (!near) false else followLatest
                } else {
                    near
                }
            }
    }

    LaunchedEffect(followLatest, itemCount, last?.id, lastLen) {
        if (!followLatest || itemCount <= 0) return@LaunchedEffect
        pin.active = true
        try {
            listState.scrollToLatest()
        } finally {
            pin.active = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        TopBar(state, vm)
        SessionTabs(state, vm)
        StatusStrip(state)
        HorizontalDivider(color = Panel2.copy(alpha = 0.8f), thickness = 1.dp)
        val showPicker = state.showSessionPicker || (active == null && !state.openingSession)
        if (showPicker) {
            SessionPicker(state, vm, Modifier.weight(1f))
        } else {
            if (state.openingSession) {
                Text(
                    "Opening session…",
                    color = Muted,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val items = active?.items.orEmpty()
                    if (items.isEmpty() && !state.openingSession) {
                        item { EmptyState() }
                    }
                    items(items, key = { it.id }) { item ->
                        TimelineRow(item, onToggleThought = { vm.toggleThought(item.id) })
                    }
                }
                if (!followLatest) {
                    Surface(
                        onClick = { followLatest = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(40.dp),
                        shape = CircleShape,
                        color = Accent,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to latest",
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
            state.pendingQuestion?.let { QuestionPromptCard(it, vm) }
            Composer(state, vm)
        }
    }

    if (state.showVoicePicker) {
        VoicePickerSheet(
            voices = state.ttsVoices,
            selected = state.selectedVoiceName,
            onSelect = { vm.selectVoice(it) },
            onPreview = { vm.previewSelectedVoice() },
            onDismiss = { vm.dismissVoicePicker() },
        )
    }
}

@Composable
private fun SessionPicker(state: UiState, vm: GrokViewModel, modifier: Modifier = Modifier) {
    val q = state.sessionQuery.trim()
    val rows = if (q.isEmpty() || q.equals(state.catalogQuery, ignoreCase = true)) {
        state.availableSessions
    } else {
        state.availableSessions.filter { it.matchesQuery(q) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Re-enter a session", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            "Search titles, folders, and transcripts. Only the session you open is loaded.",
            color = Muted,
            fontSize = 13.sp,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Panel2, RoundedCornerShape(12.dp))
                .border(1.dp, Panel2, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = state.sessionQuery,
                onValueChange = { vm.setSessionQuery(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.setSessionQuery(state.sessionQuery, immediate = true) },
                ),
                decorationBox = { inner ->
                    if (state.sessionQuery.isEmpty()) {
                        Text("Search sessions…", color = Muted, fontSize = 14.sp)
                    }
                    inner()
                },
            )
            if (state.searchingSessions) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                    color = Accent,
                    strokeWidth = 2.dp,
                )
            } else if (state.sessionQuery.isNotEmpty()) {
                IconButton(onClick = { vm.setSessionQuery("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Muted)
                }
            }
        }
        val status = when {
            q.isNotEmpty() && state.searchingSessions -> "Searching…"
            q.isNotEmpty() && rows.isEmpty() -> "No sessions match “$q”"
            q.isNotEmpty() -> {
                val n = if (state.catalogQuery.equals(q, ignoreCase = true)) {
                    state.availableTotal
                } else {
                    rows.size
                }
                if (n == 1) "1 match" else "$n matches"
            }
            else -> null
        }
        if (status != null) {
            Text(status, color = Muted, fontSize = 12.sp)
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rows, key = { it.sessionId ?: "${it.cwd}-${it.title}" }) { item ->
                SessionPickRow(item, onClick = { vm.enterAvailable(item) })
            }
            item {
                val hidden = (state.availableTotal - state.availableSessions.size).coerceAtLeast(0)
                if (q.isEmpty() && (state.catalogTruncated || hidden > 0)) {
                    Surface(
                        onClick = { vm.showAllSessions() },
                        color = Panel2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (hidden > 0) "Show all sessions ($hidden more)" else "Show all sessions",
                            color = Accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            item {
                Surface(
                    onClick = { vm.newSession() },
                    color = Panel,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Panel2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "New session",
                        color = TextPrimary,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

private fun AvailableSession.matchesQuery(q: String): Boolean {
    if (q.isBlank()) return true
    return title.contains(q, ignoreCase = true) ||
        cwd.contains(q, ignoreCase = true) ||
        (preview?.contains(q, ignoreCase = true) == true) ||
        (sessionId?.contains(q, ignoreCase = true) == true)
}

@Composable
private fun SessionPickRow(item: AvailableSession, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Panel,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Panel2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val cwdShort = item.cwd.substringAfterLast('\\').substringAfterLast('/')
            val meta = buildString {
                if (cwdShort.isNotBlank()) append(cwdShort)
                if (item.messageCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${item.messageCount} msgs")
                }
            }
            if (meta.isNotBlank()) {
                Text(meta, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.preview?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Panel2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ready", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Type a message or tap the mic. While Grok is working, Send cancels the current turn and injects your new instruction midstream.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun TopBar(state: UiState, vm: GrokViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Text(
            "Grok Remote",
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
        Text(
            state.connDetail.ifBlank { "…" },
            color = Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                onClick = { if (!state.catchingUp) vm.catchUp() },
                enabled = state.activeSessionId != null && state.conn == ConnState.Online,
                color = Color(0xFF009640),
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(
                    Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.catchingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_emergency_exit),
                            contentDescription = "Catch up from PC",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = { vm.openVoicePicker() },
                color = Panel2,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, if (state.ttsEnabled) Accent.copy(alpha = 0.45f) else Panel2),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = "Voice",
                        tint = if (state.ttsEnabled) Accent else Muted,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = shortVoiceLabel(state.selectedVoiceLabel),
                        color = TextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
            }
            IconButton(onClick = { vm.toggleTts() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.ttsEnabled) {
                        Icons.AutoMirrored.Filled.VolumeUp
                    } else {
                        Icons.AutoMirrored.Filled.VolumeOff
                    },
                    contentDescription = "TTS on/off",
                    tint = if (state.ttsEnabled) Accent else Muted,
                )
            }
            IconButton(onClick = { vm.toggleThinkingSound() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.thinkingSoundEnabled) {
                        Icons.Default.NotificationsActive
                    } else {
                        Icons.Default.NotificationsOff
                    },
                    contentDescription = if (state.thinkingSoundEnabled) {
                        "Thinking beep on"
                    } else {
                        "Thinking beep off"
                    },
                    tint = if (state.thinkingSoundEnabled) Accent else Muted,
                )
            }
            IconButton(onClick = { vm.newSession() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New session", tint = TextPrimary)
            }
            IconButton(onClick = { vm.unpair() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.LinkOff, contentDescription = "Unpair", tint = Muted)
            }
        }
    }
}

private fun shortVoiceLabel(label: String): String {
    // "English (United States) · Female · 2" -> keep short for chip
    val parts = label.split(" · ")
    return when {
        parts.size >= 2 -> parts.take(2).joinToString(" · ")
        else -> label.take(28).ifBlank { "Voice" }
    }
}

@Composable
private fun StatusStrip(state: UiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(state.conn)
        if (state.agentAlive == false) {
            MiniPill("agent down", Danger)
        } else if (state.agentAlive == true) {
            MiniPill(state.agentTransport ?: "agent", Ok)
        }
        if (state.catchingUp) {
            MiniPill("catching up", Color(0xFF009640))
        }
        if (state.active?.busy == true) {
            MiniPill("working", Warn)
        }
        Spacer(Modifier.weight(1f))
        val n = state.sessions.size
        Text("$n session${if (n == 1) "" else "s"}", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun StatusPill(conn: ConnState) {
    val (label, color) = when (conn) {
        ConnState.Online -> "online" to Ok
        ConnState.Connecting -> "connecting" to Warn
        ConnState.Error -> "error" to Danger
        ConnState.Disconnected -> "offline" to Danger
    }
    MiniPill(label, color)
}

@Composable
private fun MiniPill(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SessionTabs(state: UiState, vm: GrokViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel.copy(alpha = 0.55f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.showSessionPicker,
            onClick = { vm.openSessionPicker() },
            label = { Text("Sessions") },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Panel2,
                labelColor = Muted,
                selectedContainerColor = Accent.copy(alpha = 0.18f),
                selectedLabelColor = TextPrimary,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = state.showSessionPicker,
                borderColor = Panel2,
                selectedBorderColor = Accent.copy(alpha = 0.5f),
            ),
        )
        state.sessions.values.forEach { s ->
            val selected = s.sessionId == state.activeSessionId
            FilterChip(
                selected = selected,
                onClick = { vm.selectSession(s.sessionId) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.busy) {
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .size(7.dp)
                                    .background(Warn, CircleShape),
                            )
                        }
                        Text(
                            s.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Panel2,
                    labelColor = Muted,
                    selectedContainerColor = Accent.copy(alpha = 0.18f),
                    selectedLabelColor = TextPrimary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = Panel2,
                    selectedBorderColor = Accent.copy(alpha = 0.5f),
                ),
            )
        }
    }
    state.active?.cwd?.takeIf { it.isNotBlank() }?.let { cwd ->
        Text(
            cwd,
            color = Muted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, onToggleThought: () -> Unit) {
    when (item) {
        is TimelineItem.User -> MessageBubble(alignEnd = true, color = UserBubble, label = "You") {
            Text(item.text, color = TextPrimary, lineHeight = 20.sp)
        }
        is TimelineItem.Assistant -> MessageBubble(alignEnd = false, color = AgentBubble, label = "Grok") {
            MarkdownText(
                markdown = item.text.ifBlank { if (item.streaming) "…" else "" },
            )
            if (item.streaming) {
                Text("streaming…", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        is TimelineItem.Thought -> ThoughtCard(item, onToggleThought)
        is TimelineItem.Tool -> ToolCard(item)
        is TimelineItem.System -> {
            Text(
                item.text,
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QuestionPromptCard(q: PendingUserQuestion, vm: GrokViewModel) {
    val picks = androidx.compose.runtime.remember(q.requestId) {
        androidx.compose.runtime.mutableStateMapOf<String, String>()
    }
    Surface(
        color = Warn.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, Warn.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Grok needs your answer", color = Warn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            q.questions.forEach { question ->
                Text(question.prompt, color = TextPrimary, fontSize = 14.sp)
                if (question.options.isEmpty()) {
                    BasicTextField(
                        value = picks[question.id] ?: "",
                        onValueChange = { picks[question.id] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Panel2, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        cursorBrush = SolidColor(Accent),
                        decorationBox = { inner ->
                            if (picks[question.id].isNullOrEmpty()) {
                                Text("Type an answer…", color = Muted, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        question.options.forEach { opt ->
                            FilterChip(
                                selected = picks[question.id] == opt.label,
                                onClick = { picks[question.id] = opt.label },
                                label = { Text(opt.label, maxLines = 2) },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.skipPendingQuestion() }) {
                    Text("Skip", color = Muted)
                }
                Surface(
                    onClick = {
                        val answers = q.questions.mapNotNull { picks[it.id]?.takeIf { a -> a.isNotBlank() } }
                        vm.answerPendingQuestion(answers)
                    },
                    color = Accent,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "Send answer",
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(state: UiState, vm: GrokViewModel) {
    val busy = state.active?.busy == true
    Surface(
        color = Panel,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Surface(
                        onClick = { vm.cancel() },
                        color = Danger.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Danger.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Stop, null, tint = Danger, modifier = Modifier.size(14.dp))
                            Text("Cancel turn", color = Danger, fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Send = interrupt + new thought",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { vm.toggleMic() },
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (state.listening) Danger else Panel2,
                            RoundedCornerShape(14.dp),
                        ),
                ) {
                    Icon(
                        if (state.listening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mic",
                        tint = TextPrimary,
                    )
                }
                BasicTextField(
                    value = state.draft,
                    onValueChange = { vm.setDraft(it) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp, max = 140.dp)
                        .background(Panel2, RoundedCornerShape(14.dp))
                        .border(1.dp, if (busy) Warn.copy(alpha = 0.35f) else Panel2, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Accent),
                    decorationBox = { inner ->
                        if (state.draft.isEmpty()) {
                            Text(
                                if (busy) "Inject new instruction…" else "Message Grok…",
                                color = Muted,
                            )
                        }
                        inner()
                    },
                )
                IconButton(
                    onClick = { vm.send(interruptIfBusy = busy) },
                    enabled = state.draft.isNotBlank() && state.conn == ConnState.Online,
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (state.draft.isNotBlank() && state.conn == ConnState.Online) Accent else Panel2,
                            RoundedCornerShape(14.dp),
                        ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = TextPrimary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    voices: List<VoiceOption>,
    selected: String?,
    onSelect: (String) -> Unit,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        contentColor = TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("TTS voice", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = TextPrimary)
            Text(
                "Tap a voice to select and hear a preview. On-device voices work offline; Network needs data.",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                TextButton(onClick = onPreview) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Preview")
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
            if (voices.isEmpty()) {
                Text(
                    "No voices loaded yet. Open system Settings → Text-to-speech and install a voice pack, then reopen this sheet.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    voices.forEach { v ->
                        val isSelected = v.name == selected
                        Surface(
                            onClick = { onSelect(v.name) },
                            color = if (isSelected) Accent.copy(alpha = 0.14f) else Panel2,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) Accent.copy(alpha = 0.5f) else Panel2,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = v.label,
                                        color = TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        text = v.detail,
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class ProgrammaticScroll {
    var active: Boolean = false
}

private fun LazyListState.nearBottom(thresholdPx: Int = 120): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    val lastIndex = info.totalItemsCount - 1
    if (lastVisible.index < lastIndex) return false
    val bottom = lastVisible.offset + lastVisible.size
    return bottom <= info.viewportEndOffset + thresholdPx
}

private suspend fun LazyListState.scrollToLatest() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    val visibleLast = layoutInfo.visibleItemsInfo.lastOrNull()
    if (visibleLast != null && visibleLast.index == lastIndex) {
        val extra = (visibleLast.offset + visibleLast.size) - layoutInfo.viewportEndOffset
        if (extra > 0) scrollBy(extra.toFloat())
        return
    }
    scrollToItem(lastIndex)
    withFrameNanos { }
    val lastItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val extra = (lastItem.offset + lastItem.size) - layoutInfo.viewportEndOffset
    if (extra > 0) scrollBy(extra.toFloat())
}
