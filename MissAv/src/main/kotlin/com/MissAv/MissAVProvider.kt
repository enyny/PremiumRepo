package com.MissAV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element

@OptIn(com.lagradost.cloudstream3.Prerelease::class)
class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws"
    override var name = "MissAV"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/$lang/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "$mainUrl/$lang/release" to "Keluaran Terbaru",
        "$mainUrl/$lang/new" to "Recent Update",
        "$mainUrl/$lang/genres/Wanita%20Menikah/Ibu%20Rumah%20Tangga" to "Wanita Menikah"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document
        val homeItems = ArrayList<SearchResponse>()

        document.select("div.thumbnail").forEach { element ->
            val linkElement = element.selectFirst("a.text-secondary") ?: return@forEach
            val href = linkElement.attr("href")
            val fixedUrl = fixUrl(href)
            val title = linkElement.text().trim()
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            homeItems.add(newMovieSearchResponse(title, fixedUrl, TvType.NSFW) {
                this.posterUrl = posterUrl
            })
        }
        
        return newHomePageResponse(
            HomePageList(name = request.name, list = homeItems, isHorizontalImages = true),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.trim().replace(" ", "-")
        val url = "$mainUrl/$lang/search/$fixedQuery"
        return try {
            val document = app.get(url).document
            val results = ArrayList<SearchResponse>()

            document.select("div.thumbnail").forEach { element ->
                val linkElement = element.selectFirst("a.text-secondary") ?: return@forEach
                val href = linkElement.attr("href")
                val fixedUrl = fixUrl(href)
                val title = linkElement.text().trim()
                val img = element.selectFirst("img")
                val posterUrl = img?.attr("data-src") ?: img?.attr("src")

                results.add(newMovieSearchResponse(title, fixedUrl, TvType.NSFW) {
                    this.posterUrl = posterUrl
                })
            }
            results
        } catch (e: Exception) {
            e.printStackTrace()
            ArrayList()
        }
    }

    // --- FUNGSI LOAD (DETAIL VIDEO + REKOMENDASI CERDAS) ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Ambil Data Utama
        val title = document.selectFirst("h1.text-base")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video.player")?.attr("poster")
        val description = document.select("div.text-secondary")
            .maxByOrNull { it.text().length }?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        // === FITUR SARAN FILM CERDAS ===
        // Mengambil rekomendasi dari halaman Aktris/Genre agar gambar valid
        val recommendations = ArrayList<SearchResponse>()
        
        try {
            // 1. Coba cari Link Aktris dulu (Prioritas Utama)
            var recUrl = document.selectFirst("div.text-secondary a[href*='/actresses/']")?.attr("href")
            
            // 2. Kalau tidak ada Aktris, cari Link Genre atau Pembuat
            if (recUrl == null) {
                recUrl = document.selectFirst("div.text-secondary a[href*='/makers/']")?.attr("href")
            }
            if (recUrl == null) {
                recUrl = document.selectFirst("div.text-secondary a[href*='/genres/']")?.attr("href")
            }

            // 3. Jika ketemu link referensi, buka halamannya dan ambil daftar videonya
            if (recUrl != null) {
                val fixedRecUrl = fixUrl(recUrl)
                val recDoc = app.get(fixedRecUrl).document
                
                recDoc.select("div.thumbnail").forEach { element ->
                    val linkElement = element.selectFirst("a.text-secondary") ?: return@forEach
                    val href = linkElement.attr("href")
                    val fixedVideoUrl = fixUrl(href)

                    // Jangan masukkan video yang sedang ditonton ke daftar saran
                    if (fixedVideoUrl != url) {
                        val recTitle = linkElement.text().trim()
                        val img = element.selectFirst("img")
                        val recPoster = img?.attr("data-src") ?: img?.attr("src")

                        // Pastikan poster ada agar tidak muncul kotak abu-abu
                        if (!recPoster.isNullOrEmpty()) {
                            recommendations.add(
                                newMovieSearchResponse(recTitle, fixedVideoUrl, TvType.NSFW) {
                                    this.posterUrl = recPoster
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    // --- LOGIKA SUBTITLE AKURAT (STRICT FILTER) ---
    private suspend fun fetchSubtitleCat(code: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val searchUrl = "https://www.subtitlecat.com/index.php?search=$code"
            val searchDoc = app.get(searchUrl).document
            
            val searchResults = searchDoc.select("table.sub-table tbody tr td:nth-child(1) > a")
            
            // Ambil 15 hasil teratas untuk dicek
            searchResults.take(15).forEach { linkElement ->
                val resultTitle = linkElement.text().trim()
                
                // FILTER: Judul subtitle WAJIB mengandung Kode Video (misal: SSNI-528)
                if (resultTitle.contains(code, ignoreCase = true)) {
                    var detailPath = linkElement.attr("href")
                    if (!detailPath.startsWith("http")) {
                        detailPath = if (detailPath.startsWith("/")) detailPath else "/$detailPath"
                        detailPath = "https://www.subtitlecat.com$detailPath"
                    }

                    try {
                        val detailDoc = app.get(detailPath).document
                        
                        detailDoc.select("div.sub-single").forEach { item ->
                            val rawLang = item.select("span").getOrNull(1)?.text()?.trim() ?: "Unknown"
                            val downloadEl = item.selectFirst("a.green-link")
                            val downloadHref = downloadEl?.attr("href")

                            if (downloadHref != null) {
                                val finalUrl = if (downloadHref.startsWith("http")) {
                                    downloadHref
                                } else {
                                    "https://www.subtitlecat.com$downloadHref"
                                }
                                
                                subtitleCallback.invoke(
                                    SubtitleFile(
                                        lang = rawLang, // Biarkan nama asli agar player melakukan grouping
                                        url = finalUrl
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Skip error per item
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION_ERROR") 
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        var text = app.get(data).text
        text = getAndUnpack(text) 

        val m3u8Regex = Regex("""(https?:\\?\/\\?\/[^"']+\.m3u8)""")
        val matches = m3u8Regex.findAll(text)
        
        val uniqueUrls = matches.map { 
            it.groupValues[1].replace("\\/", "/") 
        }.toSet()

        // Filter nama sumber agar tidak ganda di UI
        val addedNames = mutableListOf<String>()

        if (uniqueUrls.isNotEmpty()) {
            uniqueUrls.forEach { fixedUrl ->
                val sourceName = if (fixedUrl.contains("surrit")) "Surrit" else "MissAV"
                
                if (!addedNames.contains(sourceName)) {
                    addedNames.add(sourceName)
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = sourceName,
                            url = fixedUrl,
                            referer = data,
                            quality = Qualities.Unknown.value, // Unknown = Auto (Player baca track dari m3u8)
                            isM3u8 = true
                        )
                    )
                }
            }

            // --- PROSES KODE ID & SUBTITLE ---
            val codeRegex = Regex("""([a-zA-Z]{2,5}-\d{3,5})""")
            val codeMatch = codeRegex.find(data)
            val code = codeMatch?.value
            
            if (code != null) {
                try {
                    fetchSubtitleCat(code, subtitleCallback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return true
        }
        return false
    }
}
