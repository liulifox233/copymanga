package eu.kanade.tachiyomi.extension.zh.copymanga

import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Copymanga : ConfigurableSource, HttpSource() {
    override val name = "拷贝漫画"
    override val lang = "zh"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl = "https://api.mangacopy.com"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val apiInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 210) {
            response.close()
            throw Exception("触发风控，请稍后重试")
        }

        if (!response.isSuccessful) {
            try {
                val error = json.decodeFromString<ApiResponse<Any>>(response.body.string())
                throw Exception("请求失败: ${error.message}")
            } catch (e: Exception) {
                throw Exception("请求失败: ${response.code}")
            }
        }

        response
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(apiInterceptor)
        .rateLimitHost(
            baseUrl.toHttpUrl(),
            preferences.getString(MAINSITE_RATEPERMITS_PREF, MAINSITE_RATEPERMITS_PREF_DEFAULT)!!
                .toInt(),
            preferences.getString(MAINSITE_RATEPERIOD_PREF, MAINSITE_RATEPERIOD_PREF_DEFAULT)!!
                .toLong(),
            TimeUnit.MILLISECONDS,
        )
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "COPY/2.2.5")
        .add("Accept", "application/json")
        .add("source", "copyApp")
        .add("platform", "4")
        .add("webp", "1")
        .add("Referer", "com.copymanga.app-2.2.5")

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/api/v3/recs?pos=3200102&limit=30&offset=${(page - 1) * 30}", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result =
            json.decodeFromString<ApiResponse<PopularMangaResponse>>(response.body.string())
        if (result.code != 200 || result.results == null) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val mangas = result.results.list.map { item ->
            SManga.create().apply {
                title = item.comic.name
                url = "/api/v3/comic2/${item.comic.path_word}"
                thumbnail_url = item.comic.cover
                author = item.comic.author.joinToString { a -> a.name }
                genre = item.comic.theme.joinToString { it.name }
            }
        }

        return MangasPage(
            mangas,
            hasNextPage = result.results.total > result.results.offset + result.results.limit,
        )
    }

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/v3/update/newest?limit=30&offset=${(page - 1) * 30}", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result =
            json.decodeFromString<ApiResponse<LatestUpdatesResponse>>(response.body.string())
        if (result.code != 200 || result.results == null) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val mangas = result.results.list.map { item ->
            SManga.create().apply {
                title = item.comic.name
                url = "/api/v3/comic2/${item.comic.path_word}"
                thumbnail_url = item.comic.cover
                author = item.comic.author.joinToString { a -> a.name }
            }
        }

        return MangasPage(
            mangas,
            hasNextPage = result.results.total > result.results.offset + result.results.limit,
        )
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            GET(
                "$baseUrl/api/v3/search/comic?limit=30&offset=${(page - 1) * 30}&q=$query&platform=4",
                headers,
            )
        } else {
            // Handle filters if needed
            popularMangaRequest(page)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseSearchResponse(response)
    }

    private fun parseSearchResponse(response: Response): MangasPage {
        val rawJson = response.body.string()
        val result = json.decodeFromString<ApiResponse<PaginationData<ComicSearchData>?>>(rawJson)
        if (result.code != 200 || result.results == null) {
            throw Exception("请求失败: ${result.message.ifEmpty { "未知错误" }}")
        }

        val mangas = result.results.list.map {
            SManga.create().apply {
                title = it.name
                url = "/api/v3/comic2/${it.path_word}"
                thumbnail_url = it.cover
                author = it.author.joinToString { a -> a.name }
            }
        }

        return MangasPage(
            mangas,
            result.results.total > result.results.offset + result.results.limit,
        )
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        val pathWord = manga.url.substringAfter("/comic2/")
        return GET("$baseUrl/api/v3/comic2/$pathWord?platform=4", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = json.decodeFromString<ApiResponse<ComicDetailData>>(response.body.string())
        if (result.code != 200 || result.results == null) {
            throw Exception("请求失败: ${result.message.ifEmpty { "未知错误" }}")
        }
        return SManga.create().apply {
            title = result.results.comic.name
            description = result.results.comic.brief
            thumbnail_url = result.results.comic.cover
            genre = result.results.comic.theme.joinToString { it.name }
            author = result.results.comic.author.joinToString { it.name }
            status = when {
                result.results.comic.b_404 -> SManga.CANCELLED
                result.results.comic.b_hidden -> SManga.LICENSED
                else -> SManga.UNKNOWN
            }
            url = "/api/v3/comic2/${result.results.comic.path_word}"
        }
    }

    // Chapter List
    override fun chapterListRequest(manga: SManga): Request {
        val pathWord = manga.url.substringAfter("/comic2/")
        return GET("$baseUrl/api/v3/comic2/$pathWord?platform=4", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = json.decodeFromString<ApiResponse<ComicDetailData>>(response.body.string())
        if (result.code != 200 || result.results == null) {
            throw Exception("请求失败: ${result.message.ifEmpty { "未知错误" }}")
        }

        val pathWord = response.request.url.pathSegments[3]
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

        val allChapters = mutableListOf<SChapter>()
        val defaultGroupPath = result.results.groups.values.firstOrNull()?.path_word ?: "default"
        var chapterCounter = 0

        result.results.groups.values.forEach { group ->
            try {
                val chapterResponse = client.newCall(
                    GET(
                        "$baseUrl/api/v3/comic/$pathWord/group/${group.path_word}/chapters?platform=4&limit=500",
                        headers,
                    ),
                ).execute()

                val chapterResult = json.decodeFromString<ApiResponse<PaginationData<ChapterData>>>(
                    chapterResponse.body.string(),
                )

                if (chapterResult.code == 200 && chapterResult.results != null) {
                    val groupChapters = chapterResult.results.list.map {
                        SChapter.create().apply {
                            name = if (group.path_word == defaultGroupPath) {
                                it.name
                            } else {
                                "${group.name}: ${it.name}"
                            }
                            chapter_number = chapterCounter++.toFloat()
                            date_upload = try {
                                dateFormat.parse(it.datetime_created)?.time ?: 0L
                            } catch (e: ParseException) {
                                0L
                            }
                            url = "/api/v3/comic/${it.comic_path_word}/chapter2/${it.uuid}"
                        }
                    }
                    allChapters.addAll(groupChapters)
                }
            } catch (e: Exception) {
                println("Error fetching chapters for group ${group.name}: ${e.message}")
            }
        }

        return allChapters.reversed()
    }

    // Page List
    override fun pageListRequest(chapter: SChapter): Request {
        val (comicPath, uuid) = chapter.url.split("/chapter2/")
        return GET("$baseUrl$comicPath/chapter2/$uuid?platform=4", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result =
            json.decodeFromString<ApiResponse<ChapterContentResponse>>(response.body.string())
        if (result.code != 200 || result.results == null) {
            throw Exception("请求失败: ${result.message.ifEmpty { "未知错误" }}")
        }

        val chapter = result.results.chapter
        val contents = chapter.contents
        val words = chapter.words

        val upscaleEnabled =
            preferences.getBoolean(UPSCALE_API_ENABLED_PREF, UPSCALE_API_ENABLED_PREF_DEFAULT)
        val apiTemplate = preferences.getString(UPSCALE_API_URL_PREF, UPSCALE_API_URL_DEFAULT)
            ?: UPSCALE_API_URL_DEFAULT

        if (words.isNotEmpty() && contents.size == words.size) {
            return contents.zip(words)
                .map { (content, word) ->
                    word to transformUrl(content.url, upscaleEnabled, apiTemplate)
                }
                .sortedBy { it.first }
                .mapIndexed { index, (_, url) ->
                    Page(index, imageUrl = url, url = url, uri = Uri.parse(url))
                }
        }

        return contents.mapIndexed { index, content ->
            val url = transformUrl(content.url, upscaleEnabled, apiTemplate)
            Page(index, imageUrl = url, url = url, uri = Uri.parse(url))
        }
    }

    private fun transformUrl(
        originalUrl: String,
        upscaleEnabled: Boolean,
        apiTemplate: String,
    ): String {
        return if (upscaleEnabled && apiTemplate.contains("{url}")) {
            apiTemplate.replace(
                "{url}",
                originalUrl.replace(Regex("\\.c\\d+x\\.jpg$"), ".c1500x.webp"),
            )
        } else {
            originalUrl.replace(Regex("\\.c\\d+x\\.jpg$"), ".c1500x.webp")
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    // Filters
    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("提示：使用搜索功能获取更精确的结果"),
            Filter.Separator(),
            Filter.Header("排序方式"),
            SortFilter(),
        )
    }

    private class SortFilter : Filter.Select<String>(
        "排序方式",
        arrayOf(
            "最新更新",
            "人气最高",
            "评分最高",
        ),
    )

    // Preferences
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val mainSiteRatePermitsPreference =
            androidx.preference.ListPreference(screen.context).apply {
                key = MAINSITE_RATEPERMITS_PREF
                title = MAINSITE_RATEPERMITS_PREF_TITLE
                entries = MAINSITE_RATEPERMITS_PREF_ENTRIES_ARRAY
                entryValues = MAINSITE_RATEPERMITS_PREF_ENTRIES_ARRAY
                summary = MAINSITE_RATEPERMITS_PREF_SUMMARY

                setDefaultValue(MAINSITE_RATEPERMITS_PREF_DEFAULT)
                setOnPreferenceChangeListener { _, newValue ->
                    try {
                        preferences.edit().putString(MAINSITE_RATEPERMITS_PREF, newValue as String)
                            .apply()
                        Toast.makeText(screen.context, TOAST_RESTART, Toast.LENGTH_LONG).show()
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            }

        val mainSiteRatePeriodPreference =
            androidx.preference.ListPreference(screen.context).apply {
                key = MAINSITE_RATEPERIOD_PREF
                title = MAINSITE_RATEPERIOD_PREF_TITLE
                entries = MAINSITE_RATEPERIOD_PREF_ENTRIES_ARRAY
                entryValues = MAINSITE_RATEPERIOD_PREF_ENTRIES_ARRAY
                summary = MAINSITE_RATEPERIOD_PREF_SUMMARY

                setDefaultValue(MAINSITE_RATEPERIOD_PREF_DEFAULT)
                setOnPreferenceChangeListener { _, newValue ->
                    try {
                        preferences.edit().putString(MAINSITE_RATEPERIOD_PREF, newValue as String)
                            .apply()
                        Toast.makeText(screen.context, TOAST_RESTART, Toast.LENGTH_LONG).show()
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            }

        val upscaleApiPreference =
            androidx.preference.SwitchPreferenceCompat(screen.context).apply {
                key = UPSCALE_API_ENABLED_PREF
                title = UPSCALE_API_ENABLED_PREF_TITLE
                summary = UPSCALE_API_ENABLED_PREF_SUMMARY
                setDefaultValue(UPSCALE_API_ENABLED_PREF_DEFAULT)
                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(screen.context, TOAST_RESTART, Toast.LENGTH_LONG).show()
                    true
                }
            }
        val upscaleUrlPreference = androidx.preference.EditTextPreference(screen.context).apply {
            key = UPSCALE_API_URL_PREF
            title = UPSCALE_API_URL_TITLE
            summary = UPSCALE_API_URL_SUMMARY
            dialogTitle = UPSCALE_API_URL_TITLE
            setDefaultValue(UPSCALE_API_URL_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                when {
                    newUrl.isEmpty() -> {
                        Toast.makeText(screen.context, "API地址不能为空", Toast.LENGTH_SHORT).show()
                        false
                    }

                    !newUrl.contains("{url}") -> {
                        Toast.makeText(
                            screen.context,
                            "必须包含{url}参数占位符",
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    }

                    else -> {
                        Toast.makeText(screen.context, TOAST_RESTART, Toast.LENGTH_LONG).show()
                        true
                    }
                }
            }
        }
        screen.addPreference(mainSiteRatePermitsPreference)
        screen.addPreference(mainSiteRatePeriodPreference)
        screen.addPreference(upscaleApiPreference)
        screen.addPreference(upscaleUrlPreference)
    }

    // Data Models
    @Serializable
    data class ApiResponse<T>(
        val code: Int,
        val message: String,
        val results: T? = null,
    )

    @Serializable
    data class PaginationData<T>(
        val list: List<T>,
        val total: Int,
        val limit: Int,
        val offset: Int,
    )

    @Serializable
    data class ComicSearchData(
        val name: String,
        val path_word: String,
        val cover: String,
        val author: List<Author>,
        val theme: List<Theme> = emptyList(),
    )

    @Serializable
    data class ComicDetailData(
        val comic: ComicInfo,
        val groups: Map<String, ComicGroup>,
    )

    @Serializable
    data class ComicInfo(
        val name: String,
        val path_word: String,
        val cover: String,
        val author: List<Author>,
        val theme: List<Theme>,
        val brief: String,
        val b_404: Boolean,
        val b_hidden: Boolean,
        val last_chapter: LastChapter? = null,
    )

    @Serializable
    data class Author(
        val name: String,
    )

    @Serializable
    data class Theme(
        val name: String,
    )

    @Serializable
    data class LastChapter(
        val name: String,
    )

    @Serializable
    data class ComicGroup(
        val name: String,
        val path_word: String,
    )

    @Serializable
    data class ChapterData(
        val name: String,
        val index: Int,
        val uuid: String,
        val comic_path_word: String,
        val datetime_created: String,
    )

    @Serializable
    data class ChapterContentResponse(
        val show_app: Boolean,
        val is_lock: Boolean,
        val is_login: Boolean,
        val is_mobile_bind: Boolean,
        val is_vip: Boolean,
        val comic: ChapterComicInfo,
        val chapter: ChapterContentDetails,
        val is_banned: Boolean,
    )

    @Serializable
    data class ChapterComicInfo(
        val name: String,
        val uuid: String,
        val path_word: String,
        val restrict: RestrictInfo,
    )

    @Serializable
    data class RestrictInfo(
        val value: Int,
        val display: String,
    )

    @Serializable
    data class ChapterContentDetails(
        val index: Int,
        val uuid: String,
        val count: Int,
        val ordered: Int,
        val size: Int,
        val name: String,
        val comic_id: String,
        val comic_path_word: String,
        val group_id: String?,
        val group_path_word: String,
        val type: Int,
        val img_type: Int,
        val news: String,
        val datetime_created: String,
        val prev: String?,
        val next: String?,
        val contents: List<ContentUrl>,
        val words: List<Int>,
        val is_long: Boolean,
    )

    @Serializable
    data class ContentUrl(
        val url: String,
    )

    @Serializable
    data class LatestUpdatesResponse(
        val list: List<LatestUpdateItem>,
        val total: Int,
        val limit: Int,
        val offset: Int,
    )

    @Serializable
    data class LatestUpdateItem(
        val name: String, // 章节名
        val datetime_created: String,
        val comic: LatestUpdateComic,
    )

    @Serializable
    data class LatestUpdateComic(
        val name: String,
        val path_word: String,
        val author: List<Author>,
        val theme: List<Theme>,
        val cover: String,
        val popular: Int? = null,
        val last_chapter_name: String? = null,
    )

    @Serializable
    data class PopularMangaResponse(
        val list: List<PopularMangaItem>,
        val total: Int,
        val limit: Int,
        val offset: Int,
    )

    @Serializable
    data class PopularMangaItem(
        val type: Int,
        val comic: PopularComic,
    )

    @Serializable
    data class PopularComic(
        val name: String,
        val path_word: String,
        val author: List<Author>,
        val theme: List<Theme>,
        val cover: String,
        val popular: Int,
        val img_type: Int,
    )

    companion object {
        private const val TOAST_RESTART = "请重新启动Tachiyomi以应用更改"

        private const val MAINSITE_RATEPERMITS_PREF = "mainSiteRatePermitsPreference"
        private const val MAINSITE_RATEPERMITS_PREF_DEFAULT = "6"
        private const val MAINSITE_RATEPERMITS_PREF_TITLE = "请求速率限制"
        private const val MAINSITE_RATEPERMITS_PREF_SUMMARY = "每分钟最大请求数 (需要重启)"
        private val MAINSITE_RATEPERMITS_PREF_ENTRIES_ARRAY =
            (1..10).map { it.toString() }.toTypedArray()

        private const val MAINSITE_RATEPERIOD_PREF = "mainSiteRatePeriodMillisPreference"
        private const val MAINSITE_RATEPERIOD_PREF_DEFAULT = "1000"
        private const val MAINSITE_RATEPERIOD_PREF_TITLE = "请求间隔时间"
        private const val MAINSITE_RATEPERIOD_PREF_SUMMARY = "请求之间的最小间隔(毫秒) (需要重启)"
        private val MAINSITE_RATEPERIOD_PREF_ENTRIES_ARRAY =
            (500..6000 step 500).map { it.toString() }.toTypedArray()

        private const val UPSCALE_API_ENABLED_PREF = "upscaleApiEnabled"
        private const val UPSCALE_API_ENABLED_PREF_DEFAULT = false
        private const val UPSCALE_API_ENABLED_PREF_TITLE = "启用超分辨率"
        private const val UPSCALE_API_ENABLED_PREF_SUMMARY =
            "使用upscale-api提升图片清晰度（实验性功能）"

        private const val UPSCALE_API_URL_PREF = "upscaleApiUrlPreference"
        private const val UPSCALE_API_URL_DEFAULT =
            "https://api.example.com/upscale/?model=2x_MangaJaNai_1500p_V1_ESRGAN_90k&format=webp&url={url}"
        private const val UPSCALE_API_URL_TITLE = "超分辨率API地址"
        private const val UPSCALE_API_URL_SUMMARY =
            "格式: https://api.example.com/upscale/?model=2x_MangaJaNai_1500p_V1_ESRGAN_90k&format=webp&url={url}\n必须包含{url}参数占位符"
    }
}
