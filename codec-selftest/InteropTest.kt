/*
 * InteropTest.kt — 工位 G · 工具侧互操作验证（DESIGN §13.1「工具侧互操作」）
 *
 * 把两个从未互相验证过的独立实现钉在一起：
 *   bash+python 侧  Podroid-Debian/tests/gen-vectors.sh + tools/mkimg.sh
 *                    （21 条向量 + footer_layout 19 字段偏移表 + catalog_signature）
 *   Kotlin 侧       vmdroid-app .../systemimage/VmdImageCodec.kt
 *
 * 覆盖：
 *   1. 21 条向量回放：accept → 逐字段比对 file_size/flags/各段 offset+size+sha256；
 *      reject → reason 与 bootGuard（§7.4 映射）逐条相等；app-too-old → parse 期 accept、
 *      激活期用 currentVersionCode 走 validate() 必须拒绝。
 *   2. footer_layout 19 字段偏移回归：vectors.json 偏移表 ↔ codec.parseFooter 实测对照。
 *   3. 正例反向：mkimg.sh 现场新建 .img → codec 读取 accept，段 sha256 与 mkimg --verify 对照。
 *   4. catalog_signature：openssl dgst -sha256 -verify 验签（+ 篡改反例）。
 *   5. fixture 缺失 → 报错退出（禁止静默跳过；run-interop.sh 另外探测真实退出码 == 1）。
 *
 * 纯 stdlib，无 JUnit 依赖。退出码：0 全部通过 · 1 任一失败（含 fixture 缺失）。
 * 用法：java -Dvmd.fixtures=<Podroid-Debian 绝对路径> -jar interop.jar [同路径]
 */
package interop

import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec
import io.github.ltbkq.vmdroid.systemimage.VmdImageException
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// ---------------------------------------------------------------------------
// 结果收集（任何失败 → 退出 1；绝不 Assume / 静默跳过）
// ---------------------------------------------------------------------------

private var passed = 0
private var failed = 0
private val failures = ArrayList<String>()

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
        val line = "FAIL  $name: ${t.message}"
        println(line)
        failures.add(line)
    }
}

/** fixture / 向量库缺失：与校验失败同级 —— 必须显式失败（exit 1），禁止跳过。 */
private class FixtureProblem(msg: String) : RuntimeException(msg)

// ---------------------------------------------------------------------------
// 极简 JSON 解析（vectors.json；仅 stdlib）
// ---------------------------------------------------------------------------

private class J(val s: String) {
    private var i = 0

    fun parse(): Any? {
        val v = value()
        ws()
        if (i != s.length) fail("trailing content")
        return v
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at offset $i")
    private fun ws() { while (i < s.length && s[i] in " \t\n\r") i++ }
    private fun at(c: Char) = i < s.length && s[i] == c

    private fun value(): Any? {
        ws()
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            '-', in '0'..'9' -> num()
            else -> fail("unexpected char '$c'")
        }
    }

    private fun lit(w: String, v: Any?): Any? {
        if (!s.startsWith(w, i)) fail("bad literal")
        i += w.length
        return v
    }

