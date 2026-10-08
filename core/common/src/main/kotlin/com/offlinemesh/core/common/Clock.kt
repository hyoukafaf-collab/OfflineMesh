package com.offlinemesh.core.common

/** Mọi code cần giờ phải nhận Clock vào, để test chạy lại đúng một kịch bản. */
interface Clock { fun nowMs(): Long }

object WallClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class FakeClock(private var t: Long = 0L) : Clock {
    override fun nowMs(): Long = t
    fun advance(ms: Long) { t += ms }
    fun set(ms: Long) { t = ms }
}
