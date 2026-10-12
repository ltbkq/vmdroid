/*
 * VMDroid - system image store (M3, pure JVM core of SystemImageRepository).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN.md §2.3（文件布局 / active.json 字段口径）、§5.1 状态机、
 *     §5.2 identity 重置判定、§5.3 激活与回滚、§6.3 手动导入、§6.4 校验记录、
 *     §16.3/§16.5（*.meta.json 原子写、image.log 事件口径）
 *   - docs/IMAGE-FORMAT.md §5（v1 读取算法与校验时机表）
 *   - docs/DESIGN-disk-image-v2.md §5.3/§5.4（v2 读取算法、pristine/dirty 状态机、
 *     content_id 判定）、§6.1（.img.zst 流式稀疏解压安装）、§6.4（两级恢复出厂）、
 *     §6.5（根分区扩容：setLength + pending_grow，应用侧不编辑分区表）
 *   - docs/IMAGE-FORMAT-v2.md §5（v2 读取算法/校验时机表）、§7.1（.img.zst 容器：
 *     全零 4 KiB 块跳写稀疏落盘）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 独立编译，
 * 由 systemimage-selftest/SelfTest.kt（无 JUnit）覆盖。Android 侧的 Context/通知等薄壳
 * 见 SystemImageRepository.kt（本类经 filesDir 注入即可工作）。
 *
 * 唯一写者（DESIGN §5.3, R2: A-R2-6/D-R2-7）：`active.json` 只由本类的
 * [writeActive]（经 [activate]）写入。
 *
 * ── 格式缝（M3，DESIGN §7 改动面表 SystemImageStore/Repository/BootGuard 三行）──
 * 本类只做**格式无关**编排：路径、`.meta.json` 状态机（pristine/dirty）、原子 rename、
 * image.log 事件、`content_id` 重置判定、两级恢复出厂、扩容基线。格式相关动作
 * （读取/全量校验/落盘）经 [SystemImageFormat] 注入：
 *
 *   - [LegacyV1Format]（本文件内，v1 `VmdImageCodec`）= 兼容/诊断路径 ——
 *     `systemimage-selftest/README.md` §2 的 kotlinc 命令**只编译本文件与 v1 codec**
 *     （不含 VmdDiskCodec.kt），因此缝必须留在这里；自测的 v1 夹具走它，47 条断言不变。
 *   - `V2SystemImageFormat`（SystemImageFormat.kt，`VmdDiskCodec` + `ZstdInflator`）=
 *     **生产路径**，由 SystemImageRepository 注入。v1 输入在 v2 codec 的 §5 诊断阶梯上
 *     判 LEGACY_FORMAT → BootGuard 拒绝（新 reason，见 BootGuardReason.LEGACY_FORMAT）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 镜像生命周期事件出口（DESIGN §16.5 `image.log` 的 JSONL 行）。
 * 纯逻辑层不知道日志文件在哪 —— Android 壳把它接到 [ImageLog]，
 * 自测里接到一个内存列表。事件名与字段见 §16.5 表。
 */
fun interface ImageEventSink {
    fun record(event: String, fields: Map<String, Any?>)
}

/**
 * DESIGN §5.2 重置判定 `decision`（**声明序 = §5.2 表序**；[code] 为 image.log
 * 直接引用的小写值，"勿另造"）。
 *
 * v1/v2 共用同一张判定表（v1 codec 内的同名枚举语义一致，M3 起 store 侧统一用本枚举，
 * `VmdImageCodec` 只作诊断/兼容路径）。§5.4 的唯一差异在"同一系统"的判据键：
 * v2 = `image.content_id`，v1 = `rootfs_sha256`（见 [SystemImageStore.decideFor]）。
 */
enum class ResetDecision(val code: String) {
    NO_ACTIVE("no_active"),
    ACTIVE_UNREADABLE("active_unreadable"),
    SAME("same"),
    IDENTITY("identity"),
    CONTRACT("contract"),
    INIT("init"),
    UPGRADE("upgrade"),
}

/** DESIGN §5.2：哪些 `decision` 表示"必须重置数据盘"（`no_active`/`same`/`upgrade` 否）。 */
fun ResetDecision.requiresReset(): Boolean = when (this) {
    ResetDecision.IDENTITY,
    ResetDecision.CONTRACT,
    ResetDecision.INIT,
    ResetDecision.ACTIVE_UNREADABLE,
    -> true
    else -> false
}

/**
 * 格式无关的镜像语义快照（`VmdImageCodec.ImageInfo` 与 `VmdDiskCodec.ImageInfo`
 * 的公共视图；UI 只读 `manifest.image.*` 链，故保持同形）。
 *
 * @param contentId manifest `image.content_id`（v2 构建期内容树摘要；v1 = null）
 * @param rootfsSha256 "同一系统"回退键：v1 = `footer.rootfs_sha256`；
 *   v2 = `manifest.checksums.root_sha256`（旧字段语义，保留仅作日志/回退比较）
 */
data class ImageSnapshot(
    val path: String,
    val size: Long,
    val formatVersion: Long,
    val contentId: String?,
    val rootfsSha256: String?,
    val contractVersion: Long?,
    val distroInit: String?,
    val manifest: ManifestView,
) {
    /** 兼容 UI 既有读取链：`info.manifest.image.identity` 等（ui/ 只读，勿改形）。 */
    data class ManifestView(val image: ImageView)

    data class ImageView(
        val id: String?,
        val displayName: String?,
        val variant: String?,
        val identity: String?,
        val version: String?,
        val systemVersion: Long?,
        val arch: String?,
    )
}

/** [SystemImageFormat.probe] 结果（拒绝时保留 v2 专属 guard reason，如 LEGACY_FORMAT）。 */
sealed class FormatVerdict {
    data class Ok(val snapshot: ImageSnapshot) : FormatVerdict()
    data class Reject(val reason: VmdImageReason, val guard: BootGuardReason, val detail: String) : FormatVerdict()
}

/** 安装源（DESIGN §6.1：catalog 的 `.img.zst` / SAF 导入的裸 `.img`，容器由格式层嗅探）。 */
sealed class InstallSource {
    /** 事件 `src_uri` / `src` 字段的来源标签（`download` / `import` / `reinstall` / uri）。 */
    abstract val label: String

    /** 输入流（SAF `content://` 打开的 fd、内存管道等）；[open] 由安装流负责 close。 */
    class Stream(val open: () -> InputStream, override val label: String = "stream") : InstallSource()

    /** 已落盘文件（`cache/<id>.img.zst` 离线重装、外部 `.img` 导入）。 */
    class FileSource(val file: File, override val label: String = "file") : InstallSource()
}

/**
 * 展示字段（catalog 条目带来的非校验字段；写入 `.meta.json` 的 `display` 与
 * `import_done` 事件，供 UI/日志展示，不参与任何校验）。
 *
 * [systemVersion]/[arch] 不在 catalog 交接链（`CatalogDisplay`/`.part.info`）上：
 * 安装流由 [SystemImageStore.install] 从镜像 manifest 回填（DESIGN-disk-image-v2
 * §7.2 T-2；读取侧缺字段容忍 = null）。
 */
data class InstallDisplay(
    val displayName: String? = null,
    val identity: String? = null,
    val variant: String? = null,
    val version: String? = null,
    val systemVersion: Long? = null,
    val arch: String? = null,
    val channel: String? = null,
) {
    /** 非空项组成的 map（全 null 时返回 null，`.meta.json` 不写空块）。 */
    fun toMap(): Map<String, Any?>? {
        val m = linkedMapOf<String, Any?>()
        displayName?.let { m["display_name"] = it }
        identity?.let { m["identity"] = it }
        variant?.let { m["variant"] = it }
        version?.let { m["version"] = it }
        systemVersion?.let { m["system_version"] = it }
        arch?.let { m["arch"] = it }
        channel?.let { m["channel"] = it }
        return m.ifEmpty { null }
    }

    companion object {
        /** 从 `.meta.json` 的 `display` 块还原（[SystemImageStore.readMeta]）。 */
        fun fromMap(raw: Map<*, *>?): InstallDisplay? =
            if (raw == null) null else InstallDisplay(
                displayName = MiniJson.str(raw, "display_name"),
                identity = MiniJson.str(raw, "identity"),
                variant = MiniJson.str(raw, "variant"),
                version = MiniJson.str(raw, "version"),
                systemVersion = MiniJson.long(raw, "system_version"),
                arch = MiniJson.str(raw, "arch"),
                channel = MiniJson.str(raw, "channel"),
            )
    }
}

