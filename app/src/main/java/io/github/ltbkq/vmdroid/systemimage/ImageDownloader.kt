/*
 * VMDroid - image downloader with HTTP range resume (DESIGN §6.2 / disk-image-v2 §6.1).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN-disk-image-v2.md §6.1（下载 `.img.zst`：预检 margin = zst `size` + 32 MiB，
 *     另按 `image_size` 做落盘余量预检；完成后 sha256 == catalog.sha256（= zst 摘要）
 *     → fsync + rename → **显式参数交接 Store.install**（imageId + zst 双摘要/尺寸 + 展示字段），
 *     解压/`image_sha256` 全量校验/结构校验移到 install 期）
 *   - docs/DESIGN-disk-image-v2.md §9（落盘预检用 st_blocks 语义估算，不能拿 apparent
 *     size 直接判空间不足 —— 见 [ImageDownloader.landingEstimate] 的取舍注释）
 *   - docs/IMAGE-FORMAT-v2.md §7.1（容器 = 标准 zstd frame；下载只做 sha256 + 廉价
 *     magic 兜底，结构校验由 Store 在解压后做）
 *   - docs/DESIGN.md §6.2（Range 续传/416/validators 变化重来；.part.info 续传依据）
 *   - docs/DESIGN.md §16.5（download_start/download_progress/download_done/
 *     download_fail；progress 每 5% 或 4MB 节流；每条含 dur_ms，ts 由 ImageLog 加）
 *   - docs/DESIGN.md §16.9（.part 永不冒充成品：进程被杀后 .part + .part.info
 *     保留，下次进入应用自动续传）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— java.net + 文件 IO，
 * HTTP 语义由 systemimage 自测用 JDK HttpServer 驱动。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * §6.2 下载目标：catalog 条目的下载期快照（`.part.info` 的续传依据）。
 *
 * schema v2（IMAGE-FORMAT-v2 §7.2）：
 *  - [size] / [sha256] —— `.img.zst` 容器字节数与摘要（下载预检/续传/完成校验）；
 *  - [imageSize] / [imageSha256] —— 解压后 `.img` 的表观字节数与摘要（落盘预检 +
 *    install 期全量校验）。旧 4 参调用点（未升级前）给 0/""，此时落盘预检自动降级为
 *    仅 zst+margin（见 [ImageDownloader.landingEstimate]）。
 *  - [display] —— 展示字段（install 交接的 display 部分；null 时以 imageId 兜底）。
 */
data class DownloadTarget(
    val imageId: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val imageSize: Long = 0L,
    val imageSha256: String = "",
    val display: CatalogDisplay? = null,
) {
    companion object {
        /** 从 schema2 目录条目构造完整下载目标（调用方从 4 参升级到本工厂即可启用双摘要交接）。 */
        fun from(entry: CatalogEntry): DownloadTarget = DownloadTarget(
            imageId = entry.imageId,
            url = entry.url,
            size = entry.size,
            sha256 = entry.sha256,
            imageSize = entry.imageSize,
            imageSha256 = entry.imageSha256,
            display = entry.display(),
        )
    }
}

/**
 * 下载完成 → `SystemImageStore.install(...)` 的**显式交接**（DESIGN-disk-image-v2 §6.1）。
 *
 * 集成契约（Store 侧签名以此为准，集成时统一）：
 * ```
 * fun install(
 *     source: File,          // 下载产物 <imageId>.img.zst（已过 zst sha256 校验）
 *     imageId: String,       // catalog image_id（bind 用）
 *     zstSha256: String,     // = catalog.sha256（下载期已校验，避免 Store 重读整包）
 *     zstSize: Long,         // = catalog.size
 *     imageSha256: String,   // = catalog.image_sha256（解压后全量校验锚点；"" = 未知，Store 自算）
 *     imageSize: Long,       // = catalog.image_size（apparent；0 = 未知）
 *     display: CatalogDisplay, // 展示字段
 *     src: String = "download",
 *     onProgress: ((Long) -> Unit)? = null,
 * ): InstalledImage
 * ```
 * 旧调用方（SystemImageRepository 尚未升级时）经 4 参 [DownloadTarget] 进来的目标
 * [imageSha256]/[imageSize] 为 ""/0 —— install 侧须按「未知」处理（自算摘要），不要拿 0 当校验值。
 */
