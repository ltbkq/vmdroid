/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Tiny shared helper for resolving the device's primary IPv4 address.
 * Used by both VmdroidService (when launching QEMU) and the Settings UI
 * (to display "Phone IP: …" next to port-forward rules).
 */
package io.github.ltbkq.vmdroid.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** One reachable IPv4 address of this device, tagged with the interface carrying it. */
    data class LocalAddress(val iface: String, val address: String)
    /**
     * The address users would `ssh root@<this> -p 9922` to, from another
     * device on the same LAN.
     *
     * Picks by transport preference rather than by address-pattern matching:
     * WiFi first (LAN), then Ethernet (USB-C dongles), then Cellular (hotspot
     * or LTE), skipping VPN tunnels. No address-range literals — selection is
     * a policy on transports, so it stays correct whatever network the user
     * is on.
     */
    fun localIpv4(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.let(::firstIpv4ByTransportPreference) ?: "unknown"
    } catch (_: Exception) { "unknown" }

    /**
     * Every IPv4 address a peer could plausibly reach this device on, most useful first.
     *
     * [localIpv4] deliberately answers "the one address traffic leaves through", which is
     * the wrong question when the phone is simultaneously on mobile data and acting as a
     * WiFi or USB tether: the default route is cellular, so that is the address shown, and
     * it is precisely the one a tethered PC cannot reach. Port forwards already listen on
     * 0.0.0.0, so those peers can connect fine; they were just never told the right address.
     *
     * This enumerates interfaces directly rather than going through ConnectivityManager,
     * because tethering interfaces (ap0, rndis0, swlan0) are downstreams where the phone is
     * the gateway, not networks it is a client of, so they never appear in `allNetworks` at
     * any transport preference.
     */
    fun allLocalIpv4(context: Context): List<LocalAddress> = try {
        val primary = localIpv4(context).takeIf { it != "unknown" }
        rank(enumerateIpv4(), primary)
    } catch (_: Exception) {
        emptyList()
    }

    private fun enumerateIpv4(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { LocalAddress(nif.name, it.hostAddress.orEmpty()) }
            }
            .filter { it.address.isNotEmpty() && isPresentable(it.address) }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Drops addresses that are real but useless to hand to a peer: link-local autoconf, and
     * the RFC 7335 464XLAT range carriers use for the CLAT interface, which shows up on
     * IPv6-only mobile networks and is not reachable by anyone.
     */
    private fun isPresentable(addr: String): Boolean =
        !addr.startsWith("169.254.") && !addr.startsWith("192.0.0.")

    /**
     * Primary first so the common case reads the same as before, then LAN-style private
     * addresses, since those are what a nearby PC or a tethered client actually dials.
     */
    internal fun rank(found: List<LocalAddress>, primary: String?): List<LocalAddress> =
        found.distinctBy { it.address }
            .sortedWith(
                compareBy(
                    { it.address != primary },
                    { !isPrivate(it.address) },
                    { it.iface },
                ),
            )

    private fun isPrivate(addr: String): Boolean =
        addr.startsWith("192.168.") || addr.startsWith("10.") ||
            addr.startsWith("172.") && addr.substringAfter('.').substringBefore('.').toIntOrNull()
                ?.let { it in 16..31 } == true

    /**
     * The DNS resolvers the active network handed the device over DHCP/RA, most useful
     * first, capped and sanitized for injection into the guest's `/etc/resolv.conf`
     * (see [sanitizeDnsList]).
     *
     * IPv4-only here on purpose: musl's resolver in the Alpine guest reads a plain
     * `nameserver <addr>` line per line, and the guest network stack this feeds (see
     * `podroid-network`) is IPv4-only, so a literal we can't reach is worse than none.
     */
    fun dnsServers(context: Context): List<String> = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val servers = cm?.let { it.getLinkProperties(it.activeNetwork) }
            ?.dnsServers
            ?.filterIsInstance<Inet4Address>()
            ?.map { it.hostAddress }
            .orEmpty()
        sanitizeDnsList(servers)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Pure validation/cleanup so [dnsServers]'s output is safe to write into a guest
     * config file: reject anything that isn't a plain dotted-quad IPv4 literal (kills
     * IPv6 literals, whitespace, and shell metacharacters in one check), drop loopback
     * and the unspecified address, de-duplicate, and cap at 2 - musl reads at most 3
     * `nameserver` lines and one slot is reserved for a public fallback the guest writes.
     */
    internal fun sanitizeDnsList(addresses: List<String?>): List<String> =
        addresses
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.all { c -> c.isDigit() || c == '.' } }
            .filter { isDottedQuad(it) }
            .filterNot { it.startsWith("127.") || it == "0.0.0.0" }
            .distinct()
            .take(2)

    private fun isDottedQuad(addr: String): Boolean {
        val octets = addr.split(".")
        if (octets.size != 4) return false
        return octets.all { octet ->
            octet.isNotEmpty() && octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private val TRANSPORT_PREFERENCE = intArrayOf(
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_CELLULAR,
    )

    private fun firstIpv4ByTransportPreference(cm: ConnectivityManager): String? {
        // Prefer the active (default-route) network first — it's the address
        // that traffic actually leaves through, not just any connected interface.
        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                val link = cm.getLinkProperties(active)
                if (link != null) {
                    for (la in link.linkAddresses) {
                        val addr = la.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        }
        // Fall back to transport-preference scan for edge cases (e.g. active network
        // has only IPv6, but a secondary WiFi interface has an IPv4 address).
        for (preferred in TRANSPORT_PREFERENCE) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(preferred)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = cm.getLinkProperties(net) ?: continue
                for (la in link.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        }
        return null
    }
}
