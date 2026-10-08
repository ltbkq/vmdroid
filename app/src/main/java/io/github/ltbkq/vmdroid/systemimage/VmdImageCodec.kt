/*
 * VmdImageCodec — VMDroid 系统镜像 (.img) 解析 / 校验 / 提取。
 *
 * 实现依据（冻结规格）：
 *   - docs/IMAGE-FORMAT.md v1.0（format_version = 1；§3 footer 表、§4 manifest、§5 读取算法、§8 测试向量）
 *   - docs/DESIGN.md §5.2（identity 重置判定）、§7.4（BootGuard reason 权威清单）、§13.1（本文件的测试归属）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 独立编译，
 * 并以 codec-selftest/SelfTest.kt（无 JUnit 依赖）自测。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 校验失败的细分 reason。
 *
 * codec 内部需要比 DESIGN §7.4 更细的粒度（§7.4 是 BootGuard 启动拦截清单，
 * 不区分截断/载荷损坏/清单非法等导入期问题），因此每个 codec reason 都携带
 * [bootGuard] 属性 —— 写 `image.log` 的 `bootguard_reject` 时必须用该映射值，
 * 不得直接把 codec reason 当 §7.4 枚举用（§7.4："勿另造别名"）。
 *
 * 映射表（[bootGuard] 取值 ⊆ §7.4 权威枚举）：
 *
 * | codec reason              | bootGuard (§7.4)    | 说明                                    |
 * |---------------------------|---------------------|-----------------------------------------|
 * | NOT_AN_IMAGE              | NOT_AN_IMAGE        | §7.4 #3：magic 不符/裸 squashfs/非本格式 |
 * | MANIFEST_INVALID          | NOT_AN_IMAGE        | manifest 非法 → "非本格式"（§5）          |
 * | TRUNCATED                 | CORRUPT             | 截断/拼接 → §7.4 #2 行为（重下/重导）      |
 * | PAYLOAD_CORRUPT           | CORRUPT             | 某段 sha256 不匹配                       |
 * | CORRUPT                   | CORRUPT             | 边界/重叠/flags↔段 不一致等结构损坏        |
 * | IO_ERROR                  | CORRUPT             | 读文件失败（仅用于 image.log 记录）        |
 * | FORMAT_UNSUPPORTED        | FORMAT_UNSUPPORTED  | §7.4 #4：format_version/footer_size/未知 flags |
 * | ARCH_MISMATCH             | ARCH_MISMATCH       | §7.4 #6                                  |
 * | IMAGE_ID_INVALID          | IMAGE_ID_INVALID    | §7.4 #7（防 -drive 选项注入）             |
 * | SSH_CAPABILITY_MISSING    | SSH_CAPABILITY_MISSING | §7.4 #8                                |
 * | SSH_PORT_INVALID          | SSH_PORT_INVALID    | §7.4 #9                                  |
 * | APP_TOO_OLD               | APP_TOO_OLD         | §7.4 #5                                  |
 * | NO_SYSTEM_IMAGE           | NO_SYSTEM_IMAGE     | §7.4 #1（codec 不产生；仓库/BootGuard 层） |
 * | RESET_REQUIRED            | RESET_REQUIRED      | §7.4 #10（激活层产生，见 decideReset）     |
 */
enum class VmdImageReason(val bootGuard: String) {
    NOT_AN_IMAGE("NOT_AN_IMAGE"),
    TRUNCATED("CORRUPT"),
    FORMAT_UNSUPPORTED("FORMAT_UNSUPPORTED"),
    ARCH_MISMATCH("ARCH_MISMATCH"),
    IMAGE_ID_INVALID("IMAGE_ID_INVALID"),
    SSH_CAPABILITY_MISSING("SSH_CAPABILITY_MISSING"),
    SSH_PORT_INVALID("SSH_PORT_INVALID"),
    APP_TOO_OLD("APP_TOO_OLD"),
    PAYLOAD_CORRUPT("CORRUPT"),
    MANIFEST_INVALID("NOT_AN_IMAGE"),
    CORRUPT("CORRUPT"),
    IO_ERROR("CORRUPT"),
    NO_SYSTEM_IMAGE("NO_SYSTEM_IMAGE"),
    RESET_REQUIRED("RESET_REQUIRED"),
}

/**
 * 镜像校验失败（唯一异常类型）。[reason] 为 codec 细分码；
 * [bootGuardReason] 为可直接写入 image.log `bootguard_reject` 的 §7.4 枚举值。
 */
sealed class VmdImageException(
    message: String,
    val reason: VmdImageReason,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** DESIGN §7.4 权威 reason 枚举（写 image.log 用；见 [VmdImageReason] 映射表）。 */
    val bootGuardReason: String get() = reason.bootGuard

    /** 唯一具体子类（sealed：外部不可继承，只能经 [of] 构造）。 */
    private class Impl(message: String, reason: VmdImageReason, cause: Throwable?) :
        VmdImageException(message, reason, cause)

    companion object {
        /** 构造一个校验失败异常（sealed 类不直接实例化，统一走本工厂）。 */
        fun of(message: String, reason: VmdImageReason, cause: Throwable? = null): VmdImageException =
            Impl(message, reason, cause)
    }
}

