/*
 * VMDroid - shared image error messages (DESIGN §6.3/§7.4 user-facing text).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Home（§8.2）与 Images（§8.3）两页共用同一套「异常 → 用户文案」映射，
 * 抽出避免两份漂移。非 .img 一律给 mkimg 封装指引（§6.3/§13.2 导入用例）。
 */
package io.github.ltbkq.vmdroid.ui

import android.content.Context
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.systemimage.VmdImageException
import io.github.ltbkq.vmdroid.systemimage.VmdImageReason

/** §6.3/§7.4 异常 → 用户文案。 */
fun imageErrorMessage(context: Context, t: Throwable): String = when (t) {
    is VmdImageException -> imageErrorMessage(context, t.reason, t.message)
    else -> context.getString(R.string.image_err_generic, t.message ?: t.javaClass.simpleName)
}

/** §6.3/§7.4 reason → 用户文案（非 .img 给封装指引）。 */
fun imageErrorMessage(context: Context, reason: VmdImageReason, detail: String?): String = when (reason) {
    VmdImageReason.NOT_AN_IMAGE, VmdImageReason.MANIFEST_INVALID ->
        context.getString(R.string.image_err_not_an_image)
    VmdImageReason.CORRUPT, VmdImageReason.TRUNCATED, VmdImageReason.PAYLOAD_CORRUPT,
    VmdImageReason.IO_ERROR,
    -> context.getString(R.string.image_err_corrupt)
    VmdImageReason.FORMAT_UNSUPPORTED -> context.getString(R.string.image_err_format)
    VmdImageReason.ARCH_MISMATCH -> context.getString(R.string.image_err_arch)
    VmdImageReason.APP_TOO_OLD -> context.getString(R.string.image_err_app_old)
    VmdImageReason.NO_SYSTEM_IMAGE -> context.getString(R.string.image_err_missing)
    else -> context.getString(R.string.image_err_generic, detail ?: reason.name)
}
