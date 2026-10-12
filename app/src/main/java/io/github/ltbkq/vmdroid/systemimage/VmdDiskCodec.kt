/*
 * VmdDiskCodec — VMDroid v2 完整磁盘镜像 (.img) 读取侧解析 / 校验（IMAGE-FORMAT-v2）。
 *
 * 实现依据（冻结规格）：
 *   - docs/IMAGE-FORMAT-v2.md v2.0（§2 磁盘布局 / §3 meta 分区 / §4 manifest /
 *     §5 读取算法【唯一权威时序】/ §8 兼容矩阵 / §9 测试向量）
 *   - docs/DESIGN-disk-image-v2.md §5.3（读取算法与诊断阶梯）
 *   - build-tools/mkdisk.sh verify_img()（原 Podroid-Debian/tools，已迁移）（D11：mkdisk 为格式参照实现，
 *     规格与实现冲突时以其行为对齐 —— 本文件的 GPT/meta 校验逐步对照其单一解析器）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 独立编译，
 * 并以 systemimage-selftest/SelfTestV2.kt（无 JUnit 依赖）自测；JSON 复用 MiniJson。
 *
 * 与 v1（VmdImageCodec.kt）的关系：本文件不引用 v1 任何类型。v2 reason 集合是 §5/§8 的
 * 11 项（v1 的 TRUNCATED / PAYLOAD_CORRUPT / MANIFEST_INVALID / IO_ERROR 等细分在 v2
 * 归并进 CORRUPT / NOT_AN_IMAGE）；v1 镜像经 §5 诊断阶梯判 LEGACY_FORMAT（§8：BootGuard
 * 枚举新增值 —— BootGuard reason 表的同步不在本文件内做，BootGuard.kt 由其工位维护）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * 校验失败 reason —— IMAGE-FORMAT-v2 §5/§8 权威 11 项（命名即 §5 伪代码注释与 §8 矩阵）。
 *
 * | reason                 | §5 触发点                                                  |
 * |------------------------|------------------------------------------------------------|
 * | NOT_AN_IMAGE           | size<64KiB；无保护性 MBR 且非 v1 家族；manifest 非本格式/结构非法 |
 * | LEGACY_FORMAT          | 无保护性 MBR + 尾部 VMDIMG01 / 偏移 0 hsqs（诊断阶梯，老用户迁移提示） |
 * | CORRUPT                | GPT 签名/CRC/备份/几何/越界重叠、缺 meta/root 非最后、meta 魔数/file_size/flags/边界、manifest sha、disk↔GPT/摘要不一致、root 分区 sha |
 * | FORMAT_UNSUPPORTED     | meta.format_version>2；manifest.format_version≠2           |
 * | ARCH_MISMATCH          | image.arch != "arm64"                                      |
 * | IMAGE_ID_INVALID       | image.id 缺失或不匹配 ^[a-z0-9][a-z0-9._-]{0,63}$           |
 * | SSH_CAPABILITY_MISSING | capabilities.ssh != true（fail-closed）                    |
 * | SSH_PORT_INVALID       | accounts.ssh_port != 22（类型非整数视同 ≠22）               |
 * | APP_TOO_OLD            | app.min_version_code > currentVersionCode                  |
 * | BOOT_MODE_UNSUPPORTED  | boot.mode != "uefi"                                        |
 * | CONTRACT_VERSION       | contract.version != 2                                      |
 *
 * 注：manifest JSON 解析失败/块类型非法在 v1 属 MANIFEST_INVALID（bootGuard→NOT_AN_IMAGE），
 * v2 集合无 MANIFEST_INVALID，归 NOT_AN_IMAGE（§5 "非本镜像"同义）。
 */
enum class VmdDiskReason {
    NOT_AN_IMAGE,
    LEGACY_FORMAT,
    CORRUPT,
    FORMAT_UNSUPPORTED,
    ARCH_MISMATCH,
    IMAGE_ID_INVALID,
    SSH_CAPABILITY_MISSING,
    SSH_PORT_INVALID,
    APP_TOO_OLD,
    BOOT_MODE_UNSUPPORTED,
    CONTRACT_VERSION,
}

/**
 * 镜像校验失败（唯一异常类型，sealed：外部不可继承，只能经 [of] 构造）。
 * [reason] 为 §5/§8 权威 11 项之一；[reasonName] 可直接写 image.log / 夹具对账。
 */
sealed class VmdDiskException(
    message: String,
    val reason: VmdDiskReason,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** reason 枚举名（= §5/§8 用词；写 image.log 用）。 */
    val reasonName: String get() = reason.name

    /** 唯一具体子类。 */
    private class Impl(message: String, reason: VmdDiskReason, cause: Throwable?) :
        VmdDiskException(message, reason, cause)

    companion object {
        /** 构造一个校验失败异常。 */
        fun of(message: String, reason: VmdDiskReason, cause: Throwable? = null): VmdDiskException =
            Impl(message, reason, cause)
    }
}

/**
 * `.img` 读取器：[read]（§5 阶段 1–3 快检）、[validate]（manifest 语义复检）、
 * [verifyFull]（阶段 4 全量 root sha256）、[readEspFile]/[listEspDir]（FAT32 ESP 只读访问，
 * AVF 回退方案 B 与诊断用）。
 *
 * 所有校验失败抛 [VmdDiskException]，绝不返回半成品结果；读取方 MUST NOT 拒绝的
 * 生成侧约束（1 MiB 对齐、S 档位、尾隙全零、ESP 文件清单、grub.cfg 内容、根分区内部
 * 一致性 —— §5）一律不校验。
 */
object VmdDiskCodec {

    // ---- 磁盘布局常量（IMAGE-FORMAT-v2 §2，全小端 GPT） ----
    const val SECTOR_SIZE = 512
    const val GPT_HEADER_SIZE = 92
    const val GPT_ENTRY_SIZE = 128
    const val GPT_ENTRY_COUNT = 128
    const val GPT_ENTRIES_BYTES = GPT_ENTRY_SIZE * GPT_ENTRY_COUNT   // 16 KiB
    const val FIRST_USABLE_LBA = 34L
    const val MIN_FILE_SIZE = 65536L                                  // §5: size < 64 KiB → NOT_AN_IMAGE
    const val MIB = 1048576L

    // ---- meta 分区常量（IMAGE-FORMAT-v2 §3） ----
    const val META_MAGIC = "VMDIMG02"
    const val LEGACY_MAGIC = "VMDIMG01"                               // v1 footer 魔数（诊断阶梯）
    const val META_HEADER_SIZE = 140
    const val MIN_META_SIZE = 148L                                    // 148 ≤ meta_size ≤ 分区大小
    const val MAX_FORMAT_VERSION = 2
    const val FLAGS_MASK = 0x3L
    const val FLAG_HAS_KERNEL_ESP = 0x1L
    const val FLAG_HAS_INITRD_ESP = 0x2L
    const val V1_FOOTER_SIZE = 4096L                                  // v1 footer 窗口（阶梯读 size-4096）

    /** meta 分区唯一 GUID（固定常量，ASCII "VMDMETA2" 的 mixed-endian 形态；§2.2）。 */
    const val META_PARTITION_GUID = "564d444d-4554-4132-8000-000000000001"

    // ---- 分区名（§2.2；读取方 root 按 name + 必须最后，meta 按固定 GUID） ----
    const val ROOT_PART_NAME = "vmd-root"
    const val ESP_PART_NAME = "VMDROID-EFI"

