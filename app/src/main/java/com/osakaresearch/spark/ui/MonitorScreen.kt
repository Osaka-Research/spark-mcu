package com.osakaresearch.spark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osakaresearch.spark.net.SparkClient
import com.osakaresearch.spark.net.UsbSerialMonitor
import kotlinx.coroutines.launch

private val BAUDS = listOf(74880, 115200, 9600, 57600, 230400)

@Composable
fun MonitorScreen() {
    var lines by remember { mutableStateOf(listOf("spark monitor ready. pick a baud, tap Start, tap RST on the board.")) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baud by remember { mutableStateOf(74880) }
    val monitorState by UsbSerialMonitor.state.collectAsState()
    val isRunning = monitorState is UsbSerialMonitor.State.Running

    LaunchedEffect(Unit) {
        SparkClient.incoming.collect { msg ->
            lines = (lines + msg).takeLast(500)
        }
    }
    LaunchedEffect(Unit) {
        UsbSerialMonitor.lines.collect { msg ->
            lines = (lines + msg).takeLast(500)
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Monitor",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Everything the chip says. Verbatim.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("baud", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in BAUDS) {
                    FilterChip(
                        selected = baud == b,
                        onClick = { if (!isRunning) baud = b },
                        enabled = !isRunning,
                        label = { Text(b.toString(), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (val s = monitorState) {
                is UsbSerialMonitor.State.Running -> "connected to ${s.device} @ ${s.baud} 8N1"
                is UsbSerialMonitor.State.Error -> "error: ${s.reason}"
                UsbSerialMonitor.State.Idle -> "74880 shows the ESP8266 bootROM boot log (reset reason, flash mode). Switch to 115200 for the sketch's Serial output."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        if (isRunning) UsbSerialMonitor.stop()
                        else UsbSerialMonitor.start(context, baud)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (isRunning) "Stop" else "Start") }
            OutlinedButton(
                onClick = {
                    lines = listOf("── cleared ──")
                },
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Log",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                        },
                    ) { Text("Copy") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val path = LogExport.save(context, "monitor", lines.joinToString("\n"))
                            Toast.makeText(
                                context,
                                if (path != null) "saved to $path" else "save failed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
