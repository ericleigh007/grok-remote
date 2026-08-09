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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xai.grokremote.data.ConnState
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

    LaunchedEffect(active?.items?.size, active?.items?.lastOrNull()) {
        val n = active?.items?.size ?: 0
        if (n > 0) listState.animateScrollToItem(n - 1)
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
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val items = active?.items.orEmpty()
            if (items.isEmpty()) {
                item { EmptyState() }
            }
            items(items, key = { it.id }) { item ->
                TimelineRow(item, onToggleThought = { vm.toggleThought(item.id) })
            }
        }
        Composer(state, vm)
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Grok Build",
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                fontSize = 17.sp,
            )
            Text(
                "Remote · ${state.connDetail.ifBlank { "…" }}",
                color = Muted,
                fontSize = 11.sp,
            )
        }
        // Voice chip
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
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { vm.toggleTts() }) {
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
        IconButton(onClick = { vm.newSession() }) {
            Icon(Icons.Default.Add, contentDescription = "New session", tint = TextPrimary)
        }
        IconButton(onClick = { vm.unpair() }) {
            Icon(Icons.Default.LinkOff, contentDescription = "Unpair", tint = Muted)
        }
    }
}

private fun shortVoiceLabel(label: String): String {
    // "Aria (en-US · cloud)" -> "Aria"
    return label.substringBefore(" (").substringBefore(" · ").ifBlank { "Voice" }
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
                "Uses the device speech engine. Prefer Neural / Natural / cloud voices when available.",
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
                                        v.label.substringBefore(" ("),
                                        color = TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        buildString {
                                            append(v.locale)
                                            append(if (v.networkRequired) " · cloud" else " · on-device")
                                            append(" · q")
                                            append(v.quality)
                                        },
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
