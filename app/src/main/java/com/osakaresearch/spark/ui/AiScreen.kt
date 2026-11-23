package com.osakaresearch.spark.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.saveable.rememberSaveable
import com.osakaresearch.spark.compile.McuTarget
import com.osakaresearch.spark.net.UsbSerialMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.osakaresearch.spark.ai.*
import com.osakaresearch.spark.mcp.McpBridge
import com.osakaresearch.spark.mcp.McpTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@Composable
fun AiScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { AiKeyStore(appContext) }
    val planStore = remember { ChatGptPlanStore(appContext) }
    val scope = rememberCoroutineScope()
    val agent by SparkAgentSession.state.collectAsState()
    val signIn by ChatGptSignInSession.state.collectAsState()
    val monitor by UsbSerialMonitor.state.collectAsState()
    val target by SparkDeviceContext.selectedTarget.collectAsState()
    val listState = rememberLazyListState()
    var provider by remember { mutableStateOf(store.selected) }
    var apiKey by remember(provider) { mutableStateOf(store.apiKey(provider)) }
    var baseUrl by remember(provider) { mutableStateOf(store.baseUrl(provider)) }
    var model by remember(provider) { mutableStateOf(if (provider == AiProvider.ChatGPTPlan) planStore.model() else store.model(provider)) }
    var connectedEmail by remember { mutableStateOf(planStore.email()) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<AiModel>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var authError by remember { mutableStateOf("") }
    var showCallback by remember { mutableStateOf(false) }
    var callbackUrl by remember { mutableStateOf("") }
    var showGoal by remember { mutableStateOf(false) }
    var goalDraft by remember { mutableStateOf("") }
    var showContext by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var photoData by remember { mutableStateOf<String?>(null) }
    var liveMode by remember { mutableStateOf(false) }
    var liveCapture by remember { mutableStateOf<(() -> Unit)?>(null) }
    var cameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraPermission = it }
    val plan = provider == AiProvider.ChatGPTPlan
    val ready = !agent.busy && model.isNotBlank() && (if (plan) connectedEmail.isNotBlank() else apiKey.isNotBlank())
    val response = agent.streamingText.ifBlank { agent.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty() }
    val device = remember(agent.deviceContext) { runCatching { JSONObject(agent.deviceContext) }.getOrDefault(JSONObject()) }
    val usb = device.optJSONArray("usbDevices") ?: JSONArray()
    val detected = device.optJSONObject("detectedChip")?.optString("target").orEmpty()

    fun loadModels() {
        if (loadingModels) return
        loadingModels = true
        scope.launch {
            try {
                models = AiClient.listChatGptModels(ChatGptPlanAuth.accessToken(planStore))
                if (model.isBlank()) models.firstOrNull()?.let { model = it.slug; planStore.setModel(model) }
                modelMenu = true
            } catch (error: Exception) { authError = error.message ?: "Could not load models" }
            finally { loadingModels = false }
        }
    }
    fun askAssistant(question: String, image: String? = photoData) {
        if (!ready) { settings = true; return }
        SparkAgentSession.ask(appContext, AiConfig(provider, if (plan) "pending" else apiKey.trim(),
            baseUrl.ifBlank { provider.defaultBaseUrl }.trim(), model.trim()), question, image)
        prompt = ""; photoData = null
    }
    LaunchedEffect(Unit) {
        SparkAgentSession.initialize(appContext)
        while (isActive) { SparkAgentSession.refreshContext(appContext); delay(5000) }
    }
    LaunchedEffect(signIn, provider) {
        connectedEmail = planStore.email()
        if (plan && connectedEmail.isNotBlank()) {
            model = planStore.model()
            if (model.isBlank()) loadModels()
        }
    }
    LaunchedEffect(agent.messages.size, agent.actions.size, agent.streamingText.length / 160, agent.status) {
        if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    fun attachBitmap(original: Bitmap) {
        scope.launch {
            try {
                photoData = withContext(Dispatchers.Default) {
                    val scale = minOf(1f, 1280f / maxOf(original.width, original.height))
                    val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }.toByteArray()
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            } catch (error: Exception) { authError = "Could not attach photo: ${error.message}" }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) }
                attachBitmap(bitmap)
            } catch (error: Exception) { authError = "Could not read photo: ${error.message}" }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> bitmap?.let(::attachBitmap) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spark", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(model.ifBlank { "Connect an AI model to begin" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = !agent.busy, onClick = { SparkAgentSession.newTask(appContext) }) { Text("New task") }
            TextButton(onClick = { settings = true }) { Text("Settings") }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val wifiBoard = device.optJSONObject("wifiBoard")
                    Text(when {
                        detected.isNotBlank() -> "$detected · ROM verified over USB"
                        wifiBoard != null -> "${wifiBoard.optString("board", "Spark MCU")} · Wi-Fi connected"
                        usb.length() > 0 -> "USB connected · chip not identified"
                        else -> "No board connected"
                    }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { showContext = !showContext }) { Text(if (showContext) "Less" else "Details") }
                }
                Text(when (val state = monitor) {
                    is UsbSerialMonitor.State.Running -> "Serial monitor · ${state.baud} baud"
                    is UsbSerialMonitor.State.Error -> "Monitor: ${state.reason}"
                    else -> if (device.optJSONObject("wifiBoard") != null) "Spark MCU connection ready" else "Connect a board to let Spark build and test it"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showContext) {
                    for (index in 0 until usb.length()) {
                        val entry = usb.getJSONObject(index)
                        Text("${entry.optString("name")} · ${if (entry.optBoolean("permissionGranted")) "USB access granted" else "USB permission needed"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Selected target: ${target.label}. The selection alone does not identify the connected chip.", style = MaterialTheme.typography.bodySmall)
                    Text("Retained builds: ${device.optJSONArray("recentBuilds")?.length() ?: 0}", style = MaterialTheme.typography.bodySmall)
                }
                if (showContext) TextButton(onClick = { goalDraft = agent.goal; showGoal = true }) {
                    Text(agent.goal.ifBlank { "Add project goal or wiring notes" }, maxLines = 2)
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (agent.messages.isEmpty()) item {
                Text("What should the board do?", style = MaterialTheme.typography.titleLarge)
                Text("Spark can inspect, build, flash with your approval, and debug it.", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(enabled = ready, onClick = { askAssistant("Inspect the connected board and current project. Then work end to end to make the board do what I describe, compile, flash with confirmation, and debug from fresh observations until verified or blocked by a specific hardware fact.") }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Build, run & debug current project")
                }
            }
            items(agent.messages) { message ->
                Column(Modifier.fillMaxWidth()) {
                    Text(when (message.role) { "user" -> "YOU"; "progress" -> "SPARK · PROGRESS"; else -> "SPARK" }, style = MaterialTheme.typography.labelSmall, color = if (message.role == "user") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    AgentMessageText(message.text)
                }
            }
            if (agent.plan.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Task progress", style = MaterialTheme.typography.labelLarge)
                        agent.plan.forEach { step ->
                            Text("${when (step.status) { "completed" -> "✓"; "in_progress" -> "→"; "blocked" -> "!"; else -> "○" }} ${step.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.status == "blocked") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (agent.actions.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Actions this turn", style = MaterialTheme.typography.labelLarge)
                        agent.actions.forEach { action ->
                            Text("${when (action.ok) { true -> "✓"; false -> "!"; null -> "…" }} ${action.label}", style = MaterialTheme.typography.bodySmall)
                            if (action.result.isNotBlank()) Text(action.result, style = MaterialTheme.typography.labelSmall, color = if (action.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (agent.busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(agent.status, style = MaterialTheme.typography.labelMedium)
                    if (agent.streamingText.isNotBlank()) AgentMessageText(agent.streamingText)
                }
            }
            if (liveMode && cameraPermission) item {
                LiveCameraPreview(Modifier.fillMaxWidth().height(300.dp), response.ifBlank { "Aim at the board, then capture a frame" },
                    onCaptureReady = { liveCapture = it },
                    onFrame = { data -> askAssistant(prompt.ifBlank { "Inspect this board photo in the context of our task. Explain visible evidence and what needs checking next." }, data) })
                OutlinedButton(enabled = ready, onClick = { liveCapture?.invoke() }) { Text("Capture & analyze") }
            }
            if (authError.isNotBlank()) item { Text(authError, color = MaterialTheme.colorScheme.error) }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (photoData != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Photo attached", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { photoData = null }) { Text("Remove") }
                }
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask Spark to build or fix something…") }, minLines = 1, maxLines = 5,
                    shape = RoundedCornerShape(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (plan) {
                        Box {
                            TextButton(enabled = !agent.busy, onClick = { attachMenu = true }) { Text("Attach") }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(text = { Text("Choose photo") }, onClick = { attachMenu = false; gallery.launch("image/*") })
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = { attachMenu = false; camera.launch(null) })
                                DropdownMenuItem(text = { Text(if (liveMode) "Close live camera" else "Open live camera") }, onClick = {
                                    attachMenu = false
                                    liveMode = !liveMode
                                    if (liveMode && !cameraPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
                                })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (agent.busy) OutlinedButton(enabled = !agent.writingFirmware, onClick = { SparkAgentSession.stop() }) { Text(if (agent.writingFirmware) "Writing…" else "Stop") }
                    else Button(enabled = prompt.isNotBlank(), onClick = { askAssistant(prompt.trim()) }) { Text(if (ready) "Send" else "Connect") }
                }
            }
        }
    }

    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Agent settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !agent.busy, onClick = { providerMenu = true }) { Text(provider.displayName) }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                AiProvider.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.displayName) }, onClick = { provider = choice; store.selected = choice; providerMenu = false }) }
            }
            if (plan) {
                if (connectedEmail.isBlank()) {
                    Button(enabled = !signIn.busy, onClick = { authError = ""; ChatGptSignInSession.start(appContext, planStore) }) { Text(if (signIn.busy) "Connecting…" else "Continue with ChatGPT") }
                    if (signIn.busy || planStore.pendingSignIn() != null) {
                        Text("If the browser keeps loading, paste its callback address here.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { showCallback = true }) { Text("Paste callback link") }
                        if (signIn.busy) TextButton(onClick = { ChatGptSignInSession.cancel() }) { Text("Cancel sign-in") }
                    }
                    if (signIn.error.isNotBlank()) Text(signIn.error, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(connectedEmail, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !agent.busy && !loadingModels, onClick = ::loadModels) { Text(if (loadingModels) "Loading models…" else model.ifBlank { "Choose model" }) }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { item -> DropdownMenuItem(text = { Text(item.displayName) }, onClick = { model = item.slug; planStore.setModel(model); modelMenu = false }) }
                    }
                    TextButton(enabled = !agent.busy, onClick = { planStore.clear(); connectedEmail = ""; model = "" }) { Text("Disconnect ChatGPT") }
                }
            } else {
                Text("This provider supports chat guidance. Connect ChatGPT for board tools and agent actions.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; store.setApiKey(provider, it) }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; store.setBaseUrl(provider, it) }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it; store.setModel(provider, it) }, label = { Text("Model") }, singleLine = true)
            }
            Text("Build target", style = MaterialTheme.typography.labelLarge)
            listOf(McuTarget.ESP8266_NODEMCU, McuTarget.ESP32_S3).forEach { choice ->
                FilterChip(selected = target == choice, enabled = !agent.busy, onClick = { SparkDeviceContext.selectTarget(appContext, choice); SparkAgentSession.refreshContext(appContext) }, label = { Text(choice.label) })
            }
            if (authError.isNotBlank()) Text(authError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (showCallback) AlertDialog(onDismissRequest = { showCallback = false; callbackUrl = "" }, title = { Text("Finish ChatGPT sign-in") },
        text = { OutlinedTextField(value = callbackUrl, onValueChange = { callbackUrl = it }, label = { Text("Browser callback URL") }, singleLine = true) },
        confirmButton = { Button(enabled = callbackUrl.isNotBlank(), onClick = { ChatGptSignInSession.finish(planStore, callbackUrl.trim()); callbackUrl = ""; showCallback = false }) { Text("Finish sign-in") } },
        dismissButton = { TextButton(onClick = { showCallback = false; callbackUrl = "" }) { Text("Cancel") } })
    if (showGoal) AlertDialog(onDismissRequest = { showGoal = false }, title = { Text("Project goal & hardware notes") },
        text = { OutlinedTextField(value = goalDraft, onValueChange = { goalDraft = it }, placeholder = { Text("What should the board do? Include the board label and any known wiring or pin assignments.") }, minLines = 4, maxLines = 8) },
        confirmButton = { Button(onClick = { SparkAgentSession.setGoal(appContext, goalDraft); showGoal = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showGoal = false }) { Text("Cancel") } })
    agent.confirmation?.let { confirmation ->
        var allowRepairs by remember(confirmation) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { SparkAgentSession.approveFlash(false) }, title = { Text(confirmation.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(confirmation.detail)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowRepairs, onCheckedChange = { allowRepairs = it })
                    Text("Allow corrected firmware for this task on this board connection", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
            confirmButton = { Button(onClick = { SparkAgentSession.approveFlash(true, allowRepairs) }) { Text("Flash firmware") } },
            dismissButton = { TextButton(onClick = { SparkAgentSession.approveFlash(false) }) { Text("Cancel") } })
    }
}

@Composable
private fun AgentMessageText(text: String) {
    val clipboard = LocalClipboardManager.current
    val blocks = remember(text) { text.split("```") }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, block ->
                if (index % 2 == 0) {
                    if (block.isNotBlank()) Text(block.trim().replace("**", ""), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val code = block.substringAfter('\n', block).trimEnd()
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Code", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
                            }
                            Text(code, modifier = Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCameraPreview(
    modifier: Modifier,
    overlay: String,
    onCaptureReady: (() -> Unit) -> Unit,
    onFrame: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnCaptureReady by rememberUpdatedState(onCaptureReady)
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    DisposableEffect(lifecycleOwner) {
        onDispose { executor.shutdown() }
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                    providerFuture.addListener({
                        val cameraProvider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                        runCatching {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }
                        currentOnCaptureReady {
                            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    try {
                                        val buffer = image.planes.firstOrNull()?.buffer
                                        if (buffer != null) {
                                            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                                            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                                            ContextCompat.getMainExecutor(viewContext).execute { currentOnFrame(dataUrl) }
                                        }
                                    } finally { image.close() }
                                }
                                override fun onError(exception: ImageCaptureException) = Unit
                            })
                        }
                    }, ContextCompat.getMainExecutor(viewContext))
                }
            },
        )
        Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f), modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()) {
            Text(overlay, color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
