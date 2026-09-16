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
import org.jsoup.nodes.Document
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

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/books?page=${page - 1}", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val home = document.selectFirst("div.site-home")

        if (home != null) {
            val entries = home.children()
                .filter { section ->
                    section.selectFirst(".site-section-heading")
                        ?.text()
                        ?.let {
                            it.contains("最近更新") || it.contains("最新更新")
                        } == true
                }
                .flatMap(::parseEntries)
                .distinctBy { it.url }

            if (entries.isNotEmpty()) {
                return MangasPage(entries, false)
            }
        }

        return parseMangaList(document)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val pageIndex = page - 1

        return if (query.isNotBlank()) {
            GET(
                "$baseUrl/search?term=${URLEncoder.encode(query, "UTF-8")}&page=$pageIndex",
                headers,
            )
        } else {
            GET("$baseUrl/books?page=$pageIndex", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val entries = parseEntries(document)
        return MangasPage(entries, hasNextPage(document))
    }

    private fun parseEntries(container: Element): List<SManga> = container
        .select("a.site-comic[href*=/books/]")
        .mapNotNull { element ->
            val url = element.attr("href")
                .trim()
                .takeIf(String::isNotEmpty)
                ?: return@mapNotNull null

            val title = element
                .selectFirst("h3")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null

            val thumbnail = element.selectFirst("img")?.let {
                firstNonEmpty(
                    it.absUrl("src"),
                    it.absUrl("data-src"),
                    it.absUrl("data-original"),
                )
            }

            SManga.create().apply {
                this.title = title
                this.url = url
                thumbnail_url = thumbnail
            }
        }
        .distinctBy { it.url }

    private fun hasNextPage(document: Document): Boolean {
        val pagination = document
            .selectFirst(".site-pagination-mobile")
            ?.text()
            ?.trim()
            ?: return false

        val parts = pagination.split("/")

        if (parts.size < 2) {
            return false
        }

        val current = parts[0].trim().toIntOrNull() ?: return false
        val total = parts[1].trim().toIntOrNull() ?: return false

        return current < total
    }

    override fun mangaDetailsParse(response: Response): SManga = parseMangaDetails(response.asJsoup())

    private fun parseMangaDetails(document: Document): SManga {
        val info = document.selectFirst("div.site-book-info")

        return if (info != null) {
            parseNewMangaDetails(document, info)
        } else {
            parseLegacyMangaDetails(document)
        }
    }

    private fun parseNewMangaDetails(
        document: Document,
        info: Element,
    ): SManga {
        val data = info
            .select("dl.site-book-data dt")
            .associate { dt ->
                val key = dt.text().trim()
                val value = dt.nextElementSibling()
                    ?.text()
                    ?.trim()
                    .orEmpty()

                key to value
            }

        val title = info
            .selectFirst("h1")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifEmpty {
                document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }

        val alias = info
            .selectFirst("p.site-book-alias")
            ?.text()
            ?.trim()

        val synopsis = document
            .selectFirst("div.site-book-synopsis")
            ?.text()
            ?.trim()

        val genres = mutableListOf<String>()

        data["地區"]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(genres::add)

        info.selectFirst("p.site-eyebrow")
            ?.text()
            ?.split("/")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.forEach { genre ->
                if (genre !in genres) {
                    genres.add(genre)
                }
            }

        return SManga.create().apply {
            this.title = title

            thumbnail_url = document
                .selectFirst("img.site-detail-cover")
                ?.let {
                    firstNonEmpty(
                        it.absUrl("src"),
                        it.absUrl("data-src"),
                        it.absUrl("data-original"),
                    )
                }

            author = data["作者"]
                ?.takeIf(String::isNotEmpty)

            status = parseStatus(data["狀態"])

            description = buildDescription(alias, title, synopsis)

            genre = genres
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
        }
    }

    private fun parseLegacyMangaDetails(
        document: Document,
    ): SManga {
        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            .orEmpty()

        val thumbnail = document
            .selectFirst("div.basis-2\\/5 img")
            ?.let {
                firstNonEmpty(
                    it.absUrl("src"),
                    it.absUrl("data-src"),
                    it.absUrl("data-original"),
                )
            }

        val description = document
            .selectFirst(
                "div.site-book-synopsis, p:contains(簡介:), p:contains(简介:)",
            )
            ?.text()
            ?.replaceFirst(
                Regex("^簡介\\s*[:：]?\\s*"),
                "",
            )
            ?.replaceFirst(
                Regex("^简介\\s*[:：]?\\s*"),
                "",
            )
            ?.trim()

        val author = document
            .select("dt")
            .firstOrNull {
                it.text().trim().startsWith("作者")
            }
            ?.nextElementSibling()
            ?.text()
            ?.trim()

        return SManga.create().apply {
            this.title = title
            thumbnail_url = thumbnail
            this.author = author
            this.description = description
            status = SManga.UNKNOWN
        }
    }

    private fun parseStatus(value: String?): Int = when {
        value?.contains("連載中") == true -> SManga.ONGOING
        value?.contains("連載") == true -> SManga.ONGOING
        value?.contains("已完結") == true -> SManga.COMPLETED
        value?.contains("完結") == true -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun buildDescription(
        alias: String?,
        title: String,
        synopsis: String?,
    ): String? {
        val parts = mutableListOf<String>()

        if (!alias.isNullOrBlank() && alias != title) {
            parts.add("別名：$alias")
        }

        if (!synopsis.isNullOrBlank()) {
            parts.add(synopsis)
        }

        return parts
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapters = document
            .select("a.site-chapter-link[href*=/books/]")
            .mapNotNull { element ->
                val pageUrl = element
                    .attr("href")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null

                val span = element.selectFirst("span")

                val name = firstNonEmpty(
                    span?.attr("title"),
                    span?.text(),
                    element.attr("title"),
                    element.text(),
                ) ?: return@mapNotNull null

                val apiUrl = convertChapterUrlToApi(pageUrl)

                SChapter.create().apply {
                    url = apiUrl
                    this.name = name
                }
            }
            .distinctBy { it.url }
            .asReversed()
            .toMutableList()

        if (chapters.isNotEmpty()) {
            val dateText = document
                .selectFirst("dl.site-book-data dt:contains(更新) + dd")
                ?.text()
                ?.trim()

            parseDate(dateText)
                .takeIf { it != 0L }
                ?.let {
                    chapters[0].date_upload = it
                }
        }

        return chapters
    }

    /**
     * Roumanwu 的章节页面不是实际漫画数据接口。
     *
     * 网页章节：
     * /books/xxxx
     *
     * 实际章节 API：
     * /api/books/xxxx
     *
     * API 返回：
     * {
     *   "chapter": {
     *     "images": [
     *       {"src": "..."},
     *       {"src": "..."}
     *     ]
     *   }
     * }
     */
    private fun convertChapterUrlToApi(url: String): String {
        val normalized = url
            .removePrefix(baseUrl)
            .trim()

        return when {
            normalized.startsWith("/api/books/") -> normalized
            normalized.startsWith("/books/") -> normalized.replaceFirst(
                "/books/",
                "/api/books/",
            )

            else -> normalized
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(
        baseUrl + chapter.url,
        headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("X-Requested-With", "XMLHttpRequest")
            .build(),
    )

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        val imageUrls = parseApiImages(body)

        if (imageUrls.isEmpty()) {
            return emptyList()
        }

        return imageUrls.mapIndexed { index, url ->
            Page(
                index = index,
                imageUrl = url,
            )
        }
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    /**
     * 只解析：
     *
     * chapter.images[].src
     *
     * 不再扫描整个 HTML 的 img 标签。
     *
     * 这样可以避免把：
     * - 弹窗广告
     * - logo
     * - banner
     * - 推荐漫画
     * - 统计图片
     *
     * 当成漫画页面。
     */
    private fun parseApiImages(body: String): List<String> {
        val chapterStart = findChapterObject(body)

        if (chapterStart < 0) {
            return emptyList()
        }

        val imagesStart = body.indexOf("\"images\"", chapterStart)

        if (imagesStart < 0) {
            return emptyList()
        }

        val arrayStart = body.indexOf('[', imagesStart)

        if (arrayStart < 0) {
            return emptyList()
        }

        val arrayEnd = findMatchingBracket(body, arrayStart)

        if (arrayEnd < 0) {
            return emptyList()
        }

        val imagesJson = body.substring(arrayStart, arrayEnd + 1)

        return IMAGE_SRC_REGEX
            .findAll(imagesJson)
            .mapNotNull { match ->
                match.groupValues
                    .getOrNull(1)
                    ?.unescapeUrl()
                    ?.normalizeImageUrl()
                    ?.takeIf(::isComicImage)
            }
            .distinct()
            .toList()
    }

    private fun findChapterObject(body: String): Int {
        val jsonChapter = body.indexOf("\"chapter\"")

        if (jsonChapter >= 0) {
            return jsonChapter
        }

        val jsChapter = body.indexOf("'chapter'")

        if (jsChapter >= 0) {
            return jsChapter
        }

        return -1
    }

    private fun findMatchingBracket(
        body: String,
        start: Int,
    ): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        var quote = '\u0000'

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

    private fun String.normalizeImageUrl(): String? {
        val value = trim()

        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> baseUrl + value
            else -> null
        }
    }

    private fun isComicImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        if (
            lower.contains("logo") ||
            lower.contains("avatar") ||
            lower.contains("banner") ||
            lower.contains("favicon") ||
            lower.contains("popup") ||
            lower.contains("pop-up") ||
            lower.contains("advert") ||
            lower.contains("adsense") ||
            lower.contains("tracking") ||
            lower.contains("analytics") ||
            lower.contains("icon")
        ) {
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

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) {
            return 0L
        }

        DATE_FORMATS.forEach { format ->
            try {
                val parser = SimpleDateFormat(format, Locale.ROOT)
                parser.isLenient = false

                return parser.parse(value.trim())?.time ?: 0L
            } catch (_: Exception) {
            }
        }

        return 0L
    }

    private fun firstNonEmpty(vararg values: String?): String? = values
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()

    private fun String.unescapeUrl(): String = replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("搜尋漫畫時不使用篩選條件"),
    )

    companion object {
        private val DATE_FORMATS = listOf(
            "M/d/yyyy",
            "MM/dd/yyyy",
            "M/d/yyyy HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
        )

        /**
         * 匹配：
         *
         * "src":"https://xxxx/xxx.jpg"
         *
         * 只在 chapter.images 数组里面使用。
         */
        private val IMAGE_SRC_REGEX = Regex(
            """"src"\s*:\s*"([^"]+)"""",
        )
    }
}
