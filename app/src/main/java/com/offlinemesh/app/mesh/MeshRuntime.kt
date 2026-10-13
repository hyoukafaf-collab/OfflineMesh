package com.offlinemesh.app.mesh

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.offlinemesh.app.ble.BleTransport
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.data.AppSettings
import com.offlinemesh.app.data.MessageStore
import com.offlinemesh.app.data.Notifier
import com.offlinemesh.app.location.LocationProvider
import com.offlinemesh.app.media.UriImageEncoder
import com.offlinemesh.app.power.PowerController
import com.offlinemesh.app.settings.PrivacySettings
import com.offlinemesh.app.ui.ChatState
import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.common.WallClock
import com.offlinemesh.core.mesh.crypto.MeshCrypto
import com.offlinemesh.core.mesh.history.StoredMessage
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.payload.GeoMath
import com.offlinemesh.core.mesh.payload.GeoPoint
import com.offlinemesh.core.mesh.payload.ImageCompressor
import com.offlinemesh.core.mesh.payload.ImagePayload
import com.offlinemesh.core.mesh.payload.ImageTier
import com.offlinemesh.core.mesh.payload.ReplayConfig
import com.offlinemesh.core.mesh.payload.SosEntry
import com.offlinemesh.core.mesh.payload.SosFeed
import com.offlinemesh.core.mesh.payload.SosLocationPlan
import com.offlinemesh.core.mesh.payload.SosPayload
import com.offlinemesh.core.mesh.payload.SosPresets
import com.offlinemesh.core.mesh.payload.SosReplayQueue
import com.offlinemesh.core.mesh.payload.SosType
import com.offlinemesh.core.mesh.power.BatterySample
import com.offlinemesh.core.mesh.power.PowerGovernor
import com.offlinemesh.core.mesh.power.PowerLevel
import com.offlinemesh.core.mesh.power.PowerMode
import com.offlinemesh.core.mesh.settings.Retention
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.PeerIds
import com.offlinemesh.core.model.StoredBundle
import kotlin.random.Random

