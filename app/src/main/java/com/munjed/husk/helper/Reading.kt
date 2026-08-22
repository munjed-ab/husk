package com.munjed.husk.helper

import android.content.Context
import android.util.Xml
import com.munjed.husk.data.Constants
import com.munjed.husk.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Substack reading list. Topics instead of publications: Substack's public category leaderboards
 * already rank publications by readership, so picking a topic is enough to surface something worth
 * reading without ever naming a newsletter.
 *
 * ponytail: no API key, no library, no parser dependency. One JSON call per topic for the
 * leaderboard, one RSS call per publication picked, both through the platform's own HTTP and
 * XmlPullParser.
 */

data class Article(val title: String, val url: String, val publication: String)

private const val CATEGORY_URL = "https://substack.com/api/v1/category/public/%d/all?page=0"
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Husk"
private const val TIMEOUT_MS = 15000
private const val TOP_PUBLICATIONS = 12
private const val QUEUE_LIMIT = 20
private const val READ_HISTORY_LIMIT = 200

/**
 * Fetches one article per publication, sampling [perTopic] publications from the top of each
 * topic's leaderboard. Sampling rather than always taking rank one keeps the list from showing the
 * same newsletter every day.
 */
suspend fun fetchArticles(topicIds: Collection<Int>, perTopic: Int = 2): List<Article> =
    withContext(Dispatchers.IO) {
        val articles = mutableListOf<Article>()
        for (topicId in topicIds) {
            try {
                val body = httpGet(CATEGORY_URL.format(topicId)) ?: continue
                val publications = JSONObject(body).optJSONArray("publications") ?: continue
                val indices = (0 until minOf(publications.length(), TOP_PUBLICATIONS))
                    .shuffled()
                    .take(perTopic)
                for (index in indices) {
                    val publication = publications.optJSONObject(index) ?: continue
                    val baseUrl = publication.optString("base_url").takeIf { it.isNotBlank() } ?: continue
                    val name = publication.optString("name")
                    latestFromFeed("$baseUrl/feed", name)?.let { articles.add(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        articles
    }

/** Newest item of an RSS feed. Substack and the Ghost sites it redirects to share this shape. */
private fun latestFromFeed(feedUrl: String, publication: String): Article? {
    val xml = httpGet(feedUrl) ?: return null
    return try {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var inItem = false
        var title: String? = null
        var link: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> inItem = true
                    // nextText() collapses CDATA for us, which every Substack title uses
                    "title" -> if (inItem && title == null) title = parser.nextText().trim()
                    "link" -> if (inItem && link == null) link = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") break
            event = parser.next()
        }
        if (title.isNullOrBlank() || link.isNullOrBlank()) null
        else Article(title, link, publication)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun httpGet(url: String): String? = try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.setRequestProperty("User-Agent", USER_AGENT)
    connection.inputStream.bufferedReader().use { it.readText() }
} catch (e: Exception) {
    e.printStackTrace()
    null
}

/** Fetches for the chosen topics and merges into the queue. Returns the queue size afterwards. */
suspend fun refreshReadingList(context: Context): Int {
    val prefs = Prefs(context)
    val topics = prefs.readingTopics.mapNotNull { it.toIntOrNull() }
    if (topics.isEmpty()) return 0
    val fetched = fetchArticles(topics)
    val queue = prefs.readingQueue().toMutableList()
    val known = queue.map { it.url }.toMutableSet()
    val alreadyRead = prefs.readingRead
    for (article in fetched) {
        if (article.url in known || article.url in alreadyRead) continue
        queue.add(article)
        known.add(article.url)
    }
    prefs.setReadingQueue(queue.takeLast(QUEUE_LIMIT))
    prefs.readingLastFetch = System.currentTimeMillis()
    return queue.size
}

fun Prefs.readingQueue(): List<Article> = try {
    val array = JSONArray(readingQueueJson.ifBlank { "[]" })
    (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            Article(it.optString("t"), it.optString("u"), it.optString("p"))
        }
    }.filter { it.title.isNotBlank() && it.url.isNotBlank() }
} catch (e: Exception) {
    e.printStackTrace()
    emptyList()
}

fun Prefs.setReadingQueue(articles: List<Article>) {
    val array = JSONArray()
    articles.forEach {
        array.put(JSONObject().put("t", it.title).put("u", it.url).put("p", it.publication))
    }
    readingQueueJson = array.toString()
}

/** Drops an article from the queue and remembers it, so a refresh will not bring it back. */
fun Prefs.markArticleRead(article: Article) {
    setReadingQueue(readingQueue().filterNot { it.url == article.url })
    readingRead = readingRead.toMutableSet().apply {
        add(article.url)
        while (size > READ_HISTORY_LIMIT) remove(first())
    }
}

val READING_TOPICS: Map<Int, Int> = linkedMapOf(
    Constants.Topic.TECHNOLOGY to com.munjed.husk.R.string.topic_technology,
    Constants.Topic.PHILOSOPHY to com.munjed.husk.R.string.topic_philosophy,
    Constants.Topic.SCIENCE to com.munjed.husk.R.string.topic_science,
    Constants.Topic.BUSINESS to com.munjed.husk.R.string.topic_business,
    Constants.Topic.CULTURE to com.munjed.husk.R.string.topic_culture,
    Constants.Topic.HEALTH to com.munjed.husk.R.string.topic_health,
    Constants.Topic.LITERATURE to com.munjed.husk.R.string.topic_literature,
    Constants.Topic.FAITH to com.munjed.husk.R.string.topic_faith,
)
