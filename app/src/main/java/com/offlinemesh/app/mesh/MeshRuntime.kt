package com.offlinemesh.app.mesh

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.ble.BleTransport
import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.common.WallClock
import com.offlinemesh.core.mesh.crypto.MeshCrypto
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.PeerIds
import kotlin.random.Random

/**
 * Sở hữu luồng "mesh", BleTransport và MeshNode. MỌI thứ chạm vào MeshNode/BleTransport đều chạy trên
 * luồng này (MeshNode không an toàn đa luồng); giao diện chỉ gọi các hàm post() bên dưới.
 */
class MeshRuntime(private val ctx: Context, private val log: MeshLog) : MeshController.Commands {
    private val ui = MeshController
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
        ui.update { it.copy(error = null) }
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
            onReceived = { sb ->
                val c = sb.core
                // Chữ ký đã được xác minh. "✔" chỉ khi khóa trong tin TRÙNG khóa đã ghim trong danh bạ (không chỉ trùng ID).
                val who = if (book.isPinned(c.src, c.pubKey)) "✔ ${book.byPeer(c.src)?.name}" else "⚠ ${c.src.short()} (chưa xác minh)"
                ui.addInbox("[${c.kind}] $who: ${String(c.payload, Charsets.UTF_8)} (hop ${sb.hops})")
                updateStats()
            },
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

    override fun sendSos(text: String) {
        handler?.post { node?.send(null, Kind.SOS, text.toByteArray(Charsets.UTF_8)) }
    }

    override fun sendText(peer: String, text: String) {
        handler?.post {
            try {
                node?.send(PeerId(peer), Kind.TEXT, text.toByteArray(Charsets.UTF_8))
            } catch (e: IllegalStateException) {      // chưa có khóa mã hóa của người nhận
                ui.update { it.copy(error = e.message) }
            }
        }
    }
}