    // ---- manifest 常量（IMAGE-FORMAT-v2 §4） ----
    const val MANIFEST_FORMAT = "vmdroid-system-image"
    const val REQUIRED_ARCH = "arm64"
    const val REQUIRED_SSH_PORT = 22L
    const val REQUIRED_BOOT_MODE = "uefi"
    const val REQUIRED_CONTRACT_VERSION = 2L
    const val IMAGE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$"

    /** verifyFull 流式缓冲（阶段 4，8 MiB）。 */
    const val ROOT_SHA256_BUFFER = 8 * 1024 * 1024

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /** GPT header（LBA1 主表；§2.2 92 字节）。 */
    data class GptHeader(
        val revision: Long,
        val currentLba: Long,
        val backupLba: Long,
        val firstUsable: Long,
        val lastUsable: Long,
        val diskGuid: String,
        val entriesLba: Long,
        val entryCount: Long,
        val entrySize: Long,
    )

    /**
     * 分区表项（§2.2）。GUID 文本为 mixed-endian 规范形（小写 8-4-4-4-12，见 [guidToText]）。
     * [index] 为表项序号（1 起，仅供工具阅读 —— §2.2 约束 2：定位不依赖序号）。
     * [offsetBytes]/[sizeBytes] 仅在经 parseGpt 边界校验后构造时成立。
     */
    data class Partition(
        val index: Int,
        val typeGuid: String,
        val uniqueGuid: String,
        val firstLba: Long,
        val lastLba: Long,
        val name: String,
    ) {
        val sectorCount: Long get() = lastLba - firstLba + 1
        val offsetBytes: Long get() = Math.multiplyExact(firstLba, SECTOR_SIZE.toLong())
        val sizeBytes: Long get() = Math.multiplyExact(sectorCount, SECTOR_SIZE.toLong())
    }

    /** meta 分区头 140 B（IMAGE-FORMAT-v2 §3；sha 字段为小写 hex）。 */
    data class MetaHeader(
        val formatVersion: Long,
        val metaSize: Long,
        val fileSize: Long,
        val manifestOffset: Long,
        val manifestSize: Long,
        val manifestSha256: String,
        val rootOffset: Long,
        val rootSize: Long,
        val rootSha256: String,
        val espOffset: Long,
        val espSize: Long,
        val flags: Long,
    )

    data class ImageBlock(
        val id: String?,
        val displayName: String?,
        val identity: String?,
        val variant: String?,
        val version: String?,
        val systemVersion: Long?,
        val arch: String?,
        val distroName: String?,
        val distroRelease: String?,
        val distroInit: String?,
        val createdAt: String?,
        val contentId: String?,
    )

    /** `disk.*` 子块（值字段为 null = 缺失或类型不符；仅 validateManifest 点名的字段参与校验）。 */
    data class DiskPartSpec(
        val part: Long?,
        val fstype: String?,
        val firstLba: Long?,
        val lastLba: Long?,
        val size: Long?,
        val partuuid: String?,
        val fatUuid: String?,
    )

    data class DiskBlock(
        val table: String?,
        val sectorSize: Long?,
        val fileSize: Long?,
        val esp: DiskPartSpec?,
        val meta: DiskPartSpec?,
        val root: DiskPartSpec?,
        val kernelPath: String?,
        val initrdPath: String?,
    )

    data class ContractBlock(
        val version: Long?,
        val markers: List<String>,
        val ttys: Map<String, String>,
        val kernelImageSha256: String?,
    )

    data class CapabilitiesBlock(
        val ssh: Boolean,
        val x11: Boolean,
        val desktop: Boolean,
        val containers: Boolean,
    )

    data class SshAccount(val user: String, val password: String?, val sudo: Boolean)

    data class AccountsBlock(
        val ssh: List<SshAccount>,
        val defaultUser: String?,
        /** null = 存在但类型非整数（§4.2 "类型非整数视同 ≠22" → SSH_PORT_INVALID）。 */
        val sshPort: Long?,
    )

    data class AppBlock(
        /** null = 缺块/缺键/类型非整数 → 校验期拒绝（IMP-D05 必需字段，D11 从参照实现）。 */
        val minVersionCode: Long?,
    )

    data class BootBlock(
        val mode: String?,
        val machine: String?,
        val cpu: String?,
        val append: String?,
        val kernelSha256: String?,
        val initrdSha256: String?,
        val drives: Map<String, String>,
    )

    /**
     * v2 manifest（IMAGE-FORMAT-v2 §4）。缺省解释按 §4.2：
     * - 无 `capabilities` → {ssh:true, x11:true, containers:true}（其余 false）
     * - 有块缺 `ssh` → false（fail-closed）
     * - 无 `accounts` → 仅 root、ssh_port = 22
     * - 无 `disk` → 校验期拒绝（CORRUPT，v2 没有布局信息不可用）
     * - 无 `app` / 缺 `min_version_code` → null → 校验期拒绝（IMP-D05 必需字段，D11）
     * - 无 `contract` → version = null → 校验期 CONTRACT_VERSION（v2 必须 ==2）
     * 未知字段保留在 [unknown]（前向兼容，绝不因未知字段拒绝 —— §4.2）。
     */
    data class Manifest(
        val format: String?,
        val formatVersion: Long?,
        val image: ImageBlock,
        val disk: DiskBlock?,
        val contract: ContractBlock,
        val capabilities: CapabilitiesBlock,
        val accounts: AccountsBlock,
        val app: AppBlock,
        val boot: BootBlock?,
        val checksums: Map<String, String>,
        val unknown: Map<String, Any?>,
    )

    /** [read] 返回值：已通过 §5 阶段 1–3 全部校验（[currentVersionCode] 给定时含 APP_TOO_OLD）。 */
    data class ImageInfo(
        val path: String,
        val size: Long,
        val gpt: GptHeader,
        /** 按 firstLba 升序（解析期已校验严格递增、不重叠、在可用区间内）。 */
        val partitions: List<Partition>,
        val metaPartition: Partition,
        val espPartition: Partition,
        val rootPartition: Partition,
        val meta: MetaHeader,
        val manifest: Manifest,
    )

    // ------------------------------------------------------------------
    // 读取（IMAGE-FORMAT-v2 §5，唯一权威时序）
    // ------------------------------------------------------------------

    /** 同 [read(File)]。 */
    @JvmStatic
    fun read(path: String, currentVersionCode: Long? = null): ImageInfo =
        read(File(path), currentVersionCode)