/**
 * Sở hữu luồng "mesh", BleTransport và MeshNode. MỌI thứ chạm vào MeshNode/BleTransport/hàng đợi phát lại đều chạy trên
 * luồng này; giao diện chỉ gọi các hàm lệnh (chúng tự post sang luồng mesh).
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
    private var selfId: PeerId? = null

    // Quản lý năng lượng
    private val governor = PowerGovernor()
    private var lastBattery: Pair<Int, Boolean>? = null
    private var appliedLevel: PowerLevel? = null
    private var lastRecord: Triple<Int, Boolean, String>? = null

    // Phát lại SOS: mỗi mục là MỘT thông điệp (session riêng); mỗi lần phát lại tăng seq và lấy vị trí mới
    private class SosItem(val type: SosType, val text: String, val share: Boolean, val session: Int, var seq: Int = 0)
    private var replay = SosReplayQueue<SosItem>(ReplayConfig())
    private var replayTask: Runnable? = null
    private var sosSent = 0
    private var lastPurgeMs = 0L

    var onFatal: (String) -> Unit = {}

    /** Gọi ở MỖI lần khởi động: đọc lại Bluetooth/Vị trí ngay lúc đó, không theo dõi định kỳ. */
    fun start(): Boolean {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bt = if (adapter == null) "không có" else if (adapter.isEnabled) "bật" else "tắt"
        log.log(Level.INFO, Layer.TRANSPORT, "mesh_start_requested", detail = "bluetooth=$bt sdk=${Build.VERSION.SDK_INT}")
        ui.update { it.copy(error = null, notice = null) }
        if (adapter == null) return fail("Máy này không có Bluetooth")
        if (!adapter.isEnabled) return fail("Bluetooth đang tắt: hãy bật Bluetooth để gửi và nhận tin")
        if (Build.VERSION.SDK_INT < 31) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) {
                return fail("Android 11 trở xuống cần bật Vị trí (GPS) để quét BLE")
            }
        }
        val id = ContactsController.identity.peerId
        check(PeerIds.isValid(id))
        selfId = id
        val t = HandlerThread("mesh").also { it.start() }
        val h = Handler(t.looper)
        thread = t; handler = h
        ui.update { it.copy(running = true, selfId = id.hex, seen = emptyList(), linked = emptyList(), stats = "") }
        h.post { startOnMeshThread(id, h) }
        return true
    }

    private fun fail(msg: String): Boolean {
        log.log(Level.WARN, Layer.TRANSPORT, "error", detail = msg)
        ui.update { it.copy(error = msg, running = false) }
        return false
    }

    private fun startOnMeshThread(id: PeerId, h: Handler) {
        val tr = BleTransport(ctx, id, h, log)
        tr.onFatal = { msg -> ui.update { it.copy(error = msg, running = false) }; onFatal(msg) }
        tr.onPeersChanged = { seen, linked ->
            ui.update { it.copy(seen = seen.map { p -> p.hex }, linked = linked.map { p -> p.hex }) }
        }
        val book = ContactsController.book
        val crypto = MeshCrypto(ContactsController.identity, book)
        val n = MeshNode(
            id, tr, WallClock, log, Random.Default,
            signer = crypto, verifier = crypto, cipher = crypto,
            autoResync = true, cooldownMs = 30_000,
            onReceived = { sb -> onIncoming(sb) },
        )
        n.onBundleSent = { core, _ -> if (core.src == id) MessageStore.setState(core.id.hex, 1); updateStats() }
        n.onAcked = { bid -> MessageStore.setState(bid.hex, 2) }
        transport = tr; node = n
        n.start()
        PowerController.onSettingsChanged = { handler?.post { reevaluate() } }
        val task = object : Runnable {
            override fun run() { n.tick(); updateStats(); purgeIfDue(); h.postDelayed(this, 60_000) }
        }
        tick = task
        h.postDelayed(task, 60_000)
    }

    // ---------- năng lượng ----------
    fun onBattery(pct: Int, charging: Boolean) { handler?.post { lastBattery = pct to charging; reevaluate() } }

    private fun reevaluate() {
        val (pct, charging) = lastBattery ?: return
        val tr = transport ?: return
        val n = node ?: return
        val emergency = PowerController.emergency
        val forced = PowerController.forcedLevel()
        governor.mode = if (emergency) PowerMode.EMERGENCY else PowerMode.NORMAL
        governor.forced = forced
        val p = governor.update(pct, charging)
        n.relayFilter = p.relay
        if (appliedLevel != p.level) { tr.applyProfile(p); appliedLevel = p.level }
        PowerController.onProfile(pct, charging, p)
        val label = "${p.level.name}${if (emergency) "/KC" else ""}${if (forced != null) "/ÉP" else ""}"
        val rec = Triple(pct, charging, label)
        if (rec != lastRecord) {
            lastRecord = rec
            PowerController.recorder.record(BatterySample(System.currentTimeMillis(), pct, charging, label))
        }
    }

    // ---------- lịch sử: tự xóa theo thời gian lưu (kiểm tra tối đa mỗi giờ) ----------
    private fun purgeIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPurgeMs < 3_600_000L) return
        lastPurgeMs = now
        MessageStore.purgeOlderThan(Retention.cutoffMs(now, AppSettings.retentionDays))
    }

    // ---------- tin nhận được ----------
    private fun nameOf(src: PeerId): String {
        val c = ContactsController.book.byPeer(src)
        return if (c != null) c.name else "ID ${src.short()}"
    }

    private fun verified(sb: StoredBundle): Boolean = ContactsController.book.isPinned(sb.core.src, sb.core.pubKey)

    private fun onIncoming(sb: StoredBundle) {
        val c = sb.core
        val now = System.currentTimeMillis()
        val id = c.id.hex
        val src = c.src.hex
        val isPublic = c.dst == null
        when (c.kind) {
            Kind.SOS -> {
                val p = SosPayload.decode(c.payload)
                val m = if (p == null) StoredMessage(id, "SOS", false, "SOS", String(c.payload, Charsets.UTF_8), null, now, src = src, pinned = verified(sb), hops = sb.hops, ageSec = (sb.ageAtReceiveMs / 1000).toInt())
                else StoredMessage(
                    id, "SOS", false, "SOS", p.text, null, now, src = src, pinned = verified(sb), hops = sb.hops, ageSec = (sb.ageAtReceiveMs / 1000).toInt(),
                    sosType = p.type.code, session = p.sessionId, seq = p.seq, latE6 = p.position?.latE6, lonE6 = p.position?.lonE6,
                    accM = p.position?.accuracyM, fixAgeSec = p.fixAgeSec,
                )
                val entry = m.toSosEntry()
                val isNew = entry == null || SosFeed.isNewSession(MessageStore.sosEntries(), entry)
                MessageStore.add(m)
                // Chỉ phát âm/thông báo ở lần đầu của một SOS; các bản phát lại sau chỉ cập nhật im lặng.
                if (isNew) Notifier.notify(true, "SOS: ${p?.type?.label ?: "tin khẩn cấp"}", "${nameOf(c.src)}: ${p?.text ?: ""}", "SOS", ChatState.viewing())
            }
            Kind.IMAGE -> {
                val img = ImagePayload.decode(c.payload) ?: return
                val name = MessageStore.saveImage(id, img.jpeg)
                val sosImage = img.sos && isPublic                      // ảnh SOS chỉ hợp lệ khi phát cho mọi người; cờ trên tin riêng bị bỏ qua
                val conv = if (sosImage) "SOS" else if (isPublic) "PUBLIC" else src
                MessageStore.add(StoredMessage(id, conv, false, "IMAGE", "", name, now, src = src, pinned = verified(sb), hops = sb.hops, ageSec = (sb.ageAtReceiveMs / 1000).toInt()))
                if (sosImage) Notifier.notify(true, "SOS: ảnh", "${nameOf(c.src)} đã gửi một ảnh", "SOS", ChatState.viewing())
                else Notifier.notify(false, nameOf(c.src), "đã gửi một ảnh", conv, ChatState.viewing())
            }
            else -> {
                val text = String(c.payload, Charsets.UTF_8)
                val conv = if (isPublic) "PUBLIC" else src
                MessageStore.add(StoredMessage(id, conv, false, "TEXT", text, null, now, src = src, pinned = verified(sb), hops = sb.hops, ageSec = (sb.ageAtReceiveMs / 1000).toInt()))
                Notifier.notify(false, nameOf(c.src), text, conv, ChatState.viewing())
            }
        }
        updateStats()
    }

    private fun updateStats() {
        val s = node?.stats ?: return
        ui.update { it.copy(stats = "gửi ${s.bundlesSent} · nhận ${s.bundlesReceived} · giao ${s.delivered} · trùng ${s.duplicates} · từ chối ${s.rejected}") }
    }

    fun stop() {
        val h = handler ?: return
        val t = thread
        val tr = transport
        val task = tick
        val rt = replayTask
        PowerController.onSettingsChanged = null
        handler = null; thread = null; transport = null; node = null; tick = null; replayTask = null
        h.post {
            task?.let { h.removeCallbacks(it) }
            rt?.let { h.removeCallbacks(it) }
            tr?.stop()
            t?.quitSafely()
        }
        replay.cancel()
        ui.update { it.copy(running = false, seen = emptyList(), linked = emptyList(), sos = SosStatus()) }
    }

    // ---------- gửi tin (mọi hàm tự post sang luồng mesh) ----------
    private fun now() = System.currentTimeMillis()

    private fun record(id: BundleId, conv: String, kind: String, text: String, image: String?) {
        MessageStore.add(StoredMessage(id.hex, conv, true, kind, text, image, now()))
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (e: IllegalStateException) { ui.update { it.copy(error = e.message) } }
        catch (e: IllegalArgumentException) { ui.update { it.copy(error = e.message) } }
    }

    override fun sendText(peer: String, text: String) {
        handler?.post { guard { node?.send(PeerId(peer), Kind.TEXT, text.toByteArray(Charsets.UTF_8))?.let { record(it, peer, "TEXT", text, null) } } }
    }

    override fun sendPublicText(text: String) {
        handler?.post { guard { node?.send(null, Kind.TEXT, text.toByteArray(Charsets.UTF_8))?.let { record(it, "PUBLIC", "TEXT", text, null) } } }
    }

    override fun sendImage(peer: String, uri: Uri) = sendImageTo(peer, uri, sos = false)
    override fun sendPublicImage(uri: Uri) = sendImageTo(null, uri, sos = false)

    override fun sendSosImage(uri: Uri) {
        if (!PowerController.emergency) { ui.update { it.copy(error = "Bật chế độ khẩn cấp để gửi SOS") }; return }
        sendImageTo(null, uri, sos = true)
    }

    /** sos = true: ảnh vào mục SOS (nén nhỏ hơn để máy tiết kiệm pin vẫn chuyển tiếp được), không phải mục Công khai. */
    private fun sendImageTo(peer: String?, uri: Uri, sos: Boolean) {
        val tier = if (sos) ImageTier.SMALL else ImageTier.NORMAL
        ui.update { it.copy(notice = "Đang nén ảnh…", error = null) }
        Thread {
            val c = ImageCompressor.compress(UriImageEncoder(ctx, uri), tier)
            if (c == null) {
                ui.update { it.copy(notice = null, error = "Không nén được ảnh xuống ≤ ${tier.maxBytes / 1000} KB") }
                return@Thread
            }
            val payload = ImagePayload(c.image.width, c.image.height, c.image.bytes, sos).encode()
            ui.update { it.copy(notice = "Ảnh đã nén còn ${c.image.bytes.size / 1024} KB, đang gửi") }
            handler?.post {
                guard {
                    val id = node?.send(if (sos) null else peer?.let { PeerId(it) }, Kind.IMAGE, payload)
                    if (id != null) record(id, if (sos) "SOS" else peer ?: "PUBLIC", "IMAGE", "", MessageStore.saveImage(id.hex, c.image.bytes))
                }
            }
        }.start()
    }

    // ---------- SOS ----------
    /** Lấy vị trí cho một lần phát SOS. waitFresh=false (phát lại) chỉ dùng vị trí đã biết, không chờ. */
    private fun withLocation(share: Boolean, waitFresh: Boolean, cb: (GeoPoint?, Int) -> Unit) {
        fun age(l: android.location.Location) = ((System.currentTimeMillis() - l.time) / 1000).toInt().coerceAtLeast(0)
        if (!share || !location.hasPermission()) { cb(null, 0); return }
        val last = location.lastKnown()
        val plan = SosLocationPlan.plan(last?.let { System.currentTimeMillis() - it.time })
        if ((plan.sendNow || !waitFresh) && last != null && SosLocationPlan.isUsable(System.currentTimeMillis() - last.time)) {
            cb(LocationProvider.toPoint(last), age(last)); return
        }
        if (!waitFresh) { cb(null, 0); return }
        ui.update { it.copy(notice = "Đang lấy vị trí (tối đa ${plan.waitForFixMs / 1000} giây)…") }
        main.post {
            location.current(plan.waitForFixMs) { loc ->
                val ok = loc?.takeIf { SosLocationPlan.isUsable(System.currentTimeMillis() - it.time) }
                ui.update { it.copy(notice = if (ok == null) "Không có vị trí đủ mới: SOS gửi không kèm tọa độ" else null) }
                if (ok == null) cb(null, 0) else cb(LocationProvider.toPoint(ok), age(ok))
            }
        }
    }

    private fun emit(item: SosItem, waitFresh: Boolean) {
        // Tắt "Chia sẻ vị trí trong SOS" có hiệu lực ngay cả với các lần phát lại đang chờ (riêng tin "Tôi an toàn" theo lựa chọn lúc bấm)
        withLocation(item.share && (PrivacySettings.shareLocation || item.type == SosType.SAFE), waitFresh) { pos, ageSec ->
            handler?.post {
                guard {
                    val payload = SosPayload(item.type, pos, ageSec, item.text, item.session, item.seq)
                    val id = node?.send(null, Kind.SOS, payload.encode())
                    if (id != null) {
                        MessageStore.add(
                            StoredMessage(id.hex, "SOS", true, "SOS", item.text, null, now(), sosType = item.type.code, session = item.session,
                                seq = item.seq, latE6 = pos?.latE6, lonE6 = pos?.lonE6, accM = pos?.accuracyM, fixAgeSec = ageSec),
                        )
                        item.seq = (item.seq + 1).coerceAtMost(255)
                        sosSent++
                        publishSosStatus(item.text)
                    }
                }
            }
        }
    }

    /** Thời gian trong SosStatus theo SystemClock.elapsedRealtime(); giao diện tự tính phần còn lại. */
    private fun publishSosStatus(lastText: String) {
        val st = if (replay.active) SosStatus(true, lastText, sosSent, replay.nextDueAtMs() ?: 0, replay.endAtMs) else SosStatus()
        ui.update { it.copy(sos = st) }
    }

    private fun clockNow() = SystemClock.elapsedRealtime()

    override fun sendSos(type: Int, text: String, shareLocation: Boolean) {
        if (!PowerController.emergency) { ui.update { it.copy(error = "Bật chế độ khẩn cấp để gửi SOS") }; return }
        val t = SosType.fromCode(type) ?: SosType.NEED_RESCUE
        handler?.post {
            if (!replay.active) {
                // Phiên phát lại mới, thời lượng lấy từ cài đặt hiện tại (5 phút đến 12 giờ)
                replay = SosReplayQueue(ReplayConfig(durationMs = AppSettings.replayDurationMs()))
                replay.begin(clockNow())
                sosSent = 0
            }
            val item = SosItem(t, text.ifBlank { t.label }, shareLocation, Random.nextInt(0x10000))
            replay.add(item)                                  // nối vào cuối hàng đợi FIFO
            emit(item, waitFresh = true)
            scheduleReplay()
        }
    }

    private fun scheduleReplay() {
        val h = handler ?: return
        replayTask?.let { h.removeCallbacks(it) }
        val due = replay.nextDueAtMs() ?: return
        val delay = (minOf(due, replay.endAtMs) - clockNow()).coerceAtLeast(0)
        val task = Runnable {
            val items = replay.due(clockNow())               // FIFO: mục cũ nhất phát trước; mỗi lần là bản MỚI (seq tăng, vị trí mới)
            for (it in items) emit(it, waitFresh = false)
            if (replay.active) scheduleReplay() else { sosSent = 0; ui.update { s -> s.copy(sos = SosStatus()) } }
        }
        replayTask = task
        h.postDelayed(task, delay)
        publishSosStatus(ui.state.sos.text)
    }

    override fun markSafe(shareLocation: Boolean) {
        handler?.post {
            replay.cancel()
            replayTask?.let { handler?.removeCallbacks(it) }
            sosSent = 0
            ui.update { it.copy(sos = SosStatus()) }
            emit(SosItem(SosPresets.SAFE.type, SosPresets.SAFE.text, shareLocation, Random.nextInt(0x10000)), waitFresh = true)
        }
    }
}
