/*
 * EspFat32 — FAT32 最小只读解析器（IMAGE-FORMAT-v2 §2.3 ESP / DESIGN-disk-image-v2 §3.2 方案 B）。
 *
 * 用途：
 *   - AVF 回退方案 B：无固件能力时从 ESP 提取 /vmlinuz、/initrd.img 直启
 *     （内核来源仍是镜像内部，不是回退 v1 —— 设计文档 §3.2）
 *   - 诊断：列 ESP 目录、查看 grub.cfg / manifest 冗余副本
 *
 * 范围（刻意最小化，"够用即可"）：
 *   - 只读；不写、不改 FAT、不维护 FSInfo、不跟随备份 boot 扇区
 *   - **唯一例外（R8 受控写扩展）**：[overwriteFileData] —— 定长覆盖既有文件的数据区字节，
 *     供 IMAGE-FORMAT-v2 §2.4 R8 的 `/grub/user.cfg` 运行时参数通道使用（停机期调用）。
 *     它**只碰数据区**，目录项 / 文件大小 / FAT 簇链一律不动，语义边界见其 KDoc。
 *   - FAT32 only（BPB fatsz16==0 且 root_entr_cnt==0；ESP 由 mkfs.vfat -C -F 32 生成）
 *   - 8.3 短名 + LFN 长名（含 checksum 校验与 seq 连续性检查）；名字比对大小写不敏感
 *   - 目录遍历、文件读取（簇链跟随，含成环/越界/过短保护）
 *
 * 错误语义：结构损坏 / IO 失败抛 [VmdDiskException]（reason=CORRUPT，"esp:" 前缀）；
 * 路径不存在/是目录 → 返回 null（调用方自行决定 —— ESP 内容缺文件不是读取期拒绝理由，
 * §9 向量 23：ESP 破坏读取方 MUST NOT 拒绝整盘）。
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型（与 VmdDiskCodec.kt 同批 kotlinc 自测）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 打开方式：[EspFat32.open(file, partOffset, partSize)] —— [partOffset] 为 ESP 分区在镜像内
 * 的绝对字节偏移（GPT p1 first_lba × 512），[partSize] 为分区字节数。
 * [use] 结束自动关闭底层文件。
 */