data class InstallHandoff(
    val source: File,
    val imageId: String,
    val zstSha256: String,
    val zstSize: Long,
    val imageSha256: String,
    val imageSize: Long,
    val display: CatalogDisplay,
)

/** 进度快照（UI 行内进度 / 通知栏共用）。 */
data class DownloadProgress(
    val bytes: Long,
    val total: Long,
    val bytesPerSec: Long,
) {
    val percent: Int
        get() = if (total > 0) ((bytes * 100) / total).coerceIn(0L, 100L).toInt() else 0

    /** 预计剩余秒数；速度未知时 -1。 */
    val etaSeconds: Long
        get() = if (bytesPerSec > 0 && total > bytes) (total - bytes) / bytesPerSec else -1L
}

/** §6.2 下载结局之「失败」。[resumable]=.part/.info 保留可续传；false=已按规格丢弃。 */
data class DownloadFailure(
    val err: String,
    val message: String,
    val resumable: Boolean,
)

sealed class DownloadOutcome {
    /**
     * 下载成功（`.img.zst` 已 fsync + rename + sha256 校验通过）。
     *
     * [file] = 下载产物 `<imageId>.img.zst`；[install] = 向 Store 交接的显式参数。
     * [manifestId]：v1 时是「解析 footer 后与 catalog 比对过的 manifest.image.id」；
     * v2 下载期不解包（`.img.zst` 未解压，结构/`image_sha256` 校验移到 install 期，
     * DESIGN §6.1），故恒 = catalog [imageId]，image_id ↔ manifest 绑定由 Store 在
     * install 期完成 —— 字段保留仅为兼容既有调用方（读方只把它当 imageId 用）。
     */
    data class Success(
        val imageId: String,
        val file: File,
        val manifestId: String,
        val install: InstallHandoff,
    ) : DownloadOutcome()

    data class Failure(val failure: DownloadFailure) : DownloadOutcome()
}

/** `download_fail.err` 取值（自由串，非 §7.4 启动枚举；§16.5 表只要求 err 字段）。 */
object DownloadErrors {
    const val STORAGE_FULL = "STORAGE_FULL"
    const val NETWORK = "NETWORK"
    const val SHORT_READ = "SHORT_READ"
    const val SHA_MISMATCH = "SHA_MISMATCH"
    const val IMAGE_INVALID = "IMAGE_INVALID"
    const val CANCELLED = "CANCELLED"

    /** 「仅 Wi-Fi 下载」设置拦截（§6.2；UI 应已禁用入口，此为竞态防线）。 */
    const val WIFI_ONLY = "WIFI_ONLY"
}

/**
 * §6.2 断点续传下载器（阻塞式；调用方放 `Dispatchers.IO`，以 [shouldContinue]
 * 做协作取消）。单任务串行由上层保证。**永不抛网络/IO 异常**（一律折成
 * [DownloadFailure]）；仅 sink/取消源自身抛出时传播。
 *
 * schema v2：下载目标 = `.img.zst`（[ImageCatalog.IMG_ZST_SUFFIX]）；下载期只做
 * sha256 + zstd magic 兜底，镜像结构校验与 `image_sha256` 全量校验在 install 期做。
 */
