/*
 * VMDroid - zstd 流式解压 + 全零 4 KiB 块跳写的稀疏落盘（M3）。
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * 实现依据（冻结规格）：
 *   - docs/DESIGN-disk-image-v2.md §6.1（安装: zstd -d 流式 → 全零块不写 → images/<id>.img.part
 *     → 全量 image_sha256 校验 → codec 结构校验 → 原子 rename）
 *   - docs/IMAGE-FORMAT-v2.md §7.1（.img.zst 容器：标准 zstd frame；解压 = 应用流式解压 +
 *     全零 4 KiB 块跳写，装后实际占用 ≈ rootfs 用量）
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型 —— 可脱离 Android SDK 用 kotlinc 独立编译
 * （systemimage-selftest/README §2 的命令不含本文件：只有 gradle 全量编译与本工位的端到端
 * 临时自测会编译它）。
 *
 * ── 解压实现决策（M3 拍板；2026-10-13 真机实证后修订为 jniLibs 分发）──
 * 无纯 JVM zstd 解码器可用：zstd-jni 的 native 库是 glibc 构建，Android bionic 加载不可靠
 * （且引入三方依赖）。因此采用**随 APK 分发的静态 `zstd` 可执行文件**：
 *
 *   - 分发路径：`app/src/main/jniLibs/arm64-v8a/libzstdbin.so`（aarch64 静态构建），
 *     `packaging.jniLibs.useLegacyPackaging=true` → 安装期由 PackageManager 解包到
 *     `nativeLibraryDir`（system 拥有、0755、可 exec）——与 QEMU 的
 *     `libqemu-system-aarch64.so` 同机制。
 *   - **W^X 实证（2026-10-13 真机 error=13）**：Android 10+ 的 untrusted_app 域禁止
 *     execve 应用可写目录（filesDir）内的文件，原方案 `assets/bin/zstd →
 *     filesDir/bin/zstd` 真机必 EACCES —— 已按 DESIGN §7.2 T-1 回退方案落地：
 *     资产迁入 jniLibs，`ensureZstdBinary()` 直接返回 nativeLibraryDir 路径。
 *   - 解析顺序（[ZstdInflator.resolveBinary]）：显式 hint（nativeLibraryDir/libzstdbin.so）→
 *     `$VMD_ZSTD` → `$PATH` 扫描 → /system/bin、/usr/bin 等固定位置。
 *     **开发期降级**：native 缺失时从 PATH 找 zstd（本机测试用 /usr/bin/zstd）。
 *   - 执行：`ProcessBuilder(zstd, "-dc")`，stdin 由应用喂源字节（边喂边算 `sha256(zst)`），
 *     stdout 管道由本文件的 [SparseImageWriter] 稀疏落盘 + 流式算 `image_sha256`。
 *     退出码非 0 → 安装失败（`.part` 由 SystemImageStore.install 清理）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 稀疏写入器：输出流按 [blockBytes]（默认 4 KiB，§7.1）分块，**全零块跳过不落盘**
 * （跳写前 `RandomAccessFile.seek` 到逻辑偏移，让文件系统留洞），非零块 seek 后写入。
 * 同步流式计算整流 sha256 与字节数（= `image_sha256` / `image_size`）。
 *
 * 结束语义（[finish]）：尾部不足一块的残块（含全零残块）同样按规则处理后
 * `setLength(logicalSize)` —— 被跳过的零块与零尾由文件系统按洞补齐（读作 0），
 * `fd.sync()` 落盘。**任何时刻 dst 要么是半截文件（`.part`），要么是完整文件** ——
 * 调用方（install）负责 rename 与失败清理。
 *
 * 纯 JVM（自测可直接用 `/usr/bin/zstd` 驱动）。
 */
