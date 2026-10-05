package com.offlinemesh.app

import android.content.Context
import android.util.Log
import com.offlinemesh.app.echo.EchoController
import com.offlinemesh.core.common.FileSink
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.LogSink
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.common.RingBufferSink
import com.offlinemesh.core.common.WallClock
import java.io.File

/** Log đi 4 đường: logcat (tag "Mesh"), vòng đệm, file filesDir/mesh.log, và màn hình Debug. */
object AppLog {
    val ring = RingBufferSink(300)

    lateinit var log: MeshLog
        private set

    fun init(ctx: Context) {
        val logcat = LogSink { e ->
            val p = when (e.level) {
                Level.DEBUG -> Log.DEBUG
                Level.INFO -> Log.INFO
                Level.WARN -> Log.WARN
                Level.ERROR -> Log.ERROR
            }
            Log.println(p, "Mesh", e.line())
        }
        val file = FileSink(File(ctx.filesDir, "mesh.log"))
        val screen = LogSink { EchoController.setLogTail(ring.snapshot().takeLast(12).map { it.line() }) }
        log = MeshLog(WallClock, listOf(logcat, ring, file, screen))
    }
}
