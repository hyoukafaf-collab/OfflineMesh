package com.offlinemesh.core.common

import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.PeerId
import java.io.File

enum class Level { DEBUG, INFO, WARN, ERROR }
enum class Layer { TRANSPORT, LINK, ROUTING, CACHE, CRYPTO, POWER, UI }

data class LogEvent(
    val tMs: Long,
    val level: Level,
    val layer: Layer,
    val event: String,
    val bundle: BundleId? = null,
    val peer: PeerId? = null,
    val session: String? = null,
    val error: ErrorCode? = null,
    val detail: String? = null,
) {
    /** Một dòng dạng key=value, dễ grep và gộp log của nhiều máy theo bundle=. */
    fun line(): String = buildString {
        append(tMs).append(' ').append(level).append(' ').append(layer).append(' ').append(event)
        bundle?.let { append(" bundle=").append(it.short()) }
        peer?.let { append(" peer=").append(it.short()) }
        session?.let { append(" session=").append(it) }
        error?.let { append(" err=").append(it) }
        detail?.let { append(" detail=").append(it) }
    }
}

fun interface LogSink { fun emit(e: LogEvent) }

class MeshLog(private val clock: Clock, private val sinks: List<LogSink>) {
    fun log(
        level: Level, layer: Layer, event: String,
        bundle: BundleId? = null, peer: PeerId? = null, session: String? = null,
        error: ErrorCode? = null, detail: String? = null,
    ) {
        val e = LogEvent(clock.nowMs(), level, layer, event, bundle, peer, session, error, detail)
        sinks.forEach { it.emit(e) }
    }
}

/** Vòng đệm trong bộ nhớ: cho màn hình Debug và test. */
class RingBufferSink(private val capacity: Int = 500) : LogSink {
    private val buf = ArrayDeque<LogEvent>()

    @Synchronized
    override fun emit(e: LogEvent) {
        if (buf.size == capacity) buf.removeFirst()
        buf.addLast(e)
    }

    @Synchronized
    fun snapshot(): List<LogEvent> = buf.toList()

    fun forBundle(id: BundleId): List<LogEvent> = snapshot().filter { it.bundle == id }
    fun exportText(): String = snapshot().joinToString("\n") { it.line() }
}

/** Ghi file (sống sót qua crash). Quá maxBytes thì đổi tên thành .1, giữ 1 bản cũ. */
class FileSink(private val file: File, private val maxBytes: Long = 1_000_000) : LogSink {
    @Synchronized
    override fun emit(e: LogEvent) {
        if (file.exists() && file.length() > maxBytes) {
            val old = File(file.path + ".1")
            old.delete()
            file.renameTo(old)
        }
        file.appendText(e.line() + "\n")
    }
}
