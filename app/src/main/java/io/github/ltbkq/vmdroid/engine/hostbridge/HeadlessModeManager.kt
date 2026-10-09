/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Single source of truth for "server mode" (headless). Both the guest bridge
 * (HEADLESS on/off) and the in-app card toggle this; MainActivity observes it to
 * draw the black overlay. App-scoped singleton so the one instance is shared.
 */
package io.github.ltbkq.vmdroid.engine.hostbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeadlessModeManager @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) { _active.value = value }
}
