/*
 * VMDroid - image downloader with HTTP range resume (DESIGN §6.2).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN.md §6.2（预检 size+余量 → Range 续传/416/validators 变化重来 →
 *     每块同时喂 sha256 → 完成后 sha256 == catalog.sha256 且 footer/manifest 通过
 *     （N4：footer 必需，缺失即拒）→ fsync + rename → 上层补写 .meta.json）
 *   - docs/DESIGN.md §16.5（download_start/download_progress/download_done/
 *     download_fail；progress 每 5% 或 4MB 节流；每条含 dur_ms，ts 由 ImageLog 加）
 *   - docs/DESIGN.md §16.9（.part 永不冒充成品：进程被杀后 .part + .part.info
 *     保留，下次进入应用自动续传）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— java.net + 文件 IO，
 * HTTP 语义由 systemimage 自测用 JDK HttpServer 驱动。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** §6.2 下载目标：catalog 条目的下载期快照（`.part.info` 的续传依据）。 */
data class DownloadTarget(
    val imageId: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/** 进度快照（UI 行内进度 / 通知栏共用）。 */
data class DownloadProgress(
    val bytes: Long,
    val total: Long,
    val bytesPerSec: Long,
) {
    val percent: Int
        get() = if (total > 0) ((bytes * 100) / total).coerceIn(0L, 100L).toInt() else 0

    /** 预计剩余秒数；速度未知时 -1。 */
    val etaSeconds: Long
        get() = if (bytesPerSec > 0 && total > bytes) (total - bytes) / bytesPerSec else -1L
}

/** §6.2 下载结局之「失败」。[resumable]=.part/.info 保留可续传；false=已按规格丢弃。 */
data class DownloadFailure(
    val err: String,
    val message: String,
    val resumable: Boolean,
)

sealed class DownloadOutcome {
    data class Success(val imageId: String, val file: File, val manifestId: String) : DownloadOutcome()
    data class Failure(val failure: DownloadFailure) : DownloadOutcome()
}

/** `download_fail.err` 取值（自由串，非 §7.4 启动枚举；§16.5 表只要求 err 字段）。 */
object DownloadErrors {
    const val STORAGE_FULL = "STORAGE_FULL"
    const val NETWORK = "NETWORK"
    const val SHORT_READ = "SHORT_READ"
    const val SHA_MISMATCH = "SHA_MISMATCH"
    const val IMAGE_INVALID = "IMAGE_INVALID"
    const val CANCELLED = "CANCELLED"

    /** 「仅 Wi-Fi 下载」设置拦截（§6.2；UI 应已禁用入口，此为竞态防线）。 */
    const val WIFI_ONLY = "WIFI_ONLY"
}

/**
 * §6.2 断点续传下载器（阻塞式；调用方放 `Dispatchers.IO`，以 [shouldContinue]
 * 做协作取消）。单任务串行由上层保证。**永不抛网络/IO 异常**（一律折成
 * [DownloadFailure]）；仅 sink/取消源自身抛出时传播。
 */
class ImageDownloader(
    private val versionCode: () -> Long?,
    private val availableBytes: () -> Long,
    private val events: (event: String, fields: Map<String, Any?>) -> Unit,
) {
    companion object {
        /** §6.2 预检余量：`size + 32 MiB`（"size + 余量"的落值）。 */
        const val STORAGE_MARGIN = 32L * 1024 * 1024

        /** `<image_id>.img.part.info` —— 续传元数据（url/size/sha + validators）。 */
        const val INFO_SUFFIX = ".part.info"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CHUNK = 64 * 1024

        /** 速度采样最小间隔（供 UI 显示 MB/s 与剩余时间）。 */
        private const val SPEED_SAMPLE_MS = 500L

        /** Range/validators 状态机允许的最大重连次数（防御病态服务端）。 */
        private const val MAX_REOPEN = 2
    }

    private val throttle = ProgressThrottle() // §16.9：每 5% 或 4MB 记一行

    fun partFile(imagesDir: File, imageId: String): File =
        File(imagesDir, "$imageId${SystemImageStore.IMG_SUFFIX}${SystemImageStore.PART_SUFFIX}")

    fun infoFile(imagesDir: File, imageId: String): File =
        File(imagesDir, "$imageId${SystemImageStore.IMG_SUFFIX}$INFO_SUFFIX")

    /** 扫描 images/ 下全部未完成下载（进程被杀 → 下次进入应用自动续传的输入）。 */
    fun pendingTargets(imagesDir: File): List<DownloadTarget> {
        val files = imagesDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(INFO_SUFFIX) }
            .mapNotNull { readInfo(it) }
            .map { DownloadTarget(it.imageId, it.url, it.size, it.sha256) }
            .distinctBy { it.imageId }
    }

    /** §6.2 主流程。 */
    fun download(
        target: DownloadTarget,
        imagesDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): DownloadOutcome {
        val startedAt = System.currentTimeMillis()
        throttle.reset()
        val part = partFile(imagesDir, target.imageId)
        val info = infoFile(imagesDir, target.imageId)

        // ① 空间预检（§6.2-1 / §13.2 STORAGE_FULL 用例：写盘前拦截，本次不创建 .part）
        val available = runCatching { availableBytes() }.getOrDefault(Long.MAX_VALUE)
        if (available < target.size + STORAGE_MARGIN) {
            return fail(
                target, startedAt, DownloadErrors.STORAGE_FULL,
                "need ${target.size + STORAGE_MARGIN} bytes, have $available",
                bytes = if (part.isFile) part.length().coerceAtMost(target.size) else 0L,
                resumable = part.isFile,
            )
        }

        // ② 续传判定：info 必须与本次 target 完全一致，否则丢弃陈旧 .part
        var offset = 0L
        var etag: String? = null
        var lastModified: String? = null
        var resumed = false
        val saved = readInfo(info)
        if (saved != null && saved.matches(target) && part.isFile) {
            val len = part.length()
            if (len in 1..target.size) {
                offset = len
                etag = saved.etag
                lastModified = saved.lastModified
                resumed = true
            } else if (len > target.size || len == 0L) {
                // 越界/空 part（预期 size 变了）→ 丢弃
                part.delete()
                info.delete()
            }
        } else if (part.isFile || info.isFile) {
            part.delete()
            info.delete()
        }

        emit(
            "download_start",
            linkedMapOf(
                "image" to target.imageId,
                "src" to target.url,
                "bytes" to offset,
                "resume" to resumed,
            ),
            startedAt,
        )

        // ③ 连接 + Range 状态机（§6.2-2）
        val session = negotiate(target, offset, etag, lastModified, startedAt)
        if (session is Session.Rejected) {
            return DownloadOutcome.Failure(session.failure)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var digestComplete = false

        if (session is Session.CompleteLocal) {
            // 416 且本地长度 == 预期 → 直接进校验（整个文件重喂 digest）
            if (part.length() != target.size) {
                part.delete()
                info.delete()
                return fail(
                    target, startedAt, DownloadErrors.SHORT_READ,
                    "416 but local part is ${part.length()} bytes, expected ${target.size}",
                    bytes = 0L,
                    resumable = false,
                )
            }
            feedFile(digest, part, part.length())
            digestComplete = true
        } else {
            val open = session as Session.Open
            // ④ 流式写入：前缀（如有）先喂 digest —— 续传字节不再经过网络，只读本地
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(open.start) // start>0 保留前缀；start==0 清空
                    if (open.start > 0) {
                        feedFile(digest, part, open.start)
                    }
                    raf.seek(open.start)
                    var bytes = open.start

                    // 续传依据先落盘（写第一块前）：此刻被杀也能续
                    writeInfo(info, target, open.etag, open.lastModified)

                    val input = open.conn.inputStream
                    val buf = ByteArray(CHUNK)
                    var lastSampleAt = System.currentTimeMillis()
                    var lastSampleBytes = bytes
                    var speed = 0L
                    while (true) {
                        if (!shouldContinue()) {
                            raf.fd.sync()
                            open.conn.disconnect()
                            return fail(
                                target, startedAt, DownloadErrors.CANCELLED,
                                "cancelled",
                                bytes = bytes,
                                resumable = true,
                            )
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        val room = (target.size - bytes).coerceAtMost(n.toLong()).toInt()
                        if (room <= 0) break // 服务端多发：按 size 截断，多出的丢弃
                        raf.write(buf, 0, room)
                        digest.update(buf, 0, room)
                        bytes += room

                        val now = System.currentTimeMillis()
                        if (now - lastSampleAt >= SPEED_SAMPLE_MS) {
                            speed = (bytes - lastSampleBytes) * 1000 / (now - lastSampleAt)
                            lastSampleAt = now
                            lastSampleBytes = bytes
                        }
                        onProgress(DownloadProgress(bytes, target.size, speed))
                        if (throttle.shouldLog(bytes, target.size)) {
                            emit(
                                "download_progress",
                                linkedMapOf(
                                    "image" to target.imageId,
                                    "src" to target.url,
                                    "bytes" to bytes,
                                    "resume" to open.resumed,
                                ),
                                startedAt,
                            )
                        }
                        if (bytes >= target.size) break
                    }
                    raf.fd.sync() // §6.2-4：fsync 后才允许 rename
                    if (bytes < target.size) {
                        open.conn.disconnect()
                        return fail(
                            target, startedAt, DownloadErrors.SHORT_READ,
                            "connection ended at $bytes of ${target.size} bytes",
                            bytes = bytes,
                            resumable = true,
                        )
                    }
                    digestComplete = true
                }
            } catch (e: Exception) {
                open.conn.disconnect()
                return fail(
                    target, startedAt, DownloadErrors.NETWORK,
                    e.message ?: e.javaClass.simpleName,
                    bytes = runCatching { part.length() }.getOrDefault(0L),
                    resumable = true,
                )
            }
            open.conn.disconnect()
        }

        // ④ 完成校验（§6.2-3）：sha256 == catalog.sha256
        val actualSha = if (digestComplete) {
            digest.digest().joinToString("") { "%02x".format(it) }
        } else {
            sha256File(part)
        }
        if (!actualSha.equals(target.sha256, ignoreCase = true)) {
            part.delete()
            info.delete()
            val detail = "expected ${target.sha256}, got $actualSha"
            emit(
                "verify_fail",
                linkedMapOf("image" to target.imageId, "fail" to "sha256", "err" to detail),
                startedAt,
            )
            emit(
                "download_fail",
                linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to target.size, "err" to DownloadErrors.SHA_MISMATCH),
                startedAt,
            )
            return DownloadOutcome.Failure(DownloadFailure(DownloadErrors.SHA_MISMATCH, detail, resumable = false))
        }

        // ④′ footer/manifest 解析校验（§7.4 清单 3–9；N4 footer 必需，缺失即拒）
        val read = try {
            VmdImageCodec.read(part, runCatching { versionCode() }.getOrNull())
        } catch (e: VmdImageException) {
            part.delete()
            info.delete()
            emit(
                "verify_fail",
                linkedMapOf("image" to target.imageId, "fail" to e.reason.name, "err" to e.message),
                startedAt,
            )
            emit(
                "download_fail",
                linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to target.size, "err" to DownloadErrors.IMAGE_INVALID),
                startedAt,
            )
            return DownloadOutcome.Failure(
                DownloadFailure(DownloadErrors.IMAGE_INVALID, e.message ?: e.reason.name, resumable = false),
            )
        }
        // §6.1（R2: B-R2-5）：落盘名恒由校验后的 manifest.image.id 决定；与 catalog
        // 声明不一致 = 目录被换过/串了条目，拒绝。
        val manifestId = read.manifest.image.id
        if (manifestId == null || manifestId != target.imageId) {
            part.delete()
            info.delete()
            val detail = "manifest image.id '$manifestId' != catalog image_id '${target.imageId}'"
            emit(
                "verify_fail",
                linkedMapOf("image" to target.imageId, "fail" to "image_id", "err" to detail),
                startedAt,
            )
            emit(
                "download_fail",
                linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to target.size, "err" to DownloadErrors.IMAGE_INVALID),
                startedAt,
            )
            return DownloadOutcome.Failure(DownloadFailure(DownloadErrors.IMAGE_INVALID, detail, resumable = false))
        }

        // ⑤ rename(.part → .img)；.meta.json 由上层 store.writeMeta 补写
        val finalFile = File(imagesDir, "$manifestId${SystemImageStore.IMG_SUFFIX}")
        if (finalFile.exists() && !finalFile.delete()) {
            return fail(
                target, startedAt, DownloadErrors.NETWORK,
                "cannot replace existing ${finalFile.name}",
                bytes = target.size,
                resumable = true,
            )
        }
        if (!part.renameTo(finalFile)) {
            return fail(
                target, startedAt, DownloadErrors.NETWORK,
                "rename .part -> .img failed",
                bytes = target.size,
                resumable = true,
            )
        }
        info.delete()
        emit(
            "download_done",
            linkedMapOf(
                "image" to target.imageId,
                "src" to target.url,
                "bytes" to target.size,
                "resume" to resumedOf(session),
            ),
            startedAt,
        )
        return DownloadOutcome.Success(target.imageId, finalFile, manifestId)
    }

    // ---------------------------------------------------------------- 连接协商

    /** [download] ③ 的产出。 */
    private sealed class Session {
        /** 可写会话：[conn] 供流式读取，[start] 为写入起点（0=全新，>0=续传前缀已在本地）。 */
        data class Open(
            val conn: HttpURLConnection,
            val start: Long,
            val etag: String?,
            val lastModified: String?,
            val resumed: Boolean,
        ) : Session()

        /** 416 且本地 part 已是完整长度 → 无需连接，直接校验。 */
        data object CompleteLocal : Session()

        /** 拒绝（download_fail 已记）。 */
        data class Rejected(val failure: DownloadFailure) : Session()
    }

    private fun resumedOf(session: Session): Boolean = when (session) {
        is Session.Open -> session.resumed
        // CompleteLocal 仅在发过 Range（offset>0）时可能达到 → 必是续传
        Session.CompleteLocal -> true
        is Session.Rejected -> false
    }

    /** Range/If-Range/416 状态机（§6.2-2）。失败时已 emit `download_fail`。 */
    private fun negotiate(
        target: DownloadTarget,
        offset0: Long,
        etag0: String?,
        lastModified0: String?,
        startedAt: Long,
    ): Session {
        var offset = offset0
        var etag = etag0
        var lastModified = lastModified0
        var resumed = offset0 > 0
        var reopen = 0
        var conn = try {
            open(target, offset, etag, lastModified)
        } catch (e: Exception) {
            return Session.Rejected(
                failAs(target, startedAt, DownloadErrors.NETWORK, e.message ?: "open failed", offset, resumable = resumed),
            )
        }
        while (true) {
            val opening = try {
                evaluate(conn, offset, target)
            } catch (e: Exception) {
                conn.disconnect()
                return Session.Rejected(
                    failAs(target, startedAt, DownloadErrors.NETWORK, e.message ?: "connect failed", offset, resumable = resumed),
                )
            }
            when (opening) {
                is Opening.Serving -> {
                    etag = opening.etag ?: etag
                    lastModified = opening.lastModified ?: lastModified
                    return Session.Open(conn, opening.start, etag, lastModified, resumed)
                }
                is Opening.FullBody -> {
                    // 200：服务端未按 Range 服务（未支持 / If-Range 失配）→ 从 0 重来。
                    // 旧 .part 由写入路径 setLength(0) 清掉，.part.info 由 writeInfo 覆盖。
                    etag = opening.etag
                    lastModified = opening.lastModified
                    resumed = false
                    return Session.Open(conn, 0L, etag, lastModified, resumed)
                }
                Opening.Complete -> {
                    conn.disconnect()
                    return Session.CompleteLocal
                }
                is Opening.Reopen -> {
                    conn.disconnect()
                    if (++reopen > MAX_REOPEN) {
                        return Session.Rejected(
                            failAs(target, startedAt, DownloadErrors.NETWORK, "range negotiation failed (${opening.hint})", offset, resumable = false),
                        )
                    }
                    // 416 越界 / 206 Content-Range 错位 → 丢弃本地前缀从 0 重来
                    offset = 0
                    etag = null
                    lastModified = null
                    resumed = false
                    conn = try {
                        open(target, 0, null, null)
                    } catch (e: Exception) {
                        return Session.Rejected(
                            failAs(target, startedAt, DownloadErrors.NETWORK, e.message ?: "open failed", 0, resumable = false),
                        )
                    }
                }
                is Opening.HttpError -> {
                    conn.disconnect()
                    return Session.Rejected(
                        failAs(target, startedAt, DownloadErrors.NETWORK, "HTTP ${opening.code}", offset, resumable = true),
                    )
                }
            }
        }
    }

    private sealed class Opening {
        data class Serving(val start: Long, val etag: String?, val lastModified: String?) : Opening()
        data class FullBody(val etag: String?, val lastModified: String?) : Opening()
        data class Reopen(val hint: String) : Opening()
        data object Complete : Opening()
        data class HttpError(val code: Int) : Opening()
    }

    private fun open(target: DownloadTarget, offset: Long, etag: String?, lastModified: String?): HttpURLConnection {
        val conn = URL(target.url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=$offset-")
            // If-Range：validators 失配时服务端回 200 全量（→ FullBody 重来，§6.2）
            val validator = etag ?: lastModified
            if (validator != null) conn.setRequestProperty("If-Range", validator)
        }
        return conn
    }

    private fun evaluate(conn: HttpURLConnection, offset: Long, target: DownloadTarget): Opening {
        val code = conn.responseCode
        val etag = conn.getHeaderField("ETag")
        val lastModified = conn.getHeaderField("Last-Modified")
        return when {
            code == HttpURLConnection.HTTP_OK ->
                Opening.FullBody(etag, lastModified)
            code == HttpURLConnection.HTTP_PARTIAL -> {
                val start = contentRangeStart(conn.getHeaderField("Content-Range"))
                if (start != null && start == offset) Opening.Serving(start, etag, lastModified)
                else Opening.Reopen("206 start=$start offset=$offset")
            }
            code == 416 -> {
                val total = contentRangeTotal(conn.getHeaderField("Content-Range"))
                if (offset == target.size && (total == null || total == target.size)) {
                    Opening.Complete
                } else {
                    Opening.Reopen("416 offset=$offset total=$total size=${target.size}")
                }
            }
            code >= 400 -> Opening.HttpError(code)
            else -> Opening.Reopen("unexpected HTTP $code")
        }
    }

    /** Content-Range `bytes 100-199/200` 的起点 → 100；星号形态（416 用）或坏值 → null。 */
    internal fun contentRangeStart(value: String?): Long? =
        Regex("""^bytes\s+(\d+)-\d+/\d+$""").find(value?.trim() ?: "")?.groupValues?.get(1)?.toLongOrNull()

    /** Content-Range 总长：数字形态 `bytes 0-99/100` 与星号形态 `bytes star/100` 都 → 100；坏值 → null。 */
    internal fun contentRangeTotal(value: String?): Long? =
        Regex("""^bytes\s+(?:\d+-\d+|\*)/(\d+)$""").find(value?.trim() ?: "")?.groupValues?.get(1)?.toLongOrNull()

    // ---------------------------------------------------------------- 工具

    private fun feedFile(digest: MessageDigest, file: File, limit: Long) {
        RandomAccessFile(file, "r").use { raf ->
            var fed = 0L
            val buf = ByteArray(CHUNK)
            while (fed < limit) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), limit - fed).toInt())
                if (n < 0) break
                digest.update(buf, 0, n)
                fed += n
            }
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        feedFile(digest, file, file.length())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(
        target: DownloadTarget,
        startedAt: Long,
        err: String,
        message: String,
        bytes: Long,
        resumable: Boolean,
    ): DownloadOutcome {
        emit(
            "download_fail",
            linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to bytes, "err" to err),
            startedAt,
        )
        return DownloadOutcome.Failure(DownloadFailure(err, message, resumable))
    }

    /** [negotiate] 内部版：只产出 failure（download_fail 已记）。 */
    private fun failAs(
        target: DownloadTarget,
        startedAt: Long,
        err: String,
        message: String,
        bytes: Long,
        resumable: Boolean,
    ): DownloadFailure {
        emit(
            "download_fail",
            linkedMapOf("image" to target.imageId, "src" to target.url, "bytes" to bytes, "err" to err),
            startedAt,
        )
        return DownloadFailure(err, message, resumable)
    }

    private fun emit(event: String, fields: Map<String, Any?>, startedAt: Long) {
        val withDur = LinkedHashMap(fields)
        withDur["dur_ms"] = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        runCatching { events(event, withDur) }
    }

    // ---------------------------------------------------------------- .part.info

    private data class SavedInfo(
        val imageId: String,
        val url: String,
        val size: Long,
        val sha256: String,
        val etag: String?,
        val lastModified: String?,
    ) {
        fun matches(t: DownloadTarget) =
            imageId == t.imageId && url == t.url && size == t.size && sha256 == t.sha256
    }

    private fun writeInfo(file: File, target: DownloadTarget, etag: String?, lastModified: String?) {
        val map = LinkedHashMap<String, Any?>()
        map["image_id"] = target.imageId
        map["url"] = target.url
        map["size"] = target.size
        map["sha256"] = target.sha256
        if (etag != null) map["etag"] = etag
        if (lastModified != null) map["last_modified"] = lastModified
        runCatching { AtomicFiles.write(file, MiniJson.write(map)) }
    }

    private fun readInfo(file: File): SavedInfo? {
        return try {
            if (file.isFile) readInfoFields(MiniJson.parseObject(file.readText())) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readInfoFields(m: Map<String, Any?>): SavedInfo? {
        val imageId = MiniJson.str(m, "image_id") ?: return null
        val url = MiniJson.str(m, "url") ?: return null
        val size = MiniJson.long(m, "size") ?: return null
        val sha256 = MiniJson.str(m, "sha256") ?: return null
        return SavedInfo(
            imageId = imageId,
            url = url,
            size = size,
            sha256 = sha256,
            etag = MiniJson.str(m, "etag"),
            lastModified = MiniJson.str(m, "last_modified"),
        )
    }
}