class SparseImageWriter @JvmOverloads constructor(
    dst: File,
    private val blockBytes: Int = BLOCK_BYTES,
) : Closeable {
    private val raf = RandomAccessFile(dst, "rw")
    private val digest = MessageDigest.getInstance("SHA-256")
    private val block = ByteArray(blockBytes)
    private var blockLen = 0
    private var logicalSize = 0L
    private var finished = false

    init {
        require(blockBytes > 0 && blockBytes % 4096 == 0) { "blockBytes must be a positive 4 KiB multiple" }
        raf.setLength(0) // 可能覆盖上次失败残留的 .part —— 必须从零开始
    }

    /** 追加 [len] 字节（内部按块切分；[src] 可复用）。 */
    fun write(src: ByteArray, off: Int, len: Int) {
        var i = off
        var remain = len
        while (remain > 0) {
            val n = minOf(blockBytes - blockLen, remain)
            System.arraycopy(src, i, block, blockLen, n)
            blockLen += n
            i += n
            remain -= n
            if (blockLen == blockBytes) flushBlock()
        }
    }

    private fun flushBlock() {
        digest.update(block, 0, blockLen)
        if (!allZero(block, blockLen)) {
            raf.seek(logicalSize) // 写前 seek：此前被跳过的零块即成稀疏洞
            raf.write(block, 0, blockLen)
        }
        logicalSize += blockLen
        blockLen = 0
    }

    /**
     * 收尾：残块 → `setLength(逻辑大小)` → fsync。
     * @return (整流 sha256 小写 hex, 逻辑字节数)
     */
    fun finish(): Result {
        if (!finished) {
            if (blockLen > 0) flushBlock()
            raf.setLength(logicalSize) // 全零尾/被跳过块补齐为洞
            raf.fd.sync()
            finished = true
        }
        return Result(SystemImageStore.hex(digest.digest()), logicalSize)
    }

    override fun close() {
        try {
            raf.close()
        } catch (e: IOException) {
            // 收尾失败已由 finish 抛出/记录；close 只兜底
        }
    }

    data class Result(val sha256: String, val size: Long)

    companion object {
        const val BLOCK_BYTES = 4096

        private fun allZero(b: ByteArray, len: Int): Boolean {
            for (i in 0 until len) if (b[i].toInt() != 0) return false
            return true
        }
    }
}

/**
 * `.img.zst` 流式解压（`zstd -dc` 子进程）→ [SparseImageWriter] 稀疏落盘。
 * 见文件头"解压实现决策"。所有失败抛 [VmdImageException]（reason = `IO_ERROR` /
 * `PAYLOAD_CORRUPT`），`.part` 清理由调用方负责。
 */
object ZstdInflator {

    /** zstd frame 魔数（little-endian 0xFD2FB528）。 */
    val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private const val FEED_BUFFER = 256 * 1024
    private const val READ_BUFFER = 1024 * 1024
    private const val STDERR_LIMIT = 512

    /** 解压结果：`.img` 侧摘要/字节数 +（可选）源 `.img.zst` 侧摘要/字节数。 */
    data class Result(
        val imageSha256: String,
        val imageSize: Long,
        val inputSha256: String?,
        val inputBytes: Long,
    )

    /** 读到的前 4 字节是否 zstd frame 魔数（容器嗅探，§7.1）。 */
    fun isZstdMagic(head: ByteArray): Boolean {
        if (head.size < 4) return false
        for (i in 0 until 4) if (head[i] != ZSTD_MAGIC[i]) return false
        return true
    }

