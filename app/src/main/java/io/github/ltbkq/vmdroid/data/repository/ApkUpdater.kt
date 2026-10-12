/*
 * VMDroid - in-app update downloader / installer (v2.0.0 自动更新).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 平台合规的「半自动」更新：下载静默（后台、带进度），安装必须用户确认 ——
 * Android 从 7.0 起禁止静默安装（`PackageInstaller` 必须走用户可见确认），
 * 所以最多只能做到「下载好了点一下安装」。
 *
 * 状态机（StateFlow，设置页与 Home 横幅共享同一份状态）：
 *
 *   Idle ──check()──▶ Checking ──有新版本──▶ Available ──download()──▶ Downloading
 *     ▲                   │                     │                        │  │
 *     │                   └──无更新/门关闭──▶ Idle│◀──开关关/无资产/退避门◀──┘  │
 *     │                                             │                          │
 *     │                                             └──download 失败──▶ Failed ──重试──▶ Downloading
 *     │                                             └──校验通过+rename──▶ Ready（file）──installUpdate()──▶ 系统安装器
 *     └── 重启后 restore()：filesDir/update/<version>/ 下的 APK 存在且 sha256 复验通过 ──▶ Ready
 *
 * 持久化：DataStore 记「已下载版本 + 其 sha256 + releaseUrl + 资产名」，
 * 目录 `filesDir/update/<version>/` 存成品 APK（`.part` 永不冒充成品，
 * fsync 后原子 rename；残留 `.part` 下次启动/新版本下载前清理）。
 *
 * 失败退避：与 UpdateRepository 的检查门同一节奏 —— 失败写入
 * `update_download_failure_stamp`，24h 内自动下载不再重试；瞬时失败（IO）
 * 只退 20 分钟。手动「下载/重试」绕过该门（用户意图优先）。
 */
package io.github.ltbkq.vmdroid.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ltbkq.vmdroid.BuildConfig
import io.github.ltbkq.vmdroid.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------- 状态

/**
 * Updater state shared by Settings and the Home banner.
 * [info] is null exactly when no update is known (Idle/Checking) — the UI keys
 * banner visibility off that so a running check never flickers the banner away.
 */
sealed class UpdateState {
    /** Never checked, or checked and already up to date. */
    object Idle : UpdateState() {
        override val info: UpdateInfo? get() = null
    }

    /** Check (and local re-verification of a downloaded APK) in flight. */
    object Checking : UpdateState() {
        override val info: UpdateInfo? get() = null
    }

    /** New version known; may or may not be auto-downloadable (see [UpdateInfo.canAutoDownload]). */
    data class Available(override val info: UpdateInfo) : UpdateState()

    /** Download running — [bytes]/[total] drive the progress UI. */
    data class Downloading(override val info: UpdateInfo, val bytes: Long, val total: Long) : UpdateState() {
        val percent: Int
            get() = if (total > 0) ((bytes * 100) / total).coerceIn(0L, 100L).toInt() else 0
    }

    /** APK on disk, sha256 verified — one tap away from the system installer. */
    data class Ready(override val info: UpdateInfo, val file: File) : UpdateState()

    /** Download attempt failed; [reason] is one of [UpdateFailures]. */
    data class Failed(override val info: UpdateInfo?, val reason: String) : UpdateState()

    /** The update being tracked, when there is one (banner source of truth). */
    abstract val info: UpdateInfo?
}

/** Machine-readable failure reasons (UI maps them to localized strings). */
object UpdateFailures {
    const val NETWORK = "NETWORK"
    const val HTTP = "HTTP"
    const val SHORT_READ = "SHORT_READ"
    const val SHA_MISMATCH = "SHA_MISMATCH"
    const val STORAGE = "STORAGE"
    const val IO = "IO"
    const val NO_APK = "NO_APK"
    const val NO_CHECKSUM = "NO_CHECKSUM"
}

// ---------------------------------------------------------------- 纯函数（单测覆盖）

/** One persisted download record ("which APK is already sitting on disk"). */
internal data class DownloadedRecord(
    val version: String,
    val sha256: String,
    val releaseUrl: String,
    val assetName: String,
)

/** Successful [restoreReadyFrom] result: reconstructed info + the verified file. */
internal data class RestoredReady(val info: UpdateInfo, val file: File)

