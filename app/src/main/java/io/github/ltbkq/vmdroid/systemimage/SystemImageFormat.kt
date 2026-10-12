/*
 * VMDroid - v2 系统镜像格式实现（M3，生产路径）。
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN-disk-image-v2.md §5.3（v2 读取算法/诊断阶梯）、§5.5/§6.1（安装校验时机：
 *     zst 流式校验 → 稀疏落盘 → image_sha256 双字段比对 → codec 结构校验 → 原子 rename）
 *   - docs/IMAGE-FORMAT-v2.md §5（读取算法唯一权威时序）、§7.1（.img.zst 容器）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 编译
 * （本文件不在 systemimage-selftest/README §2 的 kotlinc 清单内：那边测的是 v1 兼容路径）。
 *
 * 与只读工位文件的关系（**调用不重写**）：
 *   - `VmdDiskCodec.kt`（v2 读取侧）：probe/verifyFull/结构校验全部经它；
 *   - `VmdImageCodec.kt`（v1，只读参考）：本文件不用它解析，仅把 v2 reason 映射到
 *     UI 文案层认识的 `VmdImageReason`（ui/ 只读，见 [toVmdImageReason]）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.PushbackInputStream
import java.security.MessageDigest

/**
 * `VmdDiskReason`（IMAGE-FORMAT-v2 §5/§8 十一项）→ `VmdImageReason`（v1 reason 集合）。
 *
 * ui/ 的 `imageErrorMessage(context, reason, detail)` 只认 v1 枚举（ui/ 为只读工位文件），
 * 故 v2 拒绝在**文案层**折叠到语义最近的 v1 项；BootGuard 层不折叠 —— 见 [toBootGuard]
 * （`LEGACY_FORMAT` 在此保留为独立 reason）。
 */
fun VmdDiskReason.toVmdImageReason(): VmdImageReason = when (this) {
    VmdDiskReason.NOT_AN_IMAGE -> VmdImageReason.NOT_AN_IMAGE
    VmdDiskReason.LEGACY_FORMAT -> VmdImageReason.FORMAT_UNSUPPORTED // 文案："格式不受支持，请重新获取"
    VmdDiskReason.CORRUPT -> VmdImageReason.CORRUPT
    VmdDiskReason.FORMAT_UNSUPPORTED -> VmdImageReason.FORMAT_UNSUPPORTED
    VmdDiskReason.ARCH_MISMATCH -> VmdImageReason.ARCH_MISMATCH
    VmdDiskReason.IMAGE_ID_INVALID -> VmdImageReason.IMAGE_ID_INVALID
    VmdDiskReason.SSH_CAPABILITY_MISSING -> VmdImageReason.SSH_CAPABILITY_MISSING
    VmdDiskReason.SSH_PORT_INVALID -> VmdImageReason.SSH_PORT_INVALID
    VmdDiskReason.APP_TOO_OLD -> VmdImageReason.APP_TOO_OLD
    // v2 专属（v1 枚举无对应项）：折叠到"格式不受支持"文案，BootGuard reason 不折叠
    VmdDiskReason.BOOT_MODE_UNSUPPORTED -> VmdImageReason.FORMAT_UNSUPPORTED
    VmdDiskReason.CONTRACT_VERSION -> VmdImageReason.FORMAT_UNSUPPORTED
}

/**
 * `VmdDiskReason` → §7.4 权威 [BootGuardReason]（**M3 要求的映射**）。
 * `LEGACY_FORMAT` → `LEGACY_FORMAT`（BootGuard 拒绝 + UI 提示"旧格式，请重新下载"）；
 * 其余经 [toVmdImageReason] 的 `bootGuard` 字符串回查（[bootGuardReasonOf] 为唯一字符串表）。
 */
fun VmdDiskReason.toBootGuard(): BootGuardReason =
    if (this == VmdDiskReason.LEGACY_FORMAT) {
        BootGuardReason.LEGACY_FORMAT
    } else {
        bootGuardReasonOf(toVmdImageReason().bootGuard) ?: BootGuardReason.CORRUPT
    }

/** `VmdDiskCodec.ImageInfo` → 格式无关 [ImageSnapshot]（UI 读取链同形）。 */
fun snapshotOf(info: VmdDiskCodec.ImageInfo): ImageSnapshot {
    val img = info.manifest.image
    return ImageSnapshot(
        path = info.path,
        size = info.size,
        formatVersion = info.manifest.formatVersion ?: VmdDiskCodec.MAX_FORMAT_VERSION.toLong(),
        contentId = img.contentId,
        // §5.4：v2 的 rootfs 摘要只作旧字段日志/回退；判定键是 content_id
        rootfsSha256 = info.manifest.checksums["root_sha256"] ?: info.meta.rootSha256,
        contractVersion = info.manifest.contract.version,
        distroInit = img.distroInit,
        manifest = ImageSnapshot.ManifestView(
            ImageSnapshot.ImageView(
                id = img.id,
                displayName = img.displayName,
                variant = img.variant,
                identity = img.identity,
                version = img.version,
                systemVersion = img.systemVersion,
                arch = img.arch,
            ),
        ),
    )
}

/**
 * v2 格式实现（DESIGN-disk-image-v2 §7 改动面"SystemImageStore/Repository"的格式半边）。
 *
 * - [probe] = `VmdDiskCodec.read`（§5 阶段 1–3，含 v1 诊断阶梯 → LEGACY_FORMAT）；
 * - [verifyFull] = `VmdDiskCodec.verifyFull`（阶段 1–4，root 分区全字节 sha256）；
 * - [materialise] = §6.1 安装流：容器嗅探（zstd magic）→ `zstd -dc` 流式解压 /
 *   裸流拷贝 → **全零 4 KiB 块跳写稀疏落盘**（[SparseImageWriter]）→ 源/整镜像双摘要
 *   与 catalog 比对 → `VmdDiskCodec.read` 结构校验 → 交回 store 原子 rename。
 *
 * @param versionCode 应用 versionCode（APP_TOO_OLD 比较）
 * @param zstdBinary zstd 可执行文件 hint（Android = `filesDir/bin/zstd` 提取物；null → PATH）
 */
