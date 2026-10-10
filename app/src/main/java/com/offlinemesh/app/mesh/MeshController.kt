package com.offlinemesh.app.mesh

/** Một mục trong hộp thư: tiêu đề, nội dung, và ảnh (JPEG) nếu có. */
class InboxItem(val title: String, val body: String, val image: ByteArray? = null)

data class MeshUiState(
    val running: Boolean = false,
    val selfId: String = "-",
    val seen: List<String> = emptyList(),      // máy đang thấy quảng bá gần đây
    val linked: List<String> = emptyList(),    // máy đang có liên kết sống
    val inbox: List<InboxItem> = emptyList(),
    val stats: String = "",
    val error: String? = null,
    val notice: String? = null,                // thông báo trung tính (ví dụ kết quả nén ảnh)
    val logTail: List<String> = emptyList(),
)

/** Ranh giới giữa giao diện và lõi mesh: giao diện chỉ đọc state và gọi lệnh ở đây. */
object MeshController {
    interface Commands {
        /** type: mã SosType. includeLocation: lấy vị trí hiện tại (cần quyền vị trí đã được cấp). */
        fun sendSos(type: Int, includeLocation: Boolean, text: String)
        fun sendText(peer: String, text: String)

        /** uri: ảnh người dùng chọn; sẽ được nén xuống mức NORMAL (≤ 30 KB) rồi mã hóa gửi cho peer. */
        fun sendImage(peer: String, uri: android.net.Uri)
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

    fun addInbox(item: InboxItem) = update { it.copy(inbox = (listOf(item) + it.inbox).take(30)) }

    /** Lỗi/thông báo của lần thử trước không còn đúng nữa; chỉ xóa khi không chạy. */
    fun clearStaleMessages() = update { if (it.running) it else it.copy(error = null, notice = null) }

    fun sendSos(type: Int, includeLocation: Boolean, text: String) { commands?.sendSos(type, includeLocation, text) }
    fun sendText(peer: String, text: String) { commands?.sendText(peer, text) }
    fun sendImage(peer: String, uri: android.net.Uri) { commands?.sendImage(peer, uri) }
}
