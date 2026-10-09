/*
 * VMDroid - boot/image logging & rotation (DESIGN §16, pure JVM).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN.md §16.2（文件布局 + 轮转 5 步时序 + 后端差异 + 失败不阻断）
 *     §16.3（boot_id / 结构化格式 / 写入协议 / result 四值） §16.4（meta.json 模式）
 *     §16.5（image.log 事件与节流） §16.9（配额与轮转硬上限）
 *
 * **分层**：本文件全部为纯 JVM 类（路径/解析/配额/时序/结果判定），零 `android.*` 依赖；
 * logcat、Context、通知等 Android 侧只在 VmdroidService 薄壳里（注入 `warn` 回调即可）。
 * 由 systemimage-selftest/SelfTest.kt（无 JUnit）覆盖。
 *
 * 铁律（§16.2 R3: C-R3-6）：**轮转/写日志失败绝不阻断启动** —— 每一步独立 try/catch，
 * 只 warn 不抛。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom

// ======================================================================
// boot_id（§16.3）
// ======================================================================

/**
 * `boot_id` = `yyyyMMddTHHmmss-<6hex>`（§16.3），**本次启动前**（④ 步）生成，
 * 同时写入 `boots/<id>.meta.json` 与 `image.log` 的 `boot` 字段。
 */
object BootIds {
    val PATTERN: Regex = Regex("^[0-9]{8}T[0-9]{6}-[0-9a-f]{6}$")
    private const val HEX = "0123456789abcdef"

    /** 生成一个 boot_id（本地时区时间前缀 + 6 位随机 hex）。 */
    @JvmStatic
    fun generate(nowMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)
        fmt.timeZone = TimeZone.getDefault()
        val rnd = ThreadLocalRandom.current()
        val tail = buildString(6) { repeat(6) { append(HEX[rnd.nextInt(16)]) } }
        return "${fmt.format(Date(nowMs))}-$tail"
    }
}

// ======================================================================
// result 判定（§16.3）
// ======================================================================

/**
 * `meta.json.result` 四值判定（§16.3）：
 * - `ready`   = detector 真实命中 `Ready!`
 * - `failed`  = 引擎 `VmState.Error` / 非零退出（未命中 Ready）
 * - `timeout` = 90s 未命中（诊断判据；不因引擎 120s 安全网合成 Ready 而改写）
 * - `killed`  = 用户 Stop 或进程被回收（下次 ③ 步补写）
 *
 * 优先级：ready > failed > timeout > killed（引擎已死优先于"还在等"）。
 */
object ResultJudge {
    const val READY = "ready"
    const val TIMEOUT = "timeout"
    const val FAILED = "failed"
    const val KILLED = "killed"

    val ALL: List<String> = listOf(READY, TIMEOUT, FAILED, KILLED)

    @JvmStatic
    fun decide(readyHit: Boolean, engineFailed: Boolean, timedOut: Boolean): String = when {
        readyHit -> READY
        engineFailed -> FAILED
        timedOut -> TIMEOUT
        else -> KILLED
    }

    @JvmStatic
    fun isValid(value: String?): Boolean = value in ALL
}

// ======================================================================
// boot meta.json（§16.3/§16.4）
// ======================================================================

/** `boots/<boot_id>.meta.json` 的读写（temp + fsync + rename，任何时刻磁盘上是完整 JSON）。 */
object BootMeta {
    /** 读 meta；文件缺失或**半截/损坏 JSON** → null（调用方按孤儿补写，§16.2 ③）。 */
    @JvmStatic
    fun read(file: File): Map<String, Any?>? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return null
        }
        return try {
            MiniJson.parseObject(text)
        } catch (e: IllegalArgumentException) {
            null // 写中断留下的半截文件 → 识别为不可用
        }
    }

    /** 原子写（§16.3 写入协议）。失败抛 [IOException]（由 BootRotator 的 guard 吞掉）。 */
    @JvmStatic
    fun write(file: File, meta: Map<String, Any?>) {
        AtomicFiles.write(file, MiniJson.write(meta))
    }
}

