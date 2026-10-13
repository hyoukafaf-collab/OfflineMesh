package com.offlinemesh.app.mesh

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Trạng thái SOS của chính mình (đang phát lại hay không). Thời gian theo SystemClock.elapsedRealtime(). */
data class SosStatus(
    val active: Boolean = false,
    val text: String = "",
    val sentCount: Int = 0,
    val nextAtMs: Long = 0,
    val endAtMs: Long = 0,
)

data class MeshUiState(
    val running: Boolean = false,
    val selfId: String = "-",
    val seen: List<String> = emptyList(),
    val linked: List<String> = emptyList(),
    val stats: String = "",
    val error: String? = null,
    val notice: String? = null,
    val logTail: List<String> = emptyList(),
    val sos: SosStatus = SosStatus(),
)

/** Ranh giới giữa giao diện và lõi mesh: giao diện chỉ đọc state (Compose tự cập nhật) và gọi lệnh ở đây. */
object MeshController {
    interface Commands {
        /** SOS cần chế độ khẩn cấp đang bật. Gửi ngay rồi phát lại theo lịch (FIFO) cho tới khi "Tôi an toàn" hoặc hết hạn. */
        fun sendSos(type: Int, text: String, shareLocation: Boolean)
        fun markSafe(shareLocation: Boolean)
        fun sendText(peer: String, text: String)
        fun sendImage(peer: String, uri: Uri)
        fun sendPublicText(text: String)
        fun sendPublicImage(uri: Uri)
        /** Ảnh SOS (nén rất nhỏ, đi cùng mục SOS, KHÔNG vào mục Công khai). Cần chế độ khẩn cấp; gửi một lần, không phát lại. */
        fun sendSosImage(uri: Uri)
    }

    var state by mutableStateOf(MeshUiState())
        private set

    @Volatile
    var commands: Commands? = null

    fun update(f: (MeshUiState) -> MeshUiState) { state = f(state) }

    fun setLogTail(lines: List<String>) = update { it.copy(logTail = lines) }
    fun clearStaleMessages() = update { if (it.running) it else it.copy(error = null, notice = null) }

    fun sendSos(type: Int, text: String, share: Boolean) { commands?.sendSos(type, text, share) }
    fun markSafe(share: Boolean) { commands?.markSafe(share) }
    fun sendText(peer: String, text: String) { commands?.sendText(peer, text) }
    fun sendImage(peer: String, uri: Uri) { commands?.sendImage(peer, uri) }
    fun sendPublicText(text: String) { commands?.sendPublicText(text) }
    fun sendPublicImage(uri: Uri) { commands?.sendPublicImage(uri) }
    fun sendSosImage(uri: Uri) { commands?.sendSosImage(uri) }
}
