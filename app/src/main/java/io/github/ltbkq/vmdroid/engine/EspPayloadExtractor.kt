/*
 * EspPayloadExtractor — AVF plan-B boot payloads from the v2 image ESP.
 *
 * DESIGN-disk-image-v2 §3.2 方案 B（本期必选实现）：AVF has no firmware/pflash
 * channel, so instead of UEFI→GRUB the app reads the kernel + initrd straight
 * out of the image's ESP (FAT32, IMAGE-FORMAT-v2 §2.3) and hands them to
 * `AvfReflect.setKernelPath/setInitrdPath`. The kernel still comes FROM THE
 * IMAGE (not from a bundled APK asset) — this is not a fallback to v1.
 *
 * Outputs (under `filesDir/qemu-esp/`):
 *   vmlinuz       ESP `/vmlinuz` — already an uncompressed arm64 `Image` (§2.3 M-a),
 *                 so AvfEngine.ensureRawKernel() passes it through untouched.
 *   initrd.img    ESP `/initrd.img` (gzip cpio, init-podroid v2).
 *   extract.meta  cache record: source image path + content_id + the manifest's
 *                 `checksums.kernel_sha256`/`initrd_sha256`. Written LAST, so a
 *                 half-finished extraction never reads as a cache hit.
 *
 * Cache policy (deliberately small): reuse the extracted files when they exist
 * and the recorded source still matches this image (path + content_id + both
 * shas when the manifest carries them). Any mismatch → re-extract. sha256 of
 * every extraction is verified against the manifest before the bytes are
 * renamed into place (R6: `checksums.kernel_sha256` = ESP `/vmlinuz` actual
 * bytes), so a torn/corrupt ESP read never reaches AVF.
 *
 * Writes are atomic (`<name>.tmp` → rename) because a process kill mid-copy
 * must not leave a truncated kernel that the next boot would happily feed to
 * crosvm.
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型（可脱离 Android SDK 用 kotlinc 编译）。
 */
package io.github.ltbkq.vmdroid.engine

import io.github.ltbkq.vmdroid.systemimage.VmdDiskCodec
import java.io.File
import java.io.IOException

object EspPayloadExtractor {

    /** Directory name under `filesDir` (AvfEngine passes the resolved dir). */
    const val DIR_NAME = "qemu-esp"

    private const val META_NAME = "extract.meta"
    private const val KERNEL_SRC = "/vmlinuz"
    private const val INITRD_SRC = "/initrd.img"
    private const val KERNEL_DST = "vmlinuz"
    private const val INITRD_DST = "initrd.img"
    private const val NONE = "-"

    /** Extracted (or cache-reused) boot payloads ready for setKernelPath/setInitrdPath. */
    data class Payload(val kernel: File, val initrd: File)

    /**
     * Ensure `destDir` holds this image's `/vmlinuz` + `/initrd.img`.
     *
     * @param info a `VmdDiskCodec.read()` result for the active image (GPT +
     *             manifest already validated — the caller needs it anyway for
     *             `disk.root.partuuid`, see init-podroid-v2's FATAL check).
     * @throws IOException ESP file missing, sha256 mismatch, or write failure.
     * @throws io.github.ltbkq.vmdroid.systemimage.VmdDiskException
     *             ESP/FAT32 structure corrupt (reason=CORRUPT) — propagates as-is.
     */
    @JvmStatic
    fun extract(info: VmdDiskCodec.ImageInfo, destDir: File): Payload {
        val kernel = File(destDir, KERNEL_DST)
        val initrd = File(destDir, INITRD_DST)
        val expectedKernelSha = shaOrNull(info, "kernel_sha256")
        val expectedInitrdSha = shaOrNull(info, "initrd_sha256")
        val contentId = info.manifest.image.contentId?.trim()

        if (kernel.isFile && initrd.isFile &&
            cacheValid(File(destDir, META_NAME), info.path, contentId, expectedKernelSha, expectedInitrdSha)
        ) {
            return Payload(kernel, initrd)
        }

        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("cannot create ${destDir.absolutePath}")
        }

        val kernelBytes = VmdDiskCodec.readEspFile(info, KERNEL_SRC)
            ?: throw IOException("ESP 内缺 $KERNEL_SRC —— 不是可用的 v2 系统镜像 (${info.path})")
        val initrdBytes = VmdDiskCodec.readEspFile(info, INITRD_SRC)
            ?: throw IOException("ESP 内缺 $INITRD_SRC —— 不是可用的 v2 系统镜像 (${info.path})")

        verifySha(kernelBytes, expectedKernelSha, KERNEL_SRC)
        verifySha(initrdBytes, expectedInitrdSha, INITRD_SRC)

        // Kernel/initrd first, meta last: an interrupted extraction is detected
        // (no valid .meta) and redone on the next start instead of reused.
        writeAtomic(kernel, kernelBytes)
        writeAtomic(initrd, initrdBytes)
        writeAtomic(
            File(destDir, META_NAME),
            buildString {
                appendLine("image=${info.path}")
                appendLine("content_id=${contentId ?: NONE}")
                appendLine("kernel_sha256=${expectedKernelSha ?: NONE}")
                append("initrd_sha256=${expectedInitrdSha ?: NONE}")
            }.toByteArray(Charsets.UTF_8),
        )
        return Payload(kernel, initrd)
    }

    // ------------------------------------------------------------------ cache

    private fun cacheValid(
        meta: File,
        imagePath: String,
        contentId: String?,
        kernelSha: String?,
        initrdSha: String?,
    ): Boolean {
        val recorded = runCatching { parseMeta(meta) }.getOrNull() ?: return false
        if (recorded["image"] != imagePath) return false
        // Every value the current image publishes must match what was recorded
        // at extraction time; keys the manifest doesn't carry are not constraining.
        if (contentId != null && recorded["content_id"] != contentId) return false
        if (kernelSha != null && recorded["kernel_sha256"] != kernelSha) return false
        if (initrdSha != null && recorded["initrd_sha256"] != initrdSha) return false
        return true
    }

    private fun parseMeta(meta: File): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in meta.readLines(Charsets.UTF_8)) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            out[line.substring(0, idx)] = line.substring(idx + 1)
        }
        return out
    }

    // ------------------------------------------------------------------ io

    private fun shaOrNull(info: VmdDiskCodec.ImageInfo, key: String): String? =
        info.manifest.checksums[key]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != NONE }

    private fun verifySha(bytes: ByteArray, expected: String?, what: String) {
        if (expected == null) return
        val actual = VmdDiskCodec.sha256(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IOException(
                "ESP $what sha256 mismatch (manifest=$expected actual=$actual) — image corrupt?",
            )
        }
    }

    private fun writeAtomic(dst: File, bytes: ByteArray) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(dst)) {
                // rename over an existing file can fail on some filesystems
                dst.delete()
                if (!tmp.renameTo(dst)) {
                    throw IOException("cannot replace ${dst.absolutePath}")
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
