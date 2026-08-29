package dev.openimager.core.customization

/** A file to be dropped in the root of the boot partition. */
data class BootFile(val name: String, val content: String)

/** Everything the customisation step wants to change on the boot partition. */
data class BootCustomization(
    val files: List<BootFile>,
    /** Extra kernel arguments to append to `cmdline.txt`, without a leading space. */
    val cmdlineArguments: List<String>,
)

/**
 * Builds the first boot files. The generated `firstrun.sh` and cloud-init documents follow the ones
 * Raspberry Pi Imager writes, so images that special case them (`raspberrypi-sys-mods`,
 * `userconf-pi`) behave exactly as their authors expect.
 */
object BootFileGenerator {

    private const val FIRSTRUN_PATH = "/boot/firstrun.sh"

    fun build(settings: CustomizationSettings, format: InitFormat): BootCustomization {
        if (!settings.hasAnyImageChange || format == InitFormat.NONE) {
            return BootCustomization(emptyList(), emptyList())
        }
        return when (format) {
            InitFormat.SYSTEMD -> systemd(settings)
            InitFormat.CLOUD_INIT -> cloudInit(settings)
            InitFormat.NONE -> BootCustomization(emptyList(), emptyList())
        }
    }

    // region firstrun.sh

    private fun systemd(settings: CustomizationSettings): BootCustomization {
        val script = StringBuilder()
        script.append("#!/bin/bash\n\n")
        script.append("# Written by RPi Open Imager. Runs once on first boot, then deletes itself.\n")
        script.append("set +e\n\n")

        if (settings.hostname.isNotBlank()) script.append(hostnameSection(settings.hostname))
        if (settings.hasUser || settings.enableSsh) {
            script.append("FIRSTUSER=`getent passwd 1000 | cut -d: -f1`\n")
            script.append("FIRSTUSERHOME=`getent passwd 1000 | cut -d: -f6`\n\n")
        }
        if (settings.enableSsh) script.append(sshSection(settings))
        if (settings.hasUser) script.append(userSection(settings))
        if (settings.hasWifi) script.append(wifiSection(settings))
        if (settings.timezone.isNotBlank() || settings.keyboardLayout.isNotBlank()) {
            script.append(localeSection(settings))
        }

        script.append("rm -f $FIRSTRUN_PATH\n")
        script.append("sed -i 's| systemd.run.*||g' /boot/cmdline.txt\n")
        script.append("exit 0\n")

        val arguments = mutableListOf(
            "systemd.run=$FIRSTRUN_PATH",
            "systemd.run_success_action=reboot",
            "systemd.unit=kernel-command-line.target",
        )
        if (settings.hasWifi && settings.wifiCountry.isNotBlank()) {
            arguments.add(0, "cfg80211.ieee80211_regdom=${settings.wifiCountry}")
        }
        return BootCustomization(listOf(BootFile("firstrun.sh", script.toString())), arguments)
    }

    private fun hostnameSection(hostname: String): String = buildString {
        append("CURRENT_HOSTNAME=`cat /etc/hostname | tr -d \" \\t\\n\\r\"`\n")
        append("if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then\n")
        append("   /usr/lib/raspberrypi-sys-mods/imager_custom set_hostname ${quote(hostname)}\n")
        append("else\n")
        append("   echo ${quote(hostname)} >/etc/hostname\n")
        append("   sed -i \"s/127.0.1.1.*\$CURRENT_HOSTNAME/127.0.1.1\\t$hostname/g\" /etc/hosts\n")
        append("fi\n\n")
    }