    private fun obj(): LinkedHashMap<String, Any?> {
        i++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (at('}')) { i++; return m }
        while (true) {
            ws()
            if (!at('"')) fail("expected key")
            val k = str()
            ws()
            if (!at(':')) fail("expected ':'")
            i++
            m[k] = value()
            ws()
            when {
                at(',') -> i++
                at('}') -> { i++; return m }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arr(): ArrayList<Any?> {
        i++
        val a = ArrayList<Any?>()
        ws()
        if (at(']')) { i++; return a }
        while (true) {
            a.add(value())
            ws()
            when {
                at(',') -> i++
                at(']') -> { i++; return a }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun str(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            when (val c = s[i]) {
                '"' -> { i++; return sb.toString() }
                '\\' -> {
                    i++
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r')
                        'u' -> {
                            if (i + 4 >= s.length) fail("bad \\u escape")
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u '$hex'"))
                            i += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
    }

    private fun num(): Any {
        val start = i
        if (at('-')) i++
        while (i < s.length && (s[i] in "0123456789.eE+-")) i++
        val t = s.substring(start, i)
        return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
            t.toDoubleOrNull() ?: fail("bad num '$t'")
        } else {
            t.toLongOrNull() ?: fail("bad num '$t'")
        }
    }
}

private fun parseJson(text: String): Any? = J(text).parse()

private fun objOf(m: Map<String, Any?>, k: String): Map<String, Any?> =
    m[k] as? Map<String, Any?> ?: throw AssertionError("missing object field '$k' in $m")

private fun Map<String, Any?>.olong(k: String): Long =
    (this[k] as? Number)?.toLong() ?: throw AssertionError("missing/invalid number '$k' in $this")

private fun Map<String, Any?>.ostr(k: String): String? = this[k] as? String

private fun Map<String, Any?>.olist(k: String): List<Any?> =
    this[k] as? List<Any?> ?: throw AssertionError("missing list '$k' in $this")

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

private fun runCmd(cmd: List<String>, timeoutSec: Long = 180): Pair<Int, String> {
    val p = try {
        ProcessBuilder(cmd).redirectErrorStream(true).start()
    } catch (e: Exception) {
        throw AssertionError("cannot run '${cmd.joinToString(" ")}': $e")
    }
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        throw AssertionError("command timed out (${timeoutSec}s): ${cmd.joinToString(" ")}")
    }
    return p.exitValue() to out
}

private fun leU32(b: ByteArray, off: Int): Long {
    var v = 0L
    for (k in 3 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
    return v
}

private fun leU64(b: ByteArray, off: Int): Long {
    var v = 0L
    for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
    return v
}

/** 按 vectors.json footer_layout 的 offset/size/type 独立解码（与 codec 完全无关的读取器）。 */
private fun rawField(buf: ByteArray, off: Int, size: Int, type: String): Any = when (type) {
    "bytes" -> String(buf, off, size, Charsets.US_ASCII)
    "u32le" -> leU32(buf, off)
    "u64le" -> leU64(buf, off)
    "bytes32" -> VmdImageCodec.hex(buf.copyOfRange(off, off + size))
    "zero" -> buf.copyOfRange(off, off + size).all { it == 0.toByte() }
    else -> throw AssertionError("unknown footer_layout type '$type'")
}

/** codec Footer 中与 layout 字段名对应的实测值（reserved 无对应属性 → null）。 */
private fun codecField(f: VmdImageCodec.Footer, field: String): Any? = when (field) {
    "magic" -> f.magic
    "format_version" -> f.formatVersion.toLong()
    "footer_size" -> f.footerSize.toLong()
    "file_size" -> f.fileSize
    "rootfs_offset" -> f.rootfsOffset
    "rootfs_size" -> f.rootfsSize
    "rootfs_sha256" -> f.rootfsSha256
    "manifest_offset" -> f.manifestOffset
    "manifest_size" -> f.manifestSize
    "manifest_sha256" -> f.manifestSha256
    "flags" -> f.flags.toLong()
    "kernel_offset" -> f.kernelOffset
    "kernel_size" -> f.kernelSize
    "kernel_sha256" -> f.kernelSha256
    "initrd_offset" -> f.initrdOffset
    "initrd_size" -> f.initrdSize
    "initrd_sha256" -> f.initrdSha256
    "magic_tail" -> f.magicTail
    else -> null // reserved：codec 无该属性
}

/** 段三元组 offset / size / sha256（footer 无 sha256）。 */
private fun segOf(f: VmdImageCodec.Footer, name: String): Triple<Long, Long, String?> = when (name) {
    "rootfs" -> Triple(f.rootfsOffset, f.rootfsSize, f.rootfsSha256)
    "kernel" -> Triple(f.kernelOffset, f.kernelSize, f.kernelSha256)
    "initrd" -> Triple(f.initrdOffset, f.initrdSize, f.initrdSha256)
    "manifest" -> Triple(f.manifestOffset, f.manifestSize, f.manifestSha256)
    "footer" -> Triple(f.footerOffset, VmdImageCodec.FOOTER_SIZE.toLong(), null)
    else -> throw AssertionError("unknown segment '$name'")
}

/** codec 侧读取链：read → validate → verifyPayloads，返回首个结果（接受 / 首个异常及其阶段）。 */
private sealed class Outcome {
    data class Ok(val info: VmdImageCodec.ImageInfo) : Outcome()
    data class Rej(val reason: String, val bootGuard: String, val at: String, val msg: String) : Outcome()
}

private fun replayChain(file: File, versionCode: Long?): Outcome {
    val info = try {
        VmdImageCodec.read(file, versionCode)
    } catch (e: VmdImageException) {
        return Outcome.Rej(e.reason.name, e.bootGuardReason, "read", e.message ?: "")
    }
    try {
        VmdImageCodec.validate(info, versionCode)
    } catch (e: VmdImageException) {
        return Outcome.Rej(e.reason.name, e.bootGuardReason, "validate", e.message ?: "")
    }
    try {
        VmdImageCodec.verifyPayloads(info)
    } catch (e: VmdImageException) {
        return Outcome.Rej(e.reason.name, e.bootGuardReason, "verifyPayloads", e.message ?: "")
    }
    return Outcome.Ok(info)
}

// ---------------------------------------------------------------------------
// fixture 装载（缺失 → FixtureProblem → exit 1）
// ---------------------------------------------------------------------------

private class Fixture(val root: File, val vectorsJson: File, val vectors: List<Map<String, Any?>>)

/** 要求 fixtures 齐备；任一缺失 → 抛 [FixtureProblem]（绝不静默跳过）。 */
private fun requireFixtures(root: File): Fixture {
    val vj = File(root, "tests/vectors/vectors.json")
    if (!vj.isFile) throw FixtureProblem("vectors.json 不存在：$vj")
    val imgDir = File(root, "tests/data/vectors")
    if (!imgDir.isDirectory) throw FixtureProblem("向量镜像目录不存在：$imgDir")
    for (need in listOf(
        "tests/data/bare-rootfs.sqfs", "tests/data/fake-kernel.bin", "tests/data/fake-initrd.img",
        "tests/data/manifest-valid.json", "tests/data/catalog-signature.message.txt",
        "tools/mkimg.sh",
    )) {
        if (!File(root, need).isFile) throw FixtureProblem("依赖 fixture 不存在：${File(root, need)}")
    }
    val doc = parseJson(vj.readText()) as? Map<String, Any?>
        ?: throw FixtureProblem("vectors.json 解析失败（根不是 object）：$vj")
    val vecs = doc.olist("vectors").map { it as? Map<String, Any?> ?: throw AssertionError("vector 不是 object") }
    val missing = vecs.mapNotNull { v ->
        val rel = v.ostr("file") ?: throw AssertionError("vector 缺 file 字段：$v")
        val f = File(vj.parentFile, rel)
        if (!f.isFile) "$rel（${v["name"]}）" else null
    }
    if (missing.isNotEmpty()) {
        throw FixtureProblem(
            "缺少 ${missing.size} 个向量镜像：${missing.joinToString(", ")} —— " +
                "请先运行 cd ${root.absolutePath} && ./tests/gen-vectors.sh",
        )
    }
    return Fixture(root, vj, vecs)
}

// ---------------------------------------------------------------------------
// mkimg --verify 输出解析（正例反向对照）
// ---------------------------------------------------------------------------

private data class SegLine(val offset: Long, val size: Long, val sha: String?)

private fun parseVerifyOutput(out: String): Triple<Map<String, SegLine>, Long, Int> {
    val segs = LinkedHashMap<String, SegLine>()
    val re = Regex(
        """^\s*(rootfs|kernel|initrd|manifest|footer)\s+offset=(\d+)\s+size=(\d+)(?:\s+sha256=([0-9a-f]{64}))?""",
        RegexOption.MULTILINE,
    )
    for (m in re.findAll(out)) {
        segs[m.groupValues[1]] = SegLine(
            m.groupValues[2].toLong(),
            m.groupValues[3].toLong(),
            m.groupValues[4].ifEmpty { null },
        )
    }
    val fileSize = Regex("""^\s+file_size\s+(\d+)""", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toLong()
        ?: throw AssertionError("mkimg --verify 输出缺 file_size 行")
    val flags = Regex("""^\s+flags\s+0x([0-9a-f]+)""", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toInt(16)
        ?: throw AssertionError("mkimg --verify 输出缺 flags 行")
    return Triple(segs, fileSize, flags)
}

private fun segCount(f: VmdImageCodec.Footer): Int =
    2 + (if (f.hasKernel) 1 else 0) + (if (f.hasInitrd) 1 else 0)

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

fun main(args: Array<String>) {
    val rootPath = System.getProperty("vmd.fixtures") ?: args.firstOrNull()
    if (rootPath == null) {
        System.err.println("FIXTURE MISSING: 未指定 fixtures 根（-Dvmd.fixtures=<Podroid-Debian 绝对路径> 或 argv[0]）")
        exitProcess(1)
    }
    val fx = try {
        requireFixtures(File(rootPath).absoluteFile)
    } catch (e: FixtureProblem) {
        System.err.println("FIXTURE MISSING: ${e.message}")
        System.err.println("禁止静默跳过 —— 请先生成：cd Podroid-Debian && ./tests/gen-vectors.sh")
        exitProcess(1)
    }
    val doc = parseJson(fx.vectorsJson.readText()) as Map<String, Any?>
    val tmp = File(System.getProperty("java.io.tmpdir"), "vmd-interop").apply { deleteRecursively(); mkdirs() }

    println("== 工位 G 互操作验证 · fixtures=${fx.root} · vectors=${fx.vectors.size} ==")
    check("前置：vectors.json 共 21 条（当前 ${fx.vectors.size} 条）") {
        must(fx.vectors.size == 21, "期望 21 条，实际 ${fx.vectors.size} 条")
    }

    // ================================================================
    // 1. 向量回放
    // ================================================================
    var acceptPass = 0
    var rejectPass = 0
    fx.vectors.forEachIndexed { idx, v ->
        val name = v.ostr("name") ?: "[$idx]"
        val rel = v.ostr("file") ?: throw AssertionError("vector $name 缺 file")
        val img = File(fx.vectorsJson.parentFile, rel)
        val exp = objOf(v, "expect")
        val result = exp.ostr("result") ?: throw AssertionError("vector $name 缺 expect.result")
        check("vector[${"%02d".format(idx + 1)}] $name → $result") {
            val outcome = replayChain(img, null)
            when (result) {
                "accept" -> {
                    if (outcome !is Outcome.Ok) {
                        val r = outcome as Outcome.Rej
                        throw AssertionError(
                            "向量期望 accept，codec 实际 reject@${r.at}：reason=${r.reason} bootGuard=${r.bootGuard} — ${r.msg}",
                        )
                    }
                    val f = outcome.info.footer
                    if (exp.containsKey("file_size")) {
                        val want = exp.olong("file_size")
                        must(f.fileSize == want, "file_size：向量期望=$want codec=${f.fileSize}")
                        must(f.fileSize == img.length(), "file_size=${f.fileSize} ≠ 实际文件大小=${img.length()}")
                    }
                    if (exp.containsKey("flags")) {
                        val want = exp.olong("flags")
                        must(f.flags.toLong() == want, "flags：向量期望=$want codec=${f.flags}")
                    }
                    exp["segments"]?.let { segsRaw ->
                        val segs = segsRaw as Map<String, Any?>
                        for ((segName, raw) in segs) {
                            val s = raw as Map<*, *>
                            val (off, size, sha) = segOf(f, segName)
                            val wantOff = (s["offset"] as Number).toLong()
                            val wantSize = (s["size"] as Number).toLong()
                            must(off == wantOff, "$segName.offset：向量期望=$wantOff codec=$off")
                            must(size == wantSize, "$segName.size：向量期望=$wantSize codec=$size")
                            val wantSha = s["sha256"] as? String
                            if (wantSha != null) {
                                must(sha == wantSha, "$segName.sha256：向量期望=$wantSha codec=$sha")
                            }
                        }
                    }
                    exp.ostr("file_sha256")?.let { want ->
                        val got = VmdImageCodec.sha256File(img.absolutePath)
                        must(got == want, "file_sha256：向量期望=$want codec=$got")
                    }
                    val act = exp["activate"] as? Map<String, Any?>
                    if (act != null) {
                        val vc = objOf(act, "context").olong("currentVersionCode")
                        val rej = try {
                            VmdImageCodec.validate(outcome.info, vc)
                            null
                        } catch (e: VmdImageException) {
                            e
                        }
                        must(rej != null, "activate：validate(currentVersionCode=$vc) 未按预期拒绝")
                        val wantReason = act.ostr("reason")
                        val wantGuard = act.ostr("bootGuard")
                        must(rej!!.reason.name == wantReason, "activate reason：向量期望=$wantReason codec=${rej.reason.name}")
                        must(rej.bootGuardReason == wantGuard, "activate bootGuard：向量期望=$wantGuard codec=${rej.bootGuardReason}")
                        acceptPass++
                        println("      → parse=accept · activate@vc=$vc reject reason=${rej.reason.name} bootGuard=${rej.bootGuardReason}")
                    } else {
                        acceptPass++
                        println(
                            "      → accept fileSize=${f.fileSize} flags=${f.flags} segments=${segCount(f)} " +
                                "rootfs=${f.rootfsSha256.take(12)} file_sha256=ok verifyPayloads=ok",
                        )
                    }
                }
                "reject" -> {
                    if (outcome !is Outcome.Rej) {
                        throw AssertionError(
                            "向量期望 reject(stage=${exp.ostr("stage")} reason=${exp.ostr("reason")})，codec 全链接受了该镜像",
                        )
                    }
                    val wantReason = exp.ostr("reason")
                    val wantGuard = exp.ostr("bootGuard")
                    must(
                        outcome.reason == wantReason,
                        "reason 不一致：向量期望=$wantReason codec=${outcome.reason}（stage=${outcome.at}，" +
                            "bootGuard 期望=$wantGuard 实际=${outcome.bootGuard}）— ${outcome.msg}",
                    )
                    must(
                        outcome.bootGuard == wantGuard,
                        "bootGuard 不一致：向量期望=$wantGuard codec=${outcome.bootGuard}（reason=$wantReason 一致）",
                    )
                    rejectPass++
                    println("      → reject@${outcome.at} reason=${outcome.reason} bootGuard=${outcome.bootGuard}")
                }
                else -> throw AssertionError("未知 expect.result=$result")
            }
        }
    }

    // ================================================================
    // 2. footer_layout 19 字段偏移回归
    // ================================================================
    val layout = doc.olist("footer_layout").map { it as Map<String, Any?> }
    check("footer_layout：偏移表自洽（19 字段连续拼满 4096，常量与 codec 一致）") {
        must(layout.size == 19, "字段数=${layout.size}，期望 19")
        var cursor = 0L
        for (f in layout) {
            val off = f.olong("offset")
            val size = f.olong("size")
            must(off == cursor, "${f["field"]}：offset=$off ≠ 上一字段末尾=$cursor")
            cursor = off + size
        }
        must(cursor == 4096L, "末字段 end=$cursor ≠ 4096")
        val byName = layout.associateBy { it["field"] as String }
        must((byName["footer_size"]!!["fixed_value"] as Number).toLong() == 4096L, "footer_size fixed_value")
        must(byName["magic"]!!["fixed_value"] == VmdImageCodec.MAGIC, "magic fixed_value")
        must(byName["magic_tail"]!!["fixed_value"] == VmdImageCodec.MAGIC, "magic_tail fixed_value")
        must(
            (byName["magic_tail"]!!["offset"] as Number).toLong() == VmdImageCodec.MAGIC_TAIL_OFFSET.toLong(),
            "magic_tail offset ≠ codec MAGIC_TAIL_OFFSET",
        )
        must(VmdImageCodec.FOOTER_SIZE == 4096, "codec FOOTER_SIZE ≠ 4096")
    }

    check("footer_layout ↔ codec.parseFooter：真实镜像逐字段对照（valid-full + valid-min）") {
        for (imgName in listOf("valid-full.img", "valid-min.img")) {
            val img = File(fx.root, "tests/data/vectors/$imgName")
            val all = img.readBytes()
            val bytes = all.copyOfRange(all.size - 4096, all.size)
            val f = VmdImageCodec.parseFooter(bytes)
            for (lf in layout) {
                val field = lf["field"] as String
                val off = lf.olong("offset").toInt()
                val size = lf.olong("size").toInt()
                val raw = rawField(bytes, off, size, lf["type"] as String)
                val fixed = lf["fixed_value"]
                if (fixed != null) {
                    val want: Any = if (fixed is Number) fixed.toLong() else fixed
                    must(raw == want, "$imgName#$field：偏移表 fixed_value=$want，实际字节@$off=$raw")
                }
                if (field == "reserved") {
                    must(raw == true, "$imgName#reserved[$off,+$size) 非全零")
                    continue // codec 无该属性；parseFooter 成功即证明读取未越界
                }
                val cv = codecField(f, field)
                must(
                    cv == raw,
                    "$imgName#$field：偏移表@$off+$size 独立解码=$raw vs codec.parseFooter=$cv（偏移不一致 = 回归失败）",
                )
            }
        }
    }

    check("footer_layout ↔ codec.parseFooter：合成 buffer（每字段唯一随机值，钉死 codec 实际读取偏移）") {
        val buf = ByteArray(4096)
        var seed = 0x11223344L
        fun nextVal(): Long {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return seed and 0x00FF_FFFFL
        }
        for (lf in layout) {
            val off = lf.olong("offset").toInt()
            val size = lf.olong("size").toInt()
            when (lf["type"] as String) {
                "bytes" -> (lf["fixed_value"] as String).toByteArray(Charsets.US_ASCII).copyInto(buf, off)
                "u32le" -> {
                    val v = nextVal() and 0xFFFF_FFFFL
                    for (k in 0 until 4) buf[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
                }
                "u64le" -> {
                    val v = nextVal()
                    for (k in 0 until 8) buf[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
                }
                "bytes32" -> {
                    val v = nextVal()
                    for (k in 0 until 32) buf[off + k] = ((v + k) and 0xFF).toByte()
                }
                "zero" -> Unit // 保持零
            }
        }
        val f = VmdImageCodec.parseFooter(buf)
        for (lf in layout) {
            val field = lf["field"] as String
            val off = lf.olong("offset").toInt()
            val size = lf.olong("size").toInt()
            if (field == "reserved") continue
            val raw = rawField(buf, off, size, lf["type"] as String)
            val cv = codecField(f, field)
            must(cv == raw, "$field：偏移表@$off 独立解码=$raw vs codec.parseFooter=$cv")
        }
    }

    // ================================================================
    // 3. 正例反向：mkimg.sh 现场构建 → codec 读 → 与 mkimg --verify 对照
    // ================================================================
    check("正例反向：mkimg.sh 新建 .img → codec 接受 + 段 sha256 与 mkimg --verify 一致") {
        val mkimg = File(fx.root, "tools/mkimg.sh")
        must(mkimg.canExecute(), "mkimg.sh 不存在或不可执行：$mkimg")
        val out = File(tmp, "reverse/interop-reverse.img")
        out.parentFile.mkdirs()
        val (brc, bout) = runCmd(
            listOf(
                mkimg.absolutePath,
                "--rootfs", File(fx.root, "tests/data/bare-rootfs.sqfs").absolutePath,
                "--manifest", File(fx.root, "tests/data/manifest-valid.json").absolutePath,
                "--kernel", File(fx.root, "tests/data/fake-kernel.bin").absolutePath,
                "--initrd", File(fx.root, "tests/data/fake-initrd.img").absolutePath,
                "-o", out.absolutePath, "--no-smoke",
            ),
        )
        must(brc == 0, "mkimg 构建失败 rc=$brc\n$bout")
        val (vrc, vout) = runCmd(listOf(mkimg.absolutePath, "--verify", out.absolutePath))
        must(vrc == 0, "mkimg --verify 失败 rc=$vrc\n$vout")

        val info = VmdImageCodec.read(out, null)
        VmdImageCodec.validate(info, null)
        VmdImageCodec.verifyPayloads(info)
        val f = info.footer
        val (mkSegs, mkSize, mkFlags) = parseVerifyOutput(vout)

        must(f.fileSize == mkSize, "file_size：mkimg=$mkSize codec=${f.fileSize}")
        must(f.fileSize == out.length(), "file_size=${f.fileSize} ≠ 实际=${out.length()}")
        must(f.flags == mkFlags, "flags：mkimg=0x${mkFlags.toString(16)} codec=0x${f.flags.toString(16)}")
        must(mkSegs.size == 5, "mkimg --verify 段行数=${mkSegs.size}，期望 5")
        for ((segName, mk) in mkSegs) {
            val (off, size, sha) = segOf(f, segName)
            must(off == mk.offset, "$segName.offset：mkimg=${mk.offset} codec=$off")
            must(size == mk.size, "$segName.size：mkimg=${mk.size} codec=$size")
            if (mk.sha != null) must(sha == mk.sha, "$segName.sha256：mkimg=${mk.sha} codec=$sha")
        }
        val fileSha = VmdImageCodec.sha256File(out.absolutePath)
        val mkFileSha = Regex("""file sha256\s+([0-9a-f]{64})""").find(vout)?.groupValues?.get(1)
            ?: Regex("""^file_sha256=([0-9a-f]{64})""", RegexOption.MULTILINE).find(bout)?.groupValues?.get(1)
            ?: throw AssertionError("mkimg 输出缺 file sha256")
        must(fileSha == mkFileSha, "file_sha256：mkimg=$mkFileSha codec=$fileSha")

        println("      ---- mkimg --verify 关键输出 ----")
        vout.lineSequence().filter {
            it.contains("file_size") || it.contains("flags") || it.contains("offset=") || it.startsWith("VERIFY OK")
        }.forEach { println("      | $it") }
        println("      ---- codec 实测 ----")
        println("      | file_size=${f.fileSize} flags=0x${f.flags.toString(16)} file_sha256=$fileSha")
        for (n in listOf("rootfs", "kernel", "initrd", "manifest", "footer")) {
            val (off, size, sha) = segOf(f, n)
            println("      | ${"%-8s".format(n)} offset=$off size=$size sha256=${sha ?: "-"}")
        }
    }

    // ================================================================
    // 4. catalog 签名互操作（openssl dgst -sha256 -verify）
    // ================================================================
    check("catalog_signature：openssl 验签通过 + 篡改消息验签失败") {
        val cs = objOf(doc, "catalog_signature")
        val pub = File(tmp, "catalog.pub.pem").apply { writeText(cs.ostr("public_key_pem")!!) }
        val msg = File(tmp, "catalog.msg").apply { writeText(cs.ostr("message")!!) }
        val bad = File(tmp, "catalog.msg.tampered").apply { writeText(cs.ostr("tampered_message")!!) }
        val sig = File(tmp, "catalog.sig.der").apply { writeBytes(Base64.getDecoder().decode(cs.ostr("signature_b64")!!)) }

        val wantSha = cs.ostr("message_sha256")
        val gotSha = VmdImageCodec.sha256File(msg.absolutePath)
        must(gotSha == wantSha, "message_sha256：向量期望=$wantSha 实际=$gotSha")

        val (okRc, okOut) = runCmd(
            listOf("openssl", "dgst", "-sha256", "-verify", pub.absolutePath, "-signature", sig.absolutePath, msg.absolutePath),
        )
        must(okRc == 0 && okOut.contains("Verified OK"), "openssl 验签未通过 rc=$okRc: ${okOut.trim()}")
        val (badRc, badOut) = runCmd(
            listOf("openssl", "dgst", "-sha256", "-verify", pub.absolutePath, "-signature", sig.absolutePath, bad.absolutePath),
        )
        must(badRc != 0, "篡改消息竟验签成功（rc=$badRc）: ${badOut.trim()}")
        val msgFile = File(fx.root, "tests/data/catalog-signature.message.txt")
        must(msgFile.readText() == cs.ostr("message"), "message_path 内容与 vectors.json message 字段不一致")
        println("      | openssl ok: ${okOut.trim().lines().last()} · tampered: ${badOut.trim().lines().last()}")
    }

    // ================================================================
    // 5. fixture 缺失 → 必须失败（禁止静默跳过）
    // ================================================================
    check("反向：fixture 缺失必须报错（不存在的根 / 缺 .img 的根）") {
        val hit1 = try {
            requireFixtures(File(tmp, "definitely-not-a-fixtures-root"))
            throw AssertionError("不存在的 fixtures 根竟被接受（会静默跑 0 条测试却显示 OK）")
        } catch (e: FixtureProblem) {
            e.message ?: ""
        }
        val fake = File(tmp, "fake-root").apply {
            deleteRecursively()
            // 造一个"vectors.json 在、依赖占位在、但一张向量镜像都没有"的根
            File(this, "tests/vectors").mkdirs()
            File(this, "tests/data/vectors").mkdirs()
            File(this, "tools").mkdirs()
            File(this, "tests/vectors/vectors.json").writeText(fx.vectorsJson.readText())
            for (d in listOf(
                "tests/data/bare-rootfs.sqfs", "tests/data/fake-kernel.bin", "tests/data/fake-initrd.img",
                "tests/data/manifest-valid.json", "tests/data/catalog-signature.message.txt", "tools/mkimg.sh",
            )) File(this, d).createNewFile()
        }
        val hit2 = try {
            requireFixtures(fake)
            throw AssertionError("缺 .img 的 fixtures 根竟被接受（会静默跑 0 条测试却显示 OK）")
        } catch (e: FixtureProblem) {
            e.message ?: ""
        }
        must(hit1.contains("vectors.json"), "缺失场景①报错不明确：$hit1")
        must(hit2.contains("gen-vectors.sh") || hit2.contains("缺少"), "缺失场景②报错不明确：$hit2")
        println("      | missing-root → $hit1")
        println("      | missing-imgs → ${hit2.take(160)}")
    }

    println()
    println(
        "== InteropTest 汇总: $passed passed, $failed failed · " +
            "向量回放 accept=$acceptPass/3 reject=$rejectPass/18 · footer_layout=${layout.size} 字段 · " +
            "反向构建+签名+缺失各 1 ==",
    )
    if (failed > 0) {
        println("-- 失败明细（向量期望 vs codec 实际）--")
        failures.forEach { println(it) }
        exitProcess(1)
    }
    exitProcess(0)
}
