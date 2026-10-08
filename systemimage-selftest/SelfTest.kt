/*
 * 工位 F 自测（纯 JVM，无 JUnit 依赖）：SystemImageStore + BootGuard + LogDiagnostics。
 *
 * 覆盖 DESIGN §7.4（11 条权威清单逐条触发/通过样本）、§5.2（activate 五种 decision，
 * 含 RESET_REQUIRED 信号不清数据）、§6.4（校验时机）、§16.2/16.3/16.9（轮转 5 步时序、
 * meta 原子写与半截识别、image.log 轮转 reopen + JSONL、配额双条件成对删除、
 * download_progress 节流、轮转失败不阻断启动）。
 *
 * 运行方式与 fixtures 生成见 systemimage-selftest/README.md。
 */
import io.github.ltbkq.vmdroid.systemimage.AtomicFiles
import io.github.ltbkq.vmdroid.systemimage.BootGuard
import io.github.ltbkq.vmdroid.systemimage.BootGuardReason
import io.github.ltbkq.vmdroid.systemimage.BootIds
import io.github.ltbkq.vmdroid.systemimage.BootMeta
import io.github.ltbkq.vmdroid.systemimage.BootMetaInit
import io.github.ltbkq.vmdroid.systemimage.BootQuota
import io.github.ltbkq.vmdroid.systemimage.BootRotator
import io.github.ltbkq.vmdroid.systemimage.ImageEventSink
import io.github.ltbkq.vmdroid.systemimage.ImageLog
import io.github.ltbkq.vmdroid.systemimage.MiniJson
import io.github.ltbkq.vmdroid.systemimage.ProgressThrottle
import io.github.ltbkq.vmdroid.systemimage.ResultJudge
import io.github.ltbkq.vmdroid.systemimage.SystemImageStore
import io.github.ltbkq.vmdroid.systemimage.VmdImageCodec
import io.github.ltbkq.vmdroid.systemimage.VmdImageException
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
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

private lateinit var fixtures: File
private lateinit var tmpRoot: File
private var tmpSeq = 0

private fun tempDir(name: String): File {
    val d = File(tmpRoot, "$name-${tmpSeq++}")
    d.mkdirs()
    return d
}

/** versionCode 恒为 1 的 store（配合 app-old fixture 的 min_version_code=999999）。 */
private class Rec {
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    val sink = ImageEventSink { ev, fields -> events.add(ev to fields) }
}

private fun newStore(dir: File, rec: Rec? = null): SystemImageStore = SystemImageStore(
    filesDir = dir,
    versionCode = { 1L },
    events = rec?.sink,
)

/** 落位一个镜像 + meta（可选 active 记录）；identity/rootfs 尽量取自镜像自身。 */
private fun place(
    store: SystemImageStore,
    imageId: String,
    fixtureName: String,
    activate: Boolean = false,
    identityFallback: String = "debian:trixie",
): File {
    val dest = File(store.imagesDir, "$imageId.img")
    File(fixtures, fixtureName).copyTo(dest, overwrite = true)
    store.writeMeta(dest)
    if (activate) {
        val info = runCatching { VmdImageCodec.read(dest, 1L) }.getOrNull()
        store.writeActive(
            SystemImageStore.ActiveRecord(
                imageId = imageId,
                identity = info?.manifest?.image?.identity ?: identityFallback,
                rootfsSha256 = info?.footer?.rootfsSha256 ?: "0".repeat(64),
                activatedAt = SystemImageStore.isoOf(System.currentTimeMillis()),
            ),
        )
    }
    return dest
}

private fun env(name: String): Pair<SystemImageStore, BootGuard> {
    val dir = tempDir("bootguard-$name")
    val store = newStore(dir)
    return store to BootGuard(store, File(dir, "storage.img"))
}

private fun install(store: SystemImageStore, fixtureName: String): SystemImageStore.InstalledImage =
    store.install(FileInputStream(File(fixtures, fixtureName)), src = "import")

private fun assertReject(v: io.github.ltbkq.vmdroid.systemimage.BootGuardVerdict, reason: BootGuardReason) {
    must(!v.ok, "应被拒绝，实际通过：$v")
    must(v.reason == reason, "期望 $reason，实际 ${v.reason}（${v.detail}）")
    must(v.reasonName == reason.name, "reasonName 应为 ${reason.name}，实际 ${v.reasonName}")
}

private fun assertPass(v: io.github.ltbkq.vmdroid.systemimage.BootGuardVerdict, what: String) {
    must(v.ok, "$what 应通过，实际 ${v.reason}：${v.detail}")
    must(v.reason == null, "$what 不应带 reason：$v")
}

// ======================================================================
// §16.3 boot_id
// ======================================================================

