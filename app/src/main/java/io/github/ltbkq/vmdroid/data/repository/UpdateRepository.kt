package io.github.ltbkq.vmdroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.ltbkq.vmdroid.systemimage.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of an update check.
 *
 * [apk]/[apkSha256] describe the in-app download path; either being null does
 * **not** fail the check — it degrades it to "open the release page in a
 * browser" (the pre-2.0 behaviour), which is also the fail-closed answer when
 * the release ships no APK asset or no usable checksum.
 */
data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    /** APK asset chosen from the release; null = this release ships no APK. */
    val apk: UpdateAsset? = null,
    /** Lowercase hex sha256 of [apk]; null = no integrity value available. */
    val apkSha256: String? = null,
) {
    /**
     * True when a verified in-app download is possible at all. Fail-closed:
     * every field (asset, checksum, download URL) must be present, otherwise
     * the caller must fall back to opening [releaseUrl].
     */
    val canAutoDownload: Boolean
        get() = apk != null && apkSha256 != null && apk.downloadUrl.isNotBlank()
}

/** One GitHub release asset, as needed by the updater. */
data class UpdateAsset(
    val name: String,
    val size: Long,
    /** `browser_download_url` — the direct, redirect-following download link. */
    val downloadUrl: String,
    /** Raw API `digest` field (`"sha256:<hex>"`); null when not provided. */
    val digest: String? = null,
)

/** `v`-stripped tag must be a pure dotted version (`1`, `1.3.0`, …). */
private val RELEASE_TAG_PATTERN = Regex("""^\d+(\.\d+)*""")
private val APK_ASSET_PATTERN = Regex("""^vmdroid-.*\.apk$""")
private val SHA256_HEX_PATTERN = Regex("""^[0-9a-f]{64}$""")
/** Standard `sha256sum` line: 64 hex, whitespace, optional binary marker `*`, name. */
private val SUMS_LINE_PATTERN = Regex("""^([0-9a-f]{64})\s+\*?(.+)$""")

/**
 * Tag guard: only plain version tags (`v1.3.0`, `2.0.0`) may reach [isNewer].
 * The GitHub repo also carries non-app releases (e.g. `system-images-*` tags)
 * whose names would otherwise be fed into version comparison; those return
 * false from [isNewer] only by accident, so they are rejected here as a
 * contract instead. Applied after a single `v` prefix strip, per release
 * convention. Prerelease-suffixed tags (`v1.3.0-rc1`) are deliberately not
 * matched either: `releases/latest` only ever surfaces full releases.
 */
internal fun isReleaseTag(tag: String): Boolean =
    RELEASE_TAG_PATTERN.matches(tag.removePrefix("v"))

/**
 * Parses the `assets` array of a GitHub release payload. Malformed entries
 * (missing name/URL) are skipped rather than failing the whole release.
 * Throws [IllegalArgumentException] on an unparsable payload — callers treat
 * that as "no assets" (degrade to the browser) instead of failing the check.
 */
internal fun parseReleaseAssets(json: String): List<UpdateAsset> =
    parseReleaseAssets(MiniJson.parseObject(json))

/** Map overload so the release payload is only parsed once. */
internal fun parseReleaseAssets(root: Map<String, Any?>): List<UpdateAsset> {
    val raw = root["assets"] as? List<*> ?: return emptyList()
    return raw.mapNotNull { entry ->
        val m = entry as? Map<*, *> ?: return@mapNotNull null
        val name = MiniJson.str(m, "name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val url = MiniJson.str(m, "browser_download_url")?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        UpdateAsset(
            name = name,
            size = MiniJson.long(m, "size") ?: 0L,
            downloadUrl = url,
            digest = MiniJson.str(m, "digest"),
        )
    }
}

/**
 * Picks the APK for this release:
 *  1. `vmdroid-<latestVersion>.apk` (exact),
 *  2. `vmdroid-<latestVersion>-<anything>.apk` (build-type suffix tolerance,
 *     e.g. `-debug`),
 *  3. any other `vmdroid-*.apk` shipped by the release.
 *
 * Returns null when the release ships no `vmdroid-*.apk` at all → the caller
 * degrades to "open the release page".
 */
internal fun selectApkAsset(assets: List<UpdateAsset>, latestVersion: String): UpdateAsset? {
    val candidates = assets.filter { APK_ASSET_PATTERN.matches(it.name) }
    if (candidates.isEmpty()) return null
    candidates.firstOrNull { it.name == "vmdroid-$latestVersion.apk" }?.let { return it }
    candidates.firstOrNull { it.name.startsWith("vmdroid-$latestVersion-") }?.let { return it }
    return candidates.first()
}

