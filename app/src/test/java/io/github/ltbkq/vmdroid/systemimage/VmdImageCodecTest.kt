/*
 * VmdImageCodec JUnit 测试（IMAGE-FORMAT.md §8 测试向量 + DESIGN §5.2/§7.4/§13.1）。
 *
 * 归属（DESIGN §13.1「单元 (JVM)」行）：往返编解码、footer 定位、字段缺省、
 * 截断/损坏/错位/越界拒绝、ssh_port≠22 拒绝、identity 判据全表、流式 sha256。
 *
 * fixtures 由 codec-selftest/gen_fixtures.py 生成；找不到时整类 skip（Assume），
 * 可用 -Dvmd.fixtures=/path/to/fixtures 显式指定（CI 可把向量库挂到任意路径）。
 */
package io.github.ltbkq.vmdroid.systemimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

class VmdImageCodecTest {

    private lateinit var fixtures: File
    private lateinit var tmp: File

    @Before
    fun setUpFixtures() {
        fixtures = locateFixtures()
        Assume.assumeTrue(
            "fixtures not found (run: python3 codec-selftest/gen_fixtures.py, " +
                "or set -Dvmd.fixtures=/path/to/fixtures)",
            fixtures.isDirectory,
        )
        tmp = File(System.getProperty("java.io.tmpdir"), "vmd-codec-junit").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun read(name: String, current: Long? = null): VmdImageCodec.ImageInfo =
        VmdImageCodec.read(File(fixtures, name), current)

    private fun expectReject(name: String, expected: VmdImageReason, current: Long? = null) {
        try {
            val info = VmdImageCodec.read(File(fixtures, name), current)
            throw AssertionError("expected $expected but read() succeeded: $info")
        } catch (e: VmdImageException) {
            assertEquals("reject reason for $name (msg=${e.message})", expected, e.reason)
        }
    }

    private fun sidecar(file: File): String =
        file.readText(Charsets.UTF_8).trim().substringBefore(' ')

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        for (k in 0..3) b[off + k] = ((v ushr (8 * k)) and 0xFF).toByte()
    }

    private fun putU64(b: ByteArray, off: Int, v: Long) {
        for (k in 0..7) b[off + k] = ((v ushr (8 * k)) and 0xFF).toByte()
    }

    // ------------------------------------------------------------------
    // 正向
    // ------------------------------------------------------------------

    @Test
    fun readsValidMinimalImage() {
        val img = File(fixtures, "valid-min.img")
        val info = read("valid-min.img", 1)
        val f = info.footer
        assertEquals(VmdImageCodec.MAGIC, f.magic)
        assertEquals(VmdImageCodec.MAGIC, f.magicTail)
        assertEquals(1, f.formatVersion)
        assertEquals(VmdImageCodec.FOOTER_SIZE, f.footerSize)
        assertEquals(img.length(), f.fileSize)
        assertEquals(0, f.flags) // §8: 无 kernel/initrd → flags==0
        assertEquals(0L, f.rootfsOffset)
        assertEquals(65536L, f.rootfsSize)
        assertEquals(img.length() - 4096, f.dataLimit)

        val m = info.manifest
        assertEquals(VmdImageCodec.MANIFEST_FORMAT, m.format)
        assertEquals("debian-minimal-arm64", m.image.id)
        assertTrue(Regex(VmdImageCodec.IMAGE_ID_PATTERN).matches(m.image.id!!))
        assertEquals("arm64", m.image.arch)
        assertEquals("debian:trixie", m.image.identity)
        assertEquals("systemd", m.image.distroInit)
        assertEquals(34L, m.image.systemVersion)
        assertTrue("UTF-8 中文显示名", m.image.displayName!!.contains("最小化"))
        assertTrue(m.capabilities.ssh)
        assertTrue(m.capabilities.x11)
        assertFalse(m.capabilities.desktop)
        assertEquals(22L, m.accounts.sshPort)
        assertEquals(2, m.accounts.ssh.size)
        assertEquals("ltbkq", m.accounts.ssh[1].user)
        assertTrue(m.accounts.ssh[1].sudo)
        assertEquals(1L, m.app.minVersionCode)
        assertEquals(1L, m.contract.version)
        assertEquals(5, m.contract.markers.size)
        assertEquals("console=ttyAMA0 mitigations=off", m.boot!!.append)
        assertEquals(f.rootfsSha256, m.checksums["rootfs_sha256"])

        // 未知字段前向兼容 + \uXXXX 转义
        val extra = m.unknown["extra_top"] as? Map<*, *>
        assertNotNull(extra)
        assertEquals("中", extra!!["esc"])
        assertEquals(3, (extra["nested"] as List<*>).size)
    }

