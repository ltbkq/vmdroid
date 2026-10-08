/*
 * VMDroid - system image store (M3, pure JVM core of SystemImageRepository).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN.md §2.3（文件布局 / active.json 字段口径）、§5.1 状态机、
 *     §5.2 identity 重置判定、§5.3 激活与回滚、§6.3 手动导入、§6.4 校验记录
 *   - docs/IMAGE-FORMAT.md §5（读取算法与校验时机表）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 独立编译，
 * 由 systemimage-selftest/SelfTest.kt（无 JUnit）覆盖。Android 侧的 Context/通知等薄壳
 * 见 SystemImageRepository.kt（本类经 filesDir 注入即可工作）。
 *
 * 唯一写者（DESIGN §5.3, R2: A-R2-6/D-R2-7）：`active.json` 只由本类的
 * [writeActive]（经 [activate]）写入。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
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

/** DESIGN §5.2：哪些 `decision` 表示"必须重置数据盘"（`no_active`/`same`/`upgrade` 否）。 */
fun VmdImageCodec.ResetDecision.requiresReset(): Boolean =
    this == VmdImageCodec.ResetDecision.IDENTITY ||
        this == VmdImageCodec.ResetDecision.CONTRACT ||
        this == VmdImageCodec.ResetDecision.INIT ||
        this == VmdImageCodec.ResetDecision.ACTIVE_UNREADABLE

/**
 * `filesDir/images/` 的全部读写逻辑（安装 / 激活 / 删除 / 校验）。
 *
 * @param filesDir 应用 `filesDir`（§2.3：`images/`、`storage.img` 都在其下）
 * @param versionCode 当前 versionCode（§7.4 #5 APP_TOO_OLD 比较用）
 * @param nowMs 时钟（自测可控）
 * @param events 生命周期事件出口（`image.log`），null = 不记日志
 */
