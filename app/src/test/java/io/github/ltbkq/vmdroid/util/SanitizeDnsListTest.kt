/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Validation rules for DNS resolver strings before they reach the guest's resolv.conf.
 */
package io.github.ltbkq.vmdroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeDnsListTest {

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList<String>(), NetworkUtils.sanitizeDnsList(emptyList()))
    }

    @Test
    fun `a typical two-resolver LAN case is preserved in order`() {
        assertEquals(
            listOf("192.168.1.1", "192.168.1.2"),
            NetworkUtils.sanitizeDnsList(listOf("192.168.1.1", "192.168.1.2")),
        )
    }

    @Test
    fun `an ipv6 literal is dropped`() {
        assertEquals(
            emptyList<String>(),
            NetworkUtils.sanitizeDnsList(listOf("2001:4860:4860::8888")),
        )
    }

    @Test
    fun `loopback is dropped`() {
        assertEquals(emptyList<String>(), NetworkUtils.sanitizeDnsList(listOf("127.0.0.1")))
    }

    @Test
    fun `the unspecified address is dropped`() {
        assertEquals(emptyList<String>(), NetworkUtils.sanitizeDnsList(listOf("0.0.0.0")))
    }

    @Test
    fun `a blank string is dropped`() {
        assertEquals(emptyList<String>(), NetworkUtils.sanitizeDnsList(listOf("   ")))
    }

    @Test
    fun `a string with shell metacharacters is dropped`() {
        assertEquals(
            emptyList<String>(),
            NetworkUtils.sanitizeDnsList(listOf("1.2.3.4 ; rm -rf /")),
        )
    }

    @Test
    fun `an out of range octet is dropped`() {
        assertEquals(emptyList<String>(), NetworkUtils.sanitizeDnsList(listOf("999.1.1.1")))
    }

    @Test
    fun `too few octets is dropped`() {
        assertEquals(emptyList<String>(), NetworkUtils.sanitizeDnsList(listOf("1.2.3")))
    }

    @Test
    fun `duplicates collapse with order preserved`() {
        assertEquals(
            listOf("8.8.8.8", "8.8.4.4"),
            NetworkUtils.sanitizeDnsList(listOf("8.8.8.8", "8.8.4.4", "8.8.8.8")),
        )
    }

    @Test
    fun `four valid entries are capped at two, keeping the first two`() {
        assertEquals(
            listOf("1.1.1.1", "1.0.0.1"),
            NetworkUtils.sanitizeDnsList(listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4")),
        )
    }
}
