package com.offlinemesh.app.power

import android.content.Context
import com.offlinemesh.core.mesh.power.BatteryCsv
import com.offlinemesh.core.mesh.power.BatterySample
import java.io.File

/** Ghi mẫu đo pin vào filesDir/battery.csv (chỉ khi % pin, trạng thái sạc hoặc chế độ thay đổi: không đọc định kỳ). */
class BatteryRecorder(ctx: Context) {
    private val file = File(ctx.filesDir, "battery.csv")

    @Synchronized
    fun record(s: BatterySample) {
        file.appendText(BatteryCsv.encode(s) + "\n")
        if (file.length() > 200_000) {
            val keep = file.readLines().takeLast(1500)
            file.writeText(keep.joinToString("\n") + "\n")
        }
    }

    @Synchronized
    fun samples(): List<BatterySample> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { BatteryCsv.decode(it) }

    @Synchronized
    fun clear() { file.delete() }
}
