package com.CeweCantik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val commonUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private fun getHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to referer,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Sec-Ch-Ua-Mobile" to "?1",
            "Sec-Ch-Ua-Platform" to "\"Android\""
        )
    }

    private fun Element.getImageUrl(): String? {
        return this.attr("data-setbg").ifEmpty { this.attr("src") }
    }

    // ==============================
    // 1. MAIN PAGE
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = mapOf("User-Agent" to commonUserAgent)).document
        val homePageList = ArrayList<HomePageList>()

        val ongoing = document.select("div.product__sidebar__view__item").mapNotNull {
            val title = it.selectFirst("h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("h5 a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__sidebar__view__item__pic")?.getImageUrl()
            val epText = it.selectFirst(".ep")?.text()?.trim()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
                addQuality(epText ?: "")
            }
        }

        val latest = document.select("div.product__item").mapNotNull {
            val title = it.selectFirst(".product__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__item__pic")?.getImageUrl()
            val epText = it.selectFirst(".ep")?.text() ?: ""
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
                addQuality(epText)
            }
        }

        if (ongoing.isNotEmpty()) homePageList.add(HomePageList("Sedang Tayang", ongoing))
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Terbaru", latest))
        
        return newHomePageResponse(homePageList)
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = mapOf("User-Agent" to commonUserAgent)).document

        return document.select("div.product__item").mapNotNull {
            val title = it.selectFirst(".product__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__item__pic")?.getImageUrl()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
            }
        }
    }

    // ==============================
    // 3. LOAD DETAILS
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to commonUserAgent)).document

        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".anime__details__pic")?.getImageUrl()
        val synopsis = document.selectFirst(".anime__details__text p")?.text()?.trim()

        var type = TvType.Anime
        var status = ShowStatus.Ongoing
        var year: Int? = null

        document.select(".anime__details__widget ul li").forEach { li ->
            val text = li.text()
            if (text.contains("Movie", true)) type = TvType.AnimeMovie
            if (text.contains("Selesai", true)) status = ShowStatus.Completed
            if (text.contains("Musim", true)) {
                Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { year = it }
            }
        }

        val episodes = document.select("#episodeLists a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.text().trim()
            val epNum = Regex("Episode\\s+(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = synopsis
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ==============================
    // 4. LOAD LINKS (RAHASIA DI SINI)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Ambil halaman episode biasa untuk dapatkan CSRF Token
        val response = app.get(data, headers = mapOf("User-Agent" to commonUserAgent))
        val document = response.document
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        // 2. Ekstrak ID Anime dan Episode dari URL
        // Format URL: https://v8.kuramanime.blog/anime/{ID}/slug/episode/{EP}
        // Target API: https://v8.kuramanime.blog/anime/{ID}/episode/{EP}/check-episode
        val urlRegex = Regex("""/anime/(\d+)/[^/]+/episode/(\d+)""")
        val match = urlRegex.find(data)

        if (match != null && csrfToken != null) {
            val (animeId, episodeNum) = match.destructured
            val apiUrl = "$mainUrl/anime/$animeId/episode/$episodeNum/check-episode"

            // 3. Panggil API Tersembunyi
            try {
                val apiHeaders = getHeaders(data).toMutableMap()
                apiHeaders["X-CSRF-TOKEN"] = csrfToken

                val apiResponse = app.get(apiUrl, headers = apiHeaders).parsedSafe<KuramaApiResponse>()
                
                // 4. Jika dapat URL dari API (biasanya sunrong.my.id), proses
                if (apiResponse?.url != null && apiResponse.url.isNotEmpty()) {
                    var serverUrl = apiResponse.url
                    if (serverUrl.startsWith("//")) serverUrl = "https:$serverUrl"

                    // Jika linknya adalah wrapper (sunrong), loadextractor akan otomatis handle jika di-support
                    // atau kita load lagi untuk cari iframe dalamnya
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Fallback jika API gagal
            }
        }

        // 5. Fallback: Scan manual iframe/script jika API gagal (Backup Plan)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("chat")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }

    // Data class untuk parsing JSON
    data class KuramaApiResponse(
        val url: String? = null,
        val message: String? = null
    )
}
