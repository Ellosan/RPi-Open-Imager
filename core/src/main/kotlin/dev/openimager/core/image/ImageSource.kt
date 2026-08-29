package dev.openimager.core.image

import java.io.InputStream

/**
 * Where the image bytes come from: an HTTP download, a file the user picked, or a test fixture.
 * [open] may be called twice when a write is retried, so implementations must return a fresh
 * stream positioned at the start each time.
 */
interface ImageSource {
    val displayName: String

    /** Size of the compressed stream, or 0 when the server does not say. */
    val compressedSize: Long

    /** Size after decompression when the catalogue knows it, otherwise 0. */
    val uncompressedSize: Long

    /** SHA-256 of the decompressed image, when published. */
    val expectedSha256: String?

    fun open(onBytesRead: (Long) -> Unit = {}): InputStream
}

class SimpleImageSource(
    override val displayName: String,
    override val compressedSize: Long = 0,
    override val uncompressedSize: Long = 0,
    override val expectedSha256: String? = null,
    private val opener: ((Long) -> Unit) -> InputStream,
) : ImageSource {
    override fun open(onBytesRead: (Long) -> Unit): InputStream = opener(onBytesRead)
}
