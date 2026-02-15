package com.CeweCantik

import com.lagradost.cloudstream3.*
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

    // Header Browser Asli (Penting untuk melewati proteksi)
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest", // Kadang dibutuhkan
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\""
    )

    // ==========================================
    // 1. HOME (HTML PARSING)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url, headers = commonHeaders).document
        
        // Selector HTML berdasarkan struktur website
        val home = document.select("div.product__sidebar__view__item, .product__item").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        // Cek struktur elemen, bisa jadi ada di parent <a> atau di dalam div
        val linkElement = element.selectFirst("a") ?: element.parent() as? Element ?: return null
        val title = linkElement.selectFirst("h5")?.text()?.trim() ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Ambil gambar dari style="background-image: url(...)" atau data-setbg
        var posterUrl = element.attr("data-setbg")
        if (posterUrl.isNullOrEmpty()) {
            val style = element.attr("style")
            if (style.contains("url(")) {
                posterUrl = style.substringAfter("url(").substringBefore(")").replace("\"", "").replace("'", "")
            }
        }
        
        // Fallback ambil img tag
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = element.selectFirst("img")?.attr("src")
        }

        // Kualitas/Episode info
        val epText = element.selectFirst(".ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (!epText.isNullOrEmpty()) {
                addQuality(epText)
            }
        }
    }

    // ==========================================
    // 2. SEARCH (HTML PARSING)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("div.product__sidebar__view__item, .product__item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==========================================
    // 3. LOAD (DETAIL ANIME)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        // Trik: Jika URL mengarah ke episode (misal .../episode/6), kita ambil root anime-nya dulu untuk info,
        // TAPI kita pakai URL episode untuk parsing list episode (karena halaman detail anime sering kosong).
        
        val document = app.get(url, headers = commonHeaders).document

        // Ambil Metadata
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(Regex("\\(Episode.*\\)"), "")
            ?.replace("Subtitle Indonesia", "")
            ?.replace("- Kuramanime", "")
            ?.trim() ?: "Unknown Title"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // Ambil Episode
        // Selector update berdasarkan analisis HTML
        // #animeEpisodes a -> link episode
        val episodes = document.select("#animeEpisodes a").mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epName = ep.text().trim() // "Ep 1", "Ep 2"
            
            // Regex untuk ambil nomor episode
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
            
            // Tambahkan Score jika ada (convert manual karena class Score mungkin error di beberapa versi)
            // if (scoreText != null) addScore(scoreText) 

            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ==========================================
    // 4. LOAD LINKS (VIDEO PLAYER)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val document = app.get(data, headers = commonHeaders).document
        
        // 1. Cek Dropdown Server (Paling umum)
        val serverOptions = document.select("select#changeServer option")
        
        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverName = option.text()
                val serverValue = option.attr("value")
                
                // Skip server VIP/Premium
                if (serverName.contains("vip", true)) return@forEach

                // Construct URL server
                val serverUrl = "$data?server=$serverValue"
                
                try {
                    // Request ke URL server dengan header lengkap
                    val doc = app.get(serverUrl, headers = commonHeaders).document
                    val iframe = doc.select("iframe").attr("src")
                    
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // ignore error per server
                }
            }
        } else {
            // 2. Fallback: Cari Iframe langsung (jika tidak ada dropdown)
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