    /**
     * 读取并校验 v2 镜像（§5 阶段 1–3；阶段 4 见 [verifyFull]）。
     *
     * 时序：size≥64KiB → 保护性 MBR（否则诊断阶梯 LEGACY/NOT_AN_IMAGE）→ GPT 全套校验 →
     * 分区定位（meta 固定 GUID / root name+最后）→ meta 双魔数与边界 → manifest sha →
     * JSON 与语义（§4.2）。
     *
     * @param currentVersionCode 应用 versionCode；非 null 时执行 APP_TOO_OLD 比较。
     * @throws VmdDiskException 任一校验失败（reason 见 [VmdDiskReason]）。
     */
    @JvmStatic
    fun read(file: File, currentVersionCode: Long? = null): ImageInfo {
        try {
            if (!file.isFile || !file.canRead()) {
                throw VmdDiskException.of(
                    "io: not a readable file: $file（v2 集合无 IO_ERROR，归 CORRUPT）",
                    VmdDiskReason.CORRUPT,
                )
            }
            val size = file.length()

            // ---- 阶段 0：最小尺寸 ----
            if (size < MIN_FILE_SIZE) {
                throw VmdDiskException.of(
                    "file too small ($size < $MIN_FILE_SIZE bytes): not a VMDroid system image",
                    VmdDiskReason.NOT_AN_IMAGE,
                )
            }

            // ---- 阶段 1：是不是 v2 磁盘（保护性 MBR → 诊断阶梯 → GPT） ----
            val mbr = readAt(file, 0L, SECTOR_SIZE)
            if (!hasProtectiveMbr(mbr)) {
                // 诊断阶梯（IMP-D02 v2 版，§5）：先判 v1 家族（老用户迁移提示），再判非镜像
                if (size >= V1_FOOTER_SIZE) {
                    val tail = readAt(file, size - V1_FOOTER_SIZE, 8)
                    if (ascii(tail, 0, 8) == LEGACY_MAGIC) {
                        throw VmdDiskException.of(
                            "v1 旧格式（尾部 $LEGACY_MAGIC）：请重新获取 v2 镜像",
                            VmdDiskReason.LEGACY_FORMAT,
                        )
                    }
                }
                if (ascii(readAt(file, 0, 4), 0, 4) == "hsqs") {
                    throw VmdDiskException.of(
                        "v1/裸 squashfs 旧格式（hsqs @0）：请重新获取 v2 镜像",
                        VmdDiskReason.LEGACY_FORMAT,
                    )
                }
                throw VmdDiskException.of("非 VMDroid 系统镜像（无保护性 MBR）", VmdDiskReason.NOT_AN_IMAGE)
            }
            val gpt = parseGpt(file, size)

            // ---- 阶段 2：meta 结构 ----
            val meta = parseMeta(file, size, gpt)

            // ---- 阶段 3：manifest 语义 ----
            val manifest = parseManifest(file, gpt, meta)
            validateManifest(manifest, size, gpt, meta, currentVersionCode)

            return ImageInfo(
                path = file.absolutePath,
                size = size,
                gpt = gpt.header,
                partitions = gpt.partitions,
                metaPartition = gpt.metaPartition,
                espPartition = gpt.espPartition,
                rootPartition = gpt.rootPartition,
                meta = meta,
                manifest = manifest,
            )
        } catch (e: VmdDiskException) {
            throw e
        } catch (e: IOException) {
            throw VmdDiskException.of("io error while reading $file: ${e.message}", VmdDiskReason.CORRUPT, e)
        }
    }

    /**
     * manifest 语义复检（激活前 / 换 currentVersionCode 时用；幂等）。
     * 顺序 = §5 阶段 3：format → format_version → id → arch → contract → ssh → ssh_port →
     * boot.mode → app → disk↔GPT → 摘要一致性。
     *
     * @throws VmdDiskException 对应 §5 reason。
     */
    @JvmStatic
    fun validate(info: ImageInfo, currentVersionCode: Long? = null) {
        validateManifest(info.manifest, info.size, gptOf(info), info.meta, currentVersionCode)
    }

    // ------------------------------------------------------------------
    // 阶段 4：full 校验（非启动前快检；IMAGE-FORMAT-v2 §5 / 校验时机表）
    // ------------------------------------------------------------------

    /**
     * 阶段 4：重算 root 分区全字节 sha256（8 MiB 流式缓冲）与 meta.root_sha256 比对。
     * 手动"校验"动作调用；[info] 来自 [read] 时阶段 1–3 已通过。
     *
     * @throws VmdDiskException [VmdDiskReason.CORRUPT] 摘要不符 / 文件被并发截断。
     */
    @JvmStatic
    fun verifyFull(info: ImageInfo) {
        val file = File(info.path)
        val actual = sha256Range(file, info.meta.rootOffset, info.meta.rootSize, ROOT_SHA256_BUFFER)
        if (!actual.equals(info.meta.rootSha256, ignoreCase = true)) {
            throw VmdDiskException.of(
                "root 分区 sha256 不符 (meta=${info.meta.rootSha256}, actual=$actual)",
                VmdDiskReason.CORRUPT,
            )
        }
    }

    /** 阶段 1–3 + 阶段 4；返回 [ImageInfo] 供后续 ESP 访问复用。 */
    @JvmStatic
    fun verifyFull(file: File, currentVersionCode: Long? = null): ImageInfo {
        val info = read(file, currentVersionCode)
        verifyFull(info)
        return info
    }

    // ------------------------------------------------------------------
    // ESP（FAT32 只读访问 —— AVF 回退方案 B：读 /vmlinuz、/initrd.img；诊断用）
    // ------------------------------------------------------------------

    /**
     * 读 ESP 内单个文件（路径形如 `/vmlinuz`、`/vmdroid/manifest.json`）。
     * 仅做 GPT 定位 + FAT32 解析，不做阶段 2/3（ESP 内容不在启动快检范围 —— §9 向量 23）。
     *
     * @return 文件内容；[path] 不存在或是目录时返回 null。
     * @throws VmdDiskException [VmdDiskReason.CORRUPT] ESP 缺失 / FAT32 结构损坏 / IO 失败。
     */
    @JvmStatic
    fun readEspFile(file: File, path: String): ByteArray? {
        val gpt = parseGpt(file, file.length())
        return EspFat32.open(file, gpt.espPartition.offsetBytes, gpt.espPartition.sizeBytes)
            .use { it.readFile(path) }
    }

    /** 同 [readEspFile(File, String)]，复用已校验的 [info]（免二次 GPT 解析）。 */
    @JvmStatic
    fun readEspFile(info: ImageInfo, path: String): ByteArray? =
        EspFat32.open(File(info.path), info.espPartition.offsetBytes, info.espPartition.sizeBytes)
            .use { it.readFile(path) }

    /** 列 ESP 目录（诊断；`/` 为根）。返回 null = 路径不存在或非目录。 */
    @JvmStatic
    fun listEspDir(file: File, path: String = "/"): List<EspFat32.Entry>? {
        val gpt = parseGpt(file, file.length())
        return EspFat32.open(file, gpt.espPartition.offsetBytes, gpt.espPartition.sizeBytes)
            .use { it.list(path) }
    }

    /** 同 [listEspDir(File, String)]，复用已校验的 [info]。 */
    @JvmStatic
    fun listEspDir(info: ImageInfo, path: String = "/"): List<EspFat32.Entry>? =
        EspFat32.open(File(info.path), info.espPartition.offsetBytes, info.espPartition.sizeBytes)
            .use { it.list(path) }

    // ------------------------------------------------------------------
    // 阶段 1：保护性 MBR + GPT（§2.2；对照 mkdisk.sh verify_img stage 1）
    // ------------------------------------------------------------------

    private data class Gpt(
        val header: GptHeader,
        val partitions: List<Partition>,
        val metaPartition: Partition,
        val espPartition: Partition,
        val rootPartition: Partition,
    )

    private fun hasProtectiveMbr(mbr: ByteArray): Boolean =
        mbr[510] == 0x55.toByte() && mbr[511] == 0xAA.toByte() && mbr[450] == 0xEE.toByte()

