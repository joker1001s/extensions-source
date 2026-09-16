package eu.kanade.tachiyomi.extension.zh.roumanwu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Roumanwu : HttpSource() {
    
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(ScrambledImageInterceptor())
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaPage(response)

    override fun popularMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaPage(response)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encodedQuery&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaPage(response)

    override fun searchMangaNextPageSelector(): String? = null

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("搜索支持网站自身搜索"),
    )

    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = mutableListOf<SManga>()
        val seen = HashSet<String>()

        val selectors = listOf(
            "a[href*='/manga/']",
            "a[href*='/comic/']",
            "a[href*='/book/']",
            ".book-item a",
            ".comic-item a",
            ".manga-item a",
            ".item a",
        )

        for (selector in selectors) {
            for (element in document.select(selector)) {
                val href = element.absUrl("href").trim()

                if (href.isBlank() || !seen.add(href)) {
                    continue
                }

                val title = firstNonEmpty(
                    element.attr("title"),
                    element.selectFirst("img")?.attr("alt"),
                    element.text(),
                ) ?: continue

                if (title.isBlank()) {
                    continue
                }

                mangas.add(
                    SManga.create().apply {
                        url = href.removePrefix(baseUrl)
                        this.title = title
                        thumbnail_url = element.selectFirst("img")
                            ?.absUrl("src")
                            ?.takeIf(::isValidImageUrl)
                    },
                )
            }
        }

        return MangasPage(
            mangas,
            hasNextPage = false,
        )
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = firstNonEmpty(
                document.selectFirst("h1")?.text(),
                document.selectFirst("h2")?.text(),
                document.selectFirst("meta[property=og:title]")?.attr("content"),
            ) ?: ""

            thumbnail_url = firstNonEmpty(
                document.selectFirst("meta[property=og:image]")?.attr("content"),
                document.selectFirst("img")?.absUrl("src"),
            )?.takeIf(::isValidImageUrl)

            author = firstNonEmpty(
                document.selectFirst(".author")?.text(),
                document.selectFirst("[class*=author]")?.text(),
            )

            artist = author

            description = firstNonEmpty(
                document.selectFirst(".description")?.text(),
                document.selectFirst("[class*=description]")?.text(),
                document.selectFirst(".intro")?.text(),
                document.selectFirst("[class*=intro]")?.text(),
            )

            status = parseStatus(
                firstNonEmpty(
                    document.selectFirst(".status")?.text(),
                    document.selectFirst("[class*=status]")?.text(),
                ),
            )
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        val seen = HashSet<String>()

        val selectors = listOf(
            "a[href*='/chapter/']",
            "a[href*='/read/']",
            "a[href*='/comic/']",
            "a[href*='/manga/']",
            ".chapter-list a",
            ".chapters a",
            ".chapter a",
        )

        for (selector in selectors) {
            for (element in document.select(selector)) {
                val href = element.absUrl("href").trim()

                if (href.isBlank() || !seen.add(href)) {
                    continue
                }

                val name = firstNonEmpty(
                    element.attr("title"),
                    element.text(),
                ) ?: continue

                if (name.isBlank()) {
                    continue
                }

                chapters.add(
                    SChapter.create().apply {
                        url = href.removePrefix(baseUrl)
                        this.name = name
                        chapter_number = parseChapterNumber(name)
                        date_upload = parseDate(element)
                    },
                )
            }
        }

        if (chapters.isEmpty()) {
            return parseChapterData(response.body.string())
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter): Request = super.pageListRequest(chapter)
        .newBuilder()
        .addHeader("rsc", "1")
        .build()

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        return parseChapterImages(body).mapIndexed { index, url ->
            Page(
                index = index,
                imageUrl = url,
            )
        }
    }

    private fun parseChapterImages(body: String): List<String> {
        val results = mutableListOf<String>()
        var searchStart = 0

        while (searchStart < body.length) {
            val markerStart = findImagePathsMarker(body, searchStart)

            if (markerStart < 0) {
                break
            }

            val arrayStart = findArrayStart(body, markerStart)

            if (arrayStart < 0) {
                searchStart = markerStart + IMAGE_PATHS_KEY.length
                continue
            }

            val arrayEnd = findArrayEnd(body, arrayStart)

            if (arrayEnd < 0) {
                searchStart = markerStart + IMAGE_PATHS_KEY.length
                continue
            }

            val array = body.substring(arrayStart + 1, arrayEnd)

            extractUrls(array)
                .mapNotNull(::normalizeImageUrl)
                .filter(::isComicImageUrl)
                .forEach { url ->
                    if (!results.contains(url)) {
                        results.add(url)
                    }
                }

            searchStart = arrayEnd + 1
        }

        return results
    }

    private fun findImagePathsMarker(body: String, start: Int): Int {
        val markers = listOf(
            "\"imagePaths\"",
            "'imagePaths'",
            "imagePaths:",
            "imagePaths=",
        )

        return markers
            .map { body.indexOf(it, start) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
    }

    private fun findArrayStart(body: String, markerStart: Int): Int {
        var index = markerStart

        while (index < body.length && index < markerStart + 500) {
            when (body[index]) {
                '[' -> return index
                ':', '=' -> Unit
            }

            index++
        }

        return -1
    }

    private fun findArrayEnd(body: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var quote = '\u0000'
        var escaped = false

        for (index in start until body.length) {
            val char = body[index]

            if (escaped) {
                escaped = false
                continue
            }

            if (quoted && char == '\\') {
                escaped = true
                continue
            }

            if (quoted) {
                if (char == quote) {
                    quoted = false
                }

                continue
            }

            if (char == '"' || char == '\'') {
                quoted = true
                quote = char
                continue
            }

            when (char) {
                '[' -> depth++
                ']' -> {
                    depth--

                    if (depth == 0) {
                        return index
                    }
                }
            }
        }

        return -1
    }

    private fun extractUrls(array: String): List<String> = IMAGE_STRING_REGEX
        .findAll(array)
        .mapNotNull { match ->
            match.groupValues
                .getOrNull(1)
                ?.unescapeUrl()
                ?.takeIf(String::isNotBlank)
        }
        .toList()

    private fun normalizeImageUrl(value: String): String? {
        val url = value
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim('"', '\'')

        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> null
        }
    }

    private fun isComicImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        val blockedWords = listOf(
            "loading",
            "favicon",
            "logo",
            "avatar",
            "banner",
            "popup",
            "pop-up",
            "advert",
            "ads",
            "adsense",
            "googlead",
            "tracking",
            "icon",
        )

        if (blockedWords.any(lower::contains)) {
            return false
        }

        val path = lower
            .substringBefore('?')
            .substringBefore('#')

        return path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif")
    }

    private fun isValidImageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }

        return url.startsWith("http://") ||
            url.startsWith("https://") ||
            url.startsWith("//")
    }

    private fun parseChapterData(body: String): List<SChapter> {
        val result = mutableListOf<SChapter>()

        val regex = Regex(
            """["']([^"']*(?:chapter|chap)[^"']*)["']\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )

        val seen = HashSet<String>()

        for (match in regex.findAll(body)) {
            val name = match.groupValues
                .getOrNull(1)
                ?.trim()
                ?: continue

            val url = match.groupValues
                .getOrNull(2)
                ?.trim()
                ?: continue

            if (url.isBlank() || !seen.add(url)) {
                continue
            }

            result.add(
                SChapter.create().apply {
                    this.url = normalizeChapterUrl(url)
                    this.name = name
                    chapter_number = parseChapterNumber(name)
                },
            )
        }

        return result.sortedByDescending { it.chapter_number }
    }

    private fun normalizeChapterUrl(url: String): String = when {
        url.startsWith("http://") -> url.removePrefix(baseUrl)
        url.startsWith("https://") -> url.removePrefix(baseUrl)
        url.startsWith("/") -> url
        else -> "/$url"
    }

    private fun parseChapterNumber(name: String): Float {
        val match = Regex(
            """(?:第\s*)?(\d+(?:\.\d+)?)""",
        ).find(name)

        return match?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: -1f
    }

    private fun parseDate(element: Element): Long {
        val value = firstNonEmpty(
            element.attr("data-date"),
            element.attr("data-time"),
            element.selectFirst("time")?.attr("datetime"),
            element.selectFirst("time")?.text(),
        ) ?: return 0L

        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
        )

        for (format in formats) {
            try {
                return SimpleDateFormat(
                    format,
                    Locale.getDefault(),
                ).parse(value)?.time ?: 0L
            } catch (_: Exception) {
                continue
            }
        }

        return 0L
    }

    private fun parseStatus(value: String?): Int = when {
        value.isNullOrBlank() -> SManga.UNKNOWN
        value.contains("完结") -> SManga.COMPLETED
        value.contains("连载") -> SManga.ONGOING
        value.contains("停更") -> SManga.ON_HIATUS
        value.contains("休刊") -> SManga.ON_HIATUS
        value.contains("腰斩") -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun firstNonEmpty(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun String.unescapeUrl(): String = replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .trim()

    private companion object {
        const val IMAGE_PATHS_KEY = "imagePaths"

        val IMAGE_STRING_REGEX = Regex(
            """["']((?:https?:)?(?:\\/\\/|/)[^"']+)["']""",
        )
    }
}