private fun testBootId() {
    check("boot_id 格式 yyyyMMddTHHmmss-<6hex>") {
        repeat(50) {
            val id = BootIds.generate(System.currentTimeMillis())
            must(BootIds.PATTERN.matches(id), "格式不符：$id")
            must(id.length == 22, "长度应 22：$id")
            must(id.substring(8, 9) == "T", "缺 T 分隔：$id")
            must(id.substring(15, 16) == "-", "缺 - 分隔：$id")
        }
    }

    check("boot_id 时钟确定性（固定毫秒 → 固定时间前缀）") {
        val now = 1790000000000L
        val id = BootIds.generate(now)
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.ROOT)
        fmt.timeZone = TimeZone.getDefault()
        val expectedPrefix = fmt.format(Date(now))
        must(id.startsWith("$expectedPrefix-"), "期望前缀 $expectedPrefix，实际 $id")
        must(BootIds.PATTERN.matches(id), "格式不符：$id")
    }
}

// ======================================================================
// §16.2 轮转 5 步时序
// ======================================================================

private fun testRotationSequence() {
    check("轮转 5 步：归档到【上一个】boot_id + 新建本次 meta + 孤儿 meta 补写 killed") {
        val dir = tempDir("rot-orphan")
        // 构造：上次启动留下了 console.log 与 .last_boot_id，但进程被杀（无 meta）
        File(dir, "console.log").writeText("PREV CONSOLE LINE\n")
        File(dir, ".last_boot_id").writeText("20261001T010203-abcdef")

        val rotator = BootRotator(dir)
        val session = rotator.prepareBoot(BootMetaInit(backend = "qemu", image = "debian-minimal-arm64"))
        must(session.skippedSteps.isEmpty(), "不应有失败步骤：${session.skippedSteps}")
        must(BootIds.PATTERN.matches(session.bootId), "本次 boot_id 非法：${session.bootId}")

        // ② console.log 归档到【上一个】id（不是本次 id）
        must(!File(dir, "console.log").exists(), "console.log 应被移走（引擎 ⑤ 步才新建）")
        val archived = File(File(dir, "boots"), "20261001T010203-abcdef.console.log")
        must(archived.isFile, "未归档到上一个 boot_id")
        must(archived.readText() == "PREV CONSOLE LINE\n", "归档内容被改动")

        // ③ 孤儿 meta（上次被杀、无 meta）→ 补写 {boot_id, orphan, killed, finished_at:null}
        val prevMeta = BootMeta.read(File(File(dir, "boots"), "20261001T010203-abcdef.meta.json"))
            ?: throw AssertionError("孤儿 meta 未补写")
        must(prevMeta["boot_id"] == "20261001T010203-abcdef", "孤儿 meta boot_id 错")
        must(prevMeta["orphan"] == true, "孤儿 meta 应 orphan=true")
        must(prevMeta["result"] == "killed", "孤儿 meta 应 result=killed，实际 ${prevMeta["result"]}")
        must(prevMeta["finished_at"] == null, "孤儿 meta finished_at 应 null")

        // ④ 本次 meta（finished_at:null, result:null）+ .last_boot_id 更新
        must(File(dir, ".last_boot_id").readText().trim() == session.bootId, ".last_boot_id 未更新为本次 id")
        val newMeta = BootMeta.read(session.metaFile) ?: throw AssertionError("本次 meta 未写入")
        must(newMeta["boot_id"] == session.bootId, "本次 meta boot_id 错")
        must(newMeta["result"] == null, "本次 meta result 应 null（未 finalize）")
        must(newMeta["finished_at"] == null, "本次 meta finished_at 应 null")
        must(newMeta["backend"] == "qemu", "backend 未记录")
        must(newMeta["image"] == "debian-minimal-arm64", "image 未记录")
        must(newMeta["stages"] is List<*>, "stages 应为空数组")
        // ⑤ 引擎尚未启动 → console.log 不应被本次轮转创建
        must(!File(dir, "console.log").exists(), "console.log 应由引擎在 ⑤ 步创建")
    }

    check("轮转 5 步：第二次启动归档【第一次】的 console.log 并保留已 finalize 结果") {
        val dir = tempDir("rot-second")
        val rotator = BootRotator(dir)
        val s1 = rotator.prepareBoot(BootMetaInit(backend = "qemu"))
        // 引擎写 console.log + finalize=ready
        File(dir, "console.log").writeText("BOOT1 CONSOLE\n")
        must(
            rotator.finalizeBoot(s1, ResultJudge.READY, stages = listOf(mapOf("name" to "Ready", "t_ms" to 12000L))),
            "finalize 应成功",
        )
        // 第二次启动
        val s2 = rotator.prepareBoot(BootMetaInit(backend = "qemu"))
        must(s2.bootId != s1.bootId, "boot_id 应不同")
        val archived = File(File(dir, "boots"), "${s1.bootId}.console.log")
        must(archived.isFile && archived.readText() == "BOOT1 CONSOLE\n", "未归档上一次 console.log")
        val m1 = BootMeta.read(File(File(dir, "boots"), "${s1.bootId}.meta.json"))
            ?: throw AssertionError("上一次 meta 丢失")
        must(m1["result"] == "ready", "已 finalize 的 meta 不应被改写：$m1")
        must(m1["fail_stage"] == null, "ready 时 fail_stage 应 null")
        must(m1["stages"] is List<*> && (m1["stages"] as List<*>).size == 1, "stages 应保留")
        must(File(dir, ".last_boot_id").readText().trim() == s2.bootId, ".last_boot_id 应为第二次 id")
        val m2 = BootMeta.read(s2.metaFile)
        must(m2 != null && m2["result"] == null, "第二次 meta 应 result=null")
    }

    check("meta.json 半截（写中断）→ 读取识别 + 下次轮转重建") {
        val dir = tempDir("rot-torn")
        File(dir, ".last_boot_id").writeText("20261002T000000-dead01")
        val boots = File(dir, "boots"); boots.mkdirs()
        File(boots, "20261002T000000-dead01.meta.json")
            .writeText("""{"boot_id":"20261002T000000-dead01","result":"ready","finished_""")
        must(BootMeta.read(File(boots, "20261002T000000-dead01.meta.json")) == null, "半截 meta 应被识别为不可用")

        BootRotator(dir).prepareBoot()
        val rebuilt = BootMeta.read(File(boots, "20261002T000000-dead01.meta.json"))
            ?: throw AssertionError("半截 meta 未重建")
        must(rebuilt["result"] == "killed" && rebuilt["orphan"] == true, "重建应为孤儿 killed：$rebuilt")
        must(rebuilt["finished_at"] == null, "finished_at 应 null")
    }

    check("meta.json 已存在但未 finalize → ③ 步补写 result=killed（保留原字段）") {
        val dir = tempDir("rot-unfinalized")
        File(dir, ".last_boot_id").writeText("20261003T000000-abc000")
        val boots = File(dir, "boots"); boots.mkdirs()
        BootMeta.write(
            File(boots, "20261003T000000-abc000.meta.json"),
            mapOf(
                "boot_id" to "20261003T000000-abc000",
                "started_at" to "2026-10-03T00:00:00.000+08:00",
                "finished_at" to null,
                "result" to null,
                "orphan" to false,
            ),
        )
        BootRotator(dir).prepareBoot()
        val m = BootMeta.read(File(boots, "20261003T000000-abc000.meta.json"))!!
        must(m["result"] == "killed", "应补写 killed，实际 ${m["result"]}")
        must(m["started_at"] == "2026-10-03T00:00:00.000+08:00", "原字段应保留")
        must(m["finished_at"] == null, "finished_at 应 null")
    }
}

