```kotlin
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
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/home", headers)

    private fun parseEntries(container: Element): List<SManga> {
        return container
            .select("a[href*=/books/]")
            .mapNotNull { element ->
                val href = element.attr("href").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title = element
                    .selectFirst("div.truncate")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: element.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val thumbnail = element
                    .selectFirst("div.bg-cover")
                    ?.attr("style")
                    ?.let(::extractBackgroundImage)

                SManga.create().apply {
                    this.title = title
                    url = href
                    thumbnail_url = thumbnail
                }
            }
    }

    private fun extractBackgroundImage(style: String): String? {
        if (style.isBlank()) return null

        return Regex(
            """url\(\s*["']?([^)"']+)["']?\s*\)""",
            RegexOption.IGNORE_CASE,
        )
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        return parseHomePage(
            document,
            Regex("正熱門|今日最佳|本週熱門"),
        )
    }

    private fun parseHomePage(
        document: Document,
        sections: Regex,
    ): MangasPage {
        val container = document.selectFirst("div.px-1")
            ?: return MangasPage(emptyList(), false)

        val entries = container
            .children()
            .flatMap { section ->
                val sectionTitle = section
                    .selectFirst("h1, h2, h3")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val fallbackTitle = section
                    .children()
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val title = if (sectionTitle.isNotBlank()) {
                    sectionTitle
                } else {
                    fallbackTitle
                }

                if (sections.containsMatchIn(title)) {
                    parseEntries(section)
                } else {
                    emptyList()
                }
            }
            .distinctBy { it.url }

        return MangasPage(entries, false)
    }

    override fun latestUpdatesRequest(page: Int) =
        popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        return parseHomePage(
            document,
            Regex("最近更新"),
        )
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        return if (query.isNotBlank()) {
            GET(
                "$baseUrl/search?term=$query&page=${page - 1}",
                headers,
            )
        } else {
            val parts = filters
                .filterIsInstance<UriPartFilter>()
                .joinToString("") { it.toUriPart() }

            GET(
                "$baseUrl/books?page=${page - 1}$parts",
                headers,
            )
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val entries = parseEntries(document)

        val hasNextPage = document
            .select("a")
            .any {
                it.text().trim().contains("下一頁")
            }

        return MangasPage(
            entries,
            hasNextPage,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val infobox = parseInfobox(document)

        val title = infobox
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Roumanwu: manga title not found")

        val thumbnail = document
            .selectFirst("div.basis-2\\/5 img")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { imageUrl ->
                runCatching {
                    imageUrl.toHttpUrl()
                        .queryParameter("url")
                        ?: imageUrl
                }.getOrDefault(imageUrl)
            }

        val description = document
            .selectFirst("p:contains(簡介:)")
            ?.text()
            ?.let {
                it.removePrefix("簡介:")
                    .removePrefix("簡介：")
                    .trim()
            }
            .orEmpty()

        val genres = ArrayList<String>()
        var author: String? = null
        var status = SManga.UNKNOWN
        var finalDescription = description

        for (text in infobox.drop(1)) {
            val value = text
                .drop(3)
                .trimStart()

            if (value.isEmpty()) continue

            when (text.take(3)) {
                "別名:" -> {
                    if (value != title) {
                        finalDescription = "$text\n\n$finalDescription"
                    }
                }

                "作者:" -> {
                    author = value
                }

                "狀態:" -> {
                    status = when (value) {
                        "連載中" -> SManga.ONGOING
                        "已完結" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }

                "地區:" -> {
                    genres.add(value)
                }

                "標籤:" -> {
                    genres.addAll(
                        value
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                    )
                }
            }
        }

        return SManga.create().apply {
            this.title = title
            thumbnail_url = thumbnail
            this.author = author
            this.status = status
            genre = genres.joinToString()
            this.description = finalDescription
        }
    }

    private fun parseInfobox(document: Document): List<String> {
        val infobox = document
            .selectFirst("div.basis-3\\/5")
            ?: return emptyList()

        return infobox
            .children()
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapters = document
            .select("a[href~=/books/.*/\\d+]")
            .mapNotNull { element ->
                val url = element
                    .attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val name = element
                    .text()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                SChapter.create().apply {
                    this.url = url
                    this.name = name
                }
            }
            .asReversed()
            .toMutableList()

        if (chapters.isNotEmpty()) {
            for (text in parseInfobox(document).asReversed()) {
                val date = DATE_FORMAT.tryParse(text)

                if (date != 0L) {
                    chapters[0].date_upload = date
                    break
                }
            }
        }

        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return super
            .pageListRequest(chapter)
            .newBuilder()
            .addHeader("rsc", "1")
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()

        return IMAGE_URL_REGEX
            .findAll(html)
            .mapIndexed { index, match ->
                Page(
                    index,
                    imageUrl = match.groupValues[1],
                )
            }
            .toList()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    override fun getFilterList() = FilterList(
        Filter.Header("提示：搜尋時篩選無效"),
        StatusFilter(),
    )

    private abstract class UriPartFilter(
        name: String,
        values: Array<String>,
    ) : Filter.Select<String>(name, values) {

        abstract fun toUriPart(): String
    }

    private class StatusFilter :
        UriPartFilter(
            "狀態",
            arrayOf(
                "全部",
                "連載中",
                "已完結",
            ),
        ) {

        override fun toUriPart() = when (state) {
            1 -> "&continued=true"
            2 -> "&continued=false"
            else -> ""
        }
    }

    companion object {

        private val DATE_FORMAT =
            SimpleDateFormat(
                "M/d/yyyy",
                Locale.ROOT,
            )

        private val IMAGE_URL_REGEX =
            Regex(
                """"imageUrl":"([^"]+)"""",
            )
    }
}
```
