/*
 * 工位 F 自测 v2（纯 JVM，无 JUnit 依赖）：VmdDiskCodec + EspFat32（IMAGE-FORMAT-v2）。
 *
 * 三段式：
 *   1. 合成负样本（诊断阶梯/小文件/GPT 头缺失）+ mixed-endian GUID 往返 —— 不依赖任何夹具；
 *   2. 夹具对账：消费 codec-selftest/fixtures/v2/fixtures-v2.json（另一工位并行生成，只读），
 *      expect=ok → read() 通过 + 字段可读 + verifyFull；expect=reject → read()/verifyFull 拒绝
 *      且 reason 映射一致。**清单未落盘时打印 SKIP 而不失败**（并行施工中，冒烟模式兜底）；
 *   3. 冒烟模式：用环境变量/参数指定的 mkdisk.sh 现场产物跑全流程（read/verifyFull/
 *      FAT32 读 ESP 五项文件/grub.cfg partuuid/volume id 对账）。
 *
 * 另含 ESP FAT32 单元冒烟（mkfs.vfat + mcopy 现场造 FAT32，含 LFN 长名；缺工具则 SKIP）。
 *
 * 运行方式与编译命令见 systemimage-selftest/README.md §4。
 */
import io.github.ltbkq.vmdroid.systemimage.EspFat32
import io.github.ltbkq.vmdroid.systemimage.MiniJson
import io.github.ltbkq.vmdroid.systemimage.VmdDiskCodec
import io.github.ltbkq.vmdroid.systemimage.VmdDiskException
import io.github.ltbkq.vmdroid.systemimage.VmdDiskReason
import java.io.File
import java.security.MessageDigest
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0
private var skipped = 0
private var warned = 0

