/*
 * EspUserCfgWriter — R8 运行时参数通道（IMAGE-FORMAT-v2 §2.4 R8；
 * DESIGN-disk-image-v2 §3.4 方案 b / §16 TBD-1 / §7.2 T-10）。
 *
 * 形态（规格定稿，实测结论照录）：
 *   - `/grub/user.cfg` = **定长 4096 字节** GRUB 脚本文件（构建期预置、簇已分配）：
 *     魔数行 `#VMDU2\n` + 可选一行 `set podroid_params="<空格分隔参数串>"\n` +
 *     其余以 `0x0A` 填充（**禁 NUL** —— GRUB source 读到 NUL 行为不可控）。
 *   - 引号必需（无引号 GRUB set 值截断到第一个空格）；引号会保留在变量值里 →
 *     内核 cmdline 该段带包裹引号，guest 子串/模式捕获不受影响（不需要任何规避）。
 *   - 消费方 = 嵌入式 cfg 的 `source /grub/user.cfg` + `linux … $podroid_params`
 *     （mkdisk 已产出此形态；**不要**用 configfile —— 实测不回传变量）。
 *   - 写入 = **定长覆盖**：只覆写文件数据区字节，永不改目录项/文件大小/簇链；
 *     写后整 4096 字节回读比对，不一致即用写前备份回滚并报错。
 *
 * 安全边界（R8-4，防 GRUB 命令注入）：参数键固定、逐字段白名单，任何字段不满足
 * **整条拒绝**（[sanitize] 返回 null，fail-closed —— 调用方记 warning 用默认值启动）。
 *
 * 纯 JVM Kotlin：禁止 import 任何 android.* 类型（与 VmdDiskCodec.kt / EspFat32.kt 同批
 * kotlinc 自测，且可直接做 Gradle 单元测试）。
 */
package io.github.ltbkq.vmdroid.systemimage

import java.io.File

/**
 * R8 的 4 个运行时参数（DESIGN-disk-image-v2 §3.4 TBD-1 收窄清单；`androidip=`/`ssh=1`
 * 为上游死参数不在此列，v2 不传）。键名由 [EspUserCfgWriter.sanitize] 固定拼装，
 * 调用方只给值 —— 不接受任意键注入。
 */
data class BootParams(
    /** `podroid.x11.dpi=`（纯数字；恒注入，与 v1 `-append` 行为一致）。 */
    val x11Dpi: Int,
    /** `podroid.bandwidth=`（纯数字；>0 才注入）。 */
    val bandwidthMbps: Int,
    /** `podroid.dns=`（逗号拼接；每项须匹配 `[0-9a-fA-F.:%]+`，非空才注入）。 */
    val dnsServers: List<String>,
    /** `userKernelExtras`（自由文本；白名单外字符 → 整条拒绝；空白不注入）。 */
    val kernelExtras: String,
)

/** R8 `/grub/user.cfg` 构造 + 白名单 + 定长覆盖写（形态规格见文件头注释）。 */
object EspUserCfgWriter {

    /** ESP 内目标文件路径（`/` 为根；[EspFat32] 路径比对大小写不敏感）。 */
    const val USER_CFG_PATH: String = "/grub/user.cfg"

    /** 定长字节数（构建期预置值；覆盖写与回读比对都以它为准）。 */
    const val CONTENT_SIZE: Int = 4096

    /** 魔数行（首行；含行尾 `\n`，R8-1）。 */
    private const val MAGIC_LINE = "#VMDU2\n"

    /** `podroid.dns` 单项字符集：hex/点/冒号/百分号（IP、v6、带端口、scope-id）。 */
    private val DNS_TOKEN = Regex("^[0-9a-fA-F.:%]+$")

