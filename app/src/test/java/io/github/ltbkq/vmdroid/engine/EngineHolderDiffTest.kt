/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package io.github.ltbkq.vmdroid.engine

import io.github.ltbkq.vmdroid.data.repository.PortForwardRule
import io.github.ltbkq.vmdroid.engine.EngineHolder.Companion.computeRuleDiff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure port-forward diff used by EngineHolder's reconciliation loop.
 *
 * The load-bearing property is the cold-start seeding: when the VM enters
 * Running, appliedRules is seeded from the launch set (the rules already baked
 * into QEMU's cmdline). An unchanged boot must therefore produce no work
 * (added == removed == empty), and a rule removed during the boot window must
 * still be torn down on Running.
 */
class EngineHolderDiffTest {

    private fun rule(host: Int, guest: Int = host) = PortForwardRule(host, guest)

    @Test
    fun `unchanged boot yields no add and no remove`() {
        val launch = setOf(rule(8080), rule(2222, 22))
        // desired == launch set (nothing changed during boot)
        val (added, removed) = computeRuleDiff(applied = launch, desired = launch)
        assertEquals(emptySet<PortForwardRule>(), added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `rule removed mid-boot is torn down`() {
        val launch = setOf(rule(8080), rule(2222, 22))
        val desired = setOf(rule(8080)) // user removed 2222 during boot
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(emptySet<PortForwardRule>(), added)
        assertEquals(setOf(rule(2222, 22)), removed)
    }

    @Test
    fun `rule added mid-boot is applied`() {
        val launch = setOf(rule(8080))
        val desired = setOf(rule(8080), rule(9090)) // user added 9090 during boot
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(setOf(rule(9090)), added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `empty applied re-adds everything (legacy seeding regression guard)`() {
        // The pre-fix seeding (appliedRules = emptySet) re-added every baked-in
        // rule on Running; this documents that contrast so the seeding choice is
        // intentional, not accidental.
        val desired = setOf(rule(8080), rule(2222, 22))
        val (added, removed) = computeRuleDiff(applied = emptySet(), desired = desired)
        assertEquals(desired, added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `user rules kept off the cmdline are applied on the Running edge`() {
        // QEMU bakes only the implicit forwards into its launch cmdline, because
        // SLIRP kills the whole VM over one hostfwd it cannot bind. Everything
        // else has to arrive over QMP instead, which only happens if the →Running
        // seeding reflects what the engine ACTUALLY applied rather than the whole
        // launch set. Seeding it from launchRules here would compute added ==
        // empty and the user's forwards would silently never exist.
        // loopbackOnly is what marks the viewer's pair as app-injected, and it is
        // what keeps them on the cmdline, so the fixture has to carry it.
        val ssh = rule(9922, 22)
        val vnc = PortForwardRule(5900, 5900, "tcp", loopbackOnly = true)
        val audio = PortForwardRule(4713, 4713, "tcp", loopbackOnly = true)
        val userRules = setOf(rule(8080), rule(2121))
        val launch = userRules + setOf(ssh, vnc, audio)
        val appliedAtLaunch = QemuEngine.inlineLaunchRules(launch).toSet()
        val implicit = launch - userRules
        val (added, removed) = computeRuleDiff(applied = appliedAtLaunch, desired = userRules + implicit)
        assertEquals(userRules, added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `implicit always-on forwards are never removed`() {
        // SSH/VNC/audio are injected into the launch set by VmdroidService but
        // never persisted to the DataStore, so they appear in launchRules yet
        // not in `rules`. The diff loop folds them back in (desired = rules +
        // implicit) so they are never computed as removed. Without that fold the
        // first →Running diff tore them down, racing the engine's own initial
        // setup and surfacing as intermittent SSH/VNC/audio dropout.
        val ssh = rule(9922, 22); val vnc = rule(5900); val audio = rule(4713)
        val launch = setOf(ssh, vnc, audio)             // appliedRules at →Running
        val persisted = emptySet<PortForwardRule>()     // DataStore: no user rules
        val implicit = launch - persisted               // captured at →Running edge
        val desired = persisted + implicit              // the fold
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(emptySet<PortForwardRule>(), removed)
        assertEquals(emptySet<PortForwardRule>(), added)
    }

    @Test
    fun `user rule removed mid-session is torn down while implicit forwards survive`() {
        val ssh = rule(9922, 22)
        val user = rule(8000)
        val launch = setOf(ssh, user)
        val implicit = launch - setOf(user)             // = {ssh}, captured at →Running
        // user later deletes 8000 → DataStore now empty
        val desired = emptySet<PortForwardRule>() + implicit
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(setOf(user), removed)              // user rule gone; ssh not removed
        assertEquals(emptySet<PortForwardRule>(), added)
    }
}