    /**
     * 解析并全量校验 GPT（签名 / header CRC32 / 备份位置 / first-last usable / 表项几何 /
     * 表项 CRC32 / 备份表一致 / 严格递增不重叠 / 落在可用区间 / meta 定位 / root 最后）。
     * 任一失败 → [VmdDiskReason.CORRUPT]。
     */
    private fun parseGpt(file: File, size: Long): Gpt {
        val mbr = readAt(file, 0L, SECTOR_SIZE)
        if (!hasProtectiveMbr(mbr)) {
            throw VmdDiskException.of("GPT 阶段：无保护性 MBR（0x55AA / 0xEE）", VmdDiskReason.CORRUPT)
        }
        // 64 KiB 门槛在 read() 已保证；readEspFile 直连时兜底
        if (size < (FIRST_USABLE_LBA + 1) * SECTOR_SIZE) {
            throw VmdDiskException.of("file too small for a GPT disk ($size bytes)", VmdDiskReason.CORRUPT)
        }
        val lastLba = size / SECTOR_SIZE - 1        // §2.2: last = file_size/512 − 1（截断→backup 失配）

        // ---- GPT header（LBA1，92 B） ----
        val hdr = readAt(file, SECTOR_SIZE.toLong(), GPT_HEADER_SIZE)
        if (ascii(hdr, 0, 8) != "EFI PART") {
            throw VmdDiskException.of("GPT 头缺失（LBA1 签名非 \"EFI PART\"）", VmdDiskReason.CORRUPT)
        }
        val headerSize = u32(hdr, 12)
        if (headerSize != GPT_HEADER_SIZE.toLong()) {
            throw VmdDiskException.of("GPT header size=$headerSize != $GPT_HEADER_SIZE", VmdDiskReason.CORRUPT)
        }
        // header CRC32：本字段（16..20）置 0 后对前 92 字节计算
        val crc = CRC32()
        crc.update(hdr, 0, 16)
        crc.update(byteArrayOf(0, 0, 0, 0))
        crc.update(hdr, 20, GPT_HEADER_SIZE - 20)
        val headerCrc = u32(hdr, 16)
        if (crc.value != headerCrc) {
            throw VmdDiskException.of(
                "GPT header CRC32 不符 (stored=$headerCrc, computed=${crc.value})",
                VmdDiskReason.CORRUPT,
            )
        }
        val currentLba = u64Checked(hdr, 24, "current_lba")
        val backupLba = u64Checked(hdr, 32, "backup_lba")
        val firstUsable = u64Checked(hdr, 40, "first_usable")
        val lastUsable = u64Checked(hdr, 48, "last_usable")
        val diskGuid = guidToText(hdr.copyOfRange(56, 72))
        val entriesLba = u64Checked(hdr, 72, "entries_lba")
        val entryCount = u32(hdr, 80)
        val entrySize = u32(hdr, 84)
        val entriesCrc = u32(hdr, 88)

        if (currentLba != 1L) {
            throw VmdDiskException.of("GPT current LBA=$currentLba != 1", VmdDiskReason.CORRUPT)
        }
        if (backupLba != lastLba) {
            throw VmdDiskException.of(
                "GPT backup LBA=$backupLba != $lastLba（文件被截断/追加？）",
                VmdDiskReason.CORRUPT,
            )
        }
        if (firstUsable != FIRST_USABLE_LBA || lastUsable != lastLba - 33) {
            throw VmdDiskException.of(
                "GPT 可用区间 [$firstUsable,$lastUsable] != [$FIRST_USABLE_LBA,${lastLba - 33}]",
                VmdDiskReason.CORRUPT,
            )
        }
        if (entryCount != GPT_ENTRY_COUNT.toLong() || entrySize != GPT_ENTRY_SIZE.toLong()) {
            throw VmdDiskException.of(
                "GPT 表项几何 $entryCount/$entrySize != $GPT_ENTRY_COUNT/$GPT_ENTRY_SIZE",
                VmdDiskReason.CORRUPT,
            )
        }

        // ---- 表项（16 KiB @ entriesLba） ----
        if (entriesLba < 0 || entriesLba > size / SECTOR_SIZE) {
            throw VmdDiskException.of("GPT entries LBA $entriesLba 越界", VmdDiskReason.CORRUPT)
        }
        val entries = readAt(file, Math.multiplyExact(entriesLba, SECTOR_SIZE.toLong()), GPT_ENTRIES_BYTES)
        val eCrc = CRC32()
        eCrc.update(entries)
        if (eCrc.value != entriesCrc) {
            throw VmdDiskException.of(
                "GPT 表项 CRC32 不符 (stored=$entriesCrc, computed=${eCrc.value})",
                VmdDiskReason.CORRUPT,
            )
        }
        // 备份表项（LBA last-32）必须与主表逐字节一致
        val backupEntries = readAt(file, Math.multiplyExact(lastLba - 32, SECTOR_SIZE.toLong()), GPT_ENTRIES_BYTES)
        if (!backupEntries.contentEquals(entries)) {
            throw VmdDiskException.of("GPT 备份表项与主表不一致", VmdDiskReason.CORRUPT)
        }

        // ---- 解析表项（type GUID 全零 = 空槽） ----
        val parsed = ArrayList<Partition>(GPT_ENTRY_COUNT)
        for (i in 0 until GPT_ENTRY_COUNT) {
            val off = i * GPT_ENTRY_SIZE
            if (allZero(entries, off, 16)) continue
            val first = u64Signed(entries, off + 32)
            val last = u64Signed(entries, off + 40)
            if (first < 0 || last < 0 || first > last) {
                throw VmdDiskException.of(
                    "分区表项 #${i + 1} first/last 非法 (first=$first, last=$last)",
                    VmdDiskReason.CORRUPT,
                )
            }
            // 名称：UTF-16LE 72 B，NUL 截断
            val nameBytes = entries.copyOfRange(off + 56, off + 128)
            val name = String(nameBytes, Charsets.UTF_16LE).substringBefore('\u0000')
            parsed.add(
                Partition(
                    index = i + 1,
                    typeGuid = guidToText(entries.copyOfRange(off, off + 16)),
                    uniqueGuid = guidToText(entries.copyOfRange(off + 16, off + 32)),
                    firstLba = first,
                    lastLba = last,
                    name = name,
                ),
            )
        }
        if (parsed.isEmpty()) {
            throw VmdDiskException.of("GPT 分区表为空", VmdDiskReason.CORRUPT)
        }

        // ---- 结构约束：严格递增、不重叠、落在可用区间（§5；按 firstLba 排序后比较） ----
        val parts = parsed.sortedBy { it.firstLba }
        for (k in 1 until parts.size) {
            if (parts[k].firstLba <= parts[k - 1].lastLba) {
                throw VmdDiskException.of(
                    "分区重叠/乱序：#${parts[k - 1].index}" +
                        "[${parts[k - 1].firstLba},${parts[k - 1].lastLba}] 与 " +
                        "#${parts[k].index}[${parts[k].firstLba},${parts[k].lastLba}]",
                    VmdDiskReason.CORRUPT,
                )
            }
        }
        for (p in parts) {
            if (p.firstLba < FIRST_USABLE_LBA || p.lastLba > lastUsable) {
                throw VmdDiskException.of(
                    "分区越界：#${p.index} '${p.name}' [${p.firstLba},${p.lastLba}] ∉ " +
                        "[$FIRST_USABLE_LBA,$lastUsable]",
                    VmdDiskReason.CORRUPT,
                )
            }
        }

        // ---- meta 定位（固定唯一 GUID；§2.2 约束 2） ----
        val metas = parts.filter { it.uniqueGuid == META_PARTITION_GUID }
        if (metas.size != 1) {
            throw VmdDiskException.of(
                "缺 meta 分区（唯一 GUID 应为 $META_PARTITION_GUID，命中 ${metas.size} 个）",
                VmdDiskReason.CORRUPT,
            )
        }

        // ---- root 按 name == "vmd-root" 且必须是最后分区（§2.2 约束 1 / §5） ----
        val roots = parts.filter { it.name == ROOT_PART_NAME }
        if (roots.size != 1) {
            throw VmdDiskException.of(
                "'$ROOT_PART_NAME' 分区数=${roots.size}（应恰好 1 个）",
                VmdDiskReason.CORRUPT,
            )
        }
        val lastPart = parts[parts.size - 1]
        if (roots[0] !== lastPart) {
            throw VmdDiskException.of(
                "root 非最后分区（#${roots[0].index} @${roots[0].firstLba}，" +
                    "末分区为 #${lastPart.index} '${lastPart.name}'）",
                VmdDiskReason.CORRUPT,
            )
        }

        // ---- ESP 定位（name == "VMDROID-EFI"；mkdisk 单一解析器同断言；ESP 文件清单本身不校验） ----
        val esps = parts.filter { it.name == ESP_PART_NAME }
        if (esps.size != 1) {
            throw VmdDiskException.of(
                "'$ESP_PART_NAME' 分区数=${esps.size}（应恰好 1 个）",
                VmdDiskReason.CORRUPT,
            )
        }

        return Gpt(
            header = GptHeader(
                revision = u32(hdr, 8),
                currentLba = currentLba,
                backupLba = backupLba,
                firstUsable = firstUsable,
                lastUsable = lastUsable,
                diskGuid = diskGuid,
                entriesLba = entriesLba,
                entryCount = entryCount,
                entrySize = entrySize,
            ),
            partitions = parts,
            metaPartition = metas[0],
            espPartition = esps[0],
            rootPartition = roots[0],
        )
    }

