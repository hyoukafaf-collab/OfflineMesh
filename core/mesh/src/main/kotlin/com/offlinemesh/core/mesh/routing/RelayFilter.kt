package com.offlinemesh.core.mesh.routing

import com.offlinemesh.core.model.Kind

/** Quyết định có nhận/chuyển tiếp một tin của người khác (loại, kích thước nội dung) hay không. */
fun interface RelayFilter {
    fun allow(kind: Kind, size: Int): Boolean
}

object AllowAllRelay : RelayFilter {
    override fun allow(kind: Kind, size: Int) = true
}