    /** `userKernelExtras` 字符白名单 `[A-Za-z0-9 ._=/,:+-]`（R8-4）。 */
    private fun Char.isExtrasAllowed(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in " ._=/,:+-"

    /** 纯数字校验：非空且**仅** ASCII `0-9`（不用 Char.isDigit —— 它接受 Unicode 数字）。 */
    private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

    // ------------------------------------------------------------------
    // 拼装 + 白名单（R8-4）
    // ------------------------------------------------------------------

    /**
     * 拼装参数串并做全字段白名单校验。
     *
     * 规则表（键固定；任一字段不满足 → **整条拒绝返回 null**，fail-closed）：
     *
     * | 字段 | 注入条件 | 校验 |
     * |---|---|---|
     * | `podroid.x11.dpi` | 恒注入 | ASCII 纯数字（负数 → 拒绝） |
     * | `podroid.bandwidth` | `> 0` | ASCII 纯数字 |
     * | `podroid.dns` | 列表非空 | 每项非空且匹配 `[0-9a-fA-F.:%]+` |
     * | `kernelExtras`（原样追加） | trim 后非空 | 每字符 ∈ `[A-Za-z0-9 ._=/,:+-]` |
     *
     * @return 合法参数串（空格分隔；**无任何可注入字段时为 `""`**，非 null）；
     *         `null` = 白名单拒绝 —— 调用方记 warning、用默认参数启动、不阻断。
     */
    fun sanitize(params: BootParams): String? {
        val parts = ArrayList<String>(4)
        val dpi = params.x11Dpi.toString()
        if (!dpi.isAsciiDigits()) return null
        parts += "podroid.x11.dpi=$dpi"
        if (params.bandwidthMbps > 0) {
            val bw = params.bandwidthMbps.toString()
            if (!bw.isAsciiDigits()) return null
            parts += "podroid.bandwidth=$bw"
        }
        if (params.dnsServers.isNotEmpty()) {
            // 每项逐个校验（空串、含空格/分号/引号/字母 g… 任一 → 整条拒绝）
            for (srv in params.dnsServers) {
                if (!DNS_TOKEN.matches(srv)) return null
            }
            parts += "podroid.dns=" + params.dnsServers.joinToString(",")
        }
        val extras = params.kernelExtras.trim()
        if (extras.isNotEmpty()) {
            if (extras.any { !it.isExtrasAllowed() }) return null
            parts += extras
        }
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // 构造 4096 字节内容（R8-1）
    // ------------------------------------------------------------------

    /**
     * 构造定长 [CONTENT_SIZE] 字节的 user.cfg 内容：
     * `#VMDU2\n` + （[userParams] 非 null/非空时）`set podroid_params="<userParams>"\n`
     * + `0x0A` 填充至 [CONTENT_SIZE]。
     *
     * [userParams] 为 null / 空串 → **无 set 行**版本（仅魔数 + 填充 —— GRUB 侧
     * `$podroid_params` 未定义/空时展开为空串，等价"默认参数启动"）。
     *
     * 形态级防线（内容白名单在 [sanitize]；这里是最后一道）：
     * NUL 一律拒绝（R8-1 禁 NUL），换行/回车拒绝（保持"一行 set"结构，防换行注入），
     * 超长拒绝（装不下即抛）。
     *
     * @throws IllegalArgumentException 含 NUL/CR/LF 或超出 [CONTENT_SIZE]。
     */
    fun build(userParams: String?): ByteArray {
        val head = StringBuilder(MAGIC_LINE.length + 64)
        head.append(MAGIC_LINE)
        if (!userParams.isNullOrEmpty()) {
            require(userParams.none { it == '\u0000' || it == '\n' || it == '\r' }) {
                "userParams 含 NUL/换行（禁 NUL，且 set 行必须单行）"
            }
            head.append("set podroid_params=\"").append(userParams).append("\"\n")
        }
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        require(headBytes.size <= CONTENT_SIZE) {
            "user.cfg 内容超长：${headBytes.size} > $CONTENT_SIZE"
        }
        val out = ByteArray(CONTENT_SIZE) { 0x0A }   // 0x0A 填充，全文件禁 NUL
        headBytes.copyInto(out)
        return out
    }

    // ------------------------------------------------------------------
    // 定长覆盖写（R8-3：定位 → 覆盖 → 回读比对 → 不一致回滚）
    // ------------------------------------------------------------------

    /**
     * 把 [content] 定长覆盖写入镜像 ESP 的 [USER_CFG_PATH]（VM **停机期**调用）。
     *
     * 时序（IMAGE-FORMAT-v2 §2.4 R8-3）：
     * 1. [EspFat32] 定位 `/grub/user.cfg` 目录项 —— **不存在/是目录/非 4096 定长 → 拒绝**
     *    （不创建新文件：恒定长覆盖语义，预置是前提）；
     * 2. 写前备份原 4096 字节（驻留内存）；
     * 3. 覆写簇链数据区（[EspFat32.overwriteFileData]：不动目录项/大小/簇链，
     *    `content` 越界在落盘前即拒绝）；
     * 4. 整 4096 字节回读比对 → 不一致（或第 3/4 步抛错，含"部分写"可能）→ 用备份回滚并报错。
     *
     * @param content 必须恰为 [CONTENT_SIZE] 字节（定长覆盖；越界/不足均拒绝）。
     * @return 成功 `Result.success(Unit)`；任何失败 `Result.failure`（**不抛给调用方**）。
     */
    fun write(imagePath: File, espOffset: Long, espSize: Long, content: ByteArray): Result<Unit> {
        if (content.size != CONTENT_SIZE) {
            return Result.failure(
                IllegalArgumentException(
                    "content 必须恰为 $CONTENT_SIZE 字节（定长覆盖），got ${content.size}",
                ),
            )
        }
        return runCatching {
            EspFat32.open(imagePath, espOffset, espSize).use { esp ->
                val backup = esp.readFile(USER_CFG_PATH)
                    ?: error("$USER_CFG_PATH 不存在或是目录 —— 拒绝（不创建新文件，恒定长覆盖语义）")
                if (backup.size != CONTENT_SIZE) {
                    error("$USER_CFG_PATH 非定长预置（size=${backup.size} ≠ $CONTENT_SIZE），拒绝覆盖")
                }
                try {
                    if (!esp.overwriteFileData(USER_CFG_PATH, content, CONTENT_SIZE)) {
                        error("$USER_CFG_PATH 覆写目标消失（目录项竞态）")
                    }
                } catch (t: Throwable) {
                    // overwriteFileData 在 IO 失败时可能已部分写 → 尽力回滚后报错
                    rollbackQuietly(esp, backup)
                    throw t
                }
                val readback = try {
                    esp.readFile(USER_CFG_PATH)
                } catch (t: Throwable) {
                    rollbackQuietly(esp, backup)
                    throw t
                } ?: run {
                    rollbackQuietly(esp, backup)
                    error("回读失败：$USER_CFG_PATH 消失或变目录")
                }
                if (!readback.contentEquals(content)) {
                    rollbackQuietly(esp, backup)
                    error("回读比对不一致（${readback.size} B ≠ $CONTENT_SIZE B），已用写前备份回滚")
                }
            }
            Unit
        }
    }

    /** 写前备份回滚（best-effort：回滚自身失败只能记在最终错误的因果里，仍要报错）。 */
    private fun rollbackQuietly(esp: EspFat32, backup: ByteArray) {
        try {
            esp.overwriteFileData(USER_CFG_PATH, backup, CONTENT_SIZE)
        } catch (_: Throwable) {
            // 回滚失败：保留原始错误为主错误（调用方 fail-closed 用默认值启动）
        }
    }
}
