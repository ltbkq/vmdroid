/*
 * VMDroid - system image repository (fork of Podroid).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Resolves the currently ACTIVE system image — the read-only vdb disk the
 * engines attach (DESIGN §7.1/§7.2/§7.4). The APK no longer bundles a rootfs:
 * systems live under `filesDir/images/` and the active one is selected via
 * `filesDir/images/active.json`.
 *
 * v1 (M1 scope): activation lookup only. Install/verify/delete, `.meta.json`
 * verification and the full BootGuard checklist land in M2/M3 — until then a
 * missing or unreadable activation record simply means "no image", which the
 * engines turn into a fail-fast NO_SYSTEM_IMAGE instead of a guest that boots
 * and then hangs mounting vdb (DESIGN §7.1/§7.4, R-15).
 */
package io.github.ltbkq.vmdroid.systemimage

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read side of the image-activation record.
 *
 * `active.json` is written by the image manager (DESIGN §2.3/§5.3 — fields
 * `{image_id, identity, rootfs_sha256, activated_at}`; this reader is also
 * tolerant of an explicit `path` / `sha256` spelling so early writers and the
 * spec sketch in §7.1 both work). Only `image_id` is required; the file path
 * defaults to `images/<image_id>.img` under `filesDir`.
 *
 * Constructor-injected by Hilt (no module needed) so both engines can take it
 * as a plain constructor dependency — `EngineModule`/`EngineHolder` are
 * untouched (DESIGN §3: engine routing kept as-is).
 */
@Singleton
class SystemImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Private data class: one parsed `active.json` record. */
    private data class ActiveImage(val imageId: String, val imageFile: File)

    /**
     * Absolute path of the active system image file, or `null` when there is
     * no activation record, the record is unusable, or the referenced file is
     * gone. Never returns a path that does not exist — callers rely on that to
     * fail fast rather than launching a VM with no vdb.
     */
    fun activeRootfsPath(): File? {
        val active = active() ?: return null
        if (!active.imageFile.isFile) {
            Log.w(
                TAG,
                "NO_SYSTEM_IMAGE: active image '${active.imageId}' points at a missing file " +
                    "(${active.imageFile.absolutePath})"
            )
            return null
        }
        return active.imageFile
    }

    /** `image_id` of the active system image, or `null` when none is active. */
    fun activeImageId(): String? = active()?.imageId

    /**
     * Parses `filesDir/images/active.json`. Any failure (absent file, malformed
     * JSON, unusable `image_id`) logs and returns null — "no active image".
     */
    private fun active(): ActiveImage? {
        val record = File(File(context.filesDir, IMAGES_DIR), ACTIVE_FILE)
        if (!record.isFile) return null // first run / no image installed yet
        // Plain try/catch rather than runCatching {}.getOrElse { return null }:
        // the non-local return would infer getOrElse's type parameter as Nothing.
        val text = try {
            record.readText()
        } catch (e: Exception) {
            Log.w(TAG, "NO_SYSTEM_IMAGE: unreadable activation record $record", e)
            return null
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "NO_SYSTEM_IMAGE: malformed activation record $record", e)
            return null
        }
        val imageId = json.optString(IMAGE_ID_FIELD, "")
        if (imageId.isEmpty()) {
            Log.w(TAG, "NO_SYSTEM_IMAGE: $record has no image_id")
            return null
        }
        // Reject anything that is not a plain image id before it can shape a
        // path (DESIGN §7.4 IMAGE_ID_INVALID: `^[a-z0-9][a-z0-9._-]{0,63}$`,
        // also blocks path traversal like `../../`).
        if (!IMAGE_ID_PATTERN.matches(imageId)) {
            Log.w(TAG, "NO_SYSTEM_IMAGE: rejecting malformed image_id '$imageId' in $record")
            return null
        }
        val rawPath = json.optString(PATH_FIELD, "").ifEmpty { "$IMAGES_DIR/$imageId.img" }
        val imageFile = if (rawPath.startsWith("/")) File(rawPath) else File(context.filesDir, rawPath)
        return ActiveImage(imageId, imageFile)
    }

    companion object {
        private const val TAG = "SystemImageRepo"
        const val IMAGES_DIR = "images"
        const val ACTIVE_FILE = "active.json"
        private const val IMAGE_ID_FIELD = "image_id"
        private const val PATH_FIELD = "path"
        private val IMAGE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    }
}