    @Test
    fun segmentLayoutAndFooterPosition() {
        val img = File(fixtures, "valid-min.img")
        val f = read("valid-min.img").footer
        assertEquals(0L, f.manifestOffset % VmdImageCodec.MIB)
        assertEquals(img.length() - 4096, f.footerOffset)
        assertTrue(f.rootfsSize <= f.manifestOffset)
        assertTrue(f.manifestOffset + f.manifestSize <= f.dataLimit)
    }

    @Test
    fun verifyPayloadsAndWholeFileSha() {
        val img = File(fixtures, "valid-min.img")
        VmdImageCodec.verifyPayloads(read("valid-min.img"))
        assertEquals(sidecar(File(fixtures, "valid-min.img.sha256")), VmdImageCodec.sha256File(img.absolutePath))

        val full = File(fixtures, "valid-full.img")
        VmdImageCodec.verifyPayloads(read("valid-full.img"))
        assertEquals(sidecar(File(fixtures, "valid-full.img.sha256")), VmdImageCodec.sha256File(full.absolutePath))
    }

    /** §8 回归向量：seed 已于 R3 移除，72/124/172 必须解析为 manifest/kernel/initrd。 */
    @Test
    fun footerOffsetRegressionNoSeed() {
        val b = ByteArray(VmdImageCodec.FOOTER_SIZE)
        val magic = VmdImageCodec.MAGIC.toByteArray(Charsets.US_ASCII)
        magic.copyInto(b, 0)
        magic.copyInto(b, VmdImageCodec.MAGIC_TAIL_OFFSET)
        putU32(b, 8, 1)
        putU32(b, 12, VmdImageCodec.FOOTER_SIZE)
        putU64(b, 16, 1L shl 30)
        putU64(b, 24, 0)
        putU64(b, 32, 0x1_0000)
        for (i in 40 until 72) b[i] = 0x11
        putU64(b, 72, 0x0011_2233_4455_6677)
        putU64(b, 80, 0x1234)
        for (i in 88 until 120) b[i] = 0x22
        putU32(b, 120, 0x3)
        putU64(b, 124, 0x0102_0304_0506_0708)
        putU64(b, 132, 0x1112_1314_1516_1718)
        for (i in 140 until 172) b[i] = 0x33
        putU64(b, 172, 0x2122_2324_2526_2728)
        putU64(b, 180, 0x3132_3334_3536_3738)
        for (i in 188 until 220) b[i] = 0x44

        val f = VmdImageCodec.parseFooter(b)
        assertEquals(0x0011_2233_4455_6677L, f.manifestOffset)
        assertEquals(0x1234L, f.manifestSize)
        assertEquals("22".repeat(32), f.manifestSha256)
        assertEquals(0x0102_0304_0506_0708L, f.kernelOffset)
        assertEquals(0x1112_1314_1516_1718L, f.kernelSize)
        assertEquals("33".repeat(32), f.kernelSha256)
        assertEquals(0x2122_2324_2526_2728L, f.initrdOffset)
        assertEquals(0x3132_3334_3536_3738L, f.initrdSize)
        assertEquals("44".repeat(32), f.initrdSha256)
        assertEquals("11".repeat(32), f.rootfsSha256)
        assertEquals(3, f.flags)
        assertTrue(f.hasKernel && f.hasInitrd)
        assertEquals(0L, f.rootfsOffset)
    }

