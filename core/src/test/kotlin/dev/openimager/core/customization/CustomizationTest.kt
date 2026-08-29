package dev.openimager.core.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationTest {

    @Test
    fun `derives the WPA PSK from the IEEE 802 11i test vectors`() {
        assertEquals(
            "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e",
            WpaPsk.derive("IEEE", "password"),
        )
        assertEquals(
            "0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af",
            WpaPsk.derive("ThisIsASSID", "ThisIsAPassword"),
        )
    }

    @Test
    fun `leaves an already hashed PSK alone`() {
        val psk = "0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af"
        assertEquals(psk, WpaPsk.derive("ThisIsASSID", psk))
    }

    @Test
    fun `quotes values that would otherwise break out of the shell`() {
        assertEquals("'plain'", BootFileGenerator.quote("plain"))
        assertEquals("""'it'"'"'s'""", BootFileGenerator.quote("it's"))
        val settings = customized(username = "o'brien; rm -rf /", passwordCrypt = "\$6\$salt\$hash")
        val script = firstrunOf(settings)
        assertFalse("the injected command must stay inside the quoted argument", script.contains("; rm -rf /\n"))
        assertTrue(script.contains("""'o'"'"'brien; rm -rf /'"""))
    }

    @Test
    fun `firstrun script covers every enabled section and cleans up after itself`() {
        val settings = customized(
            hostname = "openpi",
            username = "maker",
            passwordCrypt = "\$6\$abc\$def",
            wifiSsid = "Home Net",
            wifiPsk = "0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af",
            wifiCountry = "GB",
            timezone = "Europe/London",
            keyboardLayout = "gb",
            enableSsh = true,
        )
        val customization = BootFileGenerator.build(settings, InitFormat.SYSTEMD)
        val script = customization.files.single { it.name == "firstrun.sh" }.content

        assertTrue(script.startsWith("#!/bin/bash\n"))
        assertTrue(script.contains("imager_custom set_hostname 'openpi'"))
        assertTrue(script.contains("userconf 'maker'"))
        assertTrue(script.contains("imager_custom enable_ssh"))
        assertTrue(script.contains("imager_custom set_wlan 'Home Net'"))
        assertTrue(script.contains("psk=0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af"))
        assertTrue(script.contains("imager_custom set_timezone 'Europe/London'"))
        assertTrue(script.contains("rm -f /boot/firstrun.sh"))
        assertTrue(script.endsWith("exit 0\n"))

        assertTrue(customization.cmdlineArguments.contains("systemd.run=/boot/firstrun.sh"))
        assertTrue(customization.cmdlineArguments.contains("cfg80211.ieee80211_regdom=GB"))
    }

    @Test
    fun `hidden networks are marked in both wpa_supplicant and network-config`() {
        val settings = customized(wifiSsid = "Ghost", wifiPsk = "passphrase", wifiHidden = true, wifiCountry = "NL")
        assertTrue(BootFileGenerator.wpaSupplicantConf(settings).contains("scan_ssid=1"))
        assertTrue(BootFileGenerator.networkConfig(settings).contains("hidden: true"))
    }

    @Test
    fun `cloud-init user-data carries the user, password and keys`() {
        val settings = customized(
            hostname = "openpi",
            username = "maker",
            passwordCrypt = "\$6\$abc\$def",
            enableSsh = true,
            sshAuthorizedKeys = "ssh-ed25519 AAAAC3Nz key-one\nssh-rsa AAAAB3Nz key-two\n",
        )
        val customization = BootFileGenerator.build(settings, InitFormat.CLOUD_INIT)
        val userData = customization.files.single { it.name == "user-data" }.content

        assertTrue(userData.startsWith("#cloud-config\n"))
        assertTrue(userData.contains("hostname: openpi"))
        assertTrue(userData.contains("- name: maker"))
        assertTrue(userData.contains("passwd: \$6\$abc\$def"))
        assertTrue(userData.contains("    - ssh-ed25519 AAAAC3Nz key-one"))
        assertTrue(userData.contains("    - ssh-rsa AAAAB3Nz key-two"))
        assertFalse("no firstrun.sh for cloud-init images", customization.files.any { it.name == "firstrun.sh" })
    }

    @Test
    fun `images that declare no init format are left untouched`() {
        val customization = BootFileGenerator.build(customized(hostname = "openpi"), InitFormat.NONE)
        assertTrue(customization.files.isEmpty())
        assertTrue(customization.cmdlineArguments.isEmpty())
    }

    @Test
    fun `catalogue init formats map to the mechanism the image supports`() {
        assertEquals(InitFormat.CLOUD_INIT, InitFormat.fromCatalogue("cloudinit"))
        assertEquals(InitFormat.CLOUD_INIT, InitFormat.fromCatalogue("cloudinit-rpi"))
        assertEquals(InitFormat.SYSTEMD, InitFormat.fromCatalogue("systemd"))
        assertEquals(InitFormat.NONE, InitFormat.fromCatalogue("none"))
        assertEquals(InitFormat.SYSTEMD, InitFormat.fromCatalogue(null))
    }

    private fun firstrunOf(settings: CustomizationSettings): String =
        BootFileGenerator.build(settings, InitFormat.SYSTEMD).files.single().content

    private fun customized(
        hostname: String = "",
        username: String = "",
        passwordCrypt: String = "",
        wifiSsid: String = "",
        wifiPsk: String = "",
        wifiHidden: Boolean = false,
        wifiCountry: String = "",
        timezone: String = "",
        keyboardLayout: String = "",
        enableSsh: Boolean = false,
        sshAuthorizedKeys: String = "",
    ) = CustomizationSettings(
        enabled = true,
        hostname = hostname,
        username = username,
        passwordCrypt = passwordCrypt,
        wifiSsid = wifiSsid,
        wifiPsk = wifiPsk,
        wifiHidden = wifiHidden,
        wifiCountry = wifiCountry,
        timezone = timezone,
        keyboardLayout = keyboardLayout,
        enableSsh = enableSsh,
        sshAuthorizedKeys = sshAuthorizedKeys,
    )
}
