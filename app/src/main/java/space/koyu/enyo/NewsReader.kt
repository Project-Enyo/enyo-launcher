package space.koyu.enyo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val newsAction = "today_news"
internal const val newsArticleExtra = "news_article"

/** Opens the reader on the article list, or on one article when [article] is set. */
internal fun Context.newsPendingIntent(id: Int, article: String?): PendingIntent = PendingIntent.getActivity(
    this, newsRequestBase + id * 2,
    Intent(this, EnyoActivity::class.java).setAction(newsAction).putExtra(weatherTokenExtra, weatherWidgetToken())
        .apply { if (article != null) putExtra(newsArticleExtra, article) },
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** The list item template; each headline fills in its article id. */
internal fun Context.newsTemplateIntent(id: Int): PendingIntent = PendingIntent.getActivity(
    this, newsRequestBase + id * 2 + 1,
    Intent(this, EnyoActivity::class.java).setAction(newsAction).putExtra(weatherTokenExtra, weatherWidgetToken()),
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** The article a news intent asks for: an id, an empty string for the list, or null for other intents. */
internal fun Context.newsRequest(intent: Intent?): String? {
    if (intent?.action != newsAction || intent.getStringExtra(weatherTokenExtra) != weatherWidgetToken()) return null
    return intent.getStringExtra(newsArticleExtra).orEmpty()
}

private const val newsRequestBase = 0x4E00000

/** Enyo's reader: the newest articles of every feed, one article in full, and the feed list. */
@Composable
internal fun NewsReader(initial: String, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val articles by NewsStore.articles.collectAsState()
    val feeds by NewsStore.feeds.collectAsState()
    val loading by NewsStore.loading.collectAsState()
    val failed by NewsStore.failed.collectAsState()
    var open by remember(initial) { mutableStateOf(initial.takeIf { it.isNotEmpty() }) }
    var managing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { NewsStore.refresh(context) }
    Dialog(onDismissRequest = { if (open != null && initial.isEmpty()) open = null else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding()) {
            val article = articles.firstOrNull { it.id == open }
            Row(
                Modifier.fillMaxWidth().padding(tokens.gap),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderButton(Glyph.Back, stringResource(R.string.back)) { if (article != null && initial.isEmpty()) open = null else close() }
                Text(
                    article?.source ?: stringResource(R.string.widget_news), Modifier.weight(1f).semantics { heading() },
                    style = tokens.state, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (article == null) {
                    ReaderButton(Glyph.Refresh, stringResource(R.string.news_refresh)) { scope.launch { NewsStore.refresh(context, 0) } }
                    ReaderButton(Glyph.Rss, stringResource(R.string.news_feeds)) { managing = true }
                } else if (article.link != null) {
                    ReaderButton(Glyph.External, stringResource(R.string.news_open_browser)) {
                        context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(article.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (article != null) ArticleView(article)
            else LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = tokens.margin, end = tokens.margin, bottom = tokens.margin),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                if (articles.isEmpty()) item {
                    ListCard { ListNote(stringResource(when {
                        feeds.isEmpty() -> R.string.news_no_feeds
                        loading -> R.string.news_loading
                        failed -> R.string.news_failed
                        else -> R.string.news_empty
                    })) }
                }
                items(articles, key = { it.id }) { item ->
                    ArticleRow(item) { open = item.id }
                }
            }
        }
    }
    if (managing) FeedSheet(feeds) { managing = false }
}

@Composable
private fun ReaderButton(glyph: Glyph, label: String, run: () -> Unit) {
    val tokens = LocalTokens.current
    Box(Modifier.size(tokens.touch).clip(CircleShape).appPress(run).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        EnyoGlyph(glyph, color = tokens.onAccentContainer)
    }
}

@Composable
private fun ArticleRow(article: NewsArticle, open: () -> Unit) {
    val tokens = LocalTokens.current
    val image = rememberArticleImage(article.image)
    ListCard(Modifier.appPress(open)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = tokens.padding, vertical = tokens.listRowPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.padding)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                Text(articleMeta(article), style = tokens.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(article.title, style = tokens.rowTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (article.summary.isNotBlank()) Text(article.summary, style = tokens.rowSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (image != null) Image(
                image, null, Modifier.size(tokens.newsThumbnail).clip(RoundedCornerShape(tokens.radius / 2)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun ArticleView(article: NewsArticle) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val image = rememberArticleImage(article.image)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = tokens.margin).padding(bottom = tokens.margin),
        verticalArrangement = Arrangement.spacedBy(tokens.padding)
    ) {
        if (image != null) Image(
            image, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(tokens.radius)),
            contentScale = ContentScale.Crop
        )
        Text(article.title, style = tokens.articleTitle)
        Text(articleMeta(article), style = tokens.caption)
        Text(article.summary.ifBlank { stringResource(R.string.news_no_text) }, style = tokens.articleBody)
        if (article.link != null) Row {
            PillButton(stringResource(R.string.news_open_browser), filled = true) {
                context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(article.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

@Composable
private fun articleMeta(article: NewsArticle): String {
    val context = LocalContext.current
    return remember(article.id, article.publishedAt) {
        val time = if (article.publishedAt > 0) DateUtils.formatDateTime(
            context, article.publishedAt,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
        ) else null
        listOfNotNull(article.source, time).joinToString(" · ")
    }
}

@Composable
private fun FeedSheet(feeds: List<NewsFeed>, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun add() {
        if (address.isBlank() || adding) return
        adding = true
        failed = false
        scope.launch {
            val added = NewsStore.addFeed(context, address)
            adding = false
            if (added) address = "" else failed = true
        }
    }
    EnyoSheet(stringResource(R.string.news_feeds), close) {
        ListCard {
            feeds.forEach { feed ->
                ListRow(
                    feed.title.ifBlank { Uri.parse(feed.url).host.orEmpty() }, summary = feed.url, summaryLines = 1,
                    leading = { EnyoGlyph(Glyph.Rss, color = tokens.accent) },
                    trailing = { PillButton(stringResource(R.string.news_remove)) { scope.launch { NewsStore.removeFeed(context, feed) } } }
                )
            }
            if (feeds.isEmpty()) ListNote(stringResource(R.string.news_no_feeds))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically) {
            val hint = stringResource(R.string.news_feed_address)
            BasicTextField(
                address, { address = it; failed = false },
                Modifier.weight(1f).heightIn(min = tokens.touch).clip(RoundedCornerShape(tokens.touch / 2)).background(tokens.widgetTrack)
                    .padding(horizontal = tokens.padding, vertical = tokens.gap * 1.5f).semantics { contentDescription = hint },
                singleLine = true,
                textStyle = tokens.body,
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (address.isEmpty()) Text(hint, style = tokens.body.copy(color = tokens.quiet), maxLines = 1)
                        field()
                    }
                }
            )
            PillButton(stringResource(if (adding) R.string.news_adding else R.string.news_add), filled = true, enabled = address.isNotBlank() && !adding) { add() }
        }
        if (failed) Text(stringResource(R.string.news_add_failed), style = tokens.caption)
    }
}

/** A downscaled article picture, loaded only over https and kept small. */
@Composable
private fun rememberArticleImage(url: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, url) {
        value = url?.let { address -> withContext(Dispatchers.IO) { loadArticleImage(address) } }
    }
    return image
}

private val articleImages = android.util.LruCache<String, ImageBitmap>(24)

private fun loadArticleImage(address: String): ImageBitmap? {
    articleImages.get(address)?.let { return it }
    return runCatching {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "Enyo")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readNBytes(6 * 1024 * 1024) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()?.also { articleImages.put(address, it) }
}
