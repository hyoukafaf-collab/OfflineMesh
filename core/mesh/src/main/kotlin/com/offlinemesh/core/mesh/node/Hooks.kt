package com.offlinemesh.core.mesh.node

import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId

/** Ký lên BundleCodec.immutableBytes(core). publicKey được đặt vào tin (nằm trong phần được ký). */
interface Signer {
    val publicKey: ByteArray get() = ByteArray(0)
    fun sign(data: ByteArray): ByteArray
}

interface Verifier { fun verify(core: BundleCore): Boolean }

/** Tuần 3: chưa ký, chưa xác minh. */
object NoCrypto : Signer, Verifier {
    override fun sign(data: ByteArray): ByteArray = ByteArray(0)
    override fun verify(core: BundleCore): Boolean = true
}

/** Mã hóa đầu-cuối cho tin riêng (không áp dụng cho broadcast và ACK). */
interface PayloadCipher {
    /** null nếu không mã hóa được (chưa có khóa của người nhận). */
    fun seal(dst: PeerId, kind: Kind, src: PeerId, plaintext: ByteArray): ByteArray?

    /** null nếu không giải mã được (sai khóa hoặc bản mã bị sửa). */
    fun open(core: BundleCore): ByteArray?
}

/** Không mã hóa (mô phỏng và các test cũ). */
object NoCipher : PayloadCipher {
    override fun seal(dst: PeerId, kind: Kind, src: PeerId, plaintext: ByteArray): ByteArray? = plaintext
    override fun open(core: BundleCore): ByteArray? = core.payload
}

class NodeStats {
    var bundlesSent = 0
    var bytesSent = 0L
    var bundlesReceived = 0
    var duplicates = 0
    var rejected = 0
    var delivered = 0
}
