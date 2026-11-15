package com.osakaresearch.spark.mcp

import android.util.Base64
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
            name = "compile_sketch",
            description = "Compile a .ino sketch on-device for the given MCU target. Returns a buildId that flash_firmware can reference, plus the firmware size and the compile log. Default target: esp8266-nodemcu.",
            schema = """
              {"type":"object",
               "properties":{
                 "ino_source":{"type":"string","description":"Full .ino source. Multi-file sketches must be inlined."},
                 "target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"],"default":"esp8266-nodemcu"}
               },
               "required":["ino_source"],
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val src = args.getString("ino_source")
            val target = args.optString("target", "esp8266-nodemcu")
            when (val r = bridge.compileSketch(src, target)) {
                is McpBridge.CompileOk -> JSONObject().apply {
                    put("ok", true)
                    put("buildId", r.buildId)
                    put("sizeBytes", r.sizeBytes)
                    put("sha256", r.sha256)
                    put("log", r.log.takeLast(4000))
                }.toString()
                is McpBridge.CompileErr -> JSONObject().apply {
                    put("ok", false)
                    put("reason", r.reason)
                    put("log", r.log.takeLast(4000))
                }.toString()
                else -> throw ToolError(-32603, "unexpected compile result")
            }
        },

        tool(
            name = "flash_firmware",
            description = "Flash firmware via USB-OTG. Pass either a buildId from compile_sketch, or an inline bin (base64). Opens the ROM bootloader, writes, resets. Default offset 0x0, default target esp8266-nodemcu.",
            schema = """
              {"type":"object",
               "properties":{
                 "buildId":{"type":"string"},
                 "bin_base64":{"type":"string"},
                 "offset":{"type":"integer","default":0},
                 "target":{"type":"string","enum":["esp8266-nodemcu","esp32-s3"],"default":"esp8266-nodemcu"},
                 "manual_boot":{"type":"boolean","default":false}
               },
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val buildId = args.optString("buildId", "").ifBlank { null }
            val b64 = args.optString("bin_base64", "").ifBlank { null }
            val bin = b64?.let { Base64.decode(it, Base64.DEFAULT) }
            val offset = args.optInt("offset", 0)
            val target = args.optString("target", "esp8266-nodemcu")
            val manualBoot = args.optBoolean("manual_boot", false)
            when (val r = bridge.flashFirmware(buildId, bin, offset, target, manualBoot)) {
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
            JSONObject().put("ok", ok).put("baud", baud).toString()
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
            description = "Return buffered serial lines emitted after `since_cursor`. Returns an updated cursor to pass to the next call. Pass 0 to read from the oldest buffered line.",
            schema = """
              {"type":"object",
               "properties":{
                 "since_cursor":{"type":"integer","default":0},
                 "max_lines":{"type":"integer","default":200}
               },
               "additionalProperties":false}
            """.trimIndent(),
        ) { args ->
            val cursor = args.optLong("since_cursor", 0L)
            val max = args.optInt("max_lines", 200)
            val batch = bridge.readSerial(cursor, max)
            JSONObject().apply {
                put("cursor", batch.cursor)
                put("lines", JSONArray(batch.lines))
            }.toString()
        },
    )

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
