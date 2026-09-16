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
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Roumanwu : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(ScrambledImageInterceptor())
        .build()

    // ---------------------------------------------------------
    // Popular
    // ---------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/books?page=${page - 1}", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaList(document)
    }

    // ---------------------------------------------------------
    // Latest
    // ---------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/home", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val section = document.selectFirst("div.site-home")
            ?: return MangasPage(emptyList(), false)

        val entries = section.children()
            .flatMap { child ->
                val heading = child
                    .selectFirst(".site-section-heading")
                    ?.text()
                    .orEmpty()

                if (
                    heading.contains("最近更新") ||
                    heading.contains("最新更新")
                ) {
                    parseEntries(child)
                } else {
                    emptyList()
                }
            }
            .distinctBy { it.url }

        /*
         * 如果新版首页没有找到“最近更新”区块，
         * 就退回整个页面解析，避免网站再次改版后完全没有结果。
         */
        if (entries.isNotEmpty()) {
            return MangasPage(entries, false)
        }

        return parseMangaList(document)
    }

    // ---------------------------------------------------------
    // Search
    // ---------------------------------------------------------

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        return if (query.isNotBlank()) {
            GET(
                "$baseUrl/search?term=${query.encodeUrl()}&page=${page - 1}",
                headers,
            )
        } else {
            GET(
                "$baseUrl/books?page=${page - 1}",
                headers,
            )
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaList(document)
    }

    // ---------------------------------------------------------
    // Manga list
    // ---------------------------------------------------------

    private fun parseMangaList(document: Document): MangasPage {
        val entries = parseEntries(document)
        val hasNextPage = hasNextPage(document)

        return MangasPage(entries, hasNextPage)
    }

    private fun parseEntries(container: Element): List<SManga> {
        return container
            .select("a.site-comic[href*=/books/]")
            .mapNotNull { element ->

                val url = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val title = element
                    .selectFirst("h3")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val image = element.selectFirst("img")

                val thumbnail = image?.let {
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
    }

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

        val current = parts[0]
            .trim()
            .toIntOrNull()
            ?: return false

        val total = parts[1]
            .trim()
            .toIntOrNull()
            ?: return false

        return current < total
    }

    // ---------------------------------------------------------
    // Manga details
    // ---------------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return parseMangaDetails(document)
    }

    private fun parseMangaDetails(document: Document): SManga {
        return SManga.create().apply {

            val info = document.selectFirst("div.site-book-info")

            /*
             * 新版页面
             */
            if (info != null) {

                title = info
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                if (title.isEmpty()) {
                    title = document
                        .selectFirst("h1")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                }

                thumbnail_url = document
                    .selectFirst("img.site-detail-cover")
                    ?.let {
                        firstNonEmpty(
                            it.absUrl("src"),
                            it.absUrl("data-src"),
                            it.absUrl("data-original"),
                        )
                    }

                val alias = info
                    .selectFirst("p.site-book-alias")
                    ?.text()
                    ?.trim()

                val synopsis = document
                    .selectFirst("div.site-book-synopsis")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                description = buildString {

                    if (!alias.isNullOrBlank() && alias != title) {
                        append("別名：")
                        append(alias)
                        append("\n\n")
                    }

                    if (synopsis.isNotBlank()) {
                        append(synopsis)
                    }
                }

                /*
                 * 解析：
                 *
                 * 作者
                 * 狀態
                 * 地區
                 * 更新
                 */
                val data = info
                    .select("dl.site-book-data dt")
                    .associate { dt ->
                        val key = dt.text().trim()
                        val value = dt
                            .nextElementSibling()
                            ?.text()
                            ?.trim()
                            .orEmpty()

                        key to value
                    }

                author = data["作者"]
                    ?.takeIf { it.isNotBlank() }

                status = when {
                    data["狀態"]
                        ?.contains("連載中") == true -> {
                        SManga.ONGOING
                    }

                    data["狀態"]
                        ?.contains("已完結") == true -> {
                        SManga.COMPLETED
                    }

                    data["狀態"]
                        ?.contains("完結") == true -> {
                        SManga.COMPLETED
                    }

                    else -> {
                        SManga.UNKNOWN
                    }
                }

                /*
                 * 地区 + 分类
                 *
                 * 例如：
                 * 韓國
                 * 韓漫
                 * 戲劇
                 * NTL
                 */
                val genres = mutableListOf<String>()

                data["地區"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        genres.add(it)
                    }

                info
                    .selectFirst("p.site-eyebrow")
                    ?.text()
                    ?.trim()
                    ?.let { text ->

                        text.split("/")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .forEach { genre ->
                                if (!genres.contains(genre)) {
                                    genres.add(genre)
                                }
                            }
                    }

                genre = genres.joinToString(", ")
            } else {

                /*
                 * 旧版页面兼容。
                 *
                 * 如果肉漫屋以后再次调整部分 DOM，
                 * 至少不会因为 selectFirst() == null
                 * 直接导致 NPE。
                 */

                title = document
                    .selectFirst("h1")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                thumbnail_url = document
                    .selectFirst("img")
                    ?.let {
                        firstNonEmpty(
                            it.absUrl("src"),
                            it.absUrl("data-src"),
                            it.absUrl("data-original"),
                        )
                    }

                description = document
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

                author = document
                    .select("dt, div, p")
                    .firstOrNull {
                        it.text()
                            .trim()
                            .startsWith("作者")
                    }
                    ?.nextElementSibling()
                    ?.text()
                    ?.trim()

                status = SManga.UNKNOWN
                genre = null
            }
        }
    }

    // ---------------------------------------------------------
    // Chapters
    // ---------------------------------------------------------

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapters = document
            .select("a.site-chapter-link[href*=/books/]")
            .mapNotNull { element ->

                val url = element
                    .attr("href")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val span = element.selectFirst("span")

                val name = firstNonEmpty(
                    span?.attr("title"),
                    span?.text(),
                    element.attr("title"),
                    element.text(),
                ) ?: return@mapNotNull null

                SChapter.create().apply {
                    this.url = url
                    this.name = name.trim()
                }
            }
            .distinctBy { it.url }
            .asReversed()

        /*
         * 把漫画更新时间放到最新章节。
         */
        if (chapters.isNotEmpty()) {

            val dateText = document
                .selectFirst(
                    "dl.site-book-data dt:contains(更新) + dd",
                )
                ?.text()
                ?.trim()

            val date = parseDate(dateText)

            if (date != 0L) {
                chapters[0].date_upload = date
            }
        }

        return chapters
    }

    // ---------------------------------------------------------
    // Chapter pages
    // ---------------------------------------------------------

    override fun pageListRequest(chapter: SChapter): Request {
        return super.pageListRequest(chapter)
            .newBuilder()
            /*
             * 肉漫屋新版阅读页会根据 RSC 请求返回
             * 页面数据，因此保留这个 header。
             */
            .addHeader("rsc", "1")
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        /*
         * -----------------------------------------------------
         * 第一种：
         * imagePaths:[...]
         *
         * 这是目前新版页面最重要的数据格式。
         * -----------------------------------------------------
         */
        parseImagePaths(body)
            .takeIf { it.isNotEmpty() }
            ?.let { urls ->
                return urls.mapIndexed { index, url ->
                    Page(
                        index,
                        imageUrl = url,
                    )
                }
            }

        /*
         * -----------------------------------------------------
         * 第二种：
         * imageUrl:"..."
         *
         * 兼容旧版 / 某些 RSC 返回内容。
         * -----------------------------------------------------
         */
        val imageUrls = IMAGE_URL_REGEX
            .findAll(body)
            .mapNotNull { match ->
                match.groupValues
                    .getOrNull(1)
                    ?.unescapeUrl()
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .toList()

        if (imageUrls.isNotEmpty()) {
            return imageUrls.mapIndexed { index, url ->
                Page(
                    index,
                    imageUrl = url,
                )
            }
        }

        /*
         * -----------------------------------------------------
         * 第三种：
         * 普通 HTML <img>
         *
         * 如果网站不再返回 RSC，而直接返回 HTML，
         * 仍然可以读取图片。
         * -----------------------------------------------------
         */
        val document = body.asJsoup()

        val htmlImages = document
            .select("img")
            .mapNotNull { image ->

                firstNonEmpty(
                    image.absUrl("src"),
                    image.absUrl("data-src"),
                    image.absUrl("data-original"),
                )
            }
            .filter { url ->
                url.startsWith("http://") ||
                    url.startsWith("https://")
            }
            .distinct()

        if (htmlImages.isNotEmpty()) {
            return htmlImages.mapIndexed { index, url ->
                Page(
                    index,
                    imageUrl = url,
                )
            }
        }

        return emptyList()
    }

    // ---------------------------------------------------------
    // Filters
    // ---------------------------------------------------------

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("搜尋漫畫時不使用篩選條件"),
        )
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private fun parseImagePaths(body: String): List<String> {

        /*
         * 支持：
         *
         * imagePaths:["https://...","https://..."]
         *
         * imagePaths: ["https://...", "..."]
         *
         * "imagePaths":["https://..."]
         */
        val arrayRegex = Regex(
            """["']?imagePaths["']?\s*[:=]\s*\[(.*?)]""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        val array = arrayRegex
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        return URL_IN_ARRAY_REGEX
            .findAll(array)
            .mapNotNull { match ->
                match.groupValues
                    .getOrNull(1)
                    ?.unescapeUrl()
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .toList()
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) {
            return 0L
        }

        val formats = listOf(
            "M/d/yyyy",
            "MM/dd/yyyy",
            "M/d/yyyy HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
        )

        for (format in formats) {
            try {
                val parser = SimpleDateFormat(
                    format,
                    Locale.ROOT,
                )

                parser.isLenient = false

                val date = parser.parse(value.trim())

                if (date != null) {
                    return date.time
                }
            } catch (_: Exception) {
                // Try next format.
            }
        }

        return 0L
    }

    private fun firstNonEmpty(
        vararg values: String?,
    ): String? {
        return values.firstOrNull {
            !it.isNullOrBlank()
        }?.trim()
    }

    private fun String.unescapeUrl(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.encodeUrl(): String {
        return java.net.URLEncoder
            .encode(this, "UTF-8")
    }

    companion object {

        private val IMAGE_URL_REGEX = Regex(
            """"imageUrl"\s*:\s*"([^"]+)"""",
        )

        private val URL_IN_ARRAY_REGEX = Regex(
            """"(https?://[^"]+)"""",
        )
    }
}
