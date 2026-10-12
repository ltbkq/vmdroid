/*
 * VMDroid - catalog schema v2 解析/验签单测（IMAGE-FORMAT-v2 §7.2 / DESIGN-disk-image-v2 §9）。
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 被测对象：`io.github.ltbkq.vmdroid.systemimage.ImageCatalog`（schema v2 解析 + ECDSA P-256
 * detached 验签）。实现位于 systemimage/（catalog/下载工位的 v2 切换产物），本测试按任务
 * 约定放在 data/repository 测试包下，纯 JVM、无 android.* 依赖。
 *
 * 生产侧真值：distro-build/mkcatalog.sh SCHEMA=2 分支 —— 15 字段条目、签名对象 =
 * catalog.json 原始字节、单行 base64(DER)。
 */
package io.github.ltbkq.vmdroid.data.repository

import io.github.ltbkq.vmdroid.systemimage.CatalogException
import io.github.ltbkq.vmdroid.systemimage.ImageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ImageCatalogV2Test {

    companion object {
        private const val SHA_ZST = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val SHA_IMG = "1111111111111111111111111111111111111111111111111111111111111111"
    }

    // ---------------------------------------------------------------- 正例

    /** schema2 全字段（mkcatalog.sh SCHEMA=2 的 15 字段条目）逐一映射。 */
    @Test
    fun parse_schema2_allFields() {
        val json = """
            {
              "schema": 2,
              "generated_at": "2026-10-12T00:00:00Z",
              "assets": [
                {
                  "image_id": "debian-minimal-arm64",
                  "display_name": "Debian 13 (trixie) · 最小化",
                  "identity": "debian:trixie",
                  "variant": "minimal",
                  "version": "2026.10.0-r2",
                  "system_version": 35,
                  "arch": "arm64",
                  "channel": "stable",
                  "url": "https://github.com/ltbkq/vmdroid/releases/download/system-images/debian-minimal-arm64.img.zst",
                  "size": 1056768,
                  "sha256": "$SHA_ZST",
                  "image_size": 4294967296,
                  "image_sha256": "$SHA_IMG",
                  "app_min_version_code": 3,
                  "notes": "最小化：systemd + dropbear"
                }
              ]
            }
        """.trimIndent()

        val entries = ImageCatalog.parse(json)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("debian-minimal-arm64", e.imageId)
        assertEquals("Debian 13 (trixie) · 最小化", e.displayName)
        assertEquals("debian:trixie", e.identity)
        assertEquals("minimal", e.variant)
        assertEquals("2026.10.0-r2", e.version)
        assertEquals(35L, e.systemVersion)
        assertEquals("arm64", e.arch)
        assertEquals("stable", e.channel)
        assertEquals("https://github.com/ltbkq/vmdroid/releases/download/system-images/debian-minimal-arm64.img.zst", e.url)
        assertEquals(1056768L, e.size)
        assertEquals(SHA_ZST, e.sha256)
        assertEquals(4294967296L, e.imageSize)
        assertEquals(SHA_IMG, e.imageSha256)
        assertEquals(3L, e.appMinVersionCode)
        assertEquals("最小化：systemd + dropbear", e.notes)
        // install 交接的 display 快照携带展示字段
        val display = e.display()
        assertEquals("Debian 13 (trixie) · 最小化", display.displayName)
        assertEquals("debian:trixie", display.identity)
        assertEquals("stable", display.channel)
    }

    /** schema2 最小条目：可选展示字段缺省（display_name 回退 image_id，数值字段回退默认）。 */
    @Test
    fun parse_schema2_minimalEntry_defaultsApply() {
        val json = """
            {"schema":2,"assets":[{
              "image_id":"alpine-3.24-arm64",
              "url":"https://example.com/alpine-3.24-arm64.img.zst",
              "size":1,
              "sha256":"$SHA_ZST",
              "image_size":2,
              "image_sha256":"$SHA_IMG"
            }]}
        """.trimIndent()

        val e = ImageCatalog.parse(json).single()
        assertEquals("alpine-3.24-arm64", e.displayName) // display_name 缺省 → image_id
        assertEquals(1L, e.appMinVersionCode)
        assertEquals(null, e.identity)
        assertEquals(null, e.systemVersion)
        assertEquals(null, e.notes)
    }

    // ---------------------------------------------------------------- 负例：schema 版本

    /** schema < 2（旧目录）整体拒绝；语义 = 「目录需要更新应用版本」（message 承载）。 */
    @Test
    fun parse_schema1_rejected() {
        val json = """
            {"schema":1,"images":[{"image_id":"debian-minimal-arm64"}]}
        """.trimIndent()
        val e = assertThrows(CatalogException::class.java) { ImageCatalog.parse(json) }
        assertEquals(CatalogException.Reason.MALFORMED, e.reason)
        assertTrue(e.message!!.contains("schema 1 < 2"))
    }

    /** schema > 2（目录比应用新）同样拒绝，语义 = 「需要更新应用版本」。 */
    @Test
    fun parse_schemaNewerThan2_rejected() {
        val e = assertThrows(CatalogException::class.java) { ImageCatalog.parse("""{"schema":3,"assets":[]}""") }
        assertEquals(CatalogException.Reason.MALFORMED, e.reason)
        assertTrue(e.message!!.contains("schema 3 > 2"))
    }

    @Test
    fun parse_schemaMissing_rejected() {
        val e = assertThrows(CatalogException::class.java) { ImageCatalog.parse("""{"assets":[]}""") }
        assertEquals(CatalogException.Reason.MALFORMED, e.reason)
    }

    /** schema2 但数组键仍是 v1 的 `images` → 拒绝（数组键必须是 assets）。 */
    @Test
    fun parse_schema2WithImagesKey_rejected() {
        val e = assertThrows(CatalogException::class.java) {
            ImageCatalog.parse("""{"schema":2,"images":[]}""")
        }
        assertEquals(CatalogException.Reason.MALFORMED, e.reason)
    }

    // ---------------------------------------------------------------- 负例：条目级严格

    private fun entryCatalog(entryJson: String): String = """{"schema":2,"assets":[$entryJson]}"""

    private val goodEntry =
        """{"image_id":"debian-minimal-arm64","url":"https://example.com/debian-minimal-arm64.img.zst",""" +
            """"size":1056768,"sha256":"$SHA_ZST","image_size":4294967296,"image_sha256":"$SHA_IMG"}"""

    private fun assertBadEntry(entryJson: String) {
        val e = assertThrows(CatalogException::class.java) { ImageCatalog.parse(entryCatalog(entryJson)) }
        assertEquals(CatalogException.Reason.BAD_ENTRY, e.reason)
    }

    /** 缺 `sha256`（zst 摘要）→ 整体拒绝（下载完整性锚点，绝不放行）。 */
    @Test
    fun parse_missingSha256_rejected() {
        assertBadEntry(
            """{"image_id":"debian-minimal-arm64","url":"https://example.com/debian-minimal-arm64.img.zst",""" +
                """"size":1056768,"image_size":4294967296,"image_sha256":"$SHA_IMG"}""",
        )
    }

    /** 缺 `image_sha256`（.img 摘要）→ 整体拒绝（install 期第二道比对的锚点）。 */
    @Test
    fun parse_missingImageSha256_rejected() {
        assertBadEntry(
            """{"image_id":"debian-minimal-arm64","url":"https://example.com/debian-minimal-arm64.img.zst",""" +
                """"size":1056768,"sha256":"$SHA_ZST","image_size":4294967296}""",
        )
    }

    /** 缺 `image_size` / `size` → 整体拒绝。 */
    @Test
    fun parse_missingSizes_rejected() {
        assertBadEntry(
            """{"image_id":"a","url":"https://example.com/a.img.zst","size":1,"sha256":"$SHA_ZST","image_sha256":"$SHA_IMG"}""",
        )
        assertBadEntry(
            """{"image_id":"a","url":"https://example.com/a.img.zst","sha256":"$SHA_ZST","image_size":2,"image_sha256":"$SHA_IMG"}""",
        )
    }

    /** url 非 `.img.zst`（含旧 `.img` 直链）→ BAD_ENTRY 整体拒绝（§7.2 唯一分发容器）。 */
    @Test
    fun parse_urlNotImgZst_rejected() {
        assertBadEntry(
            """{"image_id":"a","url":"https://example.com/a.img","size":1,"sha256":"$SHA_ZST",""" +
                """"image_size":2,"image_sha256":"$SHA_IMG"}""",
        )
        assertBadEntry(
            """{"image_id":"a","url":"https://example.com/a.img.zst?x=1","size":1,"sha256":"$SHA_ZST",""" +
                """"image_size":2,"image_sha256":"$SHA_IMG"}""",
        )
    }

    /** image_id 白名单正则（B-R1-16）与重复 id → 整体拒绝。 */
    @Test
    fun parse_badOrDuplicateImageId_rejected() {
        assertBadEntry(
            """{"image_id":"../evil","url":"https://example.com/evil.img.zst","size":1,"sha256":"$SHA_ZST",""" +
                """"image_size":2,"image_sha256":"$SHA_IMG"}""",
        )
        val dup = """{"schema":2,"assets":[$goodEntry,$goodEntry]}"""
        val e = assertThrows(CatalogException::class.java) { ImageCatalog.parse(dup) }
        assertEquals(CatalogException.Reason.BAD_ENTRY, e.reason)
    }

    /** 坏 sha 形态（大写/长度不符）→ 整体拒绝。 */
    @Test
    fun parse_badShaShape_rejected() {
        assertBadEntry(
            goodEntry.replace(SHA_ZST, "NOT-A-SHA"),
        )
    }

    // ---------------------------------------------------------------- 前向兼容

    /** 未知字段一律容忍（根级 + 条目级）：目录新增字段不应把目录打挂。 */
    @Test
    fun parse_unknownFields_tolerated() {
        val json = """
            {
              "schema": 2,
              "generated_at": "2026-10-12T00:00:00Z",
              "future_root_field": {"nested": true},
              "assets": [
                {
                  "image_id": "debian-minimal-arm64",
                  "url": "https://example.com/debian-minimal-arm64.img.zst",
                  "size": 1056768,
                  "sha256": "$SHA_ZST",
                  "image_size": 4294967296,
                  "image_sha256": "$SHA_IMG",
                  "future_entry_field": "whatever",
                  "another_one": [1, 2, 3]
                }
              ]
            }
        """.trimIndent()
        val e = ImageCatalog.parse(json).single()
        assertEquals("debian-minimal-arm64", e.imageId)
    }

    // ---------------------------------------------------------------- 签名（机制同 v1）

    /** 固定向量（schema 无关：签名对象 = catalog.json 原始字节）。 */
    @Test
    fun signature_fixedVectorVerifies() {
        val content = """{"schema":1,"generated_at":"2026-10-07T12:00:00Z","images":[]}"""
        val pem = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEng2f4SRlzmGG6rb2wuukV6AVMXFf
3LZoZUYzFRLqJ2Luykzr2WgdbNN5iy2Um3UHPJ0LKXdxGLBunMj8/MQBlQ==
-----END PUBLIC KEY-----"""
        val sig =
            "MEQCIDprgRZC99yiurHDqUHrnMII1R6xk6MyPN8WaLZhPtb5AiA5fwiIkAaPH2LscBGpyYah70cjbqWVf/FMLXcRrFuTZw=="
        assertTrue(ImageCatalog.verifySignature(pem, sig, content.toByteArray()))
        assertFalse(ImageCatalog.verifySignature(pem, sig, "tampered".toByteArray()))
    }

    /** schema2 内容 round-trip：单行 base64(DER) 通过；MIME（含换行）/篡改/换 key 拒。 */
    @Test
    fun signature_schema2RoundTrip() {
        val content = """{"schema":2,"generated_at":"2026-10-12T00:00:00Z","assets":[]}""".toByteArray()
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(content)
        }.sign()
        val pem = pemOf(kp.public.encoded)
        val b64 = Base64.getEncoder().encodeToString(sig) // 单行，非 MIME

        assertTrue(ImageCatalog.verifySignature(pem, b64, content))
        assertFalse(ImageCatalog.verifySignature(pem, Base64.getMimeEncoder().encodeToString(sig), content))
        assertFalse(ImageCatalog.verifySignature(pemOf(kpg.generateKeyPair().public.encoded), b64, content))
        assertFalse(ImageCatalog.verifySignature("not-a-key", b64, content))
    }

    private fun pemOf(x509: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(x509)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
    }
}
