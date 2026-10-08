package io.github.ltbkq.vmdroid.systemimage

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * §6.2 断点续传下载器单测 —— 用 JDK HttpServer 驱动真实 HTTP（Range/If-Range/416）。
 * 冻结测试镜像 = systemimage-selftest fixtures 的拷贝（id `debian-minimal-arm64`，
 * 1 MiB），随 test resources 入库，测试不依赖外部生成步骤。
 */
class ImageDownloaderTest {

    companion object {
        private val BODY: ByteArray by lazy {
            checkNotNull(ImageDownloaderTest::class.java.getResourceAsStream("/fixtures/debian-minimal.img")) {
                "missing test fixture /fixtures/debian-minimal.img"
            }.use { it.readBytes() }
        }

        private val BODY_SHA: String by lazy { VmdImageCodec.sha256(BODY) }
    }

    private lateinit var imagesDir: File
    private val log = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        imagesDir = Files.createTempDirectory("vmdroid-dl-test").toFile()
        log.clear()
    }

    @After
    fun tearDown() {
        imagesDir.deleteRecursively()
    }

    private fun downloader(available: () -> Long = { Long.MAX_VALUE }) = ImageDownloader(
        versionCode = { null },
        availableBytes = available,
        events = { e, f -> log += e to f },
    )

    private fun target(
        id: String = "debian-minimal-arm64",
        url: String,
        size: Long = BODY.size.toLong(),
        sha: String = BODY_SHA,
    ) = DownloadTarget(id, url, size, sha)

    private fun events(name: String) = log.filter { it.first == name }

    private fun fields(name: String): Map<String, Any?> = events(name).last().second

    private fun partFile() = File(imagesDir, "debian-minimal-arm64.img.part")

    private fun infoFile() = File(imagesDir, "debian-minimal-arm64.img.part.info")

    // ---------------------------------------------------------------- 基础

    @Test
    fun fullDownload_succeeds_finalizesAndCleansPart() {
        val server = FakeImageServer(BODY)
        try {
            val out = downloader().download(target(url = server.url), imagesDir)
            assertTrue("expected success, got $out", out is DownloadOutcome.Success)
            out as DownloadOutcome.Success
            assertEquals("debian-minimal-arm64", out.imageId)
            assertEquals("debian-minimal-arm64", out.manifestId)

            val file = File(imagesDir, "debian-minimal-arm64.img")
            assertArrayEquals(BODY, file.readBytes())
            assertFalse(partFile().exists())
            assertFalse(infoFile().exists())
            // .meta.json 由上层 store.writeMeta 负责（职责切分）
            assertFalse(File(imagesDir, "debian-minimal-arm64.img.meta.json").exists())

            // §16.5 生命周期：start（非续传）→ progress → done，无 fail
            val start = fields("download_start")
            assertEquals("debian-minimal-arm64", start["image"])
            assertEquals(server.url, start["src"])
            assertEquals(0L, start["bytes"])
            assertEquals(false, start["resume"])
            assertTrue(events("download_progress").isNotEmpty())
            val done = fields("download_done")
            assertEquals(BODY.size.toLong(), done["bytes"])
            assertTrue((done["dur_ms"] as Long) >= 0L)
            assertTrue(events("download_fail").isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun progressCallback_reportsAdvancingBytes() {
        val server = FakeImageServer(BODY)
        try {
            val seen = mutableListOf<DownloadProgress>()
            val out = downloader().download(target(url = server.url), imagesDir, onProgress = { seen += it })
            assertTrue(out is DownloadOutcome.Success)
            assertTrue(seen.isNotEmpty())
            assertEquals(BODY.size.toLong(), seen.last().bytes)
            // 单调不减
            assertTrue(seen.zipWithNext().all { (a, b) -> b.bytes >= a.bytes })
            assertEquals(100, seen.last().percent)
        } finally {
            server.stop()
        }
    }

    // ---------------------------------------------------------------- 续传（§6.2 核心）

    @Test
    fun interruptedDownload_thenResume_completesViaRange() {
        val server = FakeImageServer(BODY)
        try {
            // ① 模拟 60% 处杀进程：shouldContinue 协作取消
            var got = 0L
            val first = downloader().download(
                target(url = server.url),
                imagesDir,
                onProgress = { got = it.bytes },
                shouldContinue = { got < BODY.size * 3 / 5 },
            )
            val f = (first as DownloadOutcome.Failure).failure
            assertEquals(DownloadErrors.CANCELLED, f.err)
            assertTrue(f.resumable)
            assertTrue(partFile().isFile)
            assertTrue(partFile().length() in 1 until BODY.size.toLong())
            assertTrue(infoFile().isFile)

            // 续传依据落盘格式（url/size/sha/etag）
            val info = MiniJson.parseObject(infoFile().readText())
            assertEquals("debian-minimal-arm64", info["image_id"])
            assertEquals(server.url, info["url"])
            assertEquals(BODY.size.toLong(), info["size"])
            assertEquals(BODY_SHA, info["sha256"])
            assertEquals("\"v1\"", info["etag"])

            // ② 下次进入应用 → 自动续传
            val second = downloader().download(target(url = server.url), imagesDir)
            assertTrue("expected success, got $second", second is DownloadOutcome.Success)
            assertArrayEquals(BODY, File(imagesDir, "debian-minimal-arm64.img").readBytes())

            // 服务器确实收到了 Range 续传请求
            assertTrue(server.seenRanges.any { it != null && it.startsWith("bytes=") })
            assertEquals(true, fields("download_start")["resume"])
            assertTrue((fields("download_start")["bytes"] as Long) > 0L)

            // 生命周期：两次 start（第二条 resume=true）、一次 done、取消记了 fail
            assertEquals(2, events("download_start").size)
            assertEquals(1, events("download_done").size)
            assertEquals(1, events("download_fail").size)
            assertEquals(DownloadErrors.CANCELLED, fields("download_fail")["err"])
            assertFalse(partFile().exists())
            assertFalse(infoFile().exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun etagChange_forcesFullRestartFromZero() {
        val server = FakeImageServer(BODY)
        try {
            var got = 0L
            downloader().download(
                target(url = server.url),
                imagesDir,
                onProgress = { got = it.bytes },
                shouldContinue = { got < BODY.size / 2 },
            )
            assertTrue(partFile().isFile)

            // 服务端实体变了（validators 变化）→ If-Range 失配 → 200 全量重来
            server.etag = "\"v2\""
            val out = downloader().download(target(url = server.url), imagesDir)
            assertTrue("expected success, got $out", out is DownloadOutcome.Success)
            // 若错误地在旧前缀上追加，sha 必然对不上 —— 内容一致即证明从 0 重来
            assertArrayEquals(BODY, File(imagesDir, "debian-minimal-arm64.img").readBytes())
            assertEquals("\"v1\"", server.seenIfRanges.last())
        } finally {
            server.stop()
        }
    }

    @Test
    fun serverWithoutRangeSupport_restartsFromZero() {
        val server = FakeImageServer(BODY)
        try {
            var got = 0L
            downloader().download(
                target(url = server.url),
                imagesDir,
                onProgress = { got = it.bytes },
                shouldContinue = { got < BODY.size / 4 },
            )
            server.always200 = true
            val out = downloader().download(target(url = server.url), imagesDir)
            assertTrue("expected success, got $out", out is DownloadOutcome.Success)
            assertArrayEquals(BODY, File(imagesDir, "debian-minimal-arm64.img").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun completeLocalPart_416FinalizesWithoutBodyDownload() {
        val server = FakeImageServer(BODY)
        try {
            // 上一轮最后一步（rename 前）被杀：本地已是完整字节
            partFile().writeBytes(BODY)
            AtomicFiles.write(
                infoFile(),
                MiniJson.write(
                    mapOf(
                        "image_id" to "debian-minimal-arm64",
                        "url" to server.url,
                        "size" to BODY.size.toLong(),
                        "sha256" to BODY_SHA,
                        "etag" to "\"v1\"",
                    ),
                ),
            )
            server.fourSixteen = true

            val out = downloader().download(target(url = server.url), imagesDir)
            assertTrue("expected success, got $out", out is DownloadOutcome.Success)
            assertArrayEquals(BODY, File(imagesDir, "debian-minimal-arm64.img").readBytes())
            assertEquals(1, server.seenRanges.size)
            assertEquals("bytes=${BODY.size}-", server.seenRanges[0])
            assertEquals(true, fields("download_start")["resume"])
            assertFalse(infoFile().exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun stalePartInfoFromDifferentUrl_isDiscarded() {
        val server = FakeImageServer(BODY)
        try {
            // 半截 part + 与本次 target 不匹配的 info → 陈旧状态整体丢弃
            partFile().writeBytes(BODY.copyOfRange(0, BODY.size / 3))
            AtomicFiles.write(
                infoFile(),
                MiniJson.write(
                    mapOf(
                        "image_id" to "debian-minimal-arm64",
                        "url" to "http://127.0.0.1:1/other.img",
                        "size" to BODY.size.toLong(),
                        "sha256" to BODY_SHA,
                    ),
                ),
            )
            val out = downloader().download(target(url = server.url), imagesDir)
            assertTrue("expected success, got $out", out is DownloadOutcome.Success)
            // 首个请求不带 Range（从 0 重来）
            assertNull(server.seenRanges.first())
            assertArrayEquals(BODY, File(imagesDir, "debian-minimal-arm64.img").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun pendingTargets_restoresInterruptedDownloadMetadata() {
        val server = FakeImageServer(BODY)
        try {
            var got = 0L
            downloader().download(
                target(url = server.url),
                imagesDir,
                onProgress = { got = it.bytes },
                shouldContinue = { got < BODY.size / 4 },
            )
            val pending = downloader().pendingTargets(imagesDir)
            assertEquals(listOf(target(url = server.url)), pending)
        } finally {
            server.stop()
        }
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    fun storagePreflight_blocksBeforeAnyConnectionAndPart() {
        val server = FakeImageServer(BODY)
        try {
            val out = downloader(available = { 1024L }).download(target(url = server.url), imagesDir)
            val f = (out as DownloadOutcome.Failure).failure
            assertEquals(DownloadErrors.STORAGE_FULL, f.err)
            assertFalse(f.resumable)
            assertFalse(partFile().exists())
            assertFalse(infoFile().exists())
            assertTrue(server.seenRanges.isEmpty()) // 写盘前拦截，根本没连服务器（§13.2）
            assertEquals(DownloadErrors.STORAGE_FULL, fields("download_fail")["err"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun shaMismatch_discardsPartAndRecordsVerifyFail() {
        val server = FakeImageServer(BODY)
        try {
            val out = downloader().download(target(url = server.url, sha = "0".repeat(64)), imagesDir)
            val f = (out as DownloadOutcome.Failure).failure
            assertEquals(DownloadErrors.SHA_MISMATCH, f.err)
            assertFalse(f.resumable)
            assertFalse(partFile().exists())
            assertFalse(infoFile().exists())
            assertFalse(File(imagesDir, "debian-minimal-arm64.img").exists())
            assertEquals("sha256", fields("verify_fail")["fail"])
            assertTrue(events("download_fail").isNotEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun shortRead_keepsPartForLaterResume() {
        val server = FakeImageServer(BODY)
        try {
            server.declaredLength = BODY.size.toLong() / 2
            val out = downloader().download(target(url = server.url), imagesDir)
            val f = (out as DownloadOutcome.Failure).failure
            assertEquals(DownloadErrors.SHORT_READ, f.err)
            assertTrue(f.resumable)
            assertTrue(partFile().isFile)
            assertTrue(infoFile().isFile)
            assertFalse(File(imagesDir, "debian-minimal-arm64.img").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun httpError_mapsToNetworkFailure() {
        val server = FakeImageServer(BODY)
        try {
            server.forceStatus = 404
            val out = downloader().download(target(url = server.url), imagesDir)
            val f = (out as DownloadOutcome.Failure).failure
            assertEquals(DownloadErrors.NETWORK, f.err)
            assertEquals("HTTP 404", f.message)
            assertFalse(partFile().exists())
            assertEquals(DownloadErrors.NETWORK, fields("download_fail")["err"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun manifestIdMismatch_rejectsAndDiscards() {
        val server = FakeImageServer(BODY)
        try {
            // catalog 声明 other-id，manifest 里是 debian-minimal-arm64（B-R2-5 防串条目）
            val out = downloader().download(target(id = "other-id", url = server.url), imagesDir)
            val f = (out as DownloadOutcome.Failure).failure
            assertEquals(DownloadErrors.IMAGE_INVALID, f.err)
            assertFalse(f.resumable)
            assertFalse(File(imagesDir, "other-id.img").exists())
            assertFalse(File(imagesDir, "other-id.img.part").exists())
            assertEquals("image_id", fields("verify_fail")["fail"])
        } finally {
            server.stop()
        }
    }

    // ---------------------------------------------------------------- 纯函数

    @Test
    fun contentRangeParsing_handlesBothForms() {
        val d = downloader()
        assertEquals(100L, d.contentRangeStart("bytes 100-199/200"))
        assertEquals(0L, d.contentRangeStart("bytes 0-99/100"))
        assertNull(d.contentRangeStart("bytes */200"))
        assertNull(d.contentRangeStart("garbage"))
        assertNull(d.contentRangeStart(null))
        assertEquals(200L, d.contentRangeTotal("bytes 100-199/200"))
        assertEquals(200L, d.contentRangeTotal("bytes */200"))
        assertNull(d.contentRangeTotal("garbage"))
        assertNull(d.contentRangeTotal(null))
    }

    @Test
    fun progressMath_percentAndEta() {
        assertEquals(50, DownloadProgress(50, 100, 10).percent)
        assertEquals(100, DownloadProgress(150, 100, 10).percent)
        assertEquals(0, DownloadProgress(0, 0, 0).percent)
        assertEquals(5L, DownloadProgress(50, 100, 10).etaSeconds)
        assertEquals(-1L, DownloadProgress(50, 100, 0).etaSeconds)
        assertEquals(-1L, DownloadProgress(100, 100, 10).etaSeconds)
    }

    // ---------------------------------------------------------------- 服务器

    /** 可编程镜像服务器：Range/If-Range/416/短读/状态码注入。 */
    private class FakeImageServer(private val body: ByteArray) {
        var etag: String? = "\"v1\""          // null = 不回 ETag
        var always200 = false                 // 无视 Range（服务端不支持续传）
        var forceStatus: Int? = null          // 固定状态码（如 404）
        var fourSixteen = false               // 强制 416
        var declaredLength: Long? = null      // 短读：Content-Length < 实际 size
        val seenRanges = mutableListOf<String?>()
        val seenIfRanges = mutableListOf<String?>()

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val url: String get() = "http://127.0.0.1:${server.address.port}/image.img"

        init {
            server.createContext("/image.img") { ex ->
                try {
                    handle(ex)
                } catch (_: Exception) {
                    // 客户端提前断开 —— 测试环境忽略
                } finally {
                    ex.close()
                }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(ex: com.sun.net.httpserver.HttpExchange) {
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            seenRanges += range
            seenIfRanges += ifRange
            etag?.let { ex.responseHeaders.add("ETag", it) }

            forceStatus?.let {
                ex.sendResponseHeaders(it, -1)
                return
            }
            if (fourSixteen) {
                ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                ex.sendResponseHeaders(416, -1)
                return
            }

            // If-Range 失配 → 按语义回 200 全量（§6.2 validators 变化重来）
            val mismatch = ifRange != null && etag != null && ifRange != etag
            val offset: Long? =
                if (range != null && !always200 && !mismatch) {
                    Regex("""bytes=(\d+)-""").find(range)?.groupValues?.get(1)?.toLongOrNull()
                } else {
                    null
                }

            when {
                offset == null -> {
                    val len = declaredLength ?: body.size.toLong()
                    ex.sendResponseHeaders(200, len)
                    ex.responseBody.use { it.write(body, 0, len.toInt()) }
                }
                offset >= body.size -> {
                    ex.responseHeaders.set("Content-Range", "bytes */${body.size}")
                    ex.sendResponseHeaders(416, -1)
                }
                else -> {
                    ex.responseHeaders.add("Content-Range", "bytes $offset-${body.size - 1}/${body.size}")
                    ex.sendResponseHeaders(206, body.size - offset)
                    ex.responseBody.use { it.write(body, offset.toInt(), body.size - offset.toInt()) }
                }
            }
        }
    }
}