/** Lowercase hex sha256 of a file, streamed (APK-sized, constant memory). */
internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The finished APK inside `update/<version>/`: the recorded asset name first,
 * otherwise any `*.apk` (a renamed/older record must not silently match a
 * `.part` — those are excluded by the suffix check).
 */
internal fun findApkFile(dir: File, assetName: String): File? {
    if (!dir.isDirectory) return null
    if (assetName.isNotEmpty()) {
        val exact = File(dir, assetName)
        if (exact.isFile) return exact
    }
    return dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        ?.minByOrNull { it.name }
}

/** Removes leftover `*.part` files (killed downloads) under [root]; returns count. */
internal fun cleanupPartFiles(root: File): Int {
    val files = root.listFiles() ?: return 0
    var removed = 0
    files.forEach { entry ->
        if (entry.isDirectory) {
            removed += cleanupPartFiles(entry)
        } else if (entry.name.endsWith(".part") && entry.delete()) {
            removed++
        }
    }
    return removed
}

/**
 * Restart recovery: locate the previously downloaded APK for [record] and
 * re-verify its sha256 before trusting it — a truncated/corrupted file (or a
 * record for a version that is no longer newer, i.e. the update already got
 * installed) yields null so the caller drops the record and starts over.
 *
 * Pure apart from the filesystem: [updateDir] is `filesDir/update`, injectable
 * as any temp directory in tests.
 */
internal fun restoreReadyFrom(
    updateDir: File,
    record: DownloadedRecord,
    currentVersion: String,
): RestoredReady? {
    if (record.version.isEmpty() || record.sha256.isEmpty()) return null
    // Same/older than what is running → nothing pending (record is stale).
    // `-debug` is build-type decoration, not a prerelease: normalize exactly
    // like checkForUpdate does so a 2.1.0 APK isn't re-offered to 2.1.0-debug.
    if (!isNewer(record.version, currentVersion.removeSuffix("-debug"))) return null
    val file = findApkFile(File(updateDir, record.version), record.assetName) ?: return null
    if (!sha256File(file).equals(record.sha256, ignoreCase = true)) return null
    return RestoredReady(
        info = UpdateInfo(
            latestVersion = record.version,
            releaseUrl = record.releaseUrl,
            apk = UpdateAsset(
                name = record.assetName.ifEmpty { file.name },
                size = file.length(),
                downloadUrl = "",
                digest = null,
            ),
            apkSha256 = record.sha256.lowercase(),
        ),
        file = file,
    )
}

// ---------------------------------------------------------------- 流式下载（纯 JVM，单测用本地 HttpServer 驱动）

/** Outcome of [streamApk]. */
internal sealed class ApkDownloadResult {
    /** Downloaded, sha256-verified and atomically renamed into place. */
    data class Success(val file: File, val sha256: String) : ApkDownloadResult()

    /** [reason] is one of [UpdateFailures]; [transient] feeds the backoff stamp. */
    data class Failure(val reason: String, val detail: String, val transient: Boolean) : ApkDownloadResult()
}

/** Minimum gap between progress callbacks (the UI only needs ~5 updates/s). */
private const val PROGRESS_EMIT_MS = 200L

/**
 * Streaming download: `.part` → sha256 computed while writing → fsync →
 * atomic rename. Network/IO problems never throw — they fold into
 * [ApkDownloadResult.Failure]; cancellation propagates after the partial file
 * is removed (there is **no resume**: a partial file can never verify).
 *
 * [onProgress] is throttled to ~5 calls/s plus one final call carrying the
 * exact byte count. Pure JVM — exercised against a local HttpServer.
 */
