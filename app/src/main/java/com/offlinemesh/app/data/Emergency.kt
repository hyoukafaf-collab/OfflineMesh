package com.offlinemesh.app.data

import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.power.PowerController
import com.offlinemesh.app.settings.PrivacySettings
import com.offlinemesh.core.mesh.payload.SosType

/** Thoát chế độ khẩn cấp: một chỗ duy nhất để mục SOS và màn Pin dùng chung, đảm bảo luôn làm đủ các việc. */
object Emergency {
    /** Còn SOS của mình chưa được giải quyết: đang phát lại, hoặc lần gửi gần nhất không phải "Tôi an toàn". */
    fun hasOpenOwnSos(): Boolean {
        if (MeshController.state.sos.active) return true
        val last = MessageStore.conversation("SOS").lastOrNull { it.outgoing && it.kind == "SOS" } ?: return false
        return last.sosType != SosType.SAFE.code
    }

    /**
     * Tắt chế độ khẩn cấp cũng là kết thúc SOS của bạn:
     *  1. còn SOS đang mở thì gửi "Tôi an toàn" (việc này cũng dừng phát lại);
     *  2. tắt chia sẻ vị trí trong SOS;
     *  3. quay về chế độ năng lượng thường (tự động theo pin, hoặc mức đang ép nếu có).
     */
    fun exit() {
        val share = PrivacySettings.shareLocation          // đọc trước khi tắt để tin "an toàn" vẫn kèm vị trí nếu bạn đang bật
        if (hasOpenOwnSos()) MeshController.markSafe(share)
        PrivacySettings.setShareLocation(false)
        PowerController.setEmergency(false)
    }
}
