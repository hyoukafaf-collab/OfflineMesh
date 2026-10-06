package com.offlinemesh.app.echo

/** Trạng thái hiển thị: toàn chuỗi/số đơn giản, giao diện chỉ việc vẽ. */
data class EchoUiState(
    val running: Boolean = false,
    val selfId: String = "-",
    val peers: List<String> = emptyList(),
    val linkedPeer: String? = null,
    val incomingFrom: String? = null,
    val progress: String = "",
    val result: String = "",
    val error: String? = null,
    val logTail: List<String> = emptyList(),
)

/**
 * Ranh giới giữa giao diện và logic BLE. Giao diện chỉ: (1) đọc/nghe state, (2) gọi lệnh ở đây.
 * Khi làm lại giao diện, chỉ thay phần ui/, không đụng ble/.
 */
object EchoController {
    interface Commands {
        fun connect(peer: String)
        fun runEcho(frames: Int)
    }

    @Volatile
    var state = EchoUiState()
        private set

    @Volatile
    var listener: ((EchoUiState) -> Unit)? = null

    @Volatile
    var commands: Commands? = null

    fun update(f: (EchoUiState) -> EchoUiState) {
        synchronized(this) { state = f(state) }
        listener?.invoke(state)
    }

    fun setLogTail(lines: List<String>) = update { it.copy(logTail = lines) }

    /** Lỗi của lần thử trước không còn đúng nữa (ví dụ người dùng vừa bật Bluetooth); chỉ xóa khi không chạy. */
    fun clearStaleMessages() = update { if (it.running) it else it.copy(error = null, result = "", progress = "") }

    fun connect(peer: String) { commands?.connect(peer) }
    fun runEcho(frames: Int) { commands?.runEcho(frames) }
}