internal fun streamApk(
    downloadUrl: String,
    partFile: File,
    finalFile: File,
    expectedSha256: String,
    totalSize: Long,
    onProgress: (Long) -> Unit = {},
): ApkDownloadResult {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true // GitHub → objects.githubusercontent.com
        val code = conn.responseCode
        if (code !in 200..299) {
            return ApkDownloadResult.Failure(UpdateFailures.HTTP, "HTTP $code", transient = false)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        FileOutputStream(partFile).use { out ->
            val input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            var lastEmit = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                bytes += n
                val now = System.currentTimeMillis()
                val finished = totalSize > 0 && bytes >= totalSize
                if (now - lastEmit >= PROGRESS_EMIT_MS || finished) {
                    lastEmit = now
                    onProgress(bytes)
                }
            }
            out.fd.sync() // fsync before the atomic rename (ImageDownloader style)
        }
        onProgress(bytes)

        if (totalSize > 0 && bytes != totalSize) {
            partFile.delete()
            return ApkDownloadResult.Failure(
                UpdateFailures.SHORT_READ,
                "connection ended at $bytes of $totalSize bytes",
                transient = true,
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            partFile.delete()
            return ApkDownloadResult.Failure(
                UpdateFailures.SHA_MISMATCH,
                "expected $expectedSha256, got $actual",
                transient = false,
            )
        }
        if (finalFile.exists() && !finalFile.delete()) {
            partFile.delete()
            return ApkDownloadResult.Failure(UpdateFailures.IO, "cannot replace ${finalFile.name}", transient = true)
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.delete()
            return ApkDownloadResult.Failure(UpdateFailures.IO, "rename .part -> ${finalFile.name} failed", transient = true)
        }
        ApkDownloadResult.Success(finalFile, actual)
    } catch (c: CancellationException) {
        runCatching { partFile.delete() }
        throw c
    } catch (e: Exception) {
        runCatching { partFile.delete() }
        ApkDownloadResult.Failure(
            reason = if (e is java.io.IOException) UpdateFailures.NETWORK else UpdateFailures.IO,
            detail = e.message ?: e.javaClass.simpleName,
            transient = e is java.io.IOException,
        )
    } finally {
        conn?.disconnect()
    }
}

// ---------------------------------------------------------------- 更新器

// DataStore keys for the persisted download record / failure backoff. Literal
// names are pinned by UpdateRepositoryTest (persistence is sacred: renaming a
// key without a migration orphans the stored APK record).
internal val updateDownloadedVersionKey = stringPreferencesKey("update_downloaded_version")
internal val updateDownloadedShaKey = stringPreferencesKey("update_downloaded_sha256")
internal val updateDownloadedUrlKey = stringPreferencesKey("update_downloaded_release_url")
internal val updateDownloadedAssetKey = stringPreferencesKey("update_downloaded_asset")
internal val updateDownloadFailureKey = longPreferencesKey("update_download_failure_stamp")

