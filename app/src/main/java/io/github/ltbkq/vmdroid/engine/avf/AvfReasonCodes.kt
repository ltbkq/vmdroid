/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Decodes android.system.virtualmachine.VirtualMachineCallback STOP_REASON_*
 * and ERROR_* integer codes to their constant names. Values verified against
 * the official AOSP source. Pure (no Android deps) so it unit-tests on the JVM.
 */
package io.github.ltbkq.vmdroid.engine.avf

object AvfReasonCodes {
    private val STOP = mapOf(
        -1 to "STOP_REASON_VIRTUALIZATION_SERVICE_DIED",
        0 to "STOP_REASON_INFRASTRUCTURE_ERROR",
        1 to "STOP_REASON_KILLED",
        2 to "STOP_REASON_UNKNOWN",
        3 to "STOP_REASON_SHUTDOWN",
        4 to "STOP_REASON_START_FAILED",
        5 to "STOP_REASON_REBOOT",
        6 to "STOP_REASON_CRASH",
        7 to "STOP_REASON_PVM_FIRMWARE_PUBLIC_KEY_MISMATCH",
        8 to "STOP_REASON_PVM_FIRMWARE_INSTANCE_IMAGE_CHANGED",
        9 to "STOP_REASON_BOOTLOADER_PUBLIC_KEY_MISMATCH",
        10 to "STOP_REASON_BOOTLOADER_INSTANCE_IMAGE_CHANGED",
        11 to "STOP_REASON_MICRODROID_FAILED_TO_CONNECT_TO_VIRTUALIZATION_SERVICE",
        12 to "STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED",
        13 to "STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED",
        14 to "STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG",
        15 to "STOP_REASON_MICRODROID_UNKNOWN_RUNTIME_ERROR",
        16 to "STOP_REASON_HANGUP",
    )
    private val ERROR = mapOf(
        0 to "ERROR_UNKNOWN",
        1 to "ERROR_PAYLOAD_VERIFICATION_FAILED",
        2 to "ERROR_PAYLOAD_CHANGED",
        3 to "ERROR_PAYLOAD_INVALID_CONFIG",
    )

    fun stopReason(code: Int): String = STOP[code]?.let { "$code $it" } ?: "$code (unmapped)"
    fun errorCode(code: Int): String = ERROR[code]?.let { "$code $it" } ?: "$code (unmapped)"
}
