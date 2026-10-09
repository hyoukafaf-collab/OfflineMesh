package com.offlinemesh.core.model

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "độ dài hex phải chẵn" }
    require(all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "ký tự hex không hợp lệ" }
    return ByteArray(length / 2) { ((Character.digit(this[2 * it], 16) shl 4) or Character.digit(this[2 * it + 1], 16)).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