// ======================================================================
// 轮转 5 步时序（§16.2）
// ======================================================================

/** 启动元信息（④ 步新建 meta 用；null 字段在磁盘上写为 JSON null）。 */
data class BootMetaInit(
    val backend: String? = null,          // qemu | avf（§16.2 后端差异）
    val image: String? = null,
    val imageSha256: String? = null,
    val appVersion: String? = null,
    val systemVersion: Long? = null,
    val qemuArgv: String? = null,         // verbose 开启时附加（§16.7）
)

/** 一次启动的日志会话（④ 步产物；`metaFile` 供后续 finalize 覆写）。 */
data class BootSession(
    val bootId: String,
    val metaFile: File,
    val consoleFile: File,
    val startedAt: String,
    /** 被 guard 吞掉的失败步骤名（空 = 轮转全成功；**不影响启动**）。 */
    val skippedSteps: List<String> = emptyList(),
)

/**
 * §16.2 轮转时序（① 读 `.last_boot_id` → ② 归档上次 `console.log` →
 * ③ 合并/写孤儿 meta → ④ 写本次 meta + `.last_boot_id` → ⑤ 调用方启动引擎）。
 *
 * 每一步独立 try/catch：任一步失败 → [warn] + 记入 [BootSession.skippedSteps]，
 * **绝不抛给调用方**（R3: C-R3-6：日志不能成为启动的前置依赖）。
 */
