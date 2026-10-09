/*
 * VMDroid - image catalog (DESIGN §6.1) + detached signature (ECDSA P-256).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN.md §6.1（目录 schema、image_id 白名单正则 R1: B-R1-16、
 *     签名决策 2026-10-08：ECDSA P-256 + SHA-256、签名对象 = catalog.json 原始字节、
 *     base64(DER) 非 MIME、校验失败拒绝加载且不降级裸 HTTPS、R3: B-R3-21）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— HTTP 获取、订阅 URL 设置与
 * 公钥资源读取在 Android 壳（ImageCatalogRepository）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** §6.1 `images[]` 条目（catalog 扁平字段口径；`app_min_version_code` 与 manifest 的 `app.min_version_code` 同义）。 */
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
    val appMinVersionCode: Long,
    val notes: String?,
)

/** catalog 加载失败。[reason] 驱动 UI 文案（签名失败 → "镜像目录签名无效"，§6.1）。 */
class CatalogException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        /** 网络层失败（DNS/超时/HTTP≥400/超限）—— 与解析、签名错误区分，UI 文案不同。 */
        NETWORK,

        /** JSON/schema 不可解析、schema 版本不认识。 */
        MALFORMED,

        /** 条目级校验失败（image_id/url/size/sha256 任一非法即整体拒绝）。 */
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
 */
object ImageCatalog {

    /** §6.1（R1: B-R1-16）：`^[a-z0-9][a-z0-9._-]{0,63}$` —— 与 store/codec 同源口径。 */
    val IMAGE_ID_REGEX: Regex = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    private val SHA256_REGEX: Regex = Regex("^[0-9a-f]{64}$")

    /** schema=1 解析。@throws CatalogException MALFORMED / BAD_ENTRY */
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
        if (schema != 1L) {
            throw CatalogException(CatalogException.Reason.MALFORMED, "unsupported catalog schema: $schema")
        }
        val images = root["images"]
        if (images !is List<*>) {
            throw CatalogException(CatalogException.Reason.MALFORMED, "catalog.images missing or not an array")
        }
        return images.mapIndexed { index, raw ->
            if (raw !is Map<*, *>) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "images[$index] is not an object")
            }
            @Suppress("UNCHECKED_CAST")
            val m = raw as Map<String, Any?>
            val id = MiniJson.str(m, "image_id")
            if (id == null || !IMAGE_ID_REGEX.matches(id)) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "images[$index].image_id invalid: $id")
            }
            val url = MiniJson.str(m, "url")
            if (url.isNullOrBlank()) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "images[$index].url missing")
            }
            val size = MiniJson.long(m, "size")
            if (size == null || size <= 0L) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "images[$index].size invalid: $size")
            }
            val sha = MiniJson.str(m, "sha256")?.lowercase()
            if (sha == null || !SHA256_REGEX.matches(sha)) {
                throw CatalogException(CatalogException.Reason.BAD_ENTRY, "images[$index].sha256 invalid")
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
                appMinVersionCode = MiniJson.long(m, "app_min_version_code") ?: 1L,
                notes = MiniJson.str(m, "notes"),
            )
        }
    }

    /**
     * §6.1 detached 签名校验：`catalog.json` **原始字节**（不做规范化重排）+
     * base64(DER ECDSA-Signature) + 内置 P-256 公钥（PEM）。
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
