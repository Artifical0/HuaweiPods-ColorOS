package moe.chenxy.huaweipods.pods

/** Bound outstanding UI requests; timeouts and replies remove the same nonce at most once. */
internal class SharedControlRequests<T>(private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val pending = mutableMapOf<String, T>()
    @Synchronized fun add(nonce: String, request: T): Boolean {
        if (pending.size >= capacity || pending.containsKey(nonce)) return false
        pending[nonce] = request
        return true
    }
    @Synchronized operator fun get(nonce: String): T? = pending[nonce]
    @Synchronized fun remove(nonce: String): T? = pending.remove(nonce)
}
