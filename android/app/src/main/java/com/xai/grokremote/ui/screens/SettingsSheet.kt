package com.xai.grokremote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xai.grokremote.data.UiState
import com.xai.grokremote.ui.GrokViewModel
import com.xai.grokremote.ui.theme.Accent
import com.xai.grokremote.ui.theme.Danger
import com.xai.grokremote.ui.theme.Muted
import com.xai.grokremote.ui.theme.Panel2
import com.xai.grokremote.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    state: UiState,
    vm: GrokViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Settings", color = TextPrimary, fontSize = 20.sp)
            Text(
                "New behavior is on by default. Turn a switch off if it misbehaves.",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SectionLabel("Mic")
            SettingToggle(
                title = "Allow pauses while speaking",
                detail = "Keeps listening across gaps, then sends one prompt when you tap the mic or pause for a few seconds after the last words.",
                checked = state.pauseTolerantStt,
                onCheckedChange = vm::setPauseTolerantStt,
            )
            SettingToggle(
                title = "Auto-send when you finish",
                detail = "Sends one prompt when you tap the mic to stop, or when the pause timeout below elapses.",
                checked = state.autoSendVoice,
                onCheckedChange = vm::setAutoSendVoice,
            )
            Text(
                "Send after pause",
                color = TextPrimary,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "How long to wait after the last words before sending. Short pauses while you think should be under this.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            PauseTimeoutChips(
                selectedMs = state.voiceIdleSendMs,
                enabled = state.pauseTolerantStt && state.autoSendVoice,
                onSelect = vm::setVoiceIdleSendMs,
            )

            SectionLabel("Chat")
            SettingToggle(
                title = "Hold your place when you scroll up",
                detail = "Long replies stay put after you scroll. Off = always jump to the latest line.",
                checked = state.holdScroll,
                onCheckedChange = vm::setHoldScroll,
            )

            SectionLabel("Sound")
            SettingToggle(
                title = "Speak replies",
                detail = "Read assistant messages with the selected voice.",
                checked = state.ttsEnabled,
                onCheckedChange = { vm.toggleTts() },
            )
            SettingToggle(
                title = "Thinking beep",
                detail = "Short beep while Grok is working and has not started a reply.",
                checked = state.thinkingSoundEnabled,
                onCheckedChange = { vm.toggleThinkingSound() },
            )
            TextButton(onClick = {
                onDismiss()
                vm.openVoicePicker()
            }) {
                Text("Choose voice…", color = Accent)
            }

            SectionLabel("Account")
            TextButton(onClick = {
                onDismiss()
                vm.unpair()
            }) {
                Text("Unpair this phone", color = Danger)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PauseTimeoutChips(
    selectedMs: Long,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
) {
    val options = listOf(
        3_000L to "3s",
        5_000L to "5s",
        8_000L to "8s",
        12_000L to "12s",
        0L to "Tap only",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (ms, label) ->
            FilterChip(
                selected = selectedMs == ms,
                onClick = { onSelect(ms) },
                enabled = enabled,
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Panel2,
                    labelColor = Muted,
                    selectedContainerColor = Accent.copy(alpha = 0.18f),
                    selectedLabelColor = TextPrimary,
                    disabledContainerColor = Panel2,
                    disabledLabelColor = Muted.copy(alpha = 0.5f),
                    disabledSelectedContainerColor = Accent.copy(alpha = 0.08f),
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Accent,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = Accent,
            ),
        )
    }
}
