package com.osakaresearch.spark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osakaresearch.spark.mcp.McpTokenStore

/**
 * Developer-facing surface for the local MCP endpoint. Shows the URL + a
 * toggleable bearer token with copy + rotate. Not meant to be pretty —
 * meant to make the token reachable without needing `adb logcat`.
 */
@Composable
fun McpStatusCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { McpTokenStore(ctx) }
    var token by remember { mutableStateOf(store.tokenOrMint()) }
    var revealed by remember { mutableStateOf(false) }

    val endpoint = "http://127.0.0.1:8847/mcp"
    val shown = if (revealed) token else "•".repeat(token.length.coerceAtMost(32))

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("MCP endpoint", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Lets any MCP-speaking client (e.g. Claude Code in Termux) drive compile + flash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(endpoint, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("token", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                SelectionContainer {
                    Text(shown, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Reveal")
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(token))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    token = store.rotate()
                    revealed = false
                }) { Text("Rotate") }
            }
        }
    }
}
