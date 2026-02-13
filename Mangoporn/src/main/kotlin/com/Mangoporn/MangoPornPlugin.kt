package com.Mangoporn

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@Plugin
class MangoPornPlugin : CloudstreamPlugin() {
    override fun load(context: Context) {
        // Mendaftarkan class provider utama kita
        registerMainAPI(MangoPorn())
    }
}
