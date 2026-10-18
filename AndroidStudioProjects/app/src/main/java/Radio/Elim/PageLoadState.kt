package Radio.Elim

/** One document's startup state. A delay is recoverable; a document failure is not. */
class PageLoadState {
    enum class Phase { IDLE, LOADING, DELAYED, READY, FAILED }

    var pageId: String? = null
        private set
    var phase = Phase.IDLE
        private set
    var message: String? = null
        private set

    fun begin(id: String) {
        pageId = id
        phase = Phase.LOADING
        message = null
    }

    fun isCurrent(id: String): Boolean = pageId == id

    fun awaitingReady(id: String): Boolean = isCurrent(id) &&
        (phase == Phase.LOADING || phase == Phase.DELAYED)

    fun markReady(id: String): Boolean {
        if (!awaitingReady(id)) return false
        phase = Phase.READY
        message = null
        return true
    }

    fun markDelayed(id: String, detail: String): Boolean {
        if (!isCurrent(id) || phase != Phase.LOADING) return false
        phase = Phase.DELAYED
        message = detail
        return true
    }

    fun fail(id: String, detail: String): Boolean {
        if (!isCurrent(id)) return false
        phase = Phase.FAILED
        message = detail
        return true
    }

    fun clear() {
        pageId = null
        phase = Phase.IDLE
        message = null
    }
}
