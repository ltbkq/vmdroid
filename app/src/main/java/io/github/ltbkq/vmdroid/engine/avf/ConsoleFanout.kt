/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package io.github.ltbkq.vmdroid.engine.avf

import android.annotation.SuppressLint
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.ltbkq.vmdroid.engine.BootStageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream

/**
 * Bridges AVF's console streams ↔ a filesystem unix-domain socket so the
 * existing `libpodroid-bridge.so` subprocess can splice PTY ↔ that socket
 * unchanged. Uses `android.system.Os` directly because Android 14's
 * ServerSocketChannel doesn't expose the ProtocolFamily factory needed for
 * NIO unix sockets, but Os.bind() accepts UnixDomainSocketAddress.
 *
 * AVF requires Android 14+ (Pixel 8+) so the @RequiresApi(34) on this class
 * is never violated at runtime — AvfEngine only constructs ConsoleFanout
 * when AvfDiagnostics says the framework is available.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class ConsoleFanout(
    private val consoleOutput: InputStream,
    private val consoleInput: OutputStream,
    private val socketPath: String,
    private val detector: BootStageDetector,
    private val scope: CoroutineScope,
    /**
     * Optional tee for every byte chunk the VM emits. AvfEngine wires this to
     * console.log + the consoleText flow so AVF gets the same post-mortem
     * surface as QEMU (issue #29: kernel panics under AVF were invisible
     * because the bytes only went to the bridge socket + boot detector).
     */
    private val onVmBytes: ((ByteArray, Int) -> Unit)? = null,
) {
    companion object { private const val TAG = "ConsoleFanout" }

    private var serverFd: FileDescriptor? = null
    /**
     * The currently-connected bridge client, or null when none is connected.
     * Volatile because it's written by the accept loop and read each iteration
     * by the long-lived VM→capture pump on a different thread.
     */
    @Volatile private var clientFd: FileDescriptor? = null
    private val jobs = mutableListOf<Job>()
    @Volatile private var closed = false
    private val lock = Any()

    /** Append a job under the close monitor; if already closed, cancel it at once. */
    private fun track(job: Job) {
        synchronized(lock) {
            if (closed) { runCatching { job.cancel() }; return }
            jobs.add(job)
        }
    }

    @SuppressLint("BlockedPrivateApi") // android.system.UnixSocketAddress is exempt via HiddenApiBypass
    fun start() {
        File(socketPath).delete()  // stale socket blocks bind

        val fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
        // Assign serverFd BEFORE bind/listen so a throw there still lets close()
        // reclaim the socket fd (previously serverFd was set only after listen,
        // leaking the raw fd on every bind/listen failure).
        serverFd = fd
        val addrCls = Class.forName("android.system.UnixSocketAddress")
        val addr = addrCls.getDeclaredMethod("createFileSystem", String::class.java)
            .apply { isAccessible = true }
            .invoke(null, socketPath) as java.net.SocketAddress
        Os.bind(fd, addr)
        // Allow a small backlog so a bridge respawn's connect is queued, not
        // refused, while we re-accept.
        Os.listen(fd, 4)

        // Long-lived VM → (boot detector + console capture + current bridge
        // client) pump. This drains consoleOutput for the VM's whole lifetime so
        // boot-stage detection and console.log capture survive a bridge
        // disconnect/respawn — it does NOT tear the fanout down on a write
        // failure to a transient client (issue #29: capture must outlive the
        // terminal). Only EOF/error on the VM stream itself ends it.
        track(scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = consoleOutput.read(buf)
                    if (n <= 0) break
                    // Tee to capture BEFORE feeding the detector. detector.feed
                    // flips _state Starting→Running on "Ready!", and onVmBytes
                    // only persists while Starting; feeding first would drop the
                    // very chunk carrying "Ready!" (and any late-boot error
                    // batched with it) from console.log. Order guarantees capture
                    // is never LESS than before — it now includes the Ready chunk.
                    onVmBytes?.invoke(buf, n)
                    detector.feed(buf, n)
                    val client = clientFd ?: continue   // no bridge attached → keep capturing
                    var off = 0
                    while (off < n) {
                        val w = try { Os.write(client, buf, off, n - off) } catch (_: Exception) { -1 }
                        if (w <= 0) break   // client gone; the accept loop will swap in the next one
                        off += w
                    }
                }
            } catch (e: Exception) {
                if (!closed) Log.d(TAG, "vm capture pump ended: ${e.message}")
            } finally { close() }   // VM stream itself ended → whole fanout is done
        })

        // Accept loop: service the first bridge connection AND any later
        // reconnect (a bridge crash/respawn). Each client gets a dedicated
        // client→VM pump; when that client disconnects we clear it and loop back
        // to accept the next one rather than tearing the fanout down.
        track(scope.launch(Dispatchers.IO) {
            while (!closed) {
                val client = try {
                    Os.accept(fd, null /* peerAddress out-param */)
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                Log.d(TAG, "bridge connected at $socketPath")
                clientFd = client
                // Pump bridge → VM for this client. Runs inline so the next
                // accept() only happens after this client disconnects (single
                // active bridge at a time, matching the terminal model).
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = Os.read(client, buf, 0, buf.size)
                        if (n <= 0) break
                        consoleInput.write(buf, 0, n)
                        consoleInput.flush()
                    }
                } catch (e: Exception) {
                    if (!closed) Log.d(TAG, "bridge→vm pump ended: ${e.message}")
                }
                // Client disconnected: drop it and re-accept. Do NOT close the
                // fanout — the VM capture pump keeps running.
                if (clientFd === client) clientFd = null
                runCatching { Os.close(client) }
            }
        })
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        // Cancel coroutines and force-close fds so any blocking Os.accept/Os.read
        // throws and the pump threads exit promptly (cancel alone can't interrupt
        // a native blocking read).
        synchronized(lock) {
            jobs.forEach { runCatching { it.cancel() } }
            jobs.clear()
        }
        runCatching { clientFd?.let { Os.close(it) } }
        clientFd = null
        runCatching { serverFd?.let { Os.close(it) } }
        serverFd = null
        // Single owner of the AVF console streams (AvfEngine.cleanup no longer
        // closes them) — close once here.
        runCatching { consoleOutput.close() }
        runCatching { consoleInput.close() }
        runCatching { File(socketPath).delete() }
    }
}
