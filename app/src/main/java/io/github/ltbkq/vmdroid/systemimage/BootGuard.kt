/*
 * VMDroid - BootGuard (DESIGN §7.4 authoritative boot checklist).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN.md §7.4（校验清单权威定义：11 条 + reason 枚举 + 调用点）
 *   - docs/DESIGN.md §6.4（校验时机：启动前 size/mtime 快速路径，手动才全量）
 *   - docs/IMAGE-FORMAT.md §5（读取算法：footer/manifest 语义校验）
 *
 * **调用点（R3: A-R3-2 统一）**：主判定在 `VmdroidService.start()`（启动前拦截）；
 * 引擎内 `failFastNoImage()`（QemuEngine/AvfEngine）只是兜底 fail-fast —— 两处共用
 * 本清单，禁止散落再造（R3: A-R3-3）。
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 编译，
 * 由 systemimage-selftest/SelfTest.kt 逐条覆盖 11 个 reason 的触发样本与通过样本。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File

/**
 * DESIGN §7.4 权威 `reason` 枚举（**唯一来源**；写 `image.log` 的
 * `bootguard_reject.reason` 时用 [Verdict.reasonName]，"勿另造 `NO_IMAGE` 之类别名"）。
 *
 * | # | 条件 | reason |
 * |---|---|---|
 * | 1 | 未安装镜像 | NO_SYSTEM_IMAGE |
 * | 2 | `.meta.json` 缺失/损坏，或 size/mtime 不一致且全量重算失败 | CORRUPT |
 * | 3 | footer magic 不符 / 裸 squashfs / 非本格式 | NOT_AN_IMAGE |
 * | 4 | `format_version > 1` / `footer_size != 4096` / 未知 flags | FORMAT_UNSUPPORTED |
 * | 5 | `app.min_version_code > versionCode` | APP_TOO_OLD |
 * | 6 | `arch != arm64` | ARCH_MISMATCH |
 * | 7 | `image.id` 不匹配正则（防 `-drive` 注入） | IMAGE_ID_INVALID |
 * | 8 | `capabilities.ssh == false` | SSH_CAPABILITY_MISSING |
 * | 9 | `accounts.ssh_port != 22` | SSH_PORT_INVALID |
 * | 10 | 激活时 identity/contract/init 判据不通过 | RESET_REQUIRED |
 * | 11 | `storage.img` 缺失 | （**非拒绝**：`ensureStorageImage()` 正常创建） |
 */
enum class BootGuardReason {
    NO_SYSTEM_IMAGE,
    CORRUPT,
    NOT_AN_IMAGE,
    FORMAT_UNSUPPORTED,
    APP_TOO_OLD,
    ARCH_MISMATCH,
    IMAGE_ID_INVALID,
    SSH_CAPABILITY_MISSING,
    SSH_PORT_INVALID,
    RESET_REQUIRED;

    /** 写 `image.log` 用（= 枚举名；与 §7.4 表逐字一致）。 */
    val reasonName: String get() = name
}

/**
 * BootGuard 判定结果。
 *
 * @param ok true = 允许启动/激活
 * @param reason 拒绝原因（§7.4 枚举；`ok=true` 时恒 null）
 * @param detail 供 logcat / `image.log` 的可读细节
 * @param storagePresent `storage.img` 是否存在（§7.4 #11：**仅信息**，不参与 ok）
 */
data class BootGuardVerdict(
    val ok: Boolean,
    val reason: BootGuardReason? = null,
    val detail: String = "",
    val storagePresent: Boolean = true,
) {
    /** `reason` 的字符串形式（`image.log` 的 `bootguard_reject.reason` 字段）。 */
    val reasonName: String? get() = reason?.name

    companion object {
        fun pass(detail: String, storagePresent: Boolean = true): BootGuardVerdict =
            BootGuardVerdict(true, null, detail, storagePresent)

        fun reject(reason: BootGuardReason, detail: String, storagePresent: Boolean = true): BootGuardVerdict =
            BootGuardVerdict(false, reason, detail, storagePresent)
    }
}

/**
 * §7.4 主判定入口。包装 [SystemImageStore]（纯逻辑）+ `storage.img` 路径。
 *
 * 时序（DESIGN §16.2）：`VmdroidService.start()` → [checkBeforeStart] 通过 →
 * 日志轮转 ①–④ → `engine.start()`。拒绝时调用方写
 * `image.log` 的 `bootguard_reject` 并中止启动（引擎内兜底是第二道防线）。
 */