@OptIn(ExperimentalContracts::class)
private fun must(cond: Boolean, msg: String) {
    contract { returns() implies cond }   // 供后续 null 检查后的智能转换
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

private fun skip(name: String, why: String) {
    skipped++
    println("SKIP  $name — $why")
}

private fun warn(name: String, why: String) {
    warned++
    println("WARN  $name — $why")
}

private fun sha256(b: ByteArray): String =
    VmdDiskCodec.hex(MessageDigest.getInstance("SHA-256").digest(b))

private lateinit var tmpRoot: File
private var tmpSeq = 0

private fun tmpFile(name: String): File {
    val d = File(tmpRoot, "t-${tmpSeq++}")
    d.mkdirs()
    return File(d, name)
}

// ======================================================================
// 1. 合成负样本（§5 诊断阶梯 + 尺寸门槛 + GPT 头缺失）
// ======================================================================

private fun expectReject(file: File, reason: VmdDiskException.() -> Unit = {}) {
    val thrown = runCatching { VmdDiskCodec.read(file, 1L) }.exceptionOrNull()
    must(thrown is VmdDiskException, "应抛 VmdDiskException，实际 $thrown")
    (thrown as VmdDiskException).reason()
}

private fun testSyntheticLadder() {
    check("阶梯: 文件 100 B → NOT_AN_IMAGE") {
        val f = tmpFile("too-small.img")
        f.writeBytes(ByteArray(100))
        expectReject(f) { must(reason.name == "NOT_AN_IMAGE", "reason=${reason.name} msg=$message") }
    }

    check("阶梯: 全零垃圾 80 KiB（无保护性 MBR、无 v1 尾魔数）→ NOT_AN_IMAGE") {
        val f = tmpFile("junk.img")
        f.writeBytes(ByteArray(80 * 1024))
        expectReject(f) { must(reason.name == "NOT_AN_IMAGE", "reason=${reason.name} msg=$message") }
    }

    check("阶梯: 尾部 VMDIMG01（v1 家族）→ LEGACY_FORMAT") {
        val f = tmpFile("v1-tail.img")
        val b = ByteArray(80 * 1024)
        "VMDIMG01".toByteArray().copyInto(b, destinationOffset = b.size - 4096)
        f.writeBytes(b)
        expectReject(f) { must(reason.name == "LEGACY_FORMAT", "reason=${reason.name} msg=$message") }
    }

    check("阶梯: 偏移 0 hsqs（裸 squashfs）→ LEGACY_FORMAT") {
        val f = tmpFile("bare-squashfs.img")
        val b = ByteArray(80 * 1024)
        "hsqs".toByteArray().copyInto(b)
        f.writeBytes(b)
        expectReject(f) { must(reason.name == "LEGACY_FORMAT", "reason=${reason.name} msg=$message") }
    }

    check("阶梯: 有保护性 MBR 但 LBA1 无 EFI PART → CORRUPT（不是 NOT_AN_IMAGE）") {
        val f = tmpFile("mbr-only.img")
        val b = ByteArray(80 * 1024)
        b[450] = 0xEE.toByte()
        b[510] = 0x55.toByte()
        b[511] = 0xAA.toByte()
        f.writeBytes(b)
        expectReject(f) { must(reason.name == "CORRUPT", "reason=${reason.name} msg=$message") }
    }
}

// ======================================================================
// 1b. mixed-endian GUID（§2.2：uuid.UUID(bytes_le) 语义；前 3 字段小端）
// ======================================================================

private fun testGuidRoundTrip() {
    check("GUID: 固定 meta GUID 往返（text → 16B → text）") {
        val bytes = VmdDiskCodec.guidToBytes(VmdDiskCodec.META_PARTITION_GUID)
        must(bytes != null, "guidToBytes 返回 null")
        must(
            VmdDiskCodec.guidToText(bytes) == VmdDiskCodec.META_PARTITION_GUID,
            "往返不一致：${VmdDiskCodec.guidToText(bytes)}",
        )
    }

    check("GUID: mixed-endian 已知向量 00112233-4455-6677-8899-aabbccddeeff") {
        val bytes = VmdDiskCodec.guidToBytes("00112233-4455-6677-8899-aabbccddeeff")
        must(bytes != null, "guidToBytes 返回 null")
        val expect = byteArrayOf(
            0x33, 0x22, 0x11, 0x00, 0x55, 0x44, 0x77, 0x66,
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        must(bytes.contentEquals(expect), "字节序不符：${VmdDiskCodec.hex(bytes)}")
    }

    check("GUID: 大写输入归一 + 非法文本返回 null") {
        val upper = VmdDiskCodec.guidToBytes("564D444D-4554-4132-8000-000000000001")
        val lower = VmdDiskCodec.guidToBytes(VmdDiskCodec.META_PARTITION_GUID)
        must(upper != null && upper.contentEquals(lower), "大写输入应与小写等价")
        must(VmdDiskCodec.guidToBytes("not-a-guid") == null, "非法文本应返回 null")
        must(VmdDiskCodec.guidToBytes("564d444d45544132800000000001") == null, "长度不足应返回 null")
    }
}

// ======================================================================
// 2. EspFat32 单元冒烟（mkfs.vfat + mcopy 现场造 FAT32：8.3 + LFN + 子目录）
// ======================================================================

private fun runTool(vararg cmd: String) {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    val code = p.waitFor()
    must(code == 0, "命令失败($code): ${cmd.joinToString(" ")}\n$out")
}

private fun toolAvailable(name: String): Boolean = try {
    val p = ProcessBuilder("sh", "-c", "command -v $name").start()
    val ok = p.waitFor() == 0
    p.inputStream.readBytes()
    ok
} catch (e: Exception) {
    false
}

private fun testFat32WithMkfs() {
    val name = "FAT32: mkfs.vfat+mcopy 现场镜像（8.3 大小写不敏感 + LFN 长名 + 子目录）"
    if (!toolAvailable("mkfs.vfat") || !toolAvailable("mcopy")) {
        skip(name, "环境缺 mkfs.vfat/mcopy")
        return
    }
    check(name) {
        val img = tmpFile("fat32.img")
        java.io.RandomAccessFile(img, "rw").use { it.setLength(64L * 1024 * 1024) }
        runTool("mkfs.vfat", "-F", "32", "-n", "VMDTEST", img.absolutePath)

        // 本地文件树：8.3 短名 + >8.3 的 LFN 名 + 子目录
        val src = img.parentFile!!
        val shortTxt = File(src, "SHORT.TXT").apply { writeText("hello-83") }
        val lfnName = "a-very-long-file-name-for-lfn-check.txt"
        val lfnTxt = File(src, lfnName).apply { writeText("hello-lfn") }
        val subDir = File(src, "hello.bin").apply { writeBytes(ByteArray(256) { (it * 7 and 0xFF).toByte() }) }
        runTool("mmd", "-i", img.absolutePath, "::/subdir")
        runTool("mcopy", "-i", img.absolutePath, shortTxt.absolutePath, "::/SHORT.TXT")
        runTool("mcopy", "-i", img.absolutePath, lfnTxt.absolutePath, "::$lfnName")
        runTool("mcopy", "-i", img.absolutePath, subDir.absolutePath, "::/subdir/hello.bin")

        EspFat32.open(img, 0, img.length()).use { fat ->
            val root = fat.list("/")
            must(root != null, "根目录应可列")
            val names = root.map { it.name.lowercase() }.toSet()
            must("short.txt" in names, "缺 8.3 项，实际：$names")
            must(lfnName in names, "缺 LFN 项，实际：$names")
            must("subdir" in names, "缺子目录，实际：$names")

            // 8.3：mtools 盘上存大写 SHORT.TXT → 断言小写路径可读（大小写不敏感）
            must(fat.readFile("/short.txt")?.toString(Charsets.UTF_8) == "hello-83", "8.3 内容不符")
            // LFN：整名读回
            must(fat.readFile("/$lfnName")?.toString(Charsets.UTF_8) == "hello-lfn", "LFN 内容不符")
            // 子目录 + 二进制
            val bin = fat.readFile("/subdir/hello.bin")
            must(bin != null && bin.contentEquals(subDir.readBytes()), "子目录二进制内容不符")
            // 子目录列表
            must(fat.list("/subdir")?.any { it.name.equals("hello.bin", true) } == true, "子目录列表缺 hello.bin")
            // 不存在 → null；目录当文件读 → null
            must(fat.readFile("/missing.txt") == null, "缺失文件应返回 null")
            must(fat.readFile("/subdir") == null, "目录当文件读应返回 null")
            // 卷标不作为目录项返回
            must(fat.list("/")?.none { it.name.equals("VMDTEST", true) } != false, "卷标不应出现在目录列表")
        }
    }
}

// ======================================================================
// 3. 夹具对账（codec-selftest/fixtures/v2/fixtures-v2.json，只读消费）
// ======================================================================

private data class Fixture(
    val name: String,
    val file: String,
    val expect: String,          // "ok" | "reject"
    val reason: String?,         // reject 期期望的 VmdDiskReason 名
    val specRef: String?,
    val verifyOutput: String?,
)

private var currentVersionCode = 1L
private var skipFullVerify = false
private val reasonStats = LinkedHashMap<String, Int>()

private fun bumpReason(r: String) {
    reasonStats[r] = (reasonStats[r] ?: 0) + 1
}

private fun loadFixtures(manifest: File): List<Fixture> {
    val doc = MiniJson.parseObject(manifest.readText(Charsets.UTF_8))
    val raw = doc["fixtures"] as? List<*>
        ?: throw IllegalStateException("清单缺 fixtures 数组")
    return raw.map { el ->
        val m = el as? Map<*, *> ?: throw IllegalStateException("fixtures 项非对象：$el")
        Fixture(
            name = MiniJson.str(m, "name") ?: "<unnamed>",
            file = MiniJson.str(m, "file") ?: throw IllegalStateException("夹具缺 file：$m"),
            expect = MiniJson.str(m, "expect") ?: throw IllegalStateException("夹具缺 expect：$m"),
            reason = MiniJson.str(m, "reason"),
            specRef = MiniJson.str(m, "spec_ref"),
            verifyOutput = MiniJson.str(m, "verify_output"),
        )
    }
}

/**
 * 夹具 reason → v2 reason 集合映射。
 *
 * 清单契约的 `reason` 字段由夹具生成侧以 **mkdisk.sh --verify（oracle，D11）实录的 FAIL reason**
 * 填写（见 fixtures-v2.json 顶层 note/oracle），不保证是 §5/§8 的 11 项枚举名，因此本函数：
 *   1. 若 reason 恰为枚举名（如 "CORRUPT"）→ 直接采用（契约原义）；
 *   2. 否则按 oracle 文案前缀映射（与 mkdisk verify_img 的 fail 分支一一对应）；
 *   3. 无法识别（未来新增文案）→ null，此时仅断言 read/verifyFull 拒绝、不比对具体 reason。
 */
private fun expectedReasonOf(fx: Fixture): VmdDiskReason? {
    val r = fx.reason ?: return null
    VmdDiskReason.entries.firstOrNull { it.name == r }?.let { return it }
    return when {
        r.startsWith("LEGACY_FORMAT") -> VmdDiskReason.LEGACY_FORMAT          // stage=ladder
        r.startsWith("NOT_AN_IMAGE") -> VmdDiskReason.NOT_AN_IMAGE            // stage=ladder
        r.startsWith("FORMAT_UNSUPPORTED") -> VmdDiskReason.FORMAT_UNSUPPORTED // stage=meta
        r.startsWith("format_version=") -> VmdDiskReason.FORMAT_UNSUPPORTED   // stage=manifest
        r.startsWith("format") -> VmdDiskReason.NOT_AN_IMAGE                  // manifest.format 非本格式
        r.startsWith("arch=") -> VmdDiskReason.ARCH_MISMATCH
        r.startsWith("contract.version") -> VmdDiskReason.CONTRACT_VERSION
        r.startsWith("capabilities.ssh") -> VmdDiskReason.SSH_CAPABILITY_MISSING
        r.startsWith("accounts.ssh_port") -> VmdDiskReason.SSH_PORT_INVALID
        r.startsWith("image.id") -> VmdDiskReason.IMAGE_ID_INVALID
        r.startsWith("boot.mode") -> VmdDiskReason.BOOT_MODE_UNSUPPORTED
        r.startsWith("app.min_version_code") -> VmdDiskReason.CORRUPT    // 必需字段缺失（2026-10-12 对齐 §4.2 拍板）
        // 其余（gpt/meta/manifest sha/layout/full 阶段的结构失败）→ CORRUPT
        else -> VmdDiskReason.CORRUPT
    }
}

private fun runFixture(base: File, fx: Fixture) {
    val label = "fx:${fx.name}"
    check(label) {
        val f = File(base, fx.file)
        must(f.isFile, "夹具文件缺失：${fx.file}（相对 ${base}）")
        var info: VmdDiskCodec.ImageInfo? = null
        var thrown: VmdDiskException? = null
        try {
            info = VmdDiskCodec.read(f, currentVersionCode)
        } catch (e: VmdDiskException) {
            thrown = e
        }

        if (fx.expect == "ok") {
            must(thrown == null, "应解析成功，实际 ${thrown?.reason}：${thrown?.message}")
            val i = info!!
            val m = i.manifest
            // 各字段可读（§5 阶段 3 通过后的基本断言）
            must(m.image.id != null && Regex(VmdDiskCodec.IMAGE_ID_PATTERN).matches(m.image.id), "image.id 不可读/非法")
            must(m.image.arch == VmdDiskCodec.REQUIRED_ARCH, "image.arch=${m.image.arch}")
            must(m.contract.version == VmdDiskCodec.REQUIRED_CONTRACT_VERSION, "contract.version=${m.contract.version}")
            must(m.capabilities.ssh, "capabilities.ssh 应为 true")
            must(m.accounts.sshPort == VmdDiskCodec.REQUIRED_SSH_PORT, "ssh_port=${m.accounts.sshPort}")
            must(m.boot?.mode == VmdDiskCodec.REQUIRED_BOOT_MODE, "boot.mode=${m.boot?.mode}")
            must(i.meta.flags == VmdDiskCodec.FLAGS_MASK, "meta.flags=0x${i.meta.flags.toString(16)}")
            must(i.meta.formatVersion == 2L, "meta.format_version=${i.meta.formatVersion}")
            must(i.meta.fileSize == f.length(), "meta.file_size=${i.meta.fileSize} != ${f.length()}")
            must(i.metaPartition.uniqueGuid == VmdDiskCodec.META_PARTITION_GUID, "meta 分区 GUID 错位")
            must(i.partitions.last() == i.rootPartition, "root 应为最后分区")
            val pu = m.disk?.root?.partuuid
            must(
                pu != null && pu.lowercase().replace("-", "") == i.rootPartition.uniqueGuid.replace("-", ""),
                "disk.root.partuuid=$pu 与 GPT ${i.rootPartition.uniqueGuid} 不符",
            )
            must(
                (m.checksums["root_sha256"] ?: "").equals(i.meta.rootSha256, ignoreCase = true),
                "checksums.root_sha256 与 meta 不一致",
            )
            if (fx.verifyOutput != null && !fx.verifyOutput.contains("VERIFY_OK")) {
                warn("$label verify_output 无 VERIFY_OK", fx.verifyOutput.take(120))
            }
            if (!skipFullVerify) {
                VmdDiskCodec.verifyFull(i)   // 阶段 4：root 分区 sha256
            }
        } else if (fx.expect == "reject") {
            var rej: VmdDiskException? = thrown
            if (rej == null) {
                // read 通过（如 §9 向量 10 root 区单字节翻转）→ 阶段 4 必须拒
                val t2 = runCatching { VmdDiskCodec.verifyFull(info!!) }.exceptionOrNull()
                if (t2 !is VmdDiskException) {
                    throw AssertionError("应被拒绝（期望 ${fx.reason ?: "reject"}）：read+verifyFull 均通过")
                }
                rej = t2
            }
            val got = rej.reason.name
            bumpReason(got)
            val expected = expectedReasonOf(fx)
            if (expected != null) {
                must(
                    got == expected.name,
                    "reason 期望 ${expected.name}（oracle: ${fx.reason}），实际 $got：${rej.message}",
                )
            }
        } else {
            throw AssertionError("未知 expect=${fx.expect}（契约仅 ok|reject）")
        }
    }
}

private fun runFixtures(manifest: File) {
    val fixtures = loadFixtures(manifest)
    val base = manifest.parentFile
    println("夹具清单: ${manifest.absolutePath}（${fixtures.size} 条，versionCode=$currentVersionCode）")
    for (fx in fixtures) runFixture(base, fx)
    val okCount = fixtures.count { it.expect == "ok" }
    val rejectCount = fixtures.count { it.expect == "reject" }
    println(
        "FIXTURES: total=${fixtures.size} ok=$okCount reject=$rejectCount " +
            "(full 校验${if (skipFullVerify) "跳过" else "开启"})",
    )
    if (reasonStats.isNotEmpty()) {
        println("REJECT-REASONS: " + reasonStats.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }
}

// ======================================================================
// 4. 冒烟模式（mkdisk.sh 现场产物；无夹具清单时的兜底路径）
// ======================================================================

private fun testSmoke(img: File) {
    check("smoke: read() 阶段 1–3 通过 + 字段可读") {
        val info = VmdDiskCodec.read(img, currentVersionCode)
        val m = info.manifest
        must(m.image.id != null && Regex(VmdDiskCodec.IMAGE_ID_PATTERN).matches(m.image.id), "image.id 不可读")
        must(m.image.arch == VmdDiskCodec.REQUIRED_ARCH, "arch=${m.image.arch}")
        must(m.contract.version == VmdDiskCodec.REQUIRED_CONTRACT_VERSION, "contract.version=${m.contract.version}")
        must(m.capabilities.ssh, "capabilities.ssh 应为 true")
        must(m.accounts.sshPort == VmdDiskCodec.REQUIRED_SSH_PORT, "ssh_port=${m.accounts.sshPort}")
        must(m.boot?.mode == VmdDiskCodec.REQUIRED_BOOT_MODE, "boot.mode=${m.boot?.mode}")
        must(m.app.minVersionCode != null, "app.min_version_code 不可读")
        must(m.image.contentId?.startsWith("sha256:") == true, "image.content_id=${m.image.contentId}")
        must(info.meta.flags == VmdDiskCodec.FLAGS_MASK, "flags=0x${info.meta.flags.toString(16)}")
        must(info.meta.formatVersion == 2L, "meta.format_version=${info.meta.formatVersion}")
        must(info.meta.fileSize == img.length(), "meta.file_size != 文件大小")
        must(info.metaPartition.uniqueGuid == VmdDiskCodec.META_PARTITION_GUID, "meta 分区 GUID 错位")
        must(info.partitions.last() == info.rootPartition, "root 应为最后分区")
        must(info.partitions.size == 3, "mkdisk 标准布局应 3 分区，实际 ${info.partitions.size}")
        val pu = m.disk?.root?.partuuid?.lowercase()?.replace("-", "")
        must(pu == info.rootPartition.uniqueGuid.replace("-", ""), "disk.root.partuuid 与 GPT 不符")
        must(
            (m.checksums["root_sha256"] ?: "").equals(info.meta.rootSha256, ignoreCase = true),
            "checksums.root_sha256 与 meta 不符",
        )
        println(
            "      image=${m.image.id} version=${m.image.version} file=${img.length()}B " +
                "root=@${info.rootPartition.firstLba}..${info.rootPartition.lastLba}",
        )
    }

    check("smoke: verifyFull() 阶段 4 root 分区 sha256") {
        val info = VmdDiskCodec.read(img, currentVersionCode)
        VmdDiskCodec.verifyFull(info)
    }

    val name = "smoke: ESP FAT32 — 目录清单 / vmlinuz / initrd / manifest(LFN) / grub.cfg"
    check(name) {
        val info = VmdDiskCodec.read(img, currentVersionCode)
        val m = info.manifest

        // 目录清单（mtools 可能以 8.3 大写存短名 → 断言大小写不敏感）
        val root = VmdDiskCodec.listEspDir(info, "/")
        must(root != null, "ESP 根目录应可列")
        val names = root.map { it.name.lowercase() }.toSet()
        for (want in listOf("vmlinuz", "initrd.img", "efi", "grub", "vmdroid")) {
            must(want in names, "ESP 根缺 $want，实际：$names")
        }

        // /vmlinuz：非空；摘要对账（mkdisk 的 kernel_sha256 取自 --kernel 原始入参 —— 若入参为
        // gzip 形态，ESP 内是展开后的 Image，摘要不等属预期 → 降级 WARN）
        val vmlinuz = VmdDiskCodec.readEspFile(info, "/vmlinuz")
        must(vmlinuz != null && vmlinuz.isNotEmpty(), "/vmlinuz 缺失或为空")
        val ksum = m.checksums["kernel_sha256"]
        if (ksum != null && sha256(vmlinuz) == ksum) {
            // 硬对上（--kernel 未压缩入参）—— 顺带验证 FAT 读取逐字节正确
        } else {
            warn("/vmlinuz sha256 与 kernel_sha256 不符", "属预期当 --kernel 为 gzip 形态（ESP 存展开 Image）")
        }

        // /initrd.img：mkdisk 原样拷贝 → 摘要必须相等（FAT 读取逐字节正确的硬证据）
        val initrd = VmdDiskCodec.readEspFile(info, "/initrd.img")
        must(initrd != null && initrd.isNotEmpty(), "/initrd.img 缺失或为空")
        must(
            (m.checksums["initrd_sha256"] ?: "").equals(sha256(initrd), ignoreCase = true),
            "/initrd.img sha256 与 checksums.initrd_sha256 不符",
        )

        // /vmdroid/manifest.json：唯一 8.3 装不下的名字 → LFN 路径硬校验
        val manCopy = VmdDiskCodec.readEspFile(info, "/vmdroid/manifest.json")
        must(manCopy != null && manCopy.isNotEmpty(), "/vmdroid/manifest.json 缺失（LFN 读取失败？）")
        val manDoc = MiniJson.parseObject(manCopy.toString(Charsets.UTF_8))
        must(
            MiniJson.str(manDoc, "format") == VmdDiskCodec.MANIFEST_FORMAT,
            "ESP 冗余 manifest.format=${MiniJson.str(manDoc, "format")}",
        )

        // /grub/grub.cfg：必须携带 vmd.root_partuuid=<disk.root.partuuid>
        val cfg = VmdDiskCodec.readEspFile(info, "/grub/grub.cfg")
        must(cfg != null, "/grub/grub.cfg 缺失")
        val cfgText = cfg.toString(Charsets.UTF_8)
        val pu = m.disk?.root?.partuuid ?: ""
        must(cfgText.contains("vmd.root_partuuid=$pu"), "grub.cfg 未携带 vmd.root_partuuid=$pu")

        // /EFI/BOOT/BOOTAA64.EFI：PE/COFF（MZ 头；软断言，GRUB 镜像必为 PE）
        val efi = VmdDiskCodec.readEspFile(info, "/EFI/BOOT/BOOTAA64.EFI")
        must(efi != null && efi.isNotEmpty(), "BOOTAA64.EFI 缺失或为空")
        if (efi.size < 2 || efi[0] != 'M'.code.toByte() || efi[1] != 'Z'.code.toByte()) {
            warn("BOOTAA64.EFI 非 MZ/PE 头", "首两字节=${efi.take(2).joinToString(" ") { String.format("%02x", it) }}")
        }

        // FAT 卷 ID ↔ manifest.disk.esp.fat_uuid（mkfs.vfat -i 固定；§2.3）
        EspFat32.open(img, info.espPartition.offsetBytes, info.espPartition.sizeBytes).use { fat ->
            val fatUuid = m.disk?.esp?.fatUuid?.lowercase()
            must(
                fatUuid == null || fat.volumeIdHex == fatUuid,
                "FAT 卷 ID ${fat.volumeIdHex} != disk.esp.fat_uuid $fatUuid",
            )
        }
    }
}

// ======================================================================
// main
// ======================================================================

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun main(args: Array<String>) {
    tmpRoot = File("systemimage-selftest/out/tmp-v2")
    tmpRoot.deleteRecursively()
    tmpRoot.mkdirs()

    val manifestPath = if (args.isNotEmpty()) args[0] else env("VMD_FIXTURES_V2")
    val smokeImagePath = if (args.size > 1) args[1] else env("VMD_SMOKE_IMAGE")
    currentVersionCode = env("VMD_SELFTEST_VERSION_CODE")?.toLongOrNull() ?: 1L
    skipFullVerify = env("VMD_SELFTEST_SKIP_FULL") == "1"

    println("tmp      : ${tmpRoot.absolutePath}")
    println("version  : $currentVersionCode" + if (skipFullVerify) "（full 校验跳过）" else "")
    println("")

    // ---- 1. 无夹具依赖项（恒跑） ----
    testSyntheticLadder()
    testGuidRoundTrip()
    testFat32WithMkfs()

    // ---- 2. 夹具对账（清单缺失 → SKIP，不失败：并行施工中清单稍后落盘） ----
    val candidates = ArrayList<File>()
    if (manifestPath != null) candidates += File(manifestPath)
    candidates += File("codec-selftest/fixtures/v2/fixtures-v2.json")
    candidates += File("../codec-selftest/fixtures/v2/fixtures-v2.json")
    val manifest = candidates.firstOrNull { it.isFile }
    if (manifest != null) {
        try {
            runFixtures(manifest)
        } catch (t: Throwable) {
            failed++
            println("FAIL  fx:<manifest> — 清单解析/运行失败：${t.javaClass.simpleName}: ${t.message}")
        }
    } else if (manifestPath != null) {
        failed++
        println("FAIL  fx:<manifest> — 指定的清单不存在：$manifestPath")
    } else {
        skip("夹具对账（fixtures-v2.json）", "清单未落盘（并行施工中）→ 仅跑合成负样本 + 冒烟模式")
    }

    // ---- 3. 冒烟模式（需环境变量/参数指定镜像） ----
    if (smokeImagePath != null) {
        val img = File(smokeImagePath)
        if (!img.isFile) {
            failed++
            println("FAIL  smoke:<image> — 指定的镜像不存在：$smokeImagePath")
        } else {
            testSmoke(img)
        }
    } else {
        skip("冒烟模式", "未指定镜像（java -jar selftest-v2.jar [manifest] [img] 或 VMD_SMOKE_IMAGE=…）")
    }

    println("")
    println("TOTAL: $passed passed, $failed failed, $skipped skipped, $warned warnings")
    exitProcess(if (failed == 0) 0 else 1)
}
