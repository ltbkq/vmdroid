/*
 * EspUserCfgWriter JUnit 测试（R8 运行时参数通道，IMAGE-FORMAT-v2 §2.4 R8 /
 * DESIGN-disk-image-v2 §3.4 方案 b、§16 TBD-1、§7.2 T-10）。
 *
 * 覆盖：
 *   - build：4096 定长、魔数行、set 行格式、无 NUL、空参数（无 set 行）版本、
 *     NUL/换行/超长拒绝；
 *   - sanitize：正常拼装顺序、默认最小集、注入串拒绝（`";reboot`、反引号、`;rm`、
 *     `$()`、换行）、纯数字校验（负 dpi）、dns 合法（IP/v6/端口/scope-id）与非法
 *     （分号、空格、非 hex 主机名、空项）；
 *   - 写入 roundtrip：现场 `mkfs.vfat -C -F 32` + `mmd`/`mcopy` 造含 4096 预置
 *     `/grub/user.cfg` 的 FAT 镜像 → write → 整 4096 回读比对 + 目录项（大小/首簇）
 *     不变；>4096 拒绝、<4096 拒绝（恒定长覆盖语义）、文件不存在拒绝（不创建）；
 *   - 真实 mkdisk 产物集成：环境变量 `R8_IMAGE` 指向 v2 镜像时才跑（否则 Assume 跳过），
 *     `VmdDiskCodec.read` 取 esp 范围 → write → 回读。
 *
 * 依赖外部工具（mkfs.vfat/mcopy/mmd）的用例缺工具即 Assume 跳过（IMP-C03 语义）。
 */
