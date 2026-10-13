package moe.chenxy.huaweipods.broadcast

internal enum class HeadsetEventKind { CONNECT, STATE, BATTERY, DISCONNECT }
internal data class HeadsetDelivery<T>(val id: Long, val address: String, val kind: HeadsetEventKind,
                                       val elapsed: Long, val payload: T)

/** Keep the in-flight head and newest state, including the final disconnect, until applied. */
internal class HeadsetDeliveryQueue<T>(private val capacity: Int = 16) {
    init { require(capacity >= 2) }
    private val events = mutableListOf<HeadsetDelivery<T>>()
    private val latestTimes = linkedMapOf<String, Long>()
    private val disconnectedAt = linkedMapOf<String, Long>()
    private var nextId = 0L
    private var protectedId: Long? = null
    val size get() = events.size
    fun peek(): HeadsetDelivery<T>? = events.firstOrNull()
    fun enqueue(address: String, kind: HeadsetEventKind, elapsed: Long, payload: T): Boolean {
        val key = address.uppercase()
        if (key.isBlank() || elapsed < (latestTimes[key] ?: Long.MIN_VALUE)) return false
        if (kind != HeadsetEventKind.CONNECT && kind != HeadsetEventKind.DISCONNECT &&
            disconnectedAt.containsKey(key)) return false
        latestTimes[key] = elapsed
        if (kind == HeadsetEventKind.DISCONNECT) disconnectedAt[key] = elapsed
        if (kind == HeadsetEventKind.CONNECT) disconnectedAt.remove(key)
        events.removeAll { it.id != protectedId && it.address == key &&
            (it.kind == kind || kind == HeadsetEventKind.DISCONNECT || kind == HeadsetEventKind.CONNECT) }
        if (events.size >= capacity) {
            val index = events.indexOfFirst { it.id != protectedId && it.kind != HeadsetEventKind.DISCONNECT }
                .takeIf { it >= 0 } ?: events.indexOfFirst { it.id != protectedId }
            if (index < 0) return false
            events.removeAt(index)
        }
        events.add(HeadsetDelivery(++nextId, key, kind, elapsed, payload))
        if (latestTimes.size > 32) latestTimes.remove(latestTimes.keys.first())
        if (disconnectedAt.size > 32) disconnectedAt.remove(disconnectedAt.keys.first())
        return true
    }
    fun protectHead(): HeadsetDelivery<T>? = peek()?.also { protectedId = it.id }
    fun acknowledge(id: Long): Boolean {
        if (peek()?.id != id) return false
        events.removeAt(0); protectedId = null
        return true
    }
    fun clear() { events.clear(); protectedId = null; latestTimes.clear(); disconnectedAt.clear() }
}

internal class HeadsetDeliveryRetry(private val maxFailures: Int = 5) {
    private var failures = 0
    fun nextDelay(): Long? = if (failures >= maxFailures) null else
        longArrayOf(250, 1_000, 2_000, 4_000, 8_000)[(failures++).coerceAtMost(4)]
    fun reset() { failures = 0 }
}

internal class HeadsetAppliedLedger(private val capacity: Int = 32) {
    private val applied = linkedMapOf<String, Long>()
    fun wasApplied(client: String, id: Long): Boolean = id <= (applied[client] ?: 0)
    fun record(client: String, id: Long) {
        applied[client] = maxOf(applied[client] ?: 0, id)
        if (applied.size > capacity) applied.remove(applied.keys.first())
    }
}
