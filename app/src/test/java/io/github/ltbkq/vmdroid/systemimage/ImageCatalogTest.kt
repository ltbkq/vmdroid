package io.github.ltbkq.vmdroid.systemimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * §6.1 catalog 解析（schema v2：`schema:2` + `assets[]` + `.img.zst` 双摘要）+
 * ECDSA P-256 detached 签名单测（纯 JVM）。
 * 固定向量 = §13.1「固定 key+message+sig 入库」的正例锚点（签名机制与 schema 版本无关）。
 */
class ImageCatalogTest {

    companion object {
        /**
         * §13.1 固定向量：入库签名所用的原始字节（UTF-8）。签名对象 = 产物原始字节、
         * 无规范化（IMAGE-FORMAT-v2 §7.2：两 schema 同一机制、同一私钥）—— 本向量是
         * 与 catalog schema 版本无关的密码学锚点，保留作正例互操作断言。
         */
        private const val FIXED_VECTOR_BYTES =
            """{"schema":1,"generated_at":"2026-10-07T12:00:00Z","images":[]}"""

        /** schema2 目录字节串（v2 产物口径）：与固定向量签名内容不同 → 同一 sig 必须验签失败。 */
        private const val SIGNED_CONTENT =
            """{"schema":2,"generated_at":"2026-10-07T12:00:00Z","assets":[]}"""

        /** 固定 P-256 公钥（X.509/SPKI PEM；私钥仅存在于生成时，不入库）。 */
        private const val FIXED_PEM = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEng2f4SRlzmGG6rb2wuukV6AVMXFf
3LZoZUYzFRLqJ2Luykzr2WgdbNN5iy2Um3UHPJ0LKXdxGLBunMj8/MQBlQ==
-----END PUBLIC KEY-----"""

        /** base64(DER ECDSA-Signature) 非 MIME —— 与 §6.1 决策 2026-10-08 一致。 */
        private const val FIXED_SIG =
            "MEQCIDprgRZC99yiurHDqUHrnMII1R6xk6MyPN8WaLZhPtb5AiA5fwiIkAaPH2LscBGpyYah70cjbqWVf/FMLXcRrFuTZw=="
    }

    // ---------------------------------------------------------------- 解析

    private val validCatalog = """
        {
          "schema": 2,
          "generated_at": "2026-10-07T12:00:00Z",
          "assets": [
            {
              "image_id": "debian-minimal-arm64",
              "display_name": "Debian 13 (trixie) · 最小化",
              "identity": "debian:trixie",
              "variant": "minimal",
              "version": "2026.10.0-r1",
              "system_version": 34,
              "arch": "arm64",
              "channel": "stable",
              "url": "https://github.com/ltbkq/Podroid-Debian/releases/download/v34/debian.img.zst",
              "size": 39318048,
              "sha256": "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00",
              "image_size": 148951040,
              "image_sha256": "d0f7d5dfbde47ba54d9a49082ff75b6b6d52989e898fb92631139416911e59b7",
              "app_min_version_code": 3,
              "notes": "最小化：systemd + dropbear"
            }
          ]
        }
    """.trimIndent()

    /** 旧 v1 目录（`schema:1` + `images[]` + `.img` 直链）—— DESIGN §9：必须整体拒载。 */
    private val schema1LegacyCatalog = """
        {
          "schema": 1,
          "generated_at": "2026-10-07T12:00:00Z",
          "images": [
            {
              "image_id": "debian-minimal-arm64",
              "url": "https://github.com/ltbkq/Podroid-Debian/releases/download/v34/debian.img",
              "size": 148951040,
              "sha256": "d0f7d5dfbde47ba54d9a49082ff75b6b6d52989e898fb92631139416911e59b7"
            }
          ]
        }
    """.trimIndent()

    private fun entryJson(modify: (MutableMap<String, Any?>) -> Unit = {}): String {
        val m = linkedMapOf<String, Any?>(
            "image_id" to "debian-minimal-arm64",
            "url" to "https://example.com/debian.img.zst",
            "size" to 66490L,
            "sha256" to "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00",
            "image_size" to 1056768L,
            "image_sha256" to "d0f7d5dfbde47ba54d9a49082ff75b6b6d52989e898fb92631139416911e59b7",
        )
        modify(m)
        return MiniJson.write(m)
    }

    private fun catalogWith(vararg entries: String): String =
        """{"schema":2,"assets":[${entries.joinToString(",")}]}"""

    private fun assertRejects(catalog: String, reason: CatalogException.Reason) {
        val e = assertThrows(CatalogException::class.java) { ImageCatalog.parse(catalog) }
        assertEquals(reason, e.reason)
    }

    @Test
    fun parse_validCatalog_extractsEveryField() {
        val entries = ImageCatalog.parse(validCatalog)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("debian-minimal-arm64", e.imageId)
        assertEquals("Debian 13 (trixie) · 最小化", e.displayName)
        assertEquals("debian:trixie", e.identity)
        assertEquals("minimal", e.variant)
        assertEquals("2026.10.0-r1", e.version)
        assertEquals(34L, e.systemVersion)
        assertEquals("arm64", e.arch)
        assertEquals("stable", e.channel)
        assertEquals("https://github.com/ltbkq/Podroid-Debian/releases/download/v34/debian.img.zst", e.url)
        assertEquals(39318048L, e.size)
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00", e.sha256)
        assertEquals(148951040L, e.imageSize)
        assertEquals("d0f7d5dfbde47ba54d9a49082ff75b6b6d52989e898fb92631139416911e59b7", e.imageSha256)
        assertEquals(3L, e.appMinVersionCode)
        assertEquals("最小化：systemd + dropbear", e.notes)
        // install 显式交接的展示字段快照（DESIGN-disk-image-v2 §6.1 display 部分）
        val display = e.display()
        assertEquals("Debian 13 (trixie) · 最小化", display.displayName)
        assertEquals("debian:trixie", display.identity)
        assertEquals("minimal", display.variant)
        assertEquals("2026.10.0-r1", display.version)
        assertEquals("stable", display.channel)
        assertEquals("最小化：systemd + dropbear", display.notes)
    }

    @Test
    fun parse_displayNameFallsBackToImageId() {
        val entries = ImageCatalog.parse(catalogWith(entryJson { it.remove("display_name") }))
        assertEquals("debian-minimal-arm64", entries[0].displayName)
    }

    @Test
    fun parse_shaUppercaseIsNormalizedToLowercase() {
        val upper = "D0F7D5DFBDE47BA54D9A49082FF75B6B6D52989E898FB92631139416911E59B7"
        val entries = ImageCatalog.parse(
            catalogWith(entryJson { it["sha256"] = upper; it["image_sha256"] = upper }),
        )
        assertEquals(upper.lowercase(), entries[0].sha256)
        assertEquals(upper.lowercase(), entries[0].imageSha256)
    }

    @Test
    fun parse_optionalFieldsMayBeAbsent() {
        val entries = ImageCatalog.parse(
            catalogWith(entryJson { it.remove("identity"); it.remove("variant"); it.remove("version"); it.remove("arch") }),
        )
        val e = entries[0]
        assertNull(e.identity)
        assertNull(e.variant)
        assertNull(e.version)
        assertNull(e.arch)
        assertEquals(1L, e.appMinVersionCode) // 默认值（与 manifest app.min_version_code 同义）
    }

    @Test
    fun parse_schemaMissingOrUnknown_isMalformed() {
        assertRejects("""{"assets":[]}""", CatalogException.Reason.MALFORMED) // schema 缺失
        assertRejects("""{"schema":1,"assets":[]}""", CatalogException.Reason.MALFORMED) // 旧目录被拒（DESIGN §9）
        assertRejects(schema1LegacyCatalog, CatalogException.Reason.MALFORMED) // 完整 v1 目录同样拒载
        assertRejects("""{"schema":3,"assets":[]}""", CatalogException.Reason.MALFORMED) // 目录比应用新
        assertRejects("""{"schema":2}""", CatalogException.Reason.MALFORMED) // assets 缺失
        assertRejects("""{"schema":2,"assets":{}}""", CatalogException.Reason.MALFORMED)
        assertRejects("not json at all {", CatalogException.Reason.MALFORMED)
        assertRejects("""[{"schema":2}]""", CatalogException.Reason.MALFORMED)
    }

    @Test
    fun parse_badImageId_isRejected() {
        // B-R1-16 注入面：逗号会截断 QEMU -drive 选项；大写/前导符号/超长一律不收
        val badIds = listOf("Debian-Minimal", "has,comma", "../evil", "-lead", "a".repeat(65), "")
        for (id in badIds) {
            val e = assertThrows(CatalogException::class.java) {
                ImageCatalog.parse(catalogWith(entryJson { it["image_id"] = id }))
            }
            assertEquals("id='$id'", CatalogException.Reason.BAD_ENTRY, e.reason)
        }
    }

    @Test
    fun parse_badShaOrSizeOrUrl_isRejected() {
        assertRejects(catalogWith(entryJson { it["sha256"] = "abc" }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it.remove("sha256") }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it["size"] = 0L }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it["size"] = -5L }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it.remove("size") }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it["url"] = "" }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it.remove("url") }), CatalogException.Reason.BAD_ENTRY)
        // §7.2：v2 分发容器只有 .img.zst —— 旧 .img 直链整体拒绝
        assertRejects(
            catalogWith(entryJson { it["url"] = "https://example.com/debian.img" }),
            CatalogException.Reason.BAD_ENTRY,
        )
        // 双摘要语义：image_size / image_sha256（对 .img）同样严格
        assertRejects(catalogWith(entryJson { it["image_sha256"] = "abc" }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it.remove("image_sha256") }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it["image_size"] = 0L }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it["image_size"] = -5L }), CatalogException.Reason.BAD_ENTRY)
        assertRejects(catalogWith(entryJson { it.remove("image_size") }), CatalogException.Reason.BAD_ENTRY)
    }

    @Test
    fun parse_oneBadEntryRejectsWholeCatalog() {
        // 整体原子：宁可不显示也不显示半份目录
        val good = entryJson()
        val bad = entryJson { it["sha256"] = "nope" }
        assertRejects(catalogWith(good, bad), CatalogException.Reason.BAD_ENTRY)
        val notAnObject = """"just-a-string""""
        assertRejects(catalogWith(good, notAnObject), CatalogException.Reason.BAD_ENTRY)
    }

    // ---------------------------------------------------------------- 签名（§6.1）

    @Test
    fun signature_fixedVectorVerifies() {
        assertTrue(
            ImageCatalog.verifySignature(FIXED_PEM, FIXED_SIG, FIXED_VECTOR_BYTES.toByteArray()),
        )
    }

    @Test
    fun signature_tamperedContentFails() {
        // 签名对象是原始字节、无规范化：内容变化（含换成 schema2 目录字节串）→ 必须失败
        val bytes = FIXED_VECTOR_BYTES.toByteArray().clone()
        bytes[1] = 'X'.code.toByte() // 改一个字节
        assertFalse(ImageCatalog.verifySignature(FIXED_PEM, FIXED_SIG, bytes))
        assertFalse(ImageCatalog.verifySignature(FIXED_PEM, FIXED_SIG, SIGNED_CONTENT.toByteArray()))
        assertFalse(ImageCatalog.verifySignature(FIXED_PEM, FIXED_SIG, "other".toByteArray()))
    }

    @Test
    fun signature_roundTripAndEdgeCases() {
        val content = "catalog-bytes-for-round-trip".toByteArray()
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val kp = kpg.generateKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(content)
        }.sign()
        val pem = pemOf(kp.public.encoded)
        val b64 = Base64.getEncoder().encodeToString(sig)

        assertTrue(ImageCatalog.verifySignature(pem, b64, content))
        // 无头部的纯 body PEM 也接受
        assertTrue(
            ImageCatalog.verifySignature(
                pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""),
                b64,
                content,
            ),
        )
        // 换 key → 拒
        assertFalse(ImageCatalog.verifySignature(pemOf(kpg.generateKeyPair().public.encoded), b64, content))
        // MIME（含换行）签名 → 拒（§6.1：非 MIME）
        assertFalse(ImageCatalog.verifySignature(pem, Base64.getMimeEncoder().encodeToString(sig), content))
        // 垃圾 PEM / 垃圾 base64 → 拒（绝不抛）
        assertFalse(ImageCatalog.verifySignature("not-a-key", b64, content))
        assertFalse(ImageCatalog.verifySignature(pem, "not-base64!!!", content))
        assertFalse(ImageCatalog.verifySignature(pem, "", content))
    }

    private fun pemOf(x509: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(x509)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
    }
}
