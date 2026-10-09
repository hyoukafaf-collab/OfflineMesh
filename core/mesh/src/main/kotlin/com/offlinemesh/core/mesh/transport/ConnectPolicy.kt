package com.offlinemesh.core.mesh.transport

import com.offlinemesh.core.model.PeerId

data class ConnectConfig(
    val maxCentralLinks: Int = 3,           // số liên kết ta chủ động mở cùng lúc
    val sightingTtlMs: Long = 15_000,       // thấy trong khoảng này mới coi là còn ở gần
    val fallbackAfterMs: Long = 20_000,     // máy ID lớn hơn tự nối nếu thấy liên tục mà chưa ai nối
    val reconnectCooldownMs: Long = 60_000, // sau một liên kết thành công, chưa nối lại ngay
    val backoffBaseMs: Long = 2_000,        // nối hỏng: chờ 2s, 4s, 8s... tối đa backoffMaxMs
    val backoffMaxMs: Long = 60_000,
)

/**
 * Quyết định NỐI AI, KHI NÀO (Kotlin thuần, test được trên JVM; phần Android chỉ gọi vào đây):
 * - Mặc định máy có ID nhỏ hơn chủ động nối, tránh hai máy cùng nối nhau.
 * - Nếu máy nhỏ không nối được (không nghe thấy), máy lớn nối sau fallbackAfterMs.
 * - Mỗi lúc chỉ một lần nối đang chạy (Android nối song song hay lỗi 133).
 * - Nối hỏng thì lùi dần; nối xong thì nghỉ một lúc (tránh nối lại liên tục).
 * - Chọn máy có tín hiệu mạnh nhất (gần nhất) trước.
 */
class ConnectPolicy(private val self: PeerId, private val cfg: ConnectConfig = ConnectConfig()) {
    private class Seen(var first: Long, var last: Long, var rssi: Int)
    private class History { var failures = 0; var notBefore = 0L }

    private val seen = HashMap<PeerId, Seen>()
    private val history = HashMap<PeerId, History>()
    private val connecting = HashSet<PeerId>()
    private val central = HashSet<PeerId>()

    fun onSighting(peer: PeerId, rssi: Int, now: Long) {
        if (peer == self) return
        val s = seen[peer]
        if (s == null || now - s.last > cfg.sightingTtlMs) seen[peer] = Seen(now, now, rssi)
        else { s.last = now; s.rssi = rssi }
    }

    fun onConnectStarted(peer: PeerId) { connecting += peer }

    fun onLinkReady(peer: PeerId, weAreCentral: Boolean) {
        connecting -= peer
        if (weAreCentral) central += peer
    }

    fun onLinkClosed(peer: PeerId, now: Long, wasReady: Boolean) {
        connecting -= peer
        central -= peer
        val h = history.getOrPut(peer) { History() }
        if (wasReady) {
            h.failures = 0
            h.notBefore = now + cfg.reconnectCooldownMs
        } else {
            h.failures++
            h.notBefore = now + minOf(cfg.backoffMaxMs, cfg.backoffBaseMs shl (h.failures - 1).coerceAtMost(20))
        }
    }

    /** Liên kết do peer mở tới ta (ta là Peripheral) đã đóng: chỉ đặt thời gian nghỉ, không đụng trạng thái nối của ta. */
    fun onRemoteLinkClosed(peer: PeerId, now: Long) {
        history.getOrPut(peer) { History() }.notBefore = now + cfg.reconnectCooldownMs
    }

    /** `linked`: các peer đang có liên kết sẵn sàng (cả hai vai). */
    fun nextToConnect(now: Long, linked: Set<PeerId>): PeerId? {
        seen.entries.removeIf { now - it.value.last > cfg.sightingTtlMs * 4 }
        if (connecting.isNotEmpty() || central.size >= cfg.maxCentralLinks) return null
        return seen.entries.asSequence()
            .filter { (p, s) ->
                p !in linked && now - s.last <= cfg.sightingTtlMs && now >= (history[p]?.notBefore ?: 0L)
            }
            .filter { (p, s) -> self.hex < p.hex || now - s.first >= cfg.fallbackAfterMs }
            .maxByOrNull { it.value.rssi }
            ?.key
    }

    /** Các peer đang được thấy gần đây (cho giao diện). */
    fun recentlySeen(now: Long): List<PeerId> =
        seen.entries.filter { now - it.value.last <= cfg.sightingTtlMs }.map { it.key }
}
