package Radio.Elim

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

object PlaybackItems {
    fun fromJson(data: JSONObject): MediaItem {
        val src = data.getString("src")
        require(PlaybackRules.isAudio(src)) { "지원하지 않는 음원 주소입니다." }
        val id = data.getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val tab = if (data.optString("tab") == "sermon") "sermon" else "radio"
        val title = data.getString("title").take(500)
        val video = data.optString("youtubeId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val extras = Bundle().apply {
            putString("src", src)
            putString("tab", tab)
            putString("date", data.optString("date").take(30))
            putString("youtubeId", video.orEmpty())
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(if (tab == "sermon") "주일말씀" else "라디오 엘림")
            .setAlbumTitle("생명의말씀선교회 광주교회")
            .setExtras(extras)
        if (video != null) metadata.setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$video/hqdefault.jpg"))
        return MediaItem.Builder().setMediaId(id).setUri(src)
            .setMediaMetadata(metadata.build()).build()
    }

    fun toJson(item: MediaItem): JSONObject = JSONObject()
        .put("id", item.mediaId)
        .put("src", item.localConfiguration?.uri?.toString()
            ?: item.mediaMetadata.extras?.getString("src").orEmpty())
        .put("title", item.mediaMetadata.title?.toString().orEmpty())
        .put("tab", item.mediaMetadata.extras?.getString("tab") ?: "radio")
        .put("date", item.mediaMetadata.extras?.getString("date").orEmpty())
        .put("youtubeId", item.mediaMetadata.extras?.getString("youtubeId").orEmpty())

    fun queue(array: JSONArray): List<MediaItem> {
        require(array.length() in 1..PlaybackRules.MAX_QUEUE)
        val items = (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        require(items.map { it.mediaId }.distinct().size == items.size)
        return items
    }
}