/**
 * `install(...)` 的**显式契约**（M3 接口缝：不依赖 `ImageCatalog` 的具体类型，
 * catalog 工位按此适配）。全部校验字段可空 —— 缺哪个就跳过哪道比对，
 * 结构校验（v2 = `VmdDiskCodec.read` 阶段 1–3）恒执行。
 *
 * @param imageId catalog `image_id`；已知时用于 `.part` 命名，且必须与 manifest
 *   `image.id` 一致（不一致 → IMAGE_ID_INVALID）。SAF 手动导入传 null（以 manifest 为准）。
 * @param src 事件里的 `src` 字段（§16.5：`download` / `import` / `reinstall` / uri）
 * @param zstSha256 catalog `sha256`（`.img.zst` 摘要）—— 安装流边喂 zstd 边算源摘要比对
 * @param zstSize catalog `size`（`.img.zst` 字节数）—— 安装进度总量 + 记录
 * @param imageSha256 catalog `image_sha256`（解压后 `.img` 摘要）—— 第二道比对
 * @param imageSize catalog `image_size`（`.img` apparent 字节数）—— 落盘字节数比对
 * @param display catalog 展示字段（写 `.meta.json.display` 与 `import_done`）
 * @param onProgress 源字节进度回调（zst 安装 = 已消费的压缩字节 / [zstSize]）
 */
data class InstallRequest(
    val imageId: String? = null,
    val src: String = "import",
    val zstSha256: String? = null,
    val zstSize: Long? = null,
    val imageSha256: String? = null,
    val imageSize: Long? = null,
    val display: InstallDisplay = InstallDisplay(),
    val onProgress: ((Long) -> Unit)? = null,
)

/** 安装流产物（格式层返回；`.img` 已在 `tmp` 就位并通过结构校验）。 */
data class InstallPayload(
    val imageId: String,
    val sha256: String,
    val size: Long,
    /** manifest `image.content_id`（v2；v1 = null）。 */
    val contentId: String? = null,
    val identity: String? = null,
    val contractVersion: Long? = null,
    val distroInit: String? = null,
)

/**
 * 格式相关动作的缝（M3）。实现：[LegacyV1Format]（v1，本文件）与
 * `V2SystemImageFormat`（v2，SystemImageFormat.kt）。
 *
 * 失败契约：[probe] 返回 [FormatVerdict.Reject]；[read]/[verifyFull]/[materialise]
 * 抛 [VmdImageException]（UI 文案层 `imageErrorMessage` 只认 v1 reason 集合）。
 */
interface SystemImageFormat {
    /** 格式名（image.log / 诊断：`v1` / `v2`）。 */
    val name: String

    /** 写入 `.meta.json` 的 `format_version`。 */
    val formatVersion: Long

    /** 读取 + 语义校验（启动前/激活用）；**不抛异常**，拒绝时保留 guard reason。 */
    fun probe(file: File): FormatVerdict

    /** 同 [probe]，失败抛 [VmdImageException]（`readImage()` 的 runCatching 语义依赖此形）。 */
    fun read(file: File): ImageSnapshot {
        val verdict = probe(file)
        return when (verdict) {
            is FormatVerdict.Ok -> verdict.snapshot
            is FormatVerdict.Reject -> throw VmdImageException.of(verdict.detail, verdict.reason)
        }
    }

    /** 手动全量校验（v1 = payload 段摘要；v2 = 阶段 1–4，root 分区全字节 sha256）。 */
    fun verifyFull(file: File)

    /**
     * 把 [source] 写成 `tmp`（v1 = 流拷贝+摘要；v2 = 稀疏落盘+双摘要+结构校验）。
     * 失败抛 [VmdImageException]，`tmp` 的清理由 [SystemImageStore.install] 负责。
     */
    fun materialise(source: InstallSource, tmp: File, request: InstallRequest): InstallPayload
}

/**
 * `filesDir/images/` 的全部读写逻辑（安装 / 激活 / 删除 / 校验 / 状态机 / 扩容）。
 *
 * @param filesDir 应用 `filesDir`（§2.3：`images/`、`storage.img` 都在其下）
 * @param versionCode 当前 versionCode（§7.4 #5 APP_TOO_OLD 比较用）
 * @param nowMs 时钟（自测可控）
 * @param events 生命周期事件出口（`image.log`），null = 不记日志
 * @param format 格式缝；默认 [LegacyV1Format]（v1 兼容/自测路径）。
 *   **生产必须注入 `V2SystemImageFormat`**（见 SystemImageRepository）。
 */
