package dev.openimager.core.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checks the hash byte for byte against `openssl passwd -6`, which is what the Pi will verify. */
class Sha512CryptTest {

    @Test
    fun `matches openssl for a range of passwords and salts`() {
        assumeTrue("openssl is required", openssl(listOf("version"), "") != null)
        val cases = listOf(
            "raspberry" to "abcdefghijklmnop",
            "correct horse battery staple" to "SaltySalt",
            "\u00fcml\u00e4ut-pa\u00dfwort" to "Zz09./xy",
            "x".repeat(200) to "LongSaltHere1234",
        )
        for ((password, salt) in cases) {
            val expected = openssl(listOf("passwd", "-6", "-salt", salt, "-stdin"), password)
            assertNotNull("openssl produced no hash for $salt", expected)
            assertEquals("password=$password salt=$salt", expected, Sha512Crypt.hash(password, salt))
        }
    }

    /** glibc reference hashes for the cases `openssl passwd` will not produce. */
    @Test
    fun `matches glibc crypt for edge cases`() {
        assertEquals(
            "\$6\$0123456789./ABCD\$UO6RYu1wblUE2yP6W3D7YgOMy.l8m7g8/OL9Z5kkgVgjzv5MzbNuuLDyXwRqKk.mdZUr2HoK.nFJYr55qxN1p0",
            Sha512Crypt.hash("", "0123456789./ABCD"),
        )
        assertEquals(
            "\$6\$rounds=10000\$SaltySalt\$gPtccjtHL7Tov4YBA3gn0CD/mwm5BuLKGHfL4ppv/xyvec/THH0fHpAsXOxI4bHPjs76Efmwhl9SYQf7P/NTu.",
            Sha512Crypt.hash("correct horse battery staple", "SaltySalt", rounds = 10000),
        )
    }

    @Test
    fun `generates a fresh 16 character salt`() {
        val first = Sha512Crypt.hash("raspberry")
        val second = Sha512Crypt.hash("raspberry")
        assertTrue(first.startsWith("$6$"))
        assertEquals(3 + 16 + 1 + 86, first.length)
        assertTrue("salt should be random", first != second)
    }

    private fun openssl(args: List<String>, stdin: String): String? = try {
        val process = ProcessBuilder(listOf("openssl") + args).start()
        try {
            process.outputStream.use { it.write((stdin + "\n").toByteArray()) }
        } catch (e: java.io.IOException) {
            // openssl subcommands that take no input close stdin straight away.
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output.ifEmpty { null } else null
    } catch (e: Exception) {
        null
    }
}
