/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Length-prefixed framing for UDP datagrams tunneled over the AVF vsock stream.
 * Each frame is [u16 big-endian length][payload]. Must stay byte-compatible with
 * udp_relay() in build-rootfs/vsock-agent/podroid-vsock-agent.c. Pure (no
 * android.*) so it unit-tests on the JVM.
 */
package io.github.ltbkq.vmdroid.engine.avf

import java.io.IOException
import java.io.InputStream

internal object DatagramFraming {
    /** Max UDP payload that fits a u16 length prefix. */
    const val MAX_PAYLOAD = 65535

    /** Frames the first [length] bytes of [payload] as [u16 len][payload]. */
    fun encode(payload: ByteArray, length: Int): ByteArray {
        require(length in 0..MAX_PAYLOAD) { "datagram length $length out of range" }
        require(length <= payload.size) { "length $length exceeds payload size ${payload.size}" }
        val out = ByteArray(2 + length)
        out[0] = ((length ushr 8) and 0xff).toByte()
        out[1] = (length and 0xff).toByte()
        System.arraycopy(payload, 0, out, 2, length)
        return out
    }

    /**
     * Reads exactly one framed datagram, blocking until the full frame arrives.
     * Returns the payload, null on clean EOF at a frame boundary, or throws
     * IOException on EOF mid-frame (truncated).
     */
    fun readFrame(input: InputStream): ByteArray? {
        val hi = input.read()
        if (hi < 0) return null
        val lo = input.read()
        if (lo < 0) throw IOException("EOF in frame length")
        val len = (hi shl 8) or lo
        // A zero-length datagram is valid UDP and intentionally round-trips as len=0.
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("EOF in frame payload ($off/$len)")
            off += n
        }
        return buf
    }
}
