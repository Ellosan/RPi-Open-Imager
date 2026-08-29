package dev.openimager.core.customization

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The `$6$` password hash understood by `chpasswd -e` and `userconf`, so a password chosen in the
 * customisation sheet never reaches the card in the clear.
 *
 * Implements Ulrich Drepper's SHA-crypt specification, the same thing `openssl passwd -6` produces.
 */
object Sha512Crypt {

    private const val PREFIX = "$6$"
    private const val DEFAULT_ROUNDS = 5000
    private const val ALPHABET = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun randomSalt(random: SecureRandom = SecureRandom()): String =
        (1..16).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun hash(password: String, salt: String = randomSalt(), rounds: Int = DEFAULT_ROUNDS): String {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val saltBytes = salt.take(16).toByteArray(Charsets.UTF_8)

        val alternate = sha512(passwordBytes, saltBytes, passwordBytes)

        val digestA = MessageDigest.getInstance("SHA-512")
        digestA.update(passwordBytes)
        digestA.update(saltBytes)
        digestA.update(repeated(alternate, passwordBytes.size))
        var count = passwordBytes.size
        while (count > 0) {
            if (count and 1 != 0) digestA.update(alternate) else digestA.update(passwordBytes)
            count = count shr 1
        }
        var intermediate = digestA.digest()

        val sequenceP = repeated(sha512(*Array(passwordBytes.size) { passwordBytes }), passwordBytes.size)
        val saltRepeats = 16 + (intermediate[0].toInt() and 0xFF)
        val sequenceS = repeated(sha512(*Array(saltRepeats) { saltBytes }), saltBytes.size)

        for (round in 0 until rounds) {
            val digest = MessageDigest.getInstance("SHA-512")
            if (round and 1 != 0) digest.update(sequenceP) else digest.update(intermediate)
            if (round % 3 != 0) digest.update(sequenceS)
            if (round % 7 != 0) digest.update(sequenceP)
            if (round and 1 != 0) digest.update(intermediate) else digest.update(sequenceP)
            intermediate = digest.digest()
        }

        val roundsPart = if (rounds == DEFAULT_ROUNDS) "" else "rounds=$rounds$"
        return PREFIX + roundsPart + salt.take(16) + "$" + encode(intermediate)
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        parts.forEach(digest::update)
        return digest.digest()
    }

    /** Repeats [source] until [length] bytes have been produced. */
    private fun repeated(source: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var written = 0
        while (written < length) {
            val n = minOf(source.size, length - written)
            System.arraycopy(source, 0, out, written, n)
            written += n
        }
        return out
    }

    /** crypt(3) base64: little endian groups of three bytes taken in a fixed, shuffled order. */
    private fun encode(digest: ByteArray): String {
        val out = StringBuilder(86)
        for (group in GROUPS) {
            var value = ((digest[group[0]].toInt() and 0xFF) shl 16) or
                ((digest[group[1]].toInt() and 0xFF) shl 8) or
                (digest[group[2]].toInt() and 0xFF)
            repeat(4) {
                out.append(ALPHABET[value and 0x3F])
                value = value shr 6
            }
        }
        var last = digest[63].toInt() and 0xFF
        repeat(2) {
            out.append(ALPHABET[last and 0x3F])
            last = last shr 6
        }
        return out.toString()
    }

    private val GROUPS: Array<IntArray> = Array(21) { index ->
        // Each group reads three bytes 21 apart, and consecutive groups start 22 bytes further on.
        val first = (index * 22) % 63
        intArrayOf(first, (first + 21) % 63, (first + 42) % 63)
    }
}