    // ------------------------------------------------------------------
    // 阶段 2：meta 分区（IMAGE-FORMAT-v2 §3；对照 mkdisk stage 2）
    // ------------------------------------------------------------------

    private fun parseMeta(file: File, size: Long, gpt: Gpt): MetaHeader {
        val part = gpt.metaPartition
        val moff = part.offsetBytes
        val partBytes = part.sizeBytes

        val mh = readAt(file, moff, META_HEADER_SIZE)
        if (ascii(mh, 0, 8) != META_MAGIC) {
            throw VmdDiskException.of(
                "meta 头魔数错误（应为 $META_MAGIC，实际 ${hex(mh.copyOfRange(0, 8))}）",
                VmdDiskReason.CORRUPT,
            )
        }
        val formatVersion = u32(mh, 8)
        val metaSize = u32(mh, 12)
        // §3 约束：148 ≤ meta_size ≤ 分区大小（读取方按 meta_size 定位 tail，不硬编码 8184）
        if (metaSize < MIN_META_SIZE || metaSize > partBytes) {
            throw VmdDiskException.of(
                "meta_size=$metaSize ∉ [$MIN_META_SIZE, $partBytes]",
                VmdDiskReason.CORRUPT,
            )
        }
        // 双魔数：tail @ meta_size-8 再次 "VMDIMG02"
        val tail = readAt(file, Math.addExact(moff, metaSize - 8), 8)
        if (ascii(tail, 0, 8) != META_MAGIC) {
            throw VmdDiskException.of("meta 尾魔数缺失（@meta_size-8）", VmdDiskReason.CORRUPT)
        }
        if (formatVersion > MAX_FORMAT_VERSION) {
            throw VmdDiskException.of(
                "meta.format_version=$formatVersion > $MAX_FORMAT_VERSION: 格式过新，请升级应用",
                VmdDiskReason.FORMAT_UNSUPPORTED,
            )
        }
        val fileSize = u64Checked(mh, 16, "file_size")
        if (fileSize != size) {
            throw VmdDiskException.of(
                "meta.file_size=$fileSize != 实际大小 $size（文件被截断/追加）",
                VmdDiskReason.CORRUPT,
            )
        }
        // flags：合法掩码 0x3 且 v2.0 两位必须全置 → 恒 == 0x3（§5: &~0x3 或 &0x3 != 0x3 均非法）
        val flags = u32(mh, 136)
        if (flags != FLAGS_MASK) {
            throw VmdDiskException.of(
                "meta.flags=0x${flags.toString(16)} 非法（v2.0 必须恒 0x3）",
                VmdDiskReason.CORRUPT,
            )
        }

        val manifestOffset = u64Checked(mh, 24, "manifest_offset")
        val manifestSize = u64Checked(mh, 32, "manifest_size")
        // §5: 1 ≤ manifest_size ≤ meta_size − 148
        if (manifestSize < 1 || manifestSize > metaSize - MIN_META_SIZE) {
            throw VmdDiskException.of(
                "manifest_size=$manifestSize ∉ [1, ${metaSize - MIN_META_SIZE}]",
                VmdDiskReason.CORRUPT,
            )
        }
        // manifest 区间 ⊆ meta 分区且不与头部（<140）/tail（meta_size-8）重叠（溢出安全）
        val tailStart = metaSize - 8
        if (manifestOffset < META_HEADER_SIZE || manifestOffset > tailStart - manifestSize) {
            throw VmdDiskException.of(
                "manifest 区间 [$manifestOffset, +$manifestSize) 与头部/tail 重叠或越界",
                VmdDiskReason.CORRUPT,
            )
        }

        // root/esp 区间与 GPT 一致（§5 "与 §2 公式或 manifest.disk 不一致"；mkdisk 同断言）
        val rootOffset = u64Checked(mh, 72, "root_offset")
        val rootSize = u64Checked(mh, 80, "root_size")
        if (rootOffset != gpt.rootPartition.offsetBytes || rootSize != gpt.rootPartition.sizeBytes) {
            throw VmdDiskException.of(
                "meta.root 区间 [$rootOffset,+$rootSize) 与 GPT '$ROOT_PART_NAME' " +
                    "[${gpt.rootPartition.offsetBytes},+${gpt.rootPartition.sizeBytes}) 不符",
                VmdDiskReason.CORRUPT,
            )
        }
        val espOffset = u64Checked(mh, 120, "esp_offset")
        val espSize = u64Checked(mh, 128, "esp_size")
        if (espOffset != gpt.espPartition.offsetBytes || espSize != gpt.espPartition.sizeBytes) {
            throw VmdDiskException.of(
                "meta.esp 区间 [$espOffset,+$espSize) 与 GPT '$ESP_PART_NAME' " +
                    "[${gpt.espPartition.offsetBytes},+${gpt.espPartition.sizeBytes}) 不符",
                VmdDiskReason.CORRUPT,
            )
        }

        return MetaHeader(
            formatVersion = formatVersion,
            metaSize = metaSize,
            fileSize = fileSize,
            manifestOffset = manifestOffset,
            manifestSize = manifestSize,
            manifestSha256 = hex(mh.copyOfRange(40, 72)),
            rootOffset = rootOffset,
            rootSize = rootSize,
            rootSha256 = hex(mh.copyOfRange(88, 120)),
            espOffset = espOffset,
            espSize = espSize,
            flags = flags,
        )
    }