    @Test
    fun parseFooterRejectsInvalidInput() {
        expectParseFooterReject(ByteArray(VmdImageCodec.FOOTER_SIZE), VmdImageReason.NOT_AN_IMAGE)
        expectParseFooterReject(ByteArray(100), VmdImageReason.FORMAT_UNSUPPORTED)
        val b = ByteArray(VmdImageCodec.FOOTER_SIZE)
        val magic = VmdImageCodec.MAGIC.toByteArray(Charsets.US_ASCII)
        magic.copyInto(b, 0)
        magic.copyInto(b, VmdImageCodec.MAGIC_TAIL_OFFSET)
        putU64(b, 16, Long.MIN_VALUE) // ≥2^63 的 u64 → 落成负数
        expectParseFooterReject(b, VmdImageReason.CORRUPT)
    }

    private fun expectParseFooterReject(bytes: ByteArray, expected: VmdImageReason) {
        try {
            VmdImageCodec.parseFooter(bytes)
            throw AssertionError("expected $expected")
        } catch (e: VmdImageException) {
            assertEquals(expected, e.reason)
        }
    }

    @Test
    fun validFullFlagsAlignmentAndSources() {
        val f = read("valid-full.img").footer
        assertEquals(0x3, f.flags)
        assertTrue(f.hasKernel && f.hasInitrd)
        assertEquals(VmdImageCodec.MIB, f.kernelOffset)
        assertEquals(VmdImageCodec.MIB, f.kernelSize)
        assertEquals(2 * VmdImageCodec.MIB, f.initrdOffset)
        assertEquals(8192L, f.initrdSize)
        assertEquals(3 * VmdImageCodec.MIB, f.manifestOffset)
        assertEquals(0L, f.kernelOffset % VmdImageCodec.MIB)
        assertEquals(0L, f.initrdOffset % VmdImageCodec.MIB)
        assertEquals(0L, f.manifestOffset % VmdImageCodec.MIB)
        assertEquals(
            VmdImageCodec.sha256(File(fixtures, "sources/kernel.src").readBytes()),
            f.kernelSha256,
        )
        assertEquals(
            VmdImageCodec.sha256(File(fixtures, "sources/initrd.src").readBytes()),
            f.initrdSha256,
        )
    }

    @Test
    fun extractPayloadsMatchSources() {
        val info = read("valid-full.img")
        for (name in listOf("rootfs", "kernel", "initrd")) {
            val out = File(tmp, "$name.out")
            VmdImageCodec.extractPayload(info, name, out)
            assertEquals(
                "extracted $name sha256 vs python source",
                sidecar(File(fixtures, "sources/$name.src.sha256")),
                VmdImageCodec.sha256File(out.absolutePath),
            )
            val expectedLen = when (name) {
                "rootfs" -> info.footer.rootfsSize
                "kernel" -> info.footer.kernelSize
                else -> info.footer.initrdSize
            }
            assertEquals(expectedLen, out.length())
            out.delete()
        }
    }

    @Test
    fun extractRejectsUnknownOrAbsentPayload() {
        val info = read("valid-min.img") // flags=0：无 kernel/initrd
        for (name in listOf("kernel", "initrd", "bogus")) {
            try {
                VmdImageCodec.extractPayload(info, name, File(tmp, "nope.out"))
                throw AssertionError("extract('$name') should throw")
            } catch (e: IllegalArgumentException) {
                assertFalse(File(tmp, "nope.out").exists())
            }
        }
    }

    // ------------------------------------------------------------------
    // §4 字段缺省
    // ------------------------------------------------------------------

