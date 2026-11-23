package space.koyu.enyo

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a UnifiedInbox message holds beyond its text (SPEC.md, `message`): files, link previews,
 * music, a place, view once and chat notices. Everything comes from another app, so it is bounded
 * and checked on the way in, and links only ever open on http or https.
 */
internal data class InboxAttachmentInfo(
    val id: String,
    val kind: String,
    val name: String?,
    val mime: String?,
    val size: Long?,
    val width: Int?,
    val height: Int?,
    val duration: Long?,
    val waveform: List<Float>,
    val thumbnail: String?,
    val spoiler: Boolean
) {
    val visual get() = kind == "image" || kind == "video" || kind == "gif" || kind == "sticker"
    val playable get() = kind == "voice" || kind == "audio"
}

internal data class InboxPreviewInfo(val url: String, val title: String?, val description: String?, val site: String?, val image: String?)
internal data class InboxMusicInfo(val artist: String, val title: String, val url: String?, val image: String?)
internal data class InboxLocationInfo(val lat: Double, val lng: Double, val accuracy: Double?, val name: String?, val live: Boolean, val until: Long?)

internal data class MessageContent(
    val attachments: List<InboxAttachmentInfo> = emptyList(),
    val previews: List<InboxPreviewInfo> = emptyList(),
    val music: InboxMusicInfo? = null,
    val location: InboxLocationInfo? = null,
    val once: Boolean = false,
    val system: Boolean = false,
    /** The text only describes the rest, such as "Photo"; it is not shown next to it. */
    val description: Boolean = false
) {
    val empty get() = attachments.isEmpty() && previews.isEmpty() && music == null && location == null && !once && !system

    /** Image ids the message shows, for fetching. */
    val images get() = (attachments.mapNotNull { it.thumbnail } + previews.mapNotNull { it.image } + listOfNotNull(music?.image)).distinct()

    fun toJson(): String = JSONObject().apply {
        if (attachments.isNotEmpty()) put("attachments", JSONArray(attachments.map { a ->
            JSONObject().put("id", a.id).put("kind", a.kind).putOpt("name", a.name).putOpt("mime", a.mime).putOpt("size", a.size)
                .putOpt("width", a.width).putOpt("height", a.height).putOpt("duration", a.duration)
                .apply { if (a.waveform.isNotEmpty()) put("waveform", JSONArray(a.waveform.map { it.toDouble() })) }
                .putOpt("thumbnail", a.thumbnail).apply { if (a.spoiler) put("spoiler", true) }
        }))
        if (previews.isNotEmpty()) put("previews", previewsJson(previews))
        music?.let { put("music", JSONObject().put("artist", it.artist).put("title", it.title).putOpt("url", it.url).putOpt("image", it.image)) }
        location?.let { put("location", locationJson(it)) }
        if (once) put("once", true)
        if (system) put("system", true)
        if (description) put("description", true)
    }.toString()

    companion object {
        private const val maxAttachments = 20
        private const val maxPreviews = 4
        private const val maxIdLength = 256
        private const val maxTextLength = 500
        private val kinds = setOf("image", "video", "gif", "sticker", "voice", "audio", "file")

        fun previewsJson(previews: List<InboxPreviewInfo>) = JSONArray(previews.map { p ->
            JSONObject().put("url", p.url).putOpt("title", p.title).putOpt("description", p.description).putOpt("site", p.site).putOpt("image", p.image)
        })

        fun locationJson(l: InboxLocationInfo): JSONObject = JSONObject().put("lat", l.lat).put("lng", l.lng).putOpt("accuracy", l.accuracy)
            .putOpt("name", l.name).apply { if (l.live) put("live", true) }.putOpt("until", l.until)

        private fun JSONObject.text(name: String, limit: Int = maxTextLength) =
            if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotEmpty() }?.take(limit) else null

        private fun JSONObject.id(name: String) = text(name, maxIdLength)

        /** Only web links are kept; anything else could open an arbitrary app. */
        fun webUrl(value: String?): String? = value?.takeIf { it.length <= 2048 }?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?.takeIf { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank() }?.toString()

        fun previews(array: JSONArray?): List<InboxPreviewInfo> = List(minOf(array?.length() ?: 0, maxPreviews)) { array!!.optJSONObject(it) }
            .mapNotNull { p ->
                p ?: return@mapNotNull null
                InboxPreviewInfo(webUrl(p.text("url", 2048)) ?: return@mapNotNull null, p.text("title", 300), p.text("description"), p.text("site", 100), p.id("image"))
            }

        fun location(l: JSONObject?): InboxLocationInfo? {
            l ?: return null
            val lat = l.optDouble("lat").takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
            val lng = l.optDouble("lng").takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
            return InboxLocationInfo(lat, lng, l.optDouble("accuracy").takeIf { it.isFinite() && it >= 0 }, l.text("name", 200),
                l.optBoolean("live"), l.optLong("until").takeIf { it > 0 })
        }

        /** The content of a connector's `message` event, or the stored JSON of it. */
        fun parse(event: JSONObject): MessageContent {
            val attachments = event.optJSONArray("attachments")
            return MessageContent(
                attachments = List(minOf(attachments?.length() ?: 0, maxAttachments)) { attachments!!.optJSONObject(it) }.mapNotNull { a ->
                    a ?: return@mapNotNull null
                    val waveform = a.optJSONArray("waveform")
                    InboxAttachmentInfo(
                        id = a.id("id") ?: return@mapNotNull null,
                        kind = a.text("kind", 20)?.takeIf { it in kinds } ?: "file",
                        name = a.text("name", 200),
                        mime = a.text("mime", 100),
                        size = a.optLong("size").takeIf { it > 0 },
                        width = a.optInt("width").takeIf { it > 0 },
                        height = a.optInt("height").takeIf { it > 0 },
                        duration = a.optLong("duration").takeIf { it > 0 },
                        waveform = List(minOf(waveform?.length() ?: 0, 128)) { waveform!!.optDouble(it).toFloat().takeIf { v -> v.isFinite() }?.coerceIn(0f, 1f) ?: 0f },
                        thumbnail = a.id("thumbnail"),
                        spoiler = a.optBoolean("spoiler")
                    )
                },
                previews = previews(event.optJSONArray("previews")),
                music = event.optJSONObject("music")?.let { m ->
                    InboxMusicInfo(m.text("artist", 200) ?: return@let null, m.text("title", 200) ?: return@let null, webUrl(m.text("url", 2048)), m.id("image"))
                },
                location = location(event.optJSONObject("location")),
                once = event.optBoolean("once"),
                system = event.optBoolean("system"),
                description = event.optBoolean("description")
            )
        }

        fun stored(json: String?): MessageContent? = json?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}

/** One reaction on a message; an empty [sender] is the person. */
internal data class MessageReaction(val emoji: String, val sender: String)