package io.github.ltbkq.vmdroid.systemimage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class EspUserCfgWriterTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "vmd-r8-junit").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun runTool(cmd: List<String>) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = p.waitFor()
        if (code != 0) throw AssertionError("命令失败($code): ${cmd.joinToString(" ")}\n$out")
    }

    private fun toolAvailable(name: String): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "command -v $name").start()
        val ok = p.waitFor() == 0
        p.inputStream.readBytes()
        ok
    } catch (_: Exception) {
        false
    }

    private fun assumeFatTools() {
        assumeTrue(
            "环境缺 mkfs.vfat/mcopy/mmd",
            toolAvailable("mkfs.vfat") && toolAvailable("mcopy") && toolAvailable("mmd"),
        )
    }

    /**
     * 现场造 64 MiB FAT32 镜像（512B 簇 → user.cfg 跨 8 簇，顺带覆盖多簇链写）。
     * [withUserCfg] = 是否预置 4096 字节 `/grub/user.cfg`（mkdisk 的构建期预置形态）。
     */
    private fun makeFatImage(name: String, withUserCfg: Boolean): File {
        val img = File(tmp, name)
        img.delete()
        RandomAccessFile(img, "rw").use { it.setLength(64L * 1024 * 1024) }
        runTool(listOf("mkfs.vfat", "-F", "32", "-n", "VMDR8", img.absolutePath))
        runTool(listOf("mmd", "-i", img.absolutePath, "::/grub"))
        if (withUserCfg) {
            val preset = File(tmp, "$name.preset.cfg")
            preset.writeBytes(EspUserCfgWriter.build(null))
            runTool(listOf("mcopy", "-i", img.absolutePath, preset.absolutePath, "::/grub/user.cfg"))
        }
        return img
    }

    /** 读目录项快照（size / firstCluster —— 定长覆盖不得改动它们）。 */
    private fun userCfgEntry(img: File): EspFat32.Entry? =
        EspFat32.open(img, 0, img.length()).use { esp ->
            esp.list("/grub")?.firstOrNull { it.name.equals("user.cfg", ignoreCase = true) }
        }

    private fun readUserCfg(img: File): ByteArray? =
        EspFat32.open(img, 0, img.length()).use { it.readFile("/grub/user.cfg") }

    // ------------------------------------------------------------------
    // build（R8-1：定长 4096、魔数、set 行、无 NUL、空参数版本）
    // ------------------------------------------------------------------

    @Test
    fun buildProducesFixed4096WithMagicAndSetLine() {
        val params = "podroid.x11.dpi=170 podroid.dns=8.8.8.8"
        val c = EspUserCfgWriter.build(params)
        assertEquals(4096, c.size)
        val line = "#VMDU2\nset podroid_params=\"$params\"\n"
        val expected = line + "\n".repeat(4096 - line.length)
        assertArrayEquals("4096 定长 + 魔数 + set 行 + 0x0A 填充", expected.toByteArray(), c)
        // 无 NUL（R8-1 硬性：禁 0x00）
        assertFalse("内容不得含 NUL", c.contains(0))
        // 全文件只由换行(0x0A)与可打印 ASCII 组成（无控制字符、无 NUL）
        assertTrue(c.all { b -> b == 0x0A.toByte() || b.toInt() in 0x20..0x7E })
    }

    @Test
    fun buildWithoutParamsIsMagicOnly() {
        for (empty in listOf<String?>(null, "")) {
            val c = EspUserCfgWriter.build(empty)
            assertEquals(4096, c.size)
            val expected = "#VMDU2\n" + "\n".repeat(4089)
            assertArrayEquals("空参数版本 = 仅魔数 + 填充", expected.toByteArray(), c)
            assertFalse(String(c, Charsets.US_ASCII).contains("set "))
            assertFalse(c.contains(0))
        }
    }

    @Test
    fun buildRejectsNulNewlineAndOverflow() {
        // NUL：R8-1 禁止
        assertThrows(IllegalArgumentException::class.java) { EspUserCfgWriter.build("a\u0000b") }
        // 换行：必须保持"一行 set"（防换行注入）
        assertThrows(IllegalArgumentException::class.java) { EspUserCfgWriter.build("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { EspUserCfgWriter.build("a\rb") }
        // 超长：装不下
        assertThrows(IllegalArgumentException::class.java) {
            EspUserCfgWriter.build("x".repeat(4090))
        }
    }

    // ------------------------------------------------------------------
    // sanitize（R8-4：键固定 + 白名单，任一字段不满足 → 整条拒绝 null）
    // ------------------------------------------------------------------

    @Test
    fun sanitizeAssemblesParamsInFixedOrder() {
        val s = EspUserCfgWriter.sanitize(
            BootParams(
                x11Dpi = 170,
                bandwidthMbps = 100,
                dnsServers = listOf("8.8.8.8", "1.1.1.1"),
                kernelExtras = "quiet consoleblank=0",
            ),
        )
        assertEquals(
            "podroid.x11.dpi=170 podroid.bandwidth=100 " +
                "podroid.dns=8.8.8.8,1.1.1.1 quiet consoleblank=0",
            s,
        )
    }

    @Test
    fun sanitizeDefaultsInjectOnlyDpi() {
        // bandwidth<=0 / dns 空 / extras 空白 → 不注入
        assertEquals(
            "podroid.x11.dpi=96",
            EspUserCfgWriter.sanitize(BootParams(96, 0, emptyList(), "")),
        )
        assertEquals(
            "podroid.x11.dpi=96",
            EspUserCfgWriter.sanitize(BootParams(96, -1, emptyList(), "   \t ")),
        )
        // dpi 恒注入（v1 -append 行为一致）：dpi=0 合法，输出仍非空
        assertEquals(
            "podroid.x11.dpi=0",
            EspUserCfgWriter.sanitize(BootParams(0, 0, emptyList(), "")),
        )
        // sanitize 的 null 语义专表"白名单拒绝"；合法输入永不返回 null
        assertNotNull(EspUserCfgWriter.sanitize(BootParams(96, 0, emptyList(), "")))
    }

    @Test
    fun sanitizeRejectsInjectionInKernelExtras() {
        val evil = listOf(
            "foo\";reboot",              // 双引号闭合 + GRUB 命令
            "foo`reboot`",                // 反引号
            ";rm -rf /",                  // 语句分隔符开头
            "a$(reboot)",                 // $() 展开
            "a${'$'}podroid_params",      // $ 变量引用
            "quiet\nreboot",              // 换行注入（整条白名单拒绝）
            "a|reboot",                   // 管道
        )
        for (extras in evil) {
            assertNull("应拒绝: $extras", EspUserCfgWriter.sanitize(BootParams(96, 0, emptyList(), extras)))
        }
        // 合法白名单内容原样通过（允许字符 = [A-Za-z0-9 ._=/,:+-]）
        assertEquals(
            "podroid.x11.dpi=96 consoleblank=0 root=/dev/vda3 a-b_c.d,e:f+g",
            EspUserCfgWriter.sanitize(
                BootParams(96, 0, emptyList(), "consoleblank=0 root=/dev/vda3 a-b_c.d,e:f+g"),
            ),
        )
    }

    @Test
    fun sanitizeValidatesPureDigits() {
        // 负 dpi 不是纯数字 → fail-closed 整条拒绝
        assertNull(EspUserCfgWriter.sanitize(BootParams(-5, 0, emptyList(), "")))
        // 正常数字
        assertEquals(
            "podroid.x11.dpi=170 podroid.bandwidth=42",
            EspUserCfgWriter.sanitize(BootParams(170, 42, emptyList(), "")),
        )
        // extras 中夹带空白以外的非法字符（如引号）→ 整条拒绝
        assertNull(
            EspUserCfgWriter.sanitize(BootParams(96, 0, emptyList(), "evil=1; reboot")),
        )
        // 注：extras 本身是设计上的自由文本逃生口（userKernelExtras —— 允许任意
        // `key=value` 形态的内核参数），安全边界是字符白名单而非键表；
        // "键固定"约束的是我们自己拼装的 podroid.* 三键（见 sanitizeAssemblesParamsInFixedOrder）。
    }

    @Test
    fun sanitizeDnsAcceptsIpV6AndPort() {
        val cases = listOf(
            "8.8.8.8",
            "fe80::1",
            "2001:db8::1",
            "1.2.3.4:5353",
            "fe80::1%1",              // 数字 scope-id（% 在规格字符集内）
        )
        for (dns in cases) {
            val s = EspUserCfgWriter.sanitize(BootParams(96, 0, listOf(dns), ""))
            assertEquals("应接受 dns=$dns", "podroid.x11.dpi=96 podroid.dns=$dns", s)
        }
        // 多项列表 → sanitize 用逗号拼接（"逗号分隔的 [0-9a-fA-F.:%]"）
        assertEquals(
            "podroid.x11.dpi=96 podroid.dns=0.0.0.0,::1,8.8.8.8:53",
            EspUserCfgWriter.sanitize(BootParams(96, 0, listOf("0.0.0.0", "::1", "8.8.8.8:53"), "")),
        )
        // 单项内夹带逗号（列表元素须是单个 token）→ 拒绝
        assertNull(
            EspUserCfgWriter.sanitize(BootParams(96, 0, listOf("1.1.1.1,9.9.9.9"), "")),
        )
    }

    @Test
    fun sanitizeDnsRejectsIllegalTokens() {
        val cases = listOf(
            "8.8.8.8;reboot",   // 分号
            "8.8.8.8 rm -rf",   // 空格
            "dns.google",       // 非 hex 字母（g/n/s…）
            "\"8.8.8.8\"",      // 引号
            "",                 // 空项
            "8.8.8.8,",         // 逗号不在单项白名单（且会产生空项）
            "fe80::1%eth0",     // scope-id 界名含 't/h'（超出 [0-9a-fA-F.:%]）
        )
        for (dns in cases) {
            assertNull("应拒绝 dns='$dns'", EspUserCfgWriter.sanitize(BootParams(96, 0, listOf(dns), "")))
        }
    }

    // ------------------------------------------------------------------
    // write roundtrip（R8-3：定位 → 定长覆盖 → 整 4096 回读；现场 FAT 镜像）
    // ------------------------------------------------------------------

    @Test
    fun writeRoundtripOverwritesDataOnly() {
        assumeFatTools()
        val img = makeFatImage("roundtrip.img", withUserCfg = true)
        val before = userCfgEntry(img)
        assertNotNull("预置 user.cfg 应存在", before)
        assertEquals(4096L, before!!.size)

        val content = EspUserCfgWriter.build("podroid.x11.dpi=170")
        val res = EspUserCfgWriter.write(img, 0, img.length(), content)
        assertTrue("write 失败: ${res.exceptionOrNull()}", res.isSuccess)

        // 整 4096 回读比对
        val readback = readUserCfg(img)
        assertNotNull(readback)
        assertTrue("回读与写入不一致", readback!!.contentEquals(content))
        val text = String(readback, Charsets.UTF_8)
        assertTrue(text.startsWith("#VMDU2\n"))
        assertTrue(text.contains("set podroid_params=\"podroid.x11.dpi=170\"\n"))
        assertFalse("禁 NUL", readback.contains(0))

        // 目录项（大小/首簇）不变 —— 只覆写数据区
        val after = userCfgEntry(img)
        assertNotNull(after)
        assertEquals("文件大小不得改变", before.size, after!!.size)
        assertEquals("首簇（簇链入口）不得改变", before.firstCluster, after.firstCluster)

        // 幂等覆盖：换参数再写一次成功且内容随之变化
        val content2 = EspUserCfgWriter.build("podroid.x11.dpi=240")
        assertTrue(EspUserCfgWriter.write(img, 0, img.length(), content2).isSuccess)
        assertTrue(readUserCfg(img)!!.contentEquals(content2))
        assertEquals("再次覆盖后文件大小仍不得改变", 4096L, userCfgEntry(img)!!.size)
    }

    @Test
    fun writeRejectsOversizeAndLeavesContentIntact() {
        assumeFatTools()
        val img = makeFatImage("oversize.img", withUserCfg = true)
        val preset = readUserCfg(img)!!

        assertTrue(EspUserCfgWriter.write(img, 0, img.length(), ByteArray(4097)).isFailure)
        assertTrue(EspUserCfgWriter.write(img, 0, img.length(), ByteArray(1 shl 20)).isFailure)
        // 越界写在落盘前即被拒绝：原内容/目录项均未变
        assertTrue("失败写不得改动原内容", readUserCfg(img)!!.contentEquals(preset))
        assertEquals(4096L, userCfgEntry(img)!!.size)
    }

    @Test
    fun writeRejectsUndersizeNotFixedLength() {
        assumeFatTools()
        val img = makeFatImage("undersize.img", withUserCfg = true)
        val preset = readUserCfg(img)!!
        // 恒定长覆盖语义：非 4096 一律拒绝（含不足 4096）
        assertTrue(EspUserCfgWriter.write(img, 0, img.length(), ByteArray(0)).isFailure)
        assertTrue(EspUserCfgWriter.write(img, 0, img.length(), ByteArray(100)).isFailure)
        assertTrue(readUserCfg(img)!!.contentEquals(preset))
    }

    @Test
    fun writeRejectsMissingFileWithoutCreatingIt() {
        assumeFatTools()
        val img = makeFatImage("missing.img", withUserCfg = false)
        val res = EspUserCfgWriter.write(
            img, 0, img.length(), EspUserCfgWriter.build("podroid.x11.dpi=170"),
        )
        assertTrue("文件不存在必须拒绝", res.isFailure)
        // 恒定长覆盖语义：绝不创建新文件
        assertNull("不得创建 user.cfg", readUserCfg(img))
    }

    // ------------------------------------------------------------------
    // 真实 mkdisk 产物集成（R8_IMAGE 指向 v2 镜像时启用，否则 Assume 跳过）
    // ------------------------------------------------------------------

    @Test
    fun roundtripOnRealMkdiskImageIfProvided() {
        val path = System.getenv("R8_IMAGE")
        assumeTrue("R8_IMAGE 未设置 → 跳过真实镜像集成", !path.isNullOrBlank())
        val img = File(path!!)
        assumeTrue("R8_IMAGE=$path 不是可读文件 → 跳过", img.isFile)

        val info = VmdDiskCodec.read(img)
        val content = EspUserCfgWriter.build("podroid.x11.dpi=170")
        val res = EspUserCfgWriter.write(
            img, info.espPartition.offsetBytes, info.espPartition.sizeBytes, content,
        )
        assertTrue("真实镜像 write 失败: ${res.exceptionOrNull()}", res.isSuccess)

        val readback = EspFat32.open(img, info.espPartition.offsetBytes, info.espPartition.sizeBytes)
            .use { it.readFile(EspUserCfgWriter.USER_CFG_PATH) }
        assertNotNull(readback)
        assertTrue("真实镜像回读不一致", readback!!.contentEquals(content))
    }

    // ------------------------------------------------------------------
    // 回滚语义：目录项损坏场景（非定长预置）拒绝覆盖
    // ------------------------------------------------------------------

    @Test
    fun writeRejectsNonFixedLengthPreset() {
        assumeFatTools()
        val img = File(tmp, "shortpreset.img")
        img.delete()
        RandomAccessFile(img, "rw").use { it.setLength(64L * 1024 * 1024) }
        runTool(listOf("mkfs.vfat", "-F", "32", "-n", "VMDR8", img.absolutePath))
        runTool(listOf("mmd", "-i", img.absolutePath, "::/grub"))
        val small = File(tmp, "short.cfg").apply { writeText("#VMDU2\n") }  // 7 字节，非 4096
        runTool(listOf("mcopy", "-i", img.absolutePath, small.absolutePath, "::/grub/user.cfg"))

        val res = EspUserCfgWriter.write(img, 0, img.length(), EspUserCfgWriter.build("podroid.x11.dpi=170"))
        assertTrue("非 4096 预置必须拒绝", res.isFailure)
        // 原样保留
        assertEquals("#VMDU2\n", String(readUserCfg(img)!!, Charsets.US_ASCII))
    }
}
