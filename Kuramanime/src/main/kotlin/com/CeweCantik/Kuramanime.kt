package com.CeweCantik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

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

    // User-Agent Desktop (Windows) agar server memberikan HTML lengkap
    private val commonUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun getHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Cookie" to "preferred_stserver=filemoon; should_do_galak=hide" // Cookie sakti dari log kamu
        )
    }

    private fun Element.getImageUrl(): String? {
        return this.attr("data-setbg").ifEmpty { this.attr("src") }
    }

    // ==============================
    // 1. MAIN PAGE (Strategi Baru)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = ArrayList<HomePageList>()

        // Mengambil data dari halaman "Ongoing" (Lebih stabil daripada Home)
        val ongoingUrl = "$mainUrl/quick/ongoing?order_by=latest"
        val ongoingDoc = app.get(ongoingUrl, headers = getHeaders()).document
        val ongoingList = ongoingDoc.select("div.product__item").mapNotNull {
            it.toSearchResponse()
        }

        // Mengambil data dari halaman "Terbaru/Updated"
        val latestUrl = "$mainUrl/anime?order_by=updated"
        val latestDoc = app.get(latestUrl, headers = getHeaders()).document
        val latestList = latestDoc.select("div.product__item").mapNotNull {
            it.toSearchResponse()
        }

        if (ongoingList.isNotEmpty()) {
            homePageList.add(HomePageList("Sedang Tayang", ongoingList))
        }
        
        if (latestList.isNotEmpty()) {
            homePageList.add(HomePageList("Baru Diupdate", latestList))
        }

        if (homePageList.isEmpty()) {
            throw ErrorLoadingException("Gagal memuat. Coba refresh atau cek koneksi.")
        }

        return newHomePageResponse(homePageList)
    }

    // Helper untuk mengubah Element HTML menjadi SearchResponse
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".product__item__text h5 a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href")
        val image = this.selectFirst(".product__item__pic")?.getImageUrl()
        
        // Coba ambil info episode
        val epText = this.selectFirst(".ep")?.text()?.trim() ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = image
            addQuality(epText)
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = getHeaders()).document

        return document.select("div.product__item").mapNotNull {
            it.toSearchResponse()
        }
    }

    // ==============================
    // 3. LOAD DETAILS
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders("$mainUrl/anime")).document

        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".anime__details__pic")?.getImageUrl()
        val synopsis = document.selectFirst(".anime__details__text p")?.text()?.trim()

        var type = TvType.Anime
        var status = ShowStatus.Ongoing
        var year: Int? = null

        document.select(".anime__details__widget ul li").forEach { li ->
            val text = li.text()
            if (text.contains("Tipe", true) && text.contains("Movie", true)) type = TvType.AnimeMovie
            if (text.contains("Status", true) && (text.contains("Selesai", true) || text.contains("Finished", true))) status = ShowStatus.Completed
            if (text.contains("Musim", true)) {
                Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { year = it }
            }
        }

        // Selector episode yang lebih luas (menangani ID #episodeLists dan class .anime__details__episodes)
        val episodes = document.select("#episodeLists a, .anime__details__episodes a").mapNotNull {
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
    // 4. LOAD LINKS (Metode API Check-Episode)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ambil halaman untuk CSRF token
        val response = app.get(data, headers = getHeaders())
        val document = response.document
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")

        val urlRegex = Regex("""/anime/(\d+)/[^/]+/episode/(\d+)""")
        val match = urlRegex.find(data)

        if (match != null && csrfToken != null) {
            val (animeId, episodeNum) = match.destructured
            // URL API Rahasia yang ditemukan dari log
            val apiUrl = "$mainUrl/anime/$animeId/episode/$episodeNum/check-episode"

            try {
                val apiHeaders = getHeaders(data).toMutableMap()
                apiHeaders["X-CSRF-TOKEN"] = csrfToken
                apiHeaders["X-Requested-With"] = "XMLHttpRequest"
                apiHeaders["Accept"] = "application/json, text/javascript, */*; q=0.01"

                val apiResponse = app.get(apiUrl, headers = apiHeaders).parsedSafe<KuramaApiResponse>()
                
                if (apiResponse?.url != null && apiResponse.url.isNotEmpty()) {
                    var serverUrl = apiResponse.url
                    if (serverUrl.startsWith("//")) serverUrl = "https:$serverUrl"
                    
                    // Prioritas: Load link dari API (Filemoon/Sunrong)
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Ignore API fail
            }
        }

        // Fallback: Scan Iframe biasa (jika API gagal)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("chat") && !src.contains("disqus")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }

    data class KuramaApiResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("message") val message: String? = null
    )
}
