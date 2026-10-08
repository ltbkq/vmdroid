/*
 * VmdImageCodec 自测（纯 JVM，无 JUnit 依赖）。
 *
 * 直接调用 codec API 断言 IMAGE-FORMAT.md §8 测试向量 + DESIGN §5.2/§7.4。
 * 运行方式见 codec-selftest/README.md；fixtures 由 gen_fixtures.py 生成。
 */
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.FOOTER_SIZE
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.IMAGE_ID_PATTERN
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.MAGIC
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.MAGIC_TAIL_OFFSET
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.MANIFEST_FORMAT
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.MIB
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.REQUIRED_ARCH
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.ResetDecision
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec.ResetKey
import io.github.ltbkq.vmdroid.systemimage.VmdImageException
import io.github.ltbkq.vmdroid.systemimage.VmdImageReason
import java.io.File
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0

private fun must(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS  $name")
    } catch (t: Throwable) {
        failed++
        println("FAIL  $name — ${t.javaClass.simpleName}: ${t.message}")
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

private fun magicBytes(): ByteArray =
    MAGIC.toByteArray(Charsets.US_ASCII)

/** 期望 read() 以指定 reason 拒绝。 */
private fun expectReject(fixtures: File, name: String, expected: VmdImageReason, current: Long? = null) {
    check("REJECT $name → $expected") {
        try {
            VmdImageCodec.read(File(fixtures, name), current)
            throw AssertionError("expected VmdImageException($expected) but read() succeeded")
        } catch (e: VmdImageException) {
            must(
                e.reason == expected,
                "expected reason=$expected got ${e.reason} (bootGuard=${e.bootGuardReason}) msg=${e.message}",
            )
        }
    }
}

private fun expectVerifyReject(fixtures: File, name: String, block: (VmdImageCodec.ImageInfo) -> Unit) {
    check("REJECT(verify) $name → PAYLOAD_CORRUPT") {
        val info = VmdImageCodec.read(File(fixtures, name)) // read 应通过（payload sha 延迟校验）
        try {
            block(info)
            throw AssertionError("expected VmdImageException(PAYLOAD_CORRUPT)")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.PAYLOAD_CORRUPT, "got ${e.reason}: ${e.message}")
        }
    }
}

private fun locateFixtures(args: Array<String>): File {
    if (args.isNotEmpty()) {
        val f = File(args[0])
        require(f.isDirectory) { "fixtures dir not found: ${f.absolutePath}" }
        return f
    }
    System.getProperty("vmd.fixtures")?.let {
        val f = File(it)
        require(f.isDirectory) { "-Dvmd.fixtures=$it is not a directory" }
        return f
    }
    var up: File? = File(".").absoluteFile
    repeat(8) {
        val dir = up ?: return@repeat
        val f = File(dir, "codec-selftest/fixtures")
        if (f.isDirectory) return f
        up = dir.parentFile
    }
    error(
        "fixtures not found (run: python3 codec-selftest/gen_fixtures.py, " +
            "or pass dir as argv[0] / -Dvmd.fixtures=...)",
    )
}

