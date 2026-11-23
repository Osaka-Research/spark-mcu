package com.osakaresearch.spark.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Runs sequentially: a chip, compiler or USB port must never get overlapping actions. */
internal class AgentToolLoop(private val maxRounds: Int = 48) {
    suspend fun run(
        input: JSONArray,
        request: suspend (JSONArray) -> AiClient.ResponseTurn,
        execute: suspend (AiToolCall) -> String,
        onRound: (Int) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): AiResult {
        val completedCalls = mutableMapOf<String, Pair<String, String>>()
        var emptyRounds = 0
        repeat(maxRounds) { round ->
            currentCoroutineContext().ensureActive()
            if (input.toString().length > 500_000) return AiResult.Err(0,
                "Paused because this task filled its context. Sketch, build results and observations are saved; send Continue to resume.")
            onRound(round + 1)
            var turn = request(input)
            var retries = 0
            while (turn.error?.code in setOf(0, 408, 429, 500, 502, 503, 504) && retries < 2) {
                // No tools are executed until a complete response has arrived.
                delay(1000L shl retries)
                retries++
                onRound(round + 1)
                turn = request(input)
            }
            turn.error?.let { return it }
            for (i in 0 until turn.outputItems.length()) input.put(turn.outputItems.getJSONObject(i))
            if (turn.calls.isEmpty()) {
                val messages = (0 until turn.outputItems.length()).mapNotNull { turn.outputItems.optJSONObject(it) }
                    .filter { it.optString("type") == "message" }
                val finalMessage = messages.lastOrNull { it.optString("phase") != "commentary" }
                if (finalMessage != null && turn.text.isNotBlank()) return AiResult.Ok(turn.text)
                if (++emptyRounds >= 3) return AiResult.Err(0, "The model stopped making progress. Your work is saved; send Continue to retry.")
                if (turn.text.isNotBlank()) onProgress(turn.text)
                return@repeat
            }
            emptyRounds = 0
            if (turn.text.isNotBlank()) onProgress(turn.text)
            for (call in turn.calls) {
                currentCoroutineContext().ensureActive()
                val identity = "${call.namespace}.${call.name}:${call.arguments}"
                val prior = completedCalls[call.callId]
                val output = if (call.callId.isBlank()) return AiResult.Err(0, "Model returned a tool call without an ID")
                else if (prior != null) {
                    if (prior.first != identity) return AiResult.Err(0, "Model reused a tool call ID with different arguments")
                    prior.second // Never repeat a write because the same call was replayed.
                } else try { execute(call) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { JSONObject().put("ok", false).put("reason", error.message ?: "Tool failed").toString() }
                completedCalls[call.callId] = identity to output
                input.put(JSONObject().put("type", "function_call_output").put("call_id", call.callId).put("output", output))
            }
        }
        return AiResult.Err(0, "Paused after $maxRounds steps. Sketch, builds and diagnostics are saved; send Continue to carry on.")
    }
}

data class AgentPlanStep(val title: String, val status: String)

object AgentPlan {
    fun parse(steps: JSONArray): List<AgentPlanStep> {
        require(steps.length() in 1..8) { "Plan must contain 1–8 steps" }
        val result = (0 until steps.length()).map {
            val item = steps.getJSONObject(it)
            val title = item.getString("step").trim()
            val status = item.getString("status")
            require(title.isNotEmpty() && title.length <= 160) { "Step titles must be 1–160 characters" }
            require(status in setOf("pending", "in_progress", "completed", "blocked")) { "Invalid step status" }
            AgentPlanStep(title, status)
        }
        require(result.count { it.status == "in_progress" } <= 1) { "Only one step may be in progress" }
        return result
    }

    fun json(steps: List<AgentPlanStep>) = JSONArray().apply {
        steps.forEach { put(JSONObject().put("step", it.title).put("status", it.status)) }
    }
}

/** An explicit UI grant, held only in memory and bound to this attachment and target. */
data class RepairFlashGrant(val deviceKey: String, val attachmentVersion: Long, val target: String) {
    fun permits(key: String, version: Long, targetId: String) =
        key == deviceKey && version == attachmentVersion && targetId == target
}
