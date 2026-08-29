package dev.openimager.net

import android.content.Context
import android.util.Log
import dev.openimager.core.oslist.CatalogueUrls
import dev.openimager.core.oslist.OsListDocument
import dev.openimager.core.oslist.OsListItem
import dev.openimager.core.oslist.OsListParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads the Raspberry Pi OS catalogue and keeps a copy on disk, so the picker still opens when
 * the phone is offline - which is a normal state for a device being used as a card writer.
 */
class CatalogueRepository(
    context: Context,
    private val client: OkHttpClient = Http.client,
) {

    private val cacheDir = File(context.cacheDir, "catalogue").apply { mkdirs() }

    suspend fun load(forceRefresh: Boolean = false): OsListDocument = withContext(Dispatchers.IO) {
        OsListParser.parseDocument(fetch(CatalogueUrls.OS_LIST, forceRefresh))
    }

    /** Some catalogue entries defer their children to another document. */
    suspend fun loadSubList(url: String): List<OsListItem> = withContext(Dispatchers.IO) {
        OsListParser.parseSubList(fetch(url, forceRefresh = false))
    }

    private fun fetch(url: String, forceRefresh: Boolean): String {
        val cached = File(cacheDir, cacheName(url))
        val fresh = cached.exists() && System.currentTimeMillis() - cached.lastModified() < MAX_AGE_MILLIS
        if (!forceRefresh && fresh) return cached.readText()

        return try {
            val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                val body = response.body?.string() ?: throw IOException("empty response for $url")
                cached.writeText(body)
                body
            }
        } catch (e: IOException) {
            // A stale catalogue is far more useful than an error screen.
            if (cached.exists()) {
                Log.w(TAG, "using the cached catalogue after a failed refresh", e)
                cached.readText()
            } else {
                throw e
            }
        }
    }

    private fun cacheName(url: String): String =
        url.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "catalogue.json" }

    private companion object {
        const val TAG = "CatalogueRepository"
        const val MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
