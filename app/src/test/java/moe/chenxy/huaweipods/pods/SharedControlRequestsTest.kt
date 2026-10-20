package moe.chenxy.huaweipods.pods

import org.junit.Assert.*
import org.junit.Test

class SharedControlRequestsTest {
    @Test fun requestCapacityIsBoundedAndRecoversAfterTimeout() {
        val requests = SharedControlRequests<String>(2)
        assertTrue(requests.add("first", "one"))
        assertTrue(requests.add("second", "two"))
        assertFalse(requests.add("third", "three"))
        assertEquals("one", requests.remove("first"))
        assertTrue(requests.add("third", "three"))
        assertEquals("two", requests["second"])
    }
    @Test fun duplicateNonceCannotReplaceAnOutstandingOperation() {
        val requests = SharedControlRequests<String>()
        assertTrue(requests.add("nonce", "original"))
        assertFalse(requests.add("nonce", "replacement"))
        assertEquals("original", requests.remove("nonce"))
    }
    @Test fun oldReplyAndTimeoutCannotCompleteAnotherRequest() {
        val requests = SharedControlRequests<String>()
        requests.add("old", "off")
        assertEquals("off", requests.remove("old"))
        requests.add("new", "on")
        assertNull(requests.remove("old"))
        assertEquals("on", requests["new"])
    }
}
