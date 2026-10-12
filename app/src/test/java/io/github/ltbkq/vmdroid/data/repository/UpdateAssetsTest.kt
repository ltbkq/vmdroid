/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Unit tests for the v2.0.0 release-asset pipeline: JSON → asset list → APK
 * selection → integrity resolution (digest / SHA256SUMS) → tag guard.
 * All pure functions from UpdateRepository.kt — no Android context needed.
 */
package io.github.ltbkq.vmdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAssetsTest {

    companion object {
        private const val SHA_APK = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private const val SHA_SUMS = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }

    private fun assetJson(
        name: String,
        size: Long = 1_000L,
        url: String? = "https://github.example/dl/$name",
        digest: String? = null,
    ): String = buildString {
        append("{\"name\":\"").append(name).append("\"")
        append(",\"size\":").append(size)
        if (url != null) append(",\"browser_download_url\":\"").append(url).append("\"")
        if (digest != null) append(",\"digest\":\"").append(digest).append("\"")
        append("}")
    }

    private fun releaseJson(vararg assets: String): String =
        "{\"tag_name\":\"v2.0.0\",\"html_url\":\"https://github.example/rel\"," +
            "\"assets\":[${assets.joinToString(",")}]}"

    // ------------------------------------------------------------------
    // parseReleaseAssets
    // ------------------------------------------------------------------

    @Test
    fun `parseReleaseAssets reads name size url and digest`() {
        val json = releaseJson(
            assetJson("vmdroid-2.0.0.apk", size = 12_345L, digest = "sha256:$SHA_APK"),
            assetJson("SHA256SUMS", size = 99L, url = "https://github.example/dl/SHA256SUMS"),
        )
        val assets = parseReleaseAssets(json)
        assertEquals(2, assets.size)
        assertEquals("vmdroid-2.0.0.apk", assets[0].name)
        assertEquals(12_345L, assets[0].size)
        assertEquals("https://github.example/dl/vmdroid-2.0.0.apk", assets[0].downloadUrl)
        assertEquals("sha256:$SHA_APK", assets[0].digest)
        // digest is optional — GitHub only started emitting it recently.
        assertNull(assets[1].digest)
    }

    @Test
    fun `parseReleaseAssets skips entries without a name or download url`() {
        val json = "{\"assets\":[" +
            "{\"browser_download_url\":\"https://x/y\"}," + // no name
            "{\"name\":\"orphan.apk\"}," +                   // no url
            "42," +                                          // not an object
            assetJson("vmdroid-2.0.0.apk") +
            "]}"
        val assets = parseReleaseAssets(json)
        assertEquals(1, assets.size)
        assertEquals("vmdroid-2.0.0.apk", assets[0].name)
    }

    @Test
    fun `parseReleaseAssets returns empty when the payload has no assets array`() {
        assertTrue(parseReleaseAssets("{\"tag_name\":\"v2.0.0\"}").isEmpty())
        assertTrue(parseReleaseAssets("{\"assets\":\"nope\"}").isEmpty())
    }

    // ------------------------------------------------------------------
    // APK selection
    // ------------------------------------------------------------------

    @Test
    fun `selectApkAsset prefers the exact versioned name`() {
        val assets = listOf(
            UpdateAsset("vmdroid-2.0.0-debug.apk", 1, "https://x/a"),
            UpdateAsset("vmdroid-2.0.0.apk", 1, "https://x/b"),
            UpdateAsset("vmdroid-arm64.apk", 1, "https://x/c"),
        )
        assertEquals("vmdroid-2.0.0.apk", selectApkAsset(assets, "2.0.0")!!.name)
    }

    @Test
    fun `selectApkAsset tolerates a build-type suffix`() {
        // Only the `-debug` build is attached — still this version's APK.
        val assets = listOf(
            UpdateAsset("vmdroid-arm64.apk", 1, "https://x/c"),
            UpdateAsset("vmdroid-2.0.0-debug.apk", 1, "https://x/a"),
        )
        assertEquals("vmdroid-2.0.0-debug.apk", selectApkAsset(assets, "2.0.0")!!.name)
    }

    @Test
    fun `selectApkAsset falls back to any vmdroid apk`() {
        val assets = listOf(UpdateAsset("vmdroid-arm64-release.apk", 1, "https://x/c"))
        assertEquals("vmdroid-arm64-release.apk", selectApkAsset(assets, "2.0.0")!!.name)
    }

    @Test
    fun `selectApkAsset returns null when the release ships no matching apk`() {
        val assets = listOf(
            UpdateAsset("SHA256SUMS", 1, "https://x/s"),
            UpdateAsset("Podroid-1.0.apk", 1, "https://x/other"), // wrong prefix
            UpdateAsset("vmdroid-2.0.0.zip", 1, "https://x/z"),   // wrong extension
        )
        assertNull(selectApkAsset(assets, "2.0.0"))
    }

    @Test
    fun `selectApkAsset returns null for an empty asset list`() {
        assertNull(selectApkAsset(emptyList(), "2.0.0"))
    }

    // ------------------------------------------------------------------
    // Integrity path 1: GitHub asset digest
    // ------------------------------------------------------------------

    @Test
    fun `sha256FromDigest accepts a sha256 digest and lowercases it`() {
        assertEquals(SHA_APK, sha256FromDigest("sha256:${SHA_APK.uppercase()}"))
        assertEquals(SHA_APK, sha256FromDigest("  sha256:$SHA_APK  "))
    }

    @Test
    fun `sha256FromDigest rejects absent foreign or malformed digests`() {
        assertNull(sha256FromDigest(null))
        assertNull(sha256FromDigest(""))
        assertNull(sha256FromDigest("md5:0123456789abcdef0123456789abcdef"))
        assertNull(sha256FromDigest("sha256:tooshort"))
        assertNull(sha256FromDigest(SHA_APK)) // bare hex without the algorithm prefix
    }

    @Test
    fun `expectedSha256 prefers the asset digest over SHA256SUMS`() {
        val sums = "$SHA_SUMS  vmdroid-2.0.0.apk\n"
        assertEquals(SHA_APK, expectedSha256("sha256:$SHA_APK", sums, "vmdroid-2.0.0.apk"))
    }

    // ------------------------------------------------------------------
    // Integrity path 2: SHA256SUMS fallback
    // ------------------------------------------------------------------

    @Test
    fun `expectedSha256 falls back to SHA256SUMS when the digest is missing`() {
        val sums = "$SHA_SUMS  other-file.zip\n$SHA_APK  vmdroid-2.0.0.apk\n"
        assertEquals(SHA_APK, expectedSha256(null, sums, "vmdroid-2.0.0.apk"))
        // Foreign digest algorithm → still falls back to the sums file.
        assertEquals(SHA_APK, expectedSha256("md5:deadbeef", sums, "vmdroid-2.0.0.apk"))
    }

    @Test
    fun `expectedSha256 is null when neither source has a value (fail-closed)`() {
        assertNull(expectedSha256(null, null, "vmdroid-2.0.0.apk"))
        assertNull(expectedSha256(null, "", "vmdroid-2.0.0.apk"))
        assertNull(expectedSha256(null, "$SHA_SUMS  other.zip\n", "vmdroid-2.0.0.apk"))
    }

    @Test
    fun `sha256FromSums accepts the standard two-space text-mode layout`() {
        val sums = "$SHA_APK  vmdroid-2.0.0.apk\n"
        assertEquals(SHA_APK, sha256FromSums(sums, "vmdroid-2.0.0.apk"))
    }

    @Test
    fun `sha256FromSums accepts the star-prefixed binary-mode layout`() {
        val sums = "$SHA_APK *vmdroid-2.0.0.apk\n"
        assertEquals(SHA_APK, sha256FromSums(sums, "vmdroid-2.0.0.apk"))
    }

    @Test
    fun `sha256FromSums tolerates CRLF comments blank lines and tab separators`() {
        val sums = "# generated by release workflow\r\n" +
            "\r\n" +
            "$SHA_SUMS\tSHA256SUMS\r\n" +
            "$SHA_APK  vmdroid-2.0.0.apk\r\n"
        assertEquals(SHA_APK, sha256FromSums(sums, "vmdroid-2.0.0.apk"))
    }

    @Test
    fun `sha256FromSums matches only the requested file name`() {
        val sums = "$SHA_SUMS  vmdroid-2.0.0-debug.apk\n$SHA_APK  vmdroid-2.0.0.apk\n"
        assertEquals(SHA_APK, sha256FromSums(sums, "vmdroid-2.0.0.apk"))
        assertEquals(SHA_SUMS, sha256FromSums(sums, "vmdroid-2.0.0-debug.apk"))
        assertNull(sha256FromSums(sums, "missing.apk"))
    }

    @Test
    fun `parseSumsLine rejects anything that is not a standard sha256sum line`() {
        assertNull(parseSumsLine(""))                                  // blank
        assertNull(parseSumsLine("# comment"))                          // comment
        assertNull(parseSumsLine("${SHA_APK.drop(1)}  file.apk"))       // 63 hex chars
        assertNull(parseSumsLine("${SHA_APK}x  file.apk"))              // 65 hex chars
        assertNull(parseSumsLine("nothex-at-all-but-64-chars-long-string-here..  file")) // not hex
        assertNull(parseSumsLine("d41d8cd98f00b204e9800998ecf8427e  file.apk")) // md5 (32 hex)
        // And a valid line still parses.
        assertEquals(SHA_APK to "file.apk", parseSumsLine("$SHA_APK  file.apk"))
    }

    // ------------------------------------------------------------------
    // Tag guard (only version tags may reach isNewer)
    // ------------------------------------------------------------------

    @Test
    fun `isReleaseTag accepts plain version tags`() {
        assertTrue(isReleaseTag("v1.3.0"))
        assertTrue(isReleaseTag("1.3.0"))
        assertTrue(isReleaseTag("v2.0.0"))
        assertTrue(isReleaseTag("v12"))
        assertTrue(isReleaseTag("2.0"))
    }

    @Test
    fun `isReleaseTag rejects non-version tags`() {
        // The repo also hosts non-app releases; their tags must never reach isNewer.
        assertFalse(isReleaseTag("system-images-android-34"))
        assertFalse(isReleaseTag("vsystem-images-1"))
        assertFalse(isReleaseTag("latest"))
        assertFalse(isReleaseTag(""))
        assertFalse(isReleaseTag("v2-images"))   // digits must be a dotted version
        assertFalse(isReleaseTag("vv1.0.0"))     // only one leading `v` is stripped
        assertFalse(isReleaseTag("2.0.0-beta1")) // releases/latest never serves prereleases
    }

    // ------------------------------------------------------------------
    // DataStore key literals for the new update settings/records
    // ------------------------------------------------------------------

    @Test
    fun `update preference key literals are pinned`() {
        // Renaming these without a migration orphans the stored value (see
        // DataStoreKeyContractTest for why persistence is sacred).
        assertEquals("update_auto_check", updateAutoCheckKey.name)
        assertEquals("update_auto_download", updateAutoDownloadKey.name)
        assertEquals("update_downloaded_version", updateDownloadedVersionKey.name)
        assertEquals("update_downloaded_sha256", updateDownloadedShaKey.name)
        assertEquals("update_downloaded_release_url", updateDownloadedUrlKey.name)
        assertEquals("update_downloaded_asset", updateDownloadedAssetKey.name)
        assertEquals("update_download_failure_stamp", updateDownloadFailureKey.name)
    }

    @Test
    fun `update preference key literals are unique`() {
        val names = listOf(
            updateAutoCheckKey,
            updateAutoDownloadKey,
            updateDownloadedVersionKey,
            updateDownloadedShaKey,
            updateDownloadedUrlKey,
            updateDownloadedAssetKey,
            updateDownloadFailureKey,
        ).map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
