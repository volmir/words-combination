package com.example.sostavslova.core

/** Событийный stopped/running автомат. Tick живёт только в памяти процесса. */
class ActiveTimeCounter(private val monotonicMs: () -> Long) {
    private var startTick: Long? = null
    val running: Boolean get() = startTick != null

    fun start() {
        if (startTick == null) startTick = monotonicMs()
    }

    fun flush(): Long? {
        val start = startTick ?: return null
        val end = monotonicMs()
        check(end >= start) { "Monotonic clock moved backwards" }
        val duration = Math.subtractExact(end, start)
        startTick = end
        return duration
    }

    fun stop(): Long? {
        val duration = flush()
        startTick = null
        return duration
    }
}