class BootRotator @JvmOverloads constructor(
    private val filesDir: File,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val bootsDir: File get() = File(filesDir, BOOTS_DIR)
    val consoleFile: File get() = File(filesDir, CONSOLE_FILE)
    private val lastBootIdFile: File get() = File(filesDir, LAST_BOOT_ID_FILE)

    /** 执行 ①–④；返回本次会话（永不抛异常）。⑤ `engine.start()` 由调用方执行。 */
    fun prepareBoot(init: BootMetaInit = BootMetaInit()): BootSession {
        val skipped = mutableListOf<String>()
        fun guard(step: String, body: () -> Unit) {
            try {
                body()
            } catch (t: Throwable) {
                skipped += step
                warn("boot log rotation step '$step' failed — start continues: ${t.message}", t)
            }
        }

        // ① 读 filesDir/.last_boot_id → prev_id（不存在/损坏则跳过 ②③）
        var prev: String? = null
        guard(STEP_LAST_ID) { prev = readLastBootId() }
        val prevId = prev
        if (prevId != null) {
            // ② 上一次启动的 console.log → boots/<prev_id>.console.log（原样归档）
            guard(STEP_ARCHIVE) { archiveConsole(prevId) }
            // ③ 与已存在的 boots/<prev_id>.meta.json 合并（缺失/半截 → 补写孤儿 killed）
            guard(STEP_MERGE) { mergePrevMeta(prevId) }
        }

        // ④ 生成本次 boot_id → 写 meta（finished_at:null, result:null）→ 写 .last_boot_id
        val bootId = BootIds.generate(nowMs())
        val startedAt = SystemImageStore.isoOf(nowMs())
        val metaFile = metaFile(bootId)
        guard(STEP_NEW_META) { BootMeta.write(metaFile, initialMeta(bootId, startedAt, init)) }
        guard(STEP_LAST_ID) { AtomicFiles.write(lastBootIdFile, bootId) }
        // 配额：成对删除（§16.9；失败也不阻断）
        guard(STEP_QUOTA) { BootQuota.enforce(bootsDir) }

        // ⑤ engine.start() —— 引擎此时才以 truncate 模式新建 console.log
        return BootSession(bootId, metaFile, consoleFile, startedAt, skipped)
    }

    /**
     * finalize：`Ready!`/超时/停止时**再写一次**覆盖（§16.3）。永不抛异常。
     * @return true = 写成功
     */
    @JvmOverloads
    fun finalizeBoot(
        session: BootSession,
        result: String,
        failStage: String? = null,
        stages: List<Map<String, Any?>> = emptyList(),
        consoleTailB64: String? = null,
        consoleTailAbsent: Boolean = false,
        extra: Map<String, Any?> = emptyMap(),
    ): Boolean = finalizeBoot(session.bootId, result, failStage, stages, consoleTailB64, consoleTailAbsent, extra)

    /** 同上（按 boot_id；meta 缺失时从零重建）。 */
    @JvmOverloads
    fun finalizeBoot(
        bootId: String,
        result: String,
        failStage: String? = null,
        stages: List<Map<String, Any?>> = emptyList(),
        consoleTailB64: String? = null,
        consoleTailAbsent: Boolean = false,
        extra: Map<String, Any?> = emptyMap(),
    ): Boolean {
        val file = metaFile(bootId)
        return try {
            val base = BootMeta.read(file) ?: initialMeta(bootId, SystemImageStore.isoOf(nowMs()), BootMetaInit()).toMutableMap()
            val meta = LinkedHashMap(base)
            meta["boot_id"] = bootId
            meta["finished_at"] = SystemImageStore.isoOf(nowMs())
            meta["result"] = result
            meta["fail_stage"] = failStage
            if (stages.isNotEmpty()) meta["stages"] = stages
            if (consoleTailB64 != null) meta["console_tail_b64"] = consoleTailB64
            meta["console_tail_absent"] = consoleTailAbsent
            for ((k, v) in extra) meta[k] = v
            BootMeta.write(file, meta)
            true
        } catch (t: Throwable) {
            warn("finalize boot meta failed for $bootId — ignored: ${t.message}", t)
            false
        }
    }

    /** `fail_stage` 规则（§16.4）：stages 非空 → 最后一项 name；空 → null（卡在极早期）。 */
    fun failStageOf(stages: List<Map<String, Any?>>): String? = stages.lastOrNull()?.get("name") as? String

    // ------------------------------------------------------------ 内部步骤

    private fun metaFile(bootId: String) = File(bootsDir, "$bootId$META_SUFFIX")

    private fun readLastBootId(): String? {
        if (!lastBootIdFile.isFile) return null
        return lastBootIdFile.readText().trim().takeIf { it.isNotEmpty() && BootIds.PATTERN.matches(it) }
    }

    private fun archiveConsole(prevId: String) {
        val console = consoleFile
        if (!console.isFile) return
        val dest = File(bootsDir, "$prevId$CONSOLE_SUFFIX")
        AtomicFiles.move(console, dest) // 同文件系统 rename；半截 console.log 原样归档，不修补
        truncateHead(dest, MAX_CONSOLE_ARCHIVE_BYTES) // §16.9 归档后 8MB：截头保尾
    }

    private fun mergePrevMeta(prevId: String) {
        val file = File(bootsDir, "$prevId$META_SUFFIX")
        val existing = BootMeta.read(file)
        when {
            // 上次进程被杀/崩溃，或 meta 半截（写中断）→ 补写孤儿记录（§16.2 ③）
            existing == null -> BootMeta.write(
                file,
                linkedMapOf(
                    "boot_id" to prevId,
                    "orphan" to true,
                    "result" to ResultJudge.KILLED,
                    "finished_at" to null,
                ),
            )
            // meta 在但从未 finalize（同一次被杀，meta 有幸留下）→ 补 result=killed
            existing["result"] == null -> {
                val merged = LinkedHashMap(existing)
                merged["boot_id"] = prevId
                merged["result"] = ResultJudge.KILLED
                if (merged["finished_at"] == null) merged["finished_at"] = null
                merged["orphan"] = false
                BootMeta.write(file, merged)
            }
            else -> Unit // 已 finalize，原样保留
        }
    }

    private fun initialMeta(bootId: String, startedAt: String, init: BootMetaInit): Map<String, Any?> =
        linkedMapOf(
            "boot_id" to bootId,
            "started_at" to startedAt,
            "finished_at" to null,
            "backend" to init.backend,
            "image" to init.image,
            "image_sha256" to init.imageSha256,
            "app_version" to init.appVersion,
            "system_version" to init.systemVersion,
            "t_ms_origin" to "bootStartTime",
            "stages" to emptyList<Any?>(),
            "result" to null,
            "fail_stage" to null,
            "console_tail_b64" to null,
            "console_tail_absent" to false,
            "qemu_argv" to init.qemuArgv,
            "orphan" to false,
        )

    /** §16.9 归档后单次 console.log ≤ 8MB：超限**截头保尾**（失败信息在末尾）。 */
    private fun truncateHead(file: File, maxBytes: Long) {
        val len = file.length()
        if (len <= maxBytes) return
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(len - maxBytes)
            val tail = ByteArray(maxBytes.toInt())
            raf.readFully(tail)
            raf.seek(0)
            raf.write(tail)
            raf.setLength(maxBytes)
        }
    }

    companion object {
        const val BOOTS_DIR = "boots"
        const val CONSOLE_FILE = "console.log"
        const val LAST_BOOT_ID_FILE = ".last_boot_id"
        const val META_SUFFIX = ".meta.json"
        const val CONSOLE_SUFFIX = ".console.log"

        const val STEP_LAST_ID = "read .last_boot_id"
        const val STEP_ARCHIVE = "archive console.log"
        const val STEP_MERGE = "merge prev meta"
        const val STEP_NEW_META = "write new boot meta"
        const val STEP_QUOTA = "boots quota"

        const val MAX_CONSOLE_ARCHIVE_BYTES = 8L * 1024 * 1024 // §16.9
    }
}

