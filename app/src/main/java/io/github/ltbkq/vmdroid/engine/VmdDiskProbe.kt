/*
 * VMDroid — v2 disk-image quick probe (M3 dual-backend switch).
 *
 * Answers one question as cheaply as possible: "is the ACTIVE system image a
 * v2 full-disk image (GPT + meta partition), or a v1 image?" Both engines need
 * the answer BEFORE building their launch config:
 *
 *   QEMU   v2 → UEFI direct boot: `-bios qemu/QEMU_EFI.fd`, no -kernel/-initrd/
 *                -append, vdb writable + discard (DESIGN-disk-image-v2 §3.1)
 *          v1 → the existing direct kernel boot path (migration-period coexistence)
 *   AVF    v2 → plan B: extract /vmlinuz + /initrd.img from the ESP and boot
 *                them directly via setKernelPath/setInitrdPath (§3.2)
 *          v1 → filesDir/vmlinuz-virt extracted from the v1 footer
 *
 * This is the FAST PATH, not the authoritative parser:
 * `VmdDiskCodec.read()` (IMAGE-FORMAT-v2 §5, stages 1–3) is the spec-authoritative
 * reader and is what the engines call when they actually need manifest/GPT
 * semantics. It validates header CRCs, backup tables, meta dual magic, manifest
 * digest and all semantic rules — too much work for a boot-path branch test
 * (it would also throw on a merely-odd image, forcing error handling where the
 * caller only wants a boolean).
 *
 * The probe instead reads exactly three 512-byte sectors:
 *   LBA0  protective MBR  (0x55AA @510, 0xEE @450)
 *   LBA1  GPT header      ("EFI PART", entries_lba @72, entry_size @84)
 *   LBA<entries_lba> 512B covering the first four 128-byte entries — v2 fixes
 *                   meta at p2 (IMAGE-FORMAT-v2 §2.1), so its unique GUID must
 *                   appear there (mixed-endian form of the fixed constant
 *                   `VmdDiskCodec.META_PARTITION_GUID`).
 *
 * A `false` result is "not a v2 image" (v1, bare squashfs, garbage, IO error) —
 * callers then take the v1 path, exactly like a v1 image. A false negative on a
 * *corrupt* v2 image only costs a failed v1 boot (there is no v1 payload to
 * extract from a v2 image), never a wrong-disk mount: the guest-side initramfs
 * re-verifies `vmd.root_partuuid` against the GPT before mounting.
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型（可脱离 Android SDK 用 kotlinc 编译）。
 */
package io.github.ltbkq.vmdroid.engine

import io.github.ltbkq.vmdroid.systemimage.VmdDiskCodec
import java.io.File
import java.io.RandomAccessFile

object VmdDiskProbe {

    /** GPT entry size (IMAGE-FORMAT-v2 §2.2, frozen at 128 B). */
    private const val ENTRY_SIZE = 128

    /** Entries covered by the single entries-LBA sector we read (512 / 128). */
    private const val SCANNED_ENTRIES = 4

    /**
     * Cheap "is this a v2 full-disk image?" test. Never throws; any IO problem
     * or structural surprise yields `false` (→ the caller's v1 path).
     */
    @JvmStatic
    fun isV2Image(file: File): Boolean = try {
        if (!file.isFile || !file.canRead() || file.length() < VmdDiskCodec.MIN_FILE_SIZE) {
            false
        } else {
            RandomAccessFile(file, "r").use { raf -> probe(raf, file.length()) }
        }
    } catch (_: Exception) {
        false
    }

    private fun probe(raf: RandomAccessFile, size: Long): Boolean {
        val sector = VmdDiskCodec.SECTOR_SIZE.toLong()
        if (size < 3 * sector) return false

        // LBA0 — protective MBR (same acceptance test as VmdDiskCodec.hasProtectiveMbr)
        val mbr = readSector(raf, 0L) ?: return false
        if (mbr[510] != 0x55.toByte() || mbr[511] != 0xAA.toByte()) return false
        if (mbr[450] != 0xEE.toByte()) return false

        // LBA1 — GPT header signature + geometry we need to find the entries
        val hdr = readSector(raf, sector) ?: return false
        if (String(hdr, 0, 8, Charsets.US_ASCII) != "EFI PART") return false
        if (leU32(hdr, 84) != ENTRY_SIZE.toLong()) return false
        val entriesLba = leU64(hdr, 72)
        // entries live at LBA2 in a v2 image; accept any in-bounds LBA and let
        // the GUID comparison below decide (this probe is not authoritative).
        if (entriesLba < 2 || entriesLba >= size / sector) return false

        // entries LBA — first four entries, scan their unique GUIDs for meta's
        val entries = readSector(raf, entriesLba * sector) ?: return false
        val metaGuid = VmdDiskCodec.guidToBytes(VmdDiskCodec.META_PARTITION_GUID) ?: return false
        for (i in 0 until SCANNED_ENTRIES) {
            val off = i * ENTRY_SIZE + 16 // unique GUID = entry + 16, 16 bytes
            var hit = true
            for (b in 0 until 16) {
                if (entries[off + b] != metaGuid[b]) { hit = false; break }
            }
            if (hit) return true
        }
        return false
    }

    private fun readSector(raf: RandomAccessFile, offset: Long): ByteArray? {
        if (offset < 0) return null
        return try {
            raf.seek(offset)
            val buf = ByteArray(VmdDiskCodec.SECTOR_SIZE)
            var read = 0
            while (read < buf.size) {
                val n = raf.read(buf, read, buf.size - read)
                if (n < 0) return null
                read += n
            }
            buf
        } catch (_: Exception) {
            null
        }
    }

    private fun leU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun leU64(b: ByteArray, off: Int): Long =
        leU32(b, off) or (leU32(b, off + 4) shl 32)
}