class V2SystemImageFormat(
    private val versionCode: () -> Long,
    private val zstdBinary: File? = null,
) : SystemImageFormat {
    override val name: String = "v2"
    override val formatVersion: Long = VmdDiskCodec.MAX_FORMAT_VERSION.toLong()

    override fun probe(file: File): FormatVerdict = try {
        FormatVerdict.Ok(snapshotOf(VmdDiskCodec.read(file, versionCode())))
    } catch (e: VmdDiskException) {
        // 诊断阶梯（§5.3）：CORRUPT / LEGACY_FORMAT / NOT_AN_IMAGE … 原样带出
        FormatVerdict.Reject(e.reason.toVmdImageReason(), e.reason.toBootGuard(), e.message ?: e.reason.name)
    } catch (e: IOException) {
        FormatVerdict.Reject(VmdImageReason.IO_ERROR, BootGuardReason.CORRUPT, e.message ?: "io error")
    }

    override fun verifyFull(file: File) {
        try {
            VmdDiskCodec.verifyFull(file, versionCode())
        } catch (e: VmdDiskException) {
            throw VmdImageException.of(e.message ?: e.reason.name, e.reason.toVmdImageReason(), e)
        }
    }

    override fun materialise(source: InstallSource, tmp: File, request: InstallRequest): InstallPayload {
        val raw = when (source) {
            is InstallSource.Stream -> source.open()
            is InstallSource.FileSource -> FileInputStream(source.file)
        }
        // 带 4 字节回推的缓冲流：嗅探容器（§6.1 "zst 或裸 img"）后原样交还给解压/拷贝
        val input = PushbackInputStream(BufferedInputStream(raw, SNIFF_BUFFER), 4)
        try {
            val head = ByteArray(4)
            var got = 0
            while (got < head.size) {
                val n = input.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            if (got > 0) input.unread(head, 0, got)
            val compressed = ZstdInflator.isZstdMagic(head) && got == 4

            val imageSha256: String
            val imageSize: Long
            if (compressed) {
                // 第一道：sha256(zst) 边喂 zstd 边算（下载期校验的安装期复核，§6.1）
                val zstDigest = MessageDigest.getInstance("SHA-256")
                val r = ZstdInflator.inflate(input, tmp, zstdBinary, zstDigest, request.onProgress)
                imageSha256 = r.imageSha256
                imageSize = r.imageSize
                val expectedZst = request.zstSha256
                if (expectedZst != null && !expectedZst.equals(r.inputSha256, ignoreCase = true)) {
                    throw VmdImageException.of(
                        "sha256(.img.zst) mismatch: expected $expectedZst, got ${r.inputSha256}",
                        VmdImageReason.PAYLOAD_CORRUPT,
                    )
                }
                if (request.zstSize != null && r.inputBytes >= 0 && request.zstSize != r.inputBytes) {
                    throw VmdImageException.of(
                        "size(.img.zst) mismatch: expected ${request.zstSize}, got ${r.inputBytes}",
                        VmdImageReason.PAYLOAD_CORRUPT,
                    )
                }
            } else {
                // SAF 导入裸 .img：跳过解压，但同样稀疏落盘 + 算 image_sha256（§6.1 兼容行）
                val r = ZstdInflator.copySparse(input, tmp, request.onProgress)
                imageSha256 = r.sha256
                imageSize = r.size
            }

            // 第二道：catalog `image_sha256` / `image_size` 双字段比对
            request.imageSha256?.let { expected ->
                if (!expected.equals(imageSha256, ignoreCase = true)) {
                    throw VmdImageException.of(
                        "image_sha256 mismatch: expected $expected, got $imageSha256",
                        VmdImageReason.PAYLOAD_CORRUPT,
                    )
                }
            }
            request.imageSize?.let { expected ->
                if (expected != imageSize) {
                    throw VmdImageException.of(
                        "image_size mismatch: expected $expected, got $imageSize",
                        VmdImageReason.PAYLOAD_CORRUPT,
                    )
                }
            }

            // 结构校验（§5 阶段 1–3；v1 输入在此被诊断阶梯判 LEGACY_FORMAT）
            val info = try {
                VmdDiskCodec.read(tmp, versionCode())
            } catch (e: VmdDiskException) {
                throw VmdImageException.of(e.message ?: e.reason.name, e.reason.toVmdImageReason(), e)
            }
            val id = info.manifest.image.id
                ?: throw VmdImageException.of("manifest.image.id missing", VmdImageReason.IMAGE_ID_INVALID)
            if (request.imageId != null && request.imageId != id) {
                throw VmdImageException.of(
                    "manifest image.id '$id' != expected image_id '${request.imageId}'",
                    VmdImageReason.IMAGE_ID_INVALID,
                )
            }
            return InstallPayload(
                imageId = id,
                sha256 = imageSha256,
                size = imageSize,
                contentId = info.manifest.image.contentId,
                identity = info.manifest.image.identity,
                contractVersion = info.manifest.contract.version,
                distroInit = info.manifest.image.distroInit,
            )
        } finally {
            runCatching { input.close() } // 级联关闭 raw；喂入线程已随 inflate 结束
        }
    }

    private companion object {
        const val SNIFF_BUFFER = 64 * 1024
    }
}
