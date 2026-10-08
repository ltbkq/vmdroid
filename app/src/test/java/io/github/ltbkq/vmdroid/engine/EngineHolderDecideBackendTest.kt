/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package io.github.ltbkq.vmdroid.engine

import io.github.ltbkq.vmdroid.engine.EngineHolder.Companion.FallbackReason
import io.github.ltbkq.vmdroid.engine.EngineHolder.Companion.decideBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure backend-selection decision extracted from EngineHolder.pick()
 * (#66). The decision itself must never change here - only its exposure as a
 * testable function - so these cases mirror pick()'s old inline `when`
 * branch-for-branch.
 */
class EngineHolderDecideBackendTest {

    @Test
    fun `auto selection with usable AVF picks avf and no fallback`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AUTO,
            avfUsable = true,
            protectedOnly = false,
        )
        assertEquals("avf", backendId)
        assertNull(reason)
    }

    @Test
    fun `auto selection with unusable AVF picks qemu and no fallback`() {
        // AUTO never surfaces a fallback reason: falling through to QEMU is the
        // normal AUTO behavior, not a forced selection that failed.
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AUTO,
            avfUsable = false,
            protectedOnly = false,
        )
        assertEquals("qemu", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced avf with unusable AVF falls back to qemu with a reason`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = false,
            protectedOnly = false,
        )
        assertEquals("qemu", backendId)
        assertEquals(FallbackReason.UNAVAILABLE, reason)
    }

    @Test
    fun `forced avf with usable AVF picks avf and no fallback`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = true,
            protectedOnly = false,
        )
        assertEquals("avf", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced qemu always picks qemu regardless of AVF usability`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.QEMU,
            avfUsable = true,
            protectedOnly = true,
        )
        assertEquals("qemu", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced avf on a protected-only device gets the protected-only reason`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = false,
            protectedOnly = true,
        )
        assertEquals("qemu", backendId)
        assertEquals(FallbackReason.PROTECTED_ONLY, reason)
    }
}
