/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package io.github.ltbkq.vmdroid.engine

import io.github.ltbkq.vmdroid.data.repository.PortForwardRule
import io.github.ltbkq.vmdroid.engine.QemuEngine.Companion.inlineLaunchRules
import io.github.ltbkq.vmdroid.x11.X11Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which port forwards are allowed onto QEMU's launch command line.
 *
 * This is a safety boundary, not a style choice. SLIRP aborts the whole launch
 * when any single hostfwd cannot be set up, and the rules are persisted, so
 * every rule inlined here is a rule that can permanently stop the VM from
 * starting. That is not hypothetical: a user who added 1005 forwards, most of
 * them inside Android's ephemeral port range where something is always already
 * bound, was left with a VM that could never boot again and concluded it had
 * destroyed their data.
 *
 * User rules therefore go in over QMP once the VM is up, where a rule that
 * cannot bind is one failed rule instead of a dead VM.
 */
class QemuLaunchRulesTest {

    private fun user(host: Int, guest: Int = host) = PortForwardRule(host, guest)

    private val vnc = PortForwardRule(
        X11Constants.VNC_PORT, X11Constants.VNC_PORT, "tcp", loopbackOnly = true,
    )
    private val audio = PortForwardRule(
        X11Constants.AUDIO_PORT, X11Constants.AUDIO_PORT, "tcp", loopbackOnly = true,
    )
    private val ssh = PortForwardRule(9922, 22)

    @Test
    fun `user rules never reach the launch command line`() {
        val inline = inlineLaunchRules(listOf(user(8080), user(2121), user(30000)))
        assertEquals(emptyList<PortForwardRule>(), inline)
    }

    @Test
    fun `the in-app viewer forwards stay inline`() {
        // The viewer dials these the moment the VM is Running, so they must be
        // live at that instant rather than after a round of QMP calls.
        val inline = inlineLaunchRules(listOf(vnc, audio, user(8080)))
        assertEquals(listOf(vnc, audio), inline)
    }

    @Test
    fun `ssh stays inline so a wrecked forward table is still recoverable`() {
        // If everything else fails to apply, the user must still be able to get
        // into the guest and undo whatever they did.
        val inline = inlineLaunchRules(listOf(user(8080), ssh))
        assertEquals(listOf(ssh), inline)
    }

    @Test
    fun `a thousand user rules collapse to the implicit ones`() {
        // The exact shape of the report: seq 30000 31000 through podroid-forward,
        // plus the three the app injects itself.
        val flood = (30000..31000).map { user(it) }
        val inline = inlineLaunchRules(flood + listOf(ssh, vnc, audio))
        assertEquals(listOf(ssh, vnc, audio), inline)
        assertTrue("1001 user rules must not reach the cmdline", inline.size == 3)
    }

    @Test
    fun `an empty set stays empty`() {
        assertEquals(emptyList<PortForwardRule>(), inlineLaunchRules(emptyList()))
    }
}