class BootGuard(
    private val store: SystemImageStore,
    private val storageImage: File,
) {
    /**
     * 启动前主判定（§7.4 #1–#9、#11；#10 属激活时机 → [checkForActivation]）。
     *
     * 快速路径（§6.4）：`size`+`mtime` 与 `.meta.json` 一致 → 只读 footer+manifest
     * 做语义校验（#3–#9），**不重算** 300MB payload sha256；不一致 → 全量重算，
     * 失败按 #2 判 `CORRUPT`。
     */
    fun checkBeforeStart(): BootGuardVerdict {
        val storagePresent = storageImage.isFile
        val active = store.active()
            ?: return BootGuardVerdict.reject(
                BootGuardReason.NO_SYSTEM_IMAGE,
                "no activation record (images/${SystemImageStore.ACTIVE_FILE}) — import or download an image first",
                storagePresent,
            )
        if (!active.idValid) {
            return BootGuardVerdict.reject(
                BootGuardReason.IMAGE_ID_INVALID,
                "active image_id '${active.imageId}' does not match ${SystemImageStore.IMAGE_ID_PATTERN}",
                storagePresent,
            )
        }
        return when (val check = store.checkActivation(active.imageId)) {
            is SystemImageStore.ActivationCheck.Rejected -> BootGuardVerdict.reject(
                reasonOf(check.reason),
                check.detail,
                storagePresent,
            )
            is SystemImageStore.ActivationCheck.Eligible -> BootGuardVerdict.pass(
                "active image '${active.imageId}' ok (decision=${check.decision.code}, " +
                    "quick=${store.quickVerify(File(store.imagesDir, "${active.imageId}.img"), store.readMeta(active.imageId))})",
                storagePresent,
            )
        }
    }

    /**
     * 激活前判定（§7.4 #3–#10 + #11）。#10 `RESET_REQUIRED` 由 §5.2
     * `decideReset` 判出 —— 调用方（[SystemImageStore.activate]）同样据此返回
     * `RESET_REQUIRED` **信号**而不是默默清数据。
     */
    fun checkForActivation(imageId: String): BootGuardVerdict {
        val storagePresent = storageImage.isFile
        if (!SystemImageStore.IMAGE_ID_REGEX.matches(imageId)) {
            return BootGuardVerdict.reject(
                BootGuardReason.IMAGE_ID_INVALID,
                "image_id '$imageId' does not match ${SystemImageStore.IMAGE_ID_PATTERN}",
                storagePresent,
            )
        }
        if (!File(store.imagesDir, "$imageId.img").isFile) {
            return BootGuardVerdict.reject(
                BootGuardReason.NO_SYSTEM_IMAGE,
                "image not installed: $imageId",
                storagePresent,
            )
        }
        return when (val check = store.checkActivation(imageId)) {
            is SystemImageStore.ActivationCheck.Rejected -> BootGuardVerdict.reject(
                reasonOf(check.reason),
                check.detail,
                storagePresent,
            )
            is SystemImageStore.ActivationCheck.Eligible ->
                if (check.decision.requiresReset()) {
                    BootGuardVerdict.reject(
                        BootGuardReason.RESET_REQUIRED,
                        "decision=${check.decision.code}: identity/contract/init changed — storage.img must be rebuilt (§8.5 dialog)",
                        storagePresent,
                    )
                } else {
                    BootGuardVerdict.pass("decision=${check.decision.code}", storagePresent)
                }
        }
    }

    /**
     * `image.log` 的 `bootguard_reject` 行字段（§16.5：`reason` 必须取 §7.4 枚举）。
     * 拒绝时**必须**调用（§7.4 表下注）。
     */
    fun rejectFields(verdict: BootGuardVerdict): Map<String, Any?> = mapOf(
        "reason" to verdict.reasonName,
        "detail" to verdict.detail,
        "storage_present" to verdict.storagePresent,
    )

    private fun reasonOf(reason: VmdImageReason): BootGuardReason = when (reason.bootGuard) {
        "NO_SYSTEM_IMAGE" -> BootGuardReason.NO_SYSTEM_IMAGE
        "CORRUPT" -> BootGuardReason.CORRUPT
        "NOT_AN_IMAGE" -> BootGuardReason.NOT_AN_IMAGE
        "FORMAT_UNSUPPORTED" -> BootGuardReason.FORMAT_UNSUPPORTED
        "APP_TOO_OLD" -> BootGuardReason.APP_TOO_OLD
        "ARCH_MISMATCH" -> BootGuardReason.ARCH_MISMATCH
        "IMAGE_ID_INVALID" -> BootGuardReason.IMAGE_ID_INVALID
        "SSH_CAPABILITY_MISSING" -> BootGuardReason.SSH_CAPABILITY_MISSING
        "SSH_PORT_INVALID" -> BootGuardReason.SSH_PORT_INVALID
        "RESET_REQUIRED" -> BootGuardReason.RESET_REQUIRED
        // codec 细分码没有独立的 §7.4 条目（截断/段损坏/IO）→ #2 CORRUPT
        else -> BootGuardReason.CORRUPT
    }
}
