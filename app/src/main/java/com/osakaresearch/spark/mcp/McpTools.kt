package com.osakaresearch.spark.mcp

import android.util.Base64
import com.osakaresearch.spark.net.ChipDetection
import com.osakaresearch.spark.ai.AgentDiagnostics
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP tool catalog — one place that defines both the schema advertised to
 * clients (`tools/list`) and the runtime dispatcher (`tools/call`). Tool
 * handlers are suspend lambdas so they can block on real device I/O.
 *
 * Return protocol: each handler returns a String that becomes the body of a
 * single `{"type":"text","text":...}` content block. Structured results are
 * JSON-stringified; the LLM on the other end parses it. Errors are raised
 * via [ToolError] which the server maps to `{isError: true}` content.
 */
class ToolError(val code: Int, message: String) : RuntimeException(message)

class McpTools(private val bridge: McpBridge) {

    private val tools: Map<String, Tool> = linkedMapOf(
        tool(
            name = "get_workspace",
            description = "Read the editable working sketch, target, revision, and last compiler diagnostics, including failed builds. Read before modifying existing code. This survives restarts.",
            schema = """{"type":"object","properties":{},"additionalProperties":false}""",
        ) { bridge.readWorkspace().put("ok", true).toString() },
        tool(
            name = "write_sketch",
            description = "Save a complete working .ino without compiling. Use the revision from get_workspace, or an empty string for a new workspace. Returns the new revision. Existing code is replaced only if that revision still matches.",
            schema = """{"type":"object","properties":{"ino_source":{"type":"string"},"target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"]},"expected_revision":{"type":"string"}},"required":["ino_source","target","expected_revision"],"additionalProperties":false}""",
        ) { args -> bridge.writeWorkspace(args.getString("ino_source"), args.getString("target"), args.getString("expected_revision"))
            .apply { remove("source"); put("ok", true) }.toString() },
        tool(
            name = "edit_sketch",
            description = "Make a focused code fix in the working sketch. search must match exactly once; include surrounding code to disambiguate. Requires its current revision. Returns a new revision, which must be compiled before flashing.",
            schema = """{"type":"object","properties":{"expected_revision":{"type":"string"},"search":{"type":"string"},"replacement":{"type":"string"}},"required":["expected_revision","search","replacement"],"additionalProperties":false}""",
        ) { args -> bridge.patchWorkspace(args.getString("expected_revision"), args.getString("search"), args.getString("replacement"))
            .apply { remove("source"); put("ok", true) }.toString() },
        tool(
            name = "compile_workspace",
            description = "Compile the saved working sketch at its current revision. Returns a stable buildId or actual compiler diagnostics; read errors, edit_sketch, then compile again. Does not flash.",
            schema = """{"type":"object","properties":{"expected_revision":{"type":"string"}},"required":["expected_revision"],"additionalProperties":false}""",
        ) { args -> compileResult(bridge.compileWorkspace(args.getString("expected_revision"))) },
        tool(
            name = "list_libraries",
            description = "List bundled and already installed Arduino libraries for a build target.",
            schema = """{"type":"object","properties":{"target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"]}},"required":["target"],"additionalProperties":false}""",
        ) { args -> bridge.libraries(args.getString("target")).put("ok", true).toString() },
        tool(
            name = "install_library",
            description = "Install an Arduino library by its exact Library Manager name from Arduino's official library index. Verifies the indexed SHA-256, safely extracts to app-private storage, installs declared dependencies, and makes it available on later ESP8266/S3 builds.",
            schema = """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":80}},"required":["name"],"additionalProperties":false}""",
        ) { args -> bridge.installLibrary(args.getString("name")).toString() },
        tool(
            name = "get_device_context",
            description = "Read current USB devices and permissions, ROM-confirmed chip (if known), selected target, serial state and recent lines, retained builds, compiler availability and last compile/flash result. Read-only: does not reset the board.",
            schema = """{"type":"object","properties":{},"additionalProperties":false}""",
        ) { bridge.deviceContext().toString() },
        tool(
            name = "select_target",
            description = "Record the MCU target the user explicitly identified after ROM detection failed. Use only when the user states ESP8266 NodeMCU or ESP32-S3, or confirms their Settings selection. This is a build configuration, not ROM evidence.",
            schema = """{"type":"object","properties":{"target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"]}},"required":["target"],"additionalProperties":false}""",
        ) { args -> bridge.selectTarget(args.getString("target")).toString() },
        tool(
            name = "wifi_board_command",
            description = "Interact with the currently connected Spark MCU Wi-Fi board and wait for its firmware acknowledgment. Use ping to identify it, analog_read to sample a sensor, digital_write to set a pin, servo to move a servo, or motor to drive motors. Pin numbers and connected hardware must be established from the user's notes or current task. Motor, servo and pin actions physically actuate hardware; use only when requested by the goal.",
            schema = """{"type":"object","properties":{"action":{"type":"string","enum":["ping","analog_read","digital_write","servo","motor"]},"pin":{"type":"integer","minimum":0,"maximum":48},"value":{"type":"integer","minimum":0,"maximum":1},"mode":{"type":"string","enum":["out","in","pullup"]},"angle":{"type":"integer","minimum":0,"maximum":180},"left":{"type":"integer","minimum":-255,"maximum":255},"right":{"type":"integer","minimum":-255,"maximum":255},"duration_ms":{"type":"integer","minimum":100,"maximum":3000}},"required":["action"],"additionalProperties":false}""",
        ) { args ->
            val action = args.getString("action")
            val pin = args.optInt("pin", -1)
            val (command, event) = when (action) {
                "ping" -> JSONObject().put("cmd", "ping") to "pong"
                "analog_read" -> {
                    require(pin in 0..48) { "Supply a sensor pin from 0 to 48" }
                    JSONObject().put("cmd", "read").put("pin", pin) to "read"
                }
                "digital_write" -> {
                    require(pin in 0..48) { "Supply a pin from 0 to 48" }
                    val mode = args.optString("mode", "out")
                    require(mode in setOf("out", "in", "pullup")) { "Unsupported pin mode" }
                    val value = if (mode == "out") args.optInt("value", -1).also { require(it in 0..1) { "Output value must be 0 or 1" } } else 0
                    JSONObject().put("cmd", "pin").put("pin", pin).put("mode", mode).put("value", value) to "pin"
                }
                "servo" -> {
                    require(pin in 0..48 && args.has("angle")) { "Supply a servo pin and angle" }
                    val angle = args.getInt("angle")
                    require(angle in 0..180) { "Servo angle must be 0–180 degrees" }
                    JSONObject().put("cmd", "servo").put("pin", pin).put("angle", angle) to "servo"
                }
                "motor" -> {
                    require(args.has("left") && args.has("right") && args.has("duration_ms")) { "Supply left and right speeds and a bounded duration" }
                    val left = args.getInt("left")
                    val right = args.getInt("right")
                    val duration = args.getInt("duration_ms")
                    require(left in -255..255 && right in -255..255) { "Motor speeds must be -255 to 255" }
                    require(duration in 100..3000) { "Motor duration must be 100–3000 ms" }
                    JSONObject().put("cmd", "motor").put("left", left).put("right", right) to "motor"
                }
                else -> error("Unsupported Wi-Fi board action")
            }
            val matcher: (JSONObject) -> Boolean = { response ->
                when (action) {
                    "analog_read", "digital_write", "servo" -> response.optInt("pin", -1) == pin
                    else -> true
                }
            }
            val reply = if (action == "motor") {
                val duration = args.getInt("duration_ms")
                var running: JSONObject? = null
                try {
                    running = bridge.wifiCommand(command, event, matcher)
                    delay(duration.toLong())
                }
                finally {
                    withContext(NonCancellable) {
                        runCatching {
                            bridge.wifiCommand(JSONObject().put("cmd", "motor").put("left", 0).put("right", 0), "motor")
                        }
                    }
                }
                requireNotNull(running).put("stopped", true).put("durationMs", duration)
            } else bridge.wifiCommand(command, event, matcher)
            JSONObject().put("ok", true).put("board", bridge.deviceContext().optJSONObject("wifiBoard") ?: JSONObject())
                .put("response", reply).toString()
        },
        tool(
            name = "get_build",
            description = "Read a retained build's target, source sketch, build log, size and hash. Use a buildId from get_device_context or compile_sketch. Does not compile or flash.",
            schema = """{"type":"object","properties":{"buildId":{"type":"string"}},"required":["buildId"],"additionalProperties":false}""",
        ) { args -> bridge.buildInfo(args.getString("buildId")).toString() },
        tool(
            name = "list_devices",
            description = "List attached USB-OTG serial adapters and ESP chips (CP2102, CH340, native ESP32-S3 USB-JTAG). No side effects.",
            schema = """{"type":"object","properties":{},"additionalProperties":false}""",
        ) { _ ->
            val arr = JSONArray()
            bridge.listDevices().forEach {
                arr.put(JSONObject().apply {
                    put("name", it.name)
                    put("vendorId", it.vendorId)
                    put("productId", it.productId)
                    put("hasPermission", it.hasPermission)
                })
            }
            JSONObject().put("devices", arr).toString()
        },

        tool(
            name = "detect_chip",
            description = "Make one bounded attempt to query the connected chip's ROM in download mode. May reset the board; does not write flash. If it fails, report the failure and ask the user for their MCU. Do not retry repeatedly; select their stated target with select_target and continue.",
            schema = """{"type":"object","properties":{"manual_boot":{"type":"boolean","default":false}},"additionalProperties":false}""",
        ) { args ->
            when (val result = bridge.detectChip(args.optBoolean("manual_boot", false))) {
                is ChipDetection.Found -> JSONObject().put("ok", true)
                    .put("target", result.target.id).put("label", result.target.label).toString()
                is ChipDetection.Fail -> JSONObject().put("ok", false)
                    .put("reason", result.reason).toString()
            }
        },

        tool(
            name = "compile_sketch",
            description = "Compile a .ino sketch on-device for the given MCU target. Returns a buildId that flash_firmware can reference, plus the firmware size and the compile log. Specify the target explicitly; never infer the MCU from a USB bridge name.",
            schema = """
              {"type":"object",
               "properties":{
                 "ino_source":{"type":"string","description":"Full .ino source. Multi-file sketches must be inlined."},
                 "target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"]}
               },
               "required":["ino_source","target"],
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val src = args.getString("ino_source")
            val target = args.getString("target")
            compileResult(bridge.compileSketch(src, target))
        },

        tool(
            name = "flash_firmware",
            description = "Flash firmware via USB-OTG. An ESP32-S3 buildId automatically flashes the bundled bootloader, partitions, boot_app0, and compiled app. For an inline bin, supply its flash offset. Specify the target explicitly; never infer the MCU from a USB bridge name.",
            schema = """
              {"type":"object",
               "properties":{
                 "buildId":{"type":"string"},
                 "bin_base64":{"type":"string"},
                 "offset":{"type":"integer","default":0},
                 "target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"]},
                 "manual_boot":{"type":"boolean","default":false}
               },
               "required":["target"],
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val buildId = args.optString("buildId", "").ifBlank { null }
            val b64 = args.optString("bin_base64", "").ifBlank { null }
            val bin = b64?.let { Base64.decode(it, Base64.DEFAULT) }
            val offset = args.optInt("offset", 0)
            val target = args.getString("target")
            val manualBoot = args.optBoolean("manual_boot", false)
            when (val r = bridge.flashFirmware(buildId, bin, offset, target, manualBoot, args.optString("_confirmedDeviceKey").takeIf(String::isNotBlank))) {
                is McpBridge.FlashOk -> JSONObject().apply {
                    put("ok", true)
                    put("bytes", r.bytes)
                    put("blocks", r.blocks)
                    put("log", r.log.takeLast(4000))
                }.toString()
                is McpBridge.FlashErr -> JSONObject().apply {
                    put("ok", false)
                    put("reason", r.reason)
                    put("log", r.log.takeLast(4000))
                }.toString()
                else -> throw ToolError(-32603, "unexpected flash result")
            }
        },
        tool(
            name = "flash_wifi_firmware",
            description = "Upload a retained compiled build to the currently connected Spark MCU over Wi-Fi OTA. Requires the live firmware hello to identify a matching ESP8266 or ESP32-S3 board. The app asks for confirmation before the write; the board reboots afterward.",
            schema = """{"type":"object","properties":{"buildId":{"type":"string"},"target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"]}},"required":["buildId","target"],"additionalProperties":false}""",
        ) { args ->
            val connected = com.osakaresearch.spark.net.SparkClient.state.value as? com.osakaresearch.spark.net.SparkConnState.Connected
                ?: return@tool JSONObject().put("ok", false).put("reason", "Connect to a Spark MCU board over Wi-Fi first").toString()
            val confirmedUrl = args.optString("_confirmedConnectionUrl")
            if (confirmedUrl.isBlank() || confirmedUrl != connected.url) {
                return@tool JSONObject().put("ok", false).put("reason", "Wi-Fi board changed after confirmation").toString()
            }
            bridge.flashWifiFirmware(args.getString("buildId"), args.getString("target"), confirmedUrl).toString()
        },

