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
 * **M3（DESIGN-disk-image-v2 §7）**：`store` 注入 `V2SystemImageFormat` —— 安装/校验/激活
 * 主路径改走 `VmdDiskCodec`（v2 `.img`），`.img.zst` 走 `ZstdInflator` 流式稀疏解压；
 * v1 镜像由 v2 codec 诊断阶梯判 `LEGACY_FORMAT` → BootGuard 拒绝。新增接口：
 * [install]（显式契约，catalog 工位对接）、[reinstallSystem]（§6.4-B）、[resetData]（§6.4-A）、
 * [growSystemImage]（§6.5）、[markBooted]（§5.4 引擎 stop 刷 dirty 基线）、
 * [ensureZstdBinary]（`assets/bin/zstd` 提取，PATH 降级）。M3 收尾 UI 的辅助只读/删除
 * 小接口（不碰 Store 安装/激活/状态机核心）：[listCachedArchives]/[deleteCachedArchive]/
 * [deleteAllCachedArchives]（§6.4 缓存管理）、[isLegacyImage]（§10 v1 遗留判定，
 * `VmdDiskProbe` 只读快检）、[systemDiskSnapshot]（§6.5 扩容控件前置）。
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
import io.github.ltbkq.vmdroid.engine.VmdDiskProbe
import io.github.ltbkq.vmdroid.util.HostMetrics
import io.github.ltbkq.vmdroid.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
            // M3 格式切换（DESIGN-disk-image-v2 §7）：生产注入 v2 —— 安装/校验/激活
            // 全走 VmdDiskCodec；v1 输入由其 §5 诊断阶梯判 LEGACY_FORMAT（BootGuard 拒绝）。
            format = V2SystemImageFormat(
                versionCode = { versionCode() },
                zstdBinary = File(context.filesDir, ZSTD_ASSET_PATH),
            ),
        )
    }

    /** §7.4 主判定入口（service 启动前调用；引擎内 fail-fast 是兜底）。 */
    fun bootGuard(): BootGuard = BootGuard(store, File(context.filesDir, STORAGE_FILE))

    // ---------------------------------------------------------------- zstd 资产（§6.1 解压器）

    /**
     * §6.1 安装流的解压器：把 APK 资产 `bin/zstd`（aarch64 静态构建，**需 CI 补产物**）
     * 提取到 `filesDir/bin/zstd` 并加执行位（模仿 VmdroidApplication 的
     * copyAssetAtomically：tmp + fsync + rename；self-healing stamp 按 lastUpdateTime）。
     *
     * 资产缺失（CI 未补 / 开发构建）→ 返回 null，`ZstdInflator` 降级从 PATH 找 zstd
     * （本机测试用 `/usr/bin/zstd`）；真机两者都没有时安装失败并给出明确报错。
     */
    fun ensureZstdBinary(): File? = try {
        val dst = File(context.filesDir, ZSTD_ASSET_PATH)
        val stamp = File(dst.parentFile, ZSTD_STAMP)
        val lastUpdate = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        val current = runCatching { stamp.readText() }.getOrDefault("")
        if (!dst.isFile || dst.length() == 0L || current != lastUpdate) {
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            try {
                context.assets.open(ZSTD_ASSET).use { ins ->
                    FileOutputStream(tmp).use { out ->
                        ins.copyTo(out)
                        out.flush()
                        out.fd.sync()
                    }
                }
                if (!tmp.renameTo(dst)) throw java.io.IOException("cannot replace ${dst.absolutePath}")
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            runCatching { stamp.writeText(lastUpdate) }
        }
        dst.setExecutable(true, false)
        if (dst.canExecute()) dst else null
    } catch (t: Throwable) {
        Log.i(TAG, "bundled zstd not available (${t.message}) — falling back to PATH lookup")
        null
    }

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
     * QEMU `-kernel` / `-initrd` 抽取（**v1 兼容路径**；v2 镜像从 ESP 自带 kernel，
     * 见 DESIGN-disk-image-v2 §7 QemuEngine/AvfEngine 行）。
     * 每次启动覆写 `filesDir/vmlinuz-virt` 与 `initrd.img`：先写 `.tmp` 再 rename；
     * `VmdImageCodec.extractPayload` 按 footer sha256 校验，坏段不落盘。
     *
     * M3 起**绝不抛异常**：激活镜像是 v2（无 v1 footer）/不可读时直接返回 false，
     * 由引擎侧按 v2 引导链处理（本方法只服务尚未迁移的 v1 引擎路径）。
     * @return 抽取成功 = true；无激活镜像 / v2 镜像 / 镜像无 kernel 段 = false。
     */
    fun extractBootPayloads(): Boolean {
        val active = active() ?: return false
        if (!active.idValid) return false
        val file = File(store.imagesDir, "${active.imageId}.img")
        val info = try {
            VmdImageCodec.read(file, versionCode())
        } catch (t: Throwable) {
            Log.i(TAG, "active image '${active.imageId}' is not a readable v1 image — kernel payload extraction skipped (v2 boots from ESP)")
            return false
        }
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
     * 流式安装**兼容重载**（既有调用面：SAF 导入 `install(input, src = uri)`）。
     * 等价于 [install] 的 [InstallSource.Stream] 形态；语义见 store 的 §6.1 安装流
     * （`.part` → 双摘要/结构校验 → 原子 rename → `.meta.json {state:pristine}`）。
     * 异常见 [VmdImageException]。
     */
    fun install(
        input: InputStream,
        targetId: String? = null,
        expectedSha256: String? = null,
        src: String = "import",
        onProgress: ((Long) -> Unit)? = null,
    ): SystemImageStore.InstalledImage = install(
        InstallSource.Stream({ input }, src),
        InstallRequest(
            imageId = targetId,
            src = src,
            imageSha256 = expectedSha256,
            onProgress = onProgress,
        ),
    )

    /**
     * **M3 安装契约（catalog 工位对接口）**：显式参数、不依赖 `CatalogEntry` 类型。
     * 触发前先确保 zstd 资产就位（[ensureZstdBinary]，PATH 降级见其 KDoc）。
     *
     * @param source [InstallSource.FileSource]（`cache/<id>.img.zst` 下载产物 /
     *   `reinstallSystem`）或 [InstallSource.Stream]（SAF fd/uri 打开的流）
     * @param request `imageId`/`zstSha256`/`zstSize`/`imageSha256`/`imageSize`/展示字段，
     *   见 [InstallRequest] 的逐字段口径
     */
    fun install(
        source: InstallSource,
        request: InstallRequest = InstallRequest(),
    ): SystemImageStore.InstalledImage {
        ensureZstdBinary() // 缺资产时降级 PATH，不阻断
        return store.install(source, request)
    }

    /**
     * 下载器 → 安装的**显式交接**（`ImageDownloader.InstallHandoff`，catalog 工位契约）。
     * Store 层不认识 catalog 类型（systemimage-selftest 的 kotlinc 清单不含
     * ImageCatalog/ImageDownloader），映射收敛在此：
     * `imageSha256=""` / `imageSize=0` 表示**未知** → 传 null（Store 自算，不拿 0 当校验值）。
     */
    fun install(handoff: InstallHandoff): SystemImageStore.InstalledImage = install(
        InstallSource.FileSource(handoff.source, "download"),
        InstallRequest(
            imageId = handoff.imageId,
            src = "download",
            zstSha256 = handoff.zstSha256.takeIf { it.isNotBlank() },
            zstSize = handoff.zstSize.takeIf { it > 0 },
            imageSha256 = handoff.imageSha256.takeIf { it.isNotBlank() },
            imageSize = handoff.imageSize.takeIf { it > 0 },
            display = handoff.display.toInstallDisplay(),
        ),
    )

    /**
     * §6.4-B **重装系统**（恢复出厂第二级）：`cache/<id>.img.zst` 存在 → 复用 §6.1
     * 安装流覆盖重建为 pristine；不存在 → [ReinstallNeedsNetworkException]
     * （`errCode == "needs_network"`，UI 据此引导去 catalog 重下）。
     */
    fun reinstallSystem(imageId: String): SystemImageStore.InstalledImage {
        ensureZstdBinary()
        return store.reinstallSystem(imageId)
    }

    /**
     * §5.4 **引擎 stop 时调用**：guest 已启动并写入系统盘 → 刷新 `.meta.json` 的
     * size/mtime 基线并转 `dirty`（+ `image.log` 的 `boot_done` 记录，作为后续启动前
     * 归因的证据）。崩溃场景（没调到）由启动前的 image.log/boots 归因兜底。
     * @return 是否刷新成功（无激活镜像/无 meta → false）
     */
    fun markBooted(): Boolean = store.markBooted()

    /**
     * §6.5 根分区扩容（**VM 停机时**调用；前置条件"引擎已停"由调用方保证）。
     * 稀疏 `setLength` + `.meta.json` 基线刷新 + `pending_grow`；分区表/guest 侧
     * 自扩容见 store KDoc 与交付报告。
     */
    fun growSystemImage(imageId: String, newSizeBytes: Long): SystemImageStore.GrowResult =
        store.growSystemImage(imageId, newSizeBytes)

    /** 同 [growSystemImage]，目标 = 当前激活镜像（无激活镜像 → null）。 */
    fun growSystemImage(newSizeBytes: Long): SystemImageStore.GrowResult? {
        val id = activeImageId() ?: return null
        return store.growSystemImage(id, newSizeBytes)
    }

    // ---------------------------------------------------------------- 缓存 / 格式辅助（M3 收尾 UI，只读 + 删除）

    /**
     * `cache/<id>.img.zst` 一项（§6.1 下载缓存 = §6.4-B 离线重装的取处）。
     * 两个体积口径并存：[sizeBytes] 表观大小、[diskBytes] 实际占用
     * （`st_blocks×512`，与 `HostMetrics.diskFootprintBytes` 同口径）。
     */
    data class CachedArchive(
        val imageId: String,
        val file: File,
        val sizeBytes: Long,
        val diskBytes: Long,
    )

    /**
     * §6.4 缓存列举（**只读**；只列已完成的 `<id>.img.zst`，下载在传的
     * `<id>.img.zst.part` 不列 —— 删除动作不应打断进行中的下载）。
     */
    fun listCachedArchives(): List<CachedArchive> {
        val files = store.cacheDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(SystemImageStore.ZST_SUFFIX) }
            .map { f ->
                CachedArchive(
                    imageId = f.name.removeSuffix(SystemImageStore.ZST_SUFFIX),
                    file = f,
                    sizeBytes = f.length(),
                    diskBytes = HostMetrics.diskFootprintBytes(f),
                )
            }
            .sortedBy { it.imageId }
    }

    /**
     * §6.4 缓存清理（单项）：**= 放弃该镜像的离线重装能力**（重装时引导去 catalog
     * 重下，`ReinstallNeedsNetworkException`）。id 非法 → false（不碰任何文件）。
     * @return true = 文件存在且已删除
     */
    fun deleteCachedArchive(imageId: String): Boolean {
        if (!SystemImageStore.IMAGE_ID_REGEX.matches(imageId)) return false
        val file = File(store.cacheDir, "$imageId${SystemImageStore.ZST_SUFFIX}")
        return file.isFile && file.delete()
    }

    /**
     * §6.4 缓存清理（全部，`.img.zst` 逐项走 [deleteCachedArchive] 的同款口径）。
     * @return 删除的文件数（`.part` 在传文件跳过不计）
     */
    fun deleteAllCachedArchives(): Int =
        listCachedArchives().count { deleteCachedArchive(it.imageId) }

    /**
     * §10 **v1 遗留镜像判定**：`VmdDiskProbe.isV2Image(images/<id>.img) == false` 即旧格式
     * （探测只读 3×512B 扇区、不跑 codec，列表逐项调用成本可忽略）。id 非法 /
     * 文件缺失同样返回 true（两者都无从启动，UI 侧统一走「清理」入口）。
     */
    fun isLegacyImage(imageId: String): Boolean {
        if (!SystemImageStore.IMAGE_ID_REGEX.matches(imageId)) return true
        return !VmdDiskProbe.isV2Image(File(store.imagesDir, "$imageId.img"))
    }

    /** §6.5 扩容控件所需的只读快照（[growable]=false 时控件整体禁用）。 */
    data class SystemDiskSnapshot(
        /** 激活镜像 id（无激活记录 → null）。 */
        val activeId: String?,
        /** `.img` 表观容量（字节；0 = 无激活镜像）。 */
        val sizeBytes: Long,
        /** §10 v1 旧格式镜像（BootGuard 会拒启；§6.5 扩容不适用）。 */
        val legacyFormat: Boolean,
        /** meta `pending_grow`（已扩容、等待 guest 下次开机消费）。 */
        val pendingGrow: Boolean,
        /** 激活镜像存在且为 v2（唯一可扩容形态）。 */
        val growable: Boolean,
    )

    /** [SystemDiskSnapshot] 的扫描实现（每次调用都重读盘，供设置页刷新用）。 */
    fun systemDiskSnapshot(): SystemDiskSnapshot {
        val rec = active()?.takeIf { it.idValid } ?: return SystemDiskSnapshot(
            activeId = null,
            sizeBytes = 0L,
            legacyFormat = false,
            pendingGrow = false,
            growable = false,
        )
        val file = File(store.imagesDir, "${rec.imageId}.img")
        if (!file.isFile) {
            return SystemDiskSnapshot(rec.imageId, 0L, legacyFormat = false, pendingGrow = false, growable = false)
        }
        val legacy = !VmdDiskProbe.isV2Image(file)
        return SystemDiskSnapshot(
            activeId = rec.imageId,
            sizeBytes = file.length(),
            legacyFormat = legacy,
            pendingGrow = store.readMeta(rec.imageId)?.pendingGrow == true,
            growable = !legacy,
        )
    }

    /**
     * 激活（§5.3）。**先算 §5.2 `decideReset`**（§5.4：v2 判定键 = `content_id`）：
     * 需要重置时返回 [SystemImageStore.ActivateResult.ResetRequired] 信号
     * （不写 `active.json`、不清 `storage.img`），由 UI 走 §8.5 对话框确认并重建数据盘后，
     * 再以 `allowReset = true` 重新调用。
     */
    fun activate(imageId: String, allowReset: Boolean = false): SystemImageStore.ActivateResult =
        store.activate(imageId, allowReset)

    /** 删除镜像 + meta（激活镜像被删时同时移除 `active.json`，§5.3；缓存 zst 保留）。 */
    fun delete(imageId: String, src: String = "manual"): Boolean = store.delete(imageId, src)

    /**
     * §6.4 校验：`full=false` 启动前只比 `size`+`mtime`；`full=true` 手动全量
     * （dirty 仅结构校验 + 明示文案，§5.4；pending_grow 跳过整镜像摘要，§6.5）。
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
     * §6.4-A **清数据**（恢复出厂第一级，推荐默认）：整文件清零重建 `storage.img`
     * （§4.3；§16.5 记 `factory_reset`）。文件不存在返回 false —— 引擎
     * `ensureStorageImage()` 按需创建，BootGuard #11 非拒绝项。
     *
     * 与 [reinstallSystem]（第二级，回原厂系统）语义不同：本动作**不会**回滚
     * `apt install` 等系统盘修改（DESIGN-disk-image-v2 §6.4 UI 文案要求区分）。
     */
    fun resetData(): Boolean = store.resetStorage(File(context.filesDir, STORAGE_FILE))

    /** §8.5「重置并切换」确认后的同一动作（历史名保留；= [resetData]）。 */
    fun rebuildStorageImage(): Boolean = resetData()

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
            // schema2（IMAGE-FORMAT-v2 §7.2）：zst + image_sha256/image_size/display 全字段交接
            DownloadTarget.from(entry),
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
        val pending = downloader.pendingTargets(store.cacheDir)
        for (target in pending) {
            // 单个任务失败（网络/校验）不能中断其余断点恢复
            try {
                runDownload(target, displayName = target.imageId, onProgress = null)
            } catch (t: Throwable) {
                Log.w(TAG, "resumed download for '${target.imageId}' failed", t)
            }
        }
        pending.size
    }

    /** 未完成下载的 `image_id` 清单（Images 页断点状态展示）。 */
    fun pendingDownloadIds(): List<String> =
        downloader.pendingTargets(store.cacheDir).map { it.imageId }

    /** §6.2「仅 Wi-Fi 下载」：true = 设置开启且当前网络可计量 → 应拦截。 */
    suspend fun wifiOnlyBlocked(): Boolean =
        settings.getDownloadsWifiOnlySnapshot() && !NetworkUtils.isUnmetered(context)

    private fun runDownload(
        target: DownloadTarget,
        displayName: String,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): DownloadOutcome {
        postDownloadNotification(displayName, null)
        // §6.1：v2 资产是 `.img.zst` → 落 `cache/`（下载期 sha256 校验 + 断点续传）
        val outcome = downloader.download(target, store.cacheDir, onProgress = { p ->
            postDownloadNotification(displayName, p)
            onProgress?.invoke(p)
        })
        when (outcome) {
            is DownloadOutcome.Success -> {
                // §6.1 下载 → 安装交接：zst 已过 sha256 校验，安装流负责解压/稀疏落盘/
                // image_sha256 双字段比对/结构校验/原子 rename（失败 → 通知 FAILED 并抛出）
                try {
                    install(outcome.install)
                } catch (t: Throwable) {
                    finishDownloadNotification(displayName, DownloadFinish.FAILED)
                    throw t
                }
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

        /** §6.1 解压器资产（`assets/bin/zstd` → `filesDir/bin/zstd`；**CI 待补产物**）。 */
        const val ZSTD_ASSET = "bin/zstd"
        const val ZSTD_ASSET_PATH = "bin/zstd" // filesDir 下的相对路径（与资产同形）
        private const val ZSTD_STAMP = "zstd_stamp"

        /** M4 下载通知渠道（§8.3「通知进度」）。 */
        const val CHANNEL_DOWNLOAD = "image_download"
        private const val DOWNLOAD_NOTIF_ID = 0x6000_0001
    }
}

/** `CatalogDisplay`（catalog 工位，交接/展示用）→ Store 的中立 `InstallDisplay`。 */
private fun CatalogDisplay.toInstallDisplay(): InstallDisplay = InstallDisplay(
    displayName = displayName,
    identity = identity,
    variant = variant,
    version = version,
    systemVersion = null, // CatalogDisplay 不携带（catalog 条目的 system_version 只进展示）
    arch = null,
    channel = channel,
)
