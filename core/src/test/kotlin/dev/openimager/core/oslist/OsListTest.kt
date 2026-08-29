package dev.openimager.core.oslist

import dev.openimager.core.customization.InitFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a slice of the catalogue Raspberry Pi actually publishes, keys and all. */
class OsListTest {

    private val document: OsListDocument by lazy {
        val json = checkNotNull(javaClass.getResourceAsStream("/os_list_sample.json")) {
            "the catalogue fixture is missing"
        }.bufferedReader().readText()
        OsListParser.parseDocument(json)
    }

    @Test
    fun `reads the hardware filter`() {
        val devices = document.imager?.devices.orEmpty()
        assertTrue(devices.isNotEmpty())
        val pi5 = devices.first { it.name == "Raspberry Pi 5" }
        assertTrue(pi5.isExclusive)
        assertEquals(listOf("pi5-64bit", "pi5-32bit"), pi5.tags)
        assertFalse(devices.first { it.name == "Raspberry Pi 4" }.isExclusive)
    }

    @Test
    fun `reads downloadable entries and categories`() {
        val top = document.osList
        val raspberryPiOs = top.first { it.name.startsWith("Raspberry Pi OS (64-bit)") }
        assertTrue(raspberryPiOs.isImage)
        assertFalse(raspberryPiOs.isCategory)
        assertTrue(raspberryPiOs.extractSize > 0)
        assertNotNull(raspberryPiOs.extractSha256)
        assertTrue(raspberryPiOs.devices.contains("pi5-64bit"))
        assertEquals(InitFormat.CLOUD_INIT, InitFormat.fromCatalogue(raspberryPiOs.initFormat))

        val category = top.first { it.name == "Raspberry Pi OS (other)" }
        assertTrue(category.isCategory)
        assertFalse(category.isImage)
        assertTrue(category.subitems.isNotEmpty())

        val deferred = top.first { it.name == "Remote category" }
        assertTrue(deferred.isCategory)
        assertEquals("https://example.invalid/sub.json", deferred.subitemsUrl)
    }

    @Test
    fun `filters images by board, keeping categories that still have children`() {
        val pi1 = document.imager!!.devices.first { it.name == "Raspberry Pi 1" }
        val filtered = document.osList.filterForDevice(pi1)

        assertFalse(
            "64-bit images cannot run on a Pi 1",
            filtered.any { it.name == "Raspberry Pi OS (64-bit)" },
        )
        filtered.forEach { item ->
            if (item.isImage) assertTrue(item.name, item.devices.isEmpty() || item.devices.contains("pi1-32bit"))
        }
        assertTrue("categories with matching children survive", filtered.any { it.isCategory })
    }

    @Test
    fun `an unfiltered list is returned unchanged`() {
        val none = document.imager!!.devices.first { it.name == "No filtering" }
        assertEquals(document.osList.size, document.osList.filterForDevice(none).size)
        assertEquals(document.osList.size, document.osList.filterForDevice(null).size)
    }
}
