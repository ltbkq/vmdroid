/*
 * VMDroid - system image repository (fork of Podroid).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Resolves the currently ACTIVE system image — the read-only vdb disk the
 * engines attach (DESIGN §7.1/§7.2/§7.4). The APK no longer bundles a rootfs:
 * systems live under `filesDir/images/` and the active one is selected via
 * `filesDir/images/active.json`.
 *
 * **分层（M3）**：本文件是 **Android 薄壳**（Context / PackageManager / logcat /
 * Hilt 注入）；安装、激活、删除、校验、`active.json` 解析等全部纯逻辑在
 * `SystemImageStore.kt`（纯 JVM，可脱离 Android SDK 用 kotlinc 编译并由
 * `systemimage-selftest/` 自测）。BootGuard 主判定见 `BootGuard.kt`（§7.4）。
 *
 * v1 状态：`list/active/install/activate/delete/verify` + BootGuard 已接线；
 * 下载（§6.2 断点续传）与 SAF 选择器 UI 由后续里程碑接入。
 */
package io.github.ltbkq.vmdroid.systemimage

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injection point of the image manager.
 *
 * `active.json` is written by [activate] only (DESIGN §2.3/§5.3 — frozen field set
 * `{image_id, identity, rootfs_sha256, activated_at}`; this reader is also
 * tolerant of an optional `path` spelling so early writers and the spec sketch in
 * §7.1 both work). Only `image_id` is required; the file path defaults to
 * `images/<image_id>.img` under `filesDir`.
 *
 * Constructor-injected by Hilt (no module needed) so both engines and the service
 * can take it as a plain constructor dependency — `EngineModule`/`EngineHolder`
 * are untouched (DESIGN §3: engine routing kept as-is).
 */
@Singleton
class SystemImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * 单写线程的 `image.log`（§16.3/§16.5）。进程内唯一实例 —— store 的生命周期
     * 事件与 service 的 `bootguard_reject` 都汇到这里。
     */
    val imageLog: ImageLog by lazy {
        ImageLog(
            File(context.filesDir, IMAGE_LOG_FILE),
            warn = { msg, t -> Log.w(TAG, msg, t) },
        )
    }

    /** 纯逻辑核心（BootGuard/自测也直接用它；`filesDir` 注入）。 */
    val store: SystemImageStore by lazy {
        SystemImageStore(
            filesDir = context.filesDir,
            versionCode = { versionCode() },
            events = ImageEventSink { event, fields ->
                imageLog.record(event, fields, sync = event in ImageLog.SYNC_EVENTS)
            },
        )
    }

    /** §7.4 主判定入口（service 启动前调用；引擎内 fail-fast 是兜底）。 */
    fun bootGuard(): BootGuard = BootGuard(store, File(context.filesDir, STORAGE_FILE))

    // ---------------------------------------------------------------- 激活读侧

    /**
     * Absolute path of the active system image file, or `null` when there is
     * no activation record, the record is unusable, or the referenced file is
     * gone. Never returns a path that does not exist — callers rely on that to
     * fail fast rather than launching a VM with no vdb.
     */
    fun activeRootfsPath(): File? {
        val active = active() ?: return null
        if (!active.idValid) {
            Log.w(TAG, "NO_SYSTEM_IMAGE: rejecting malformed image_id '${active.imageId}' in active.json")
            return null
        }
        val file = File(store.imagesDir, "${active.imageId}.img")
        if (!file.isFile) {
            Log.w(
                TAG,
                "NO_SYSTEM_IMAGE: active image '${active.imageId}' points at a missing file " +
                    "(${file.absolutePath})",
            )
            return null
        }
        return file
    }

    /** `image_id` of the active system image, or `null` when none is active. */
    fun activeImageId(): String? = active()?.imageId

    /** Parsed `active.json` record, or `null` when there is none (§2.3). */
    fun active(): SystemImageStore.ActiveRecord? = store.active()

    // ---------------------------------------------------------------- 生命周期（§5.1/§5.3/§6.3/§6.4）

    /** 已安装镜像列表（§5.1 `INSTALLED`）。 */
    fun list(): List<SystemImageStore.InstalledImage> = store.list()

    /**
     * 流式安装（§6.3 手动导入 / §6.2 下载收尾）：`.part` → sha256 → footer/manifest
     * 校验 → 原子 rename → `.meta.json`。异常见 [VmdImageException]。
     */
    fun install(
        input: InputStream,
        targetId: String? = null,
        expectedSha256: String? = null,
        src: String = "import",
        onProgress: ((Long) -> Unit)? = null,
    ): SystemImageStore.InstalledImage =
        store.install(input, targetId, expectedSha256, src, onProgress)

    /**
     * 激活（§5.3）。**先算 [VmdImageCodec.decideReset]**：需要重置时返回
     * [SystemImageStore.ActivateResult.ResetRequired] 信号（不写 `active.json`、
     * 不清 `storage.img`），由 UI 走 §8.5 对话框确认并重建数据盘后，再以
     * `allowReset = true` 重新调用。
     */
    fun activate(imageId: String, allowReset: Boolean = false): SystemImageStore.ActivateResult =
        store.activate(imageId, allowReset)

    /** 删除镜像 + meta（激活镜像被删时同时移除 `active.json`，§5.3）。 */
    fun delete(imageId: String, src: String = "manual"): Boolean = store.delete(imageId, src)

    /**
     * §6.4 校验：`full=false` 启动前只比 `size`+`mtime`；`full=true` 手动全量重算。
     */
    fun verify(imageId: String, full: Boolean = false): SystemImageStore.VerifyResult =
        store.verify(imageId, full)

    private fun versionCode(): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi)
    } catch (e: Exception) {
        Log.w(TAG, "cannot read versionCode, falling back to 1", e)
        1L
    }

    companion object {
        private const val TAG = "SystemImageRepo"
        const val IMAGES_DIR = SystemImageStore.IMAGES_DIR
        const val ACTIVE_FILE = SystemImageStore.ACTIVE_FILE
        const val IMAGE_LOG_FILE = "image.log"
        const val STORAGE_FILE = "storage.img"
    }
}
