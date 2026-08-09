package com.xai.grokremote.ui.components

import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.xai.grokremote.ui.theme.TextPrimary
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    val textColor = AndroidColor.rgb(
        (TextPrimary.red * 255).toInt(),
        (TextPrimary.green * 255).toInt(),
        (TextPrimary.blue * 255).toInt(),
    )
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.25f)
                setTextIsSelectable(true)
                // link color
                setLinkTextColor(AndroidColor.rgb(0x6E, 0xA8, 0xFF))
            }
        },
        update = { tv ->
            markwon.setMarkdown(tv, markdown.ifBlank { " " })
        },
    )
}