class SystemImageStore @JvmOverloads constructor(
    private val filesDir: File,
    private val versionCode: () -> Long = { 1L },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val events: ImageEventSink? = null,
) {
    // ---------------------------------------------------------------- 类型

    /**
     * `active.json` 记录（§2.3 冻结字段 `{image_id, identity, rootfs_sha256,
     * activated_at}`；[path] 为可选容忍字段，见类注释与交付报告"待裁决"）。
     * [idValid] = `image_id` 是否匹配 §7.4 #7 正则；非法 id 不参与路径计算。
     */
    data class ActiveRecord(
        val imageId: String,
        val identity: String?,
        val rootfsSha256: String?,
        val activatedAt: String?,
        val path: String? = null,
        val idValid: Boolean = IMAGE_ID_REGEX.matches(imageId),
    )

    /** `*.meta.json`（§6.4：`{sha256, size, mtime, verified_at, format_version}`）。 */
    data class ImageMeta(
        val sha256: String,
        val size: Long,
        val mtime: Long,
        val verifiedAt: String?,
        val formatVersion: Long?,
    )

    /** 已安装镜像（§5.1 `INSTALLED`）。 */
    data class InstalledImage(
        val imageId: String,
        val file: File,
        val meta: ImageMeta?,
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

    /** 激活前检查（不写任何文件）。 */
    sealed class ActivationCheck {
        data class Eligible(val decision: VmdImageCodec.ResetDecision) : ActivationCheck()
        data class Rejected(val reason: VmdImageReason, val detail: String) : ActivationCheck()
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

    private fun imageFile(id: String) = File(imagesDir, "$id.img")
    private fun metaFile(id: String) = File(imagesDir, "$id.img$META_SUFFIX")
    private val activeFile get() = File(imagesDir, ACTIVE_FILE)

    /** 给 BootGuard/引擎用的 versionCode 视图。 */
    fun currentVersionCode(): Long = versionCode()

    // ---------------------------------------------------------------- 读取

    /**
     * 解析 `active.json`（§2.3）。任何失败（无文件 / 非法 JSON / 缺 `image_id`）
     * 返回 null = "无激活记录"。**注意**：id 不合法时仍返回记录（`idValid=false`），
     * 由 [BootGuard] 判 `IMAGE_ID_INVALID`（§7.4 #7）、由 [activeImageFile] 判 null。
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
        return ImageMeta(sha, size, mtime, MiniJson.str(json, VERIFIED_AT_FIELD), MiniJson.long(json, FORMAT_VERSION_FIELD))
    }

    /**
     * §6.4 启动前快速判定：`size` + `mtime` 与 meta 一致即信任（**不**重算 sha256）。
     * meta 为 null 时恒 false（调用方据此走全量重算 / 判 CORRUPT）。
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
     * [quickVerify]（size+mtime，**不**重算 sha256）——「`.meta.json` 校验判定
     * 完成」的字面含义。单项异常吞掉记 false（扫描只为「判定完成」这一时序服务；
     * 启动期主判定仍由 BootGuard §7.4 兜底）。**不写 image.log**（§16.5 事件表
     * 未定义该事件，勿自造）。
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
     * 全量读取镜像（IMAGE-FORMAT §5：footer + manifest，不重算 payload sha256）。
     * @throws VmdImageException reason 已按 §7.4 映射（见 [VmdImageReason]）。
     */
    fun readImage(imageId: String): VmdImageCodec.ImageInfo =
        VmdImageCodec.read(imageFile(imageId), versionCode())

    /**
     * 写 `*.meta.json`（原子写）。[sha256] 为整镜像 sha256；未知时传 null 会先算一遍
     * （手动校验 / 启动前全量重算路径）。
     */
    fun writeMeta(imageFile: File, sha256: String? = null): ImageMeta {
        val sha = sha256 ?: VmdImageCodec.sha256File(imageFile.absolutePath)
        val meta = ImageMeta(
            sha256 = sha,
            size = imageFile.length(),
            mtime = imageFile.lastModified(),
            verifiedAt = isoNow(),
            formatVersion = VmdImageCodec.MAX_FORMAT_VERSION.toLong(),
        )
        AtomicFiles.write(
            File(imagesDir, imageFile.name + META_SUFFIX),
            MiniJson.write(
                linkedMapOf(
                    SHA256_FIELD to meta.sha256,
                    SIZE_FIELD to meta.size,
                    MTIME_FIELD to meta.mtime,
                    VERIFIED_AT_FIELD to meta.verifiedAt,
                    FORMAT_VERSION_FIELD to meta.formatVersion,
                ),
            ),
        )
        return meta
    }

    /**
     * 写 `active.json`（原子写；**唯一写者入口**，§5.3）。
     * 字段口径 = DESIGN §2.3 冻结的四个字段（不写 `path`；读取时容忍）。
     */
    fun writeActive(record: ActiveRecord) {
        AtomicFiles.write(
            activeFile,
            MiniJson.write(
                linkedMapOf(
                    IMAGE_ID_FIELD to record.imageId,
                    IDENTITY_FIELD to record.identity,
                    ROOTFS_SHA256_FIELD to record.rootfsSha256,
                    ACTIVATED_AT_FIELD to (record.activatedAt ?: isoNow()),
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- 安装（§6.3）

    /**
     * 流式导入：`input` → `images/<...>.img.part` → 边拷边算 sha256 →
     * `VmdImageCodec.read`（footer/manifest 校验）→ **原子 rename** 为
     * `images/<image.id>.img` → 写 `*.meta.json`。任一步失败删除 `.part` 并抛出。
     *
     * 中间 `.part` 名：`[targetId]` 已知时用 `<targetId>.img.part`（下载侧 §6.2 同名），
     * 否则用 `.import-<ts>.img.part` —— `image.id` 要读完 footer 才知道，
     * 最终文件名恒为 `<image.id>.img`（§2.3 安装后路径）。
     *
     * @param targetId 已知的 image_id（catalog 下载），未知传 null（SAF 手动导入）
     * @param expectedSha256 catalog sha256；不匹配 → 删 `.part` + `verify_fail`
     * @param src 事件里的 `src` 字段（`download` / `import` / …，§16.5）
     * @throws VmdImageException 校验失败（reason 见 [VmdImageReason]）
     */
    @JvmOverloads
    fun install(
        input: InputStream,
        targetId: String? = null,
        expectedSha256: String? = null,
        src: String = "import",
        onProgress: ((copiedBytes: Long) -> Unit)? = null,
    ): InstalledImage {
        val startedAt = nowMs()
        val dir = imagesDir
        val tmpName = if (targetId != null && IMAGE_ID_REGEX.matches(targetId)) {
            "$targetId$IMG_SUFFIX$PART_SUFFIX"
        } else {
            ".import-${startedAt}$PART_SUFFIX"
        }
        val tmp = File(dir, tmpName)
        var total = 0L
        var digestHex: String? = null
        try {
            FileOutputStream(tmp).use { out ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(COPY_BUFFER)
                input.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        total += n
                        onProgress?.invoke(total)
                    }
                }
                out.fd.sync()
                digestHex = VmdImageCodec.hex(md.digest())
            }
        } catch (e: Exception) {
            tmp.delete()
            emit(
                "import_fail",
                mapOf("src_uri" to src, "bytes" to total, "err" to (e.message ?: e::class.simpleName)),
                startedAt,
            )
            throw when (e) {
                is VmdImageException -> e
                is IOException -> VmdImageException.of("io error while importing: ${e.message}", VmdImageReason.IO_ERROR, e)
                else -> e
            }
        }

        val sha = digestHex ?: run {
            tmp.delete()
            throw VmdImageException.of("import stream produced no digest", VmdImageReason.IO_ERROR)
        }

        if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
            tmp.delete()
            emit("verify_fail", mapOf("src" to src, "fail" to "sha256", "err" to "expected $expectedSha256, got $sha"), startedAt)
            throw VmdImageException.of(
                "sha256 mismatch: expected $expectedSha256, got $sha",
                VmdImageReason.PAYLOAD_CORRUPT,
            )
        }

        val info = try {
            VmdImageCodec.read(tmp, versionCode())
        } catch (e: VmdImageException) {
            tmp.delete()
            emit("verify_fail", mapOf("src" to src, "fail" to e.reason.name, "err" to e.message), startedAt)
            emit("import_fail", mapOf("src_uri" to src, "bytes" to total, "err" to e.message), startedAt)
            throw e
        }

        val id = info.manifest.image.id
            ?: run {
                tmp.delete()
                throw VmdImageException.of("manifest.image.id missing", VmdImageReason.IMAGE_ID_INVALID)
            }
        val dest = imageFile(id)
        try {
            AtomicFiles.move(tmp, dest) // 原子 rename：目标要么旧文件、要么新文件
        } catch (e: IOException) {
            tmp.delete()
            emit("import_fail", mapOf("src_uri" to src, "bytes" to total, "err" to e.message), startedAt)
            throw VmdImageException.of("cannot finalise import: ${e.message}", VmdImageReason.IO_ERROR, e)
        }

        val meta = try {
            writeMeta(dest, sha)
        } catch (e: IOException) {
            // 镜像已就位；meta 写失败不应让导入"看起来"失败 —— 交给 BootGuard
            // 的全量重算路径（§6.4：meta 缺失 → 重算），但如实记日志。
            emit("verify_fail", mapOf("src" to src, "fail" to "meta_write", "err" to e.message), startedAt)
            null
        }
        emit(
            "import_done",
            mapOf("src_uri" to src, "bytes" to total, "image" to id),
            startedAt,
        )
        emit(
            "verify_ok",
            mapOf("src" to src, "image" to id, "sha256" to sha, "bytes" to total),
            startedAt,
        )
        return InstalledImage(id, dest, meta)
    }

    // ---------------------------------------------------------------- 激活（§5.2/§5.3）

    /**
     * 激活前检查（**不写任何文件**）：文件存在 → meta/快速或全量校验 →
     * `VmdImageCodec.read` → §5.2 `decideReset`。
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
        if (meta == null) {
            return ActivationCheck.Rejected(VmdImageReason.CORRUPT, ".meta.json missing or unparseable for $imageId")
        }
        if (!quickVerify(file, meta)) {
            // §6.4：size/mtime 不一致 → 全量重算；失败 = CORRUPT（§7.4 #2）
            val full = verify(imageId, full = true)
            if (!full.ok) {
                return ActivationCheck.Rejected(
                    reasonFromBootGuard(full.reason) ?: VmdImageReason.CORRUPT,
                    full.detail,
                )
            }
        }
        val info = try {
            VmdImageCodec.read(file, versionCode())
        } catch (e: VmdImageException) {
            return ActivationCheck.Rejected(e.reason, e.message ?: e.reason.name)
        }
        return ActivationCheck.Eligible(decideFor(info))
    }

    /**
     * `activate(id)`（§5.1/§5.3）：先算 `VmdImageCodec.decideReset`，
     * **需要重置则返回 [ActivateResult.ResetRequired] 信号**（不写 active.json、
     * 不碰 storage.img），用户确认后由上层重建数据盘再以 [allowReset]=true 调一次
     * （§8.5 对话框 → `factory_reset` storage.img → 重新激活）。
     *
     * @param allowReset 用户已确认重置且已重建 `storage.img`（否则遇重置判据返回信号）
     */
    @JvmOverloads
    fun activate(imageId: String, allowReset: Boolean = false): ActivateResult {
        val startedAt = nowMs()
        val current = active()
        val check = checkActivation(imageId)
        val decision: VmdImageCodec.ResetDecision = when (check) {
            is ActivationCheck.Rejected -> {
                emit(
                    "bootguard_reject",
                    mapOf("reason" to check.reason.bootGuard, "detail" to check.detail, "image" to imageId),
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
        val info = readImage(imageId)
        writeActive(
            ActiveRecord(
                imageId = imageId,
                identity = info.manifest.image.identity,
                rootfsSha256 = info.footer.rootfsSha256,
                activatedAt = isoNow(),
            ),
        )
        return ActivateResult.Activated(decision.code, current?.imageId)
    }

    /**
     * §5.2 决策表（枚举声明序 = 表序，"按顺序取第一个匹配"）：
     * `no_active`（行1）→ `same`（行3）→ `identity`（行4）→ `active_unreadable`（行2）
     * → `contract`（行5）→ `init`（行6）→ `upgrade`（行7）。
     *
     * 行3/行4 只依赖 `active.json` 的记录字段，即使激活镜像不可读也可能在此之前就返回；
     * 只有当比较 `contract`/`init` 必须读激活镜像的 footer/manifest 而读不到时，
     * 才落进行2 `active_unreadable`（**保守要求重置**，DESIGN §5.2 行2 的 RESET_REQUIRED=是）。
     */
    private fun decideFor(newInfo: VmdImageCodec.ImageInfo): VmdImageCodec.ResetDecision {
        val newKey = newInfo.resetKey()
        val current = active()
            // 行1 no_active：无 active.json —— 首次激活或记录损坏（§5.2 已明确覆盖该分支）
            // → 不重置（与 upgrade 同为 RESET_REQUIRED=否，但 image.log 记 no_active）。
            ?: return VmdImageCodec.ResetDecision.NO_ACTIVE
        if (newKey.rootfsSha256 == current.rootfsSha256) return VmdImageCodec.ResetDecision.SAME
        if (newKey.identity != current.identity) return VmdImageCodec.ResetDecision.IDENTITY
        // identity 相同 → 比硬判据 contract.version / distro.init（§5.2 规则 3）
        val activeInfo = active()?.takeIf { it.idValid }?.let { rec ->
            try {
                VmdImageCodec.read(imageFile(rec.imageId), versionCode())
            } catch (e: VmdImageException) {
                null
            }
        }
        if (activeInfo == null) {
            // 行2 active_unreadable：记录在但镜像不可读/缺失 → 无法证明 contract 未变 → 保守重置
            return VmdImageCodec.ResetDecision.ACTIVE_UNREADABLE
        }
        val activeKey = activeInfo.resetKey()
        if (newKey.contractVersion != activeKey.contractVersion) return VmdImageCodec.ResetDecision.CONTRACT
        if (newKey.distroInit != activeKey.distroInit) return VmdImageCodec.ResetDecision.INIT
        return VmdImageCodec.ResetDecision.UPGRADE
    }

    // ---------------------------------------------------------------- 数据盘（§4.3）

    /**
     * §4.3 **整文件清零**重建 `storage.img`：§8.5「重置并切换」确认后、再次
     * `activate(allowReset=true)` 之前由上层调用（M5 恢复出厂复用同一路径）。
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
     * §6.4 校验时机：
     * - [full]=false（启动前）：只比 `size`+`mtime`，不重算 sha256。
     * - [full]=true（手动"立即校验"）：全量重算 —— 整镜像 sha256（对 meta/catalog）+
     *   逐段 payload sha256（IMAGE-FORMAT §5）+ footer/manifest 语义复检，成功后刷新 meta。
     */
    @JvmOverloads
    fun verify(imageId: String, full: Boolean = false): VerifyResult {
        val startedAt = nowMs()
        if (!IMAGE_ID_REGEX.matches(imageId)) {
            return VerifyResult(false, full, VmdImageReason.IMAGE_ID_INVALID.bootGuard, "image_id '$imageId' invalid", null)
        }
        val file = imageFile(imageId)
        if (!file.isFile) {
            return VerifyResult(false, full, VmdImageReason.CORRUPT.bootGuard, "image file missing: $imageId", null)
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
                VerifyResult(false, false, VmdImageReason.CORRUPT.bootGuard, "size/mtime mismatch vs .meta.json", meta.sha256)
            }
        }
        return try {
            val info = VmdImageCodec.read(file, versionCode())
            VmdImageCodec.verifyPayloads(info)
            val sha = VmdImageCodec.sha256File(file.absolutePath)
            val meta = readMeta(imageId)
            if (meta != null && !meta.sha256.equals(sha, ignoreCase = true)) {
                val detail = "sha256 mismatch: meta=${meta.sha256}, actual=$sha"
                emit("verify_fail", mapOf("image" to imageId, "fail" to "sha256", "err" to detail), startedAt)
                VerifyResult(false, true, VmdImageReason.PAYLOAD_CORRUPT.bootGuard, detail, sha)
            } else {
                writeMeta(file, sha)
                emit("verify_ok", mapOf("image" to imageId, "sha256" to sha, "src" to "manual"), startedAt)
                VerifyResult(true, true, null, "full verification ok", sha)
            }
        } catch (e: VmdImageException) {
            emit("verify_fail", mapOf("image" to imageId, "fail" to e.reason.name, "err" to e.message), startedAt)
            VerifyResult(false, true, e.bootGuardReason, e.message ?: e.reason.name, null)
        } catch (e: IOException) {
            emit("verify_fail", mapOf("image" to imageId, "fail" to "io", "err" to e.message), startedAt)
            VerifyResult(false, true, VmdImageReason.IO_ERROR.bootGuard, e.message ?: "io error", null)
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun emit(event: String, fields: Map<String, Any?>, startedAt: Long) {
        val withDur = LinkedHashMap(fields)
        withDur["dur_ms"] = (nowMs() - startedAt).coerceAtLeast(0L)
        events?.record(event, withDur)
    }

    private fun isoNow(): String = isoOf(nowMs())

    companion object {
        const val IMAGES_DIR = "images"
        const val ACTIVE_FILE = "active.json"
        const val META_SUFFIX = ".meta.json"
        const val IMG_SUFFIX = ".img"
        const val PART_SUFFIX = ".part"

        const val IMAGE_ID_FIELD = "image_id"
        const val IDENTITY_FIELD = "identity"
        const val ROOTFS_SHA256_FIELD = "rootfs_sha256"
        const val ACTIVATED_AT_FIELD = "activated_at"
        const val PATH_FIELD = "path" // 早期写者 / §7.1 草图的可选容忍字段

        const val SHA256_FIELD = "sha256"
        const val SIZE_FIELD = "size"
        const val MTIME_FIELD = "mtime"
        const val VERIFIED_AT_FIELD = "verified_at"
        const val FORMAT_VERSION_FIELD = "format_version"

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
