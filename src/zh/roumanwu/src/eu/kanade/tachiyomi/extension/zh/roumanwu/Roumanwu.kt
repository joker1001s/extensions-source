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
import org.jsoup.Jsoup
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
                val url = element
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

                SChapter.create().apply {
                    this.url = url
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

    override fun pageListRequest(chapter: SChapter): Request = super.pageListRequest(chapter)
        .newBuilder()
        .addHeader("rsc", "1")
        .build()

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        val imagePaths = parseImagePaths(body)

        if (imagePaths.isNotEmpty()) {
            return imagePaths.mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
        }

        val imageUrls = IMAGE_URL_REGEX
            .findAll(body)
            .mapNotNull { match ->
                match.groupValues
                    .getOrNull(1)
                    ?.unescapeUrl()
                    ?.takeIf(String::isNotEmpty)
            }
            .distinct()
            .toList()

        if (imageUrls.isNotEmpty()) {
            return imageUrls.mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
        }

        return Jsoup.parse(body, response.request.url.toString())
            .select("img")
            .mapNotNull { image ->
                firstNonEmpty(
                    image.absUrl("src"),
                    image.absUrl("data-src"),
                    image.absUrl("data-original"),
                )
            }
            .filter { url ->
                url.startsWith("http://") || url.startsWith("https://")
            }
            .distinct()
            .mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    private fun parseImagePaths(body: String): List<String> {
        val array = IMAGE_PATHS_REGEX
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
                    ?.takeIf(String::isNotEmpty)
            }
            .distinct()
            .toList()
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

        private val IMAGE_PATHS_REGEX = Regex(
            """["']?imagePaths["']?\s*[:=]\s*\[(.*?)]""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        private val URL_IN_ARRAY_REGEX = Regex(
            """"(https?://[^"]+)"""",
        )

        private val IMAGE_URL_REGEX = Regex(
            """"imageUrl"\s*:\s*"([^"]+)"""",
        )
    }
}