    @Test
    fun minimalManifestDefaults() {
        val m = read("minimal-manifest.img", 1).manifest
        assertTrue(m.capabilities.ssh && m.capabilities.x11 && m.capabilities.containers)
        assertFalse(m.capabilities.desktop)
        assertFalse(m.capabilities.downloadsShare)
        assertEquals(1, m.accounts.ssh.size)
        assertEquals("root", m.accounts.ssh[0].user)
        assertEquals("root", m.accounts.defaultUser)
        assertEquals(22L, m.accounts.sshPort)
        assertEquals(1L, m.contract.version)
        assertNull(m.app.minVersionCode)
    }

    @Test
    fun presentAccountsWithoutSshPortDefaultsTo22() {
        val info = read("no-ssh-port.img", 1)
        assertEquals(22L, info.manifest.accounts.sshPort)
        assertTrue(info.manifest.capabilities.ssh)
    }

    // ------------------------------------------------------------------
    // 负向：逐 reason（§8 向量表）
    // ------------------------------------------------------------------

    @Test
    fun rejectsNotAnImage() {
        expectReject("random-junk.img", VmdImageReason.NOT_AN_IMAGE)
        expectReject("zip-as-img.img", VmdImageReason.NOT_AN_IMAGE) // .img 扩展名但内容是 zip
        expectReject("too-small.img", VmdImageReason.NOT_AN_IMAGE)
        expectReject("footer-overwritten.img", VmdImageReason.NOT_AN_IMAGE) // 尾部 4KiB 覆写
        expectReject("truncate-1byte.img", VmdImageReason.NOT_AN_IMAGE) // 截断 1B → magic 移位
    }

    @Test
    fun rejectsBareSquashfsWithMkimgHint() {
        try {
            VmdImageCodec.read(File(fixtures, "bare-squashfs.img"))
            throw AssertionError("bare squashfs must be rejected (N4)")
        } catch (e: VmdImageException) {
            assertEquals(VmdImageReason.NOT_AN_IMAGE, e.reason)
            assertTrue("应给出 mkimg 封装指引: ${e.message}", e.message!!.contains("mkimg"))
        }
    }

    @Test
    fun rejectsTruncated() {
        expectReject("file-size-mismatch.img", VmdImageReason.TRUNCATED)
        expectReject("spliced-footer.img", VmdImageReason.TRUNCATED)
        expectReject("tiny-magic.img", VmdImageReason.TRUNCATED) // magic 匹配但 <8192（§3 规则 1）
    }

    @Test
    fun rejectsFormatUnsupported() {
        expectReject("format-version-2.img", VmdImageReason.FORMAT_UNSUPPORTED)
        expectReject("footer-size-bad.img", VmdImageReason.FORMAT_UNSUPPORTED)
        expectReject("unknown-flags.img", VmdImageReason.FORMAT_UNSUPPORTED) // 掩码 0x3 之外
    }

    @Test
    fun rejectsFlagsSegmentMismatch() {
        expectReject("flags-kernel-mismatch.img", VmdImageReason.CORRUPT)
        expectReject("flags-initrd-mismatch.img", VmdImageReason.CORRUPT)
    }

    @Test
    fun rejectsBoundaryOverflowAndOverlap() {
        expectReject("rootfs-offset-nonzero.img", VmdImageReason.CORRUPT)
        expectReject("manifest-out-of-range.img", VmdImageReason.CORRUPT) // B-R1-10
        expectReject("manifest-overlap.img", VmdImageReason.CORRUPT) // 段序/重叠
        expectReject("kernel-overflow.img", VmdImageReason.CORRUPT) // offset > MAX - size 溢出安全
    }

    @Test
    fun rejectsManifestInvalid() {
        expectReject("manifest-format-wrong.img", VmdImageReason.MANIFEST_INVALID)
        expectReject("bad-manifest-json.img", VmdImageReason.MANIFEST_INVALID)
    }

