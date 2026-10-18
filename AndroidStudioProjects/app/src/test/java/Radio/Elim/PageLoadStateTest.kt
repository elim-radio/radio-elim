package Radio.Elim

import org.junit.Assert.*
import org.junit.Test

class PageLoadStateTest {
    @Test fun readyPageCannotBeCoveredByALateTimeout() {
        val state = PageLoadState()
        state.begin("first")
        assertTrue(state.markReady("first"))
        assertFalse(state.markDelayed("first", "late timeout"))
        assertEquals(PageLoadState.Phase.READY, state.phase)
        assertNull(state.message)
    }

    @Test fun delayedPageRecoversWithoutANewNavigation() {
        val state = PageLoadState()
        state.begin("first")
        assertTrue(state.markDelayed("first", "slow resources"))
        assertTrue(state.awaitingReady("first"))
        assertTrue(state.markReady("first"))
        assertEquals("first", state.pageId)
        assertEquals(PageLoadState.Phase.READY, state.phase)
        assertNull(state.message)
    }

    @Test fun retryIgnoresEveryResultFromThePreviousPage() {
        val state = PageLoadState()
        state.begin("first")
        state.markDelayed("first", "delay")
        state.begin("second")
        assertFalse(state.markReady("first"))
        assertFalse(state.markDelayed("first", "old timer"))
        assertFalse(state.fail("first", "old HTTP error"))
        assertEquals(PageLoadState.Phase.LOADING, state.phase)
        assertNull(state.message)
        assertTrue(state.markReady("second"))
    }

    @Test fun actualDocumentFailureCannotBeClearedByLateReadiness() {
        val state = PageLoadState()
        state.begin("first")
        state.fail("first", "HTTP 404")
        assertFalse(state.markReady("first"))
        assertFalse(state.markDelayed("first", "timeout"))
        assertFalse(state.awaitingReady("first"))
        assertEquals(PageLoadState.Phase.FAILED, state.phase)
        assertEquals("HTTP 404", state.message)
        state.begin("retry")
        assertTrue(state.markReady("retry"))
    }

    @Test fun disposedPageIgnoresPendingCallbacks() {
        val state = PageLoadState()
        state.begin("first")
        state.clear()
        assertFalse(state.markReady("first"))
        assertFalse(state.markDelayed("first", "timeout"))
        assertFalse(state.fail("first", "error"))
        assertNull(state.pageId)
        assertEquals(PageLoadState.Phase.IDLE, state.phase)
    }
}