    // ------------------------------------------------------------------
    // 阶段 3：manifest（IMAGE-FORMAT-v2 §4；对照 mkdisk stage 3）
    // ------------------------------------------------------------------

    private fun parseManifest(file: File, gpt: Gpt, meta: MetaHeader): Manifest {
        val bytes = readAt(
            file,
            Math.addExact(gpt.metaPartition.offsetBytes, meta.manifestOffset),
            meta.manifestSize.toInt(),
        )
        // §5: sha256(manifest) == meta.manifest_sha256
        val actual = sha256(bytes)
        if (!actual.equals(meta.manifestSha256, ignoreCase = true)) {
            throw VmdDiskException.of(
                "manifest sha256 不符 (meta=${meta.manifestSha256}, actual=$actual)",
                VmdDiskReason.CORRUPT,
            )
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw VmdDiskException.of(
                "manifest 非法 UTF-8: ${e.message}（v1 MANIFEST_INVALID 同义 → 非本镜像）",
                VmdDiskReason.NOT_AN_IMAGE,
            )
        }
        if (text.startsWith("\uFEFF")) {
            throw VmdDiskException.of("manifest 必须 UTF-8 无 BOM", VmdDiskReason.NOT_AN_IMAGE)
        }
        val root = try {
            MiniJson.parseObject(text)
        } catch (e: IllegalArgumentException) {
            throw VmdDiskException.of(
                "manifest JSON 非法: ${e.message}（v1 MANIFEST_INVALID 同义 → 非本镜像）",
                VmdDiskReason.NOT_AN_IMAGE,
            )
        }

        // 结构：已知块若存在必须是 object（disk 缺失/类型错 → §4.2 CORRUPT；其余 → 非本镜像）
        for (key in listOf("capabilities", "accounts", "contract", "app", "boot", "checksums", "image")) {
            val v = root[key]
            if (v != null && v !is Map<*, *>) {
                throw VmdDiskException.of("manifest.$key 必须是 JSON 对象", VmdDiskReason.NOT_AN_IMAGE)
            }
        }
        val diskRaw = root["disk"]
        if (diskRaw != null && diskRaw !is Map<*, *>) {
            throw VmdDiskException.of("manifest.disk 必须是 JSON 对象", VmdDiskReason.CORRUPT)
        }

        // ---- image ----
        val imageRaw = root["image"] as Map<*, *>?
        val distroRaw = imageRaw?.get("distro") as Map<*, *>?
        val image = ImageBlock(
            id = imageRaw?.get("id") as? String,
            displayName = imageRaw?.get("display_name") as? String,
            identity = imageRaw?.get("identity") as? String,
            variant = imageRaw?.get("variant") as? String,
            version = imageRaw?.get("version") as? String,
            systemVersion = strictLong(imageRaw?.get("system_version")),
            arch = imageRaw?.get("arch") as? String,
            distroName = distroRaw?.get("name") as? String,
            distroRelease = distroRaw?.get("release") as? String,
            distroInit = distroRaw?.get("init") as? String,
            createdAt = imageRaw?.get("created_at") as? String,
            contentId = imageRaw?.get("content_id") as? String,
        )

        // ---- disk（布局真值；无 disk → §4.2 拒绝 CORRUPT） ----
        val disk = (diskRaw as Map<*, *>?)?.let { d ->
            DiskBlock(
                table = d["table"] as? String,
                sectorSize = strictLong(d["sector_size"]),
                fileSize = strictLong(d["file_size"]),
                esp = partSpec(d["esp"]),
                meta = partSpec(d["meta"]),
                root = partSpec(d["root"]),
                kernelPath = d["kernel_path"] as? String,
                initrdPath = d["initrd_path"] as? String,
            )
        }

        // ---- capabilities（§4.2 缺省：无块 → {ssh:true,x11:true,containers:true}；有块缺 ssh → false） ----
        val capsRaw = root["capabilities"] as Map<*, *>?
        val caps = if (capsRaw == null) {
            CapabilitiesBlock(ssh = true, x11 = true, desktop = false, containers = true)
        } else {
            CapabilitiesBlock(
                ssh = capsRaw["ssh"] as? Boolean ?: false,
                x11 = capsRaw["x11"] as? Boolean ?: false,
                desktop = capsRaw["desktop"] as? Boolean ?: false,
                containers = capsRaw["containers"] as? Boolean ?: false,
            )
        }

        // ---- accounts（§4.2 缺省：无块 → {root, 22}；ssh_port 类型非整数 → null → SSH_PORT_INVALID） ----
        val accRaw = root["accounts"] as Map<*, *>?
        val accounts = if (accRaw == null) {
            AccountsBlock(
                ssh = listOf(SshAccount(user = "root", password = null, sudo = false)),
                defaultUser = "root",
                sshPort = REQUIRED_SSH_PORT,
            )
        } else {
            val sshRaw = accRaw["ssh"]
            if (sshRaw != null && sshRaw !is List<*>) {
                throw VmdDiskException.of("manifest.accounts.ssh 必须是 JSON 数组", VmdDiskReason.NOT_AN_IMAGE)
            }
            AccountsBlock(
                ssh = (sshRaw as? List<*>)?.map { el ->
                    val m = el as? Map<*, *>
                        ?: throw VmdDiskException.of(
                            "manifest.accounts.ssh[] 项必须是 JSON 对象",
                            VmdDiskReason.NOT_AN_IMAGE,
                        )
                    SshAccount(
                        user = m["user"] as? String ?: "",
                        password = m["password"] as? String,
                        sudo = m["sudo"] as? Boolean ?: false,
                    )
                } ?: emptyList(),
                defaultUser = accRaw["default_user"] as? String,
                sshPort = when (val p = accRaw["ssh_port"]) {
                    null -> REQUIRED_SSH_PORT     // 键缺失 → 22（mkdisk 同义 get(...,22)）
                    else -> strictLong(p)         // 类型非整数 → null → SSH_PORT_INVALID
                },
            )
        }

        // ---- contract（无块/无 version/类型错 → null → CONTRACT_VERSION，v2 必须 ==2） ----
        val contractRaw = root["contract"] as Map<*, *>?
        val contract = ContractBlock(
            version = strictLong(contractRaw?.get("version")),
            markers = strList(contractRaw?.get("markers")),
            ttys = strMap(contractRaw?.get("ttys")),
            kernelImageSha256 = (contractRaw?.get("kernel") as Map<*, *>?)?.get("image_sha256") as? String,
        )

        // ---- app（IMP-D05 必需字段：无块/缺 min_version_code/类型非整数 → null → 校验期拒绝；
        //    D11：mkdisk 参照实现对缺必需字段即 fail，优先于 §4.2 "无 app → 0" 的读取侧兜底） ----
        val appRaw = root["app"] as Map<*, *>?
        val app = AppBlock(
            minVersionCode = if (appRaw == null) null else strictLong(appRaw["min_version_code"]),
        )

        // ---- boot（无块 → null → BOOT_MODE_UNSUPPORTED；v2.0 必须 mode=="uefi"） ----
        val boot = (root["boot"] as Map<*, *>?)?.let { b ->
            BootBlock(
                mode = b["mode"] as? String,
                machine = b["machine"] as? String,
                cpu = b["cpu"] as? String,
                append = b["append"] as? String,
                kernelSha256 = b["kernel_sha256"] as? String,
                initrdSha256 = b["initrd_sha256"] as? String,
                drives = strMap(b["drives"]),
            )
        }

        val checksums = strMap(root["checksums"])
        val known = setOf(
            "format", "format_version", "image", "disk", "contract",
            "capabilities", "accounts", "app", "boot", "checksums",
        )
        val unknown = root.entries.filter { it.key !in known }.associate { it.key to it.value }

        return Manifest(
            format = root["format"] as? String,
            formatVersion = strictLong(root["format_version"]),
            image = image,
            disk = disk,
            contract = contract,
            capabilities = caps,
            accounts = accounts,
            app = app,
            boot = boot,
            checksums = checksums,
            unknown = unknown,
        )
    }