class EspFat32 private constructor(
    private val raf: RandomAccessFile,
    /** 镜像文件（只读句柄 [raf] 之外的引用；[overwriteFileData] 按需另开 rw 句柄）。 */
    private val file: File,
    /** ESP 分区在镜像文件内的绝对偏移。 */
    val partOffset: Long,
    /** ESP 分区字节数。 */
    val partSize: Long,
    private val boot: Boot,
) : Closeable {

    /** FAT 卷 ID（BPB @67，u32 LE —— FAT32 扩展 BPB；@71 起是 11 B 卷标 "NO NAME"）；文本形如 "9c17eb36"（小写 hex，对应 manifest disk.esp.fat_uuid）。 */
    val volumeIdHex: String get() = String.format("%08x", boot.volumeId)

    /** 目录项（诊断输出）。 */
    data class Entry(
        /** 文件名（LFN 优先，缺 LFN 时用 8.3 短名；大小写按盘上原样保留）。 */
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val firstCluster: Long,
    )

    // ------------------------------------------------------------------
    // BPB（引导扇区）解析
    // ------------------------------------------------------------------

    /** FAT32 BPB 派生量（[open] 时一次性校验）。 */
    private data class Boot(
        val bytesPerSec: Int,
        val secPerClus: Int,
        val reservedSec: Int,
        val numFats: Int,
        val fatSzSec: Long,
        val totalSec: Long,
        val rootClus: Long,
        val volumeId: Long,
    ) {
        /** FAT 区起始（分区相对字节）。 */
        val fatOffset: Long get() = Math.multiplyExact(reservedSec.toLong(), bytesPerSec.toLong())
        /** FAT#1 字节数。 */
        val fatBytes: Long get() = Math.multiplyExact(fatSzSec, bytesPerSec.toLong())
        /** 数据区起始（分区相对字节）。 */
        val dataOffset: Long get() =
            Math.addExact(fatOffset, Math.multiplyExact(fatBytes, numFats.toLong()))
        /** 每簇字节数。 */
        val clusBytes: Int get() = secPerClus * bytesPerSec
        /** 数据区簇总数。 */
        val clusCount: Long get() {
            val fatArea = Math.multiplyExact(fatSzSec, numFats.toLong())
            val dataSec = totalSec - reservedSec - fatArea
            return dataSec / secPerClus
        }
    }

    companion object {
        private const val LFN_ATTR = 0x0F
        private const val ATTR_VOLUME_ID = 0x08
        private const val ATTR_DIRECTORY = 0x10
        private const val EOC_MIN = 0x0FFFFFF8L
        private const val CLUS_MASK = 0x0FFFFFFFL
        /** 目录链读取上限（ESP 目录极小；防御恶意/损坏镜像撑爆内存）。 */
        private const val MAX_DIR_BYTES = 16L * 1024 * 1024
        /** 单文件读取上限（内核 ~70MB / initrd ~50MB；防御越界声明）。 */
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024

        /**
         * 打开镜像内的 ESP 分区并校验 BPB（FAT32 标记、几何、签名）。
         *
         * @throws VmdDiskException [VmdDiskReason.CORRUPT] 非 FAT32 / 几何非法 / 越界 / IO 失败。
         */
        @JvmStatic
        fun open(file: File, partOffset: Long, partSize: Long): EspFat32 {
            fun corrupt(msg: String, cause: Throwable? = null): Nothing =
                throw VmdDiskException.of("esp: $msg", VmdDiskReason.CORRUPT, cause)
            if (partOffset < 0 || partSize < 512L) {
                corrupt("分区范围非法 [$partOffset, +$partSize)")
            }
            val raf = try {
                RandomAccessFile(file, "r")
            } catch (e: IOException) {
                corrupt("无法打开镜像 $file: ${e.message}", e)
            }
            try {
                val bpb = ByteArray(512)
                raf.seek(partOffset)
                raf.readFully(bpb)
                if (bpb[510] != 0x55.toByte() || bpb[511] != 0xAA.toByte()) {
                    corrupt("引导扇区缺 0x55AA 签名")
                }
                fun u16(off: Int): Int = (bpb[off].toInt() and 0xFF) or ((bpb[off + 1].toInt() and 0xFF) shl 8)
                fun u32(off: Int): Long {
                    var v = 0L
                    for (k in 3 downTo 0) v = (v shl 8) or (bpb[off + k].toLong() and 0xFF)
                    return v
                }
                val bps = u16(11)
                if (bps !in intArrayOf(512, 1024, 2048, 4096)) {
                    corrupt("非法每扇区字节数 $bps")
                }
                val spc = bpb[13].toInt() and 0xFF
                if (spc == 0 || spc and (spc - 1) != 0 || spc > 128) {
                    corrupt("非法每簇扇区数 $spc（须为 2 的幂且 ≤128）")
                }
                val rsvd = u16(14)
                val numFats = bpb[16].toInt() and 0xFF
                if (rsvd == 0 || numFats !in 1..2) {
                    corrupt("非法 BPB 保留扇区=$rsvd FAT 数=$numFats")
                }
                // FAT32 标记：FATSZ16==0 且 ROOT_ENT_CNT==0（否则为 FAT12/16 —— 非 ESP）
                if (u16(22) != 0 || u16(17) != 0) {
                    corrupt("非 FAT32（FATSZ16=${u16(22)} ROOT_ENT_CNT=${u16(17)}）")
                }
                val fatSz = u32(36)
                val totalSec = if (u16(19) != 0) u16(19).toLong() else u32(32)
                val rootClus = u32(44)
                if (fatSz == 0L || totalSec == 0L) {
                    corrupt("非法 FAT32 几何 fatSz32=$fatSz totSec32=$totalSec")
                }
                if (rootClus < 2L) {
                    corrupt("非法 root 簇号 $rootClus")
                }
                // 声明的总扇区数不得超过分区实际大小（越界防御）
                if (Math.multiplyExact(totalSec, bps.toLong()) > partSize) {
                    corrupt("BPB totSec=$totalSec 超出分区大小 $partSize")
                }
                val boot = Boot(
                    bytesPerSec = bps,
                    secPerClus = spc,
                    reservedSec = rsvd,
                    numFats = numFats,
                    fatSzSec = fatSz,
                    totalSec = totalSec,
                    rootClus = rootClus,
                    volumeId = u32(67),
                )
                if (boot.clusCount < 1) corrupt("数据区簇数=${boot.clusCount} < 1")
                return EspFat32(raf, file, partOffset, partSize, boot)
            } catch (e: VmdDiskException) {
                raf.close()
                throw e
            } catch (e: IOException) {
                raf.close()
                corrupt("读取引导扇区失败: ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外 API：列目录 / 读文件
    // ------------------------------------------------------------------

    /**
     * 列目录（`/` 为根；跳过 "."/".." 与卷标与已删除项）。
     *
     * @return 目录项列表；[path] 不存在或不是目录 → null。
     * @throws VmdDiskException [VmdDiskReason.CORRUPT] 目录簇链损坏。
     */
    fun list(path: String): List<Entry>? {
        val e = lookup(path) ?: return null
        if (!e.isDirectory) return null
        return readDir(e.firstCluster)
    }

    /**
     * 读整个文件到内存（AVF 回退直启场景：kernel ≤ 512 MiB 上限内）。
     *
     * @return 文件内容（size=0 时为空数组）；[path] 不存在或是目录 → null。
     * @throws VmdDiskException [VmdDiskReason.CORRUPT] 簇链成环/越界/过短、声明大小超限、IO 失败。
     */
    fun readFile(path: String): ByteArray? {
        val e = lookup(path) ?: return null
        if (e.isDirectory) return null
        return readChain(startCluster = e.firstCluster, want = e.size, what = "file '$path'")
    }

    /**
     * **受控写扩展（R8）**：把 [path] 的数据区前 `bytes.size` 个字节原地覆写为 [bytes]
     * （IMAGE-FORMAT-v2 §2.4 R8-3 "定长覆盖写"；调用方为 `EspUserCfgWriter.write`）。
     *
     * 本类本是只读解析器 —— 该方法是为运行时参数通道加的**唯一**写入口，语义边界（越界即错）：
     *
     *  - **只覆写既有簇链覆盖到的数据字节**；目录项、文件大小、FAT 簇链、卷引导区**一律不改**
     *    （文件必须由构建期预置为定长且簇已分配 —— 预置是本 API 的前提，不做扩容/创建）。
     *  - `bytes.size > 文件原大小` 或 `> [maxLen]` → [IllegalArgumentException]（调用方误用，拒绝）。
     *  - [path] 不存在 / 是目录 → 返回 `false`，**绝不创建新文件**（与 [readFile] 的 null 对齐）。
     *  - 簇链过短 / 坏簇 / 空闲簇 / 越界 / 成环 → [VmdDiskException]（[VmdDiskReason.CORRUPT]，
     *    与读路径同一套防护）—— 且**先整链校验再落盘**：链不合法时不会写出任何一个字节。
     *  - 写完 `fsync`（停机期写入的持久性要求）；IO 失败 → [VmdDiskException]（CORRUPT，
     *    消息标注已写字节数 —— 此时**可能已有部分写**，调用方须写前备份并负责回滚）。
     *
     * 风险边界：**仅限 VM 停机期调用**（运行期写会与 guest 的 FAT 页缓存不一致）；
     * 仅覆写数据、不动元数据，因此写错内容也不会破坏目录结构 —— 内容正确性由调用方
     * （写前备份 + 回读比对 + 失败回滚）兜底。
     *
     * @param maxLen 调用方声明的写入上限（如 `/grub/user.cfg` 的定长 4096），双重防线之一。
     * @return `true` 覆写完成；`false` 目标不存在或是目录（未写任何字节）。
     * @throws IllegalArgumentException 字节数越界（> 文件大小或 > [maxLen]）。
     * @throws VmdDiskException 簇链非法（未落盘即拒绝）/ 写入 IO 失败（可能部分写）。
     */
    internal fun overwriteFileData(path: String, bytes: ByteArray, maxLen: Int): Boolean {
        require(bytes.size <= maxLen) {
            "esp: 覆写 ${bytes.size} 字节超过上限 $maxLen（'$path'）"
        }
        val entry = lookup(path) ?: return false
        if (entry.isDirectory) return false
        require(bytes.size <= entry.size) {
            "esp: 覆写 ${bytes.size} 字节超过文件 '$path' 大小 ${entry.size}" +
                "（只允许定长覆盖既有数据，不改文件大小）"
        }
        if (bytes.isEmpty()) return true
        if (entry.firstCluster < 2) {
            throw VmdDiskException.of(
                "esp: 文件 '$path' 首簇 ${entry.firstCluster} 非法", VmdDiskReason.CORRUPT,
            )
        }
        // 第一趟：整链校验并收集簇号 —— 链短/坏/越界/成环时**尚未写入任何字节**
        val clusBytes = boot.clusBytes.toLong()
        val clusters = ArrayList<Long>()
        var cluster = entry.firstCluster
        var covered = 0L
        var steps = 0L
        while (covered < bytes.size) {
            if (steps++ >= boot.clusCount) {
                throw VmdDiskException.of("esp: 写入 '$path' 簇链成环（> ${boot.clusCount} 簇）", VmdDiskReason.CORRUPT)
            }
            if (cluster < 2 || cluster >= boot.clusCount + 2) {
                throw VmdDiskException.of("esp: 写入 '$path' 簇 $cluster 越界", VmdDiskReason.CORRUPT)
            }
            clusters.add(cluster)
            covered += clusBytes
            if (covered >= bytes.size) break
            val next = fatNext(cluster)
            if (next >= EOC_MIN) {
                throw VmdDiskException.of(
                    "esp: 写入 '$path' 簇链过短（覆盖 $covered 字节即 EOC，需 ${bytes.size}）",
                    VmdDiskReason.CORRUPT,
                )
            }
            if (next == 0L || next == 1L || next == 0x0FFFFFF7L) {
                throw VmdDiskException.of("esp: 写入 '$path' 簇链指向坏簇/空闲簇 ($next)", VmdDiskReason.CORRUPT)
            }
            cluster = next
        }
        // 第二趟：独立 rw 句柄逐簇覆写数据区（FAT/目录项一律不碰），完成后 fsync
        val h = try {
            RandomAccessFile(file, "rw")
        } catch (e: IOException) {
            throw VmdDiskException.of("esp: 打开镜像可写句柄失败: ${e.message}", VmdDiskReason.CORRUPT, e)
        }
        h.use { rw ->
            var written = 0L
            try {
                for (c in clusters) {
                    val off = Math.addExact(boot.dataOffset, Math.multiplyExact(c - 2, clusBytes))
                    val n = minOf(clusBytes, bytes.size - written).toInt()
                    rw.seek(Math.addExact(partOffset, off))
                    rw.write(bytes, written.toInt(), n)
                    written += n
                }
                rw.fd.sync()
            } catch (e: IOException) {
                throw VmdDiskException.of(
                    "esp: 覆写 '$path' 失败（已写 $written/${bytes.size} 字节，可能部分写）: ${e.message}",
                    VmdDiskReason.CORRUPT,
                    e,
                )
            }
        }
        return true
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: IOException) {
            // 关闭失败不影响调用方（只读句柄，无状态需回滚）
        }
    }

    // ------------------------------------------------------------------
    // 路径查找（大小写不敏感；FAT 语义）
    // ------------------------------------------------------------------

    /** 解析路径 → 目录项；不存在/某级不是目录 → null。 */
    private fun lookup(path: String): Entry? {
        val comps = path.split('/').filter { it.isNotEmpty() && it != "." }
        var cluster = boot.rootClus
        var found: Entry? = null
        for ((i, comp) in comps.withIndex()) {
            val entries = readDir(cluster)
            val hit = entries.find { it.name.equals(comp, ignoreCase = true) } ?: return null
            val isLast = i == comps.lastIndex
            if (!isLast) {
                if (!hit.isDirectory || hit.firstCluster < 2) return null
                cluster = hit.firstCluster
            }
            found = hit
        }
        // 空路径（"/"）：合成根目录项
        return found ?: Entry(name = "", size = 0, isDirectory = true, firstCluster = boot.rootClus)
    }

    // ------------------------------------------------------------------
    // 目录解析（8.3 + LFN）
    // ------------------------------------------------------------------

    /** 读目录全部 32 B 项并解析（目录 = 簇链，读到 EOC 为止；上限 [MAX_DIR_BYTES]）。 */
    private fun readDir(startCluster: Long): List<Entry> {
        val bytes = readDirChain(startCluster)
        val out = ArrayList<Entry>()
        // pending LFN：seq → (13 字符, checksum)；见 assemble 注释
        val pending = HashMap<Int, Pair<String, Int>>()
        var off = 0
        while (off + 32 <= bytes.size) {
            val b0 = bytes[off].toInt() and 0xFF
            if (b0 == 0x00) break                    // 0x00 = 未再使用，目录结束
            if (b0 == 0xE5) {                        // 已删除项（其 LFN 行同样逐行标记 E5 → pending 不受影响）
                off += 32
                continue
            }
            val attr = bytes[off + 11].toInt() and 0xFF
            if (attr == LFN_ATTR) {
                parseLfn(bytes, off, pending)
                off += 32
                continue
            }
            // ---- 8.3 短名项（普通/目录/卷标）----
            val short = shortName(bytes, off)
            if (attr and ATTR_VOLUME_ID != 0) {      // 卷标：跳过并清空未完成的 LFN
                pending.clear()
                off += 32
                continue
            }
            var name = short
            if (pending.isNotEmpty()) {
                val assembled = assembleLfn(pending, checksum11(bytes, off))
                if (assembled != null) name = assembled
                pending.clear()
            }
            // 首簇 = (高 16 位 << 16) | 低 16 位（FAT32 目录项 20..21 高位、26..27 低位）
            val firstCluster = (((bytes[off + 20].toLong() and 0xFF) shl 16) or
                ((bytes[off + 21].toLong() and 0xFF) shl 24) or
                (bytes[off + 26].toLong() and 0xFF) or
                ((bytes[off + 27].toLong() and 0xFF) shl 8)) and CLUS_MASK
            val size = (bytes[off + 28].toLong() and 0xFF) or
                ((bytes[off + 29].toLong() and 0xFF) shl 8) or
                ((bytes[off + 30].toLong() and 0xFF) shl 16) or
                ((bytes[off + 31].toLong() and 0xFF) shl 24)
            if (name != "." && name != "..") {
                out.add(Entry(name = name, size = size, isDirectory = attr and ATTR_DIRECTORY != 0, firstCluster = firstCluster))
            }
            off += 32
        }
        return out
    }

    /**
     * 解析一行 LFN 项：序号 = b[0]&0x1F，bit6(0x40) = 本组最后一行（盘上最先出现）。
     * 13 个 UTF-16LE 字符分布在 [1..11)、[14..26)、[28..40)。
     * 新组的 0x40 行到来时若 pending 未闭合（上一组缺短名项）→ 丢弃旧组（防御性）。
     */
    private fun parseLfn(bytes: ByteArray, off: Int, pending: HashMap<Int, Pair<String, Int>>) {
        val seq = bytes[off].toInt() and 0x1F
        val isLast = bytes[off].toInt() and 0x40 != 0
        if (seq == 0 || seq > 20) {
            pending.clear()
            return
        }
        if (isLast && pending.isNotEmpty()) pending.clear()
        val sb = StringBuilder(13)
        for (o in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
            sb.append(((bytes[off + o].toInt() and 0xFF) or ((bytes[off + o + 1].toInt() and 0xFF) shl 8)).toChar())
        }
        pending[seq] = sb.toString() to (bytes[off + 13].toInt() and 0xFF)
    }

    /**
     * 组装 LFN：要求 seq 恰为 1..n 连续且每行 checksum 与短名项一致，否则放弃（回落 8.3 名）。
     * 0x0000 之后的填充（0xFFFF）截断。
     */
    private fun assembleLfn(pending: Map<Int, Pair<String, Int>>, shortChecksum: Int): String? {
        val n = pending.size
        if (n == 0) return null
        val all = StringBuilder(n * 13)
        for (seq in 1..n) {
            val part = pending[seq] ?: return null        // 序号不连续
            if (part.second != shortChecksum) return null // checksum 不符（LFN 与短名不配对）
            all.append(part.first)
        }
        val text = all.toString()
        val cut = text.indexOf('\u0000')
        return if (cut >= 0) text.substring(0, cut) else text
    }

    /** 8.3 短名：BASE(8)+EXT(3)，空格修剪；0x05 前缀 = 首字符 0xE5。 */
    private fun shortName(bytes: ByteArray, off: Int): String {
        fun part(o: Int, len: Int): String {
            val sb = StringBuilder(len)
            for (i in o until o + len) {
                var c = bytes[i].toInt() and 0xFF
                if (i == o && c == 0x05) c = 0xE5
                sb.append(c.toChar())
            }
            return sb.toString().trimEnd(' ')
        }
        val base = part(off, 8)
        val ext = part(off + 8, 3)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** 11 B 短名 checksum（LFN 配对判据；每步按 unsigned char 回绕 —— 与 mkfs/mtools 参考实现一致）。 */
    private fun checksum11(bytes: ByteArray, off: Int): Int {
        var sum = 0
        for (i in 0..10) {
            sum = (((sum and 1) shl 7) + (sum shr 1) + (bytes[off + i].toInt() and 0xFF)) and 0xFF
        }
        return sum
    }

    // ------------------------------------------------------------------
    // 簇链（FAT 只读；仅读 FAT#1 —— mkfs 生成的两份 FAT 内容一致）
    // ------------------------------------------------------------------

    /**
     * 读完整条目录簇链（到 EOC 为止 —— 目录不像文件有 size 字段）。
     * 成环（步数超簇总数）/ 越界 / 超 [MAX_DIR_BYTES] → [VmdDiskReason.CORRUPT]。
     */
    private fun readDirChain(startCluster: Long): ByteArray {
        if (startCluster < 2 || startCluster >= boot.clusCount + 2) {
            throw VmdDiskException.of("esp: 目录首簇 $startCluster 越界", VmdDiskReason.CORRUPT)
        }
        val clusBytes = boot.clusBytes
        val out = java.io.ByteArrayOutputStream(clusBytes)
        val chunk = ByteArray(clusBytes)
        var cluster = startCluster
        var steps = 0L
        var total = 0L
        while (true) {
            if (steps++ >= boot.clusCount) {
                throw VmdDiskException.of("esp: 目录簇链成环（> ${boot.clusCount} 簇）", VmdDiskReason.CORRUPT)
            }
            if (cluster < 2 || cluster >= boot.clusCount + 2) {
                throw VmdDiskException.of("esp: 目录簇 $cluster 越界", VmdDiskReason.CORRUPT)
            }
            if (total + clusBytes > MAX_DIR_BYTES) {
                throw VmdDiskException.of("esp: 目录链超过上限 $MAX_DIR_BYTES 字节", VmdDiskReason.CORRUPT)
            }
            val off = Math.addExact(boot.dataOffset, Math.multiplyExact(cluster - 2, clusBytes.toLong()))
            try {
                raf.seek(Math.addExact(partOffset, off))
                raf.readFully(chunk)
            } catch (e: IOException) {
                throw VmdDiskException.of("esp: 读目录簇 $cluster 失败: ${e.message}", VmdDiskReason.CORRUPT, e)
            }
            out.write(chunk)
            total += clusBytes
            val next = fatNext(cluster)
            if (next >= EOC_MIN) break
            if (next < 2L || next == 0x0FFFFFF7L) {
                throw VmdDiskException.of("esp: 目录簇链指向坏簇/空闲簇 ($next)", VmdDiskReason.CORRUPT)
            }
            cluster = next
        }
        return out.toByteArray()
    }

    /** 读 FAT 表项（u32，取低 28 位）。 */
    private fun fatNext(cluster: Long): Long {
        if (cluster < 2 || cluster >= boot.clusCount + 2) {
            throw VmdDiskException.of(
                "esp: 簇号 $cluster 越界 [2, ${boot.clusCount + 2})",
                VmdDiskReason.CORRUPT,
            )
        }
        val idx = Math.multiplyExact(cluster, 4L)
        if (idx + 4 > boot.fatBytes) {
            throw VmdDiskException.of("esp: 簇 $cluster 的 FAT 表项超出 FAT 区", VmdDiskReason.CORRUPT)
        }
        val abs = Math.addExact(boot.fatOffset, idx)
        val b = ByteArray(4)
        try {
            raf.seek(Math.addExact(partOffset, abs))
            raf.readFully(b)
        } catch (e: IOException) {
            throw VmdDiskException.of("esp: 读 FAT 失败 @+$abs: ${e.message}", VmdDiskReason.CORRUPT, e)
        }
        var v = 0L
        for (k in 3 downTo 0) v = (v shl 8) or (b[k].toLong() and 0xFF)
        return v and CLUS_MASK
    }

    /**
     * 跟随簇链读取内容。
     *
     * @param want 期望字节数（文件读取 = 目录项 size；传 null 表示读完整条链，受 [limit] 约束）。
     * @param limit 链读取字节上限（目录防御）。
     * @throws VmdDiskException CORRUPT 成环（步数超簇总数）/ 坏簇 / 越界 / 过短 / IO 失败。
     */
    private fun readChain(startCluster: Long, want: Long? = null, limit: Long = MAX_FILE_BYTES, what: String = "chain"): ByteArray {
        val need = want ?: limit
        if (need > limit) {
            throw VmdDiskException.of("esp: $what 声明大小 $need 超过上限 $limit", VmdDiskReason.CORRUPT)
        }
        if (need == 0L) return ByteArray(0)
        if (startCluster < 2) {
            // size>0 但首簇 0/1 → 结构非法；size=0 已在上行返回
            throw VmdDiskException.of("esp: $what 首簇 $startCluster 非法", VmdDiskReason.CORRUPT)
        }
        val clusBytes = boot.clusBytes.toLong()
        val out = ByteArray(need.toInt())     // need ≤ limit ≤ MAX_FILE_BYTES(512MiB)，Int 安全
        var cluster = startCluster
        var written = 0L
        var steps = 0L
        while (written < need) {
            if (steps++ >= boot.clusCount) {
                throw VmdDiskException.of("esp: $what 簇链成环（> ${boot.clusCount} 簇）", VmdDiskReason.CORRUPT)
            }
            if (cluster < 2 || cluster >= boot.clusCount + 2) {
                throw VmdDiskException.of("esp: $what 簇 $cluster 越界", VmdDiskReason.CORRUPT)
            }
            val off = Math.addExact(boot.dataOffset, Math.multiplyExact(cluster - 2, clusBytes))
            val n = minOf(clusBytes, need - written).toInt()
            try {
                raf.seek(Math.addExact(partOffset, off))
                raf.readFully(out, written.toInt(), n)
            } catch (e: IOException) {
                throw VmdDiskException.of("esp: 读簇 $cluster 失败: ${e.message}", VmdDiskReason.CORRUPT, e)
            }
            written += n
            if (written >= need) break
            val next = fatNext(cluster)
            if (next >= EOC_MIN) {
                throw VmdDiskException.of(
                    "esp: $what 簇链过短（已读 $written/$need 字节即 EOC）",
                    VmdDiskReason.CORRUPT,
                )
            }
            if (next == 0L || next == 1L || next == 0x0FFFFFF7L) {
                throw VmdDiskException.of("esp: $what 簇链指向坏簇/空闲簇 ($next)", VmdDiskReason.CORRUPT)
            }
            cluster = next
        }
        return out
    }
}
