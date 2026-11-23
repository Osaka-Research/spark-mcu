package com.osakaresearch.spark.net

/** Shared bounded history for the Monitor screen, MCP clients, and the assistant. */
class SerialLogBuffer(private val capacity: Int = 1000) {
    init { require(capacity > 0) { "Serial history capacity must be positive" } }
    data class Batch(val cursor: Long, val lines: List<String>, val truncated: Boolean)
    private val entries = ArrayDeque<Pair<Long, String>>()
    private var sequence = 0L

    @Synchronized fun append(line: String) {
        entries.addLast(++sequence to line.take(2000))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized fun clear() { entries.clear() }

    @Synchronized fun read(after: Long, limit: Int = 100): Batch {
        val selected = entries.filter { it.first > after }.take(limit.coerceIn(1, 200))
        return Batch(selected.lastOrNull()?.first ?: sequence, selected.map { it.second },
            after > 0 && entries.firstOrNull()?.first?.let { after < it - 1 } == true)
    }

    @Synchronized fun tail(limit: Int = 30): Batch = Batch(
        sequence, entries.takeLast(limit.coerceIn(1, 200)).map { it.second }, false,
    )
}
