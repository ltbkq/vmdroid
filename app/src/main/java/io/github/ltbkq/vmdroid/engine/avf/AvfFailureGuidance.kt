/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure policy: given the current vCPU setting, what should we advise the user
 * when an AVF guest fails to boot? AVF has no vCPU-count API (only ONE_CPU or
 * MATCH_HOST), and some hypervisors (e.g. Tensor G3, issue #29) crash under
 * MATCH_HOST during secondary-vCPU bringup but boot fine with one vCPU.
 * No Android deps so it unit-tests on the JVM.
 */
package io.github.ltbkq.vmdroid.engine.avf

object AvfFailureGuidance {
    enum class Advice { TRY_ONE_CORE, SWITCH_TO_QEMU }

    /** cpus > 1 -> the MATCH_HOST topology may be the cause; suggest one core
     *  first. cpus == 1 -> one core already failed, AVF can't run here. */
    fun advise(cpus: Int): Advice =
        if (cpus > 1) Advice.TRY_ONE_CORE else Advice.SWITCH_TO_QEMU
}
