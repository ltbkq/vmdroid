/*
 * VMDroid - image catalog (DESIGN §6.1) + detached signature (ECDSA P-256).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN-disk-image-v2.md §6.1/§9（catalog schema v2：`schema:2` + `assets[]`，
 *     `size`/`sha256` = `.img.zst`（下载预检/校验），`image_size`/`image_sha256` = `.img`
 *     （落盘预检/安装校验）；应用对 `schema < 2` 拒绝并提示；签名机制沿用 v1）
 *   - docs/IMAGE-FORMAT-v2.md §7.1/§7.2（分发容器 `.img.zst` + catalog schema bump）
 *   - docs/DESIGN.md §6.1（目录 schema、image_id 白名单正则 R1: B-R1-16、
 *     签名决策 2026-10-08：ECDSA P-256 + SHA-256、签名对象 = catalog.json 原始字节、
 *     base64(DER) 非 MIME、校验失败拒绝加载且不降级裸 HTTPS、R3: B-R3-21）
 *   - 生产侧字段真值：distro-build/mkcatalog.sh（SCHEMA=2 分支）/ pack-v2.sh 末行 JSON
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— HTTP 获取、订阅 URL 设置与
 * 公钥资源读取在 Android 壳（ImageCatalogRepository）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * §7.2 `assets[]` 条目（catalog 扁平字段口径；`app_min_version_code` 与 manifest 的
 * `app.min_version_code` 同义）。
 *
 * schema v2 双摘要语义（IMAGE-FORMAT-v2 §7.1 校验行）：
 *  - [size] / [sha256]     —— `.img.zst` 分发容器：下载预检（+margin）与下载完成校验；
 *  - [imageSize] / [imageSha256] —— 解压后的 `.img`：落盘预检（st_blocks 语义估算）与
 *    安装期全量校验（Store 解压后做，下载器不再读镜像结构）。
 */
data class CatalogEntry(
    val imageId: String,
    val displayName: String,
    val identity: String?,
    val variant: String?,
    val version: String?,
    val systemVersion: Long?,
    val arch: String?,
    val channel: String?,
    val url: String,
    val size: Long,
    val sha256: String,
    val imageSize: Long,
    val imageSha256: String,
    val appMinVersionCode: Long,
    val notes: String?,
) {
    /** 展示字段快照（下载完成 → Store.install 显式交接的 `display` 部分，DESIGN §6.1）。 */
    fun display(): CatalogDisplay = CatalogDisplay(
        displayName = displayName,
        identity = identity,
        variant = variant,
        version = version,
        channel = channel,
        notes = notes,
    )
}

/**
 * 目录条目的展示字段（install 显式参数 `display: …` 的载体）。
 * 仅用于交接/展示，不参与任何校验。
 */
data class CatalogDisplay(
    val displayName: String,
    val identity: String? = null,
    val variant: String? = null,
    val version: String? = null,
    val channel: String? = null,
    val notes: String? = null,
)

/** catalog 加载失败。[reason] 驱动 UI 文案（签名失败 → "镜像目录签名无效"，§6.1）。 */
class CatalogException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
    /**
     * [Reason.MALFORMED] 且错误源于 schema 版本时携带**目录里的原始 `schema` 值**
     * （`< 2` = 旧目录、`> 2` = 目录比应用新）；其余失败为 null。
     * UI（`ImagesViewModel.catalogErrorMessage`）据此在
     * `images_catalog_schema_old`（<2）/ `images_catalog_schema_new`（>2）与
     * 通用 malformed 文案之间切换。
     */
    val schema: Long? = null,
) : Exception(message, cause) {
    enum class Reason {
        /** 网络层失败（DNS/超时/HTTP≥400/超限）—— 与解析、签名错误区分，UI 文案不同。 */
        NETWORK,

        /**
         * JSON/schema 不可解析、schema 版本不认识。
         * schema v2 语义下也承载「目录需要更新应用版本」（`schema < 2` 旧目录被拒）与
         * 「需要更新应用」（`schema > 2` 目录比应用新）—— 不新增枚举值：
         * `ImagesViewModel.catalogErrorMessage` 对本枚举是**穷尽 when**，新增值会让
         * ui/ 编译失败；语义差异走 [schema] 字段（UI 据此在
         * `images_catalog_schema_old`/`_new` 与通用 malformed 文案间切换）。
         */
        MALFORMED,

        /** 条目级校验失败（image_id/url/size/sha256/image_size/image_sha256 任一非法即整体拒绝）。 */
        BAD_ENTRY,

        /** 订阅 URL 可达但 `.sig` 缺失（公钥已内置后必验，§6.1 不降级）。 */
        SIGNATURE_MISSING,

        /** 签名校验失败（detached sig 与 catalog.json 原始字节不匹配）。 */
        SIGNATURE_INVALID,
    }
}

/**
 * §6.1 catalog 解析与签名校验（纯逻辑；[ImageCatalogRepository] 负责取字节）。
 *
 * 解析策略：**条目级严格、整体原子** —— 任一条目非法即拒绝整个目录。理由：
 * `image_id` 会拼进 QEMU `-drive` 路径（B-R1-16 防选项注入），`sha256` 是下载
 * 完整性的唯一锚点；显示半份目录比不显示更危险。
 *
 * schema v2（IMAGE-FORMAT-v2 §7.2）：
 *  - `schema == 2` 才解析，数组键为 `assets`；
 *  - `schema < 2` → 拒绝（旧目录：目录需要更新应用版本语义，DESIGN §9）；
 *  - `schema > 2` → 拒绝（目录比应用新：需要更新应用版本语义）；
 *  - 未知字段一律容忍（前向兼容：目录新增字段不致于把目录打挂）。
 */