class ImageDownloader(
    // v2 起下载期不再调 VmdImageCodec（结构校验移到 install 期）；参数保留以兼容
    // 既有构造点（SystemImageRepository 传入），后续若做 app_min_version_code 下载
    // 期门闩可直接复用。
    @Suppress("UNUSED") private val versionCode: () -> Long?,
    private val availableBytes: () -> Long,
    private val events: (event: String, fields: Map<String, Any?>) -> Unit,
) {
    companion object {
        /**
         * §6.2 预检余量：`size + 32 MiB`（"size + 余量"的落值）。
         * schema v2：[DownloadTarget.size] = `.img.zst` 字节数（DESIGN-disk-image-v2 §6.1
         * 「预检 margin = size + 32 MiB」）；落盘余量另检，见 [ImageDownloader.landingEstimate]。
         */
        const val STORAGE_MARGIN = 32L * 1024 * 1024

        /** `<image_id>.img.zst.part.info` —— 续传元数据（url/size/sha + 双摘要/展示 + validators）。 */
        const val INFO_SUFFIX = ".part.info"

        /** zstd frame magic（little-endian 0xFD2FB528，ISO 18838）—— 廉价容器形态兜底。 */
        private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CHUNK = 64 * 1024

        /** 速度采样最小间隔（供 UI 显示 MB/s 与剩余时间）。 */
        private const val SPEED_SAMPLE_MS = 500L

        /** Range/validators 状态机允许的最大重连次数（防御病态服务端）。 */
        private const val MAX_REOPEN = 2

        /** 饱和加法（空间预检：size+margin+估算不允许溢出成负数）。 */
        private fun saturatingAdd(a: Long, b: Long): Long =
            if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }

    private val throttle = ProgressThrottle() // §16.9：每 5% 或 4MB 记一行

    /** 下载目标文件：`<image_id>.img.zst.part`（v1 是 `<id>.img.part`，格式硬切换后改名）。 */
    fun partFile(imagesDir: File, imageId: String): File =
        File(imagesDir, "$imageId${ImageCatalog.IMG_ZST_SUFFIX}${SystemImageStore.PART_SUFFIX}")

    fun infoFile(imagesDir: File, imageId: String): File =
        File(imagesDir, "$imageId${ImageCatalog.IMG_ZST_SUFFIX}$INFO_SUFFIX")

    /**
     * 扫描 images/ 下全部未完成下载（进程被杀 → 下次进入应用自动续传的输入）。
     *
     * 只认 `.img.zst.part.info`（v2 命名）—— v1 遗留的 `<id>.img.part.info`（.img 直链
     * 半成品，DESIGN-disk-image-v2 §10.5 旧资产已下架）不再续传：它们会按 v1 sha 白下
     * 一整包然后在容器校验处被拒。遗留文件不主动删除（清理交给 store.delete / 手工）。
     */
    fun pendingTargets(imagesDir: File): List<DownloadTarget> {
        val files = imagesDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(ImageCatalog.IMG_ZST_SUFFIX + INFO_SUFFIX) }
            .mapNotNull { readInfo(it) }
            .map {
                DownloadTarget(
                    imageId = it.imageId,
                    url = it.url,
                    size = it.size,
                    sha256 = it.sha256,
                    imageSize = it.imageSize,
                    imageSha256 = it.imageSha256,
                    display = it.display,
                )
            }
            .distinctBy { it.imageId }
    }

    /** §6.2 主流程。 */
    fun download(
        target: DownloadTarget,
        imagesDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): DownloadOutcome {
        val startedAt = System.currentTimeMillis()
        throttle.reset()
        val part = partFile(imagesDir, target.imageId)
        val info = infoFile(imagesDir, target.imageId)

        // ① 空间预检（§6.2-1 / §13.2 STORAGE_FULL 用例：写盘前拦截，本次不创建 .part）
        // ①a 下载体积：margin 按 zst `size`（DESIGN-disk-image-v2 §6.1：预检 = size + 32 MiB）
        val available = runCatching { availableBytes() }.getOrDefault(Long.MAX_VALUE)
        val downloadNeed = saturatingAdd(target.size, STORAGE_MARGIN)
        if (available < downloadNeed) {
            return fail(
                target, startedAt, DownloadErrors.STORAGE_FULL,
                "need $downloadNeed bytes (.img.zst ${target.size} + margin $STORAGE_MARGIN), have $available",
                bytes = if (part.isFile) part.length().coerceAtMost(target.size) else 0L,
                resumable = part.isFile,
            )
        }
        // ①b 落盘余量：安装时 zst 与解压后的 .img 并存（缓存默认保留 .img.zst，
        //     DESIGN §6.1）→ 峰值 ≈ zst + .img 实际占用；估算口径见 landingEstimate。
        //     image_size 未知（旧 4 参调用点，imageSize == 0）→ 跳过本检查，退回 v1 行为。
        val landing = landingEstimate(target)
        if (landing > 0L && available < saturatingAdd(downloadNeed, landing)) {
            return fail(
                target, startedAt, DownloadErrors.STORAGE_FULL,
                "need ${saturatingAdd(downloadNeed, landing)} bytes " +
                    "(download ${target.size} + margin $STORAGE_MARGIN + install estimate $landing " +
                    "= min(image_size ${target.imageSize}, zst×3)), have $available",
                bytes = if (part.isFile) part.length().coerceAtMost(target.size) else 0L,
                resumable = part.isFile,
            )
        }

        // ② 续传判定：info 必须与本次 target 完全一致，否则丢弃陈旧 .part
        var offset = 0L
        var etag: String? = null
        var lastModified: String? = null
        var resumed = false
        val saved = readInfo(info)
        if (saved != null && saved.matches(target) && part.isFile) {
            val len = part.length()
            if (len in 1..target.size) {
                offset = len
                etag = saved.etag
                lastModified = saved.lastModified
                resumed = true
            } else if (len > target.size || len == 0L) {
                // 越界/空 part（预期 size 变了）→ 丢弃
                part.delete()
                info.delete()
            }
        } else if (part.isFile || info.isFile) {
            part.delete()
            info.delete()
        }

        emit(
            "download_start",
            linkedMapOf(
                "image" to target.imageId,
                "src" to target.url,
                "bytes" to offset,
                "resume" to resumed,
            ),
            startedAt,
        )

        // ③ 连接 + Range 状态机（§6.2-2）
        val session = negotiate(target, offset, etag, lastModified, startedAt)
        if (session is Session.Rejected) {
            return DownloadOutcome.Failure(session.failure)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var digestComplete = false

        if (session is Session.CompleteLocal) {
            // 416 且本地长度 == 预期 → 直接进校验（整个文件重喂 digest）
            if (part.length() != target.size) {
                part.delete()
                info.delete()
                return fail(
                    target, startedAt, DownloadErrors.SHORT_READ,
                    "416 but local part is ${part.length()} bytes, expected ${target.size}",
                    bytes = 0L,
                    resumable = false,
                )
            }
            feedFile(digest, part, part.length())
            digestComplete = true
        } else {
            val open = session as Session.Open
            // ④ 流式写入：前缀（如有）先喂 digest —— 续传字节不再经过网络，只读本地
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(open.start) // start>0 保留前缀；start==0 清空
                    if (open.start > 0) {
                        feedFile(digest, part, open.start)
                    }
                    raf.seek(open.start)
                    var bytes = open.start

                    // 续传依据先落盘（写第一块前）：此刻被杀也能续
                    writeInfo(info, target, open.etag, open.lastModified)

                    val input = open.conn.inputStream
                    val buf = ByteArray(CHUNK)
                    var lastSampleAt = System.currentTimeMillis()
                    var lastSampleBytes = bytes
                    var speed = 0L
                    while (true) {
                        if (!shouldContinue()) {
                            raf.fd.sync()
                            open.conn.disconnect()
                            return fail(
                                target, startedAt, DownloadErrors.CANCELLED,
                                "cancelled",
                                bytes = bytes,
                                resumable = true,
                            )
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        val room = (target.size - bytes).coerceAtMost(n.toLong()).toInt()
                        if (room <= 0) break // 服务端多发：按 size 截断，多出的丢弃
                        raf.write(buf, 0, room)
                        digest.update(buf, 0, room)
                        bytes += room

                        val now = System.currentTimeMillis()
                        if (now - lastSampleAt >= SPEED_SAMPLE_MS) {
                            speed = (bytes - lastSampleBytes) * 1000 / (now - lastSampleAt)
                            lastSampleAt = now
                            lastSampleBytes = bytes
                        }
                        onProgress(DownloadProgress(bytes, target.size, speed))
                        if (throttle.shouldLog(bytes, target.size)) {
                            emit(
                                "download_progress",
                                linkedMapOf(
                                    "image" to target.imageId,
                                    "src" to target.url,
                                    "bytes" to bytes,
                                    "resume" to open.resumed,
                                ),
                                startedAt,
                            )
                        }
                        if (bytes >= target.size) break
                    }
                    raf.fd.sync() // §6.2-4：fsync 后才允许 rename
                    if (bytes < target.size) {
                        open.conn.disconnect()
                        return fail(
                            target, startedAt, DownloadErrors.SHORT_READ,
                            "connection ended at $bytes of ${target.size} bytes",
                            bytes = bytes,
                            resumable = true,
                        )
                    }
                    digestComplete = true
                }
            } catch (e: Exception) {
                open.conn.disconnect()
                return fail(
                    target, startedAt, DownloadErrors.NETWORK,
                    e.message ?: e.javaClass.simpleName,
                    bytes = runCatching { part.length() }.getOrDefault(0L),
                    resumable = true,
                )
            }
            open.conn.disconnect()
        }

        // ④ 完成校验（§6.2-3）：sha256 == catalog.sha256
        val actualSha = if (digestComplete) {
            digest.digest().joinToString("") { "%02x".format(it) }
        } else {
            sha256File(part)
        }
        if (!actualSha.equals(target.sha256, ignoreCase = true)) {
            part.delete()
            info.delete()
            val detail = "expected ${target.sha256}, got $actualSha"
            emit(
                "verify_fail",
                linkedMapOf("image" to target.imageId, "fail" to "sha256", "err" to detail),
                startedAt,
            )
            emit(
                "download_fail",
                linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to target.size, "err" to DownloadErrors.SHA_MISMATCH),
                startedAt,
            )
            return DownloadOutcome.Failure(DownloadFailure(DownloadErrors.SHA_MISMATCH, detail, resumable = false))
        }

        // ④′ 容器形态兜底（IMAGE-FORMAT-v2 §7.1）：sha256 == catalog.sha256 已由签名目录
        // 锚定内容；此处只做廉价 zstd frame magic 检查（公钥未内置阶段兜住伪装内容，
        // 错误分类沿用 IMAGE_INVALID）。镜像结构校验与 image_sha256 全量校验**移到
        // install 期**（Store 解压后做，DESIGN §6.1：.img.zst 未解压时读不出 footer/manifest）。
        if (!hasZstdMagic(part)) {
            part.delete()
            info.delete()
            val detail = "not a zstd frame (.img.zst expected): ${part.name}"
            emit(
                "verify_fail",
                linkedMapOf("image" to target.imageId, "fail" to "container", "err" to detail),
                startedAt,
            )
            emit(
                "download_fail",
                linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to target.size, "err" to DownloadErrors.IMAGE_INVALID),
                startedAt,
            )
            return DownloadOutcome.Failure(DownloadFailure(DownloadErrors.IMAGE_INVALID, detail, resumable = false))
        }

        // ⑤ rename(.part → .img.zst)；解压安装与 .meta.json 由 Store.install 显式参数接手
        val finalFile = File(imagesDir, "${target.imageId}${ImageCatalog.IMG_ZST_SUFFIX}")
        if (finalFile.exists() && !finalFile.delete()) {
            return fail(
                target, startedAt, DownloadErrors.NETWORK,
                "cannot replace existing ${finalFile.name}",
                bytes = target.size,
                resumable = true,
            )
        }
        if (!part.renameTo(finalFile)) {
            return fail(
                target, startedAt, DownloadErrors.NETWORK,
                "rename .part -> ${ImageCatalog.IMG_ZST_SUFFIX} failed",
                bytes = target.size,
                resumable = true,
            )
        }
        info.delete()
        emit(
            "download_done",
            linkedMapOf(
                "image" to target.imageId,
                "src" to target.url,
                "bytes" to target.size,
                "resume" to resumedOf(session),
            ),
            startedAt,
        )
        // ⑥ 显式交接（DESIGN-disk-image-v2 §6.1）：产物路径 + imageId + zst 双摘要/尺寸 +
        //    展示字段，替代 v1 的「outcome.file + target.sha256 → store.writeMeta」隐式约定。
        val handoff = InstallHandoff(
            source = finalFile,
            imageId = target.imageId,
            zstSha256 = target.sha256,
            zstSize = target.size,
            imageSha256 = target.imageSha256,
            imageSize = target.imageSize,
            display = target.display ?: CatalogDisplay(displayName = target.imageId),
        )
        return DownloadOutcome.Success(
            imageId = target.imageId,
            file = finalFile,
            // v2：下载期不解包 manifest，绑定检查在 install 期（见 Success 的 KDoc）
            manifestId = target.imageId,
            install = handoff,
        )
    }

    // ---------------------------------------------------------------- 连接协商

    /** [download] ③ 的产出。 */
    private sealed class Session {
        /** 可写会话：[conn] 供流式读取，[start] 为写入起点（0=全新，>0=续传前缀已在本地）。 */
        data class Open(
            val conn: HttpURLConnection,
            val start: Long,
            val etag: String?,
            val lastModified: String?,
            val resumed: Boolean,
        ) : Session()

        /** 416 且本地 part 已是完整长度 → 无需连接，直接校验。 */
        data object CompleteLocal : Session()

        /** 拒绝（download_fail 已记）。 */
        data class Rejected(val failure: DownloadFailure) : Session()
    }

    private fun resumedOf(session: Session): Boolean = when (session) {
        is Session.Open -> session.resumed
        // CompleteLocal 仅在发过 Range（offset>0）时可能达到 → 必是续传
        Session.CompleteLocal -> true
        is Session.Rejected -> false
    }

    /** Range/If-Range/416 状态机（§6.2-2）。失败时已 emit `download_fail`。 */
    private fun negotiate(
        target: DownloadTarget,
        offset0: Long,
        etag0: String?,
        lastModified0: String?,
        startedAt: Long,
    ): Session {
        var offset = offset0
        var etag = etag0
        var lastModified = lastModified0
        var resumed = offset0 > 0
        var reopen = 0
        var conn = try {
            open(target, offset, etag, lastModified)
        } catch (e: Exception) {
            return Session.Rejected(
                failAs(target, startedAt, DownloadErrors.NETWORK, e.message ?: "open failed", offset, resumable = resumed),
            )
        }
        while (true) {
            val opening = try {
                evaluate(conn, offset, target)
            } catch (e: Exception) {
                conn.disconnect()
                return Session.Rejected(
                    failAs(target, startedAt, DownloadErrors.NETWORK, e.message ?: "connect failed", offset, resumable = resumed),
                )
            }
            when (opening) {
                is Opening.Serving -> {
                    etag = opening.etag ?: etag
                    lastModified = opening.lastModified ?: lastModified
                    return Session.Open(conn, opening.start, etag, lastModified, resumed)
                }
                is Opening.FullBody -> {
                    // 200：服务端未按 Range 服务（未支持 / If-Range 失配）→ 从 0 重来。
                    // 旧 .part 由写入路径 setLength(0) 清掉，.part.info 由 writeInfo 覆盖。
                    etag = opening.etag
                    lastModified = opening.lastModified
                    resumed = false
                    return Session.Open(conn, 0L, etag, lastModified, resumed)
                }
                Opening.Complete -> {
                    conn.disconnect()
                    return Session.CompleteLocal
                }
                is Opening.Reopen -> {
                    conn.disconnect()
                    if (++reopen > MAX_REOPEN) {
                        return Session.Rejected(
                            failAs(target, startedAt, DownloadErrors.NETWORK, "range negotiation failed (${opening.hint})", offset, resumable = false),
                        )
                    }
                    // 416 越界 / 206 Content-Range 错位 → 丢弃本地前缀从 0 重来
                    offset = 0
                    etag = null
                    lastModified = null
                    resumed = false
                    conn = try {
                        open(target, 0, null, null)
                    } catch (e: Exception) {
                        return Session.Rejected(
                            failAs(target, startedAt, DownloadErrors.NETWORK, e.message ?: "open failed", 0, resumable = false),
                        )
                    }
                }
                is Opening.HttpError -> {
                    conn.disconnect()
                    return Session.Rejected(
                        failAs(target, startedAt, DownloadErrors.NETWORK, "HTTP ${opening.code}", offset, resumable = true),
                    )
                }
            }
        }
    }

    private sealed class Opening {
        data class Serving(val start: Long, val etag: String?, val lastModified: String?) : Opening()
        data class FullBody(val etag: String?, val lastModified: String?) : Opening()
        data class Reopen(val hint: String) : Opening()
        data object Complete : Opening()
        data class HttpError(val code: Int) : Opening()
    }

    private fun open(target: DownloadTarget, offset: Long, etag: String?, lastModified: String?): HttpURLConnection {
        val conn = URL(target.url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=$offset-")
            // If-Range：validators 失配时服务端回 200 全量（→ FullBody 重来，§6.2）
            val validator = etag ?: lastModified
            if (validator != null) conn.setRequestProperty("If-Range", validator)
        }
        return conn
    }

    private fun evaluate(conn: HttpURLConnection, offset: Long, target: DownloadTarget): Opening {
        val code = conn.responseCode
        val etag = conn.getHeaderField("ETag")
        val lastModified = conn.getHeaderField("Last-Modified")
        return when {
            code == HttpURLConnection.HTTP_OK ->
                Opening.FullBody(etag, lastModified)
            code == HttpURLConnection.HTTP_PARTIAL -> {
                val start = contentRangeStart(conn.getHeaderField("Content-Range"))
                if (start != null && start == offset) Opening.Serving(start, etag, lastModified)
                else Opening.Reopen("206 start=$start offset=$offset")
            }
            code == 416 -> {
                val total = contentRangeTotal(conn.getHeaderField("Content-Range"))
                if (offset == target.size && (total == null || total == target.size)) {
                    Opening.Complete
                } else {
                    Opening.Reopen("416 offset=$offset total=$total size=${target.size}")
                }
            }
            code >= 400 -> Opening.HttpError(code)
            else -> Opening.Reopen("unexpected HTTP $code")
        }
    }

    /** Content-Range `bytes 100-199/200` 的起点 → 100；星号形态（416 用）或坏值 → null。 */
    internal fun contentRangeStart(value: String?): Long? =
        Regex("""^bytes\s+(\d+)-\d+/\d+$""").find(value?.trim() ?: "")?.groupValues?.get(1)?.toLongOrNull()

    /** Content-Range 总长：数字形态 `bytes 0-99/100` 与星号形态 `bytes star/100` 都 → 100；坏值 → null。 */
    internal fun contentRangeTotal(value: String?): Long? =
        Regex("""^bytes\s+(?:\d+-\d+|\*)/(\d+)$""").find(value?.trim() ?: "")?.groupValues?.get(1)?.toLongOrNull()

    // ---------------------------------------------------------------- 工具

    private fun feedFile(digest: MessageDigest, file: File, limit: Long) {
        RandomAccessFile(file, "r").use { raf ->
            var fed = 0L
            val buf = ByteArray(CHUNK)
            while (fed < limit) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), limit - fed).toInt())
                if (n < 0) break
                digest.update(buf, 0, n)
                fed += n
            }
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        feedFile(digest, file, file.length())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** zstd frame magic（前 4 字节 = 0xFD2FB528 LE）；空文件/读失败 → false。 */
    private fun hasZstdMagic(file: File): Boolean = try {
        val head = ByteArray(ZSTD_MAGIC.size)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        head.contentEquals(ZSTD_MAGIC)
    } catch (e: Exception) {
        false
    }

    /**
     * 落盘余量估算（DESIGN-disk-image-v2 §6.1/§9：「`free ≥ image_size 的实际估算
     * （≈ zst×3 或 rootfs 用量+余量），用 st_blocks 语义避免拿 apparent size 误判空间不足」）。
     *
     * 取舍（为什么不直接拿 image_size，也不假装能读 st_blocks）：
     *  - `image_size` 是 **apparent** 字节（GiB 级，含全零稀疏区）；`st_blocks×512` 才是
     *    真实占用，但 `.img` 此刻**还不存在**，无法 stat —— 下载预检拿 apparent 直接判
     *    必然误杀（零区不落盘，设计 §7.1：稀疏跳写后占用 ≈ rootfs 用量）；
     *  - 反向也不行：只按 zst+32MiB 放行会漏掉「下载得下、装不下」的组合；
     *  - 折中 = 启发式 `min(image_size, zst × 3)`：zstd -19 对镜像里的零段压缩比极高，
     *    实际占用（非零内容）≈ zst × 压缩比，取 ≥3 作为上界估计；`image_size` 本身是
     *    任何情况都到不了的绝对上限，拿它封顶。比单纯 zst×3 更保守的场景（高压缩比
     *    镜像）已被 min() 收敛到 apparent，不会要求一个装不下的空间。
     *  - 未知（imageSize ≤ 0，旧 4 参调用点）→ 返回 0 = **跳过检查**（退回 v1 只按
     *    zst+margin 的口径），不让「调用点尚未升级」变成误杀；升级到
     *    [DownloadTarget.from] 后自动生效。真实 st_blocks 校验仍在 install 期由 Store
     *    落 `.img.part` 时按实际写入失败即报（最终防线）。
     *
     * internal 供单测锁定口径。
     */
    internal fun landingEstimate(target: DownloadTarget): Long {
        val apparent = target.imageSize
        val zst = target.size
        if (apparent <= 0L || zst <= 0L) return 0L
        val byRatio = if (zst > Long.MAX_VALUE / 3) Long.MAX_VALUE else zst * 3
        return minOf(apparent, byRatio)
    }

    private fun fail(
        target: DownloadTarget,
        startedAt: Long,
        err: String,
        message: String,
        bytes: Long,
        resumable: Boolean,
    ): DownloadOutcome {
        emit(
            "download_fail",
            linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to bytes, "err" to err),
            startedAt,
        )
        return DownloadOutcome.Failure(DownloadFailure(err, message, resumable))
    }

    /** [negotiate] 内部版：只产出 failure（download_fail 已记）。 */
    private fun failAs(
        target: DownloadTarget,
        startedAt: Long,
        err: String,
        message: String,
        bytes: Long,
        resumable: Boolean,
    ): DownloadFailure {
        emit(
            "download_fail",
            linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to bytes, "err" to err),
            startedAt,
        )
        return DownloadFailure(err, message, resumable)
    }

    private fun emit(event: String, fields: Map<String, Any?>, startedAt: Long) {
        val withDur = LinkedHashMap(fields)
        withDur["dur_ms"] = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        runCatching { events(event, withDur) }
    }

    // ---------------------------------------------------------------- .part.info

    private data class SavedInfo(
        val imageId: String,
        val url: String,
        val size: Long,
        val sha256: String,
        val imageSize: Long,
        val imageSha256: String,
        val display: CatalogDisplay?,
        val etag: String?,
        val lastModified: String?,
    ) {
        /**
         * 续传判定：核心四元组（id/url/zst size/zst sha）必须完全一致；
         * 双摘要字段**仅在两侧都已知且不同时**才判失配 —— 旧 .part.info（无 image 字段）
         * 或调用点尚未升级（imageSize=0）时以本次 target 为准，不浪费已下的前缀。
         */
        fun matches(t: DownloadTarget): Boolean {
            val imageMetaMatches =
                (imageSize == 0L || t.imageSize == 0L || imageSize == t.imageSize) &&
                    (imageSha256.isEmpty() || t.imageSha256.isEmpty() ||
                        imageSha256.equals(t.imageSha256, ignoreCase = true))
            return imageId == t.imageId && url == t.url && size == t.size &&
                sha256 == t.sha256 && imageMetaMatches
        }
    }

    private fun writeInfo(file: File, target: DownloadTarget, etag: String?, lastModified: String?) {
        val map = LinkedHashMap<String, Any?>()
        map["image_id"] = target.imageId
        map["url"] = target.url
        map["size"] = target.size
        map["sha256"] = target.sha256
        // schema v2：双摘要 + 展示字段一并落盘，续传（pendingTargets → install 交接）才不丢
        map["image_size"] = target.imageSize
        map["image_sha256"] = target.imageSha256
        target.display?.let { d ->
            map["display_name"] = d.displayName
            d.identity?.let { map["identity"] = it }
            d.variant?.let { map["variant"] = it }
            d.version?.let { map["version"] = it }
            d.channel?.let { map["channel"] = it }
            d.notes?.let { map["notes"] = it }
        }
        if (etag != null) map["etag"] = etag
        if (lastModified != null) map["last_modified"] = lastModified
        runCatching { AtomicFiles.write(file, MiniJson.write(map)) }
    }

    private fun readInfo(file: File): SavedInfo? {
        return try {
            if (file.isFile) readInfoFields(MiniJson.parseObject(file.readText())) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readInfoFields(m: Map<String, Any?>): SavedInfo? {
        val imageId = MiniJson.str(m, "image_id") ?: return null
        val url = MiniJson.str(m, "url") ?: return null
        val size = MiniJson.long(m, "size") ?: return null
        val sha256 = MiniJson.str(m, "sha256") ?: return null
        val displayName = MiniJson.str(m, "display_name")
        return SavedInfo(
            imageId = imageId,
            url = url,
            size = size,
            sha256 = sha256,
            imageSize = MiniJson.long(m, "image_size") ?: 0L,
            imageSha256 = MiniJson.str(m, "image_sha256") ?: "",
            display = displayName?.let {
                CatalogDisplay(
                    displayName = it,
                    identity = MiniJson.str(m, "identity"),
                    variant = MiniJson.str(m, "variant"),
                    version = MiniJson.str(m, "version"),
                    channel = MiniJson.str(m, "channel"),
                    notes = MiniJson.str(m, "notes"),
                )
            },
            etag = MiniJson.str(m, "etag"),
            lastModified = MiniJson.str(m, "last_modified"),
        )
    }
}
