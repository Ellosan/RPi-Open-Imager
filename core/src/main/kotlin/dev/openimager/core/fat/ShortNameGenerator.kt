package dev.openimager.core.fat

/**
 * Derives the 11 byte 8.3 entry that every FAT directory entry needs.
 *
 * When the name already fits 8.3 the canonical short name is kept verbatim - the Pi bootloader
 * looks up `CMDLINE.TXT` and `CONFIG.TXT` through the short entry, so a `CMDLIN~1.TXT` would make
 * the card unbootable. Purely lower case names are stored with the Windows NT case flags, which
 * keeps them readable on Linux without spending directory slots on a long name.
 */
internal object ShortNameGenerator {

    data class Result(
        /** Exactly 11 characters: 8 for the stem, 3 for the extension, space padded. */
        val shortName: String,
        /** Number of long file name slots that must precede the short entry. */
        val longNameEntries: Int,
        /** Bit 0x08 lower cases the stem, 0x10 the extension, when no long name is present. */
        val caseFlags: Int,
    )

    private const val ALLOWED = "$%'-_@~`!(){}^#&+,;=[]"

    fun generate(name: String, taken: Set<String>): Result {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot + 1) else ""

        if (fitsShortName(stem, extension)) {
            val caseFlags = (if (isLower(stem)) 0x08 else 0) or (if (extension.isNotEmpty() && isLower(extension)) 0x10 else 0)
            val mixedCase = !(isLower(stem) || isUpper(stem)) || !(isLower(extension) || isUpper(extension))
            val short = pad(stem.uppercase(), extension.uppercase())
            if (!mixedCase) return Result(short, longNameEntries = 0, caseFlags = caseFlags)
            return Result(short, longNameEntries = longNameEntries(name), caseFlags = 0)
        }

        val sanitizedStem = sanitize(stem).ifEmpty { "FILE" }
        val sanitizedExtension = sanitize(extension).take(3)
        var candidate = ""
        var suffix = 1
        while (suffix < 1_000_000) {
            val tail = "~$suffix"
            val base = sanitizedStem.take(8 - tail.length) + tail
            candidate = pad(base, sanitizedExtension)
            if (candidate !in taken) break
            suffix++
        }
        return Result(candidate, longNameEntries = longNameEntries(name), caseFlags = 0)
    }

    fun longNameEntries(name: String): Int = (name.length + 12) / 13

    private fun fitsShortName(stem: String, extension: String): Boolean =
        stem.isNotEmpty() && stem.length <= 8 && extension.length <= 3 &&
            stem.all(::isShortNameChar) && extension.all(::isShortNameChar)

    private fun isShortNameChar(c: Char): Boolean =
        c.isDigit() || (c in 'A'..'Z') || (c in 'a'..'z') || c in ALLOWED || c.code in 128..255

    private fun sanitize(value: String): String = buildString {
        for (c in value.uppercase()) {
            if (c == ' ' || c == '.') continue
            append(if (isShortNameChar(c)) c else '_')
        }
    }

    private fun isLower(value: String): Boolean = value.none { it.isUpperCase() }

    private fun isUpper(value: String): Boolean = value.none { it.isLowerCase() }

    private fun pad(stem: String, extension: String): String =
        stem.take(8).padEnd(8, ' ') + extension.take(3).padEnd(3, ' ')
}
