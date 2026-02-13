package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

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

        val document = app.get(url).document
        
        val items = document.select("article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 > a") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href")
        
        // PENTING: Handle Lazy Load WP Fastest Cache (data-wpfc-original-src)
        val imgElement = element.selectFirst("div.poster img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        val duration = element.selectFirst("span.duration")?.text()?.trim()

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            addDuration(duration)
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.s-title")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: "Unknown"

        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst("div.poster img")
        val poster = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        val tags = document.select(".sgeneros a").map { it.text() }
        
        val year = document.selectFirst("span.date")?.text()?.takeLast(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    // ==============================
    // 4. LOAD LINKS (PLAYER)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Cek Iframe Langsung (Standard)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. Cek Dooplay Ajax Player (Multi-server options)
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        
        document.select("#playeroptions ul li").forEach { li ->
            val id = li.attr("data-post")
            val type = li.attr("data-type")
            val nume = li.attr("data-nume")
            
            if (id.isNotEmpty()) {
                try {
                    val response = app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to id,
                            "nume" to nume,
                            "type" to type
                        ),
                        referer = data
                    ).parsedSafe<DooplayResponse>()
                    
                    val embedUrl = response?.embed_url ?: response?.content
                    if (!embedUrl.isNullOrEmpty()) {
                        val cleanUrl = if (embedUrl.contains("iframe")) {
                            embedUrl.substringAfter("src=\"").substringBefore("\"")
                        } else {
                            embedUrl
                        }
                        loadExtractor(cleanUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }

        return true
    }
    
    data class DooplayResponse(
        val embed_url: String? = null,
        val type: String? = null,
        val content: String? = null
    )
}
