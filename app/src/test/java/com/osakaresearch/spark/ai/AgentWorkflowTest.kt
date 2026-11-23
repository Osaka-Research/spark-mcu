package com.osakaresearch.spark.ai

import com.osakaresearch.spark.net.SerialLogBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AgentWorkflowTest {
    private val target = "esp32-s3"
    private fun withWorkspace(test: (AgentWorkspace) -> Unit) {
        val root = Files.createTempDirectory("spark-workspace-test").toFile()
        try { test(AgentWorkspace(root)) } finally { root.deleteRecursively() }
    }

    @Test fun failedSourceAndDiagnosticsSurviveReopening() {
        val root = Files.createTempDirectory("spark-workspace-test").toFile()
        try {
            val first = AgentWorkspace(root)
            val saved = first.write("void setup() { broken(); }\nvoid loop() {}", target, "")
            first.recordBuild(saved.getString("revision"), JSONObject().put("ok", false).put("reason", "broken is undeclared"))
            val reopened = AgentWorkspace(root).read()
            assertTrue(reopened.getString("source").contains("broken"))
            assertFalse(reopened.getJSONObject("lastBuild").getBoolean("ok"))
        } finally { root.deleteRecursively() }
    }

    @Test fun targetedFixInvalidatesBuildAndRejectsStaleEdits() = withWorkspace { ws ->
        val old = ws.write("void setup() { broken(); }\nvoid loop() {}", target, "").getString("revision")
        ws.recordBuild(old, JSONObject().put("ok", false))
        val fixed = ws.patch(old, "broken();", "Serial.begin(115200);")
        assertFalse(fixed.has("lastBuild"))
        assertTrue(fixed.getString("source").contains("Serial.begin"))
        assertTrue(runCatching { ws.patch(old, "void loop()", "void loop2()") }.isFailure)
        ws.recordBuild(old, JSONObject().put("ok", true))
        assertFalse(ws.read().has("lastBuild"))
    }

    @Test fun ambiguousPatchDoesNotChangeSource() = withWorkspace { ws ->
        val old = ws.write("int x; int x;", target, "")
        assertTrue(runCatching { ws.patch(old.getString("revision"), "int x;", "int y;") }.isFailure)
        assertEquals(old.toString(), ws.read().toString())
    }

    @Test fun revisionIncludesBuildTargetAndSourceHasBounds() = withWorkspace { ws ->
        val a = ws.write("void setup() {} void loop() {}", target)
        val b = ws.write(a.getString("source"), "esp8266-nodemcu")
        assertNotEquals(a.getString("revision"), b.getString("revision"))
        assertTrue(runCatching { ws.write("", target) }.isFailure)
        assertTrue(runCatching { ws.write("a".repeat(120001), target) }.isFailure)
        assertTrue(runCatching { ws.write("a", "esp32-cam") }.isFailure)
    }

    @Test fun compilerKeepsFirstErrorBeforeLongBuildTail() {
        val log = "sketch.ino:4:8: error: 'pin' was not declared\n" + "noise\n".repeat(3000)
        assertEquals(1, AgentDiagnostics.compiler(log).length())
        assertTrue(AgentDiagnostics.compiler(log).getString(0).contains("sketch.ino:4:8"))
    }

    @Test fun serialSignalsRequireRealEvidenceAndEmptyOutputDoesNotPass() {
        assertEquals(0, AgentDiagnostics.serial(emptyList()).length())
        val signals = AgentDiagnostics.serial(listOf("Guru Meditation Error: panic", "Brownout detector was triggered", "Backtrace: 0x42000000"))
        assertEquals(3, signals.length())
        assertTrue(signals.getJSONObject(0).has("evidence"))
    }

    @Test fun serialCursorSurvivesClearAndReportsLostHistory() {
        val buffer = SerialLogBuffer(2)
        buffer.append("old")
        val cursor = buffer.tail().cursor
        buffer.clear()
        buffer.append("new")
        assertEquals(listOf("new"), buffer.read(cursor).lines)
        buffer.append("next")
        buffer.append("last")
        assertTrue(buffer.read(cursor).truncated)
        assertTrue(buffer.read(buffer.tail().cursor).lines.isEmpty())
    }

    @Test fun repairGrantCannotCrossReconnectTargetOrBoard() {
        val grant = RepairFlashGrant("board-a", 5, target)
        assertTrue(grant.permits("board-a", 5, target))
        assertFalse(grant.permits("board-a", 6, target))
        assertFalse(grant.permits("board-b", 5, target))
        assertFalse(grant.permits("board-a", 5, "esp8266-nodemcu"))
    }

    @Test fun planRejectsParallelActiveSteps() {
        val steps = JSONArray("""[{"step":"Build","status":"in_progress"},{"step":"Test","status":"in_progress"}]""")
        assertTrue(runCatching { AgentPlan.parse(steps) }.isFailure)
        steps.getJSONObject(0).put("status", "completed")
        assertEquals(2, AgentPlan.parse(steps).size)
    }

    @Test fun compileFailureCanBeEditedRebuiltFlashedAndVerifiedInOneRun() = runBlocking {
        val sequence = listOf("detect_chip", "write_sketch", "compile_workspace", "edit_sketch", "compile_workspace", "flash_firmware", "capture_serial")
        var requestNumber = 0
        var compiles = 0
        val executed = mutableListOf<String>()
        val result = AgentToolLoop().run(JSONArray(), request = { input ->
            if (requestNumber > 0) assertEquals("function_call_output", input.getJSONObject(input.length() - 1).getString("type"))
            if (requestNumber < sequence.size) tool(sequence[requestNumber], "call-${requestNumber++}") else final("Firmware repaired; serial heartbeat observed.")
        }, execute = { call ->
            executed += call.name
            if (call.name == "compile_workspace" && compiles++ == 0) """{"ok":false,"diagnostics":["error: missing semicolon"]}"""
            else """{"ok":true}"""
        })
        assertTrue(result is AiResult.Ok)
        assertEquals(sequence, executed)
    }

    @Test fun commentaryIsProgressAndAllReasoningItemsAreReplayed() = runBlocking {
        var requests = 0
        val updates = mutableListOf<String>()
        val result = AgentToolLoop().run(JSONArray(), request = { input ->
            if (requests++ == 0) AiClient.ResponseTurn("Checking the board…", JSONArray()
                .put(JSONObject().put("type", "reasoning").put("encrypted_content", "opaque"))
                .put(JSONObject().put("type", "message").put("phase", "commentary")), emptyList(), null)
            else {
                assertEquals("opaque", input.getJSONObject(0).getString("encrypted_content"))
                assertEquals("commentary", input.getJSONObject(1).getString("phase"))
                final("Done")
            }
        }, execute = { error("No tool expected") }, onProgress = { updates += it })
        assertEquals(2, requests)
        assertEquals(listOf("Checking the board…"), updates)
        assertEquals("Done", (result as AiResult.Ok).text)
    }

    @Test fun duplicateToolIdDoesNotRepeatFirmwareWrite() = runBlocking {
        var requests = 0
        var writes = 0
        AgentToolLoop().run(JSONArray(), request = {
            if (requests++ < 2) tool("flash_firmware", "same-id") else final("Done")
        }, execute = { writes++; """{"ok":true}""" })
        assertEquals(1, writes)
    }

    @Test fun cancellationStopsBeforeTheNextTool() = runBlocking {
        var executed = 0
        val failure = runCatching {
            AgentToolLoop().run(JSONArray(), request = { tool("compile_workspace", "one") }, execute = {
                executed++
                throw CancellationException("Stopped by user")
            })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, executed)
    }

    @Test fun boundedRunReportsSavedCheckpointInsteadOfSuccess() = runBlocking {
        var requests = 0
        val result = AgentToolLoop(2).run(JSONArray(), request = { tool("get_device_context", "call-${requests++}") }, execute = { "{}" })
        assertTrue(result is AiResult.Err)
        assertTrue((result as AiResult.Err).reason.contains("Continue"))
        assertEquals(2, requests)
    }

    @Test fun malformedArgumentsAndTruncatedStreamsNeverRunTools() {
        val malformed = JSONObject().put("type", "function_call").put("namespace", "spark")
            .put("name", "flash_firmware").put("call_id", "write-1").put("arguments", "{bad json")
        val event = JSONObject().put("type", "response.completed").put("response", JSONObject().put("output", JSONArray().put(malformed)))
        val turn = AiClient.readResponsesStream(Buffer().writeUtf8("data: $event\n\n"))
        assertNotNull(turn.error)
        assertTrue(turn.calls.isEmpty())
        val truncated = AiClient.readResponsesStream(Buffer().writeUtf8("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Done\"}\n\n"))
        assertNotNull(truncated.error)
    }

    private fun tool(name: String, id: String): AiClient.ResponseTurn {
        val call = AiToolCall("spark", name, id, JSONObject())
        return AiClient.ResponseTurn("", JSONArray().put(JSONObject().put("type", "function_call")
            .put("namespace", "spark").put("name", name).put("call_id", id).put("arguments", "{}")), listOf(call), null)
    }

    private fun final(text: String) = AiClient.ResponseTurn(text,
        JSONArray().put(JSONObject().put("type", "message").put("phase", "final_answer")), emptyList(), null)
}