// ======================================================================
// §16.3 meta.json 原子写
// ======================================================================

private fun testAtomicMetaWrite() {
    check("meta.json 原子写（temp+fsync+rename，无 .tmp 残留，重复写可读）") {
        val dir = tempDir("atomic")
        val target = File(dir, "20261008T142231-a1b2c3.meta.json")
        repeat(3) { i ->
            BootMeta.write(target, mapOf("boot_id" to "20261008T142231-a1b2c3", "result" to null, "n" to i))
            must(!File(dir, "20261008T142231-a1b2c3.meta.json.tmp").exists(), "tmp 文件残留")
            val m = BootMeta.read(target)
            must(m != null && m["n"] == i.toLong(), "第 $i 次写后读取失败：$m")
        }
    }

    check("meta.json 写中断模拟：半截文件下次读取能识别并重建") {
        val dir = tempDir("atomic-torn")
        val target = File(dir, "x.meta.json")
        BootMeta.write(target, mapOf("boot_id" to "x", "result" to "ready"))
        // 绕过原子写，模拟写到一半断电
        target.writeText("""{"boot_id":"x","result":"rea""")
        must(BootMeta.read(target) == null, "半截 JSON 应返回 null")
        // 重建：AtomicFiles 覆盖写后必须是完整 JSON
        AtomicFiles.write(target, MiniJson.write(mapOf("boot_id" to "x", "result" to "killed", "finished_at" to null)))
        val m = BootMeta.read(target)
        must(m != null && m["result"] == "killed", "重建失败：$m")
        must(!File(dir, "x.meta.json.tmp").exists(), "tmp 残留")
    }

    check("MiniJson 解析/序列化往返（含转义与嵌套）") {
        val src = mapOf(
            "ts" to "2026-10-08T14:22:31.412+08:00",
            "ev" to "activate",
            "n" to 42L,
            "f" to 1.5,
            "b" to true,
            "nil" to null,
            "quote" to "a\"b\\c\nd",
            "nested" to mapOf("x" to listOf(1L, 2L, "三")),
        )
        val text = MiniJson.write(src)
        val back = MiniJson.parseObject(text)
        must(back["ts"] == "2026-10-08T14:22:31.412+08:00", "ts 往返失败")
        must(back["n"] == 42L && back["f"] == 1.5 && back["b"] == true, "标量往返失败")
        must(back["nil"] == null, "null 往返失败")
        must(back["quote"] == "a\"b\\c\nd", "转义往返失败：${back["quote"]}")
        must((back["nested"] as? Map<*, *>)?.get("x") is List<*>, "嵌套往返失败")
        // 半截必须抛异常（调用方据识别）
        var threw = false
        try {
            MiniJson.parseObject(text.substring(0, text.length / 2))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        must(threw, "半截 JSON 应抛 IllegalArgumentException")
    }
}

// ======================================================================
// §16.3/§16.5/§16.9 image.log
// ======================================================================

private fun testImageLog() {
    check("image.log JSONL 逐行可解析（ts/boot/ev 必备）") {
        val dir = tempDir("imglog")
        val log = ImageLog(File(dir, "image.log"))
        log.setBootId("20261008T142231-a1b2c3")
        log.record("import_start", mapOf("image" to "debian-minimal-arm64", "src_uri" to "content://x"))
        log.record("verify_ok", mapOf("image" to "debian-minimal-arm64", "sha256" to "ab12", "dur_ms" to 42L), sync = true)
        log.record("bootguard_reject", mapOf("reason" to "CORRUPT", "detail" to "meta missing"))
        must(log.flush(), "flush 超时")
        log.close()

        val lines = File(dir, "image.log").readLines().filter { it.isNotBlank() }
        must(lines.size == 3, "应 3 行，实际 ${lines.size}")
        for ((i, line) in lines.withIndex()) {
            val m = MiniJson.parseObject(line) // 逐行可解析
            must(m["ev"] is String, "第 $i 行缺 ev")
            must(m["ts"] is String, "第 $i 行缺 ts")
            must(m["boot"] == "20261008T142231-a1b2c3", "第 $i 行 boot 错：${m["boot"]}")
        }
        must(lines[0].contains("\"ev\":\"import_start\""), "ev 字段错：${lines[0]}")
        must(lines[2].contains("\"reason\":\"CORRUPT\""), "字段丢失：${lines[2]}")
    }

    check("image.log 轮转 rename→reopen（新文件非空、≤3 个文件、行可解析）") {
        val dir = tempDir("imglog-rot")
        val file = File(dir, "image.log")
        val log = ImageLog(file, maxFiles = 3, maxBytes = 512)
        log.setBootId("20261008T142231-b0b0b0")
        repeat(60) { i ->
            log.record("download_progress", mapOf("bytes" to (i * 100L), "total" to 6000L))
        }
        must(log.flush(), "flush 超时")
        // 轮转之后再写 → reopen 的新文件必须非空
        log.record(
            "activate",
            mapOf("from" to null, "to" to "debian-minimal-arm64", "decision" to "upgrade", "reset" to false),
            sync = true,
        )
        must(log.flush(), "flush 超时")
        log.close()

        must(file.isFile && file.length() > 0, "轮转后 reopen 的新文件应非空")
        val lines = file.readLines().filter { it.isNotBlank() }
        must(lines.isNotEmpty(), "新文件应有行")
        for (line in lines) MiniJson.parseObject(line)
        must(lines.last().contains("\"ev\":\"activate\""), "关键行应在新文件中")

        val b1 = File(dir, "image.log.1")
        must(b1.isFile, "应有滚动副本 image.log.1")
        for (line in b1.readLines().filter { it.isNotBlank() }) MiniJson.parseObject(line)

        val files = dir.listFiles()!!.filter { it.name.startsWith("image.log") }
        must(files.size <= 3, "3×2MB 配额：最多 3 个文件，实际 ${files.size}：$files")
        must(!File(dir, "image.log.3").exists(), "不应存在 image.log.3")
    }

    check("download_progress 节流：每 5% 或 4MB，取较稀者") {
        val total = 100L * 1024 * 1024 // 5% = 5MB > 4MB → 阈值 5MB
        val t = ProgressThrottle()
        must(t.shouldLog(0L, total), "首次必记")
        must(!t.shouldLog(4L * 1024 * 1024, total), "4MB < 5MB 应节流")
        must(t.shouldLog(5L * 1024 * 1024, total), "达到 5% 应记一行")
        must(!t.shouldLog(9L * 1024 * 1024, total), "9-5=4MB < 5MB 应节流")
        must(t.shouldLog(10L * 1024 * 1024, total), "10-5=5MB 应记一行")

        val t2 = ProgressThrottle()
        must(t2.shouldLog(0L, null), "未知总量：首次必记")
        must(!t2.shouldLog(3L * 1024 * 1024, null), "未知总量：4MB 阈值应节流")
        must(t2.shouldLog(4L * 1024 * 1024, null), "未知总量：4MB 应记一行")
    }
}

// ======================================================================
// §16.9 配额
// ======================================================================

private fun testQuota() {
    check("boots 配额 ≤10 次 ∧ ≤50MB：双条件取更严者 + 成对删除") {
        val boots = File(tempDir("quota"), "boots")
        boots.mkdirs()
        for (i in 1..12) {
            val id = String.format("20261008T%02d0000-%06d", i, i)
            File(boots, "$id.console.log").writeBytes(ByteArray(1024))
            File(boots, "$id.meta.json").writeText("""{"boot_id":"$id","result":"ready"}""")
        }
        val left = BootQuota.enforce(boots)
        must(left == 10, "10 次条件：应留 10，实际 $left")
        val ids = BootQuota.ids(boots)
        must(ids.size == 10, "ids 应 10，实际 ${ids.size}")
        must(ids.first() == "20261008T030000-000003" && ids.last() == "20261008T120000-000012",
            "应从最旧开始删（01/02 先删）：$ids")
        for (id in ids) {
            must(File(boots, "$id.console.log").isFile && File(boots, "$id.meta.json").isFile,
                "成对删除被破坏（残缺条目）：$id")
        }

        // 8MB × 10 = 80MB > 50MB → Σ 条件更严 → 实留 6 条（§16.10 用例）
        for (id in ids) File(boots, "$id.console.log").writeBytes(ByteArray(8 * 1024 * 1024))
        val left2 = BootQuota.enforce(boots)
        must(left2 == 6, "8MB×N：应留 6，实际 $left2")
        val ids2 = BootQuota.ids(boots)
        must(ids2 == ids.takeLast(6), "应保留最新的 6 条：$ids2")
        for (id in ids2) {
            must(File(boots, "$id.console.log").isFile && File(boots, "$id.meta.json").isFile,
                "成对删除被破坏：$id")
        }
        val total = ids2.sumOf { File(boots, "$it.console.log").length() + File(boots, "$it.meta.json").length() }
        must(total <= 50L * 1024 * 1024, "Σ 应 ≤50MB，实际 $total")
    }

    check("boots 配额：目录不存在 → 0，不抛异常") {
        must(BootQuota.enforce(File(tempDir("quota-none"), "missing")) == 0, "缺失目录应返回 0")
    }
}

// ======================================================================
// §7.4 BootGuard 11 条
// ======================================================================

private fun testBootGuard() {
    // #1 NO_SYSTEM_IMAGE -------------------------------------------------
    check("BootGuard #1 NO_SYSTEM_IMAGE 触发（无激活记录）") {
        val (_, guard) = env("n1")
        assertReject(guard.checkBeforeStart(), BootGuardReason.NO_SYSTEM_IMAGE)
    }
    check("BootGuard #1 通过样本（已安装且已激活）") {
        val (store, guard) = env("p1")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#1")
    }

    // #2 CORRUPT ---------------------------------------------------------
    check("BootGuard #2 CORRUPT 触发（.meta.json 缺失）") {
        val (store, guard) = env("n2a")
        val dest = File(store.imagesDir, "debian-minimal-arm64.img")
        File(fixtures, "debian-minimal.img").copyTo(dest, overwrite = true)
        store.writeActive(SystemImageStore.ActiveRecord("debian-minimal-arm64", "debian:trixie", "0".repeat(64),
            SystemImageStore.isoOf(System.currentTimeMillis())))
        assertReject(guard.checkBeforeStart(), BootGuardReason.CORRUPT)
    }
    check("BootGuard #2 CORRUPT 触发（.meta.json 半截）") {
        val (store, guard) = env("n2b")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        File(store.imagesDir, "debian-minimal-arm64.img.meta.json").writeText("""{"sha256":"abc","size":10567""")
        assertReject(guard.checkBeforeStart(), BootGuardReason.CORRUPT)
    }
    check("BootGuard #2 CORRUPT 触发（size/mtime 不一致 + 全量重算失败）") {
        val (store, guard) = env("n2c")
        val dest = place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        val bytes = dest.readBytes()
        bytes[200] = (bytes[200].toInt() xor 0xFF).toByte() // 翻转 rootfs 一字节 → 段 sha 不匹配
        dest.writeBytes(bytes)
        dest.setLastModified(dest.lastModified() + 5000)     // size 不变、mtime 变 → 快速路径失败
        assertReject(guard.checkBeforeStart(), BootGuardReason.CORRUPT)
    }
    check("BootGuard #2 通过样本（size/mtime 与 meta 一致，不重算 sha256）") {
        val (store, guard) = env("p2")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#2")
    }

    // #3 NOT_AN_IMAGE ----------------------------------------------------
    check("BootGuard #3 NOT_AN_IMAGE 触发（footer magic 不符）") {
        val (store, guard) = env("n3")
        place(store, "junkimg", "junk.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.NOT_AN_IMAGE)
    }
    check("BootGuard #3 通过样本") {
        val (store, guard) = env("p3")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#3")
    }

    // #4 FORMAT_UNSUPPORTED ---------------------------------------------
    check("BootGuard #4 FORMAT_UNSUPPORTED 触发（format_version=2）") {
        val (store, guard) = env("n4")
        place(store, "fmtimg", "format2.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.FORMAT_UNSUPPORTED)
    }
    check("BootGuard #4 通过样本（format_version=1）") {
        val (store, guard) = env("p4")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#4")
    }

    // #5 APP_TOO_OLD -----------------------------------------------------
    check("BootGuard #5 APP_TOO_OLD 触发（min_version_code=999999 > 1）") {
        val (store, guard) = env("n5")
        place(store, "appoldimg", "app-old.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.APP_TOO_OLD)
    }
    check("BootGuard #5 通过样本（min_version_code=1 ≤ 1）") {
        val (store, guard) = env("p5")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#5")
    }

    // #6 ARCH_MISMATCH ---------------------------------------------------
    check("BootGuard #6 ARCH_MISMATCH 触发（arch=amd64）") {
        val (store, guard) = env("n6")
        place(store, "badarchimg", "bad-arch.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.ARCH_MISMATCH)
    }
    check("BootGuard #6 通过样本（arch=arm64）") {
        val (store, guard) = env("p6")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#6")
    }

    // #7 IMAGE_ID_INVALID ------------------------------------------------
    check("BootGuard #7 IMAGE_ID_INVALID 触发（active.json 的 image_id 非法）") {
        val (store, guard) = env("n7a")
        place(store, "BadId", "debian-minimal.img")
        store.writeActive(SystemImageStore.ActiveRecord("BadId", "debian:trixie", "0".repeat(64),
            SystemImageStore.isoOf(System.currentTimeMillis())))
        assertReject(guard.checkBeforeStart(), BootGuardReason.IMAGE_ID_INVALID)
    }
    check("BootGuard #7 IMAGE_ID_INVALID 触发（manifest image.id 非法）") {
        val (store, guard) = env("n7b")
        place(store, "badimg", "bad-id.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.IMAGE_ID_INVALID)
    }
    check("BootGuard #7 通过样本（id 匹配 ^[a-z0-9][a-z0-9._-]{0,63}$）") {
        val (store, guard) = env("p7")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#7")
    }

    // #8 SSH_CAPABILITY_MISSING -----------------------------------------
    check("BootGuard #8 SSH_CAPABILITY_MISSING 触发（capabilities.ssh=false）") {
        val (store, guard) = env("n8")
        place(store, "sshoffimg", "ssh-off.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.SSH_CAPABILITY_MISSING)
    }
    check("BootGuard #8 通过样本（capabilities.ssh=true）") {
        val (store, guard) = env("p8")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#8")
    }

    // #9 SSH_PORT_INVALID ------------------------------------------------
    check("BootGuard #9 SSH_PORT_INVALID 触发（ssh_port=2222）") {
        val (store, guard) = env("n9")
        place(store, "badportimg", "bad-port.img", activate = true)
        assertReject(guard.checkBeforeStart(), BootGuardReason.SSH_PORT_INVALID)
    }
    check("BootGuard #9 通过样本（ssh_port=22）") {
        val (store, guard) = env("p9")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        assertPass(guard.checkBeforeStart(), "#9")
    }

    // #10 RESET_REQUIRED -------------------------------------------------
    check("BootGuard #10 RESET_REQUIRED 触发（激活时 identity 变化）") {
        val (store, guard) = env("n10")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        place(store, "alpine-minimal-arm64", "alpine-minimal.img") // 已安装、未激活
        val v = guard.checkForActivation("alpine-minimal-arm64")
        assertReject(v, BootGuardReason.RESET_REQUIRED)
        must(v.detail.contains("decision=identity"), "detail 应含 decision：${v.detail}")
    }
    check("BootGuard #10 通过样本（同 identity 升级 → decision=upgrade 不重置）") {
        val (store, guard) = env("p10")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        place(store, "debian-desktop-arm64", "debian-desktop.img")
        assertPass(guard.checkForActivation("debian-desktop-arm64"), "#10")
    }

    // #11 storage.img 缺失 = 非拒绝 --------------------------------------
    check("BootGuard #11 storage.img 缺失不拒绝（由 ensureStorageImage 创建）") {
        val (store, guard) = env("n11")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        must(!File(store.imagesDir.parentFile, "storage.img").exists(), "前置条件：storage.img 应不存在")
        val v = guard.checkBeforeStart()
        assertPass(v, "#11（storage 缺失）")
        must(!v.storagePresent, "storagePresent 应为 false")
    }
    check("BootGuard #11 通过样本（storage.img 存在）") {
        val (store, guard) = env("p11")
        place(store, "debian-minimal-arm64", "debian-minimal.img", activate = true)
        File(store.imagesDir.parentFile, "storage.img").writeBytes(ByteArray(4096))
        val v = guard.checkBeforeStart()
        assertPass(v, "#11")
        must(v.storagePresent, "storagePresent 应为 true")
    }
}

// ======================================================================
// §5.2 activate 三态（+ 硬判据）
// ======================================================================

private fun testActivate() {
    check("install → list → verify → delete 基础路径（.part 不残留、原子 rename）") {
        val dir = tempDir("act-base")
        val rec = Rec()
        val store = newStore(dir, rec)
        val inst = install(store, "debian-minimal.img")
        must(inst.imageId == "debian-minimal-arm64", "image.id 错：${inst.imageId}")
        must(inst.meta != null, "meta 应已写入")
        must(File(store.imagesDir, "debian-minimal-arm64.img").isFile, "镜像文件应在")
        must(store.imagesDir.listFiles()!!.none { it.name.endsWith(".part") }, "不应残留 .part")
        must(store.list().map { it.imageId } == listOf("debian-minimal-arm64"), "list 错：${store.list()}")

        must(store.verify("debian-minimal-arm64", full = false).ok, "快速校验应通过")
        val full = store.verify("debian-minimal-arm64", full = true)
        must(full.ok, "全量校验应通过：${full.detail}")
        must(full.sha256 == inst.meta!!.sha256, "全量 sha256 应与 meta 一致")

        must(store.delete("debian-minimal-arm64"), "delete 应返回 true")
        must(store.list().isEmpty(), "删除后应为空")
        must(store.active() == null, "删除激活镜像应清 active.json")
        must(rec.events.any { it.first == "import_done" }, "应记 import_done")
        must(rec.events.any { it.first == "verify_ok" }, "应记 verify_ok")
        must(rec.events.any { it.first == "install_delete" }, "应记 install_delete")
    }

    check("activate same：重复激活同一镜像 → decision=same，不重置") {
        val dir = tempDir("act-same")
        val rec = Rec()
        val store = newStore(dir, rec)
        val id = install(store, "debian-minimal.img").imageId
        val first = store.activate(id)
        must(first is SystemImageStore.ActivateResult.Activated, "首次激活应成功：$first")
        val again = store.activate(id)
        must(again is SystemImageStore.ActivateResult.Activated, "重复激活应成功：$again")
        must(again.decision == "same", "应 decision=same，实际 ${again.decision}")
        must(rec.events.count { it.first == "activate" } == 2, "应记 2 次 activate")
        val act = rec.events.last { it.first == "activate" }.second
        must(act["decision"] == "same" && act["reset"] == false, "activate 事件错：$act")
    }

    check("activate upgrade：同 identity 换镜像 → decision=upgrade，不重置") {
        val dir = tempDir("act-upgrade")
        val store = newStore(dir)
        store.activate(install(store, "debian-minimal.img").imageId)
        val up = store.activate(install(store, "debian-desktop.img").imageId)
        must(up is SystemImageStore.ActivateResult.Activated, "upgrade 应直接激活：$up")
        must(up.decision == "upgrade", "应 decision=upgrade，实际 ${up.decision}")
        must(store.active()?.imageId == "debian-desktop-arm64", "active 应切换")
        // 同 identity（§5.2：system_version 升级无需重置）
        must(store.active()?.identity == "debian:trixie", "identity 应保持")
    }

    check("activate identity：返回 RESET_REQUIRED 信号（不写 active.json、不清 storage.img）") {
        val dir = tempDir("act-identity")
        val rec = Rec()
        val store = newStore(dir, rec)
        store.activate(install(store, "debian-minimal.img").imageId)
        val storage = File(dir, "storage.img")
        storage.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val alpineId = install(store, "alpine-minimal.img").imageId
        val r = store.activate(alpineId)
        must(r is SystemImageStore.ActivateResult.ResetRequired, "应返回 ResetRequired，实际 $r")
        must((r as SystemImageStore.ActivateResult.ResetRequired).decision == "identity",
            "应 decision=identity，实际 ${r.decision}")
        must(store.active()?.imageId == "debian-minimal-arm64", "active.json 不应被改写")
        must(storage.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4, 5)), "storage.img 不应被清理")
        val act = rec.events.last { it.first == "activate" }.second
        must(act["decision"] == "identity" && act["reset"] == true, "image.log 缺 activate.decision 记录：$act")
    }

    check("activate contract/init 硬判据 → RESET_REQUIRED") {
        val dir = tempDir("act-hard")
        val store = newStore(dir)
        store.activate(install(store, "debian-minimal.img").imageId)
        store.activate(install(store, "debian-desktop.img").imageId) // active: contract=1 init=systemd

        val c2 = store.activate(install(store, "debian-contract2.img").imageId)
        must(c2 is SystemImageStore.ActivateResult.ResetRequired && c2.decision == "contract",
            "contract 变化应要求重置：$c2")
        must(store.active()?.imageId == "debian-desktop-arm64", "contract 拒绝时 active 不应变")

        val init = store.activate(install(store, "debian-openrc.img").imageId)
        must(init is SystemImageStore.ActivateResult.ResetRequired && init.decision == "init",
            "init 变化应要求重置：$init")

        // 用户确认 + 重建 storage.img 后（此处模拟 §8.5 确认路径）→ allowReset=true 再激活成功
        val ok = store.activate("debian-openrc-arm64", allowReset = true)
        must(ok is SystemImageStore.ActivateResult.Activated, "确认后应可激活：$ok")
        must(store.active()?.imageId == "debian-openrc-arm64", "确认后 active 应切换")
    }
}

// ======================================================================
// §16.2 R3: C-R3-6 轮转/写失败不阻断
// ======================================================================

private fun testFailuresDoNotBlock() {
    check("轮转失败不阻断启动（boots 被文件占位 → mkdirs 必失败）") {
        val dir = tempDir("rot-unwritable")
        File(dir, "boots").writeText("I am a file, not a directory")
        File(dir, "console.log").writeText("PREV\n")
        File(dir, ".last_boot_id").writeText("20261005T000000-ff0000")
        val warns = mutableListOf<String>()
        val rotator = BootRotator(dir, warn = { m, _ -> warns += m })

        val session = try {
            rotator.prepareBoot()
        } catch (t: Throwable) {
            throw AssertionError("prepareBoot 不得抛出给调用方：$t")
        }
        must(BootIds.PATTERN.matches(session.bootId), "仍应返回有效 boot_id：${session.bootId}")
        must(session.skippedSteps.isNotEmpty(), "应记录被跳过的步骤")
        must(warns.isNotEmpty(), "应向调用方报告 warning")
        must(warns.all { it.contains("start continues") }, "warning 应说明不阻断启动：$warns")
        // .last_boot_id 写入步骤仍可成功（与 boots 无关）
        must(File(dir, ".last_boot_id").readText().trim() == session.bootId, ".last_boot_id 应仍更新")
    }

    check("image.log 写失败不阻断（目标是目录 → 打不开）") {
        val dir = tempDir("log-unwritable")
        File(dir, "image.log").mkdirs() // 让 FileOutputStream 打开失败
        val warns = mutableListOf<String>()
        val log = ImageLog(File(dir, "image.log"), warn = { m, _ -> warns += m })
        try {
            log.setBootId("20261008T142231-dead00")
            log.record("activate", mapOf("to" to "x", "decision" to "same", "reset" to false), sync = true)
            log.record("verify_fail", mapOf("fail" to "sha256"), sync = true)
            log.flush(2_000)
        } catch (t: Throwable) {
            log.close()
            throw AssertionError("record/flush 不得抛出给调用方：$t")
        }
        log.close()
        must(warns.isNotEmpty(), "应记录写失败 warning")
    }

    check("finalize 失败不抛出（meta 目录不可写）") {
        val dir = tempDir("fin-unwritable")
        File(dir, "boots").writeText("occupied")
        val rotator = BootRotator(dir, warn = { _, _ -> })
        val session = rotator.prepareBoot()
        val ok = rotator.finalizeBoot(session, ResultJudge.TIMEOUT, failStage = "Starting SSH...")
        must(!ok, "finalize 应报告失败")
    }
}

// ======================================================================
// main
// ======================================================================

private fun resolveFixtures(args: Array<String>): File {
    val candidates = mutableListOf<File>()
    if (args.isNotEmpty()) candidates += File(args[0])
    candidates += File("systemimage-selftest/fixtures")
    candidates += File("../systemimage-selftest/fixtures")
    for (c in candidates) {
        if (File(c, "debian-minimal.img").isFile) return c
    }
    throw IllegalStateException(
        "fixtures not found — run: python3 systemimage-selftest/gen_fixtures.py (tried: $candidates)",
    )
}

fun main(args: Array<String>) {
    fixtures = resolveFixtures(args)
    tmpRoot = File("systemimage-selftest/out/tmp")
    tmpRoot.deleteRecursively()
    tmpRoot.mkdirs()
    println("fixtures : ${fixtures.absolutePath}")
    println("tmp      : ${tmpRoot.absolutePath}")
    println("")

    testBootId()
    testRotationSequence()
    testAtomicMetaWrite()
    testImageLog()
    testQuota()
    testBootGuard()
    testActivate()
    testFailuresDoNotBlock()

    println("")
    println("TOTAL: $passed passed, $failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
