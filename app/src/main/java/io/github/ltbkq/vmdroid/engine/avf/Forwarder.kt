/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Common lifecycle for the AVF per-rule forwarders so AvfEngine can hold TCP
 * stream forwarders and UDP datagram forwarders in one map keyed by vsock port.
 */
package io.github.ltbkq.vmdroid.engine.avf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal interface Forwarder {
    fun start()
    fun close()
}

/**
 * Shared dispatcher for every forwarder's blocking accept()/receive()/reaper
 * loop (VsockPortForwarder's accept loop, VsockUdpForwarder's receive and
 * reaper loops). `Dispatchers.IO` caps at 64 threads; one rule parks one
 * thread there for its whole lifetime, so past 64 rules the blocking loops
 * alone would exhaust the pool and starve every other IO user in the app.
 * A cached thread pool has no such cap - idle threads are reclaimed, busy
 * ones aren't capped.
 *
 * Per-connection/per-flow pump coroutines are NOT moved here: their
 * concurrency is bounded by real traffic (MAX_INFLIGHT / MAX_FLOWS) rather
 * than by rule count, so `Dispatchers.IO` remains the right fit for them.
 *
 * Lazily created on first forwarder start, shut down by AvfEngine.cleanup()
 * alongside the forwarders it backs; the next VM start recreates it on demand.
 */
internal object AvfForwarderDispatcher {
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var dispatcher: CoroutineDispatcher? = null

    /**
     * Obtains the shared dispatcher and launches [block] on [scope] with it
     * as a single atomic step. A forwarder's start() races against a
     * concurrent AvfEngine.cleanup() (EngineHolder's rule-diff collector can
     * still be mid-loop calling addPortForward while a VM stop flips state
     * and runs cleanup() on another thread); reading the dispatcher and
     * submitting to it under two separate locks would let shutdown() land in
     * between - grabbing a dispatcher instance right as it's torn down, or
     * silently spinning up a fresh executor after shutdown() already ran,
     * which would then never get reaped. Sharing [shutdown]'s lock across
     * both steps closes that window.
     */
    fun launch(
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = synchronized(this) {
        val d = dispatcher ?: Executors.newCachedThreadPool { r ->
            Thread(r, "avf-forwarder-io").apply { isDaemon = true }
        }.let { newExecutor ->
            executor = newExecutor
            newExecutor.asCoroutineDispatcher().also { dispatcher = it }
        }
        scope.launch(d + context, block = block)
    }

    fun shutdown() {
        synchronized(this) {
            executor?.shutdownNow()
            executor = null
            dispatcher = null
        }
    }
}
