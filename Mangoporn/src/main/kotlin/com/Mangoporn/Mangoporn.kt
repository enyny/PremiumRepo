package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.*

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // HEADERS PENTING:
    // Ini disamakan dengan hasil CURL kamu agar server tidak memblokir akses kita.
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    )

    // ==============================
    // 1. MAIN PAGE CONFIGURATION
    // ==============================
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Recent Movies",
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/ratings/" to "Top Rated",
        "$mainUrl/genres/porn-movies/" to "Porn Movies",
        "$mainUrl/xxxclips/" to "XXX Clips"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        // Menggunakan headers saat request
        val document = app.get(url, headers = headers).document
        
        val items = document.select("article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        // LOGIKA PINTAR:
        // 1. Cek 'h3 > a' (Struktur halaman Home/Article)
        // 2. Cek 'div.title > a' (Struktur halaman Search/Result Item)
        // 3. Cek 'div.image > a' (Cadangan jika judul tidak ada teks)
        val titleElement = element.selectFirst("h3 > a") 
            ?: element.selectFirst("div.title > a")
            ?: element.selectFirst("div.image > a")
            ?: return null

        val title = titleElement.text().trim()
        val url = fixUrl(titleElement.attr("href"))
        
        // Ambil poster dengan menangani lazy load (WP Fastest Cache)
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. SEARCH (FIXED BERDASARKAN HASIL TERMUX)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$fixedQuery"
        
        // Request dengan Headers lengkap
        val document = app.get(url, headers = headers).document
        
        // FIX: Mengambil 'div.result-item' karena di Termux ditemukan 36 item di sini.
        // Kita juga tetap menyertakan 'article.item' sebagai cadangan.
        return document.select("div.result-item, article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst("div.poster img")
        val poster = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        val tags = document.select(".sgeneros a, .persons a[href*='/genre/']").map { it.text() }
        
        val year = document.selectFirst(".textco a[href*='/year/']")?.text()?.toIntOrNull()
        
        val actors = document.select("#cast .persons a[href*='/pornstar/']").map { 
            ActorData(Actor(it.text(), null)) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS (PARALLEL LOADING)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        val potentialLinks = mutableListOf<String>()

        document.select("#playeroptionsul li a").forEach { link ->
            val href = fixUrl(link.attr("href"))
            if (href.startsWith("http")) potentialLinks.add(href)
        }

        document.select("#playcontainer iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.startsWith("http")) potentialLinks.add(src)
        }

        fun getServerPriority(url: String): Int {
            return when {
                url.contains("dood") -> 0
                url.contains("streamtape") -> 1
                url.contains("voe.sx") -> 2
                url.contains("vidhide") -> 5
                url.contains("filemoon") -> 6
                url.contains("mixdrop") -> 10
                url.contains("streamsb") -> 11
                else -> 20
            }
        }

        if (potentialLinks.isNotEmpty()) {
            val sortedLinks = potentialLinks.sortedBy { getServerPriority(it) }

            // Parallel loading agar cepat
            coroutineScope {
                sortedLinks.map { link ->
                    launch(Dispatchers.IO) {
                        try {
                            loadExtractor(link, data, subtitleCallback, callback)
                        } catch (e: Exception) {
                            // Ignore error per link
                        }
                    }
                }
            }
            return true
        }

        return false
    }
}
