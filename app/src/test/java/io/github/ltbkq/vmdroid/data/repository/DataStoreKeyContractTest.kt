/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pins the on-disk DataStore preference key names.
 */
package io.github.ltbkq.vmdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CLAUDE.md: "Persistence is sacred: DataStore keys and filesDir paths survive
 * every release; renames need migration."
 *
 * The key *literal* is the on-disk contract; the Kotlin identifier is not. Renaming
 * `KEY_VM_RAM` is free, but changing `"vm_ram_mb"` orphans the stored value and the
 * setting silently reverts to its default on the user's next launch, on an update
 * that is required to install in place.
 *
 * IF THIS TEST FAILS because you changed a literal: do not edit the expected string
 * to match. Either revert the literal, or keep it and ship a migration that reads the
 * old key and writes the new one, then update the expectation in the same commit.
 *
 * Adding a key is always safe and needs no change here.
 */
class DataStoreKeyContractTest {

    /** Actual key literal paired with the literal this release is contractually stuck with. */
    private val contract: List<Pair<String, String>> = listOf(
        SettingsRepository.KEY_DARK_THEME.name to "dark_theme",
        SettingsRepository.KEY_VM_RAM.name to "vm_ram_mb",
        SettingsRepository.KEY_VM_CPUS.name to "vm_cpus",
        SettingsRepository.KEY_FONT_SIZE.name to "terminal_font_size",
        SettingsRepository.KEY_STORAGE_GB.name to "storage_gb",
        SettingsRepository.KEY_STORAGE_ACCESS_ENABLED.name to "storage_access_enabled",
        SettingsRepository.KEY_SETUP_DONE.name to "setup_done",
        SettingsRepository.KEY_SSH_ENABLED.name to "ssh_enabled",
        SettingsRepository.KEY_TERMINAL_COLOR_THEME.name to "terminal_color_theme",
        SettingsRepository.KEY_TERMINAL_FONT.name to "terminal_font",
        SettingsRepository.KEY_QEMU_EXTRA_ARGS.name to "qemu_extra_args",
        SettingsRepository.KEY_KERNEL_EXTRA_CMDLINE.name to "kernel_extra_cmdline",
        SettingsRepository.KEY_SHOW_EXTRA_KEYS.name to "show_extra_keys",
        SettingsRepository.KEY_HAPTICS_ENABLED.name to "haptics_enabled",
        SettingsRepository.KEY_DYNAMIC_COLOR_ENABLED.name to "dynamic_color_enabled",
        SettingsRepository.KEY_LAST_BOOT_DURATION_MS.name to "last_boot_duration_ms",
        SettingsRepository.KEY_LAST_CONTAINER_COUNT.name to "last_container_count",
        SettingsRepository.KEY_ENGINE_SELECTION.name to "engine_selection",
        SettingsRepository.KEY_AVF_HINT_DISMISSED.name to "avf_hint_dismissed",
        SettingsRepository.KEY_AVF_VERBOSE_LOGGING.name to "avf_verbose_logging",
        SettingsRepository.KEY_AVF_CPU_CAP.name to "avf_cpu_cap",
        SettingsRepository.KEY_USB_PASSTHROUGH_ENABLED.name to "usb_passthrough_enabled",
        SettingsRepository.KEY_LOAD_BALANCE_ENABLED.name to "load_balance_enabled",
        SettingsRepository.KEY_BANDWIDTH_MBPS.name to "bandwidth_mbps",
        SettingsRepository.KEY_X11_RES_MODE.name to "x11_resolution_mode",
        SettingsRepository.KEY_X11_RES_PRESET.name to "x11_resolution_preset",
        SettingsRepository.KEY_X11_CUSTOM_W.name to "x11_custom_w",
        SettingsRepository.KEY_X11_CUSTOM_H.name to "x11_custom_h",
        SettingsRepository.KEY_X11_TOUCH_MODE.name to "x11_touch_mode",
        SettingsRepository.KEY_X11_TP_SENSITIVITY.name to "x11_tp_sensitivity",
        SettingsRepository.KEY_X11_TP_ACCEL.name to "x11_tp_accel",
        SettingsRepository.KEY_X11_FULLSCREEN.name to "x11_fullscreen_default",
        SettingsRepository.KEY_X11_ROTATION.name to "x11_rotation_lock",
        SettingsRepository.KEY_X11_SHOW_EXTRA_KEYS.name to "x11_show_extra_keys",
        SettingsRepository.KEY_X11_DPI.name to "x11_dpi",
        SettingsRepository.KEY_LANGUAGE.name to "language",
        SettingsRepository.KEY_AUTOSTART_ON_BOOT.name to "autostart_on_boot",
    )

    @Test
    fun `persisted settings key names are unchanged`() {
        contract.forEach { (actual, expected) ->
            assertEquals(
                "DataStore key literal changed; a rename without a migration loses this setting",
                expected,
                actual,
            )
        }
    }

    /**
     * Two keys must not collide on one literal: the second write clobbers the first,
     * which surfaces as intermittent setting loss rather than as a bug here.
     */
    @Test
    fun `persisted settings key names are unique`() {
        val names = contract.map { it.first }
        assertEquals(
            "two DataStore keys share a literal; the later write clobbers the earlier",
            names.size,
            names.toSet().size,
        )
    }
}