    /**
     * 解析 zstd 可执行文件（见文件头决策）：
     * [hint]（nativeLibraryDir 解包物）→ `$VMD_ZSTD` → `$PATH` → 固定位置。
     * 找到但缺执行位时尝试 `setExecutable`（nativeLibraryDir 属 system 所有、改不动，
     * 但解包物本身就是 0755）；最终找不到返回 null。
     */
    @JvmStatic
    @JvmOverloads
    fun resolveBinary(hint: File? = null): File? {
        val candidates = ArrayList<File>()
        hint?.let { candidates += it }
        System.getenv("VMD_ZSTD")?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
            if (dir.isNotBlank()) candidates += File(dir, "zstd")
        }
        candidates += File("/system/bin/zstd")
        candidates += File("/system/xbin/zstd")
        candidates += File("/usr/local/bin/zstd")
        candidates += File("/usr/bin/zstd")
        for (c in candidates) {
            if (!c.isFile) continue
            if (!c.canExecute()) c.setExecutable(true, false) // 复制物默认 0644（仅开发场景走到）
            if (c.canExecute()) return c
        }
        return null
    }

    /**
     * 解压 `input`（`.img.zst` 字节）到 [dst]（稀疏）。
     *
     * @param input 压缩源流（本方法负责 close；前 4 字节须为 zstd magic）
     * @param zstdBinary zstd 可执行文件（null → [resolveBinary]）
     * @param inputDigest 源字节摘要（`sha256(zst)` 双重校验第一道；边喂边算）
     * @param onProgress 源（压缩）字节进度
     * @throws VmdImageException zstd 缺失 / 启动失败 / 退出码非 0 / 源流 IO 失败
     */
    @JvmStatic
    @JvmOverloads
    fun inflate(
        input: InputStream,
        dst: File,
        zstdBinary: File? = null,
        inputDigest: MessageDigest? = null,
        onProgress: ((Long) -> Unit)? = null,
    ): Result {
        val bin = resolveBinary(zstdBinary) ?: throw VmdImageException.of(
            "zstd executable not found (bundled jniLibs/libzstdbin.so missing and no zstd on PATH) — " +
                "cannot install a .img.zst system image",
            VmdImageReason.IO_ERROR,
        )
        val proc = try {
            ProcessBuilder(bin.absolutePath, "-dc").start()
        } catch (e: IOException) {
            val wx = if (e.message?.contains("error=13") == true) {
                " [Android W^X: files in the app's writable data dir are not executable; " +
                    "zstd must live in nativeLibraryDir]"
            } else ""
            throw VmdImageException.of(
                "cannot start '${bin.absolutePath} -dc': ${e.message}$wx",
                VmdImageReason.IO_ERROR,
                e,
            )
        }

        // stderr 必须并行排空（zstd 的告警/错误可能撑满管道造成死锁）
        val stderr = ByteArrayOutputStream()
        val errThread = thread(name = "zstd-stderr", isDaemon = true) {
            try {
                proc.errorStream.use { it.copyTo(stderr) }
            } catch (e: IOException) {
                // 进程已退出/被销毁时的读失败可忽略
            }
        }

        // 喂入线程：源字节 → zstd stdin，同时算 sha256(zst) + 进度
        var feedError: Throwable? = null
        val inputBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val feedThread = thread(name = "zstd-feed", isDaemon = true) {
            try {
                input.use { src ->
                    proc.outputStream.use { out ->
                        val buf = ByteArray(FEED_BUFFER)
                        var total = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            inputDigest?.update(buf, 0, n)
                            total += n
                            inputBytes.set(total)
                            onProgress?.invoke(total)
                        }
                    }
                }
            } catch (t: Throwable) {
                feedError = t
                runCatching { proc.destroy() }
            }
        }

        val imageResult = try {
            // stdout：按 4 KiB 块稀疏落盘（全零块跳写）
            BufferedInputStream(proc.inputStream, READ_BUFFER).use { stdout ->
                SparseImageWriter(dst).use { writer ->
                    val buf = ByteArray(READ_BUFFER)
                    while (true) {
                        val n = stdout.read(buf)
                        if (n < 0) break
                        writer.write(buf, 0, n)
                    }
                    writer.finish()
                }
            }
        } catch (t: Throwable) {
            runCatching { proc.destroy() }
            feedThread.join()
            errThread.join()
            throw when (t) {
                is VmdImageException -> t
                is IOException -> VmdImageException.of("io error while inflating: ${t.message}", VmdImageReason.IO_ERROR, t)
                else -> t
            }
        }

        feedThread.join()
        errThread.join()
        val code = try {
            proc.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VmdImageException.of("interrupted while waiting for zstd", VmdImageReason.IO_ERROR, e)
        }
        // 优先报 zstd 自身的退出码/stderr（源损坏时它才是根因；broken pipe 是派生症状）
        if (code != 0) {
            val errText = String(stderr.toByteArray(), Charsets.UTF_8).take(STDERR_LIMIT).trim()
            // Android 信号退出 = 128+N：159 → SIGSYS(31)，典型为 seccomp 拦截
            // （glibc 静态构建在应用域会触发；bionic 静态构建已规避，DESIGN §7.2 T-1）
            val how = if (code >= 128) {
                "killed by signal ${code - 128}" +
                    if (code - 128 == 31) " (SIGSYS: seccomp blocked a syscall?)" else ""
            } else {
                "exited with $code"
            }
            throw VmdImageException.of(
                "zstd $how while decompressing: $errText",
                VmdImageReason.PAYLOAD_CORRUPT,
            )
        }
        feedError?.let { t ->
            throw when (t) {
                is IOException -> VmdImageException.of("io error while reading the .img.zst source: ${t.message}", VmdImageReason.IO_ERROR, t)
                else -> t
            }
        }
        return Result(
            imageSha256 = imageResult.sha256,
            imageSize = imageResult.size,
            inputSha256 = inputDigest?.let { SystemImageStore.hex(it.digest()) },
            inputBytes = inputBytes.get(),
        )
    }

    /**
     * 裸 `.img` 源 → 稀疏落盘（SAF 导入 `.img` 时"跳过解压"但仍稀疏，§6.1 兼容行）。
     * 只返回 `.img` 侧摘要与字节数（源即整镜像，无需第二道摘要）。
     */
    @JvmStatic
    @JvmOverloads
    fun copySparse(
        input: InputStream,
        dst: File,
        onProgress: ((Long) -> Unit)? = null,
    ): SparseImageWriter.Result = input.use { src ->
        SparseImageWriter(dst).use { writer ->
            val buf = ByteArray(FEED_BUFFER)
            var total = 0L
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                writer.write(buf, 0, n)
                total += n
                onProgress?.invoke(total)
            }
            writer.finish()
        }
    }
}
