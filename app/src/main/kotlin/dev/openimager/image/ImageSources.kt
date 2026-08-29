package dev.openimager.image

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.openimager.core.image.ImageSource
import dev.openimager.net.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Counts the bytes pulled from the underlying stream so the UI can show download progress. */
private class CountingInputStream(
    stream: InputStream,
    private val onBytesRead: (Long) -> Unit,
) : FilterInputStream(stream) {

    private var total = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) report(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) report(read.toLong())
        return read
    }

    private fun report(count: Long) {
        total += count
        onBytesRead(total)
    }
}

/** An image streamed straight from the Raspberry Pi download servers onto the card. */
class HttpImageSource(
    private val url: String,
    override val displayName: String,
    override val compressedSize: Long = 0,
    override val uncompressedSize: Long = 0,
    override val expectedSha256: String? = null,
    private val client: OkHttpClient = Http.client,
) : ImageSource {

    override fun open(onBytesRead: (Long) -> Unit): InputStream {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            // Transparent gzip would decompress an already compressed image behind our back.
            .header("Accept-Encoding", "identity")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("the download failed with HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("the download returned no data")
        return CountingInputStream(body.byteStream(), onBytesRead)
    }
}

/** An image the user picked from their own storage. */
class LocalImageSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
    override val compressedSize: Long,
    override val uncompressedSize: Long,
) : ImageSource {

    override val expectedSha256: String? = null

    override fun open(onBytesRead: (Long) -> Unit): InputStream {
        val stream = resolver.openInputStream(uri) ?: throw IOException("$displayName could not be opened")
        return CountingInputStream(stream, onBytesRead)
    }

    companion object {
        fun from(resolver: ContentResolver, uri: Uri): LocalImageSource {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
            var size = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                }
            }
            // An uncompressed image writes exactly its own size, which makes the progress bar exact.
            val uncompressed = if (isUncompressed(resolver, uri)) size else 0L
            return LocalImageSource(resolver, uri, name, size, uncompressed)
        }

        private fun isUncompressed(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val magic = ByteArray(8)
                var read = 0
                while (read < magic.size) {
                    val n = stream.read(magic, read, magic.size - read)
                    if (n < 0) break
                    read += n
                }
                dev.openimager.core.image.Decompressor.detect(magic, read) ==
                    dev.openimager.core.image.ImageFormat.RAW
            } ?: false
        }.getOrDefault(false)
    }
}
