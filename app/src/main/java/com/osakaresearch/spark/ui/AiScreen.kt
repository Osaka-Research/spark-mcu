package com.osakaresearch.spark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.osakaresearch.spark.ai.AiClient
import com.osakaresearch.spark.ai.AiConfig
import com.osakaresearch.spark.ai.AiKeyStore
import com.osakaresearch.spark.ai.AiMessage
import com.osakaresearch.spark.ai.AiProvider
import com.osakaresearch.spark.ai.AiResult
import kotlinx.coroutines.launch

@Composable
fun AiScreen() {
    val context = LocalContext.current
    val store = remember(context) { AiKeyStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(store.selected) }
    var expanded by remember { mutableStateOf(false) }
    var apiKey by remember(provider) { mutableStateOf(store.apiKey(provider)) }
    var baseUrl by remember(provider) { mutableStateOf(store.baseUrl(provider)) }
    var model by remember(provider) { mutableStateOf(store.model(provider)) }
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI", style = MaterialTheme.typography.titleLarge)
        Text("Ask a model for help with a sketch. Review its answer before using it on a device.")
        OutlinedButton(onClick = { expanded = true }) { Text(provider.displayName) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiProvider.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.displayName) }, onClick = {
                    provider = choice
                    store.selected = choice
                    expanded = false
                })
            }
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; store.setApiKey(provider, it) },
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; store.setBaseUrl(provider, it) },
            label = { Text("Base URL") },
            placeholder = { Text(provider.defaultBaseUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; store.setModel(provider, it) },
            label = { Text("Model") },
            placeholder = { Text(provider.defaultModel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && prompt.isNotBlank(),
            onClick = {
                val config = AiConfig(
                    provider = provider,
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.ifBlank { provider.defaultBaseUrl }.trim(),
                    model = model.ifBlank { provider.defaultModel }.trim(),
                )
                val question = prompt.trim()
                busy = true
                response = ""
                scope.launch {
                    try {
                        response = when (val result = AiClient.complete(config, listOf(AiMessage.user(question)))) {
                            is AiResult.Ok -> result.text
                            is AiResult.Err -> "Request failed (${result.code}): ${result.reason}"
                        }
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Waiting…" else "Ask") }
        if (response.isNotBlank()) {
            Text("Response", style = MaterialTheme.typography.titleMedium)
            Text(response)
        }
    }
}
