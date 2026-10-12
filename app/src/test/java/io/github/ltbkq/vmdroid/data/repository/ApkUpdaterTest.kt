/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Unit tests for the v2.0.0 updater plumbing: restart recovery (Ready restore
 * with an injectable fake directory), the .part housekeeping helpers and the
 * streaming download (driven by a local JDK HttpServer — no network, no
 * Android context).
 */
package io.github.ltbkq.vmdroid.data.repository

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class ApkUpdaterTest {

    private lateinit var updateDir: File
    private lateinit var server: HttpServer

    /** Deterministic fake APK body (~3 chunks worth of data). */
    private val body = ByteArray(200_000) { (it % 251).toByte() }
    private val bodySha = sha256Hex(body)

    @Before
    fun setUp() {
        updateDir = Files.createTempDirectory("vmdroid-update-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/short") { exchange ->
            // Declare the *actual* (half) length so the client sees a clean EOF
            // and the shortfall surfaces as SHORT_READ, not as an IOException.
            exchange.sendResponseHeaders(200, (body.size / 2).toLong())
            exchange.responseBody.use { it.write(body, 0, body.size / 2) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        updateDir.deleteRecursively()
    }

    private fun base(): String = "http://127.0.0.1:${server.address.port}"

    private fun write(dir: File, name: String, bytes: ByteArray): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(bytes) }
    }

    // ------------------------------------------------------------------
    // sha256File / findApkFile / cleanupPartFiles
    // ------------------------------------------------------------------

    @Test
    fun `sha256File hashes the file content`() {
        val file = write(updateDir, "a.apk", "abc".toByteArray())
        // SHA-256 of the ASCII string "abc" (FIPS 180 known-answer vector).
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256File(file),
        )
    }

    @Test
    fun `findApkFile prefers the recorded asset name`() {
        val dir = File(updateDir, "2.1.0")
        write(dir, "vmdroid-2.1.0-debug.apk", byteArrayOf(1))
        write(dir, "vmdroid-2.1.0.apk", byteArrayOf(2))
        assertEquals("vmdroid-2.1.0.apk", findApkFile(dir, "vmdroid-2.1.0.apk")!!.name)
    }

    @Test
    fun `findApkFile ignores part files and empty directories`() {
        val dir = File(updateDir, "2.1.0")
        write(dir, "vmdroid-2.1.0.apk.part", byteArrayOf(9))
        assertNull(findApkFile(dir, ""))
        assertNull(findApkFile(File(updateDir, "does-not-exist"), ""))
        // Recorded name gone → any finished *.apk still counts as a candidate.
        write(dir, "leftover.apk", byteArrayOf(7))
        assertEquals("leftover.apk", findApkFile(dir, "vmdroid-2.1.0.apk")!!.name)
    }

    @Test
    fun `cleanupPartFiles removes leftovers recursively but keeps finished apks`() {
        val v1 = File(updateDir, "2.0.0")
        write(v1, "vmdroid-2.0.0.apk.part", ByteArray(10))
        write(v1, "vmdroid-2.0.0.apk", byteArrayOf(1))
        val v2 = File(updateDir, "2.1.0")
        write(v2, "vmdroid-2.1.0.apk.part", ByteArray(10))

        assertEquals(2, cleanupPartFiles(updateDir))
        assertTrue(File(v1, "vmdroid-2.0.0.apk").isFile)
        assertFalse(File(v1, "vmdroid-2.0.0.apk.part").exists())
        assertFalse(File(v2, "vmdroid-2.1.0.apk.part").exists())
        // Idempotent: nothing left to remove.
        assertEquals(0, cleanupPartFiles(updateDir))
    }

    // ------------------------------------------------------------------
    // Ready-state recovery after a restart (fake directory injection)
    // ------------------------------------------------------------------

    @Test
    fun `restore returns Ready when the downloaded apk still verifies`() {
        val dir = File(updateDir, "2.1.0")
        val bytes = "fake-apk-bytes".toByteArray()
        val file = write(dir, "vmdroid-2.1.0.apk", bytes)
        val record = DownloadedRecord("2.1.0", sha256Hex(bytes), "https://x/r/2.1.0", "vmdroid-2.1.0.apk")

        val restored = restoreReadyFrom(updateDir, record, currentVersion = "2.0.0")
        assertNotNull(restored)
        assertEquals(file, restored!!.file)
        assertEquals("2.1.0", restored.info.latestVersion)
        assertEquals("https://x/r/2.1.0", restored.info.releaseUrl)
        assertEquals(sha256Hex(bytes), restored.info.apkSha256)
        assertEquals(file.length(), restored.info.apk!!.size)
        // A restored record carries no download URL: it must never claim the
        // file is re-downloadable (fail-closed → browser fallback instead).
        assertFalse(restored.info.canAutoDownload)
    }

    @Test
    fun `restore rejects a corrupted or truncated apk`() {
        val dir = File(updateDir, "2.1.0")
        write(dir, "vmdroid-2.1.0.apk", "tampered".toByteArray())
        val record = DownloadedRecord(
            "2.1.0",
            sha256Hex("original".toByteArray()),
            "https://x/r/2.1.0",
            "vmdroid-2.1.0.apk",
        )
        assertNull(restoreReadyFrom(updateDir, record, currentVersion = "2.0.0"))
    }

    @Test
    fun `restore returns null when no finished apk exists`() {
        // Record without any file at all.
        val noFile = DownloadedRecord("2.1.0", bodySha, "https://x/r/2.1.0", "vmdroid-2.1.0.apk")
        assertNull(restoreReadyFrom(updateDir, noFile, currentVersion = "2.0.0"))

        // A killed download leaves only a .part — that must never become Ready.
        val dir = File(updateDir, "2.2.0")
        write(dir, "vmdroid-2.2.0.apk.part", body)
        val partial = DownloadedRecord("2.2.0", bodySha, "https://x/r/2.2.0", "vmdroid-2.2.0.apk")
        assertNull(restoreReadyFrom(updateDir, partial, currentVersion = "2.0.0"))
    }

    @Test
    fun `restore ignores a record that is no longer newer than the running version`() {
        val dir = File(updateDir, "2.1.0")
        val bytes = "fake-apk-bytes".toByteArray()
        write(dir, "vmdroid-2.1.0.apk", bytes)
        val record = DownloadedRecord("2.1.0", sha256Hex(bytes), "https://x/r/2.1.0", "vmdroid-2.1.0.apk")

        // Update already installed (same version) → nothing pending.
        assertNull(restoreReadyFrom(updateDir, record, currentVersion = "2.1.0"))
        assertNull(restoreReadyFrom(updateDir, record, currentVersion = "2.1.0-debug"))
        // Running something newer than the record → stale record, drop it.
        assertNull(restoreReadyFrom(updateDir, record, currentVersion = "3.0.0"))
        // Older but still pending → restores normally.
        assertNotNull(restoreReadyFrom(updateDir, record, currentVersion = "2.0.9"))
    }

    @Test
    fun `restore rejects records with missing fields`() {
        // Nothing persisted at all.
        assertNull(restoreReadyFrom(updateDir, DownloadedRecord("", "", "", ""), currentVersion = "2.0.0"))
        // Version without a checksum can never be re-verified → refuse.
        assertNull(restoreReadyFrom(updateDir, DownloadedRecord("2.1.0", "", "https://x", "a.apk"), currentVersion = "2.0.0"))

        // Blank url/asset name are tolerated: the file itself is the source of truth.
        val bytes = "fake-apk-bytes".toByteArray()
        write(File(updateDir, "2.1.0"), "vmdroid-2.1.0.apk", bytes)
        val restored = restoreReadyFrom(
            updateDir,
            DownloadedRecord("2.1.0", sha256Hex(bytes), "", ""),
            currentVersion = "2.0.0",
        )
        assertNotNull(restored)
        assertEquals("vmdroid-2.1.0.apk", restored!!.info.apk!!.name)
    }

    // ------------------------------------------------------------------
    // streamApk — real HTTP against a local server
    // ------------------------------------------------------------------

    @Test
    fun `streamApk downloads verifies and renames into place`() {
        val part = File(updateDir, "vmdroid-2.1.0.apk.part")
        val final = File(updateDir, "vmdroid-2.1.0.apk")
        val progress = mutableListOf<Long>()

        val result = streamApk(
            downloadUrl = "${base()}/apk",
            partFile = part,
            finalFile = final,
            expectedSha256 = bodySha,
            totalSize = body.size.toLong(),
            onProgress = { progress.add(it) },
        )

        assertTrue("expected success, got $result", result is ApkDownloadResult.Success)
        assertEquals(bodySha, (result as ApkDownloadResult.Success).sha256)
        assertTrue(final.isFile)
        assertTrue(final.readBytes().contentEquals(body))
        assertFalse("partial file must be renamed away", part.exists())
        // The final progress callback carries the exact byte count.
        assertEquals(body.size.toLong(), progress.last())
        assertTrue(progress.first() in 1L..body.size.toLong())
    }

    @Test
    fun `streamApk rejects a sha mismatch and leaves nothing behind`() {
        val part = File(updateDir, "vmdroid-2.1.0.apk.part")
        val final = File(updateDir, "vmdroid-2.1.0.apk")

        val result = streamApk(
            downloadUrl = "${base()}/apk",
            partFile = part,
            finalFile = final,
            expectedSha256 = sha256Hex("different-content".toByteArray()),
            totalSize = body.size.toLong(),
        )

        assertTrue(result is ApkDownloadResult.Failure)
        result as ApkDownloadResult.Failure
        assertEquals(UpdateFailures.SHA_MISMATCH, result.reason)
        assertFalse("a mismatching download must never be kept", final.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `streamApk reports a short read when the body is smaller than the declared size`() {
        val part = File(updateDir, "a.part")
        val final = File(updateDir, "a.apk")

        val result = streamApk(
            downloadUrl = "${base()}/short",
            partFile = part,
            finalFile = final,
            expectedSha256 = bodySha,
            totalSize = body.size.toLong(),
        )

        assertTrue(result is ApkDownloadResult.Failure)
        assertEquals(UpdateFailures.SHORT_READ, (result as ApkDownloadResult.Failure).reason)
        assertTrue(result.transient)
        assertFalse(final.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `streamApk reports an HTTP failure for a missing asset`() {
        val result = streamApk(
            downloadUrl = "${base()}/missing",
            partFile = File(updateDir, "a.part"),
            finalFile = File(updateDir, "a.apk"),
            expectedSha256 = bodySha,
            totalSize = body.size.toLong(),
        )
        assertTrue(result is ApkDownloadResult.Failure)
        result as ApkDownloadResult.Failure
        assertEquals(UpdateFailures.HTTP, result.reason)
        assertFalse(result.transient)
        assertTrue(result.detail.contains("404"))
    }

    @Test
    fun `streamApk classifies an unreachable host as a transient network failure`() {
        // Port 1 on loopback: connection refused → IOException → NETWORK.
        val result = streamApk(
            downloadUrl = "http://127.0.0.1:1/apk",
            partFile = File(updateDir, "a.part"),
            finalFile = File(updateDir, "a.apk"),
            expectedSha256 = bodySha,
            totalSize = body.size.toLong(),
        )
        assertTrue(result is ApkDownloadResult.Failure)
        result as ApkDownloadResult.Failure
        assertEquals(UpdateFailures.NETWORK, result.reason)
        assertTrue("a transient failure earns only the short retry floor", result.transient)
        assertFalse(File(updateDir, "a.apk").exists())
        assertFalse(File(updateDir, "a.part").exists())
    }

    // ------------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------------

    @Test
    fun `downloading percent is derived from bytes and total`() {
        val info = UpdateInfo("2.1.0", "https://x/r/2.1.0")
        assertEquals(25, UpdateState.Downloading(info, 50, 200).percent)
        assertEquals(100, UpdateState.Downloading(info, 200, 200).percent)
        // Unknown total must not divide by zero.
        assertEquals(0, UpdateState.Downloading(info, 5, 0).percent)
        // Info is null exactly for Idle/Checking — that is what hides the banner.
        assertNull(UpdateState.Idle.info)
        assertNull(UpdateState.Checking.info)
        assertEquals(info, UpdateState.Available(info).info)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
