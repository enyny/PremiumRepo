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

    // ==========================================
    // BAGIAN 1: HALAMAN UTAMA (HOME)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        // Selector disesuaikan agar lebih spesifik
        val home = document.select("div.filter__gallery > a").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h5.sidebar-title-h5")?.text()?.trim() ?: return null
        
        // FIX: Jangan hapus bagian '/episode/' agar load() membuka halaman yang memiliki list episode
        val href = fixUrl(element.attr("href"))
        
        // FIX GAMBAR: Mencoba beberapa cara pengambilan gambar agar tidak duplikat/kosong
        val imageDiv = element.selectFirst(".product__sidebar__view__item")
        var posterUrl = imageDiv?.attr("data-setbg")
        
        // Fallback 1: Jika data-setbg kosong, coba cek style background-image
        if (posterUrl.isNullOrEmpty()) {
            val style = imageDiv?.attr("style") ?: ""
            if (style.contains("url(")) {
                posterUrl = style.substringAfter("url(").substringBefore(")")
                    .replace("\"", "").replace("'", "")
            }
        }
        
        // Fallback 2: Coba cari tag img di dalam (kadang struktur berubah)
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = element.selectFirst("img")?.attr("src")
        }

        val epText = element.selectFirst(".ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epText != null) {
                addQuality(epText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url).document
        return document.select("div.filter__gallery > a").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==========================================
    // BAGIAN 2: DETAIL ANIME & LIST EPISODE
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // FIX JUDUL: Membersihkan judul dari SEO spam yang terlihat di screenshot
        // Contoh: "Jujutsu Kaisen ... (Episode 06) Subtitle Indonesia" -> "Jujutsu Kaisen ..."
        var rawTitle = document.selectFirst("title")?.text() ?: ""
        if (rawTitle.isEmpty()) {
            rawTitle = document.select("meta[property=og:title]").attr("content")
        }
        
        val title = rawTitle
            .replace(Regex("\\(Episode\\s+\\d+\\).*"), "") // Hapus (Episode XX)...
            .replace(Regex("Episode\\s+\\d+.*"), "") // Hapus Episode XX...
            .replace("Subtitle Indonesia", "", true)
            .replace("- Kuramanime", "")
            .trim()

        val poster = document.select("meta[property=og:image]").attr("content")
        
        // Deskripsi (Meta description sering spam keyword, tapi lebih baik daripada kosong)
        val description = document.select("meta[name=description]").attr("content")

        // FIX EPISODE: Mengambil episode dari halaman Watch Page (karena URL tidak kita potong)
        // Selector: div#animeEpisodes -> a.ep-button
        val episodes = document.select("#animeEpisodes a.ep-button").mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epName = ep.text().trim() // Contoh: "Ep 6"
            
            // Ambil angka dari "Ep 6" -> 6
            val epNum = Regex("(?i)Ep\\s*(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("\\d+").find(epName)?.value?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            
            // Jika episode ditemukan, tambahkan.
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            } else {
                // Debugging: Jika kosong, mungkin struktur berubah atau server mendeteksi bot
                // Tapi harusnya aman karena kita pakai URL halaman nonton
            }
        }
    }

    // ==========================================
    // BAGIAN 3: MENGAMBIL VIDEO (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val document = app.get(data).document
        
        // Ambil server dari dropdown
        val serverOptions = document.select("select#changeServer option")

        serverOptions.forEach { option ->
            val serverName = option.text()
            val serverValue = option.attr("value")
            
            // Skip server VIP/Premium jika ada
            if (serverValue.contains("kuramadrive") && serverName.contains("vip", true)) return@forEach

            // Request ulang dengan parameter server
            val serverUrl = "$data?server=$serverValue"
            
            try {
                val serverPage = app.get(serverUrl).document
                val iframeSrc = serverPage.select("iframe").attr("src")
                
                if (iframeSrc.isNotEmpty()) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                } 
            } catch (e: Exception) {
                // Ignore error
            }
        }

        return true
    }
}
