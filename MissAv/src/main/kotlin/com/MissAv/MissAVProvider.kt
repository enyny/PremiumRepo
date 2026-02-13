package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws/id"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    
    // Fitur Search & Homepage aktif
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ==============================
    // 1. KONFIGURASI KATEGORI (HOME)
    // ==============================
    // Kita daftarkan URL dan Nama Kategori di sini.
    // Cloudstream akan otomatis memproses ini satu per satu.
    override val mainPage = mainPageOf(
        "https://missav.ws/dm628/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "https://missav.ws/dm590/id/release" to "Keluaran Terbaru",
        "https://missav.ws/dm515/id/new" to "Recent Update",
        "https://missav.ws/dm68/id/genres/Wanita%20Menikah/Ibu%20Rumah%20Tangga" to "Wanita menikah"
    )

    // Helper URL
    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    // ==============================
    // 2. FUNGSI LOAD HALAMAN (Get Main Page)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Logika Pagination:
        // Jika page = 1, pakai URL asli. Jika page > 1, tambahkan parameter page.
        // Contoh: .../release?page=2
        val url = if (page == 1) {
            request.data
        } else {
            // Cek apakah URL sudah punya tanda tanya '?' atau belum
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(url).document
        
        // Ambil daftar video langsung dari halaman kategori
        val items = document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }

        // Kembalikan hasil ke Cloudstream
        // request.name otomatis mengambil nama dari variabel mainPage di atas
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // Helper: Mengubah elemen HTML video menjadi data SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()
        val imgElement = element.selectFirst("img")
        
        // Prioritas ambil data-src (lazy load), kalau tidak ada baru src
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 3. PENCARIAN (SEARCH)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        // Menggunakan jalur legacy agar lebih aman dari token
        val url = "$mainUrl/legacy?keyword=$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==============================
    // 4. DETAIL VIDEO (LOAD)
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

        // Ambil durasi (detik) dan konversi ke menit
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
    // 5. LINK VIDEO (PLAYER)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(data).text
        
        // Regex mengambil UUID dari thumbnail nineyu
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
            return true
        }

        return false
    }
}