    private fun sshSection(settings: CustomizationSettings): String = buildString {
        if (settings.hasSshKeys) {
            val spaceSeparated = settings.sshKeyList.joinToString(" ") { quote(it) }
            val printfArgument = settings.sshKeyList.joinToString("") { quote(it) + "\\n" }
            append("if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then\n")
            append("   /usr/lib/raspberrypi-sys-mods/imager_custom enable_ssh -k $spaceSeparated\n")
            append("else\n")
            append("   install -o \"\$FIRSTUSER\" -m 700 -d \"\$FIRSTUSERHOME/.ssh\"\n")
            append("   install -o \"\$FIRSTUSER\" -m 600 <(printf \"$printfArgument\") ")
            append("\"\$FIRSTUSERHOME/.ssh/authorized_keys\"\n")
            if (!settings.sshPasswordAuthentication) {
                append("   echo 'PasswordAuthentication no' >>/etc/ssh/sshd_config\n")
            }
            append("   systemctl enable ssh\n")
            append("fi\n\n")
        } else {
            append("if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then\n")
            append("   /usr/lib/raspberrypi-sys-mods/imager_custom enable_ssh\n")
            append("else\n")
            append("   echo 'PasswordAuthentication yes' >>/etc/ssh/sshd_config\n")
            append("   systemctl enable ssh\n")
            append("fi\n\n")
        }
    }

    private fun userSection(settings: CustomizationSettings): String = buildString {
        val user = settings.username
        val crypt = quote(settings.passwordCrypt)
        append("if [ -f /usr/lib/userconf-pi/userconf ]; then\n")
        append("   /usr/lib/userconf-pi/userconf ${quote(user)} $crypt\n")
        append("else\n")
        append("   echo \"\$FIRSTUSER:\"$crypt | chpasswd -e\n")
        append("   if [ \"\$FIRSTUSER\" != ${quote(user)} ]; then\n")
        append("      usermod -l ${quote(user)} \"\$FIRSTUSER\"\n")
        append("      usermod -m -d ${quote("/home/$user")} ${quote(user)}\n")
        append("      groupmod -n ${quote(user)} \"\$FIRSTUSER\"\n")
        append("      if grep -q \"^autologin-user=\" /etc/lightdm/lightdm.conf ; then\n")
        append("         sed /etc/lightdm/lightdm.conf -i -e \"s/^autologin-user=.*/autologin-user=$user/\"\n")
        append("      fi\n")
        append("      if [ -f /etc/systemd/system/getty@tty1.service.d/autologin.conf ]; then\n")
        append("         sed /etc/systemd/system/getty@tty1.service.d/autologin.conf -i -e ")
        append("\"s/\$FIRSTUSER/$user/\"\n")
        append("      fi\n")
        append("      if [ -f /etc/sudoers.d/010_pi-nopasswd ]; then\n")
        append("         sed -i \"s/^\$FIRSTUSER /$user /\" /etc/sudoers.d/010_pi-nopasswd\n")
        append("      fi\n")
        append("   fi\n")
        append("fi\n\n")
    }

    private fun wifiSection(settings: CustomizationSettings): String = buildString {
        val hidden = if (settings.wifiHidden) " -h " else ""
        append("if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then\n")
        append("   /usr/lib/raspberrypi-sys-mods/imager_custom set_wlan$hidden ")
        append("${quote(settings.wifiSsid)} ${quote(settings.wifiPsk)} ${quote(settings.wifiCountry)}\n")
        append("else\n")
        append("cat >/etc/wpa_supplicant/wpa_supplicant.conf <<'WPAEOF'\n")
        append(wpaSupplicantConf(settings))
        append("WPAEOF\n")
        append("   chmod 600 /etc/wpa_supplicant/wpa_supplicant.conf\n")
        append("   rfkill unblock wifi\n")
        append("   for filename in /var/lib/systemd/rfkill/*:wlan ; do\n")
        append("       echo 0 > \$filename\n")
        append("   done\n")
        append("fi\n\n")
    }

    fun wpaSupplicantConf(settings: CustomizationSettings): String = buildString {
        append("country=${settings.wifiCountry}\n")
        append("ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev\n")
        append("ap_scan=1\n\n")
        append("update_config=1\n")
        append("network={\n")
        if (settings.wifiHidden) append("\tscan_ssid=1\n")
        append("\tssid=\"${settings.wifiSsid}\"\n")
        // A derived PSK is written unquoted; a passphrase short enough to keep in the clear is not.
        if (WpaPsk.isPreHashed(settings.wifiPsk)) {
            append("\tpsk=${settings.wifiPsk}\n")
        } else {
            append("\tpsk=\"${settings.wifiPsk}\"\n")
        }
        append("}\n")
    }