@Singleton
class ApkUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    // App-lifetime scope: a download must survive navigation / ViewModel death.
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /** Shared updater state (Settings + Home banner). */
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val downloading = AtomicBoolean(false)

    /** `filesDir/update/<version>/<asset>.part` → `<asset>` (atomic rename). */
    private val updateRoot: File get() = File(context.filesDir, UPDATE_DIR)

    /**
     * Re-verify a previously downloaded APK after a process restart. Local only
     * (no network): `.part` leftovers are swept, a matching record whose file
     * still hashes correctly goes straight to [UpdateState.Ready] so the user
     * is never asked to re-download an APK they already have.
     */
    suspend fun restore() = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading) return@withContext
        runCatching { cleanupPartFiles(updateRoot) }
            .onFailure { android.util.Log.w(TAG, "part cleanup failed", it) }

        val record = readRecord() ?: return@withContext

        // A newer update is already known (e.g. Settings forced a check first):
        // this record is stale — drop it rather than regressing the state.
        val known = _state.value.info
        if (known != null && known.latestVersion != record.version &&
            isNewer(known.latestVersion, record.version)
        ) {
            dropRecord(record)
            return@withContext
        }

        val restored = runCatching { restoreReadyFrom(updateRoot, record, BuildConfig.VERSION_NAME) }
            .getOrElse { e ->
                android.util.Log.w(TAG, "update restore failed", e)
                null
            }
        if (restored != null) {
            _state.value = UpdateState.Ready(restored.info, restored.file)
        } else {
            // Corrupt / already installed / gone: clear the record and files so
            // the next check starts from a clean slate instead of looping on a
            // file that can never verify.
            dropRecord(record)
        }
    }

    /**
     * Check for a new version (reusing [UpdateRepository.checkForUpdate], its
     * 24h cache gate and backoff) and move to Available/Ready/Idle.
     * [force] bypasses the cache gate (Settings 「立即检查」).
     *
     * When an update becomes available and「自动下载」is on (and the version was
     * not dismissed and no recent download failure is still backing off), the
     * download starts automatically without further UI involvement.
     */
    suspend fun check(force: Boolean = false): UpdateInfo? {
        if (_state.value !is UpdateState.Ready) restore()
        // Only enter Checking when nothing is known yet — an already-known
        // update (Available/Ready) stays on screen while the network check runs,
        // so the banner/status never flickers through a "Checking" blank.
        if (_state.value.info == null) _state.value = UpdateState.Checking

        val info = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME, force)
        if (info == null) {
            // Only fall back to Idle when nothing was known yet — a forced
            // re-check that errors out must not wipe an already-known update.
            if (_state.value is UpdateState.Checking || _state.value is UpdateState.Idle) {
                _state.value = UpdateState.Idle
            }
            return null
        }

        val ready = _state.value as? UpdateState.Ready
        if (ready != null && ready.info.latestVersion == info.latestVersion) {
            // Same version we already verified: refresh metadata (releaseUrl,
            // checksum source) but keep the file — no re-download.
            _state.value = ready.copy(info = info)
            return info
        }
        if (ready != null) {
            // Ready for an older release than the one just found — drop it.
            dropRecord(DownloadedRecord(ready.info.latestVersion, ready.info.apkSha256 ?: "",
                ready.info.releaseUrl, ready.info.apk?.name ?: ""))
            runCatching { File(updateRoot, ready.info.latestVersion).deleteRecursively() }
        }
        _state.value = UpdateState.Available(info)
        maybeAutoDownload(info)
        return info
    }

    /**
     * Download [info]'s APK. [bypassGate] = user-initiated tap (Settings
     * 「下载/重试」、Home 横幅按钮), which ignores the failure backoff; the
     * automatic path always obeys it.
     */
    fun download(info: UpdateInfo, bypassGate: Boolean = false): Job = appScope.launch {
        try {
            if (!bypassGate && !downloadGateOpen()) return@launch
            downloadNow(info)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            android.util.Log.w(TAG, "update download failed", e)
            fail(info, UpdateFailures.IO, recordBackoff = true, transient = e is java.io.IOException)
        }
    }

    /** Auto path: every preference/gate check is re-asserted here. */
    private suspend fun maybeAutoDownload(info: UpdateInfo) {
        if (!info.canAutoDownload) return
        if (!updateRepository.autoDownloadEnabled.first()) return
        // A dismissed version must not be fetched behind the user's back.
        if (updateRepository.isDismissed(info.latestVersion)) return
        if (!downloadGateOpen()) return
        download(info, bypassGate = false)
    }

    // ------------------------------------------------------------ 下载实现

    private suspend fun downloadNow(info: UpdateInfo) {
        if (!downloading.compareAndSet(false, true)) return // one download at a time
        try {
            val asset = info.apk
            if (asset == null) {
                fail(info, UpdateFailures.NO_APK, recordBackoff = false, transient = false)
                return
            }
            val expected = info.apkSha256
            if (expected == null) {
                fail(info, UpdateFailures.NO_CHECKSUM, recordBackoff = false, transient = false)
                return
            }

            // Fresh directory for this version: sweep leftovers of a killed run
            // (.part and any stale APK) so nothing old can be mistaken for done.
            val dir = File(updateRoot, info.latestVersion)
            runCatching { dir.deleteRecursively() }
            if (!dir.isDirectory && !dir.mkdirs()) {
                fail(info, UpdateFailures.IO, recordBackoff = false, transient = true)
                return
            }

            val usable = context.filesDir.usableSpace
            if (usable > 0 && usable < asset.size + STORAGE_MARGIN) {
                fail(info, UpdateFailures.STORAGE, recordBackoff = false, transient = false)
                return
            }

            val part = File(dir, "${asset.name}$PART_SUFFIX")
            val finalFile = File(dir, asset.name)
            _state.value = UpdateState.Downloading(info, 0L, asset.size)

            val result = streamApk(
                downloadUrl = asset.downloadUrl,
                partFile = part,
                finalFile = finalFile,
                expectedSha256 = expected,
                totalSize = asset.size,
            ) { bytes ->
                _state.value = UpdateState.Downloading(info, bytes, asset.size)
            }

            when (result) {
                is ApkDownloadResult.Success -> {
                    saveRecord(
                        DownloadedRecord(
                            version = info.latestVersion,
                            sha256 = expected,
                            releaseUrl = info.releaseUrl,
                            assetName = asset.name,
                        ),
                    )
                    // Success opens the gate again for the next version.
                    runCatching { context.dataStore.edit { it[updateDownloadFailureKey] = 0L } }
                    _state.value = UpdateState.Ready(info, result.file)
                }
                is ApkDownloadResult.Failure -> {
                    android.util.Log.w(TAG, "update download failed: ${result.reason} (${result.detail})")
                    fail(info, result.reason, recordBackoff = true, transient = result.transient)
                }
            }
        } finally {
            downloading.set(false)
        }
    }

    /** Failure transition (+ optional persisted backoff stamp, see file header). */
    private suspend fun fail(
        info: UpdateInfo?,
        reason: String,
        recordBackoff: Boolean,
        transient: Boolean,
    ) {
        if (recordBackoff) {
            val validity = 24 * 60 * 60 * 1000L
            val floor = 20 * 60 * 1000L
            val stamp = backoffStamp(System.currentTimeMillis(), transient, validity, floor)
            runCatching { context.dataStore.edit { it[updateDownloadFailureKey] = stamp } }
        }
        _state.value = UpdateState.Failed(info, reason)
    }

    /** True when no recent download failure is still backing off (24h/20min gate). */
    private suspend fun downloadGateOpen(): Boolean {
        val stamp = runCatching {
            context.dataStore.data
                .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
                .first()[updateDownloadFailureKey] ?: 0L
        }.getOrDefault(0L)
        val validity = 24 * 60 * 60 * 1000L
        return shouldCheck(System.currentTimeMillis(), stamp, validity)
    }

    // ------------------------------------------------------------ 安装 / 发布页

    /**
     * Hand the verified APK to the system installer (`ACTION_VIEW` +
     * `application/vnd.android.package-archive`). Android has no silent install
     * API for third-party apps, so this is always a user-confirmed step.
     * Falls back to the release page when no installer activity exists (or the
     * FileProvider URI cannot be built). Returns false when both failed.
     */
    fun installUpdate(file: File, releaseUrl: String): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            android.util.Log.w(TAG, "no installer activity for ${file.name}", e)
            openReleasePage(releaseUrl)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "install intent failed", e)
            openReleasePage(releaseUrl)
        }
    }

    /** Open the release page in the browser. Returns false when it could not be launched. */
    fun openReleasePage(releaseUrl: String): Boolean {
        if (releaseUrl.isBlank()) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cannot open release page $releaseUrl", e)
            false
        }
    }

    // ------------------------------------------------------------ DataStore 记录

    private suspend fun readRecord(): DownloadedRecord? {
        val p = context.dataStore.data
            .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
            .first()
        val version = p[updateDownloadedVersionKey]?.takeIf { it.isNotEmpty() } ?: return null
        val sha = p[updateDownloadedShaKey]?.takeIf { it.isNotEmpty() } ?: return null
        return DownloadedRecord(
            version = version,
            sha256 = sha,
            releaseUrl = p[updateDownloadedUrlKey] ?: "",
            assetName = p[updateDownloadedAssetKey] ?: "",
        )
    }

    private suspend fun saveRecord(record: DownloadedRecord) {
        context.dataStore.edit {
            it[updateDownloadedVersionKey] = record.version
            it[updateDownloadedShaKey] = record.sha256
            it[updateDownloadedUrlKey] = record.releaseUrl
            it[updateDownloadedAssetKey] = record.assetName
        }
    }

    /** Forget the downloaded APK (installed, corrupt or superseded) and delete it. */
    private suspend fun dropRecord(record: DownloadedRecord) {
        context.dataStore.edit {
            it.remove(updateDownloadedVersionKey)
            it.remove(updateDownloadedShaKey)
            it.remove(updateDownloadedUrlKey)
            it.remove(updateDownloadedAssetKey)
        }
        if (record.version.isNotEmpty()) {
            runCatching { File(updateRoot, record.version).deleteRecursively() }
        }
    }

    companion object {
        private const val TAG = "ApkUpdater"

        /** `filesDir/update/` — also mirrored by `res/xml/file_paths.xml` (`updates` root). */
        const val UPDATE_DIR = "update"

        /** Suffix of the in-flight download; never served to the installer. */
        const val PART_SUFFIX = ".part"

        /** Room for filesystem slack on top of the APK size (ImageDownloader uses 32 MiB). */
        const val STORAGE_MARGIN = 32L * 1024 * 1024
    }
}