// ======================================================================
// boots/ 配额（§16.9）
// ======================================================================

/**
 * `boots/` 硬上限：**同时满足** `≤ 10 次启动` 且 `Σ ≤ 50 MB`（两条件取更严者），
 * 按 `boot_id` 时间前缀排序，**成对删除**（`*.console.log` + `*.meta.json` 一起）。
 */
object BootQuota {
    const val MAX_BOOTS = 10
    const val MAX_BOOTS_BYTES = 50L * 1024 * 1024

    /** 执行配额；返回剩余条目数。目录不存在 → 0。 */
    @JvmStatic
    @JvmOverloads
    fun enforce(bootsDir: File, maxBoots: Int = MAX_BOOTS, maxBytes: Long = MAX_BOOTS_BYTES): Int {
        if (!bootsDir.isDirectory) return 0
        val files = bootsDir.listFiles() ?: return 0
        val byId = LinkedHashMap<String, MutableList<File>>()
        for (f in files) {
            if (!f.isFile) continue
            val id = when {
                f.name.endsWith(BootRotator.CONSOLE_SUFFIX) -> f.name.removeSuffix(BootRotator.CONSOLE_SUFFIX)
                f.name.endsWith(BootRotator.META_SUFFIX) -> f.name.removeSuffix(BootRotator.META_SUFFIX)
                else -> continue
            }
            if (id.isEmpty()) continue
            byId.getOrPut(id) { mutableListOf() }.add(f)
        }
        val entries = byId.map { (id, fs) ->
            Triple(id, fs, fs.sumOf { it.length() })
        }.sortedBy { it.first } // boot_id 时间前缀 → 字典序 = 时间序
        var total = entries.sumOf { it.third }
        var count = entries.size
        var i = 0
        while ((count > maxBoots || total > maxBytes) && i < entries.size) {
            val (_, fs, size) = entries[i]
            for (f in fs) f.delete() // 成对删除
            total -= size
            count--
            i++
        }
        return count
    }

