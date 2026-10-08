/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * TerminalSession subclass that fires a (rows, cols) callback every time
 * the host adjusts the PTY winsize. Used by AvfEngine to tap into resize
 * events without modifying the vendored terminal-emulator module.
 *
 * The base updateSize signature is (columns, rows, cellWidth, cellHeight) —
 * matching JNI.setPtyWindowSize's argument order.
 */
package io.github.ltbkq.vmdroid.engine

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class ResizeNotifyingSession(
    shellPath: String,
    cwd: String,
    args: Array<String>,
    env: Array<String>?,
    transcriptRows: Int,
    client: TerminalSessionClient,
    private val onResize: (rows: Int, cols: Int) -> Unit,
) : TerminalSession(shellPath, cwd, args, env, transcriptRows, client) {
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        super.updateSize(columns, rows, cellWidthPixels, cellHeightPixels)
        runCatching { onResize(rows, columns) }
    }
}
