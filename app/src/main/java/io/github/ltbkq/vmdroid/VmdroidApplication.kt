/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Application class — extracts QEMU, kernel, and initrd assets on first run
 * (and on app upgrade when the install-time stamp drifts).
 */
package io.github.ltbkq.vmdroid

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class VmdroidApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Completion signal for asset extraction. The VM launch path
    // (VmdroidService.launchVmdroid) reads the extracted files synchronously,
    // so it MUST await this before starting the engine — see awaitAssetsReady.
    // Completed (never failed) in extractAssets' finally so a waiter can never
    // hang even if extraction throws; intactness is enforced by the size-check
    // in QemuEngine/AvfEngine's own asset reads, not by this signal.
    private val assetsReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        exemptHiddenApi()
        // Extract off the main thread: the firmware payload alone is tens of MB
        // and blocking onCreate on first install/upgrade would ANR the cold start.
        appScope.launch { extractAssets() }
    }

    /**
     * Suspends until the bundled assets (qemu/, kernel, initrd) have
     * finished extracting to [filesDir]. The foreground service awaits this
     * before launching the VM so QEMU/AVF never read a partial or missing file.
     * The system image is NOT bundled — see SystemImageRepository.
     */
    suspend fun awaitAssetsReady() = assetsReady.await()

    // Android 14+ hides @SystemApi reflection lookups (returning NoSuchMethod
    // even via getDeclared*). Prefixes needing exemption:
    //   - Landroid/system/virtualmachine/ — AVF framework (AvfDiagnostics + AvfEngine)
    //   - Landroid/system/virtualizationservice/ — AVF AIDL parcelables
    //     (CpuOptions, VirtualMachineRawConfig, IVirtualizationService) used by
    //     AvfReflect's explicit-vCPU-count hook (issue #29).
    //   - Ljava/net/UnixDomainSocketAddress — ConsoleFanout needs UDS.of(String)
    //     which Android marks BLOCKED for untrusted_app even though the class
    //     itself is on the bootclasspath.
    // No-op on sub-P; the exemption itself never throws.
    private fun exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/system/virtualmachine/",
                "Landroid/system/virtualizationservice/",
                "Landroid/system/UnixSocketAddress",
                "Ljava/net/UnixDomainSocketAddress",
            )
        }.onFailure { Log.w(TAG, "HiddenApiBypass exemption failed", it) }
    }

    private fun extractAssets() {
        try {
            // Asset extraction has a self-healing version stamp: on every install
            // or upgrade `packageInfo.lastUpdateTime` changes, so we record it in
            // `.assets_stamp` and force a re-copy on mismatch. Pure size checks
            // are deceiving: a same-size-but-different-content firmware file
            // would silently pass a size-only check and boot stale content.
            val stampFile = File(filesDir, ".assets_stamp")
            val currentStamp = runCatching {
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            }.getOrDefault(0L).toString()
            val previousStamp = runCatching { stampFile.readText() }.getOrDefault("")
            val forceCopy = previousStamp != currentStamp
            if (forceCopy) {
                Log.i(TAG, "asset stamp drift ($previousStamp → $currentStamp) — forcing re-extract")
            }

            // Drop any .tmp files left by a process killed mid-copy so they
            // can't accumulate or shadow a fresh atomic write.
            deleteStaleTmpFiles(filesDir)

            // Fan out the three top-level extractions across a small thread pool.
            // Disk-write throughput is the bottleneck (the firmware payload),
            // but asset-FD lookup and the skip-when-already-complete check
            // overlap usefully across threads. Runs on a background coroutine
            // (not the main thread); the VM launch path awaits awaitAssetsReady.
            //
            // The system image (rootfs) is NO LONGER an APK asset: it lives in
            // filesDir/images/ and is resolved through SystemImageRepository
            // (DESIGN §7.3 — 3 asset tasks, rootfs extraction removed).
            val tasks: List<() -> Unit> = listOf(
                { copyAssetDir("qemu", filesDir, forceCopy) },
                { copyAssetIfNeeded("vmlinuz-virt", File(filesDir, "vmlinuz-virt"), forceCopy) },
                { copyAssetIfNeeded("initrd.img", File(filesDir, "initrd.img"), forceCopy) },
            )
            val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(4))
            var allSucceeded = true
            try {
                // invokeAll blocks until every Callable finishes (or times out).
                // Each Callable wraps the task so a thrown exception is captured
                // in the returned Future rather than killing the worker silently.
                val futures = pool.invokeAll(tasks.map { task ->
                    java.util.concurrent.Callable<Unit> { task() }
                })
                for (f in futures) {
                    try { f.get() } catch (e: Exception) {
                        // copyAssetIfNeeded already logs its own failures and
                        // rethrows; this catches the propagated exception so
                        // one failed asset doesn't stop the others.
                        Log.w(TAG, "Asset extraction task failed", e)
                        allSucceeded = false
                    }
                }
            } finally {
                pool.shutdown()
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow()
                    allSucceeded = false
                }
            }

            // Commit the new stamp ONLY if every extraction task succeeded.
            // Writing it after a failed copy (e.g. the initrd copy failed on an
            // upgrade: disk full, killed mid-copy) would mark the OLD file as
            // current, so stale firmware would boot forever. On failure we leave
            // the stamp stale so the next launch re-extracts.
            if (allSucceeded) {
                runCatching { stampFile.writeText(currentStamp) }
                    .onFailure { Log.w(TAG, "Failed to write assets stamp", it) }
            } else {
                Log.w(TAG, "asset extraction incomplete — leaving stamp stale to force re-extract next launch")
            }
        } finally {
            // Always release waiters — a failed/partial extract is detected by
            // the per-file size-check on the next read, not by hanging here.
            assetsReady.complete(Unit)
        }
    }

    /** Recursively removes leftover `<name>.tmp` files under [dir]. */
    private fun deleteStaleTmpFiles(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                deleteStaleTmpFiles(child)
            } else if (child.name.endsWith(TMP_SUFFIX)) {
                runCatching { child.delete() }
            }
        }
    }

    /**
     * Copies an asset to destFile unless it's already complete: destFile
     * exists, forceCopy (install-stamp drift) is false, and either the size
     * is unknown (assets.openFd() throws for the compressed assets shipped
     * here: initrd/kernel/qemu, so this is the common case) or it
     * matches. Copies are atomic (tmp + fsync + rename), so an existing
     * destination file is always complete, never partial. A stamp mismatch
     * on upgrade is what forces a re-copy of same-size-but-different-content
     * files (size-only checks would silently keep a stale copy and the VM
     * would boot stale firmware).
     * Exceptions from the copy propagate to the caller so the stamp is not
     * committed after a failed extraction.
     */
    private fun copyAssetIfNeeded(assetPath: String, destFile: File, forceCopy: Boolean) {
        val assetSize = try { assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
        if (!forceCopy && destFile.exists() && (assetSize < 0 || destFile.length() == assetSize)) return

        destFile.parentFile?.mkdirs()
        try {
            copyAssetAtomically(assetPath, destFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetPath", e)
            throw e
        }
    }

    /**
     * Walks an asset directory tree and mirrors it under destDir, delegating
     * each file to [copyAssetIfNeeded]. Exceptions from a file copy propagate
     * up through this walk to the caller.
     */
    private fun copyAssetDir(assetPath: String, destDir: File, forceCopy: Boolean) {
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = assets.list(src)
            if (subEntries != null && subEntries.isNotEmpty()) {
                dest.mkdirs()
                copyAssetDir(src, dest, forceCopy)
            } else {
                copyAssetIfNeeded(src, dest, forceCopy)
            }
        }
    }

    /**
     * Streams [assetPath] to `<destFile>.tmp`, fsyncs the data to disk, then
     * atomically renames it onto [destFile]. The final canonical path therefore
     * only ever holds a fully-written file — an async reader (the VM launch)
     * never sees a half-written initrd/kernel. Throws on any failure so the
     * caller logs it and the stale/missing file is caught by the next size-check.
     */
    private fun copyAssetAtomically(assetPath: String, destFile: File) {
        val tmpFile = File(destFile.parentFile, destFile.name + TMP_SUFFIX)
        try {
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!tmpFile.renameTo(destFile)) {
                throw java.io.IOException("atomic rename ${tmpFile.name} -> ${destFile.name} failed")
            }
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            throw e
        }
    }

    companion object {
        private const val TAG = "VmdroidApp"
        private const val TMP_SUFFIX = ".tmp"
    }
}