    private fun localeSection(settings: CustomizationSettings): String = buildString {
        append("if [ -f /usr/lib/raspberrypi-sys-mods/imager_custom ]; then\n")
        if (settings.keyboardLayout.isNotBlank()) {
            append("   /usr/lib/raspberrypi-sys-mods/imager_custom set_keymap ${quote(settings.keyboardLayout)}\n")
        }
        if (settings.timezone.isNotBlank()) {
            append("   /usr/lib/raspberrypi-sys-mods/imager_custom set_timezone ${quote(settings.timezone)}\n")
        }
        append("else\n")
        if (settings.timezone.isNotBlank()) {
            append("   rm -f /etc/localtime\n")
            append("   echo \"${settings.timezone}\" >/etc/timezone\n")
            append("   dpkg-reconfigure -f noninteractive tzdata\n")
        }
        if (settings.keyboardLayout.isNotBlank()) {
            append("cat >/etc/default/keyboard <<'KBEOF'\n")
            append("XKBMODEL=\"pc105\"\n")
            append("XKBLAYOUT=\"${settings.keyboardLayout}\"\n")
            append("XKBVARIANT=\"\"\n")
            append("XKBOPTIONS=\"\"\n")
            append("KBEOF\n")
            append("   dpkg-reconfigure -f noninteractive keyboard-configuration\n")
        }
        append("fi\n\n")
    }

    // endregion

    // region cloud-init

    private fun cloudInit(settings: CustomizationSettings): BootCustomization {
        val userData = StringBuilder("#cloud-config\n")

        if (settings.hostname.isNotBlank()) {
            userData.append("hostname: ${settings.hostname}\n")
            userData.append("manage_etc_hosts: true\n")
            userData.append("packages:\n- avahi-daemon\n")
            // NTP may not have synchronised yet on first boot, so stop apt rejecting the archive.
            userData.append("apt:\n  conf: |\n    Acquire {\n      Check-Date \"false\";\n    };\n\n")
        }

        if (settings.hasUser || settings.enableSsh) {
            val name = settings.username.ifBlank { "pi" }
            userData.append("users:\n")
            userData.append("- name: $name\n")
            userData.append("  groups: users,adm,dialout,audio,netdev,video,plugdev,cdrom,games,input,gpio,spi,i2c,render,sudo\n")
            userData.append("  shell: /bin/bash\n")
            if (settings.hasUser) {
                userData.append("  lock_passwd: false\n")
                userData.append("  passwd: ${settings.passwordCrypt}\n")
            } else {
                userData.append("  lock_passwd: true\n")
            }
            if (settings.enableSsh && settings.hasSshKeys) {
                userData.append("  ssh_authorized_keys:\n")
                settings.sshKeyList.forEach { userData.append("    - $it\n") }
                userData.append("  sudo: ALL=(ALL) NOPASSWD:ALL\n")
            }
            userData.append("\n")
            if (settings.enableSsh && !settings.hasSshKeys) userData.append("ssh_pwauth: true\n\n")
        }

        if (settings.timezone.isNotBlank()) userData.append("timezone: ${settings.timezone}\n")
        if (settings.keyboardLayout.isNotBlank()) {
            userData.append("keyboard:\n  model: pc105\n  layout: \"${settings.keyboardLayout}\"\n")
        }

        val files = mutableListOf(BootFile("user-data", userData.toString()))
        val arguments = mutableListOf<String>()

        if (settings.hasWifi) {
            files += BootFile("network-config", networkConfig(settings))
            if (settings.wifiCountry.isNotBlank()) {
                arguments += "cfg80211.ieee80211_regdom=${settings.wifiCountry}"
            }
        }
        return BootCustomization(files, arguments)
    }

    fun networkConfig(settings: CustomizationSettings): String = buildString {
        append("version: 2\n")
        append("wifis:\n")
        append("  renderer: networkd\n")
        append("  wlan0:\n")
        append("    dhcp4: true\n")
        append("    optional: true\n")
        append("    access-points:\n")
        append("      \"${settings.wifiSsid}\":\n")
        append("        password: \"${settings.wifiPsk}\"\n")
        if (settings.wifiHidden) append("        hidden: true\n")
    }

    // endregion

    /** Wraps a value in single quotes the way `escapeshellarg` does. */
    internal fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
