package com.offlinemesh.core.mesh.sim

import com.offlinemesh.core.mesh.routing.DefaultPolicy
import com.offlinemesh.core.mesh.routing.DirectOnlyPolicy
import com.offlinemesh.core.mesh.routing.EpidemicPolicy
import com.offlinemesh.core.mesh.routing.ForwardingPolicy
import com.offlinemesh.core.model.Kind
import java.io.PrintStream
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random

/** Mọi tham số của một đợt quét L. Đặt bằng chuỗi key=value, xem HELP. */
data class SweepConfig(
    val ls: List<Int> = listOf(1, 2, 4, 8, 16, 32),
    val policy: String = "spray",            // spray | epidemic | direct
    val model: String = "uniform",           // uniform | clusters
    val nodes: Int = 40,                     // chỉ cho model=uniform
    val rate: Double = 0.25,                 // số lần gặp / nút / giờ (uniform)
    val clusters: Int = 3,                   // model=clusters
    val perCluster: Int = 8,
    val mules: Int = 2,
    val intra: Double = 4.0,                 // lần gặp / nút / giờ trong cụm
    val mule: Double = 6.0,                  // lần gặp / giờ của mỗi người đi lại
    val hours: Int = 48,
    val messages: Int = 60,
    val injectHours: Int = 12,               // tin được tạo ngẫu nhiên trong khoảng này
    val kind: Kind = Kind.TEXT,
    val sizeBytes: Int = 300,
    val lifeHours: Int? = null,              // null = dùng hạn sống mặc định của loại tin
    val bandwidth: Long = 20_000,            // byte/giây trong một cuộc gặp
    val meanContactSec: Int = 60,
    val seeds: Int = 5,
)

object SweepArgs {
    const val HELP = """Tham số (key=value, cách nhau bằng khoảng trắng); giá trị trong ngoặc là mặc định:
  L=1,2,4,8,16,32   các giá trị L cần quét (1..255)
  policy=spray|epidemic|direct  (spray)        model=uniform|clusters  (uniform)
  nodes=40  rate=0.25                           (model=uniform: số nút, lần gặp/nút/giờ)
  clusters=3 per=8 mules=2 intra=4 mule=6       (model=clusters)
  hours=48  msgs=60  inject=12                  (thời gian mô phỏng, số tin, tin được tạo trong N giờ đầu)
  kind=text|image|sos|video  size=300  life=<giờ>   (loại tin, kích thước payload, hạn sống; bỏ trống = mặc định)
  bw=20000  dur=60                              (byte/giây, thời lượng gặp trung bình tính bằng giây)
  seeds=5                                       (số lần chạy với seed khác nhau để lấy trung bình)"""

    fun parse(args: Array<String>): SweepConfig {
        var c = SweepConfig()
        for (a in args) {
            if (a.isBlank()) continue
            val kv = a.split("=", limit = 2)
            require(kv.size == 2) { "Sai định dạng '$a' (cần key=value)\n$HELP" }
            val (k, v) = kv
            c = when (k.lowercase()) {
                "l" -> c.copy(ls = v.split(",").map { it.trim().toInt() }.also { l -> require(l.all { it in 1..255 }) { "L phải trong 1..255" } })
                "policy" -> c.copy(policy = v.also { require(it in setOf("spray", "epidemic", "direct")) { "policy không hợp lệ: $it" } })
                "model" -> c.copy(model = v.also { require(it in setOf("uniform", "clusters")) { "model không hợp lệ: $it" } })
                "nodes" -> c.copy(nodes = v.toInt())
                "rate" -> c.copy(rate = v.toDouble())
                "clusters" -> c.copy(clusters = v.toInt())
                "per" -> c.copy(perCluster = v.toInt())
                "mules" -> c.copy(mules = v.toInt())
                "intra" -> c.copy(intra = v.toDouble())
                "mule" -> c.copy(mule = v.toDouble())
                "hours" -> c.copy(hours = v.toInt())
                "msgs" -> c.copy(messages = v.toInt())
                "inject" -> c.copy(injectHours = v.toInt())
                "kind" -> c.copy(kind = when (v.lowercase()) {
                    "text" -> Kind.TEXT; "image" -> Kind.IMAGE; "sos" -> Kind.SOS; "video" -> Kind.VIDEO
                    else -> throw IllegalArgumentException("kind không hợp lệ: $v")
                })
                "size" -> c.copy(sizeBytes = v.toInt())
                "life" -> c.copy(lifeHours = v.toInt())
                "bw" -> c.copy(bandwidth = v.toLong())
                "dur" -> c.copy(meanContactSec = v.toInt())
                "seeds" -> c.copy(seeds = v.toInt())
                else -> throw IllegalArgumentException("Không có tham số '$k'\n$HELP")
            }
        }
        return c
    }
}

object Sweep {
    private fun policyOf(name: String): () -> ForwardingPolicy = when (name) {
        "epidemic" -> { { EpidemicPolicy() } }
        "direct" -> { { DirectOnlyPolicy() } }
        else -> { { DefaultPolicy() } }
    }