/** 极简 JSON 解析器（manifest ≤ 64 KiB；仅依赖 stdlib）。非法输入抛 IllegalArgumentException。 */
private class JsonParser(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        val v = value()
        ws()
        if (i != s.length) fail("trailing content")
        return v
    }

    private fun fail(msg: String): Nothing =
        throw IllegalArgumentException("$msg at offset $i")

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun at(c: Char): Boolean = i < s.length && s[i] == c

    private fun value(): Any? {
        ws()
        if (i >= s.length) fail("unexpected end of input")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            '-', in '0'..'9' -> num()
            else -> fail("unexpected char '$c'")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("bad literal")
        i += word.length
        return v
    }

    private fun obj(): LinkedHashMap<String, Any?> {
        i++ // '{'
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (at('}')) {
            i++
            return m
        }
        while (true) {
            ws()
            if (!at('"')) fail("expected string key")
            val k = str()
            ws()
            if (!at(':')) fail("expected ':'")
            i++
            m[k] = value()
            ws()
            when {
                at(',') -> i++
                at('}') -> {
                    i++
                    return m
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arr(): ArrayList<Any?> {
        i++ // '['
        val a = ArrayList<Any?>()
        ws()
        if (at(']')) {
            i++
            return a
        }
        while (true) {
            a.add(value())
            ws()
            when {
                at(',') -> i++
                at(']') -> {
                    i++
                    return a
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            when (val c = s[i]) {
                '"' -> {
                    i++
                    return sb.toString()
                }
                '\\' -> {
                    i++
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) fail("bad \\u escape")
                            val hex = s.substring(i + 1, i + 5)
                            val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '\\u$hex'")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
    }

    private fun num(): Any {
        val start = i
        if (at('-')) i++
        while (i < s.length && (s[i] in '0'..'9' || s[i] in ".eE+-")) i++
        if (i == start) fail("bad number")
        val t = s.substring(start, i)
        return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
            t.toDoubleOrNull() ?: fail("bad number '$t'")
        } else {
            t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number '$t'")
        }
    }
}

/**
 * `.img` 编解码器：读取（[read]）、语义复检（[validate]）、逐段 sha256（[verifyPayloads]）、
 * 段提取（[extractPayload]）、footer 解析（[parseFooter]）、流式 sha256（[sha256]/[sha256File]）、
 * 重置判定（[decideReset]，DESIGN §5.2）。
 *
 * 所有校验失败抛 [VmdImageException]，绝不返回半成品结果。
 */
object VmdImageCodec {

    // ---- footer 常量（IMAGE-FORMAT §3，R3 冻结，全小端） ----
    const val MAGIC = "VMDIMG01"
    const val FOOTER_SIZE = 4096
    const val MAGIC_TAIL_OFFSET = 4088
    const val MAX_FORMAT_VERSION = 1
    const val FLAGS_MASK = 0x3
    const val FLAG_HAS_KERNEL = 0x1
    const val FLAG_HAS_INITRD = 0x2

    // ---- manifest 常量（IMAGE-FORMAT §4） ----
    const val MANIFEST_FORMAT = "vmdroid-system-image"
    const val REQUIRED_ARCH = "arm64"
    const val REQUIRED_SSH_PORT = 22L
    const val IMAGE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$"

    // ---- 布局约束（IMAGE-FORMAT §2） ----
    const val MIN_FILE_SIZE = 8192L   // magic 匹配后 file_size < 8192 → 拒绝（§3 规则 1, R1: B-R1-11）
    const val MAX_MANIFEST_SIZE = 65536L
    const val MIB = 1048576L

    private const val EXTRACT_BUFFER = 256 * 1024
    private val IMAGE_ID_REGEX = Regex(IMAGE_ID_PATTERN)
    private val ROOT_MANIFEST_KEYS = setOf(
        "format", "format_version", "image", "contract", "capabilities",
        "accounts", "app", "boot", "checksums",
    )

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /** footer（文件最后 4096 字节）全部字段；sha256 字段为小写 hex。 */
    data class Footer(
        val magic: String,
        val formatVersion: Int,
        val footerSize: Int,
        val fileSize: Long,
        val rootfsOffset: Long,
        val rootfsSize: Long,
        val rootfsSha256: String,
        val manifestOffset: Long,
        val manifestSize: Long,
        val manifestSha256: String,
        val flags: Int,
        val kernelOffset: Long,
        val kernelSize: Long,
        val kernelSha256: String,
        val initrdOffset: Long,
        val initrdSize: Long,
        val initrdSha256: String,
        val magicTail: String,
    ) {
        val hasKernel: Boolean get() = flags and FLAG_HAS_KERNEL != 0
        val hasInitrd: Boolean get() = flags and FLAG_HAS_INITRD != 0
        /** payload 数据区上界（= footer 起始偏移）；所有段必须落在 [0, dataLimit)。 */
        val dataLimit: Long get() = fileSize - FOOTER_SIZE
        val footerOffset: Long get() = dataLimit
    }

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
        val source: String?,
        val license: String?,
    )

    data class ContractBlock(
        val version: Long,
        val markers: List<String>,
        val ttys: Map<String, String>,
        val kernelBuiltinOnly: Boolean?,
        val kernelMin: String?,
        val kernelMax: String?,
        val kernelImageSha256: String?,
    )

    data class CapabilitiesBlock(
        val ssh: Boolean,
        val x11: Boolean,
        val desktop: Boolean,
        val containers: Boolean,
        val desktopProfile: Boolean,
        val downloadsShare: Boolean,
        val usbPassthroughHost: Boolean,
    )

    data class SshAccount(
        val user: String,
        val password: String?,
        val sudo: Boolean,
    )

    data class AccountsBlock(
        val ssh: List<SshAccount>,
        val defaultUser: String?,
        val sshPort: Long,
    )

    data class AppBlock(val minVersionCode: Long?)

    data class BootBlock(
        val machine: String?,
        val cpu: String?,
        val append: String?,
        val kernelSha256: String?,
        val initrdSha256: String?,
        val drives: Map<String, String>,
    )

    /**
     * manifest（IMAGE-FORMAT §4）。缺省解释已按 §4 "缺省解释" 归一化：
     * - 无 `capabilities` → {ssh:true, x11:true, containers:true}（其余 false）
     * - 无 `accounts` → 仅 root、ssh_port = 22、default_user = root
     * - 无 `contract` → contract.version = 1
     * - 无 `app` → [AppBlock.minVersionCode] = null（无版本约束）
     * 未知字段保留在 [unknown] 中（前向兼容，绝不因未知字段拒绝）。
     */
    data class Manifest(
        val format: String?,
        val formatVersion: Long?,
        val image: ImageBlock,
        val contract: ContractBlock,
        val capabilities: CapabilitiesBlock,
        val accounts: AccountsBlock,
        val app: AppBlock,
        val boot: BootBlock?,
        val checksums: Map<String, String>,
        val unknown: Map<String, Any?>,
    )

    /** [read] 的返回值：已通过 footer + manifest 全部校验（[currentVersionCode] 未给时除 APP_TOO_OLD）。 */
    data class ImageInfo(
        val path: String,
        val footer: Footer,
        val manifest: Manifest,
    ) {
        /** DESIGN §5.2 重置判定输入（见 [VmdImageCodec.decideReset]）。 */
        fun resetKey(): ResetKey = ResetKey(
            rootfsSha256 = footer.rootfsSha256,
            identity = manifest.image.identity,
            contractVersion = manifest.contract.version,
            distroInit = manifest.image.distroInit,
        )
    }

    /** DESIGN §5.2 重置判定输入。 */
    data class ResetKey(
        val rootfsSha256: String,
        val identity: String?,
        val contractVersion: Long?,
        val distroInit: String?,
    )

    /** DESIGN §5.2 `decision` 枚举；[code] 为 image.log 直接引用的小写值（"勿自造"）。 */
    enum class ResetDecision(val code: String) {
        SAME("same"),
        IDENTITY("identity"),
        CONTRACT("contract"),
        INIT("init"),
        UPGRADE("upgrade"),
    }

    // ------------------------------------------------------------------
    // 读取（IMAGE-FORMAT §5）
    // ------------------------------------------------------------------

    /**
     * 读取并校验镜像。校验链：双 magic → ≥8192 → file_size 一致 → format_version/footer_size/flags 掩码
     * → flags↔段一致 → 边界（溢出安全）→ 段序不重叠 → manifest sha256 → JSON 结构 →
     * format/id/arch/ssh/ssh_port/[app.min_version_code]。
     *
     * @param currentVersionCode 应用 versionCode；非 null 时执行 §7.4 #5（APP_TOO_OLD）比较。
     * @throws VmdImageException 任一校验失败（reason 见 [VmdImageReason]）。
     */
    @JvmStatic
    fun read(path: String, currentVersionCode: Long? = null): ImageInfo =
        read(File(path), currentVersionCode)

    /** 同 [read(String)]。 */
    @JvmStatic
    fun read(file: File, currentVersionCode: Long? = null): ImageInfo {
        try {
            if (!file.isFile) {
                throw VmdImageException.of("not a readable file: $file", VmdImageReason.IO_ERROR)
            }
            val size = file.length()
            // §5 第 1 步：footer 定位
            if (size < FOOTER_SIZE) {
                throw VmdImageException.of(
                    "file too small ($size < $FOOTER_SIZE bytes): not a VMDroid system image",
                    VmdImageReason.NOT_AN_IMAGE,
                )
            }
            val footerBytes = readAt(file, size - FOOTER_SIZE, FOOTER_SIZE)
            val magicOk = matchesMagic(footerBytes)
            if (!magicOk) {
                // §5：魔数不符 → 裸 squashfs 提示封装，否则 "非本格式"（N4）
                if (startsWithHsqs(file)) {
                    throw VmdImageException.of(
                        "no valid footer and file starts with squashfs magic (hsqs): " +
                            "raw squashfs or .img with corrupted footer — wrap it with mkimg.sh to produce a .img",
                        VmdImageReason.NOT_AN_IMAGE,
                    )
                }
                throw VmdImageException.of(
                    "bad footer magic: not a VMDroid system image",
                    VmdImageReason.NOT_AN_IMAGE,
                )
            }
            // §3 规则 1：magic 匹配后才拒绝极小文件（避免误杀，R1: B-R1-11）
            if (size < MIN_FILE_SIZE) {
                throw VmdImageException.of(
                    "footer magic matched but file_size=$size < $MIN_FILE_SIZE",
                    VmdImageReason.TRUNCATED,
                )
            }
            // §5 第 3 步：file_size 与实际一致（否则 = 截断/拼接）
            val claimedSize = leLong(footerBytes, 16)
            if (claimedSize != size) {
                throw VmdImageException.of(
                    "footer.file_size=$claimedSize != actual size=$size (truncated or spliced)",
                    VmdImageReason.TRUNCATED,
                )
            }
            val footer = parseFooter(footerBytes)
            validateFooter(footer, size)
            // §5 第 6 步：读 manifest 并比对 sha256
            val manifestBytes = readAt(file, footer.manifestOffset, footer.manifestSize.toInt())
            val manifestSha = sha256(manifestBytes)
            if (manifestSha != footer.manifestSha256) {
                throw VmdImageException.of(
                    "manifest sha256 mismatch (footer=${footer.manifestSha256}, actual=$manifestSha)",
                    VmdImageReason.PAYLOAD_CORRUPT,
                )
            }
            val manifest = parseManifest(manifestBytes)
            validateManifest(manifest, currentVersionCode)
            return ImageInfo(file.absolutePath, footer, manifest)
        } catch (e: VmdImageException) {
            throw e
        } catch (e: IOException) {
            throw VmdImageException.of("io error while reading $file: ${e.message}", VmdImageReason.IO_ERROR, e)
        }
    }

    /**
     * manifest 语义复检（激活前/换 currentVersionCode 时用；幂等）。
     * 校验顺序 = §5：format → image.id → arch → capabilities.ssh → accounts.ssh_port → app.min_version_code。
     *
     * @throws VmdImageException 对应 §7.4 reason（IMAGE_ID_INVALID/ARCH_MISMATCH/…）。
     */
    @JvmStatic
    fun validate(info: ImageInfo, currentVersionCode: Long? = null) {
        validateManifest(info.manifest, currentVersionCode)
    }

    /**
     * 逐段重算 sha256（rootfs 恒校验；kernel/initrd 按 flags；manifest 一并）。
     * 手动 "校验" 动作调用（IMAGE-FORMAT §5 校验时机表）。
     *
     * @throws VmdImageException [VmdImageReason.PAYLOAD_CORRUPT] 段损坏 / TRUNCATED 文件被并发截断。
     */
    @JvmStatic
    fun verifyPayloads(info: ImageInfo) {
        val f = info.footer
        val file = File(info.path)
        try {
            verifySegment(file, f.rootfsOffset, f.rootfsSize, f.rootfsSha256, "rootfs")
            if (f.hasKernel) verifySegment(file, f.kernelOffset, f.kernelSize, f.kernelSha256, "kernel")
            if (f.hasInitrd) verifySegment(file, f.initrdOffset, f.initrdSize, f.initrdSha256, "initrd")
            verifySegment(file, f.manifestOffset, f.manifestSize, f.manifestSha256, "manifest")
        } catch (e: VmdImageException) {
            throw e
        } catch (e: IOException) {
            throw VmdImageException.of("io error while verifying $file: ${e.message}", VmdImageReason.IO_ERROR, e)
        }
    }

    /**
     * 提取单个段到 [out]（kernel/initrd 提取即 R-16 PC 启动所用；rootfs/manifest 亦可）。
     * **边拷边算 sha256**：不匹配即删除 [out] 并抛 [VmdImageReason.PAYLOAD_CORRUPT]
     * （坏内核不能上机 —— IMAGE-FORMAT §8），因此即使调用方未先跑 [verifyPayloads] 也不会落盘坏文件。
     *
     * @param name "rootfs" | "kernel" | "initrd" | "manifest"
     * @throws IllegalArgumentException name 未知，或该段不存在（flags 未置位）。
     */
    @JvmStatic
    fun extractPayload(info: ImageInfo, name: String, out: File) {
        val f = info.footer
        val seg: Triple<Long, Long, String> = when (name) {
            "rootfs" -> Triple(f.rootfsOffset, f.rootfsSize, f.rootfsSha256)
            "kernel" ->
                if (f.hasKernel) Triple(f.kernelOffset, f.kernelSize, f.kernelSha256)
                else throw IllegalArgumentException("image has no '$name' payload (flags=0x${f.flags.toString(16)})")
            "initrd" ->
                if (f.hasInitrd) Triple(f.initrdOffset, f.initrdSize, f.initrdSha256)
                else throw IllegalArgumentException("image has no '$name' payload (flags=0x${f.flags.toString(16)})")
            "manifest" -> Triple(f.manifestOffset, f.manifestSize, f.manifestSha256)
            else -> throw IllegalArgumentException("unknown payload '$name' (expected rootfs|kernel|initrd|manifest)")
        }
        val (offset, size, expectedSha) = seg
        out.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            RandomAccessFile(info.path, "r").use { raf ->
                FileOutputStream(out).use { fos ->
                    raf.seek(offset)
                    val buf = ByteArray(EXTRACT_BUFFER)
                    var remaining = size
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) {
                            throw VmdImageException.of(
                                "unexpected EOF while extracting '$name' (file changed under us?)",
                                VmdImageReason.TRUNCATED,
                            )
                        }
                        fos.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        remaining -= n
                    }
                }
            }
        } catch (e: VmdImageException) {
            out.delete()
            throw e
        } catch (e: IOException) {
            out.delete()
            throw VmdImageException.of("io error while extracting '$name': ${e.message}", VmdImageReason.IO_ERROR, e)
        }
        val actualSha = hex(digest.digest())
        if (actualSha != expectedSha) {
            out.delete()
            throw VmdImageException.of(
                "'$name' payload sha256 mismatch (footer=$expectedSha, actual=$actualSha): refusing to extract corrupt payload",
                VmdImageReason.PAYLOAD_CORRUPT,
            )
        }
    }

    // ------------------------------------------------------------------
    // footer 解析与校验（IMAGE-FORMAT §3）
    // ------------------------------------------------------------------

    /**
     * 解析 4096 字节 footer（不做边界校验 —— [read] 中由内部校验链完成）。
     * 校验双 magic；u64 字段 ≥ 2^63（无符号溢出为负）→ [VmdImageReason.CORRUPT]。
     *
     * 供 §8 "footer 偏移重编号回归" 等测试直接喂字节断言偏移（manifest@72、kernel@124、initrd@172）。
     */
    @JvmStatic
    fun parseFooter(bytes: ByteArray): Footer {
        if (bytes.size != FOOTER_SIZE) {
            throw VmdImageException.of(
                "footer must be exactly $FOOTER_SIZE bytes, got ${bytes.size}",
                VmdImageReason.FORMAT_UNSUPPORTED,
            )
        }
        if (!matchesMagic(bytes)) {
            throw VmdImageException.of("bad footer magic: not a VMDroid system image", VmdImageReason.NOT_AN_IMAGE)
        }
        fun u64(off: Int, name: String): Long {
            val v = leLong(bytes, off)
            if (v < 0) {
                throw VmdImageException.of("footer.$name exceeds u64 range of a real file", VmdImageReason.CORRUPT)
            }
            return v
        }
        return Footer(
            magic = MAGIC,
            formatVersion = leInt(bytes, 8),
            footerSize = leInt(bytes, 12),
            fileSize = u64(16, "file_size"),
            rootfsOffset = u64(24, "rootfs_offset"),
            rootfsSize = u64(32, "rootfs_size"),
            rootfsSha256 = hex(bytes.copyOfRange(40, 72)),
            manifestOffset = u64(72, "manifest_offset"),
            manifestSize = u64(80, "manifest_size"),
            manifestSha256 = hex(bytes.copyOfRange(88, 120)),
            flags = leInt(bytes, 120),
            kernelOffset = u64(124, "kernel_offset"),
            kernelSize = u64(132, "kernel_size"),
            kernelSha256 = hex(bytes.copyOfRange(140, 172)),
            initrdOffset = u64(172, "initrd_offset"),
            initrdSize = u64(180, "initrd_size"),
            initrdSha256 = hex(bytes.copyOfRange(188, 220)),
            magicTail = MAGIC,
        )
    }

    /** §5 第 2–5 步：footer 字段、flags↔段一致、边界（溢出安全）、段序不重叠。 */
    private fun validateFooter(f: Footer, actualSize: Long) {
        // 防御（read() 已查；双保险）
        if (f.fileSize != actualSize) {
            throw VmdImageException.of(
                "footer.file_size=${f.fileSize} != actual size=$actualSize",
                VmdImageReason.TRUNCATED,
            )
        }
        // §5：format_version ≤ 1（u32 无符号比较）
        val fmt = f.formatVersion.toLong() and 0xFFFF_FFFFL
        if (fmt > MAX_FORMAT_VERSION) {
            throw VmdImageException.of(
                "format_version=$fmt > $MAX_FORMAT_VERSION: format too new, update the app",
                VmdImageReason.FORMAT_UNSUPPORTED,
            )
        }
        // §5：footer_size == 4096（u32 无符号比较）
        val fsz = f.footerSize.toLong() and 0xFFFF_FFFFL
        if (fsz != FOOTER_SIZE.toLong()) {
            throw VmdImageException.of("footer_size=$fsz != $FOOTER_SIZE", VmdImageReason.FORMAT_UNSUPPORTED)
        }
        // §5：合法 flags 掩码 = 0x3（bit0 HAS_KERNEL | bit1 HAS_INITRD；v1 无 seed），保留位必须为 0
        if (f.flags and FLAGS_MASK.inv() != 0) {
            throw VmdImageException.of(
                "unknown flag bits set: flags=0x${f.flags.toString(16)} (legal mask = 0x${FLAGS_MASK.toString(16)})",
                VmdImageReason.FORMAT_UNSUPPORTED,
            )
        }
        // §5 第 4 步：flags ↔ 段一致
        if (f.hasKernel != (f.kernelSize > 0)) {
            throw VmdImageException.of(
                "flags HAS_KERNEL(${f.hasKernel}) != (kernel_size=${f.kernelSize} > 0): corrupt footer",
                VmdImageReason.CORRUPT,
            )
        }
        if (f.hasInitrd != (f.initrdSize > 0)) {
            throw VmdImageException.of(
                "flags HAS_INITRD(${f.hasInitrd}) != (initrd_size=${f.initrdSize} > 0): corrupt footer",
                VmdImageReason.CORRUPT,
            )
        }
        if (!f.hasKernel && f.kernelOffset != 0L) {
            throw VmdImageException.of("kernel_offset=${f.kernelOffset} != 0 while HAS_KERNEL clear", VmdImageReason.CORRUPT)
        }
        if (!f.hasInitrd && f.initrdOffset != 0L) {
            throw VmdImageException.of("initrd_offset=${f.initrdOffset} != 0 while HAS_INITRD clear", VmdImageReason.CORRUPT)
        }
        // §2/§5：rootfs 恒存在且 offset == 0（0 长 rootfs 会让后续段占位到 0 破坏布局）
        if (f.rootfsOffset != 0L) {
            throw VmdImageException.of("rootfs_offset=${f.rootfsOffset} != 0 (format_version 1 requires 0)", VmdImageReason.CORRUPT)
        }
        if (f.rootfsSize < 1) {
            throw VmdImageException.of("rootfs_size=${f.rootfsSize} < 1: empty rootfs", VmdImageReason.CORRUPT)
        }
        // §2：manifest 1 ≤ M ≤ 65536
        if (f.manifestSize < 1 || f.manifestSize > MAX_MANIFEST_SIZE) {
            throw VmdImageException.of(
                "manifest_size=${f.manifestSize} outside [1, $MAX_MANIFEST_SIZE]",
                VmdImageReason.CORRUPT,
            )
        }
        // §5 第 5 步：边界 + 溢出安全 + 段序（rootfs → kernel → initrd → manifest → footer）
        val limit = f.dataLimit // fileSize ≥ 8192 已在 read() 保证 → limit ≥ 4096
        fun contains(off: Long, size: Long, name: String) {
            if (off > Long.MAX_VALUE - size) {
                throw VmdImageException.of(
                    "$name offset+size overflows (overflow-safe check: $off > MAX - $size)",
                    VmdImageReason.CORRUPT,
                )
            }
            if (off + size > limit) {
                throw VmdImageException.of(
                    "$name segment [$off, ${off + size}) exceeds [0, $limit)",
                    VmdImageReason.CORRUPT,
                )
            }
        }
        contains(f.rootfsOffset, f.rootfsSize, "rootfs")
        var prevEnd = f.rootfsSize
        if (f.hasKernel) {
            contains(f.kernelOffset, f.kernelSize, "kernel")
            if (f.kernelOffset < prevEnd) {
                throw VmdImageException.of(
                    "kernel_offset=${f.kernelOffset} < prev segment end=$prevEnd (overlap/out-of-order)",
                    VmdImageReason.CORRUPT,
                )
            }
            prevEnd = f.kernelOffset + f.kernelSize
        }
        if (f.hasInitrd) {
            contains(f.initrdOffset, f.initrdSize, "initrd")
            if (f.initrdOffset < prevEnd) {
                throw VmdImageException.of(
                    "initrd_offset=${f.initrdOffset} < prev segment end=$prevEnd (overlap/out-of-order)",
                    VmdImageReason.CORRUPT,
                )
            }
            prevEnd = f.initrdOffset + f.initrdSize
        }
        contains(f.manifestOffset, f.manifestSize, "manifest")
        if (f.manifestOffset < prevEnd) {
            throw VmdImageException.of(
                "manifest_offset=${f.manifestOffset} < prev segment end=$prevEnd " +
                    "(segments must be ordered rootfs → kernel → initrd → manifest)",
                VmdImageReason.CORRUPT,
            )
        }
    }

    // ------------------------------------------------------------------
    // manifest（IMAGE-FORMAT §4）
    // ------------------------------------------------------------------

    private fun parseManifest(bytes: ByteArray): Manifest {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw VmdImageException.of("manifest is not valid UTF-8: ${e.message}", VmdImageReason.MANIFEST_INVALID)
        }
        if (text.startsWith("\uFEFF")) {
            throw VmdImageException.of("manifest must be UTF-8 without BOM", VmdImageReason.MANIFEST_INVALID)
        }
        val root = try {
            JsonParser(text).parse()
        } catch (e: IllegalArgumentException) {
            throw VmdImageException.of("manifest JSON invalid: ${e.message}", VmdImageReason.MANIFEST_INVALID)
        }
        val obj = root as? Map<*, *>
            ?: throw VmdImageException.of("manifest root must be a JSON object", VmdImageReason.MANIFEST_INVALID)

        // 结构：已知嵌套块若存在必须是 object（类型错 → MANIFEST_INVALID，不静默降级为缺省）
        for (key in listOf("capabilities", "accounts", "contract", "app", "boot", "checksums")) {
            val v = obj[key]
            if (v != null && v !is Map<*, *>) {
                throw VmdImageException.of("manifest.$key must be a JSON object", VmdImageReason.MANIFEST_INVALID)
            }
        }

        // capabilities：无块 → §4 缺省 {ssh:true, x11:true, containers:true}；
        // 有块 → 逐键读取，缺省 false（ssh 缺失 = false → SSH_CAPABILITY_MISSING，fail-closed）
        val caps = if (obj["capabilities"] == null) {
            CapabilitiesBlock(
                ssh = true, x11 = true, desktop = false, containers = true,
                desktopProfile = false, downloadsShare = false, usbPassthroughHost = false,
            )
        } else {
            val c = obj["capabilities"] as Map<*, *>
            CapabilitiesBlock(
                ssh = mbool(c["ssh"]),
                x11 = mbool(c["x11"]),
                desktop = mbool(c["desktop"]),
                containers = mbool(c["containers"]),
                desktopProfile = mbool(c["desktop_profile"]),
                downloadsShare = mbool(c["downloads_share"]),
                usbPassthroughHost = mbool(c["usb_passthrough_host"]),
            )
        }

        // accounts：无块 → §4 缺省 仅 root、ssh_port=22、default_user=root；
        // 有块 → ssh_port 缺省 22，类型非整数 → SSH_PORT_INVALID（fail-closed）
        val accRaw = obj["accounts"] as Map<*, *>?
        val accounts = if (accRaw == null) {
            AccountsBlock(
                ssh = listOf(SshAccount(user = "root", password = null, sudo = false)),
                defaultUser = "root",
                sshPort = REQUIRED_SSH_PORT,
            )
        } else {
            val portRaw = accRaw["ssh_port"]
            val port = when {
                portRaw == null -> REQUIRED_SSH_PORT
                else -> mlong(portRaw) ?: throw VmdImageException.of(
                    "accounts.ssh_port must be an integer (got ${portRaw::class.simpleName})",
                    VmdImageReason.SSH_PORT_INVALID,
                )
            }
            val sshRaw = accRaw["ssh"]
            if (sshRaw != null && sshRaw !is List<*>) {
                throw VmdImageException.of("accounts.ssh must be a JSON array", VmdImageReason.MANIFEST_INVALID)
            }
            val sshAccounts = (sshRaw as? List<*>)?.map { el ->
                val m = el as? Map<*, *>
                    ?: throw VmdImageException.of("accounts.ssh[] entries must be JSON objects", VmdImageReason.MANIFEST_INVALID)
                SshAccount(
                    user = m["user"] as? String ?: "",
                    password = m["password"] as? String,
                    sudo = m["sudo"] as? Boolean ?: false,
                )
            } ?: emptyList()
            AccountsBlock(ssh = sshAccounts, defaultUser = accRaw["default_user"] as? String, sshPort = port)
        }

        // contract：无块 → §4 缺省 contract.version = 1；有块但 version 缺失 → 1；类型非整数 → 非法
        val contractRaw = obj["contract"] as Map<*, *>?
        val contract = if (contractRaw == null) {
            ContractBlock(
                version = 1, markers = emptyList(), ttys = emptyMap(),
                kernelBuiltinOnly = null, kernelMin = null, kernelMax = null, kernelImageSha256 = null,
            )
        } else {
            val verRaw = contractRaw["version"]
            val ver = when {
                verRaw == null -> 1L
                else -> mlong(verRaw) ?: throw VmdImageException.of(
                    "contract.version must be an integer (got ${verRaw::class.simpleName})",
                    VmdImageReason.MANIFEST_INVALID,
                )
            }
            val kernRaw = contractRaw["kernel"] as? Map<*, *>
            ContractBlock(
                version = ver,
                markers = mstrList(contractRaw["markers"]),
                ttys = mstrMap(contractRaw["ttys"]),
                kernelBuiltinOnly = kernRaw?.get("builtin_only") as? Boolean,
                kernelMin = kernRaw?.get("min") as? String,
                kernelMax = kernRaw?.get("max") as? String,
                kernelImageSha256 = kernRaw?.get("image_sha256") as? String,
            )
        }

        // app：无块或无 min_version_code → 无约束（null）；类型非整数 → 非法
        val appRaw = obj["app"] as Map<*, *>?
        val minRaw = appRaw?.get("min_version_code")
        val app = AppBlock(
            minVersionCode = when {
                minRaw == null -> null
                else -> mlong(minRaw) ?: throw VmdImageException.of(
                    "app.min_version_code must be an integer (got ${minRaw::class.simpleName})",
                    VmdImageReason.MANIFEST_INVALID,
                )
            },
        )

        // image / boot / checksums（展示与工具侧字段；语义校验在 validateManifest）
        val imageRaw = obj["image"] as? Map<*, *>
        val distroRaw = imageRaw?.get("distro") as? Map<*, *>
        val image = ImageBlock(
            id = imageRaw?.get("id") as? String,
            displayName = imageRaw?.get("display_name") as? String,
            identity = imageRaw?.get("identity") as? String,
            variant = imageRaw?.get("variant") as? String,
            version = imageRaw?.get("version") as? String,
            systemVersion = imageRaw?.let { mlong(it["system_version"]) },
            arch = imageRaw?.get("arch") as? String,
            distroName = distroRaw?.get("name") as? String,
            distroRelease = distroRaw?.get("release") as? String,
            distroInit = distroRaw?.get("init") as? String,
            createdAt = imageRaw?.get("created_at") as? String,
            source = imageRaw?.get("source") as? String,
            license = imageRaw?.get("license") as? String,
        )

        val boot = (obj["boot"] as Map<*, *>?)?.let { b ->
            BootBlock(
                machine = b["machine"] as? String,
                cpu = b["cpu"] as? String,
                append = b["append"] as? String,
                kernelSha256 = b["kernel_sha256"] as? String,
                initrdSha256 = b["initrd_sha256"] as? String,
                drives = mstrMap(b["drives"]),
            )
        }

        val checksums = mstrMap(obj["checksums"])
        val unknown = obj.entries
            .filter { it.key is String && it.key !in ROOT_MANIFEST_KEYS }
            .associate { it.key as String to it.value }

        return Manifest(
            format = obj["format"] as? String,
            formatVersion = mlong(obj["format_version"]),
            image = image,
            contract = contract,
            capabilities = caps,
            accounts = accounts,
            app = app,
            boot = boot,
            checksums = checksums,
            unknown = unknown,
        )
    }

    /** §5：manifest 语义校验（顺序与伪代码一致）。 */
    private fun validateManifest(m: Manifest, currentVersionCode: Long?) {
        if (m.format != MANIFEST_FORMAT) {
            val got = m.format?.let { "\"$it\"" } ?: "<missing>"
            throw VmdImageException.of(
                "manifest.format=$got != \"$MANIFEST_FORMAT\": not a VMDroid system image",
                VmdImageReason.MANIFEST_INVALID,
            )
        }
        val id = m.image.id
        if (id == null || !IMAGE_ID_REGEX.matches(id)) {
            val got = id?.let { "\"$it\"" } ?: "<missing>"
            throw VmdImageException.of(
                "image.id=$got does not match $IMAGE_ID_PATTERN (drive option injection guard)",
                VmdImageReason.IMAGE_ID_INVALID,
            )
        }
        if (m.image.arch != REQUIRED_ARCH) {
            val got = m.image.arch?.let { "\"$it\"" } ?: "<missing>"
            throw VmdImageException.of("image.arch=$got != \"$REQUIRED_ARCH\"", VmdImageReason.ARCH_MISMATCH)
        }
        if (!m.capabilities.ssh) {
            throw VmdImageException.of(
                "capabilities.ssh is not true: SSH is mandatory (DESIGN §4.8)",
                VmdImageReason.SSH_CAPABILITY_MISSING,
            )
        }
        if (m.accounts.sshPort != REQUIRED_SSH_PORT) {
            throw VmdImageException.of(
                "accounts.ssh_port=${m.accounts.sshPort} != $REQUIRED_SSH_PORT (DESIGN §4.8)",
                VmdImageReason.SSH_PORT_INVALID,
            )
        }
        val min = m.app.minVersionCode
        if (min != null && currentVersionCode != null && min > currentVersionCode) {
            throw VmdImageException.of(
                "app.min_version_code=$min > current versionCode=$currentVersionCode: update the app",
                VmdImageReason.APP_TOO_OLD,
            )
        }
    }

    // ------------------------------------------------------------------
    // DESIGN §5.2 重置判定（§13.1：identity 判据全表归属本类的单测）
    // ------------------------------------------------------------------

    /**
     * 换镜像是否需要重置数据盘（§5.2 决策表，按顺序取第一个匹配）：
     * `same`（内容优先，永不重置）→ `identity` → `contract`（硬判据）→ `init`（硬判据）→ `upgrade`（不重置）。
     * 返回值的 [ResetDecision.code] 可直接写 `image.log`（"勿自造"）。
     */
    @JvmStatic
    fun decideReset(new: ResetKey, active: ResetKey): ResetDecision = when {
        new.rootfsSha256 == active.rootfsSha256 -> ResetDecision.SAME
        new.identity != active.identity -> ResetDecision.IDENTITY
        new.contractVersion != active.contractVersion -> ResetDecision.CONTRACT
        new.distroInit != active.distroInit -> ResetDecision.INIT
        else -> ResetDecision.UPGRADE
    }

    // ------------------------------------------------------------------
    // sha256 工具（下载/导入侧流式使用）
    // ------------------------------------------------------------------

    /** 单块 sha256（小写 hex）。 */
    @JvmStatic
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 流式计算整文件 sha256（小写 hex；用于下载/导入时与 catalog sha256 比对）。 */
    @JvmStatic
    fun sha256File(path: String): String {
        val file = File(path)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(EXTRACT_BUFFER)
            RandomAccessFile(file, "r").use { raf ->
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        } catch (e: IOException) {
            throw VmdImageException.of("io error while hashing $file: ${e.message}", VmdImageReason.IO_ERROR, e)
        }
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

    private fun verifySegment(file: File, offset: Long, size: Long, expected: String, name: String) {
        val actual = sha256Range(file, offset, size)
        if (actual != expected) {
            throw VmdImageException.of(
                "'$name' payload sha256 mismatch (footer=$expected, actual=$actual)",
                VmdImageReason.PAYLOAD_CORRUPT,
            )
        }
    }

    private fun sha256Range(file: File, offset: Long, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(EXTRACT_BUFFER)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var remaining = size
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) {
                    throw VmdImageException.of(
                        "unexpected EOF reading [$offset, +$size) of $file",
                        VmdImageReason.TRUNCATED,
                    )
                }
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return hex(md.digest())
    }

    private fun readAt(file: File, offset: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var got = 0
            while (got < size) {
                val n = raf.read(out, got, size - got)
                if (n < 0) {
                    throw VmdImageException.of(
                        "unexpected EOF reading [$offset, +$size) of $file (truncated concurrently?)",
                        VmdImageReason.TRUNCATED,
                    )
                }
                got += n
            }
        }
        return out
    }

    private fun matchesMagic(footerBytes: ByteArray): Boolean {
        if (footerBytes.size < FOOTER_SIZE) return false
        return ascii(footerBytes, 0, 8) == MAGIC && ascii(footerBytes, MAGIC_TAIL_OFFSET, 8) == MAGIC
    }

    private fun startsWithHsqs(file: File): Boolean {
        if (file.length() < 4) return false
        val head = readAt(file, 0, 4)
        return head[0] == 'h'.code.toByte() && head[1] == 's'.code.toByte() &&
            head[2] == 'q'.code.toByte() && head[3] == 's'.code.toByte()
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String = String(b, off, len, Charsets.US_ASCII)

    private fun leLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) {
            v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        }
        return v
    }

    private fun leInt(b: ByteArray, off: Int): Int = leLong(b, off).toInt()

    private fun mbool(v: Any?): Boolean = v as? Boolean ?: false

    /** JSON 整数（Long）或整值 Double → Long；其他 → null。 */
    private fun mlong(v: Any?): Long? = when (v) {
        is Long -> v
        is Double ->
            if (v.isFinite() && v % 1.0 == 0.0 && v >= -9.007199254740992E15 && v <= 9.007199254740992E15) v.toLong()
            else null
        else -> null
    }

    private fun mstrList(v: Any?): List<String> =
        (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun mstrMap(v: Any?): Map<String, String> =
        (v as? Map<*, *>)
            ?.entries
            ?.filter { it.key is String && it.value is String }
            ?.associate { it.key as String to it.value as String }
            ?: emptyMap()
}