    @Test
    fun rejectsArchMismatch() {
        expectReject("arch-amd64.img", VmdImageReason.ARCH_MISMATCH)
    }

    @Test
    fun rejectsInvalidImageId() {
        expectReject("bad-image-id.img", VmdImageReason.IMAGE_ID_INVALID)
    }

    @Test
    fun rejectsMissingSshCapability() {
        expectReject("ssh-cap-false.img", VmdImageReason.SSH_CAPABILITY_MISSING)
        expectReject("ssh-cap-missing-key.img", VmdImageReason.SSH_CAPABILITY_MISSING) // fail-closed
    }

    @Test
    fun rejectsInvalidSshPort() {
        expectReject("ssh-port-2222.img", VmdImageReason.SSH_PORT_INVALID)
        expectReject("ssh-port-bad-type.img", VmdImageReason.SSH_PORT_INVALID)
    }

    @Test
    fun rejectsAppTooOldButAllowsNewerApp() {
        expectReject("app-too-old.img", VmdImageReason.APP_TOO_OLD, current = 1)
        val info = read("app-too-old.img", 999999)
        assertEquals(999999L, info.manifest.app.minVersionCode)
    }

    @Test
    fun rejectsManifestShaMismatchOnRead() {
        expectReject("manifest-flip.img", VmdImageReason.PAYLOAD_CORRUPT)
    }

    @Test
    fun rejectsCorruptPayloadOnVerifyAndExtract() {
        for (name in listOf("kernel-flip.img", "initrd-flip.img")) {
            val info = read(name) // read 不查 payload sha（延迟到校验动作）
            try {
                VmdImageCodec.verifyPayloads(info)
                throw AssertionError("verifyPayloads($name) should reject")
            } catch (e: VmdImageException) {
                assertEquals(VmdImageReason.PAYLOAD_CORRUPT, e.reason)
            }
        }
        // extract 同样拒绝且不落盘坏文件（R-16：坏内核不能上机）
        val info = read("kernel-flip.img")
        val out = File(tmp, "kernel-flip.out")
        try {
            VmdImageCodec.extractPayload(info, "kernel", out)
            throw AssertionError("extract should refuse corrupt kernel")
        } catch (e: VmdImageException) {
            assertEquals(VmdImageReason.PAYLOAD_CORRUPT, e.reason)
        }
        assertFalse("坏载荷不得落盘", out.exists())
    }

    // ------------------------------------------------------------------
    // validate() 复检 + §7.4 映射 + §5.2 重置判定
    // ------------------------------------------------------------------

    @Test
    fun validateRecheckWithNewVersionCode() {
        val info = read("valid-min.img", 1)
        try {
            VmdImageCodec.validate(info, 0) // min_version_code=1 > 0
            throw AssertionError("validate(info, 0) should reject")
        } catch (e: VmdImageException) {
            assertEquals(VmdImageReason.APP_TOO_OLD, e.reason)
        }
        VmdImageCodec.validate(info, 1) // 幂等通过
    }

    @Test
    fun bootGuardReasonMappingMatchesDesign74() {
        val expected = mapOf(
            VmdImageReason.NOT_AN_IMAGE to "NOT_AN_IMAGE",
            VmdImageReason.MANIFEST_INVALID to "NOT_AN_IMAGE",
            VmdImageReason.TRUNCATED to "CORRUPT",
            VmdImageReason.PAYLOAD_CORRUPT to "CORRUPT",
            VmdImageReason.CORRUPT to "CORRUPT",
            VmdImageReason.IO_ERROR to "CORRUPT",
            VmdImageReason.FORMAT_UNSUPPORTED to "FORMAT_UNSUPPORTED",
            VmdImageReason.ARCH_MISMATCH to "ARCH_MISMATCH",
            VmdImageReason.IMAGE_ID_INVALID to "IMAGE_ID_INVALID",
            VmdImageReason.SSH_CAPABILITY_MISSING to "SSH_CAPABILITY_MISSING",
            VmdImageReason.SSH_PORT_INVALID to "SSH_PORT_INVALID",
            VmdImageReason.APP_TOO_OLD to "APP_TOO_OLD",
            VmdImageReason.NO_SYSTEM_IMAGE to "NO_SYSTEM_IMAGE",
            VmdImageReason.RESET_REQUIRED to "RESET_REQUIRED",
        )
        for ((reason, bootGuard) in expected) {
            assertEquals("$reason 映射", bootGuard, reason.bootGuard)
        }
        try {
            VmdImageCodec.read(File(fixtures, "arch-amd64.img"))
            throw AssertionError("should reject")
        } catch (e: VmdImageException) {
            assertEquals("ARCH_MISMATCH", e.bootGuardReason)
        }
    }

