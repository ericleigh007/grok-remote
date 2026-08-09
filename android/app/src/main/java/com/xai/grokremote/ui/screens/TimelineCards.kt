package com.xai.grokremote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xai.grokremote.data.TimelineItem
import com.xai.grokremote.ui.theme.Accent
import com.xai.grokremote.ui.theme.Muted
import com.xai.grokremote.ui.theme.Panel2
import com.xai.grokremote.ui.theme.TextPrimary
import com.xai.grokremote.ui.theme.ThoughtBg
import com.xai.grokremote.ui.theme.ToolBg

@Composable
fun ThoughtCard(item: TimelineItem.Thought, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ThoughtBg,
        border = BorderStroke(1.dp, Panel2),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggle)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Thinking" + if (item.streaming) "…" else "",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    color = Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = if (item.collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (!item.collapsed) {
                Text(
                    text = item.text,
                    modifier = Modifier.padding(top = 8.dp),
                    color = Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
fun ToolCard(item: TimelineItem.Tool) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ToolBg,
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Panel2, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                val sub = listOfNotNull(item.kind, item.status).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(text = sub, color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    alignEnd: Boolean,
    color: androidx.compose.ui.graphics.Color,
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (alignEnd) 16.dp else 5.dp,
                bottomEnd = if (alignEnd) 5.dp else 16.dp,
            ),
            color = color,
            border = BorderStroke(1.dp, Panel2.copy(alpha = 0.65f)),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = label.uppercase(),
                    modifier = Modifier.padding(bottom = 6.dp),
                    color = Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.7.sp,
                )
                content()
            }
        }
    }
}
