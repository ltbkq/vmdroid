/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Samples emulator CPU load from /proc/[pid]/stat for the Status load graph.
 */
package io.github.ltbkq.vmdroid.util

import android.os.SystemClock
import java.io.File

/**
 * Stateful sampler: call [sampleCpuPercent] every ~2s with the emulator PID.
 * Returns null on the first sample (no delta yet) or when /proc is unreadable.
 */
class VmLoadSampler(
    private val clockHz: Long = 100L,
    // Injected for tests; production reads the real monotonic clock and /proc.
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val ticksReader: (Int) -> Long? = { readProcessCpuTicks(it) },
) {
    private var lastTicks: Long? = null
    private var lastTimeMs: Long? = null

    fun reset() {
        lastTicks = null
        lastTimeMs = null
    }

    /**
     * CPU load as 0–100% of the VM's allocated vCPU count (QEMU process on Android).
     */
    fun sampleCpuPercent(pid: Int, vmCpus: Int): Float? {
        val ticks = ticksReader(pid) ?: return null
        val now = nowMs()
        val prevTicks = lastTicks
        val prevTime = lastTimeMs
        lastTicks = ticks
        lastTimeMs = now
        if (prevTicks == null || prevTime == null) return null

        val deltaTicks = (ticks - prevTicks).coerceAtLeast(0)
        val deltaSec = (now - prevTime) / 1000.0
        if (deltaSec <= 0.0) return null

        val coresUsed = (deltaTicks / clockHz.toDouble()) / deltaSec
        val pct = (coresUsed / vmCpus.coerceAtLeast(1) * 100.0).toFloat()
        return pct.coerceIn(0f, 100f)
    }

    companion object {
        const val MAX_SAMPLES = 60

        fun readProcessCpuTicks(pid: Int): Long? = try {
            val after = File("/proc/$pid/stat").readText().substringAfter(") ")
            val fields = after.split(Regex("\\s+"))
            if (fields.size < 13) null
            else fields[11].toLong() + fields[12].toLong()
        } catch (_: Exception) {
            null
        }
    }
}
