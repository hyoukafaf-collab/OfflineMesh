package com.offlinemesh.core.mesh.crypto

import com.offlinemesh.core.mesh.codec.BundleCodec
import com.offlinemesh.core.mesh.node.PayloadCipher
import com.offlinemesh.core.mesh.node.Signer
import com.offlinemesh.core.mesh.node.Verifier
import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.hexToBytes
import java.security.SecureRandom

/**
 * Nối mật mã vào MeshNode:
 * - Ký: ECDSA-P256-SHA256 trên "OM-sig-v1" || phần bất biến của tin (đã gồm id, src, dst, loại, hạn sống, khóa công khai, nội dung).
 * - Xác minh (chạy ở MỌI máy, kể cả máy trung gian): khóa công khai đính kèm phải băm ra đúng src, rồi kiểm chữ ký.
 *   Nhờ đó không cần biết trước người gửi và không ai giả được src. ACK cũng được ký nên chỉ đích thật mới dọn được bản sao.
 * - Mã hóa đầu-cuối cho tin riêng: SealedBox tới khóa mã hóa của người nhận (lấy từ danh bạ).
 */
class MeshCrypto(
    private val identity: Identity,
    private val contacts: ContactBook,
    private val random: SecureRandom = SecureRandom(),
) : Signer, Verifier, PayloadCipher {

    override val publicKey: ByteArray get() = identity.signPub

    override fun sign(data: ByteArray): ByteArray = P256.sign(identity.signPriv, SIG_LABEL + data)

    override fun verify(core: BundleCore): Boolean {
        if (core.pubKey.size != 33 || core.signature.size != 64) return false
        if (Identity.peerIdOf(core.pubKey) != core.src) return false          // rẻ: làm trước chữ ký
        return P256.verify(core.pubKey, SIG_LABEL + BundleCodec.immutableBytes(core), core.signature)
    }

    override fun seal(dst: PeerId, kind: Kind, src: PeerId, plaintext: ByteArray): ByteArray? {
        val card = contacts.byPeer(dst) ?: return null
        return SealedBox.seal(card.encPub, plaintext, aadOf(kind, src, dst), random)
    }

    override fun open(core: BundleCore): ByteArray? {
        val dst = core.dst ?: return null
        return SealedBox.open(identity.encPriv, identity.encPub, core.payload, aadOf(core.kind, core.src, dst))
    }

    private fun aadOf(kind: Kind, src: PeerId, dst: PeerId) =
        SealedBox.aad(kind.code, src.hex.hexToBytes(), dst.hex.hexToBytes())

    private companion object {
        val SIG_LABEL = "OM-sig-v1".toByteArray()
    }
}