fun main(args: Array<String>) {
    val fixtures = try {
        locateFixtures(args)
    } catch (t: Throwable) {
        System.err.println("SETUP FAIL: ${t.message}")
        exitProcess(2)
    }
    val tmp = File(System.getProperty("java.io.tmpdir"), "vmd-codec-selftest").apply {
        deleteRecursively()
        mkdirs()
    }

    // ================================================================
    // 正向：合法镜像
    // ================================================================
    check("valid-min: read() 成功，footer/manifest 全字段断言 (flags==0)") {
        val img = File(fixtures, "valid-min.img")
        val info = VmdImageCodec.read(File(fixtures, "valid-min.img"), 1)
        val f = info.footer
        must(f.magic == MAGIC && f.magicTail == MAGIC, "双 magic @0/@4088")
        must(f.formatVersion == 1, "formatVersion=${f.formatVersion}")
        must(f.footerSize == FOOTER_SIZE, "footerSize=${f.footerSize}")
        must(f.fileSize == img.length(), "fileSize=${f.fileSize} vs actual ${img.length()}")
        must(f.flags == 0, "flags=${f.flags} (§8: 无 kernel/initrd → flags==0)")
        must(f.rootfsOffset == 0L, "rootfs_offset=${f.rootfsOffset} 必须为 0")
        must(f.rootfsSize == 65536L, "rootfs_size=${f.rootfsSize}")
        must(f.dataLimit == img.length() - 4096, "dataLimit=${f.dataLimit}")

        val m = info.manifest
        must(m.format == MANIFEST_FORMAT, "format=${m.format}")
        must(m.image.id == "debian-minimal-arm64", "image.id=${m.image.id}")
        must(m.image.id != null && Regex(IMAGE_ID_PATTERN).matches(m.image.id), "image.id 匹配正则")
        must(m.image.arch == REQUIRED_ARCH, "arch=${m.image.arch}")
        must(m.image.identity == "debian:trixie", "identity=${m.image.identity}")
        must(m.image.distroInit == "systemd", "distro.init=${m.image.distroInit}")
        must(m.image.systemVersion == 34L, "system_version=${m.image.systemVersion}")
        must(m.image.displayName?.contains("最小化") == true, "UTF-8 显示名: ${m.image.displayName}")
        must(m.capabilities.ssh && m.capabilities.x11 && !m.capabilities.desktop, "capabilities")
        must(m.accounts.sshPort == 22L, "ssh_port=${m.accounts.sshPort}")
        must(m.accounts.ssh.size == 2 && m.accounts.ssh[1].user == "ltbkq" && m.accounts.ssh[1].sudo, "accounts.ssh")
        must(m.accounts.defaultUser == "ltbkq", "default_user=${m.accounts.defaultUser}")
        must(m.app.minVersionCode == 1L, "min_version_code=${m.app.minVersionCode}")
        must(m.contract.version == 1L && m.contract.markers.size == 5, "contract.version/markers")
        must(m.boot?.append == "console=ttyAMA0 mitigations=off", "boot.append（权威基础串）")
        must(m.checksums["rootfs_sha256"] == f.rootfsSha256, "manifest.checksums 与 footer 一致")

        // 未知字段前向兼容（§4：未知字段必须忽略，不拒绝）+ \uXXXX 转义解析
        val extra = m.unknown["extra_top"] as? Map<*, *>
        must(extra != null, "未知字段 extra_top 应保留")
        must(extra!!["esc"] == "中", "\\u4e2d 转义解析: ${extra["esc"]}")
        must((extra["nested"] as? List<*>)?.size == 3, "嵌套数组解析")
    }

    check("valid-min: 段布局（manifest 1MiB 对齐、footer=末 4096B、无重叠）") {
        val img = File(fixtures, "valid-min.img")
        val f = VmdImageCodec.read(img).footer
        must(f.manifestOffset % MIB == 0L, "manifest_offset=${f.manifestOffset} 未 1MiB 对齐")
        must(f.footerOffset == img.length() - 4096, "footerOffset=${f.footerOffset}")
        must(f.rootfsSize <= f.manifestOffset, "rootfs 与 manifest 不重叠")
        must(f.manifestOffset + f.manifestSize <= f.dataLimit, "manifest 须在 footer 之前")
    }

    check("valid-min: verifyPayloads() 通过；sha256File == python hashlib") {
        val img = File(fixtures, "valid-min.img")
        val info = VmdImageCodec.read(img)
        VmdImageCodec.verifyPayloads(info)
        val expected = sidecar(File(fixtures, "valid-min.img.sha256"))
        val actual = VmdImageCodec.sha256File(img.absolutePath)
        must(actual == expected, "sha256File=$actual, python=$expected")
    }

    // ================================================================
    // §8: footer 偏移重编号回归（seed 已于 R3 移除，72/124/172 必须是 manifest/kernel/initrd）
    // ================================================================
    check("footer 偏移重编号回归: manifest@72, kernel@124, initrd@172, 无 seed") {
        val b = ByteArray(FOOTER_SIZE)
        magicBytes().copyInto(b, 0)
        magicBytes().copyInto(b, MAGIC_TAIL_OFFSET)
        putU32(b, 8, 1)
        putU32(b, 12, FOOTER_SIZE)
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
        // 220..4087 reserved 保持全零（bytearray 默认）

        val f = VmdImageCodec.parseFooter(b)
        must(f.formatVersion == 1 && f.footerSize == FOOTER_SIZE, "format/footer_size 字段")
        must(f.fileSize == (1L shl 30), "file_size@16")
        must(f.rootfsOffset == 0L && f.rootfsSize == 0x1_0000L, "rootfs@24/32")
        must(f.rootfsSha256 == "11".repeat(32), "rootfs_sha256@40")
        must(f.manifestOffset == 0x0011_2233_4455_6677L, "manifest_offset 必须解析自偏移 72，实=${f.manifestOffset}")
        must(f.manifestSize == 0x1234L, "manifest_size@80")
        must(f.manifestSha256 == "22".repeat(32), "manifest_sha256@88")
        must(f.flags == 3 && f.hasKernel && f.hasInitrd, "flags@120 (掩码 0x3)")
        must(f.kernelOffset == 0x0102_0304_0506_0708L, "kernel_offset 必须解析自偏移 124，实=${f.kernelOffset}")
        must(f.kernelSize == 0x1112_1314_1516_1718L, "kernel_size@132")
        must(f.kernelSha256 == "33".repeat(32), "kernel_sha256@140")
        must(f.initrdOffset == 0x2122_2324_2526_2728L, "initrd_offset 必须解析自偏移 172，实=${f.initrdOffset}")
        must(f.initrdSize == 0x3132_3334_3536_3738L, "initrd_size@180")
        must(f.initrdSha256 == "44".repeat(32), "initrd_sha256@188")
        // 72–119 现为 manifest_* 字段：若实现回退到含 seed 的旧布局，上述断言必然失败
    }

    check("parseFooter: 非法输入拒绝 (magic→NOT_AN_IMAGE, 尺寸→FORMAT_UNSUPPORTED, 负 u64→CORRUPT)") {
        try {
            VmdImageCodec.parseFooter(ByteArray(FOOTER_SIZE))
            throw AssertionError("zero magic should be rejected")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.NOT_AN_IMAGE, "got ${e.reason}")
        }
        try {
            VmdImageCodec.parseFooter(ByteArray(100))
            throw AssertionError("size != 4096 should be rejected")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.FORMAT_UNSUPPORTED, "got ${e.reason}")
        }
        val b = ByteArray(FOOTER_SIZE)
        magicBytes().copyInto(b, 0)
        magicBytes().copyInto(b, MAGIC_TAIL_OFFSET)
        putU64(b, 16, Long.MIN_VALUE) // 无符号 ≥ 2^63 → 落成负数
        try {
            VmdImageCodec.parseFooter(b)
            throw AssertionError("negative (>=2^63) u64 should be rejected")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.CORRUPT, "got ${e.reason}")
        }
    }

    // ================================================================
    // 正向：含 kernel/initrd 的镜像 + 提取
    // ================================================================
    check("valid-full: flags==0x3，kernel/initrd/manifest 1MiB 对齐，长度与源文件一致") {
        val info = VmdImageCodec.read(File(fixtures, "valid-full.img"))
        val f = info.footer
        must(f.flags == 0x3, "flags=0x${f.flags.toString(16)}")
        must(f.hasKernel && f.hasInitrd, "flags bit0/bit1 置位")
        must(f.kernelOffset == MIB, "kernel_offset=${f.kernelOffset} (期望 1MiB)")
        must(f.kernelSize == MIB, "kernel_size=${f.kernelSize} (§2: ≥1MiB)")
        must(f.initrdOffset == 2 * MIB, "initrd_offset=${f.initrdOffset}")
        must(f.initrdSize == 8192L, "initrd_size=${f.initrdSize}")
        must(f.manifestOffset == 3 * MIB, "manifest_offset=${f.manifestOffset}")
        must(f.kernelOffset % MIB == 0L && f.initrdOffset % MIB == 0L && f.manifestOffset % MIB == 0L, "1MiB 对齐")
        must(f.kernelSha256 == VmdImageCodec.sha256(File(fixtures, "sources/kernel.src").readBytes()), "footer.kernel_sha256 == sha(源文件)")
        must(f.initrdSha256 == VmdImageCodec.sha256(File(fixtures, "sources/initrd.src").readBytes()), "footer.initrd_sha256 == sha(源文件)")
    }

    check("valid-full: verifyPayloads() 通过；sha256File == python hashlib") {
        val img = File(fixtures, "valid-full.img")
        VmdImageCodec.verifyPayloads(VmdImageCodec.read(img))
        val expected = sidecar(File(fixtures, "valid-full.img.sha256"))
        val actual = VmdImageCodec.sha256File(img.absolutePath)
        must(actual == expected, "sha256File=$actual, python=$expected")
    }

    check("extract: kernel/initrd/rootfs 提取后 sha256 == python 源文件") {
        val info = VmdImageCodec.read(File(fixtures, "valid-full.img"))
        for (name in listOf("rootfs", "kernel", "initrd")) {
            val out = File(tmp, "$name.out")
            VmdImageCodec.extractPayload(info, name, out)
            val expected = sidecar(File(fixtures, "sources/$name.src.sha256"))
            val actual = VmdImageCodec.sha256File(out.absolutePath)
            must(actual == expected, "$name: 提取 sha256=$actual, python 源=$expected")
            val expectLen = when (name) {
                "rootfs" -> info.footer.rootfsSize
                "kernel" -> info.footer.kernelSize
                else -> info.footer.initrdSize
            }
            must(out.length() == expectLen, "$name: 提取长度=${out.length()}, 期望=$expectLen")
            out.delete()
        }
    }

    check("extract: 未知段名 / 不存在的段 → IllegalArgumentException") {
        val info = VmdImageCodec.read(File(fixtures, "valid-min.img")) // flags=0，无 kernel
        for (bad in listOf("kernel", "initrd", "bogus")) {
            try {
                VmdImageCodec.extractPayload(info, bad, File(tmp, "should-not-exist.out"))
                throw AssertionError("extract('$bad') should throw")
            } catch (e: IllegalArgumentException) {
                must(!File(tmp, "should-not-exist.out").exists(), "失败时不得残留输出文件")
            }
        }
    }

    // ================================================================
    // §4 字段缺省解释
    // ================================================================
    check("minimal-manifest: §4 缺省解释 (capabilities→ssh:true, accounts→root/22, contract→1)") {
        val m = VmdImageCodec.read(File(fixtures, "minimal-manifest.img"), 1).manifest
        must(m.capabilities.ssh && m.capabilities.x11 && m.capabilities.containers, "缺省 capabilities = {ssh,x11,containers}")
        must(!m.capabilities.desktop && !m.capabilities.downloadsShare && !m.capabilities.usbPassthroughHost, "其余能力缺省 false")
        must(m.accounts.ssh.size == 1 && m.accounts.ssh[0].user == "root", "缺省 accounts = 仅 root")
        must(m.accounts.defaultUser == "root", "缺省 default_user=root")
        must(m.accounts.sshPort == 22L, "缺省 ssh_port=22")
        must(m.contract.version == 1L, "缺省 contract.version=1")
        must(m.app.minVersionCode == null, "缺省无 app.min_version_code 约束")
        must(m.image.identity == null, "缺省 identity=null（该向量只带必需字段）")
    }

    check("no-ssh-port: accounts 块存在但缺 ssh_port → 缺省 22，读取通过") {
        val info = VmdImageCodec.read(File(fixtures, "no-ssh-port.img"), 1)
        must(info.manifest.accounts.sshPort == 22L, "ssh_port=${info.manifest.accounts.sshPort}")
        must(info.manifest.capabilities.ssh, "capabilities.ssh")
    }

    // ================================================================
    // 负向：逐 reason（§8 测试向量表 + §7.4 reason 码）
    // ================================================================
    // NOT_AN_IMAGE（§7.4 #3）
    expectReject(fixtures, "random-junk.img", VmdImageReason.NOT_AN_IMAGE)
    expectReject(fixtures, "zip-as-img.img", VmdImageReason.NOT_AN_IMAGE)           // .img 扩展名但内容是 zip
    expectReject(fixtures, "too-small.img", VmdImageReason.NOT_AN_IMAGE)             // size < 4096
    expectReject(fixtures, "footer-overwritten.img", VmdImageReason.NOT_AN_IMAGE)    // 尾部 4KiB 覆写 → magic 失败
    expectReject(fixtures, "truncate-1byte.img", VmdImageReason.NOT_AN_IMAGE)        // 截断 1B → footer 窗口移位，magic 先失败
    check("bare-squashfs: 拒绝 (NOT_AN_IMAGE) 且提示 mkimg 封装") {
        try {
            VmdImageCodec.read(File(fixtures, "bare-squashfs.img"))
            throw AssertionError("bare squashfs should be rejected")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.NOT_AN_IMAGE, "got ${e.reason}")
            must(e.message?.contains("mkimg") == true, "应给出 mkimg 封装指引: ${e.message}")
        }
    }
    // TRUNCATED（截断/拼接；§5 file_size 检查）
    expectReject(fixtures, "file-size-mismatch.img", VmdImageReason.TRUNCATED)       // footer.file_size ≠ 实际
    expectReject(fixtures, "spliced-footer.img", VmdImageReason.TRUNCATED)           // 拼接另一份 footer
    expectReject(fixtures, "tiny-magic.img", VmdImageReason.TRUNCATED)               // magic 匹配但 size<8192（§3 规则 1）
    // FORMAT_UNSUPPORTED（§7.4 #4）
    expectReject(fixtures, "format-version-2.img", VmdImageReason.FORMAT_UNSUPPORTED)
    expectReject(fixtures, "footer-size-bad.img", VmdImageReason.FORMAT_UNSUPPORTED)
    expectReject(fixtures, "unknown-flags.img", VmdImageReason.FORMAT_UNSUPPORTED)   // 保留位（掩码 0x3 之外）
    // CORRUPT（flags↔段 / 边界 / 溢出 / 段序）
    expectReject(fixtures, "flags-kernel-mismatch.img", VmdImageReason.CORRUPT)      // bit0 置位但 kernel_size==0
    expectReject(fixtures, "flags-initrd-mismatch.img", VmdImageReason.CORRUPT)      // bit1 置位但 initrd_size==0
    expectReject(fixtures, "rootfs-offset-nonzero.img", VmdImageReason.CORRUPT)      // rootfs_offset != 0
    expectReject(fixtures, "manifest-out-of-range.img", VmdImageReason.CORRUPT)      // 越界（B-R1-10）
    expectReject(fixtures, "manifest-overlap.img", VmdImageReason.CORRUPT)           // 段乱序/重叠
    expectReject(fixtures, "kernel-overflow.img", VmdImageReason.CORRUPT)            // 边界溢出：offset > MAX - size
    // MANIFEST_INVALID（→ bootGuard NOT_AN_IMAGE）
    expectReject(fixtures, "manifest-format-wrong.img", VmdImageReason.MANIFEST_INVALID)
    expectReject(fixtures, "bad-manifest-json.img", VmdImageReason.MANIFEST_INVALID)
    // ARCH_MISMATCH（§7.4 #6）
    expectReject(fixtures, "arch-amd64.img", VmdImageReason.ARCH_MISMATCH)
    // IMAGE_ID_INVALID（§7.4 #7，防 -drive 注入）
    expectReject(fixtures, "bad-image-id.img", VmdImageReason.IMAGE_ID_INVALID)
    // SSH_CAPABILITY_MISSING（§7.4 #8）
    expectReject(fixtures, "ssh-cap-false.img", VmdImageReason.SSH_CAPABILITY_MISSING)
    expectReject(fixtures, "ssh-cap-missing-key.img", VmdImageReason.SSH_CAPABILITY_MISSING) // fail-closed
    // SSH_PORT_INVALID（§7.4 #9）
    expectReject(fixtures, "ssh-port-2222.img", VmdImageReason.SSH_PORT_INVALID)
    expectReject(fixtures, "ssh-port-bad-type.img", VmdImageReason.SSH_PORT_INVALID) // 类型非整数 fail-closed
    // APP_TOO_OLD（§7.4 #5）
    expectReject(fixtures, "app-too-old.img", VmdImageReason.APP_TOO_OLD, current = 1)
    // PAYLOAD_CORRUPT（read 期 manifest sha 不匹配）
    expectReject(fixtures, "manifest-flip.img", VmdImageReason.PAYLOAD_CORRUPT)

    check("app-too-old: currentVersionCode=999999 → 通过（仅版本比较拒绝）") {
        val info = VmdImageCodec.read(File(fixtures, "app-too-old.img"), 999999)
        must(info.manifest.app.minVersionCode == 999999L, "min=${info.manifest.app.minVersionCode}")
    }

    // ================================================================
    // payload sha 延迟校验（§5 校验时机：手动"校验"动作）
    // ================================================================
    expectVerifyReject(fixtures, "kernel-flip.img") { VmdImageCodec.verifyPayloads(it) }
    expectVerifyReject(fixtures, "initrd-flip.img") { VmdImageCodec.verifyPayloads(it) }

    check("kernel-flip: extractPayload 拒绝且不落盘坏文件") {
        val info = VmdImageCodec.read(File(fixtures, "kernel-flip.img")) // read 不查 kernel sha（延迟）
        val out = File(tmp, "kernel-flip.out")
        try {
            VmdImageCodec.extractPayload(info, "kernel", out)
            throw AssertionError("extract should refuse corrupt kernel")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.PAYLOAD_CORRUPT, "got ${e.reason}: ${e.message}")
        }
        must(!out.exists(), "坏载荷不得落盘（R-16：坏内核不能上机）")
    }

    // ================================================================
    // validate() 激活前复检 + §7.4 reason 映射
    // ================================================================
    check("validate(): 携带新 versionCode 复检 → APP_TOO_OLD") {
        val info = VmdImageCodec.read(File(fixtures, "valid-min.img"), 1)
        try {
            VmdImageCodec.validate(info, 0) // min_version_code=1 > 0
            throw AssertionError("validate(info, 0) should reject")
        } catch (e: VmdImageException) {
            must(e.reason == VmdImageReason.APP_TOO_OLD, "got ${e.reason}")
        }
        VmdImageCodec.validate(info, 1) // 幂等通过
    }

    check("§7.4 BootGuard reason 映射表（image.log bootguard_reject 用）") {
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
            must(reason.bootGuard == bootGuard, "$reason.bootGuard=${reason.bootGuard}, 期望 $bootGuard")
        }
        // 异常对象上携带的映射
        try {
            VmdImageCodec.read(File(fixtures, "arch-amd64.img"))
            throw AssertionError("should reject")
        } catch (e: VmdImageException) {
            must(e.bootGuardReason == "ARCH_MISMATCH", "e.bootGuardReason=${e.bootGuardReason}")
        }
    }

    // ================================================================
    // DESIGN §5.2 重置判定全表（§13.1 归属本 codec 单测）
    // ================================================================
    check("decideReset: §5.2 决策全表 (same/identity/contract/init/upgrade + code 字段)") {
        val a = ResetKey(rootfsSha256 = "aa", identity = "debian:trixie", contractVersion = 1L, distroInit = "systemd")
        // 1. 内容优先：sha 相同即使 identity 不同 → same（永不重置）
        must(
            VmdImageCodec.decideReset(a.copy(identity = "alpine:3.24"), a) == ResetDecision.SAME,
            "内容优先：rootfs_sha256 相同必须 same",
        )
        // 2. identity 不同 → identity
        must(
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "bb", identity = "alpine:3.24"), a) == ResetDecision.IDENTITY,
            "identity 变化 → identity",
        )
        // 3. contract.version 变化（硬判据）→ contract
        must(
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "bb", contractVersion = 2L), a) == ResetDecision.CONTRACT,
            "contract.version 变化 → contract",
        )
        // 4. distro.init 变化（硬判据）→ init
        must(
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "bb", distroInit = "openrc"), a) == ResetDecision.INIT,
            "distro.init 变化 → init",
        )
        // 5. 以上全同但 sha 不同（同发行版升级）→ upgrade（不重置）
        must(
            VmdImageCodec.decideReset(a.copy(rootfsSha256 = "cc"), a) == ResetDecision.UPGRADE,
            "同 identity 升级 → upgrade",
        )
        // image.log 引用的小写 code（§5.2："勿自造"）
        must(
            listOf("same", "identity", "contract", "init", "upgrade") ==
                ResetDecision.entries.map { it.code },
            "decision code 必须是 §5.2 表中的小写值",
        )
        // ImageInfo.resetKey() 与 manifest/footer 一致
        val info = VmdImageCodec.read(File(fixtures, "valid-min.img"))
        val key = info.resetKey()
        must(
            key.rootfsSha256 == info.footer.rootfsSha256 &&
                key.identity == info.manifest.image.identity &&
                key.contractVersion == info.manifest.contract.version &&
                key.distroInit == info.manifest.image.distroInit,
            "resetKey() 组装",
        )
    }

    // ================================================================
    // sources 交叉验证（python hashlib vs Kotlin 流式 sha256）
    // ================================================================
    check("sources: sha256File == python hashlib (rootfs/kernel/initrd/manifest ×2)") {
        for (name in listOf("rootfs.src", "kernel.src", "initrd.src", "manifest-min.json", "manifest-full.json")) {
            val expected = sidecar(File(fixtures, "sources/$name.sha256"))
            val actual = VmdImageCodec.sha256File(File(fixtures, "sources/$name").absolutePath)
            must(actual == expected, "$name: kotlin=$actual, python=$expected")
        }
    }

    tmp.deleteRecursively()
    println()
    println("== SelfTest: $passed passed, $failed failed ==")
    if (failed > 0) exitProcess(1)
    exitProcess(0)
}
