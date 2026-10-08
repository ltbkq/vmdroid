/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Unit tests for PortForwardRule pure logic and dedup helper.
 */
package io.github.ltbkq.vmdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PortForwardRule.deserialize must validate protocol and port range.
 * addRule must dedup on (hostPort, protocol) key.
 */
class PortForwardRepositoryTest {

    // ------------------------------------------------------------------
    // deserialize validation
    // ------------------------------------------------------------------

    @Test
    fun `deserialize valid tcp rule round-trips`() {
        val rule = PortForwardRule(8080, 80, "tcp")
        val result = PortForwardRule.deserialize(rule.serialize())
        assertNotNull(result)
        assertEquals(rule, result)
    }

    @Test
    fun `deserialize valid udp rule round-trips`() {
        val rule = PortForwardRule(9922, 22, "udp")
        val result = PortForwardRule.deserialize(rule.serialize())
        assertNotNull(result)
        assertEquals(rule, result)
    }

    @Test
    fun `deserialize rejects unknown protocol`() {
        assertNull(PortForwardRule.deserialize("sctp:8080:80"))
    }

    @Test
    fun `deserialize rejects port zero`() {
        assertNull(PortForwardRule.deserialize("tcp:0:80"))
    }

    @Test
    fun `deserialize rejects guest port zero`() {
        assertNull(PortForwardRule.deserialize("tcp:8080:0"))
    }

    @Test
    fun `deserialize rejects port above 65535`() {
        assertNull(PortForwardRule.deserialize("tcp:65536:80"))
    }

    @Test
    fun `deserialize rejects guest port above 65535`() {
        assertNull(PortForwardRule.deserialize("tcp:8080:65536"))
    }

    @Test
    fun `deserialize rejects negative port`() {
        assertNull(PortForwardRule.deserialize("tcp:-1:80"))
    }

    @Test
    fun `deserialize allows boundary port 1`() {
        assertNotNull(PortForwardRule.deserialize("tcp:1:1"))
    }

    @Test
    fun `deserialize allows boundary port 65535`() {
        assertNotNull(PortForwardRule.deserialize("tcp:65535:65535"))
    }

    @Test
    fun `deserialize rejects malformed string with wrong part count`() {
        assertNull(PortForwardRule.deserialize("tcp:8080"))
        assertNull(PortForwardRule.deserialize("tcp:8080:80:extra"))
    }

    // ------------------------------------------------------------------
    // (hostPort, protocol) dedup helper
    // ------------------------------------------------------------------

    @Test
    fun `deduplicateByKey removes existing entry with same hostPort and protocol`() {
        val existing = setOf(
            PortForwardRule(8080, 80, "tcp").serialize(),
            PortForwardRule(9922, 22, "tcp").serialize(),
        )
        val newRule = PortForwardRule(8080, 443, "tcp")
        val result = deduplicatePortForwards(existing, newRule)
        // Old tcp:8080:80 gone; new tcp:8080:443 present; 9922 untouched.
        assertEquals(2, result.size)
        assert(newRule.serialize() in result) { "new rule should be in result" }
        assert(PortForwardRule(9922, 22, "tcp").serialize() in result) { "unrelated rule should remain" }
        assert(PortForwardRule(8080, 80, "tcp").serialize() !in result) { "old rule should be removed" }
    }

    @Test
    fun `deduplicateByKey keeps different protocol on same host port`() {
        val existing = setOf(
            PortForwardRule(8080, 80, "tcp").serialize(),
        )
        val newRule = PortForwardRule(8080, 80, "udp")
        val result = deduplicatePortForwards(existing, newRule)
        assertEquals(2, result.size)
        assert(PortForwardRule(8080, 80, "tcp").serialize() in result)
        assert(newRule.serialize() in result)
    }

    @Test
    fun `deduplicateByKey adds new rule when no conflict`() {
        val existing = setOf(
            PortForwardRule(9922, 22, "tcp").serialize(),
        )
        val newRule = PortForwardRule(8080, 80, "tcp")
        val result = deduplicatePortForwards(existing, newRule)
        assertEquals(2, result.size)
    }

    @Test
    fun `deduplicateByKey handles empty set`() {
        val newRule = PortForwardRule(8080, 80, "tcp")
        val result = deduplicatePortForwards(emptySet<String>(), newRule)
        assertEquals(setOf(newRule.serialize()), result)
    }

    // ------------------------------------------------------------------
    // table size limit
    //
    // Unbounded growth is how one user ended up with 1005 persisted rules,
    // which is both unusable in the UI and a thousand QMP round trips on every
    // boot. The limit is a guard rail on a scripted loop, not on human use.
    // ------------------------------------------------------------------

    private fun tableOf(n: Int): Set<String> =
        (1..n).map { PortForwardRule(20000 + it, 20000 + it, "tcp").serialize() }.toSet()

    @Test
    fun `a table below the limit accepts a new rule`() {
        val full = portForwardTableIsFull(tableOf(MAX_PORT_FORWARD_RULES - 1), PortForwardRule(9000, 9000))
        assertEquals(false, full)
    }

    @Test
    fun `a full table rejects a new host port`() {
        val full = portForwardTableIsFull(tableOf(MAX_PORT_FORWARD_RULES), PortForwardRule(9000, 9000))
        assertEquals(true, full)
    }

    @Test
    fun `a full table still accepts a rule that replaces an existing one`() {
        // Re-pointing an existing (hostPort, protocol) is not growth, and refusing
        // it would leave a user at the limit unable to correct a wrong rule.
        val existing = tableOf(MAX_PORT_FORWARD_RULES)
        val replacement = PortForwardRule(20001, 40001, "tcp")
        assertEquals(false, portForwardTableIsFull(existing, replacement))
        assertEquals(MAX_PORT_FORWARD_RULES, deduplicatePortForwards(existing, replacement).size)
    }

    @Test
    fun `a full table accepts the other protocol on a used host port`() {
        // (hostPort, protocol) is the key, so udp on a tcp port is a NEW rule and
        // must be refused at the limit rather than silently overwriting the tcp one.
        val existing = tableOf(MAX_PORT_FORWARD_RULES)
        assertEquals(true, portForwardTableIsFull(existing, PortForwardRule(20001, 20001, "udp")))
    }
}
