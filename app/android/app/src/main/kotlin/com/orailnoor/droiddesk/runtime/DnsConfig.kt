package com.orailnoor.droiddesk.runtime

import android.util.Log
import java.io.File
import java.net.InetAddress

/**
 * DNS configuration for the chroot.
 *
 * Writing a hardcoded 8.8.8.8 / 1.1.1.1 resolv.conf breaks the install on
 * plenty of networks: those resolvers are commonly unreachable from mainland
 * China, which surfaced as "Temporary failure resolving
 * 'mirrors.tuna.tsinghua.edu.cn'" inside the chroot even though the app itself
 * could reach the mirror.
 *
 * Two complementary fixes are applied here:
 *  1. resolv.conf is generated from the DNS servers Android is actually using
 *     (read via dumpsys / getprop), with domestic resolvers as a fallback.
 *  2. Every APT mirror hostname is resolved on the Android side (where DNS
 *     works) and pinned into the chroot's /etc/hosts, so apt keeps working even
 *     if in-chroot DNS is completely broken.
 */
object DnsConfig {
    private const val TAG = "DnsConfig"
    private const val MAX_NAMESERVERS = 4

    /**
     * Domestic resolvers first — 8.8.8.8 / 1.1.1.1 are frequently filtered in
     * mainland China, so they are kept only as a last resort.
     */
    val FALLBACK_DNS = listOf(
        "223.5.5.5",        // Alibaba Cloud
        "119.29.29.29",     // Tencent DNSPod
        "114.114.114.114",  // 114DNS
        "8.8.8.8",
        "1.1.1.1",
    )

    /** Hosts that must resolve for package installation to work. */
    private val PINNED_HOSTS = MirrorSelector.MIRROR_HOSTS + "ports.ubuntu.com"

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * Return the DNS servers Android is actively using, filtered to IPv4
     * (IPv6 is often unusable inside the chroot).
     */
    fun discoverHostDns(rootShell: RootShell): List<String> {
        val found = LinkedHashSet<String>()

        // 1) LinkProperties from connectivity — reflects the current network
        //    including DHCP-provided and Private DNS resolvers.
        try {
            val output = rootShell.exec(
                "dumpsys connectivity 2>/dev/null | grep -o 'DnsAddresses: \\[[^]]*\\]'"
            )
            Regex("""DnsAddresses: \[([^\]]*)\]""").findAll(output).forEach { match ->
                match.groupValues[1].split(',')
                    .map { it.trim() }
                    .filter { IPV4.matches(it) }
                    .forEach { found.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "dumpsys connectivity DNS read failed: ${e.message}")
        }

        // 2) Legacy net.dns* properties (still populated on many devices).
        for (key in listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4")) {
            try {
                val value = rootShell.exec("getprop $key").trim()
                if (IPV4.matches(value)) found.add(value)
            } catch (e: Exception) {
                Log.w(TAG, "getprop $key failed: ${e.message}")
            }
        }

        Log.i(TAG, "Android DNS servers: $found")
        return found.toList()
    }

    /**
     * Write <rootfs>/etc/resolv.conf. The stock file is a symlink to a systemd
     * resolver stub, so it is deleted before writing.
     * Returns the nameservers that were written.
     */
    fun writeResolvConf(rootfsDir: File, dnsServers: List<String>): List<String> {
        val usable = dnsServers.filter { IPV4.matches(it) }
            .distinct()
            .take(MAX_NAMESERVERS)
            .ifEmpty { FALLBACK_DNS.take(MAX_NAMESERVERS) }

        val content = buildString {
            usable.forEach { append("nameserver $it\n") }
            append("options timeout:2 attempts:3\n")
        }

        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.delete()
        resolvConf.writeText(content)
        Log.i(TAG, "Wrote chroot resolv.conf with $usable")
        return usable
    }

    /**
     * Resolve mirror hostnames on the Android side and pin them into
     * <rootfs>/etc/hosts. This is the safety net that keeps apt working when
     * DNS inside the chroot fails entirely.
     */
    fun pinHosts(rootfsDir: File): List<String> {
        val pins = mutableListOf<String>()
        PINNED_HOSTS.forEach { host ->
            try {
                val addresses = InetAddress.getAllByName(host)
                    .map { it.hostAddress ?: "" }
                    .filter { IPV4.matches(it) }
                addresses.forEach { pin ->
                    pins.add("$pin $host")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve $host on the Android side: ${e.message}")
            }
        }

        val hostsFile = File(rootfsDir, "etc/hosts")
        hostsFile.parentFile?.mkdirs()
        // The app fully owns this file (configureRootfs writes the same base
        // entries), so regenerate it instead of merging to avoid duplicate pins.
        val merged = buildList {
            add("127.0.0.1   localhost")
            add("127.0.0.1   droiddesk")
            add("::1         localhost ip6-localhost ip6-loopback")
            if (pins.isNotEmpty()) {
                add("# droiddesk-pin (mirror IPs resolved on Android, used only when in-chroot DNS fails)")
                addAll(pins)
            }
        }
        hostsFile.writeText(merged.joinToString("\n", postfix = "\n"))
        Log.i(TAG, "Pinned ${pins.size} mirror IPs into chroot /etc/hosts")
        return pins
    }

    /**
     * Full DNS setup: discover the real Android resolvers, write them, then
     * pin mirror IPs. Returns the nameservers written.
     */
    fun applyTo(
        rootfsDir: File,
        rootShell: RootShell,
        chinese: Boolean = false,
        onLog: (String) -> Unit = {},
    ): List<String> {
        val hostDns = try {
            discoverHostDns(rootShell)
        } catch (e: Exception) {
            Log.w(TAG, "DNS discovery failed: ${e.message}")
            emptyList()
        }
        val combined = hostDns + FALLBACK_DNS
        val written = writeResolvConf(rootfsDir, combined)
        onLog(if (chinese) "DNS 服务器：${written.joinToString("、")}\n" else "DNS servers: ${written.joinToString(", ")}\n")

        val pins = pinHosts(rootfsDir)
        if (pins.isNotEmpty()) {
            onLog(
                if (chinese) "已在 Android 侧解析 ${pins.size} 条镜像地址并写入 /etc/hosts\n"
                else "Resolved ${pins.size} mirror addresses and pinned them into /etc/hosts\n"
            )
        } else {
            onLog(
                if (chinese) "警告：未能预先解析镜像域名，将依赖 chroot 内 DNS\n"
                else "Warning: could not pre-resolve mirror hosts; relying on chroot DNS\n"
            )
        }
        return written
    }
}