    /** Một lần chạy: cùng seed thì cùng bản ghi các cuộc gặp và cùng danh sách tin, chỉ khác L. */
    fun runOne(cfg: SweepConfig, copies: Int, seed: Int): SimResult {
        val h = 3_600_000L
        val dur = cfg.meanContactSec * 1000L
        val (n, contacts) = if (cfg.model == "clusters") {
            (cfg.clusters * cfg.perCluster + cfg.mules) to Mobility.communities(
                cfg.clusters, cfg.perCluster, cfg.mules, cfg.hours, cfg.intra, cfg.mule, seed = seed, meanDurationMs = dur,
            )
        } else {
            cfg.nodes to Mobility.uniform(cfg.nodes, cfg.hours, cfg.rate, seed = seed, meanDurationMs = dur)
        }
        val rng = Random(seed * 7919 + 13)
        val injections = List(cfg.messages) {
            val (a, b) = if (cfg.model == "clusters") {
                val ca = rng.nextInt(cfg.clusters)
                val cb = (ca + 1 + rng.nextInt(cfg.clusters - 1)) % cfg.clusters      // luôn sang cụm khác
                (ca * cfg.perCluster + rng.nextInt(cfg.perCluster)) to (cb * cfg.perCluster + rng.nextInt(cfg.perCluster))
            } else {
                val a = rng.nextInt(n)
                var b = rng.nextInt(n - 1)
                if (b >= a) b++
                a to b
            }
            Injection(rng.nextLong(cfg.injectHours * h), a, b, cfg.kind, cfg.sizeBytes, copies, cfg.lifeHours?.let { it * h })
        }
        val sim = Simulation(SimConfig(n, seed = seed, bandwidthBytesPerSec = cfg.bandwidth, policy = policyOf(cfg.policy)))
        sim.run(contacts, injections)
        return sim.result()
    }

    private fun mean(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size
    private fun sd(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    fun table(cfg: SweepConfig): String {
        val f = { fmt: String, x: Double -> String.format(Locale.US, fmt, x) }
        val sb = StringBuilder()
        sb.appendLine("Quét L=${cfg.ls.joinToString(",")} | policy=${cfg.policy} model=${cfg.model} | ${cfg.messages} tin ${cfg.kind} ${cfg.sizeBytes}B | ${cfg.hours}h | ${cfg.seeds} seed")
        sb.appendLine(String.format(Locale.US, "%4s %6s %5s %7s %8s %8s %7s %7s %8s %8s %6s %6s",
            "L", "giao%", "+-sd", "tre_tv", "tre_p90", "data/tin", "ack/tin", "MB/tin", "copy_tb", "copy_max", "thua%", "nghen%"))
        for (l in cfg.ls) {
            val rs = (1..cfg.seeds).map { runOne(cfg, l, it) }
            val ratio = rs.map { it.ratio * 100 }
            val sent = rs.sumOf { it.sent }.coerceAtLeast(1).toDouble()
            val data = rs.sumOf { it.dataTransfers }
            sb.appendLine(String.format(Locale.US, "%4d %6s %5s %7s %8s %8s %7s %7s %8s %8d %6s %6s",
                l, f("%.1f", mean(ratio)), f("%.1f", sd(ratio)),
                f("%.1f", mean(rs.mapNotNull { it.medianLatencyMs?.let { m -> m / 3_600_000.0 } })),
                f("%.1f", mean(rs.mapNotNull { it.p90LatencyMs?.let { m -> m / 3_600_000.0 } })),
                f("%.1f", data / sent), f("%.1f", rs.sumOf { it.ackTransfers } / sent),
                f("%.2f", rs.sumOf { it.txMegabytes } / sent), f("%.1f", mean(rs.map { it.avgRelayCopies })),
                rs.maxOf { it.peakRelayCopies },
                f("%.1f", if (data == 0) 0.0 else 100.0 * rs.sumOf { it.afterDeliveryTransfers } / data),
                f("%.1f", 100.0 * rs.sumOf { it.congestedContacts } / rs.sumOf { it.contacts }.coerceAtLeast(1)),
            ))
        }
        sb.appendLine("""
            giao%: tỉ lệ tin tới đích (trung bình các seed), +-sd: độ lệch chuẩn giữa các seed
            tre_tv / tre_p90: độ trễ trung vị / phân vị 90 (giờ) của các tin đã tới
            data/tin, ack/tin: số lần chuyển dữ liệu / ACK trung bình cho mỗi tin; MB/tin: tổng lưu lượng mỗi tin
            copy_tb / copy_max: số bản sao nằm ở máy trung gian (trung bình theo cuộc gặp / đỉnh)
            thua%: % lần chuyển dữ liệu xảy ra SAU khi tin đã tới đích (lãng phí)
            nghen%: % cuộc gặp bị cắt vì hết ngân sách byte (dấu hiệu quá tải / "bão" quảng bá)
        """.trimIndent())
        return sb.toString()
    }
}

fun main(args: Array<String>) {
    val out = PrintStream(System.out, true, "UTF-8")
    if (args.any { it == "--help" || it == "-h" }) { out.println(SweepArgs.HELP); return }
    out.println(Sweep.table(SweepArgs.parse(args)))
}