class SystemImageStore @JvmOverloads constructor(
    private val filesDir: File,
    private val versionCode: () -> Long = { 1L },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val events: ImageEventSink? = null,
    format: SystemImageFormat? = null,
) {
    // ---------------------------------------------------------------- 类型

    /**
     * `active.json` 记录（§2.3 冻结字段 `{image_id, identity, rootfs_sha256,
     * activated_at}`；M3 增 `content_id`（§5.4 v2 判定键）；[path] 为可选容忍字段）。
     * [idValid] = `image_id` 是否匹配 §7.4 #7 正则；非法 id 不参与路径计算。
     */
    data class ActiveRecord(
        val imageId: String,
        val identity: String?,
        val rootfsSha256: String?,
        val activatedAt: String?,
        val path: String? = null,
        val idValid: Boolean = IMAGE_ID_REGEX.matches(imageId),
        /** §5.4：v2 判定键（manifest `image.content_id`）；v1 镜像 = null。 */
        val contentId: String? = null,
    )

    /**
     * `*.meta.json`（DESIGN §6.4 + DESIGN-disk-image-v2 §5.4 状态机）。
     *
     * 基线三元组 `size`/`mtime`/`baseline_at` = "上次接受该文件字节的时刻"；
     * 启动前不一致时按 §5.4 二分归因（有 boot 记录 → guest 写入 → 转 dirty 放行，
     * 无 → 外部篡改 → CORRUPT）。
     */
    data class ImageMeta(
        val sha256: String,
        val size: Long,
        val mtime: Long,
        val verifiedAt: String?,
        val formatVersion: Long?,
        /** `pristine` | `dirty`（§5.4；旧 meta 缺省视为 pristine）。 */
        val state: String = STATE_PRISTINE,
        val dirtySince: String? = null,
        val lastMtime: Long? = null,
        /** 基线建立时刻（ISO；缺省回退 [verifiedAt]，再回退 [mtime]）。 */
        val baselineAt: String? = null,
        /** §6.5：文件已被应用扩容、几何领先于镜像内记录（guest 下次开机自扩容）。 */
        val pendingGrow: Boolean = false,
        /** 安装期整 `.img` 摘要（= [sha256]；catalog `image_sha256` 的落盘副本）。 */
        val imageSha256: String? = null,
        val contentId: String? = null,
        /** 安装源 `.img.zst` 摘要（§6.4-B 离线重装复检用）。 */
        val zstSha256: String? = null,
        // ---- pending_grow 兜底快照字段（§6.5：扩容后结构读取失败时供 decideReset 用） ----
        val identity: String? = null,
        val contractVersion: Long? = null,
        val distroInit: String? = null,
        val display: InstallDisplay? = null,
    )

    /** 已安装镜像（§5.1 `INSTALLED`）。 */
    data class InstalledImage(
        val imageId: String,
        val file: File,
        val meta: ImageMeta?,
    )

    /** §6.5 扩容结果（`pending_grow` 由 guest 下次开机消费，见 DESIGN-disk-image-v2 §6.5）。 */
    data class GrowResult(
        val imageId: String,
        val oldSize: Long,
        val newSize: Long,
        val pendingGrow: Boolean = true,
    )

    /** `activate()` 结果（§5.1：`RESET_REQUIRED` 是**信号**，不落盘、不清数据）。 */
    sealed class ActivateResult {
        /** §5.2 `decision` 枚举（`same`/`upgrade`/`identity`/`contract`/`init`）。 */
        abstract val decision: String

        /** 已写 `active.json`（[from] = 原 active id）。 */
        data class Activated(override val decision: String, val from: String?) : ActivateResult()

        /** 目标镜像 identity/contract/init 变化 —— 需用户确认后重建 storage.img。 */
        data class ResetRequired(override val decision: String, val to: String) : ActivateResult()
    }

    /** 激活前检查（不写任何文件）。[Rejected.guard] 保留 v2 专属 reason（LEGACY_FORMAT）。 */
    sealed class ActivationCheck {
        data class Eligible(val decision: ResetDecision) : ActivationCheck()
        data class Rejected(
            val reason: VmdImageReason,
            val detail: String,
            val guard: BootGuardReason? = null,
        ) : ActivationCheck()
    }

    /** §6.4 校验结果（`full=false` 启动前快速路径 / `full=true` 手动全量）。 */
    data class VerifyResult(
        val ok: Boolean,
        val full: Boolean,
        val reason: String?,
        val detail: String,
        val sha256: String?,
    )

    // ---------------------------------------------------------------- 路径

    /** `filesDir/images/`（不存在时按需创建）。 */
    val imagesDir: File
        get() {
            val dir = File(filesDir, IMAGES_DIR)
            if (!dir.isDirectory) dir.mkdirs()
            return dir
        }

    /**
     * `filesDir/cache/`（§6.1：`<id>.img.zst` 下载缓存；§6.4-B 离线重装的取处）。
     * 下载侧（catalog 工位）落盘到此，本类只读。
     */
    val cacheDir: File
        get() {
            val dir = File(filesDir, CACHE_DIR)
            if (!dir.isDirectory) dir.mkdirs()
            return dir
        }

    private val imageLogFile: File get() = File(filesDir, IMAGE_LOG_FILE)
    private val bootsDir: File get() = File(filesDir, BOOTS_DIR)

    private fun imageFile(id: String) = File(imagesDir, "$id.img")
    private fun metaFile(id: String) = File(imagesDir, "$id.img$META_SUFFIX")
    private val activeFile get() = File(imagesDir, ACTIVE_FILE)

    /** 注入的格式实现；未注入 = v1 兼容路径（自测/旧镜像）。 */
    private val fmt: SystemImageFormat = format ?: LegacyV1Format(versionCode)

    /** 给 BootGuard/引擎用的 versionCode 视图。 */
    fun currentVersionCode(): Long = versionCode()

    // ---------------------------------------------------------------- 读取

    /**
     * 解析 `active.json`（§2.3 + §5.4 `content_id`）。任何失败（无文件 / 非法 JSON /
     * 缺 `image_id`）返回 null = "无激活记录"。**注意**：id 不合法时仍返回记录
     * （`idValid=false`），由 [BootGuard] 判 `IMAGE_ID_INVALID`（§7.4 #7）。
     */
    fun active(): ActiveRecord? {
        val f = activeFile
        if (!f.isFile) return null // 首次运行 / 尚未安装镜像
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return null
        }
        val json = try {
            MiniJson.parseObject(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val imageId = MiniJson.str(json, IMAGE_ID_FIELD)?.takeIf { it.isNotEmpty() } ?: return null
        return ActiveRecord(
            imageId = imageId,
            identity = MiniJson.str(json, IDENTITY_FIELD),
            rootfsSha256 = MiniJson.str(json, ROOTFS_SHA256_FIELD),
            activatedAt = MiniJson.str(json, ACTIVATED_AT_FIELD),
            path = MiniJson.str(json, PATH_FIELD),
            contentId = MiniJson.str(json, CONTENT_ID_FIELD),
        )
    }

    /**
     * 激活镜像的绝对路径，或 null（无记录 / id 非法 / 文件缺失）。
     * **绝不返回不存在的路径** —— 调用方（引擎 fail-fast）依赖该不变量。
     */
    fun activeImageFile(): File? {
        val rec = active() ?: return null
        if (!rec.idValid) return null
        val f = File(imagesDir, "${rec.imageId}.img")
        return if (f.isFile) f else null
    }

    /** 已安装镜像列表（按 `image_id` 排序；`.meta.json` 缺失时 meta=null）。 */
    fun list(): List<InstalledImage> {
        val files = imagesDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".img") && !it.name.endsWith(PART_SUFFIX) }
            .map { f ->
                val id = f.name.removeSuffix(".img")
                InstalledImage(id, f, readMeta(id))
            }
            .sortedBy { it.imageId }
    }

    /** 读 `*.meta.json`；缺失/损坏/`image_id` 非法 → null。 */
    fun readMeta(imageId: String): ImageMeta? {
        if (!IMAGE_ID_REGEX.matches(imageId)) return null
        val f = metaFile(imageId)
        if (!f.isFile) return null
        val json = try {
            MiniJson.parseObject(f.readText())
        } catch (e: Exception) {
            return null // 半截文件（写中断）= 无 meta，由 BootGuard 走全量重算
        }
        val sha = MiniJson.str(json, SHA256_FIELD) ?: return null
        val size = MiniJson.long(json, SIZE_FIELD) ?: return null
        val mtime = MiniJson.long(json, MTIME_FIELD) ?: return null
        return ImageMeta(
            sha256 = sha,
            size = size,
            mtime = mtime,
            verifiedAt = MiniJson.str(json, VERIFIED_AT_FIELD),
            formatVersion = MiniJson.long(json, FORMAT_VERSION_FIELD),
            state = MiniJson.str(json, STATE_FIELD) ?: STATE_PRISTINE,
            dirtySince = MiniJson.str(json, DIRTY_SINCE_FIELD),
            lastMtime = MiniJson.long(json, LAST_MTIME_FIELD),
            baselineAt = MiniJson.str(json, BASELINE_AT_FIELD),
            pendingGrow = json[PENDING_GROW_FIELD] == true,
            imageSha256 = MiniJson.str(json, IMAGE_SHA256_FIELD),
            contentId = MiniJson.str(json, CONTENT_ID_FIELD),
            zstSha256 = MiniJson.str(json, ZST_SHA256_FIELD),
            identity = MiniJson.str(json, IDENTITY_FIELD),
            contractVersion = MiniJson.long(json, CONTRACT_VERSION_FIELD),
            distroInit = MiniJson.str(json, DISTRO_INIT_FIELD),
            display = InstallDisplay.fromMap(json[DISPLAY_FIELD] as? Map<*, *>),
        )
    }

    /**
     * §6.4 启动前快速判定：`size` + `mtime` 与 meta 基线一致即信任（**不**重算 sha256）。
     * meta 为 null 时恒 false（调用方据此走 §5.4 状态机 / 判 CORRUPT）。
     */
    fun quickVerify(file: File, meta: ImageMeta?): Boolean {
        if (meta == null || !file.isFile) return false
        return file.length() == meta.size && file.lastModified() == meta.mtime
    }

    /** [preflightMeta] 单项判定（§7.3）。 */
    data class PreflightVerdict(
        val imageId: String,
        /** `.meta.json` 是否存在且可解析。 */
        val metaPresent: Boolean,
        /** [quickVerify]（size+mtime）是否通过。 */
        val ok: Boolean,
    )

    /**
     * §7.3 `awaitImagesReady()` 的前置判定扫描：逐个读 `.meta.json` 并做
     * [quickVerify]（size+mtime，**不**重算 sha256）。单项异常吞掉记 false。
     * **不写 image.log**（§16.5 事件表未定义该事件，勿自造）。
     */
    fun preflightMeta(): List<PreflightVerdict> {
        val installed = try {
            list()
        } catch (e: Exception) {
            return emptyList()
        }
        return installed.map { img ->
            val ok = try {
                quickVerify(img.file, img.meta)
            } catch (e: Exception) {
                false
            }
            PreflightVerdict(img.imageId, metaPresent = img.meta != null, ok = ok)
        }
    }

    // ---------------------------------------------------------------- 写 meta / active

    /**
     * 读取镜像语义快照（`readImage`）。
     * @throws VmdImageException reason 已按 §7.4 映射（见 [VmdImageReason]）
     */
    fun readImage(imageId: String): ImageSnapshot = fmt.read(imageFile(imageId))

    /**
     * 读取快照的**判决形态**（[SystemImageFormat.probe] + §6.5 pending_grow 容忍）：
     * 文件已被应用扩容（几何领先于镜像内 GPT 备份表/meta.file_size 记录，v2 结构读取
     * 必然失败）且 meta 可兜底时 → 回退 `.meta.json` 字段构造快照并记
     * `grow_read_tolerated`（否则 guest 永远等不到"下次开机自扩容"）。
     * 其余拒绝原样带出（**guard 不丢**，如 v1 镜像的 LEGACY_FORMAT）。
     */
    private fun snapshotVerdict(imageId: String, file: File, meta: ImageMeta?): FormatVerdict {
        val verdict = fmt.probe(file)
        if (verdict is FormatVerdict.Ok || meta == null || !meta.pendingGrow) return verdict
        verdict as FormatVerdict.Reject
        val fallback = fallbackSnapshot(imageId, file, meta)
            ?: return verdict
        emit(
            "grow_read_tolerated",
            mapOf("image" to imageId, "err" to verdict.detail, "pending_grow" to true),
            nowMs(),
        )
        return FormatVerdict.Ok(fallback)
    }

    /** [snapshotVerdict] 的抛异常形态（`readImage()`/`activate()` 收尾读用）。 */
    private fun snapshotFor(imageId: String, file: File, meta: ImageMeta?): ImageSnapshot {
        val verdict = snapshotVerdict(imageId, file, meta)
        return when (verdict) {
            is FormatVerdict.Ok -> verdict.snapshot
            is FormatVerdict.Reject -> throw VmdImageException.of(verdict.detail, verdict.reason)
        }
    }

    /** pending_grow 兜底快照（字段来自安装期写入的 `.meta.json`）。 */
    private fun fallbackSnapshot(imageId: String, file: File, meta: ImageMeta): ImageSnapshot? {
        val identity = meta.identity ?: meta.display?.identity ?: return null
        return ImageSnapshot(
            path = file.absolutePath,
            size = file.length(),
            formatVersion = meta.formatVersion ?: 2L,
            contentId = meta.contentId,
            rootfsSha256 = null,
            contractVersion = meta.contractVersion,
            distroInit = meta.distroInit,
            manifest = ImageSnapshot.ManifestView(
                ImageSnapshot.ImageView(
                    id = imageId,
                    displayName = meta.display?.displayName,
                    variant = meta.display?.variant,
                    identity = identity,
                    version = meta.display?.version,
                    systemVersion = meta.display?.systemVersion,
                    arch = meta.display?.arch,
                ),
            ),
        )
    }

    /**
     * 写 `*.meta.json`（原子写；**基线刷新为 pristine**）。
     *
     * [sha256] 为整镜像 sha256；未知时传 null 会先算一遍（手动校验路径）。
     * 与安装期无关但需要跨写保留的字段（`zst_sha256`/`content_id`/`display`/
     * `pending_grow`/identity 等）从既有 meta 合并保留 —— 手动校验不应弄丢
     * §6.4-B 重装所需的源摘要。
     */
    fun writeMeta(imageFile: File, sha256: String? = null): ImageMeta {
        val sha = sha256 ?: sha256File(imageFile)
        val now = isoNow()
        val old = readRawMeta(imageFile.name.removeSuffix(IMG_SUFFIX).removeSuffix(META_SUFFIX))
        val meta = ImageMeta(
            sha256 = sha,
            size = imageFile.length(),
            mtime = imageFile.lastModified(),
            verifiedAt = now,
            formatVersion = fmt.formatVersion,
            state = STATE_PRISTINE,
            dirtySince = null,
            lastMtime = null,
            baselineAt = now,
            pendingGrow = old?.get(PENDING_GROW_FIELD) == true,
            imageSha256 = MiniJson.str(old, IMAGE_SHA256_FIELD) ?: sha,
            contentId = MiniJson.str(old, CONTENT_ID_FIELD),
            zstSha256 = MiniJson.str(old, ZST_SHA256_FIELD),
            identity = MiniJson.str(old, IDENTITY_FIELD),
            contractVersion = MiniJson.long(old, CONTRACT_VERSION_FIELD),
            distroInit = MiniJson.str(old, DISTRO_INIT_FIELD),
            display = InstallDisplay.fromMap(old?.get(DISPLAY_FIELD) as? Map<*, *>),
        )
        AtomicFiles.write(metaFileOf(imageFile), metaJson(meta))
        return meta
    }

    /**
     * §7.2 T-2：`display` 缺 `system_version`/`arch` 时从镜像 manifest 回填。
     *
     * 安装流里格式层本就把 manifest 解析出来了（v1 = `VmdImageCodec.read`，
     * v2 = `VmdDiskCodec.read`），但 catalog 交接链（`CatalogDisplay`/`.part.info`）
     * 不携带这两项、SAF 手动导入更无 catalog 值 —— 故以 manifest 为真值源回填。
     * best-effort：读取失败或字段缺失一律保持原样（纯展示字段，不参与任何校验）。
     */
    private fun displayWithManifest(file: File, display: InstallDisplay): InstallDisplay {
        if (display.systemVersion != null && display.arch != null) return display
        val image = runCatching { fmt.read(file).manifest.image }.getOrNull() ?: return display
        return display.copy(
            systemVersion = display.systemVersion ?: image.systemVersion,
            arch = display.arch ?: image.arch,
        )
    }

    /** 安装完成时的 pristine 基线（§6.1：`{state:"pristine", size, mtime, image_sha256, …}`）。 */
    private fun writeInstalledMeta(
        file: File,
        payload: InstallPayload,
        request: InstallRequest,
        display: InstallDisplay,
    ): ImageMeta {
        val now = isoNow()
        val meta = ImageMeta(
            sha256 = payload.sha256,
            size = file.length(),
            mtime = file.lastModified(),
            verifiedAt = now,
            formatVersion = fmt.formatVersion,
            state = STATE_PRISTINE,
            dirtySince = null,
            lastMtime = null,
            baselineAt = now,
            pendingGrow = false,
            imageSha256 = payload.sha256,
            contentId = payload.contentId,
            zstSha256 = request.zstSha256,
            identity = payload.identity ?: request.display.identity,
            contractVersion = payload.contractVersion,
            distroInit = payload.distroInit,
            display = display.takeIf { it.toMap() != null }
                ?: payload.identity?.let { display.copy(identity = it) },
        )
        AtomicFiles.write(metaFileOf(file), metaJson(meta))
        return meta
    }

    /**
     * 增量更新 `*.meta.json`（[markDirty]/[markBooted]/[growSystemImage] 用）：
     * 读原 JSON → 应用 [transform] → 原子写回（半截/缺失时从空对象起步，保留字段由
     * transform 决定）。
     */
    private fun patchMeta(imageId: String, transform: (MutableMap<String, Any?>) -> Unit) {
        val f = metaFile(imageId)
        val map: MutableMap<String, Any?> = if (f.isFile) {
            try {
                MiniJson.parseObject(f.readText()).toMutableMap()
            } catch (e: Exception) {
                LinkedHashMap()
            }
        } else {
            LinkedHashMap()
        }
        transform(map)
        AtomicFiles.write(f, MiniJson.write(map))
    }

    /**
     * 写 `active.json`（原子写；**唯一写者入口**，§5.3）。
     * 字段口径 = DESIGN §2.3 冻结的四个字段 + M3 的 `content_id`（§5.4）。
     * `rootfs_sha256` **保留写入**：v1 镜像的判定仍需它；v2 镜像写
     * manifest `checksums.root_sha256`（同名字段的最接近语义），供 BootMeta/日志展示。
     * 取舍说明见交付报告（保留 = 最小破坏：VmdroidService/BootMeta 仍在读该字段）。
     */
    fun writeActive(record: ActiveRecord) {
        AtomicFiles.write(
            activeFile,
            MiniJson.write(
                linkedMapOf(
                    IMAGE_ID_FIELD to record.imageId,
                    IDENTITY_FIELD to record.identity,
                    ROOTFS_SHA256_FIELD to record.rootfsSha256,
                    CONTENT_ID_FIELD to record.contentId,
                    ACTIVATED_AT_FIELD to (record.activatedAt ?: isoNow()),
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- 安装（§6.1）

    /**
     * 安装主入口（格式无关编排）：
     * `source` → `images/<…>.img.part`（格式层：v2 稀疏落盘/双摘要/结构校验）→
     * **原子 rename** → `.meta.json {state:"pristine", size, mtime, image_sha256, …}`。
     * 任一步失败删除 `.part` 并抛出（§6.1 断电安全：`.part` 永不冒充成品）。
     *
     * @param source [InstallSource.Stream]（SAF fd / 任意流，容器嗅探）或
     *   [InstallSource.FileSource]（`cache/<id>.img.zst` 重装等）
     * @param request 显式契约字段（见 [InstallRequest]；不依赖 ImageCatalog 类型）
     * @throws VmdImageException 校验失败（reason 见 [VmdImageReason]）
     */
    @JvmOverloads
    fun install(source: InstallSource, request: InstallRequest = InstallRequest()): InstalledImage {
        val startedAt = nowMs()
        val tmpName = if (request.imageId != null && IMAGE_ID_REGEX.matches(request.imageId)) {
            "${request.imageId}$IMG_SUFFIX$PART_SUFFIX"
        } else {
            ".import-${startedAt}$PART_SUFFIX"
        }
        val tmp = File(imagesDir, tmpName)

        val payload = try {
            fmt.materialise(source, tmp, request)
        } catch (e: Throwable) {
            tmp.delete()
            if (e is VmdImageException) {
                emit(
                    "verify_fail",
                    mapOf("src" to request.src, "fail" to e.reason.name, "err" to e.message),
                    startedAt,
                )
                emit(
                    "import_fail",
                    mapOf("src_uri" to request.src, "image" to request.imageId, "err" to e.message),
                    startedAt,
                )
                throw e
            }
            emit(
                "import_fail",
                mapOf("src_uri" to request.src, "image" to request.imageId, "err" to (e.message ?: e::class.simpleName)),
                startedAt,
            )
            throw when (e) {
                is IOException -> VmdImageException.of("io error while installing: ${e.message}", VmdImageReason.IO_ERROR, e)
                else -> e
            }
        }

        if (!IMAGE_ID_REGEX.matches(payload.imageId)) {
            tmp.delete()
            emit("import_fail", mapOf("src_uri" to request.src, "err" to "bad manifest image.id: ${payload.imageId}"), startedAt)
            throw VmdImageException.of(
                "manifest image.id '${payload.imageId}' does not match $IMAGE_ID_PATTERN",
                VmdImageReason.IMAGE_ID_INVALID,
            )
        }
        // catalog 与 manifest 的 id 必须一致（防"下载 A 装成 B"；targetId 只在已知时比对）
        if (request.imageId != null && request.imageId != payload.imageId) {
            tmp.delete()
            emit(
                "verify_fail",
                mapOf("src" to request.src, "fail" to "IMAGE_ID_INVALID", "err" to "manifest id ${payload.imageId} != expected ${request.imageId}"),
                startedAt,
            )
            emit("import_fail", mapOf("src_uri" to request.src, "image" to request.imageId, "err" to "manifest image.id mismatch"), startedAt)
            throw VmdImageException.of(
                "manifest image.id '${payload.imageId}' != expected image_id '${request.imageId}'",
                VmdImageReason.IMAGE_ID_INVALID,
            )
        }

        val dest = imageFile(payload.imageId)
        try {
            AtomicFiles.move(tmp, dest) // 原子 rename：目标要么旧文件、要么新文件
        } catch (e: IOException) {
            tmp.delete()
            emit("import_fail", mapOf("src_uri" to request.src, "err" to e.message), startedAt)
            throw VmdImageException.of("cannot finalise install: ${e.message}", VmdImageReason.IO_ERROR, e)
        }

        // §7.2 T-2：display 缺 system_version/arch 时从 manifest 回填（见 displayWithManifest）
        val display = displayWithManifest(dest, request.display)
        val meta = try {
            writeInstalledMeta(dest, payload, request, display)
        } catch (e: IOException) {
            // 镜像已就位；meta 写失败不应让导入"看起来"失败 —— 交给 BootGuard
            // 的状态机（§5.4：meta 缺失 → 全量重算），但如实记日志。
            emit("verify_fail", mapOf("src" to request.src, "fail" to "meta_write", "err" to e.message), startedAt)
            null
        }
        emit(
            "import_done",
            mapOf(
                "src_uri" to request.src,
                "bytes" to payload.size,
                "image" to payload.imageId,
                "format" to fmt.name,
                "zst_size" to request.zstSize,
            ),
            startedAt,
        )
        emit(
            "verify_ok",
            mapOf(
                "src" to request.src,
                "image" to payload.imageId,
                "sha256" to payload.sha256,
                "bytes" to payload.size,
            ),
            startedAt,
        )
        return InstalledImage(payload.imageId, dest, meta)
    }

    /**
     * 流式安装**兼容重载**（既有调用面：SAF 导入 `install(input, src = uri)`、
     * systemimage-selftest 的 v1 夹具）。等价于
     * `install(InstallSource.Stream { input }, InstallRequest(imageId=…, imageSha256=…))`。
     *
     * @param targetId 已知的 image_id；未知传 null（以 manifest 为准）
     * @param expectedSha256 整 `.img` 摘要（v1 = catalog sha256；v2 = `image_sha256`）
     */
    @JvmOverloads
    fun install(
        input: InputStream,
        targetId: String? = null,
        expectedSha256: String? = null,
        src: String = "import",
        onProgress: ((copiedBytes: Long) -> Unit)? = null,
    ): InstalledImage = install(
        InstallSource.Stream({ input }, src),
        InstallRequest(
            imageId = targetId,
            src = src,
            imageSha256 = expectedSha256,
            onProgress = onProgress,
        ),
    )

    /**
     * §6.4-B **重装系统**（恢复出厂第二级）：`cache/<id>.img.zst` 存在 → 复用 §6.1
     * 安装流覆盖重建为 pristine（源/整镜像摘要取自既有 `.meta.json` 复检）；
     * 不存在 → 抛 [ReinstallNeedsNetworkException]（明确"需要联网重新下载"）。
     */
    fun reinstallSystem(imageId: String): InstalledImage {
        val startedAt = nowMs()
        if (!IMAGE_ID_REGEX.matches(imageId)) {
            throw VmdImageException.of("image_id '$imageId' does not match $IMAGE_ID_PATTERN", VmdImageReason.IMAGE_ID_INVALID)
        }
        val cache = File(cacheDir, "$imageId$ZST_SUFFIX")
        if (!cache.isFile) {
            emit("reinstall_fail", mapOf("image" to imageId, "err" to "cache miss", "cache" to cache.path), startedAt)
            throw ReinstallNeedsNetworkException(imageId, cache)
        }
        val meta = readMeta(imageId)
        return install(
            InstallSource.FileSource(cache, "reinstall"),
            InstallRequest(
                imageId = imageId,
                src = "reinstall",
                zstSha256 = meta?.zstSha256,
                imageSha256 = meta?.imageSha256 ?: meta?.sha256,
                // 注意不传 imageSize：meta.size 是**基线**大小，§6.5 扩容后已大于原 .img；
                // 摘要（zst + 整镜像）已足够锚定内容。
                display = meta?.display ?: InstallDisplay(),
            ),
        )
    }

    // ---------------------------------------------------------------- 激活（§5.2/§5.3）

    /**
     * 激活前检查（**不写任何文件**，§5.4 状态机在此执行）：
     * 基线一致 → 结构校验 + [decideFor]；不一致 → 二分归因
     * （安装/基线刷新后有本镜像 boot 记录 → 归因 guest 写入 → 转 dirty、刷新基线、放行；
     *   无 → pristine 走全量重算、dirty 直接判 CORRUPT）。
     */
    fun checkActivation(imageId: String): ActivationCheck {
        if (!IMAGE_ID_REGEX.matches(imageId)) {
            return ActivationCheck.Rejected(VmdImageReason.IMAGE_ID_INVALID, "image_id '$imageId' fails $IMAGE_ID_PATTERN")
        }
        val file = imageFile(imageId)
        if (!file.isFile) {
            return ActivationCheck.Rejected(VmdImageReason.NO_SYSTEM_IMAGE, "image not installed: $imageId")
        }
        val meta = readMeta(imageId)
            ?: return ActivationCheck.Rejected(VmdImageReason.CORRUPT, ".meta.json missing or unparseable for $imageId")

        if (!quickVerify(file, meta)) {
            when {
                attributedToGuest(imageId, meta) ->
                // §5.4：归因为 guest 写入 → 转 dirty、刷新 size/mtime 基线、放行（记 info）
                    markDirty(imageId, file, meta)

                meta.state == STATE_DIRTY ->
                    return ActivationCheck.Rejected(
                        VmdImageReason.CORRUPT,
                        "dirty baseline (since ${meta.dirtySince ?: meta.baselineAt}) size/mtime changed with no boot " +
                            "record after it — external modification suspected (§5.4)",
                        BootGuardReason.CORRUPT,
                    )

                else -> {
                    // pristine + 不一致 + 无 boot 记录 → 先结构/诊断阶梯（v1 旧格式在此按
                    // LEGACY_FORMAT 拒，而不是混进全量重算），再全量重算（v2 若确被改写则摘要不符）
                    val structural = fmt.probe(file)
                    if (structural is FormatVerdict.Reject) {
                        return ActivationCheck.Rejected(structural.reason, structural.detail, structural.guard)
                    }
                    val full = verify(imageId, full = true)
                    if (!full.ok) {
                        return ActivationCheck.Rejected(
                            reasonFromBootGuard(full.reason) ?: VmdImageReason.CORRUPT,
                            full.detail,
                            bootGuardReasonOf(full.reason) ?: BootGuardReason.CORRUPT,
                        )
                    }
                }
            }
        }

        val verdict = snapshotVerdict(imageId, file, meta)
        val snap = when (verdict) {
            is FormatVerdict.Ok -> verdict.snapshot
            is FormatVerdict.Reject ->
                return ActivationCheck.Rejected(verdict.reason, verdict.detail, verdict.guard)
        }
        return ActivationCheck.Eligible(decideFor(snap))
    }

    /**
     * `activate(id)`（§5.1/§5.3）：先算 §5.2 `decideReset`，
     * **需要重置则返回 [ActivateResult.ResetRequired] 信号**（不写 active.json、
     * 不碰 storage.img），用户确认后由上层重建数据盘再以 [allowReset]=true 调一次。
     *
     * @param allowReset 用户已确认重置且已重建 `storage.img`（否则遇重置判据返回信号）
     */
    @JvmOverloads
    fun activate(imageId: String, allowReset: Boolean = false): ActivateResult {
        val startedAt = nowMs()
        val current = active()
        val check = checkActivation(imageId)
        val decision: ResetDecision = when (check) {
            is ActivationCheck.Rejected -> {
                emit(
                    "bootguard_reject",
                    mapOf("reason" to (check.guard?.name ?: check.reason.bootGuard), "detail" to check.detail, "image" to imageId),
                    startedAt,
                )
                throw VmdImageException.of(check.detail, check.reason)
            }
            is ActivationCheck.Eligible -> check.decision
        }
        emit(
            "activate",
            mapOf(
                "from" to current?.imageId,
                "to" to imageId,
                "decision" to decision.code,
                "reset" to decision.requiresReset(),
                "reset_confirmed" to allowReset,
            ),
            startedAt,
        )
        if (decision.requiresReset() && !allowReset) {
            return ActivateResult.ResetRequired(decision.code, imageId)
        }
        val info = snapshotFor(imageId, imageFile(imageId), readMeta(imageId))
        writeActive(
            ActiveRecord(
                imageId = imageId,
                identity = info.manifest.image.identity,
                rootfsSha256 = info.rootfsSha256,
                activatedAt = isoNow(),
                contentId = info.contentId,
            ),
        )
        return ActivateResult.Activated(decision.code, current?.imageId)
    }

    /**
     * §5.2 决策表（枚举声明序 = 表序，"按顺序取第一个匹配"）；
     * §5.4 的 v2 变化：**第一步比 `content_id`**（替代 v1 `rootfs_sha256`），
     * 任一侧缺 content_id（v1 镜像/旧 active 记录）时回退比 `rootfs_sha256`
     * （旧字段读取仅作该回退与日志）。
     *
     * `no_active`（行1）→ 同系统（行3）→ `identity`（行4）→ `active_unreadable`（行2）
     * → `contract`（行5）→ `init`（行6）→ `upgrade`（行7）。
     */
    private fun decideFor(newInfo: ImageSnapshot): ResetDecision {
        val current = active()
            // 行1 no_active：无 active.json —— 首次激活或记录损坏 → 不重置（记 no_active）。
            ?: return ResetDecision.NO_ACTIVE
        val sameSystem = if (newInfo.contentId != null && current.contentId != null) {
            newInfo.contentId == current.contentId                       // §5.4 content_id 判定
        } else {
            newInfo.rootfsSha256 != null && newInfo.rootfsSha256 == current.rootfsSha256 // v1 回退
        }
        if (sameSystem) return ResetDecision.SAME
        if (newInfo.manifest.image.identity != current.identity) return ResetDecision.IDENTITY

        // identity 相同 → 比硬判据 contract.version / distro.init（§5.2 规则 3）
        val activeInfo = active()?.takeIf { it.idValid }?.let { rec ->
            try {
                snapshotFor(rec.imageId, imageFile(rec.imageId), readMeta(rec.imageId))
            } catch (e: VmdImageException) {
                null
            }
        }
        if (activeInfo == null) {
            // 行2 active_unreadable：记录在但镜像不可读/缺失 → 无法证明 contract 未变 → 保守重置
            return ResetDecision.ACTIVE_UNREADABLE
        }
        if (newInfo.contractVersion != activeInfo.contractVersion) return ResetDecision.CONTRACT
        if (newInfo.distroInit != activeInfo.distroInit) return ResetDecision.INIT
        return ResetDecision.UPGRADE
    }

    // ---------------------------------------------------------------- §5.4 状态机

    /**
     * §5.4 归因判定：`.meta.json` 基线建立之后，是否存在**本镜像**的 boot 记录。
     * 两处证据（任一命中即归因 guest 写入）：
     *  1. `image.log` 里 `ev ∈ [BOOT_RECORD_EVENTS]` 且 `image == imageId` 的行（主证据，§5.4 字面口径）；
     *  2. `boots/<boot_id>.meta.json`（§16.2 轮转时序 ④ 步**同步原子写**，比 image.log
     *     的单写线程缓冲更及时）里 `image == imageId` 且 `started_at` 晚于基线。
     *
     * 严格 `>` 比较：markBooted 写基线与它自己的事件同毫秒，不能自证。
     */
    private fun attributedToGuest(imageId: String, meta: ImageMeta): Boolean {
        val anchor = parseIso(meta.baselineAt ?: meta.verifiedAt) ?: meta.mtime
        return hasBootLogRecord(imageId, anchor) || hasBootMetaRecord(imageId, anchor)
    }

    private fun hasBootLogRecord(imageId: String, sinceMs: Long): Boolean {
        val log = imageLogFile
        if (!log.isFile) return false
        return try {
            var found = false
            log.forEachLine(Charsets.UTF_8) { line ->
                if (found || line.isBlank()) return@forEachLine
                val m = try {
                    MiniJson.parseObject(line)
                } catch (e: IllegalArgumentException) {
                    return@forEachLine
                }
                val ev = MiniJson.str(m, "ev")
                if (ev == null || ev !in BOOT_RECORD_EVENTS || m["image"] != imageId) return@forEachLine
                val ts = parseIso(MiniJson.str(m, "ts"))
                if (ts != null && ts > sinceMs) found = true
            }
            found
        } catch (e: Exception) {
            false // 日志不可读不能变成拒绝理由；boots/ 仍可作证据
        }
    }

    private fun hasBootMetaRecord(imageId: String, sinceMs: Long): Boolean {
        val dir = bootsDir
        if (!dir.isDirectory) return false
        val files = dir.listFiles { d, n -> n.endsWith(BOOT_META_SUFFIX) } ?: return false
        for (f in files) {
            val m = try {
                BootMeta.read(f)
            } catch (e: Exception) {
                null
            } ?: continue
            if (m["image"] != imageId) continue
            val started = parseIso(MiniJson.str(m, "started_at")) ?: f.lastModified()
            if (started > sinceMs) return true
        }
        return false
    }

    /**
     * §5.4 pristine+不一致 → 归因 guest：转 `dirty`、刷新 size/mtime/baseline 基线。
     * `dirty_since` 首次转换时定格（后续刷新基线不动它）。
     */
    private fun markDirty(imageId: String, file: File, meta: ImageMeta) {
        val now = isoNow()
        val mtime = file.lastModified()
        patchMeta(imageId) { m ->
            m[STATE_FIELD] = STATE_DIRTY
            m[DIRTY_SINCE_FIELD] = m[DIRTY_SINCE_FIELD] ?: now
            m[SIZE_FIELD] = file.length()
            m[MTIME_FIELD] = mtime
            m[LAST_MTIME_FIELD] = mtime
            m[BASELINE_AT_FIELD] = now
        }
        emit(
            "dirty_attributed",
            mapOf(
                "image" to imageId,
                "from" to meta.state,
                "state" to STATE_DIRTY,
                "size" to file.length(),
                "mtime" to mtime,
                "reason" to "boot_record",
            ),
            nowMs(),
        )
    }

    /**
     * §5.4 **引擎 stop 时调用**（Repository 暴露为 `markBooted()`）：guest 已启动并写入
     * （v2 root 可写，"首启即 dirty"）→ 刷新 size/mtime 基线 + 记 `boot_done` 事件。
     * 崩溃场景（没走到这里）由启动前的 [attributedToGuest] 兜底。
     *
     * @param imageId 缺省取当前激活镜像
     * @return 是否刷新成功（无激活镜像/无 meta → false，不抛）
     */
    @JvmOverloads
    fun markBooted(imageId: String? = null): Boolean {
        val startedAt = nowMs()
        val id = imageId ?: active()?.takeIf { it.idValid }?.imageId ?: return false
        if (!IMAGE_ID_REGEX.matches(id)) return false
        val file = imageFile(id)
        if (!file.isFile) return false
        if (readMeta(id) == null) return false
        val mtime = file.lastModified()
        patchMeta(id) { m ->
            m[STATE_FIELD] = STATE_DIRTY
            m[DIRTY_SINCE_FIELD] = m[DIRTY_SINCE_FIELD] ?: isoNow()
            m[SIZE_FIELD] = file.length()
            m[MTIME_FIELD] = mtime
            m[LAST_MTIME_FIELD] = mtime
            m[BASELINE_AT_FIELD] = isoNow()
        }
        emit(
            "boot_done",
            mapOf("image" to id, "state" to STATE_DIRTY, "size" to file.length(), "mtime" to mtime),
            startedAt,
        )
        return true
    }

    // ---------------------------------------------------------------- 数据盘（§4.3 / §6.4-A）

    /**
     * §6.4-A **清数据**（恢复出厂第一级，推荐默认）/ §8.5「重置并切换」确认后：
     * **整文件清零**重建 `storage.img`。
     *
     * 实现为「截断到 0 → 按原尺寸稀疏重扩」：逻辑内容全零（稀疏洞读作 0）、
     * 文件尺寸不变；guest `init-podroid` 开机检测到全零即自动 `mkfs.ext4`。
     * 文件不存在 = 无事可做（BootGuard #11 非拒绝：引擎 `ensureStorageImage()`
     * 按需创建），返回 false 不抛。§16.5 记 `factory_reset`
     * （`old_size`/`new_size`/`method=whole-file-zero`）。
     */
    fun resetStorage(storageFile: File): Boolean {
        val startedAt = nowMs()
        if (!storageFile.isFile) return false
        val oldSize = storageFile.length()
        RandomAccessFile(storageFile, "rw").use { raf ->
            raf.setLength(0)
            raf.setLength(oldSize)
        }
        emit(
            "factory_reset",
            linkedMapOf(
                "old_size" to oldSize,
                "new_size" to storageFile.length(),
                "method" to "whole-file-zero",
            ),
            startedAt,
        )
        return true
    }

    // ---------------------------------------------------------------- 删除（§5.3）

    /**
     * 删除已安装镜像（`<id>.img` + `<id>.img.meta.json`）；若它是激活镜像则同时
     * 移除 `active.json`（回到 §5.1 `ABSENT`，引擎 fail-fast NO_SYSTEM_IMAGE）。
     * `cache/<id>.img.zst` **不删**（保留 §6.4-B 离线重装能力；缓存清理是设置页动作）。
     * @return 是否删掉了至少一个文件
     */
    @JvmOverloads
    fun delete(imageId: String, src: String = "manual"): Boolean {
        val startedAt = nowMs()
        if (!IMAGE_ID_REGEX.matches(imageId)) {
            throw VmdImageException.of("image_id '$imageId' fails $IMAGE_ID_PATTERN", VmdImageReason.IMAGE_ID_INVALID)
        }
        var removed = false
        val file = imageFile(imageId)
        if (file.exists()) removed = file.delete() || removed
        val meta = metaFile(imageId)
        if (meta.exists()) removed = meta.delete() || removed
        val wasActive = active()?.imageId == imageId
        if (wasActive && activeFile.exists()) {
            removed = activeFile.delete() || removed
        }
        emit("install_delete", mapOf("image" to imageId, "src" to src, "was_active" to wasActive), startedAt)
        return removed
    }

    // ---------------------------------------------------------------- 校验（§6.4）

    /**
     * §6.4 校验时机（DESIGN-disk-image-v2 §5.5）：
     * - [full]=false（启动前）：只比 `size`+`mtime`，不重算 sha256。
     * - [full]=true（手动"立即校验"）：
     *   - `state=dirty` → 仅结构/manifest 校验 + 明示"内容无法校验，回原厂请重装"（§5.4/§6.4）；
     *   - `pending_grow` → 仅结构校验 + 跳过整镜像 sha（§6.5 扩容后整文件摘要必然变化）；
     *   - `pristine` → 全量（v1 = payload 段摘要；v2 = 阶段 1–4 root 分区摘要）+
     *     整镜像 sha256 对 meta → 成功后刷新 pristine 基线。
     */
    @JvmOverloads
    fun verify(imageId: String, full: Boolean = false): VerifyResult {
        val startedAt = nowMs()
        if (!IMAGE_ID_REGEX.matches(imageId)) {
            return VerifyResult(false, full, VmdImageReason.IMAGE_ID_INVALID.bootGuard, "image_id '$imageId' invalid", null)
        }
        val file = imageFile(imageId)
        if (!file.isFile) {
            return VerifyResult(false, full, VmdImageReason.CORRUPT.bootGuard, "file missing: $imageId", null)
        }
        if (!full) {
            val meta = readMeta(imageId) ?: return VerifyResult(
                false,
                false,
                VmdImageReason.CORRUPT.bootGuard,
                ".meta.json missing or unparseable for $imageId",
                null,
            )
            return if (quickVerify(file, meta)) {
                VerifyResult(true, false, null, "size/mtime match", meta.sha256)
            } else {
                VerifyResult(false, false, VmdImageReason.CORRUPT.bootGuard, "size/mtime mismatch vs .meta.json baseline", meta.sha256)
            }
        }

        val meta = readMeta(imageId)
        if (meta != null && meta.state == STATE_DIRTY) {
            // §5.4：dirty 镜像无法做内容级校验 —— 只复检结构/manifest 并明示。
            return try {
                when (val verdict = snapshotVerdict(imageId, file, meta)) {
                    is FormatVerdict.Ok -> VerifyResult(
                        true,
                        true,
                        null,
                        "dirty: structure/manifest ok — content cannot be verified on a guest-written image; " +
                            "reinstall the system to restore factory content (§6.4-B)" +
                            if (meta.pendingGrow) "; pending_grow: partition growth awaiting guest self-expand (§6.5)" else "",
                        meta.sha256,
                    )
                    is FormatVerdict.Reject -> {
                        emit("verify_fail", mapOf("image" to imageId, "fail" to verdict.reason.name, "err" to verdict.detail), startedAt)
                        VerifyResult(false, true, verdict.guard.name, verdict.detail, null)
                    }
                }
            } catch (t: Throwable) {
                emit("verify_fail", mapOf("image" to imageId, "fail" to "io", "err" to t.message), startedAt)
                VerifyResult(false, true, VmdImageReason.IO_ERROR.bootGuard, t.message ?: "io error", null)
            }
        }

        return try {
            if (meta != null && meta.pendingGrow) {
                // §6.5：扩容后整文件摘要/几何与安装期记录不一致是预期态（guest 下次开机
                // 自扩容）→ 走 pending_grow 容忍的结构判定，内容校验推迟
                when (val verdict = snapshotVerdict(imageId, file, meta)) {
                    is FormatVerdict.Ok -> VerifyResult(
                        true,
                        true,
                        null,
                        "pending_grow: structure ok — content verify deferred until the guest expands the partition (§6.5)",
                        meta.sha256,
                    )
                    is FormatVerdict.Reject -> {
                        emit("verify_fail", mapOf("image" to imageId, "fail" to verdict.reason.name, "err" to verdict.detail), startedAt)
                        VerifyResult(false, true, verdict.guard.name, verdict.detail, null)
                    }
                }
            } else {
                fmt.verifyFull(file)
                val sha = sha256File(file)
                val expected = meta?.imageSha256 ?: meta?.sha256
                if (meta != null && expected != null && !expected.equals(sha, ignoreCase = true)) {
                    val detail = "sha256 mismatch: meta=$expected, actual=$sha"
                    emit("verify_fail", mapOf("image" to imageId, "fail" to "sha256", "err" to detail), startedAt)
                    VerifyResult(false, true, VmdImageReason.PAYLOAD_CORRUPT.bootGuard, detail, sha)
                } else {
                    writeMeta(file, sha)
                    emit("verify_ok", mapOf("image" to imageId, "sha256" to sha, "src" to "manual"), startedAt)
                    VerifyResult(true, true, null, "full verification ok", sha)
                }
            }
        } catch (e: VmdImageException) {
            emit("verify_fail", mapOf("image" to imageId, "fail" to e.reason.name, "err" to e.message), startedAt)
            VerifyResult(false, true, e.bootGuardReason, e.message ?: e.reason.name, null)
        } catch (e: IOException) {
            emit("verify_fail", mapOf("image" to imageId, "fail" to "io", "err" to e.message), startedAt)
            VerifyResult(false, true, VmdImageReason.IO_ERROR.bootGuard, e.message ?: "io error", null)
        }
    }

    // ---------------------------------------------------------------- 扩容（§6.5）

    /**
     * §6.5 **根分区扩容**（应用侧两步之一；**VM 停机时**调用，前置条件由调用方保证）：
     * `RandomAccessFile.setLength(newSizeBytes)` 稀疏追加 → 更新 `.meta.json` 的
     * size/mtime/baseline 基线 → 标记 `pending_grow`（guest 下次开机按块设备实际大小
     * 自扩容：`sgdisk -e` + `sfdisk -N 3` + `resize2fs`）。**应用侧不编辑分区表/镜像内
     * 任何记录** —— 代价是扩容后到 guest 自扩容前，v2 结构读取会因备份 GPT/meta.file_size
     * 领先而失败，由 [snapshotFor] 按 `pending_grow` 容忍（详见交付报告 TODO）。
     *
     * 反向缩容不支持（§6.5）。
     */
    fun growSystemImage(imageId: String, newSizeBytes: Long): GrowResult {
        val startedAt = nowMs()
        if (!IMAGE_ID_REGEX.matches(imageId)) {
            throw VmdImageException.of("image_id '$imageId' fails $IMAGE_ID_PATTERN", VmdImageReason.IMAGE_ID_INVALID)
        }
        val file = imageFile(imageId)
        if (!file.isFile) {
            throw VmdImageException.of("image not installed: $imageId", VmdImageReason.NO_SYSTEM_IMAGE)
        }
        if (readMeta(imageId) == null) {
            throw VmdImageException.of(
                ".meta.json missing for $imageId — verify/install the image before growing it",
                VmdImageReason.CORRUPT,
            )
        }
        val oldSize = file.length()
        if (newSizeBytes <= oldSize) {
            throw VmdImageException.of(
                "grow must increase size ($oldSize -> $newSizeBytes); shrink is unsupported (§6.5)",
                VmdImageReason.IO_ERROR,
            )
        }
        try {
            RandomAccessFile(file, "rw").use { it.setLength(newSizeBytes) } // 稀疏追加，秒级
        } catch (e: IOException) {
            throw VmdImageException.of("cannot grow ${file.name}: ${e.message}", VmdImageReason.IO_ERROR, e)
        }
        val mtime = file.lastModified()
        patchMeta(imageId) { m ->
            m[SIZE_FIELD] = newSizeBytes
            m[MTIME_FIELD] = mtime
            m[LAST_MTIME_FIELD] = mtime
            m[BASELINE_AT_FIELD] = isoNow()
            m[PENDING_GROW_FIELD] = true
        }
        emit(
            "system_grow",
            mapOf(
                "image" to imageId,
                "old_size" to oldSize,
                "new_size" to newSizeBytes,
                "pending_grow" to true,
            ),
            startedAt,
        )
        return GrowResult(imageId, oldSize, newSizeBytes)
    }

    // ---------------------------------------------------------------- 工具

    private fun emit(event: String, fields: Map<String, Any?>, startedAt: Long) {
        val withDur = LinkedHashMap(fields)
        withDur["dur_ms"] = (nowMs() - startedAt).coerceAtLeast(0L)
        events?.record(event, withDur)
    }

    private fun isoNow(): String = isoOf(nowMs())

    private fun metaFileOf(imageFile: File): File =
        File(imageFile.parentFile ?: imagesDir, imageFile.name + META_SUFFIX)

    /** 读原始 `.meta.json` map（缺失/半截 → null；合并保留字段用）。 */
    private fun readRawMeta(imageId: String): Map<String, Any?>? {
        if (!IMAGE_ID_REGEX.matches(imageId)) return null
        val f = metaFile(imageId)
        if (!f.isFile) return null
        return try {
            MiniJson.parseObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun metaJson(meta: ImageMeta): String = MiniJson.write(
        linkedMapOf<String, Any?>(
            SHA256_FIELD to meta.sha256,
            SIZE_FIELD to meta.size,
            MTIME_FIELD to meta.mtime,
            VERIFIED_AT_FIELD to meta.verifiedAt,
            FORMAT_VERSION_FIELD to meta.formatVersion,
            STATE_FIELD to meta.state,
            BASELINE_AT_FIELD to meta.baselineAt,
            DIRTY_SINCE_FIELD to meta.dirtySince,
            LAST_MTIME_FIELD to meta.lastMtime,
            PENDING_GROW_FIELD to meta.pendingGrow,
            IMAGE_SHA256_FIELD to meta.imageSha256,
            CONTENT_ID_FIELD to meta.contentId,
            ZST_SHA256_FIELD to meta.zstSha256,
            IDENTITY_FIELD to meta.identity,
            CONTRACT_VERSION_FIELD to meta.contractVersion,
            DISTRO_INIT_FIELD to meta.distroInit,
            DISPLAY_FIELD to meta.display?.toMap(),
        ),
    )

    companion object {
        const val IMAGES_DIR = "images"
        const val CACHE_DIR = "cache" // §6.1 下载缓存 / §6.4-B 离线重装取处
        const val ACTIVE_FILE = "active.json"
        const val META_SUFFIX = ".meta.json"
        const val IMG_SUFFIX = ".img"
        const val PART_SUFFIX = ".part"
        const val ZST_SUFFIX = ".img.zst" // `<id>.img.zst`
        const val IMAGE_LOG_FILE = "image.log"
        const val BOOTS_DIR = "boots"
        const val BOOT_META_SUFFIX = ".meta.json"

        const val IMAGE_ID_FIELD = "image_id"
        const val IDENTITY_FIELD = "identity"
        const val ROOTFS_SHA256_FIELD = "rootfs_sha256"
        const val ACTIVATED_AT_FIELD = "activated_at"
        const val PATH_FIELD = "path" // 早期写者 / §7.1 草图的可选容忍字段
        const val CONTENT_ID_FIELD = "content_id" // §5.4 v2 判定键（active.json + .meta.json）

        const val SHA256_FIELD = "sha256"
        const val SIZE_FIELD = "size"
        const val MTIME_FIELD = "mtime"
        const val VERIFIED_AT_FIELD = "verified_at"
        const val FORMAT_VERSION_FIELD = "format_version"
        const val STATE_FIELD = "state" // §5.4 pristine | dirty
        const val DIRTY_SINCE_FIELD = "dirty_since"
        const val LAST_MTIME_FIELD = "last_mtime"
        const val BASELINE_AT_FIELD = "baseline_at"
        const val PENDING_GROW_FIELD = "pending_grow" // §6.5
        const val IMAGE_SHA256_FIELD = "image_sha256"
        const val ZST_SHA256_FIELD = "zst_sha256"
        const val CONTRACT_VERSION_FIELD = "contract_version"
        const val DISTRO_INIT_FIELD = "distro_init"
        const val DISPLAY_FIELD = "display"

        const val STATE_PRISTINE = "pristine"
        const val STATE_DIRTY = "dirty"

        /**
         * §5.4 归因用的 `image.log` boot 事件（`boot_done` 由 [markBooted] 在引擎 stop
         * 时写；`boot_start` 预留给引擎/服务侧开始打点）。
         */
        val BOOT_RECORD_EVENTS: Set<String> = setOf("boot_done", "boot_start")

        /** DESIGN §7.4 #7 / IMAGE-FORMAT §5：防 `-drive` 选项注入与路径穿越。 */
        const val IMAGE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$"
        val IMAGE_ID_REGEX: Regex = Regex(IMAGE_ID_PATTERN)

        private const val COPY_BUFFER = 256 * 1024

        /** ISO-8601 时间戳（带毫秒与本地时区偏移，例 `2026-10-08T14:22:31.412+08:00`）。 */
        @JvmStatic
        fun isoOf(epochMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
            fmt.timeZone = TimeZone.getDefault()
            return fmt.format(Date(epochMs))
        }

        /** [isoOf] 的逆（解析失败 → null，调用方按"无时间证据"处理）。 */
        @JvmStatic
        fun parseIso(text: String?): Long? {
            if (text.isNullOrEmpty()) return null
            return try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).parse(text)?.time
            } catch (e: Exception) {
                null
            }
        }

        /** 整文件 sha256（8 MiB 流式缓冲；`VmdImageCodec.sha256File` 的格式无关等价物）。 */
        @JvmStatic
        fun sha256File(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(8 * 1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        /** 字节摘要 → 小写 hex。 */
        @JvmStatic
        fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString()
        }

        /** codec 细分 reason → §7.4 bootGuard 枚举字符串（映射表见 [VmdImageReason]）。 */
        @JvmStatic
        fun reasonFromBootGuard(bootGuard: String?): VmdImageReason? = when (bootGuard) {
            "NOT_AN_IMAGE" -> VmdImageReason.NOT_AN_IMAGE
            "CORRUPT" -> VmdImageReason.CORRUPT
            "FORMAT_UNSUPPORTED" -> VmdImageReason.FORMAT_UNSUPPORTED
            "ARCH_MISMATCH" -> VmdImageReason.ARCH_MISMATCH
            "IMAGE_ID_INVALID" -> VmdImageReason.IMAGE_ID_INVALID
            "SSH_CAPABILITY_MISSING" -> VmdImageReason.SSH_CAPABILITY_MISSING
            "SSH_PORT_INVALID" -> VmdImageReason.SSH_PORT_INVALID
            "APP_TOO_OLD" -> VmdImageReason.APP_TOO_OLD
            "NO_SYSTEM_IMAGE" -> VmdImageReason.NO_SYSTEM_IMAGE
            "RESET_REQUIRED" -> VmdImageReason.RESET_REQUIRED
            else -> null
        }
    }
}

/**
 * §6.4-B 重装缓存缺失：`cache/<id>.img.zst` 不在（用户清了缓存 / 从未下载）——
 * **需要联网重新下载**。[errCode] 是给 UI/调用方的稳定错误码。
 */
class ReinstallNeedsNetworkException(
    val imageId: String,
    val expectedCache: File,
) : IOException(
    "no cached system image for '$imageId' at ${expectedCache.absolutePath} — " +
        "reconnect and download it from the catalog before reinstalling (§6.4-B)",
) {
    val errCode: String = ERR_CODE

    companion object {
        const val ERR_CODE = "needs_network"
    }
}

/**
 * v1 格式实现（`VmdImageCodec`）—— **兼容/诊断路径**：
 * `systemimage-selftest`（README §2 的 kotlinc 命令不编译 v2 codec）与旧镜像走它。
 * 生产由 SystemImageRepository 注入 `V2SystemImageFormat` 取代。
 */
private class LegacyV1Format(private val versionCode: () -> Long) : SystemImageFormat {
    override val name: String = "v1"
    override val formatVersion: Long = VmdImageCodec.MAX_FORMAT_VERSION.toLong()

    override fun probe(file: File): FormatVerdict = try {
        FormatVerdict.Ok(legacySnapshot(VmdImageCodec.read(file, versionCode())))
    } catch (e: VmdImageException) {
        FormatVerdict.Reject(
            e.reason,
            bootGuardReasonOf(e.bootGuardReason) ?: BootGuardReason.CORRUPT,
            e.message ?: e.reason.name,
        )
    }

    override fun verifyFull(file: File) {
        val info = VmdImageCodec.read(file, versionCode())
        VmdImageCodec.verifyPayloads(info)
    }

    override fun materialise(source: InstallSource, tmp: File, request: InstallRequest): InstallPayload {
        var total = 0L
        val md = MessageDigest.getInstance("SHA-256")
        val input = when (source) {
            is InstallSource.Stream -> source.open()
            is InstallSource.FileSource -> FileInputStream(source.file)
        }
        FileOutputStream(tmp).use { out ->
            input.use { ins ->
                val buf = ByteArray(LEGACY_COPY_BUFFER)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    total += n
                    request.onProgress?.invoke(total)
                }
            }
            out.fd.sync()
        }
        val sha = SystemImageStore.hex(md.digest())
        if (request.imageSha256 != null && !request.imageSha256.equals(sha, ignoreCase = true)) {
            throw VmdImageException.of(
                "sha256 mismatch: expected ${request.imageSha256}, got $sha",
                VmdImageReason.PAYLOAD_CORRUPT,
            )
        }
        val info = VmdImageCodec.read(tmp, versionCode())
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
            sha256 = sha,
            size = total,
            contentId = null,
            identity = info.manifest.image.identity,
            contractVersion = info.manifest.contract.version,
            distroInit = info.manifest.image.distroInit,
        )
    }

    private fun legacySnapshot(info: VmdImageCodec.ImageInfo): ImageSnapshot = ImageSnapshot(
        path = info.path,
        size = File(info.path).length(),
        formatVersion = info.footer.formatVersion.toLong(),
        contentId = null, // v1 无 content_id（§5.4：判定回退 rootfs_sha256）
        rootfsSha256 = info.footer.rootfsSha256,
        contractVersion = info.manifest.contract.version,
        distroInit = info.manifest.image.distroInit,
        manifest = ImageSnapshot.ManifestView(
            ImageSnapshot.ImageView(
                id = info.manifest.image.id,
                displayName = info.manifest.image.displayName,
                variant = info.manifest.image.variant,
                identity = info.manifest.image.identity,
                version = info.manifest.image.version,
                systemVersion = info.manifest.image.systemVersion,
                arch = info.manifest.image.arch,
            ),
        ),
    )

    private companion object {
        const val LEGACY_COPY_BUFFER = 256 * 1024
    }
}
