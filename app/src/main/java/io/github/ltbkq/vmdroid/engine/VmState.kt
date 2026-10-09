/*
 * Vmdroid
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package io.github.ltbkq.vmdroid.engine

/**
 * Lifecycle state of the QEMU VM.
 *
 * ```
 *   Idle ──► Starting ──► Running
 *                ▲           │
 *                │           ▼
 *              Error      Stopped
 * ```
 */
sealed class VmState {
    data object Idle     : VmState()
    data object Starting : VmState()
    data object Running  : VmState()
    data object Stopped  : VmState()
    data class  Error(val message: String) : VmState()
}
