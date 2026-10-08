/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Ordering rules for the device's reachable IPv4 addresses.
 */
package io.github.ltbkq.vmdroid.util

import io.github.ltbkq.vmdroid.util.NetworkUtils.LocalAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The interesting case is a phone on mobile data that is also tethering: the default
 * route is the carrier's, so that is the "primary" address, yet the address a tethered
 * PC must dial is the private one. Both need to be visible, and the useful ones first.
 *
 * Addresses here are illustrative only. 100.x is carrier-grade NAT space, which the
 * ranking treats as non-private; the 192.168 and 10.x values stand in for a LAN.
 */
class NetworkUtilsRankTest {

    private fun addrs(vararg pairs: Pair<String, String>) =
        pairs.map { LocalAddress(it.first, it.second) }

    @Test
    fun `primary address is listed first even when it is not a private one`() {
        val ranked = NetworkUtils.rank(
            addrs(
                "wlan0" to "192.168.45.25",
                "rmnet0" to "100.82.14.7",
            ),
            primary = "100.82.14.7",
        )
        assertEquals("100.82.14.7", ranked.first().address)
    }

    @Test
    fun `private LAN addresses outrank public ones when neither is primary`() {
        val ranked = NetworkUtils.rank(
            addrs(
                "rmnet0" to "100.82.14.7",
                "ap0" to "192.168.45.25",
            ),
            primary = null,
        )
        assertEquals("192.168.45.25", ranked.first().address)
    }

    @Test
    fun `every address survives ranking so none is hidden from the user`() {
        val ranked = NetworkUtils.rank(
            addrs(
                "wlan0" to "192.168.1.10",
                "rmnet0" to "10.20.30.40",
                "rndis0" to "192.168.35.25",
            ),
            primary = "192.168.1.10",
        )
        assertEquals(3, ranked.size)
        assertEquals("192.168.1.10", ranked.first().address)
    }

    @Test
    fun `the same address on two interfaces is reported once`() {
        val ranked = NetworkUtils.rank(
            addrs(
                "wlan0" to "192.168.1.5",
                "wlan1" to "192.168.1.5",
            ),
            primary = null,
        )
        assertEquals(1, ranked.size)
    }

    @Test
    fun `172 addresses count as private only inside the reserved 16 to 31 block`() {
        val ranked = NetworkUtils.rank(
            addrs(
                "eth0" to "172.32.0.4",
                "eth1" to "172.20.0.4",
            ),
            primary = null,
        )
        assertEquals("172.20.0.4", ranked.first().address)
    }
}
