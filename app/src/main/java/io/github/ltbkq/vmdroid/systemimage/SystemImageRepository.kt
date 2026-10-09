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
 * SAF 导入与 §8.2 选择控件 / §8.5 对话框的 UI 接线在 Home（`HomeViewModel`）；
 * 下载（§6.2 断点续传 + 通知进度 + 自动续传）已接入（M4），目录获取见
 * `ImageCatalogRepository`（§6.1），镜像管理页见 `ui/screens/images/`（§8.3）。
 */
package io.github.ltbkq.vmdroid.systemimage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ltbkq.vmdroid.MainActivity
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.data.repository.SettingsRepository
import io.github.ltbkq.vmdroid.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
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
    private val settings: SettingsRepository,
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

    /**
     * QEMU `-kernel` / `-initrd` 取自激活镜像 footer（IMAGE-FORMAT），不随 APK 发布。
     * 每次启动覆写 `filesDir/vmlinuz-virt` 与 `initrd.img`：先写 `.tmp` 再 rename；
     * [VmdImageCodec.extractPayload] 按 footer sha256 校验，坏段不落盘。
     * @return 抽取成功 = true；无激活镜像 / 镜像无 kernel 段 = false。
     */
    fun extractBootPayloads(): Boolean {
        val active = active() ?: return false
        val info = store.readImage(active.imageId)
        if (!info.footer.hasKernel) {
            Log.w(TAG, "active image '${active.imageId}' carries no kernel payload")
            return false
        }
        extractPayloadAtomic(info, "kernel", File(context.filesDir, "vmlinuz-virt"))
        val initrd = File(context.filesDir, "initrd.img")
        if (info.footer.hasInitrd) {
            extractPayloadAtomic(info, "initrd", initrd)
        } else {
            initrd.delete()
        }
        return true
    }

    private fun extractPayloadAtomic(info: VmdImageCodec.ImageInfo, name: String, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.delete()
        VmdImageCodec.extractPayload(info, name, tmp)
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw java.io.IOException("cannot replace ${dst.absolutePath}")
        }
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

    /**
     * §7.3 `awaitImagesReady()` 的前置判定（应用启动时跑一次，VmdroidApplication
     * 在放行 `imagesReady` 前调用）：逐个读 `.meta.json` 并做 size+mtime 快判，
     * **不**重算 sha256（启动期主判定仍由 BootGuard §7.4 兜底）。
     */
    fun preflightImages(): List<SystemImageStore.PreflightVerdict> = store.preflightMeta()

    /**
     * §8.5「重置并切换」确认后清零重建 `storage.img`（§4.3 整文件清零；§16.5 记
     * `factory_reset`）。文件不存在返回 false —— 引擎 `ensureStorageImage()` 按需
     * 创建，BootGuard #11 非拒绝项。
     */
    fun rebuildStorageImage(): Boolean =
        store.resetStorage(File(context.filesDir, STORAGE_FILE))

    // ---------------------------------------------------------------- 下载（§6.2 / M4）

    /** §6.2 下载器（纯核心）：事件直汇 `image.log`（`download_*`/`verify_fail`）。 */
    private val downloader: ImageDownloader by lazy {
        ImageDownloader(
            versionCode = { versionCode() },
            availableBytes = { availableBytes() },
            events = { event, fields ->
                imageLog.record(event, fields, sync = event in ImageLog.SYNC_EVENTS)
            },
        )
    }

    private var lastNotifyAt = 0L

    /**
     * §6.2 下载入口（挂起，内部 IO）：写盘前空间预检 → Range 续传 → 单遍 sha256 →
     * footer/manifest 校验（N4 footer 必需）→ fsync+rename → 补写 `.meta.json`。
     * 进度同时喂 UI 回调与常驻通知（M4「通知进度」）。
     *
     * 「仅 Wi-Fi 下载」设置在此兜底（UI 已禁用入口，这里是竞态防线）。
     */
    suspend fun downloadImage(
        entry: CatalogEntry,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (wifiOnlyBlocked()) {
            return@withContext DownloadOutcome.Failure(
                DownloadFailure(DownloadErrors.WIFI_ONLY, "downloads are restricted to Wi-Fi", resumable = true),
            )
        }
        runDownload(
            DownloadTarget(entry.imageId, entry.url, entry.size, entry.sha256),
            displayName = entry.displayName,
            onProgress = onProgress,
        )
    }

    /**
     * §6.2「进程被杀后下次进入应用自动续传」：扫 `images/` 下的 `*.part.info` 恢复全部未完成
     * 下载（VmdroidApplication 后台调用；Images 页打开时也会补扫）。Wi-Fi-only 统一兜底。
     * @return 恢复的任务数
     */
    suspend fun resumeInterruptedDownloads(): Int = withContext(Dispatchers.IO) {
        if (wifiOnlyBlocked()) return@withContext 0
        val pending = downloader.pendingTargets(store.imagesDir)
        for (target in pending) {
            runDownload(target, displayName = target.imageId, onProgress = null)
        }
        pending.size
    }

    /** 未完成下载的 `image_id` 清单（Images 页断点状态展示）。 */
    fun pendingDownloadIds(): List<String> =
        downloader.pendingTargets(store.imagesDir).map { it.imageId }

    /** §6.2「仅 Wi-Fi 下载」：true = 设置开启且当前网络可计量 → 应拦截。 */
    suspend fun wifiOnlyBlocked(): Boolean =
        settings.getDownloadsWifiOnlySnapshot() && !NetworkUtils.isUnmetered(context)

    private fun runDownload(
        target: DownloadTarget,
        displayName: String,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): DownloadOutcome {
        postDownloadNotification(displayName, null)
        val outcome = downloader.download(target, store.imagesDir, onProgress = { p ->
            postDownloadNotification(displayName, p)
            onProgress?.invoke(p)
        })
        when (outcome) {
            is DownloadOutcome.Success -> {
                // 下载器内已验证 sha256 == catalog.sha256（§6.2-3），直传避免整镜像重读
                store.writeMeta(outcome.file, target.sha256)
                finishDownloadNotification(displayName, DownloadFinish.DONE)
            }
            is DownloadOutcome.Failure -> {
                val cancelled = outcome.failure.err == DownloadErrors.CANCELLED
                finishDownloadNotification(
                    displayName,
                    if (cancelled) DownloadFinish.CANCELLED else DownloadFinish.FAILED,
                )
            }
        }
        return outcome
    }

    private enum class DownloadFinish { DONE, FAILED, CANCELLED }

    // ---------------------------------------------------------------- 下载通知

    private fun notificationsPermitted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureDownloadChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_DOWNLOAD) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                context.getString(R.string.channel_download_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun openAppIntent(): PendingIntent? = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 起始/进度通知（进度更新按 500ms 节流；完成态由 [finishDownloadNotification] 收口）。 */
    private fun postDownloadNotification(name: String, progress: DownloadProgress?) {
        if (!notificationsPermitted()) return
        ensureDownloadChannel()
        if (progress != null && progress.bytes < progress.total) {
            val now = System.currentTimeMillis()
            if (now - lastNotifyAt < 500L) return
            lastNotifyAt = now
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentTitle(context.getString(R.string.notif_download_title, name))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(progress != null && progress.bytes < progress.total)
        if (progress != null) {
            builder.setContentText(
                context.getString(
                    R.string.notif_download_progress,
                    progress.percent,
                    formatSpeed(progress.bytesPerSec),
                ),
            )
                .setProgress(100, progress.percent, false)
        }
        runCatching { NotificationManagerCompat.from(context).notify(DOWNLOAD_NOTIF_ID, builder.build()) }
    }

    private fun finishDownloadNotification(name: String, result: DownloadFinish) {
        if (!notificationsPermitted()) return
        NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIF_ID)
        if (result == DownloadFinish.CANCELLED) return
        ensureDownloadChannel()
        val title = context.getString(
            if (result == DownloadFinish.DONE) R.string.notif_download_done else R.string.notif_download_fail,
            name,
        )
        runCatching {
            NotificationManagerCompat.from(context).notify(
                DOWNLOAD_NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
                    .setSmallIcon(R.drawable.ic_vm_notification)
                    .setContentTitle(title)
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> String.format(Locale.ROOT, "%.0f kB/s", bytesPerSec / 1_000.0)
        else -> "$bytesPerSec B/s"
    }

    /** §6.2 预检的可用字节（StatFs；异常时放行，让下载器自行失败）。 */
    private fun availableBytes(): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        Log.w(TAG, "StatFs failed, skipping storage preflight", e)
        Long.MAX_VALUE
    }

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

        /** M4 下载通知渠道（§8.3「通知进度」）。 */
        const val CHANNEL_DOWNLOAD = "image_download"
        private const val DOWNLOAD_NOTIF_ID = 0x6000_0001
    }
}
