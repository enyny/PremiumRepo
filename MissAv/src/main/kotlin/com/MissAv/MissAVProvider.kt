package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws/id"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ==============================
    // 1. KONFIGURASI KATEGORI
    // ==============================
    override val mainPage = mainPageOf(
        "https://missav.ws/dm628/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "https://missav.ws/dm590/id/release" to "Keluaran Terbaru",
        "https://missav.ws/dm515/id/new" to "Recent Update",
        "https://missav.ws/dm68/id/genres/Wanita%20Menikah/Ibu%20Rumah%20Tangga" to "Wanita menikah"
    )

    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    // ==============================
    // 2. HOME PAGE
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(url).document
        
        val items = document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // Helper: Mengubah elemen HTML video menjadi data SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()
        val imgElement = element.selectFirst("img")
        
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 3. PENCARIAN
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==============================
    // 4. DETAIL VIDEO
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: "Unknown Title"

        val description = document.selectFirst("div.text-secondary.break-all")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select("div.text-secondary a[href*='/genres/']").map { it.text() }
        
        val actors = document.select("div.text-secondary a[href*='/actresses/'], div.text-secondary a[href*='/actors/']")
            .map { element ->
                ActorData(Actor(element.text(), null))
            }

        val year = document.selectFirst("time")?.text()?.trim()?.take(4)?.toIntOrNull()

        val durationSeconds = document.selectFirst("meta[property=og:video:duration]")
            ?.attr("content")?.toIntOrNull()
        val durationMinutes = durationSeconds?.div(60)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.year = year
            this.duration = durationMinutes
        }
    }

    // ==============================
    // 5. PLAYER + SUBTITLE (FIXED)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val text = document.html()
        
        val regex = """nineyu\.com\\/([0-9a-fA-F-]+)\\/seek""".toRegex()
        val match = regex.find(text)
        
        if (match != null) {
            val uuid = match.groupValues[1]
            val videoUrl = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = "MissAV",
                    name = "MissAV (Surrit)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )

            // --- LOGIKA PENCARIAN SUBTITLE DIPERBAIKI ---
            val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() ?: ""
            
            // Regex mencari Kode (Misal: JUR-613, SSIS-992, IPX-123)
            // Pola: Huruf 2-5 digit, tanda strip, Angka 3-5 digit
            val codeRegex = """([A-Za-z]{2,5}-[0-9]{3,5})""".toRegex()
            val codeMatch = codeRegex.find(title)
            val code = codeMatch?.value
            
            // Prioritaskan cari pakai Kode. Kalau tidak ada kode, pakai Judul full (walau jarang akurat)
            val query = code ?: title
            
            if (query.isNotBlank()) {
                fetchSubtitleCat(query, subtitleCallback)
            }
            
            return true
        }

        return false
    }

    // ==============================
    // 6. HELPER SUBTITLE (LOGIKA BARU 2 LANGKAH)
    // ==============================
    private suspend fun fetchSubtitleCat(query: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // LANGKAH 1: Buka Halaman Pencarian
            val searchUrl = "https://www.subtitlecat.com/index.php?search=$query"
            val searchDoc = app.get(searchUrl).document

            // Ambil link hasil pertama dari tabel (table.sub-table)
            // Selector ini mengambil href dari elemen <a> di kolom pertama
            val firstResultLink = searchDoc.selectFirst("table.sub-table tbody tr td a")?.attr("href")

            if (!firstResultLink.isNullOrEmpty()) {
                // Perbaiki URL karena link dari website bersifat relative (subs/...)
                val detailPageUrl = if (firstResultLink.startsWith("http")) firstResultLink else "https://www.subtitlecat.com/$firstResultLink"

                // LANGKAH 2: Buka Halaman Detail Subtitle
                val detailDoc = app.get(detailPageUrl).document

                // Ambil semua kotak subtitle (div.sub-single)
                detailDoc.select("div.sub-single").forEach { element ->
                    // Ambil Bahasa (Span ke-2)
                    val lang = element.select("span").getOrNull(1)?.text()?.trim() ?: "Unknown"
                    
                    // Ambil Link Download (Cari tag <a> yang memiliki href .srt)
                    // Kita hindari tombol "Translate", kita cari tombol "Download"
                    val downloadLink = element.select("a[href$='.srt']").firstOrNull()?.attr("href")
                    
                    if (!downloadLink.isNullOrEmpty()) {
                        val fullDownloadUrl = if (downloadLink.startsWith("http")) downloadLink else "https://www.subtitlecat.com$downloadLink"
                        
                        subtitleCallback.invoke(
                            SubtitleFile(lang, fullDownloadUrl)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
