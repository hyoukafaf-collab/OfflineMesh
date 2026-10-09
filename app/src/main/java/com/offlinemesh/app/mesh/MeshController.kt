package com.offlinemesh.app.mesh

data class MeshUiState(
    val running: Boolean = false,
    val selfId: String = "-",
    val seen: List<String> = emptyList(),      // máy đang thấy quảng bá gần đây
    val linked: List<String> = emptyList(),    // máy đang có liên kết sống
    val inbox: List<String> = emptyList(),
    val stats: String = "",
    val error: String? = null,
    val logTail: List<String> = emptyList(),
)

/** Ranh giới giữa giao diện và lõi mesh: giao diện chỉ đọc state và gọi lệnh ở đây. */
object MeshController {
    interface Commands {
        fun sendSos(text: String)
        fun sendText(peer: String, text: String)
    }

    @Volatile
    var state = MeshUiState()
        private set

    @Volatile
    var listener: ((MeshUiState) -> Unit)? = null

    @Volatile
    var commands: Commands? = null

    fun update(f: (MeshUiState) -> MeshUiState) {
        synchronized(this) { state = f(state) }
        listener?.invoke(state)
    }

    fun setLogTail(lines: List<String>) = update { it.copy(logTail = lines) }

    fun addInbox(line: String) = update { it.copy(inbox = (listOf(line) + it.inbox).take(30)) }

    /** Lỗi của lần thử trước không còn đúng nữa; chỉ xóa khi không chạy. */
    fun clearStaleMessages() = update { if (it.running) it else it.copy(error = null) }

    fun sendSos(text: String) { commands?.sendSos(text) }
    fun sendText(peer: String, text: String) { commands?.sendText(peer, text) }
}

