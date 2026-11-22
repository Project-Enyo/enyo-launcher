package space.koyu.enyo

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class NewsFeed(val url: String, val title: String)

internal data class NewsArticle(
    val id: String,
    val feed: String,
    val source: String,
    val title: String,
    val link: String?,
    val summary: String,
    val image: String?,
    val publishedAt: Long
)

/**
 * RSS 2.0 and Atom feeds for the Today news widget and the reader. Feeds and the latest articles
 * are kept in app storage; a refresh replaces a feed's articles only when it loaded successfully.
 */
internal object NewsStore {
    val articles = MutableStateFlow<List<NewsArticle>>(emptyList())
    val feeds = MutableStateFlow<List<NewsFeed>>(emptyList())
    val loading = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    /** An article the widget asked the reader to show, or an empty id for the list. */
    val requested = MutableStateFlow<String?>(null)
    private val lock = Mutex()
    @Volatile private var loaded = false
    @Volatile private var refreshedAt = 0L

    private fun feedFile(context: Context) = File(context.filesDir, "news_feeds.json")
    private fun cacheFile(context: Context) = File(context.filesDir, "news_articles.json")

    /** Tagesschau and heise on a German phone, BBC World and The Verge everywhere else. */
    fun defaultFeeds(context: Context): List<NewsFeed> =
        if (context.resources.configuration.locales[0].language == "de") listOf(
            NewsFeed("https://www.tagesschau.de/index~rss2.xml", "tagesschau.de"),
            NewsFeed("https://www.heise.de/rss/heise-top-atom.xml", "heise online")
        ) else listOf(
            NewsFeed("https://feeds.bbci.co.uk/news/world/rss.xml", "BBC News"),
            NewsFeed("https://www.theverge.com/rss/index.xml", "The Verge")
        )

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        lock.withLock {
            if (loaded) return@withLock
            feeds.value = runCatching {
                val saved = JSONArray(feedFile(context).readText())
                List(saved.length()) { saved.getJSONObject(it).let { row -> NewsFeed(row.getString("url"), row.optString("title")) } }
            }.getOrElse { defaultFeeds(context) }
            articles.value = runCatching {
                val saved = JSONObject(cacheFile(context).readText())
                refreshedAt = saved.optLong("refreshedAt")
                val rows = saved.getJSONArray("articles")
                List(rows.length()) { rows.getJSONObject(it).toArticle() }
            }.getOrDefault(emptyList())
            loaded = true
        }
    }

    /** Fetches every feed when the cache is older than [maxAgeMillis]. */
    suspend fun refresh(context: Context, maxAgeMillis: Long = refreshMillis) = withContext(Dispatchers.IO) {
        load(context)
        if (System.currentTimeMillis() - refreshedAt < maxAgeMillis) return@withContext
        lock.withLock {
            loading.value = true
            try {
                val current = feeds.value
                val results = coroutineScope {
                    current.map { feed -> async { feed to runCatching { fetch(feed) }.getOrNull() } }.awaitAll()
                }
                val previous = articles.value
                val merged = results.flatMap { (feed, fetched) -> fetched ?: previous.filter { it.feed == feed.url } }
                    .distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(maxArticles)
                failed.value = results.isNotEmpty() && results.all { it.second == null }
                // Feeds that named themselves keep that name.
                val named = current.map { feed ->
                    val title = results.firstOrNull { it.first == feed }?.second?.firstOrNull()?.source
                    if (feed.title.isBlank() && !title.isNullOrBlank()) feed.copy(title = title) else feed
                }
                if (named != current) saveFeeds(context, named)
                articles.value = merged
                refreshedAt = System.currentTimeMillis()
                runCatching {
                    cacheFile(context).writeText(JSONObject().put("refreshedAt", refreshedAt)
                        .put("articles", JSONArray(merged.map { it.toJson() })).toString())
                }
            } finally {
                loading.value = false
            }
        }
        publishTodayWidget(context, TodayWidget.News)
    }

    suspend fun addFeed(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        load(context)
        val clean = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (feeds.value.any { it.url == clean }) return@withContext true
        val fetched = runCatching { fetch(NewsFeed(clean, "")) }.getOrNull() ?: return@withContext false
        saveFeeds(context, feeds.value + NewsFeed(clean, fetched.firstOrNull()?.source.orEmpty()))
        articles.value = (articles.value + fetched).distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(maxArticles)
        publishTodayWidget(context, TodayWidget.News)
        true
    }

    suspend fun removeFeed(context: Context, feed: NewsFeed) = withContext(Dispatchers.IO) {
        saveFeeds(context, feeds.value.filterNot { it.url == feed.url })
        articles.value = articles.value.filterNot { it.feed == feed.url }
        publishTodayWidget(context, TodayWidget.News)
    }

    private fun saveFeeds(context: Context, next: List<NewsFeed>) {
        feeds.value = next
        runCatching {
            feedFile(context).writeText(JSONArray(next.map { JSONObject().put("url", it.url).put("title", it.title) }).toString())
        }
    }

    private fun fetch(feed: NewsFeed): List<NewsArticle>? {
        var url = URL(feed.url)
        repeat(4) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Enyo")
                connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                when (connection.responseCode) {
                    in 300..399 -> {
                        val next = connection.getHeaderField("Location") ?: return null
                        url = URL(url, next)
                        if (url.protocol != "https" && url.protocol != "http") return null
                    }
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use { input ->
                        parse(feed, input.readNBytes(maxFeedBytes).inputStream())
                    }
                    else -> return null
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun parse(feed: NewsFeed, input: java.io.InputStream): List<NewsArticle> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        var channelTitle: String? = null
        val items = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        val path = ArrayDeque<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    path.addLast(name)
                    if (name == "item" || name == "entry") current = mutableMapOf()
                    val item = current
                    if (item != null) when (name) {
                        "link" -> parser.getAttributeValue(null, "href")?.let { href ->
                            val rel = parser.getAttributeValue(null, "rel")
                            if (rel == null || rel == "alternate") item.putIfAbsent("link", href)
                        }
                        "enclosure", "media:content", "media:thumbnail" -> parser.getAttributeValue(null, "url")?.let { src ->
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            if (type.isEmpty() || type.startsWith("image")) item.putIfAbsent("image", src)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: continue
                    val tag = path.lastOrNull() ?: continue
                    val item = current
                    if (item == null) {
                        if (tag == "title" && channelTitle == null && path.size <= 3) channelTitle = text.trim()
                    } else {
                        val key = when (tag) {
                            "title" -> "title"
                            "link" -> "link"
                            "guid", "id" -> "id"
                            "pubdate", "published", "updated", "dc:date" -> "date"
                            "description", "summary" -> "summary"
                            "content:encoded", "content" -> "content"
                            else -> null
                        }
                        if (key != null) item[key] = (item[key].orEmpty() + text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = path.removeLastOrNull()
                    if ((name == "item" || name == "entry") && current != null) {
                        items += current!!
                        current = null
                    }
                }
            }
        }
        val source = channelTitle?.takeIf { it.isNotBlank() } ?: feed.title.ifBlank { URL(feed.url).host }
        return items.mapNotNull { item ->
            val title = plainText(item["title"].orEmpty()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = item["link"]?.trim()?.takeIf { it.startsWith("http") }
            val body = item["content"]?.takeIf { it.isNotBlank() } ?: item["summary"].orEmpty()
            val image = item["image"] ?: imagePattern.find(body)?.groupValues?.get(1)
            NewsArticle(
                id = feed.url + "|" + (item["id"]?.trim()?.takeIf { it.isNotEmpty() } ?: link ?: title),
                feed = feed.url, source = source, title = title, link = link,
                summary = plainText(body), image = image?.takeIf { it.startsWith("https://") },
                publishedAt = parseDate(item["date"]?.trim()) ?: 0L
            )
        }
    }

    private val imagePattern = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Article HTML as readable paragraphs, without markup or images. */
    private fun plainText(html: String): String =
        android.text.Html.fromHtml(html.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), ""), android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString().replace('￼', ' ').replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun parseDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        runCatching { return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z")) {
            runCatching { return SimpleDateFormat(pattern, Locale.US).parse(text)?.time }
        }
        return null
    }

    private fun NewsArticle.toJson() = JSONObject().put("id", id).put("feed", feed).put("source", source).put("title", title)
        .put("link", link).put("summary", summary).put("image", image).put("publishedAt", publishedAt)

    private fun JSONObject.toArticle() = NewsArticle(
        getString("id"), getString("feed"), optString("source"), getString("title"),
        optString("link").takeIf { has("link") && it.isNotBlank() && it != "null" }, optString("summary"),
        optString("image").takeIf { has("image") && it.isNotBlank() && it != "null" }, optLong("publishedAt")
    )

    const val refreshMillis = 30 * 60_000L
    private const val maxArticles = 60
    private const val maxFeedBytes = 4 * 1024 * 1024
    private const val timeoutMillis = 15_000
}
