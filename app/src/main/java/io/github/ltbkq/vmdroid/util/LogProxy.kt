/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Tiny shim for the seven log methods every Termux TerminalSessionClient and
 * TerminalViewClient implementation has to override. Both QemuEngine's proxy
 * client and TerminalViewModel's clients used to stamp the same boilerplate
 * twice — this is the single source of truth.
 */
package io.github.ltbkq.vmdroid.util

import android.util.Log

object LogProxy {
    fun error(tag: String?, fallback: String, msg: String?) { Log.e(tag ?: fallback, msg ?: "") }
    fun warn(tag: String?, fallback: String, msg: String?)  { Log.w(tag ?: fallback, msg ?: "") }
    fun info(tag: String?, fallback: String, msg: String?)  { Log.i(tag ?: fallback, msg ?: "") }
    fun debug(tag: String?, fallback: String, msg: String?) { Log.d(tag ?: fallback, msg ?: "") }
    fun verbose(tag: String?, fallback: String, msg: String?) { Log.v(tag ?: fallback, msg ?: "") }
    fun stackTraceWithMessage(tag: String?, fallback: String, msg: String?, e: Exception?) {
        Log.e(tag ?: fallback, msg, e)
    }
    fun stackTrace(tag: String?, fallback: String, e: Exception?) {
        Log.e(tag ?: fallback, "Stack trace", e)
    }
}