    /** 只读快照：条目 id（升序）。供自测/诊断用。 */
    @JvmStatic
    fun ids(bootsDir: File): List<String> {
        if (!bootsDir.isDirectory) return emptyList()
        val files = bootsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && (it.name.endsWith(BootRotator.CONSOLE_SUFFIX) || it.name.endsWith(BootRotator.META_SUFFIX)) }
            .map {
                it.name
                    .removeSuffix(BootRotator.CONSOLE_SUFFIX)
                    .removeSuffix(BootRotator.META_SUFFIX)
            }
            .distinct()
            .sorted()
    }
}

// ======================================================================
// image.log（§16.3/§16.5/§16.9）
// ======================================================================

/**
 * 镜像生命周期 JSONL（§16.5）。
 *
 * 写入协议（§16.3）：**单写线程 + `O_APPEND` 单次 `write` 整行**（行不撕裂）；
 * **轮转在同一一线程内 rename → create+reopen**（否则 fd 指向被滚动走的旧文件，
 * 新文件永远为空）。配额 3 × 2MB（§16.9）。
 *
 * 任何写失败只 warn 不抛（R3: C-R3-6：日志不能阻断业务）。
 */
class ImageLog @JvmOverloads constructor(
    private val file: File,
    private val maxFiles: Int = IMAGE_LOG_MAX_FILES,
    private val maxBytes: Long = IMAGE_LOG_MAX_BYTES,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : Closeable {

    private class Job(val line: String, val sync: Boolean, val done: CountDownLatch?)

    private val queue = LinkedBlockingQueue<Job>()
    @Volatile private var boot: String? = null
    @Volatile private var closed = false
    @Volatile private var broken = false // 打开失败后停止尝试（只 warn 一次）
    private val worker: Thread

    init {
        worker = Thread({ workerLoop() }, "vmdroid-image-log").apply {
            isDaemon = true
            start()
        }
    }

    /** 本次 boot_id（§16.3：`image.log` 每行的 `boot` 字段）。 */
    fun setBootId(id: String?) {
        boot = id
    }

    fun bootId(): String? = boot

    /**
     * 入队一行（异步；行内含 `ts`/`boot`/`ev`）。
     * @param sync true = 写后 fsync（仅 `activate`/`verify_fail`/`factory_reset` 关键行，§16.3）
     */
    fun record(event: String, fields: Map<String, Any?> = emptyMap(), sync: Boolean = false) {
        if (closed || broken) return
        val line = try {
            buildLine(event, fields)
        } catch (t: Throwable) {
            warn("image.log line build failed for '$event' — dropped: ${t.message}", t)
            return
        }
        try {
            queue.put(Job(line, sync, null))
        } catch (t: Throwable) {
            warn("image.log enqueue failed for '$event' — dropped: ${t.message}", t)
        }
    }

    /** 阻塞直到队列清空（自测/关键行前用）。@return 是否在超时前完成 */
    @JvmOverloads
    fun flush(timeoutMs: Long = 5_000): Boolean {
        if (closed) return true
        val done = CountDownLatch(1)
        return try {
            queue.put(Job("", false, done))
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            warn("image.log flush failed: ${t.message}", t)
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val done = CountDownLatch(1)
            queue.put(Job("", false, done))
            done.await(2, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            warn("image.log close failed: ${t.message}", t)
        }
    }

    // ------------------------------------------------------------ worker

    private fun workerLoop() {
        var out: FileOutputStream? = null
        try {
            while (true) {
                val job = queue.take()
                if (job.done != null && job.line.isEmpty()) {
                    if (closed) {
                        // 关闭前把已入队的行写完（避免 close 丢日志）
                        var pending = queue.poll()
                        while (pending != null) {
                            if (pending.line.isNotEmpty()) out = writeSafely(out, pending)
                            pending = queue.poll()
                        }
                        closeQuietly(out)
                        out = null
                        job.done.countDown()
                        break
                    }
                    job.done.countDown() // flush 哨兵
                    continue
                }
                out = writeSafely(out, job)
            }
        } catch (t: Throwable) {
            warn("image.log worker stopped: ${t.message}", t)
        } finally {
            closeQuietly(out)
        }
    }

    private fun writeSafely(out: FileOutputStream?, job: Job): FileOutputStream? {
        if (broken) return out
        return try {
            writeLine(out, job)
        } catch (t: Throwable) {
            broken = true // 打不开（目录不可写等）→ 之后静默丢弃，只 warn 一次
            warn("image.log write failed — further lines dropped: ${t.message}", t)
            closeQuietly(out)
            null
        }
    }

    private fun writeLine(out: FileOutputStream?, job: Job): FileOutputStream {
        val bytes = job.line.toByteArray(Charsets.UTF_8)
        var stream = out ?: openStream()
        // 轮转在同一线程内 rename → reopen（§16.3）
        if (file.length() + bytes.size > maxBytes) {
            rotate(stream)
            stream = openStream()
        }
        stream.write(bytes) // 单次 write 整行（O_APPEND，行不撕裂）
        stream.flush()
        if (job.sync) stream.fd.sync()
        return stream
    }

    private fun openStream(): FileOutputStream {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        return FileOutputStream(file, true) // O_APPEND
    }

    private fun rotate(current: FileOutputStream?) {
        try {
            current?.close()
            if (maxFiles <= 1) {
                file.delete()
                return
            }
            for (i in maxFiles - 1 downTo 1) {
                val dst = File(file.parentFile, "${file.name}.$i")
                val src = if (i == 1) file else File(file.parentFile, "${file.name}.${i - 1}")
                if (i == maxFiles - 1 && dst.exists()) dst.delete() // 超出保留数
                if (src.exists()) src.renameTo(dst)
            }
        } catch (t: Throwable) {
            warn("image.log rotation failed: ${t.message}", t)
        }
    }

    private fun closeQuietly(out: FileOutputStream?) {
        try {
            out?.close()
        } catch (t: Throwable) {
            warn("image.log close failed: ${t.message}", t)
        }
    }

    private fun buildLine(event: String, fields: Map<String, Any?>): String {
        val m = LinkedHashMap<String, Any?>()
        m["ts"] = SystemImageStore.isoOf(nowMs())
        m["boot"] = boot
        m["ev"] = event
        for ((k, v) in fields) {
            if (k != "ts" && k != "boot" && k != "ev") m[k] = v
        }
        return MiniJson.write(m) + "\n"
    }

    companion object {
        const val IMAGE_LOG_MAX_FILES = 3 // §16.9：3 × 2MB
        const val IMAGE_LOG_MAX_BYTES = 2L * 1024 * 1024

        /** fsync 的关键事件（§16.3）。 */
        val SYNC_EVENTS: Set<String> = setOf("activate", "verify_fail", "factory_reset")
    }
}

/**
 * `download_progress` 节流（§16.5/§16.9：每 5% 或 4 MB 记一行，**取较稀者**）——
 * 避免挤掉 `activate`/`verify_fail` 关键证据。其余事件必记。
 */
class ProgressThrottle @JvmOverloads constructor(
    private val stepPercent: Int = 5,
    private val stepBytes: Long = 4L * 1024 * 1024,
) {
    private var lastLoggedBytes: Long = -1L

    /** @return 这一行是否该记（首次恒 true）。 */
    fun shouldLog(bytes: Long, totalBytes: Long?): Boolean {
        if (lastLoggedBytes < 0) {
            lastLoggedBytes = bytes
            return true
        }
        // 取较稀者 = 阈值取两者较大（5% 总量 vs 4MB）
        val pctThreshold = if (totalBytes != null && totalBytes > 0) {
            totalBytes * stepPercent / 100
        } else {
            0L
        }
        val threshold = maxOf(stepBytes, pctThreshold)
        return if (bytes - lastLoggedBytes >= threshold) {
            lastLoggedBytes = bytes
            true
        } else {
            false
        }
    }

    fun reset() {
        lastLoggedBytes = -1L
    }
}
