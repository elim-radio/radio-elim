package Radio.Elim

import java.net.URI

/** Pure rules shared by the bridge and the service. No wildcard web/native bridge. */
object PlaybackRules {
    const val ORIGIN = "https://elim-radio.github.io"
    const val MAX_QUEUE = 1500

    fun isDocument(url: String): Boolean = runCatching {
        val uri = URI(url).normalize()
        uri.scheme == "https" && uri.host == "elim-radio.github.io" &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.path.split('/').none { it == ".." || it == "." } &&
            (uri.path == "/radio-elim" || uri.path.startsWith("/radio-elim/"))
    }.getOrDefault(false)

    fun isAudio(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "jbch.org" || host.endsWith(".jbch.org"))
    }.getOrDefault(false)

    fun speed(value: Float): Float =
        if (value in listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) value else 1f
}
