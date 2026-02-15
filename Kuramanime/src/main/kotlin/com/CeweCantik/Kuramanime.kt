package com.CeweCantik

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@CloudstreamPlugin
class KuramanimeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KuramanimeProvider())
        registerExtractorAPI(Nyomo())
        registerExtractorAPI(Streamhide())
        registerExtractorAPI(Kuramadrive())
        registerExtractorAPI(Lbx())
        registerExtractorAPI(Sunrong())
    }
}

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v5.kuramanime.blog"
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    var authorization: String? = "KFhElffuFYZZHAqqBqlGewkwbaaFUtJS"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private var cookies: Map<String, String> = mapOf()

        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/properties/season/summer-2022?order_by=most_viewed&page=" to "Dilihat Terbanyak Musim Ini",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/anime?search=$query&order_by=latest"
        ).document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags =
            document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
                .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()

        for (i in 1..30) {
            val doc = app.get("$url?page=$i").document
            val eps = Jsoup.parse(doc.select("#episodeLists").attr("data-content"))
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)
                        ?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episode }
                }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()
                ?.lowercase() ?: "tv", episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }

    }

    private suspend fun invokeLocalSource(
        url: String,
        server: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.post(
            url,
            data = mapOf("authorization" to getAuth()),
            headers = headers,
            cookies = cookies
        ).document
        document.select("video#player > source").map {
            val link = fixUrl(it.attr("src"))
            val quality = it.attr("size").toIntOrNull()
            callback.invoke(
                newExtractorLink(
                    fixTitle(server),
                    fixTitle(server),
                    link,
                    INFER_TYPE
                ) {
                    this.headers = mapOf(
                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                        "Range" to "bytes=0-",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "no-cors",
                    )
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
        }
        if (server == "kuramadrive") {
            document.select("div#animeDownloadLink a").amap {
                loadExtractor(it.attr("href"), "$mainUrl/", subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val req = app.get(data)
        val res = req.document
        cookies = req.cookies

        val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false
        val dataKps = res.selectFirst("div.col-lg-12.mt-3")?.attr("data-kk") ?: return false

        val assets = getAssets(dataKps)

        var headers = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
            "X-Request-ID" to randomId(),
            "X-Request-Index" to "0",
            "X-Requested-With" to "XMLHttpRequest",
        )

        val tokenKey = app.get(
            "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}",
            headers = headers,
            cookies = cookies
        ).text

        headers = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Requested-With" to "XMLHttpRequest",
        )

        res.select("select#changeServer option").amap { source ->
            val server = source.attr("value")
            val link =
                "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server"
            if (server.contains(Regex("(?i)kuramadrive|archive"))) {
                invokeLocalSource(link, server, headers, subtitleCallback, callback)
            } else {
                app.post(
                    link,
                    data = mapOf("authorization" to getAuth()),
                    referer = data,
                    headers = headers,
                    cookies = cookies
                ).document.select("div.iframe-container iframe").attr("src").let { videoUrl ->
                    loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                }
            }
        }


        return true
    }

    private suspend fun getAssets(bpjs: String?): Assets {
        val env = app.get("$mainUrl/assets/js/$bpjs.js").text
        val MIX_PREFIX_AUTH_ROUTE_PARAM =
            env.substringAfter("MIX_PREFIX_AUTH_ROUTE_PARAM: '").substringBefore("',")
        val MIX_AUTH_ROUTE_PARAM =
            env.substringAfter("MIX_AUTH_ROUTE_PARAM: '").substringBefore("',")
        val MIX_AUTH_KEY = env.substringAfter("MIX_AUTH_KEY: '").substringBefore("',")
        val MIX_AUTH_TOKEN = env.substringAfter("MIX_AUTH_TOKEN: '").substringBefore("',")
        val MIX_PAGE_TOKEN_KEY = env.substringAfter("MIX_PAGE_TOKEN_KEY: '").substringBefore("',")
        val MIX_STREAM_SERVER_KEY =
            env.substringAfter("MIX_STREAM_SERVER_KEY: '").substringBefore("',")
        return Assets(
            MIX_PREFIX_AUTH_ROUTE_PARAM,
            MIX_AUTH_ROUTE_PARAM,
            MIX_AUTH_KEY,
            MIX_AUTH_TOKEN,
            MIX_PAGE_TOKEN_KEY,
            MIX_STREAM_SERVER_KEY
        )
    }

    suspend fun getAuth(): String {
        return authorization ?: fetchAuth().also { authorization = it }
    }

    suspend fun fetchAuth(): String {
        val url = "$mainUrl/storage/leviathan.js?v=512"
        val res = app.get(url).text
        val auth = Regex("""=\s*\[(.*?)]""").find(res)?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            ?: throw ErrorLoadingException()

        return "${auth.last()}${auth[9]}${auth[1]}${auth.first()}i"
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String?,
        val MIX_AUTH_ROUTE_PARAM: String?,
        val MIX_AUTH_KEY: String?,
        val MIX_AUTH_TOKEN: String?,
        val MIX_PAGE_TOKEN_KEY: String?,
        val MIX_STREAM_SERVER_KEY: String?,
    )

}

// --- Extractors ---

class Sunrong : FilemoonV2() {
    override var mainUrl = "https://sunrong.my.id"
    override var name = "Sunrong"
}

class Nyomo : StreamSB() {
    override var name: String = "Nyomo"
    override var mainUrl = "https://nyomo.my.id"
}

class Streamhide : Filesim() {
    override var name: String = "Streamhide"
    override var mainUrl: String = "https://streamhide.to"
}

open class Lbx : ExtractorApi() {
    override val name = "Linkbox"
    override val mainUrl = "https://lbx.to"
    private val realUrl = "https://www.linkbox.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val token = Regex("""(?:/f/|/file/|\?id=)(\w+)""").find(url)?.groupValues?.get(1)
        val id =
            app.get("$realUrl/api/file/share_out_list/?sortField=utime&sortAsc=0&pageNo=1&pageSize=50&shareToken=$token")
                .parsedSafe<Responses>()?.data?.itemId
        app.get("$realUrl/api/file/detail?itemId=$id", referer = url)
            .parsedSafe<Responses>()?.data?.itemInfo?.resolutionList?.map { link ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        link.url ?: return@map null,
                        INFER_TYPE
                    ) {
                        this.referer = "$realUrl/"
                        this.quality = getQualityFromName(link.resolution)
                    }
                )
            }
    }

    data class Resolutions(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
    )

    data class ItemInfo(
        @JsonProperty("resolutionList") val resolutionList: ArrayList<Resolutions>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("itemInfo") val itemInfo: ItemInfo? = null,
        @JsonProperty("itemId") val itemId: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: Data? = null,
    )

}

open class Kuramadrive : ExtractorApi() {
    override val name = "DriveKurama"
    override val mainUrl = "https://kuramadrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val req = app.get(url, referer = referer)
        val doc = req.document

        val title = doc.select("title").text()
        val token = doc.select("meta[name=csrf-token]").attr("content")
        val routeCheckAvl = doc.select("input#routeCheckAvl").attr("value")

        val json = app.get(
            routeCheckAvl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to token
            ),
            referer = url,
            cookies = req.cookies
        ).parsedSafe<Source>()

        callback.invoke(
            newExtractorLink(
                name,
                name,
                json?.url ?: return,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
                this.quality = getIndexQuality(title)
            }
        )
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private data class Source(
        @JsonProperty("url") val url: String,
    )

}
