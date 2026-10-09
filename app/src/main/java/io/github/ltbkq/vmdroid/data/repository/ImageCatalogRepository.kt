/*
 * VMDroid - image catalog fetch (DESIGN §6.1).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Android 壳：订阅 URL 读设置、HTTP 取 `catalog.json`（+ `.sig`）、公钥资源读取；
 * 解析与 ECDSA 验签的纯逻辑在 `ImageCatalog.kt`。
 */
package io.github.ltbkq.vmdroid.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ltbkq.vmdroid.systemimage.CatalogEntry
import io.github.ltbkq.vmdroid.systemimage.CatalogException
import io.github.ltbkq.vmdroid.systemimage.ImageCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.1 镜像目录获取。
 *
 * **签名链门闩（M4→M7）**：`res/raw/catalog_pub.pem` 缺失时跳过验签 —— M4 先落
 * 目录功能，M7 把公钥内置 APK 后 `.sig` 即为**必填**：缺 → [CatalogException]
 * `SIGNATURE_MISSING`，验不过 → `SIGNATURE_INVALID`，一律拒绝加载（§6.1 决策：
 * 不降级裸 HTTPS，否则签名形同虚设）。
 */
@Singleton
class ImageCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    companion object {
        /**
         * §6.1 默认订阅。
         *
         * FIXLIST ISSUE-01：原值指向 `ltbkq/Podroid-Debian` 且镜像实际发布在
         * `ltbkq/vmdroid`，页面恒 404。改为**固定 tag** 而不是 `releases/latest`：
         * `latest` 永远是 APK Release（如 v1.3.0），用 `latest/download` 取镜像目录
         * 必然 404；固定 tag 只在**镜像 Release 变更时**需要同步改这里
         * （生成与签名见 distro-build/mkcatalog.sh）。
         */
        const val DEFAULT_URL =
            "https://github.com/ltbkq/vmdroid/releases/download/system-images-2026.10.08/catalog.json"

        private const val TAG = "ImageCatalogRepo"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000

        /** 防御性上限：catalog 是目录不是数据（1 MiB 足够放数百条）。 */
        private const val MAX_BYTES = 1024 * 1024

        /** M7 内置公钥的资源名（`res/raw/catalog_pub.pem`）。 */
        private const val PUBKEY_RES = "catalog_pub"
    }

    /** 当前订阅 URL（应用内可改；空白回退默认值）。 */
    suspend fun catalogUrl(): String =
        settings.getCatalogUrlSnapshot().ifBlank { DEFAULT_URL }

    /**
     * 拉取 →（公钥已内置时）验签 → 解析。
     * @throws CatalogException NETWORK / SIGNATURE_MISSING / SIGNATURE_INVALID / MALFORMED / BAD_ENTRY
     */
    suspend fun fetch(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val url = catalogUrl()
        val body = httpGet(url)
        val pem = loadPubkeyPem()
        if (pem != null) {
            val sig = try {
                String(httpGet("$url.sig"), Charsets.UTF_8)
            } catch (e: CatalogException) {
                throw CatalogException(
                    CatalogException.Reason.SIGNATURE_MISSING,
                    "$url.sig unavailable: ${e.message}",
                    e,
                )
            }
            if (!ImageCatalog.verifySignature(pem, sig, body)) {
                throw CatalogException(CatalogException.Reason.SIGNATURE_INVALID, "catalog signature mismatch for $url")
            }
        }
        ImageCatalog.parse(String(body, Charsets.UTF_8))
    }

    /** 公钥资源；M7 前不存在 → null（跳过验签，见类注释）。 */
    private fun loadPubkeyPem(): String? {
        val id = context.resources.getIdentifier(PUBKEY_RES, "raw", context.packageName)
        if (id == 0) return null
        return runCatching {
            context.resources.openRawResource(id).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun httpGet(url: String): ByteArray {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw CatalogException(CatalogException.Reason.NETWORK, "bad catalog url $url: ${e.message}", e)
        }
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                throw CatalogException(CatalogException.Reason.NETWORK, "HTTP $code for $url")
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        throw CatalogException(CatalogException.Reason.NETWORK, "$url exceeds $MAX_BYTES bytes")
                    }
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: CatalogException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "catalog fetch failed: $url", e)
            throw CatalogException(CatalogException.Reason.NETWORK, "$url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
