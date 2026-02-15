package com.CeweCantik

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Header Browser Asli (Penting untuk video)
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\""
    )

    // ==========================================
    // JSON DATA CLASSES
    // ==========================================
    data class KuramaResponse(
        @JsonProperty("data") val data: List<KuramaAnime>? = null,
        @JsonProperty("ongoingAnimes") val ongoingAnimes: KuramaPage? = null,
        @JsonProperty("finishedAnimes") val finishedAnimes: KuramaPage? = null,
        @JsonProperty("movieAnimes") val movieAnimes: KuramaPage? = null,
    )

    data class KuramaPage(
        @JsonProperty("data") val data: List<KuramaAnime>? = null
    )

    data class KuramaAnime(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("image_portrait_url") val imagePortraitUrl: String? = null,
        @JsonProperty("image_landscape_url") val imageLandscapeUrl: String? = null,
        @JsonProperty("posts") val posts: List<KuramaPost>? = null, // INI KUNCINYA!
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class KuramaPost(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("anime_id") val animeId: Int? = null
    )

    // ==========================================
    // 1. HOME (JSON API)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page + "&need_json=true"
        val response = app.get(url, headers = commonHeaders).text
        
        // Kita parse responsenya
        val json = parseJson<KuramaResponse>(response)
        
        // Gabungkan semua kemungkinan lokasi data
        val animeList = json.data 
            ?: json.ongoingAnimes?.data 
            ?: json.finishedAnimes?.data 
            ?: json.movieAnimes?.data 
            ?: emptyList()

        val home = animeList.mapNotNull { anime ->
            toSearchResult(anime)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(anime: KuramaAnime): SearchResponse? {
        val title = anime.title ?: return null
        val id = anime.id ?: return null
        
        // TRIK RAHASIA:
        // Daripada memasukkan URL website, kita masukkan DATA JSON LENGKAP ke dalam variabel URL.
        // Nanti di fungsi load(), kita baca data ini.
        val dataUrl = toJson(anime)

        val poster = anime.imagePortraitUrl ?: anime.imageLandscapeUrl

        return newAnimeSearchResponse(title, dataUrl, TvType.Anime) {
            this.posterUrl = poster
            if (anime.score != null) {
                // Tampilkan rating di cover depan
                addQuality("⭐ ${anime.score}")
            }
        }
    }

    // ==========================================
    // 2. SEARCH (JSON API)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest&need_json=true"
        val response = app.get(url, headers = commonHeaders).text
        val json = parseJson<KuramaResponse>(response)
        
        return json.data?.mapNotNull { toSearchResult(it) } ?: emptyList()
    }

    // ==========================================
    // 3. LOAD (DATA PASSING)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        // Cek apakah 'url' adalah JSON (diawali kurung kurawal)
        if (url.trim().startsWith("{")) {
            // YES! Ini data dari Home/Search. Kita tidak perlu request internet!
            val anime = parseJson<KuramaAnime>(url)
            
            val title = anime.title ?: "Unknown"
            val poster = anime.imagePortraitUrl ?: anime.imageLandscapeUrl
            val synopsis = anime.synopsis ?: ""
            val slug = anime.slug ?: ""
            val id = anime.id ?: 0

            // Ambil episode langsung dari JSON
            val episodes = anime.posts?.mapNotNull { post ->
                val epNum = post.episode?.toString()?.toIntOrNull()
                val epTitle = post.title ?: "Episode $epNum"
                
                // Buat URL Nonton Manual: .../episode/{num}
                val epUrl = "$mainUrl/anime/$id/$slug/episode/$epNum"

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }?.reversed() ?: emptyList()

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                // Set rating
                if (anime.score != null) {
                    this.score = Score.from(anime.score, 10)
                }
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
        } else {
            // FALLBACK: Kalau URL-nya link biasa (misal dari share link), kita scraping HTML.
            // Trik: Kita arahkan ke /episode/1 karena halaman detail biasa KOSONG (hasil temuan termux).
            val fixUrl = if (url.contains("/episode/")) url else "$url/episode/1"
            
            val document = app.get(fixUrl, headers = commonHeaders).document
            
            val title = document.select("meta[property=og:title]").attr("content")
                .replace(Regex("\\(Episode.*\\)"), "")
                .replace("Subtitle Indonesia - Kuramanime", "").trim()
            val poster = document.select("meta[property=og:image]").attr("content")
            val description = document.select("meta[name=description]").attr("content")

            val episodes = document.select("#animeEpisodes a").mapNotNull { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epName = ep.text().trim()
                val epNum = Regex("\\d+").find(epName)?.value?.toIntOrNull()

                if (epUrl.contains("/episode/")) {
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                } else null
            }.reversed()

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ==========================================
    // 4. LOAD LINKS (VIDEO)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val ajaxHeaders = commonHeaders + mapOf(
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        val document = app.get(data, headers = commonHeaders).document
        val serverOptions = document.select("select#changeServer option")

        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverValue = option.attr("value")
                val serverName = option.text()
                if (serverName.contains("vip", true)) return@forEach

                val serverUrl = "$data?server=$serverValue"
                try {
                    val doc = app.get(serverUrl, headers = ajaxHeaders).document
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        } else {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