        tool(
            name = "flash_plan",
            description = "Flash multiple blobs at distinct offsets inside one ROM session — the right call for ESP32-family targets that need bootloader + partitions + boot_app0 + app. Each blob is {name, offset, bin_base64}. For ESP32-S3 the standard plan is bootloader@0x0, partitions@0x8000, boot_app0@0xE000, firmware@0x10000.",
            schema = """
              {"type":"object",
               "properties":{
                 "blobs":{
                   "type":"array",
                   "minItems":1,
                   "items":{
                     "type":"object",
                     "properties":{
                       "name":{"type":"string"},
                       "offset":{"type":"integer"},
                       "bin_base64":{"type":"string"}
                     },
                     "required":["name","offset","bin_base64"],
                     "additionalProperties":false
                   }
                 },
                 "target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"],"default":"esp32-s3"},
                 "manual_boot":{"type":"boolean","default":false}
               },
               "required":["blobs"],
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val blobsJson = args.getJSONArray("blobs")
            val blobs = buildList {
                for (i in 0 until blobsJson.length()) {
                    val b = blobsJson.getJSONObject(i)
                    add(Triple(
                        b.getString("name"),
                        b.getInt("offset"),
                        Base64.decode(b.getString("bin_base64"), Base64.DEFAULT),
                    ))
                }
            }
            val target = args.optString("target", "esp32-s3")
            val manualBoot = args.optBoolean("manual_boot", false)
            when (val r = bridge.flashPlan(blobs, target, manualBoot)) {
                is McpBridge.FlashOk -> JSONObject().apply {
                    put("ok", true)
                    put("bytes", r.bytes)
                    put("blocks", r.blocks)
                    put("log", r.log.takeLast(4000))
                }.toString()
                is McpBridge.FlashErr -> JSONObject().apply {
                    put("ok", false)
                    put("reason", r.reason)
                    put("log", r.log.takeLast(4000))
                }.toString()
                else -> throw ToolError(-32603, "unexpected flash result")
            }
        },

        tool(
            name = "reset_board",
            description = "Restart an already ROM-identified board's existing firmware without writing flash. Opens/reuses the monitor first; returns a cursor to read startup output. Use when a debugging task needs a reproducible boot or detection left the board in download mode. Interrupts the running program.",
            schema = """{"type":"object","properties":{"baud":{"type":"integer","default":115200}},"additionalProperties":false}""",
        ) { args -> bridge.resetBoard(args.optInt("baud", 115200)).toString() },
        tool(
            name = "capture_serial",
            description = "Open/reuse the monitor and observe NEW output for a complete 500–10000 ms window. Use after flashing and for debugging. Returns lines, diagnostic signals, cursor and capture identity. Empty output is not proof of success. Choose baud from Serial.begin.",
            schema = """{"type":"object","properties":{"baud":{"type":"integer","default":115200},"duration_ms":{"type":"integer","minimum":500,"maximum":10000,"default":3000}},"additionalProperties":false}""",
        ) { args -> bridge.captureSerial(args.optInt("baud", 115200), args.optInt("duration_ms", 3000)).toString() },
        tool(
            name = "write_serial",
            description = "Send a bounded UTF-8 command to the currently monitored board. Only send commands documented by its sketch or explicitly requested by the user; serial commands can actuate hardware. Read fresh output after sending. Does not open or reset the port.",
            schema = """{"type":"object","properties":{"text":{"type":"string","maxLength":1024},"newline":{"type":"boolean","default":true}},"required":["text"],"additionalProperties":false}""",
        ) { args -> JSONObject().put("ok", true).put("bytes", bridge.writeSerial(args.getString("text"), args.optBoolean("newline", true))).toString() },
        tool(
            name = "start_serial",
            description = "Open the USB-OTG serial port at the given baud and start buffering lines. 74880 = ESP8266 bootROM, 115200 = typical sketch output.",
            schema = """
              {"type":"object",
               "properties":{"baud":{"type":"integer","default":115200}},
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val baud = args.optInt("baud", 115200)
            val ok = bridge.startSerial(baud)
            JSONObject().put("ok", ok).put("baud", baud).put("serial", bridge.deviceContext().getJSONObject("serial")).toString()
        },

        tool(
            name = "set_serial_baud",
            description = "Switch the shared USB serial monitor to a different baud automatically and begin a fresh capture. Match Serial.begin in the sketch; use 74880 only for ESP8266 ROM boot messages, typically 115200 for sketches and ESP32-S3. No user tab switching is needed.",
            schema = """{"type":"object","properties":{"baud":{"type":"integer","enum":[9600,19200,38400,57600,74880,115200,230400,460800,921600]}},"required":["baud"],"additionalProperties":false}""",
        ) { args ->
            val baud = args.getInt("baud")
            val ok = bridge.startSerial(baud)
            JSONObject().put("ok", ok).put("serial", bridge.deviceContext().getJSONObject("serial")).toString()
        },

        tool(
            name = "stop_serial",
            description = "Close the serial reader opened by start_serial.",
            schema = """{"type":"object","properties":{},"additionalProperties":false}""",
        ) { _ ->
            bridge.stopSerial()
            JSONObject().put("ok", true).toString()
        },

        tool(
            name = "read_serial_lines",
            description = "Read the shared Monitor/AI serial buffer. Optionally wait up to 5000 ms for new output. Return buffered serial lines emitted after `since_cursor`. Returns an updated cursor to pass to the next call. Pass 0 to read from the oldest buffered line.",
            schema = """
              {"type":"object",
               "properties":{
                 "since_cursor":{"type":"integer","default":0},
                 "max_lines":{"type":"integer","minimum":1,"maximum":200,"default":100},
                 "wait_ms":{"type":"integer","minimum":0,"maximum":5000,"default":0}
               },
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val cursor = args.optLong("since_cursor", 0L)
            val max = args.optInt("max_lines", 200)
            val batch = bridge.readSerial(cursor, max, args.optInt("wait_ms", 0))
            JSONObject().apply {
                put("cursor", batch.cursor)
                put("lines", JSONArray(batch.lines))
                put("truncated", batch.truncated)
                put("serial", bridge.deviceContext().getJSONObject("serial").apply { remove("recentLines") })
            }.toString()
        },
    )

    private fun compileResult(result: Any): String = when (result) {
        is McpBridge.CompileOk -> JSONObject().put("ok", true).put("buildId", result.buildId)
            .put("target", result.target).put("sizeBytes", result.sizeBytes).put("sha256", result.sha256)
            .put("log", result.log.takeLast(6000)).put("workspaceRevision", result.revision)
        is McpBridge.CompileErr -> JSONObject().put("ok", false).put("reason", result.reason)
            .put("diagnostics", AgentDiagnostics.compiler(result.log)).put("log", result.log.takeLast(6000)).put("workspaceRevision", result.revision)
        else -> throw ToolError(-32603, "Unexpected compile result")
    }.toString()

    fun catalog(): JSONArray {
        val arr = JSONArray()
        tools.values.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name)
                put("description", it.description)
                put("inputSchema", JSONObject(it.schema))
            })
        }
        return arr
    }

    suspend fun invoke(name: String, args: JSONObject): String {
        val t = tools[name] ?: throw ToolError(-32601, "unknown tool: $name")
        return t.handler(args)
    }

    private data class Tool(
        val name: String,
        val description: String,
        val schema: String,
        val handler: suspend (JSONObject) -> String,
    )

    private fun tool(
        name: String,
        description: String,
        schema: String,
        handler: suspend (JSONObject) -> String,
    ): Pair<String, Tool> = name to Tool(name, description, schema, handler)
}