/**
 * `digest: "sha256:<hex>"` → lowercase hex. Anything else (absent digest,
 * another algorithm, malformed hex) → null so the caller can fall back to the
 * `SHA256SUMS` asset; both unavailable means no automatic download.
 */
internal fun sha256FromDigest(digest: String?): String? {
    val value = digest?.trim() ?: return null
    if (!value.startsWith("sha256:", ignoreCase = true)) return null
    return value.substring("sha256:".length).trim().lowercase()
        .takeIf { SHA256_HEX_PATTERN.matches(it) }
}

/** One `sha256sum -c` line → (hex, file name); non-matching lines → null. */
internal fun parseSumsLine(line: String): Pair<String, String>? {
    val m = SUMS_LINE_PATTERN.matchEntire(line.removeSuffix("\r")) ?: return null
    return m.groupValues[1] to m.groupValues[2]
}

/**
 * Looks up [fileName] in a `SHA256SUMS` body. Tolerates the two standard
 * layouts — `hash  name` (two spaces, text mode) and `hash *name` (binary
 * mode) — since `\s+` eats any run of whitespace before the optional `*`.
 */
internal fun sha256FromSums(sumsText: String, fileName: String): String? =
    sumsText.lineSequence()
        .mapNotNull(::parseSumsLine)
        .firstOrNull { (_, name) -> name == fileName }
        ?.first
        ?.lowercase()

/**
 * Expected sha256 for [fileName]: GitHub's asset `digest` first, the release's
 * `SHA256SUMS` body second, null when neither yields a value — the caller must
 * then refuse the automatic download (fail-closed) and degrade to the release
 * page rather than installing an unverified APK.
 */
internal fun expectedSha256(digest: String?, sumsText: String?, fileName: String): String? =
    sha256FromDigest(digest) ?: sumsText?.let { sha256FromSums(it, fileName) }

/**
 * Update-check endpoint. Points at this project's own releases (not the
 * upstream ExTV/Podroid releases, which would surface the wrong APK to users).
 * Overridable for CI/staging via a system property.
 */
private val UPDATE_RELEASES_URL: String =
    System.getProperty("vmdroid.updateReleasesUrl")
        ?: "https://api.github.com/repos/ltbkq/vmdroid/releases/latest"

// ---------------------------------------------------------------- 更新偏好（设置页开关）
// Top-level so the key literals are pinned by tests (see UpdateAssetsTest's
// key-contract block) — a rename without a migration silently resets the switch.

/** 「自动检查更新」（默认开）。 */
internal val updateAutoCheckKey = booleanPreferencesKey("update_auto_check")

/** 「自动下载更新」（默认开）。 */
internal val updateAutoDownloadKey = booleanPreferencesKey("update_auto_download")

/**
 * Returns true when the cache has expired and a fresh network check is warranted.
 *
 * Uses wall-clock milliseconds (currentTimeMillis) — not uptimeMillis, which
 * resets to zero on reboot and causes the post-reboot delta to be a large
 * negative number, permanently skipping the check. A negative delta is clamped
 * to "stale" so a clock going backwards also triggers a re-check rather than
 * suppressing it indefinitely.
 */
internal fun shouldCheck(now: Long, lastCheck: Long, validityMs: Long): Boolean {
    val elapsed = now - lastCheck
    return elapsed < 0L || elapsed >= validityMs
}

/**
 * Timestamp to persist after a failed update check so [shouldCheck] re-opens the
 * gate at the right time. A definitive outcome (GitHub was actually reached)
 * backs off the full [validityMs]. A transient failure (offline / DNS / timeout)
 * is back-dated so the existing validity gate re-opens after only [retryFloorMs]
 * instead of a full day — one offline launch must not suppress the check for 24h.
 */
internal fun backoffStamp(now: Long, transient: Boolean, validityMs: Long, retryFloorMs: Long): Long =
    if (transient) now - validityMs + retryFloorMs else now

/**
 * Returns true if `latest` is a higher version than `current`. Compares the
 * numeric core (`1.2.3`) first; if those are equal, treats a prerelease suffix
 * (`-rc2`) as lower than a release, and compares prerelease numeric chunks
 * numerically so `rc10 > rc9`.
 */