object ImageCatalog {

    /** §6.1（R1: B-R1-16）：`^[a-z0-9][a-z0-9._-]{0,63}$` —— 与 store/codec 同源口径。 */
    val IMAGE_ID_REGEX: Regex = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /**
     * schema v2 唯一分发容器后缀（IMAGE-FORMAT-v2 §7.1）：目录 url、下载产物
     * `<id>.img.zst`、`.part` 命名共用此常量（下载器引用同一真值，避免漂移）。
     */
    const val IMG_ZST_SUFFIX: String = ".img.zst"

    private val SHA256_REGEX: Regex = Regex("^[0-9a-f]{64}$")

    /** schema=2 解析。@throws CatalogException MALFORMED / BAD_ENTRY */
    fun parse(json: String): List<CatalogEntry> {
        val root = try {
            MiniJson.parseObject(json)
        } catch (e: Exception) {
            throw CatalogException(
                CatalogException.Reason.MALFORMED,
                "catalog.json is not a JSON object: ${e.message}",
                e,
            )
        }
        val schema = MiniJson.long(root, "schema")
        if (schema == null) {
            throw CatalogException(CatalogException.Reason.MALFORMED, "catalog.schema missing")
        }
        if (schema < 2L) {
            // DESIGN §9：schema<2 拒绝并提示（旧目录 → 「目录需要更新应用版本」语义）
            throw CatalogException(
                CatalogException.Reason.MALFORMED,
                "catalog schema $schema < 2: outdated catalog, 目录需要更新应用版本（应用只接受 schema 2）",
                schema = schema,
            )
        }
        if (schema > 2L) {
            // 目录比应用新（生产侧已 bump）→ 「需要更新应用版本」语义
            throw CatalogException(
                CatalogException.Reason.MALFORMED,
                "catalog schema $schema > 2: catalog is newer than this app, 请更新应用版本",
                schema = schema,
            )
        }
        val assets = root["assets"]
        if (assets !is List<*>) {
            throw CatalogException(CatalogException.Reason.MALFORMED, "catalog.assets missing or not an array")
        }
        val seen = HashSet<String>()
        return assets.mapIndexed { index, raw ->
            if (raw !is Map<*, *>) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index] is not an object")
            }
            @Suppress("UNCHECKED_CAST")
            val m = raw as Map<String, Any?>
            val id = MiniJson.str(m, "image_id")
            if (id == null || !IMAGE_ID_REGEX.matches(id)) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index].image_id invalid: $id")
            }
            if (!seen.add(id)) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index].image_id duplicate: $id")
            }
            val url = MiniJson.str(m, "url")
            if (url.isNullOrBlank()) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index].url missing")
            }
            if (!url.endsWith(IMG_ZST_SUFFIX)) {
                // §7.2：v2 分发容器只有 .img.zst 一种；其余（含旧 .img 直链）整体拒绝。
                // 与生产侧 mkcatalog.sh schema2 自检同口径（url endswith .img.zst → die）。
                throw CatalogException(
                    CatalogException.Reason.BAD_ENTRY,
                    "assets[$index].url must end with $IMG_ZST_SUFFIX: $url",
                )
            }
            val size = MiniJson.long(m, "size")
            if (size == null || size <= 0L) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index].size invalid: $size")
            }
            val sha = MiniJson.str(m, "sha256")?.lowercase()
            if (sha == null || !SHA256_REGEX.matches(sha)) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index].sha256 invalid")
            }
            val imageSize = MiniJson.long(m, "image_size")
            if (imageSize == null || imageSize <= 0L) {
                throw CatalogException(
                    CatalogException.Reason.BAD_ENTRY,
                    "assets[$index].image_size invalid: $imageSize",
                )
            }
            val imageSha = MiniJson.str(m, "image_sha256")?.lowercase()
            if (imageSha == null || !SHA256_REGEX.matches(imageSha)) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "assets[$index].image_sha256 invalid")
            }
            CatalogEntry(
                imageId = id,
                displayName = MiniJson.str(m, "display_name") ?: id,
                identity = MiniJson.str(m, "identity"),
                variant = MiniJson.str(m, "variant"),
                version = MiniJson.str(m, "version"),
                systemVersion = MiniJson.long(m, "system_version"),
                arch = MiniJson.str(m, "arch"),
                channel = MiniJson.str(m, "channel"),
                url = url,
                size = size,
                sha256 = sha,
                imageSize = imageSize,
                imageSha256 = imageSha,
                appMinVersionCode = MiniJson.long(m, "app_min_version_code") ?: 1L,
                notes = MiniJson.str(m, "notes"),
            )
        }
    }

    /**
     * §6.1 detached 签名校验：`catalog.json` **原始字节**（不做规范化重排）+
     * base64(DER ECDSA-Signature) + 内置 P-256 公钥（PEM）。
     * schema v2 机制不变（IMAGE-FORMAT-v2 §7.2 / mkcatalog.sh 头注释：
     * 两 schema 同一机制、同一私钥，签名对象 = 产物原始字节）。
     * 任何异常（坏 PEM / 坏 base64 / 非 ECDSA 签名）→ false（调用方拒绝加载，不降级）。
     */
    fun verifySignature(publicKeyPem: String, signatureBase64: String, content: ByteArray): Boolean = try {
        val body = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(body)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(key)
        sig.update(content)
        // Base64.getDecoder()（非 MIME）：含换行的输入直接抛 → false，符合 §6.1。
        sig.verify(Base64.getDecoder().decode(signatureBase64.trim()))
    } catch (e: Exception) {
        false
    }
}