    @Test
    fun decideResetTableMatchesDesign52() {
        val a = VmdImageCodec.ResetKey(
            rootfsSha256 = "aa",
            identity = "debian:trixie",
            contractVersion = 1L,
            distroInit = "systemd",
        )
        // 1. 内容优先：sha 相同即使 identity 不同 → same（永不重置）
        assertEquals(
            VmdImageCodec.ResetDecision.SAME,
            VmdImageCodec.decideReset(a.copy(identity = "alpine:3.24"), a),
        )
        // 2. identity 变化 → identity
        assertEquals(
            VmdImageCodec.ResetDecision.IDENTITY,
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "bb", identity = "alpine:3.24"), a),
        )
        // 3. contract.version 变化（硬判据）→ contract
        assertEquals(
            VmdImageCodec.ResetDecision.CONTRACT,
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "bb", contractVersion = 2L), a),
        )
        // 4. distro.init 变化（硬判据）→ init
        assertEquals(
            VmdImageCodec.ResetDecision.INIT,
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "bb", distroInit = "openrc"), a),
        )
        // 5. 全同但 sha 不同（同发行版升级）→ upgrade（不重置）
        assertEquals(
            VmdImageCodec.ResetDecision.UPGRADE,
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "cc"), a),
        )
        // image.log 引用的小写 code（§5.2："勿自造"）
        assertEquals(
            listOf("same", "identity", "contract", "init", "upgrade"),
            VmdImageCodec.ResetDecision.entries.map { it.code },
        )
        // resetKey() 与 footer/manifest 一致
        val info = read("valid-min.img")
        val key = info.resetKey()
        assertEquals(info.footer.rootfsSha256, key.rootfsSha256)
        assertEquals(info.manifest.image.identity, key.identity)
        assertEquals(info.manifest.contract.version, key.contractVersion)
        assertEquals(info.manifest.image.distroInit, key.distroInit)
    }

    @Test
    fun streamingSha256MatchesPythonHashlib() {
        for (name in listOf("rootfs.src", "kernel.src", "initrd.src", "manifest-min.json", "manifest-full.json")) {
            assertEquals(
                name,
                sidecar(File(fixtures, "sources/$name.sha256")),
                VmdImageCodec.sha256File(File(fixtures, "sources/$name").absolutePath),
            )
        }
    }

    // ------------------------------------------------------------------

    private fun assertNotNull(v: Any?) {
        assertTrue("expected non-null", v != null)
    }

    private fun assertNull(v: Any?) {
        assertTrue("expected null, got $v", v == null)
    }

    companion object {
        /** 依次尝试 -Dvmd.fixtures、相对路径与向上目录；找不到返回不存在的目录 → Assume skip。 */
        private fun locateFixtures(): File {
            System.getProperty("vmd.fixtures")?.let {
                val f = File(it)
                if (f.isDirectory) return f
            }
            var up: File? = File(".").absoluteFile
            repeat(8) {
                val dir = up ?: return@repeat
                val f = File(dir, "codec-selftest/fixtures")
                if (f.isDirectory) return f
                up = dir.parentFile
            }
            return File("__fixtures_not_found__")
        }
    }
}
