package com.offlinemesh.core.mesh.history

/**
 * Quy tắc "tin nhận được thì xếp vào đâu, hay bỏ". Hàm thuần để test trên JVM; MeshRuntime chỉ việc gọi.
 *
 * Phân biệt ba loại người gửi, KHÁC HẲN nhau:
 *  - CÔNG KHAI: tin gửi cho mọi người (dst = null). Ai cũng đọc được, người gửi có thể là ai đó chưa có trong danh bạ.
 *    Vào mục "Công khai". Công tắc "chỉ nhận từ danh bạ" KHÔNG áp dụng (đó không phải tin gửi riêng cho mình).
 *  - NGƯỜI TRONG DANH BẠ: tin riêng, khóa ký trùng khóa đã ghim. Vào cuộc trò chuyện của họ.
 *  - NGƯỜI LẠ: tin RIÊNG gửi đúng cho mình (mã hóa bằng khóa công khai của mình, chỉ mình giải mã được) nhưng người gửi
 *    chưa có trong danh bạ, tức mình chưa biết khóa của họ. Vào mục "người lạ" (chỉ đọc), hoặc bị bỏ nếu bật "chỉ nhận từ danh bạ".
 *
 * SOS luôn được nhận (kể cả từ người bị chặn và khi bật "chỉ nhận từ danh bạ").
 */
object InboundPolicy {
    enum class Drop { BLOCKED, STRANGER_NOT_ALLOWED, KEY_MISMATCH }

    sealed class Decision {
        /** Lưu vào cuộc trò chuyện `conv` ("SOS", "PUBLIC" hoặc PeerId hex của người gửi). */
        class Store(val conv: String, val stranger: Boolean) : Decision()
        /** Bỏ qua: không lưu, không thông báo. Việc chuyển tiếp cho người khác và ACK không bị ảnh hưởng. */
        class Skip(val why: Drop) : Decision()
    }

    /**
     * @param isSos       tin loại SOS
     * @param isPublic    tin gửi cho mọi người (không có người nhận cụ thể)
     * @param srcHex      PeerId người gửi (đã xác minh chữ ký, PeerId khớp khóa ký trong tin)
     * @param inContacts  người gửi có trong danh bạ (theo PeerId)
     * @param keyPinned   khóa ký trong tin trùng khóa đã ghim (chỉ có nghĩa khi inContacts)
     * @param blocked     người gửi nằm trong danh sách chặn
     * @param contactsOnly công tắc "chỉ nhận tin riêng từ người trong danh bạ"
     */
    fun decide(isSos: Boolean, isPublic: Boolean, srcHex: String, inContacts: Boolean, keyPinned: Boolean, blocked: Boolean, contactsOnly: Boolean): Decision {
        if (isSos) return Decision.Store("SOS", stranger = !(inContacts && keyPinned))
        if (blocked) return Decision.Skip(Drop.BLOCKED)
        if (isPublic) return Decision.Store("PUBLIC", stranger = false)
        // Tin riêng. PeerId trùng một liên hệ nhưng khóa khác khóa đã ghim: giả mạo liên hệ (PeerId chỉ dài 6 byte), tuyệt đối không xếp vào cuộc trò chuyện của họ.
        if (inContacts) return if (keyPinned) Decision.Store(srcHex, stranger = false) else Decision.Skip(Drop.KEY_MISMATCH)
        if (contactsOnly) return Decision.Skip(Drop.STRANGER_NOT_ALLOWED)
        return Decision.Store(srcHex, stranger = true)
    }
}