internal fun isNewer(latest: String, current: String): Boolean {
    fun splitCore(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val l = splitCore(latest)
    val c = splitCore(current)
    val maxLen = maxOf(l.size, c.size)
    for (i in 0 until maxLen) {
        val lv = l.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    // Numeric cores match — break ties by suffix. Empty suffix > any prerelease suffix.
    val ls = latest.substringAfter("-", "")
    val cs = current.substringAfter("-", "")
    return when {
        ls == cs -> false
        ls.isEmpty() -> true   // "1.2.0" is newer than "1.2.0-rc1"
        cs.isEmpty() -> false  // "1.2.0-rc1" is older than "1.2.0"
        else -> comparePrerelease(ls, cs) > 0
    }
}

/**
 * Compares two prerelease strings (the part after `-`) by splitting on
 * non-numeric/numeric boundaries and comparing each chunk: numeric chunks
 * compare as integers, non-numeric chunks compare lexicographically.
 * Example: "rc10" > "rc9" because the numeric chunk 10 > 9.
 */
private fun comparePrerelease(a: String, b: String): Int {
    fun chunks(s: String): List<String> = buildList {
        val sb = StringBuilder()
        var numeric = s.firstOrNull()?.isDigit() ?: false
        for (ch in s) {
            if (ch.isDigit() == numeric) {
                sb.append(ch)
            } else {
                if (sb.isNotEmpty()) add(sb.toString())
                sb.clear()
                sb.append(ch)
                numeric = ch.isDigit()
            }
        }
        if (sb.isNotEmpty()) add(sb.toString())
    }

    val ac = chunks(a)
    val bc = chunks(b)
    val len = maxOf(ac.size, bc.size)
    for (i in 0 until len) {
        val ac_ = ac.getOrElse(i) { "" }
        val bc_ = bc.getOrElse(i) { "" }
        val cmp = when {
            ac_.isEmpty() && bc_.isEmpty() -> 0
            ac_.isEmpty() -> -1
            bc_.isEmpty() -> 1
            ac_.all { it.isDigit() } && bc_.all { it.isDigit() } ->
                // Clamp oversized numeric chunks to lexicographic so a malformed
                // tag (>18 digits, > Long.MAX) can't throw NumberFormatException.
                if (ac_.length <= 18 && bc_.length <= 18) ac_.toLong().compareTo(bc_.toLong())
                else ac_.compareTo(bc_)
            else -> ac_.compareTo(bc_)
        }
        if (cmp != 0) return cmp
    }
    return 0
}

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dismissedKey = stringPreferencesKey("dismissed_update_version")
    private val lastCheckKey = longPreferencesKey("update_check_timestamp")
    private val cacheValidityMs = 24 * 60 * 60 * 1000L
    // A transient (IOException) failure backs off only this long instead of the
    // full cacheValidityMs, so a single offline launch doesn't suppress the next
    // in-app update check for a full day.
    private val retryFloorMs = 20 * 60 * 1000L

    // ---------------------------------------------------------------- 设置（应用更新分区）
    // Keys live in the shared settings DataStore (same file as SettingsRepository's
    // keys). Adding keys is always safe; see DataStoreKeyContractTest for why
    // existing literals must never be renamed.

    /** Home/Settings "自动检查更新" — off means the startup check is skipped entirely. */
    val autoCheckEnabled: Flow<Boolean> = pref(updateAutoCheckKey, true)

    /** "自动下载更新" — off means an available update is only ever offered, never fetched. */
    val autoDownloadEnabled: Flow<Boolean> = pref(updateAutoDownloadKey, true)

    /** Version the user explicitly hid ("忽略此版本"); null when nothing was dismissed. */
    val dismissedVersion: Flow<String?> = context.dataStore.data
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { it[dismissedKey]?.takeIf(String::isNotEmpty) }

    suspend fun setAutoCheckEnabled(value: Boolean) {
        context.dataStore.edit { it[updateAutoCheckKey] = value }
    }

    suspend fun setAutoDownloadEnabled(value: Boolean) {
        context.dataStore.edit { it[updateAutoDownloadKey] = value }
    }

    private fun <T> pref(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data
            .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
            .map { it[key] ?: default }

    suspend fun checkForUpdate(currentVersion: String, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        // Wall-clock time so the 24h gate survives device reboots and deep sleep.
        val now = System.currentTimeMillis()
        var connection: java.net.HttpURLConnection? = null
        try {
            val lastCheck = context.dataStore.data
                .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
                .first()[lastCheckKey] ?: 0L

            // force = Settings 的「立即检查」：绕过 24h 缓存门，但仍在完成后记录时间戳。
            if (!force && !shouldCheck(now, lastCheck, cacheValidityMs)) {
                return@withContext null
            }

            connection = URL(UPDATE_RELEASES_URL)
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            val code = connection.responseCode
            if (code !in 200..299) {
                // Consume error body so the connection can be reused, then back off.
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val root = MiniJson.parseObject(json)
            // removePrefix rather than trimStart — trimStart strips repeated 'v' chars.
            val rawTag = MiniJson.str(root, "tag_name") ?: ""
            val tag = rawTag.removePrefix("v")
            val url = MiniJson.str(root, "html_url") ?: ""

            if (tag.isEmpty() || url.isEmpty()) {
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            // Tag guard before isNewer: a non-version tag (`system-images-*` etc.)
            // means this release is not an app update — ignore it (warning only,
            // it must not break the check or trip the failure backoff).
            if (!isReleaseTag(rawTag)) {
                android.util.Log.w("UpdateRepository", "ignoring non-version release tag: $rawTag")
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            context.dataStore.edit { it[lastCheckKey] = now }

            // Build-type suffix (`-debug`) is decoration, not a prerelease — strip it so
            // 1.1.7-debug compares equal to the 1.1.7 release tag instead of "older".
            val normalizedCurrent = currentVersion.removeSuffix("-debug")
            if (!isNewer(tag, normalizedCurrent)) return@withContext null

            // ---- 资产与完整性（失败一律降级为「打开发布页」，不阻断检查） ----
            val assets = runCatching { parseReleaseAssets(root) }.getOrElse { e ->
                android.util.Log.w("UpdateRepository", "release asset parse failed", e)
                emptyList()
            }
            val apk = selectApkAsset(assets, tag)
            if (apk == null) {
                android.util.Log.w("UpdateRepository", "release $tag ships no APK asset — degrading to the release page")
                return@withContext UpdateInfo(tag, url)
            }
            var sha256 = sha256FromDigest(apk.digest)
            if (sha256 == null) {
                // Fall back to the release's SHA256SUMS file (standard sha256sum -c body).
                val sums = assets.firstOrNull { it.name.equals("SHA256SUMS", ignoreCase = true) }
                if (sums != null) {
                    sha256 = runCatching { fetchText(sums.downloadUrl) }.getOrNull()
                        ?.let { sha256FromSums(it, apk.name) }
                }
            }
            if (sha256 == null) {
                // No digest and no usable SHA256SUMS line → refuse the automatic
                // download (fail-closed) but keep the check itself successful.
                android.util.Log.w(
                    "UpdateRepository",
                    "no integrity value for ${apk.name} (digest/SHA256SUMS) — automatic download disabled for $tag",
                )
            }
            UpdateInfo(tag, url, apk, sha256)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation is not a check failure — don't record a
            // timestamp (which would back off a check the user may retry) and
            // don't swallow it; let it propagate.
            throw c
        } catch (e: Exception) {
            // Network/DataStore/JSON error: log it (a changed GitHub response or
            // JSON shape was previously failing invisibly) and record a timestamp
            // to back off on the next launch. A transient network failure
            // (offline / DNS / timeout — all IOException) only earns the short
            // retry floor; a non-IO error means GitHub was reached but returned
            // something unusable, so it keeps the full 24h backoff.
            android.util.Log.w("UpdateRepository", "update check failed", e)
            val stamp = backoffStamp(now, transient = e is java.io.IOException, cacheValidityMs, retryFloorMs)
            runCatching { context.dataStore.edit { it[lastCheckKey] = stamp } }
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Small capped GET used for the release's `SHA256SUMS` (a text asset of a
     * few hundred bytes). Returns null on any non-2xx/IO failure — the caller
     * degrades instead of failing the check.
     */
    private fun fetchText(url: String): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < MAX_SUMS_BYTES) {
                    val n = reader.read(buf, 0, minOf(buf.size, MAX_SUMS_BYTES - sb.length))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } catch (e: Exception) {
            android.util.Log.w("UpdateRepository", "SHA256SUMS fetch failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun isDismissed(version: String): Boolean {
        // Shield the read so a corrupted store returns "not dismissed" instead of
        // throwing into the caller.
        val dismissed = context.dataStore.data
            .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
            .first()[dismissedKey]
        return dismissed == version
    }

    suspend fun dismissUpdate(version: String) {
        context.dataStore.edit { it[dismissedKey] = version }
    }

    companion object {
        /** Upper bound for a SHA256SUMS body read (it is ~100 bytes per file). */
        private const val MAX_SUMS_BYTES = 1 shl 20
    }
}