    /** §5 阶段 3：manifest 语义校验（顺序 = §5 伪代码 + §4.2 规则表）。 */
    private fun validateManifest(
        m: Manifest,
        size: Long,
        gpt: Gpt,
        meta: MetaHeader,
        currentVersionCode: Long?,
    ) {
        // 1. format
        if (m.format != MANIFEST_FORMAT) {
            val got = m.format?.let { "\"$it\"" } ?: "<missing>"
            throw VmdDiskException.of(
                "manifest.format=$got != \"$MANIFEST_FORMAT\"：非本镜像",
                VmdDiskReason.NOT_AN_IMAGE,
            )
        }
        // 2. format_version == 2（缺失/其他值 → 格式过新或不可识别）
        if (m.formatVersion != MAX_FORMAT_VERSION.toLong()) {
            throw VmdDiskException.of(
                "manifest.format_version=${m.formatVersion ?: "<missing>"} != $MAX_FORMAT_VERSION",
                VmdDiskReason.FORMAT_UNSUPPORTED,
            )
        }
        // 3. image.id 正则（防 -drive/-name 注入）
        val id = m.image.id
        if (id == null || !Regex(IMAGE_ID_PATTERN).matches(id)) {
            val got = id?.let { "\"$it\"" } ?: "<missing>"
            throw VmdDiskException.of(
                "image.id=$got 不匹配 $IMAGE_ID_PATTERN（防驱动选项注入）",
                VmdDiskReason.IMAGE_ID_INVALID,
            )
        }
        // 4. arch == arm64
        if (m.image.arch != REQUIRED_ARCH) {
            val got = m.image.arch?.let { "\"$it\"" } ?: "<missing>"
            throw VmdDiskException.of("image.arch=$got != \"$REQUIRED_ARCH\"", VmdDiskReason.ARCH_MISMATCH)
        }
        // 5. contract.version == 2
        if (m.contract.version != REQUIRED_CONTRACT_VERSION) {
            throw VmdDiskException.of(
                "contract.version=${m.contract.version ?: "<missing>"} != $REQUIRED_CONTRACT_VERSION",
                VmdDiskReason.CONTRACT_VERSION,
            )
        }
        // 6. capabilities.ssh == true（fail-closed）
        if (!m.capabilities.ssh) {
            throw VmdDiskException.of(
                "capabilities.ssh != true：SSH 为必备能力",
                VmdDiskReason.SSH_CAPABILITY_MISSING,
            )
        }
        // 7. accounts.ssh_port == 22（类型非整数视同 ≠22）
        if (m.accounts.sshPort == null) {
            throw VmdDiskException.of(
                "accounts.ssh_port 类型非整数（§4.2 视同 ≠22）",
                VmdDiskReason.SSH_PORT_INVALID,
            )
        }
        if (m.accounts.sshPort != REQUIRED_SSH_PORT) {
            throw VmdDiskException.of(
                "accounts.ssh_port=${m.accounts.sshPort} != $REQUIRED_SSH_PORT",
                VmdDiskReason.SSH_PORT_INVALID,
            )
        }
        // 8. boot.mode == "uefi"（v2.0 不允许其他值/缺失）
        if (m.boot?.mode != REQUIRED_BOOT_MODE) {
            throw VmdDiskException.of(
                "boot.mode=${m.boot?.mode ?: "<missing>"} != \"$REQUIRED_BOOT_MODE\"",
                VmdDiskReason.BOOT_MODE_UNSUPPORTED,
            )
        }
        // 9. app.min_version_code（IMP-D05 必需字段：缺失/类型错 → CORRUPT ——
        //    2026-10-12 修订对齐 §4.2 拍板（"无 app 块或缺键都拒"，CORRUPT）；
        //    v1 同类问题曾归 NOT_AN_IMAGE，v2 起统一 CORRUPT：镜像是本格式但清单残缺，
        //    用户侧正确动作是重装/重下，而非"格式不对"。超 currentVersionCode → APP_TOO_OLD）
        val min = m.app.minVersionCode
            ?: throw VmdDiskException.of(
                "app.min_version_code 缺失或类型非整数（IMP-D05 必需字段）",
                VmdDiskReason.CORRUPT,
            )
        if (currentVersionCode != null && min > currentVersionCode) {
            throw VmdDiskException.of(
                "app.min_version_code=$min > 当前 versionCode=$currentVersionCode：请升级应用",
                VmdDiskReason.APP_TOO_OLD,
            )
        }
        // 10. disk 块（§4.2：无 disk → 拒绝，v2 没有布局信息不可用）
        val disk = m.disk
            ?: throw VmdDiskException.of("manifest.disk 缺失：v2 无布局信息不可用", VmdDiskReason.CORRUPT)
        if (disk.table != null && disk.table != "gpt") {
            throw VmdDiskException.of("disk.table=${disk.table} != \"gpt\"", VmdDiskReason.CORRUPT)
        }
        if (disk.sectorSize != null && disk.sectorSize != SECTOR_SIZE.toLong()) {
            throw VmdDiskException.of("disk.sector_size=${disk.sectorSize} != $SECTOR_SIZE", VmdDiskReason.CORRUPT)
        }
        if (disk.fileSize != null && disk.fileSize != size) {
            throw VmdDiskException.of("disk.file_size=${disk.fileSize} != 实际大小 $size", VmdDiskReason.CORRUPT)
        }
        // disk.root.partuuid 与 GPT 唯一 GUID 一致（大小写/连字符归一后比文本；mkdisk 同义：
        // mkdisk 侧 = uuid.UUID(bytes_le=表项).hex == manifest 文本 —— 即 mixed-endian 归一后的文本比较）
        val pu = disk.root?.partuuid
        val wantPu = gpt.rootPartition.uniqueGuid   // 解析期已按 mixed-endian 还原为规范文本
        if (pu == null || pu.lowercase().replace("-", "") != wantPu.lowercase().replace("-", "")) {
            throw VmdDiskException.of(
                "disk.root.partuuid=${pu ?: "<missing>"} 与 GPT '$ROOT_PART_NAME' 唯一 GUID $wantPu 不符",
                VmdDiskReason.CORRUPT,
            )
        }
        // disk 冗余布局字段（present 即必须与 GPT 一致；§5 "与 §2 公式或 manifest.disk 不一致"）
        checkDiskSpec("disk.esp", disk.esp, gpt.espPartition, requireRange = true)
        checkDiskSpec("disk.meta", disk.meta, gpt.metaPartition, requireRange = false)
        checkDiskSpec("disk.root", disk.root, gpt.rootPartition, requireRange = true)
        // 11. 摘要一致性（checksums.root_sha256 必须 == meta.root_sha256；kernel 双处一致）
        val rootSha = m.checksums["root_sha256"]
        if (rootSha == null || !rootSha.equals(meta.rootSha256, ignoreCase = true)) {
            throw VmdDiskException.of(
                "checksums.root_sha256=${rootSha ?: "<missing>"} != meta.root_sha256=${meta.rootSha256}",
                VmdDiskReason.CORRUPT,
            )
        }
        if (m.checksums["kernel_sha256"] != m.boot?.kernelSha256) {
            throw VmdDiskException.of(
                "checksums.kernel_sha256=${m.checksums["kernel_sha256"] ?: "<missing>"} " +
                    "!= boot.kernel_sha256=${m.boot?.kernelSha256 ?: "<missing>"}",
                VmdDiskReason.CORRUPT,
            )
        }
    }

