package com.offlinemesh.app.mesh

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.offlinemesh.app.ble.BleTransport
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.location.LocationProvider
import com.offlinemesh.app.media.UriImageEncoder
import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.common.WallClock
import com.offlinemesh.core.mesh.crypto.MeshCrypto
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.payload.GeoMath
import com.offlinemesh.core.mesh.payload.GeoPoint
import com.offlinemesh.core.mesh.payload.ImageCompressor
import com.offlinemesh.core.mesh.payload.ImagePayload
import com.offlinemesh.core.mesh.payload.ImageTier
import com.offlinemesh.core.mesh.payload.SosPayload
import com.offlinemesh.core.mesh.payload.SosType
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.PeerIds
import com.offlinemesh.core.model.StoredBundle
import kotlin.random.Random

/**
 * Sở hữu luồng "mesh", BleTransport và MeshNode. MỌI thứ chạm vào MeshNode/BleTransport đều chạy trên
 * luồng này (MeshNode không an toàn đa luồng); giao diện chỉ gọi các hàm bên dưới (chúng tự post sang luồng mesh).
 */
class MeshRuntime(private val ctx: Context, private val log: MeshLog) : MeshController.Commands {
    private val ui = MeshController
    private val location = LocationProvider(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var transport: BleTransport? = null
    private var node: MeshNode? = null
    private var tick: Runnable? = null

    /** Gọi bởi dịch vụ khi gặp lỗi nặng ở tầng BLE (để dịch vụ tự dừng). */
    var onFatal: (String) -> Unit = {}

    /**
     * Gọi ở MỖI lần bấm Bắt đầu: đọc lại Bluetooth (và Vị trí trên Android ≤ 11) ngay lúc đó,
     * không theo dõi định kỳ. Trả false (và hiện lỗi) nếu chưa chạy được.
     */
    fun start(): Boolean {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bt = if (adapter == null) "không có" else if (adapter.isEnabled) "bật" else "tắt"
        log.log(Level.INFO, Layer.TRANSPORT, "mesh_start_requested", detail = "bluetooth=$bt sdk=${Build.VERSION.SDK_INT}")
        ui.update { it.copy(error = null, notice = null) }
        if (adapter == null) return fail("Máy này không có Bluetooth")
        if (!adapter.isEnabled) return fail("Bluetooth đang tắt: hãy bật Bluetooth rồi bấm Bắt đầu lại")
        if (Build.VERSION.SDK_INT < 31) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) {
                return fail("Android 11 trở xuống cần bật Vị trí (GPS) để quét BLE")
            }
        }
        val selfId = ContactsController.identity.peerId       // = hash khóa ký, nên không giả được
        check(PeerIds.isValid(selfId))
        val t = HandlerThread("mesh").also { it.start() }
        val h = Handler(t.looper)
        thread = t; handler = h
        ui.update { it.copy(running = true, selfId = selfId.hex, seen = emptyList(), linked = emptyList(), stats = "") }
        h.post { startOnMeshThread(selfId, h) }
        return true
    }

    private fun fail(msg: String): Boolean {
        log.log(Level.WARN, Layer.TRANSPORT, "error", detail = msg)
        ui.update { it.copy(error = msg, running = false) }
        return false
    }

    private fun startOnMeshThread(selfId: PeerId, h: Handler) {
        val tr = BleTransport(ctx, selfId, h, log)
        tr.onFatal = { msg -> ui.update { it.copy(error = msg, running = false) }; onFatal(msg) }
        tr.onPeersChanged = { seen, linked ->
            ui.update { it.copy(seen = seen.map { p -> p.hex }, linked = linked.map { p -> p.hex }) }
        }
        val book = ContactsController.book
        val crypto = MeshCrypto(ContactsController.identity, book)
        val n = MeshNode(
            selfId, tr, WallClock, log, Random.Default,
            signer = crypto, verifier = crypto, cipher = crypto,      // ký mọi tin, từ chối tin không hợp lệ, mã hóa tin riêng
            autoResync = true, cooldownMs = 30_000,
            onReceived = { sb -> showReceived(sb) },
        )
        n.onBundleSent = { _, _ -> updateStats() }
        transport = tr; node = n
        n.start()
        val task = object : Runnable {
            override fun run() { n.tick(); updateStats(); h.postDelayed(this, 60_000) }
        }
        tick = task
        h.postDelayed(task, 60_000)
    }

    // ---------- hiển thị tin nhận được (chạy trên luồng mesh) ----------
    private fun who(sb: StoredBundle): String {
        val c = sb.core
        val book = ContactsController.book
        // Chữ ký đã được xác minh. "✔" chỉ khi khóa trong tin TRÙNG khóa đã ghim (không chỉ trùng ID); luôn kèm 4 ký tự ID để phân biệt tên trùng.
        return if (book.isPinned(c.src, c.pubKey)) "✔ ${book.byPeer(c.src)?.name} [${c.src.hex.take(4)}]"
        else "⚠ ${c.src.short()} (chưa xác minh)"
    }

    private fun showReceived(sb: StoredBundle) {
        val c = sb.core
        val item = when (c.kind) {
            Kind.SOS -> sosItem(sb)
            Kind.IMAGE -> {
                val img = ImagePayload.decode(c.payload)
                if (img == null) InboxItem("[ẢNH] ${who(sb)}", "(ảnh hỏng, đã bỏ qua)")
                else InboxItem("[ẢNH] ${who(sb)}", "${img.width}×${img.height}, ${img.jpeg.size / 1024} KB, hop ${sb.hops}", img.jpeg)
            }
            else -> InboxItem("[${c.kind}] ${who(sb)}", "${String(c.payload, Charsets.UTF_8)} (hop ${sb.hops})")
        }
        ui.addInbox(item)
        updateStats()
    }

    private val sosLabel = mapOf(
        SosType.NEED_RESCUE to "Cần cứu hộ", SosType.INJURED to "Bị thương", SosType.TRAPPED to "Mắc kẹt",
        SosType.SAFE to "Tôi an toàn", SosType.NEED_SUPPLIES to "Cần nhu yếu phẩm",
    )

    private fun sosItem(sb: StoredBundle): InboxItem {
        val p = SosPayload.decode(sb.core.payload)
            ?: return InboxItem("[SOS] ${who(sb)}", String(sb.core.payload, Charsets.UTF_8))
        val parts = mutableListOf<String>()
        if (p.text.isNotEmpty()) parts += p.text
        val pos = p.position
        if (pos == null) parts += "không có vị trí"
        else {
            parts += "vị trí ${"%.5f".format(java.util.Locale.US, pos.latE6 / 1e6)}, ${"%.5f".format(java.util.Locale.US, pos.lonE6 / 1e6)}" +
                (pos.accuracyM?.let { " (±$it m)" } ?: "")
            val me: GeoPoint? = location.lastKnownPoint()
            if (me != null) {
                parts += "cách bạn ${GeoMath.formatDistance(GeoMath.distanceM(me, pos))}, hướng ${GeoMath.compass(GeoMath.bearingDeg(me, pos))}"
            }
            if (p.fixAgeSec >= 60) parts += "vị trí lấy cách đây ${p.fixAgeSec / 60} phút"
        }
        val ageMin = sb.ageAtReceiveMs / 60_000
        if (ageMin >= 1) parts += "tin đã đi $ageMin phút"
        parts += "hop ${sb.hops}"
        return InboxItem("🆘 ${sosLabel[p.type]} · ${who(sb)}", parts.joinToString(" · "))
    }

    private fun updateStats() {
        val s = node?.stats ?: return
        ui.update { it.copy(stats = "gửi ${s.bundlesSent} tin · nhận ${s.bundlesReceived} · giao ${s.delivered} · trùng ${s.duplicates} · từ chối ${s.rejected}") }
    }

    fun stop() {
        val h = handler ?: return
        val t = thread
        val tr = transport
        val task = tick
        handler = null; thread = null; transport = null; node = null; tick = null
        h.post {
            task?.let { h.removeCallbacks(it) }
            tr?.stop()
            t?.quitSafely()
        }
        ui.update { it.copy(running = false, seen = emptyList(), linked = emptyList()) }
    }

    // ---------- lệnh từ giao diện ----------
    override fun sendSos(type: Int, includeLocation: Boolean, text: String) {
        val t = SosType.fromCode(type) ?: SosType.NEED_RESCUE
        fun post(pos: GeoPoint?, ageSec: Int) {
            handler?.post { node?.send(null, Kind.SOS, SosPayload(t, pos, ageSec, text).encode()) }
        }
        if (!includeLocation) { post(null, 0); return }
        // Gọi trên luồng chính (từ nút bấm); LocationProvider trả kết quả trên luồng chính.
        location.current(8_000) { loc ->
            if (loc == null) {
                ui.update { it.copy(notice = "Không lấy được vị trí: đã gửi SOS không kèm tọa độ") }
                post(null, 0)
            } else {
                post(LocationProvider.toPoint(loc), ((System.currentTimeMillis() - loc.time) / 1000).toInt().coerceAtLeast(0))
            }
        }
    }

    override fun sendText(peer: String, text: String) {
        handler?.post {
            try {
                node?.send(PeerId(peer), Kind.TEXT, text.toByteArray(Charsets.UTF_8))
            } catch (e: IllegalStateException) {      // chưa có khóa mã hóa của người nhận
                ui.update { it.copy(error = e.message) }
            } catch (e: IllegalArgumentException) {   // vượt giới hạn kích thước
                ui.update { it.copy(error = e.message) }
            }
        }
    }

    override fun sendImage(peer: String, uri: Uri) {
        ui.update { it.copy(notice = "Đang nén ảnh…", error = null) }
        Thread {
            val c = ImageCompressor.compress(UriImageEncoder(ctx, uri), ImageTier.NORMAL)
            if (c == null) {
                ui.update { it.copy(notice = null, error = "Không nén được ảnh xuống ≤ ${ImageTier.NORMAL.maxBytes / 1000} KB") }
                return@Thread
            }
            val payload = ImagePayload(c.image.width, c.image.height, c.image.bytes).encode()
            ui.update { it.copy(notice = "Ảnh đã nén còn ${c.image.bytes.size / 1024} KB (${c.image.width}×${c.image.height}, chất lượng ${c.quality}), đang gửi") }
            handler?.post {
                try {
                    node?.send(PeerId(peer), Kind.IMAGE, payload)
                } catch (e: IllegalStateException) {
                    ui.update { it.copy(error = e.message) }
                } catch (e: IllegalArgumentException) {
                    ui.update { it.copy(error = e.message) }
                }
            }
        }.start()
    }
}
