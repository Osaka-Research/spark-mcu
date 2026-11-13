package com.osakaresearch.spark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osakaresearch.spark.net.SparkClient
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ControlScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Control",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Drive the motors. Move a servo. Flip a pin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Joystick()
        ServoControl()
        PinControl()
    }
}

@Composable
private fun Joystick() {
    var left by remember { mutableStateOf(0) }
    var right by remember { mutableStateOf(0) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    val stickColor = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Drive", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .pointerInput(Unit) {
                        val sizePx = this.size
                        val cx = sizePx.width / 2f
                        val cy = sizePx.height / 2f
                        val radius = min(cx, cy) - 20f
                        detectDragGestures(
                            onDragStart = { pos ->
                                knob = Offset(pos.x - cx, pos.y - cy).clamp(radius)
                                updateMotors(knob, radius) { l, r -> left = l; right = r; sendMotor(l, r) }
                            },
                            onDragEnd = {
                                knob = Offset.Zero
                                left = 0; right = 0; sendMotor(0, 0)
                            },
                            onDrag = { change, _ ->
                                knob = Offset(change.position.x - cx, change.position.y - cy).clamp(radius)
                                updateMotors(knob, radius) { l, r -> left = l; right = r; sendMotor(l, r) }
                            },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = min(cx, cy) - 20f
                    drawCircle(
                        color = Color(0xFF26262B),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                    drawCircle(
                        color = stickColor,
                        radius = 32f,
                        center = Offset(cx + knob.x, cy + knob.y),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "L $left   R $right",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}

private fun Offset.clamp(radius: Float): Offset {
    val d = hypot(x.toDouble(), y.toDouble()).toFloat()
    return if (d <= radius) this else Offset(x / d * radius, y / d * radius)
}

private inline fun updateMotors(knob: Offset, radius: Float, out: (Int, Int) -> Unit) {
    val nx = (knob.x / radius).coerceIn(-1f, 1f)
    val ny = (-knob.y / radius).coerceIn(-1f, 1f)
    val l = ((ny + nx) * 255f).roundToInt().coerceIn(-255, 255)
    val r = ((ny - nx) * 255f).roundToInt().coerceIn(-255, 255)
    out(l, r)
}

private fun sendMotor(l: Int, r: Int) {
    SparkClient.send("""{"cmd":"motor","left":$l,"right":$r}""")
}

@Composable
private fun ServoControl() {
    var pin by remember { mutableStateOf("13") }
    var angle by remember { mutableFloatStateOf(90f) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Servo", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() } },
                    label = { Text("Pin") },
                    modifier = Modifier.width(90.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${angle.roundToInt()}°",
                    fontFamily = FontFamily.Monospace,
                )
            }
            Slider(
                value = angle,
                onValueChange = {
                    angle = it
                    val p = pin.toIntOrNull() ?: return@Slider
                    SparkClient.send("""{"cmd":"servo","pin":$p,"angle":${it.roundToInt()}}""")
                },
                valueRange = 0f..180f,
            )
        }
    }
}

@Composable
private fun PinControl() {
    var pin by remember { mutableStateOf("2") }
    var level by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Digital pin", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() } },
                    label = { Text("Pin") },
                    modifier = Modifier.width(90.dp),
                )
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Switch(
                    checked = level,
                    onCheckedChange = {
                        level = it
                        val p = pin.toIntOrNull() ?: return@Switch
                        val v = if (it) 1 else 0
                        SparkClient.send("""{"cmd":"pin","pin":$p,"mode":"out","value":$v}""")
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(if (level) "HIGH" else "LOW", fontFamily = FontFamily.Monospace)
            }
        }
    }
}