    /** disk.* 子块冗余字段（first_lba/size present 即与 GPT 比对；缺省解释：缺失不拒绝）。 */
    private fun checkDiskSpec(name: String, spec: DiskPartSpec?, part: Partition, requireRange: Boolean) {
        if (spec == null) {
            if (requireRange) {
                throw VmdDiskException.of("manifest.$name 缺失", VmdDiskReason.CORRUPT)
            }
            return
        }
        if (spec.firstLba != null && spec.firstLba != part.firstLba) {
            throw VmdDiskException.of(
                "$name.first_lba=${spec.firstLba} != GPT #${part.index} ${part.firstLba}",
                VmdDiskReason.CORRUPT,
            )
        }
        if (spec.size != null && spec.size != part.sizeBytes) {
            throw VmdDiskException.of(
                "$name.size=${spec.size} != GPT #${part.index} ${part.sizeBytes}",
                VmdDiskReason.CORRUPT,
            )
        }
    }

    // ------------------------------------------------------------------
    // GUID：mixed-endian（GPT 表项 16 字节 ↔ 规范文本；uuid.UUID(bytes_le) 同义）
    // ------------------------------------------------------------------

    /** 16 字节 → 规范文本（小写 8-4-4-4-12；前 3 字段按小端解释 —— §2.2 mixed-endian）。 */
    @JvmStatic
    fun guidToText(b: ByteArray): String {
        require(b.size == 16) { "GUID 必须 16 字节，实际 ${b.size}" }
        val sb = StringBuilder(36)
        fun hexRange(off: Int, len: Int, littleEndian: Boolean) {
            val idx = if (littleEndian) (len - 1 downTo 0) else (0 until len)
            for (k in idx) {
                val v = b[off + k].toInt() and 0xFF
                sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
            }
        }
        hexRange(0, 4, littleEndian = true)
        sb.append('-')
        hexRange(4, 2, littleEndian = true)
        sb.append('-')
        hexRange(6, 2, littleEndian = true)
        sb.append('-')
        hexRange(8, 2, littleEndian = false)
        sb.append('-')
        hexRange(10, 6, littleEndian = false)
        return sb.toString()
    }

    /** 规范文本 → 16 字节（[guidToText] 逆变换；容忍大写/连字符/花括号，非法返回 null）。 */
    @JvmStatic
    fun guidToBytes(text: String): ByteArray? {
        val hexText = text.trim().removePrefix("{").removeSuffix("}").lowercase().replace("-", "")
        if (hexText.length != 32 || !hexText.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val raw = ByteArray(16)
        for (i in 0 until 16) {
            val v = hexText.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            raw[i] = v.toByte()
        }
        val out = ByteArray(16)
        // 前 3 字段（4+2+2 字节）小端回转，其余原样
        for (i in 0 until 4) out[i] = raw[3 - i]
        out[4] = raw[5]
        out[5] = raw[4]
        out[6] = raw[7]
        out[7] = raw[6]
        System.arraycopy(raw, 8, out, 8, 8)
        return out
    }

    // ------------------------------------------------------------------
    // sha256 工具
    // ------------------------------------------------------------------

    /** 单块 sha256（小写 hex）。 */
    @JvmStatic
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 字节区间流式 sha256（[bufferSize] 缓冲；EOF 提前 → CORRUPT）。 */
    private fun sha256Range(file: File, offset: Long, size: Long, bufferSize: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferSize)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                var remaining = size
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) {
                        throw VmdDiskException.of(
                            "unexpected EOF reading [$offset, +$size) of $file（文件被并发修改？）",
                            VmdDiskReason.CORRUPT,
                        )
                    }
                    md.update(buf, 0, n)
                    remaining -= n
                }
            }
        } catch (e: IOException) {
            throw VmdDiskException.of("io error while hashing $file: ${e.message}", VmdDiskReason.CORRUPT, e)
        }
        return hex(md.digest())
    }

    /** 字节 → 小写 hex。 */
    @JvmStatic
    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4])
            sb.append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun gptOf(info: ImageInfo): Gpt = Gpt(
        header = info.gpt,
        partitions = info.partitions,
        metaPartition = info.metaPartition,
        espPartition = info.espPartition,
        rootPartition = info.rootPartition,
    )

    private fun partSpec(v: Any?): DiskPartSpec? {
        val m = v as? Map<*, *> ?: return null
        return DiskPartSpec(
            part = strictLong(m["part"]),
            fstype = m["fstype"] as? String,
            firstLba = strictLong(m["first_lba"]),
            lastLba = strictLong(m["last_lba"]),
            size = strictLong(m["size"]),
            partuuid = m["partuuid"] as? String,
            fatUuid = m["fat_uuid"] as? String,
        )
    }

    private fun readAt(file: File, offset: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                var got = 0
                while (got < size) {
                    val n = raf.read(out, got, size - got)
                    if (n < 0) {
                        throw VmdDiskException.of(
                            "unexpected EOF reading [$offset, +$size) of $file（截断/追加？）",
                            VmdDiskReason.CORRUPT,
                        )
                    }
                    got += n
                }
            }
        } catch (e: IOException) {
            throw VmdDiskException.of("io error while reading $file: ${e.message}", VmdDiskReason.CORRUPT, e)
        }
        return out
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String = String(b, off, len, Charsets.US_ASCII)

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 3 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    /** u64 按无符号读；≥ 2^63（落成负数）说明字段超出真实文件范围 → CORRUPT。 */
    private fun u64Checked(b: ByteArray, off: Int, name: String): Long {
        val v = u64Signed(b, off)
        if (v < 0) {
            throw VmdDiskException.of(
                "u64 字段 $name 超出真实文件可表示范围（符号位为 1）",
                VmdDiskReason.CORRUPT,
            )
        }
        return v
    }

    /** u64 按位读（负值由调用方判非法）。 */
    private fun u64Signed(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    private fun allZero(b: ByteArray, off: Int, len: Int): Boolean {
        for (i in off until off + len) if (b[i] != 0.toByte()) return false
        return true
    }

    /** JSON 整数（Long）或整值 Double → Long；其他（含字符串）→ null（类型非整数）。 */
    private fun strictLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Double ->
            if (v.isFinite() && v % 1.0 == 0.0 && v >= -9.007199254740992E15 && v <= 9.007199254740992E15) v.toLong()
            else null
        else -> null
    }

    private fun strList(v: Any?): List<String> =
        (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun strMap(v: Any?): Map<String, String> =
        (v as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String && it.value is String }
            ?.associate { it.key as String to it.value as String }
            ?: emptyMap()
}
