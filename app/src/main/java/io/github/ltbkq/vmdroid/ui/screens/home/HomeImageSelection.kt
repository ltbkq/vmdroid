package io.github.ltbkq.vmdroid.ui.screens.home

import java.util.Locale

/**
 * §8.2 启动镜像选择控件的**纯 UI 模型**（零 android.* import —— 可 JUnit 直测）。
 *
 * 状态机对应 §8.2 行为表八行；Compose 层（HomeScreen）只做渲染与事件转发，
 * ViewModel 负责 IO 与状态转移，判定逻辑全部在这里：
 *
 * | 场景 | 落点 |
 * |---|---|
 * | 校验中 | [HomeImageState.Loaded.checkingId] 非空 → 控件禁用 + spinner |
 * | 无镜像 | [HomeImageState.Loaded.isEmpty] → 空态双入口 + 启动按钮隐藏 |
 * | 加载失败 | [HomeImageState.Loaded.loadFailed] → 保留上次值 + inline error |
 * | CORRUPT | [ImageRowUi.corrupt] 三重编码 + [StartButtonMode.REVERIFY] |
 * | 运行中禁用 | Compose 传入 `running`（v1 不做热切换，「请先停止虚拟机」） |
 */

/** §8.2 下拉行两行结构：① `名称 · 变体` ② `image_id · 体积`。 */
data class ImageRowUi(
    val imageId: String,
    /** manifest `image.display_name`（缺失时回退 image_id）。 */
    val displayName: String,
    val variant: String?,
    /** §8.5 对话框文案用（`alpine:3.24 → debian:trixie`）。 */
    val identity: String?,
    val sizeLabel: String,
    /** §8.2「已损坏」标记（Compose 层给 error 色 + ⚠ + 文字三重编码）。 */
    val corrupt: Boolean,
    val active: Boolean,
    /** manifest `image.system_version`（§8.3 卡片版本行；缺失不显示段落）。 */
    val systemVersion: Long? = null,
) {
    /** 行首：`Debian 13 (trixie) · 最小化`（无变体时只有名称）。 */
    val line1: String = buildString {
        append(displayName)
        if (!variant.isNullOrBlank()) append(" · ").append(variant)
    }

    /** 行次：`debian-minimal-arm64 · 148 MB`。 */
    val line2: String = "$imageId · $sizeLabel"

    /** §8.3 版本行：`debian:trixie · system_version 34`（identity 缺失回退 —）。 */
    val versionLine: String = buildString {
        append(identity ?: "—")
        if (systemVersion != null) append(" · system_version ").append(systemVersion)
    }
}

/** §8.5 激活冲突对话框入参（identity 文案取自 active 记录与目标 manifest）。 */
data class ResetPrompt(
    val targetId: String,
    val fromIdentity: String,
    val toIdentity: String,
    /** §5.2 decision 小写码（identity / contract / init / active_unreadable）。 */
    val decision: String,
)

/** §8.2 行为表 → 启动按钮形态（HomeActionButtons 消费；仅停止态分支生效）。 */
enum class StartButtonMode {
    /** 常规「启动虚拟机」。 */
    SHOW,

    /** 空态：隐藏（不显示必然失败的按钮）。 */
    HIDDEN,

    /** 选中/显示项已损坏：主按钮变「重新校验 / 重新下载」。 */
    REVERIFY,
}

/** §8.2 选择控件状态（初始 [Loading]；转移只发生在 HomeViewModel）。 */
sealed interface HomeImageState {
    /** 首次列表读取未返回（既无数据也无错误）。 */
    data object Loading : HomeImageState

    /**
     * 列表读取已返回。[rows] 可能为空：
     * `rows.isEmpty() && !loadFailed` = 确认无镜像（空态）；
     * [loadFailed] = 目录/列表异常（保留上次值 + 顶部 inline error，不塌陷布局）。
     */
    data class Loaded(
        val rows: List<ImageRowUi>,
        val activeId: String?,
        /** 非空 = 校验中（20dp spinner + 文字「校验中」+ 控件禁用）。 */
        val checkingId: String? = null,
        val loadFailed: Boolean = false,
        /** 选中但未通过激活检查的行（标损坏；显示值与重新校验目标）。 */
        val pendingId: String? = null,
        /** 一次性提示（导入结果 / 激活结果 / 校验结果）；下一次动作前清空。 */
        val message: String? = null,
    ) : HomeImageState {
        /** 控件显示值（§8.2「选中态」）：pending 优先于 active。 */
        val displayedId: String? get() = pendingId ?: activeId

        /** 确认无镜像 → 整块空态 + 隐藏启动按钮（loadFailed 不算空态）。 */
        val isEmpty: Boolean get() = rows.isEmpty() && !loadFailed

        /** 当前显示项是否已判损坏（驱动主按钮 REVERIFY 与行样式）。 */
        val displayedCorrupt: Boolean get() = row(displayedId)?.corrupt == true

        fun row(id: String?): ImageRowUi? = rows.find { it.imageId == id }

        /** 把 [id] 的损坏标记置为 [value] 的副本（[value]=false 即「重新校验通过」治愈）。 */
        fun markCorrupt(id: String, value: Boolean = true): Loaded = copy(
            rows = rows.map { if (it.imageId == id) it.copy(corrupt = value) else it },
        )
    }
}

/** §8.2「完全无镜像 → 启动按钮隐藏；显示项损坏 → 重新校验」的按钮形态判定。 */
fun startButtonMode(state: HomeImageState): StartButtonMode = when (state) {
    // 加载中：维持 M1 既有行为（不误伤启动入口），BootGuard 在 start() 兜底。
    is HomeImageState.Loading -> StartButtonMode.SHOW
    is HomeImageState.Loaded -> when {
        state.isEmpty -> StartButtonMode.HIDDEN
        state.displayedCorrupt -> StartButtonMode.REVERIFY
        else -> StartButtonMode.SHOW
    }
}

/** §8.2 体积标签（十进制 MB/GB，与 mkimg/catalog 的 `MB` 口径一致）。 */
fun formatBytes(size: Long): String {
    fun trim1(v: Double): String =
        String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")
    return when {
        size >= 1_000_000_000L -> trim1(size / 1_000_000_000.0) + " GB"
        size >= 1_000_000L -> trim1(size / 1_000_000.0) + " MB"
        size >= 1_000L -> trim1(size / 1_000.0) + " kB"
        else -> "$size B"
    }
}

/**
 * 由 store 数据组装下拉行。参数取基本类型（manifest 读失败时由调用方传 null
 * 字段 → 回退 image_id，布局不塌陷）。
 */
fun buildImageRow(
    imageId: String,
    fileLength: Long,
    displayName: String?,
    variant: String?,
    identity: String?,
    activeId: String?,
    corrupt: Boolean = false,
    systemVersion: Long? = null,
): ImageRowUi = ImageRowUi(
    imageId = imageId,
    displayName = displayName ?: imageId,
    variant = variant,
    identity = identity,
    sizeLabel = formatBytes(fileLength),
    corrupt = corrupt,
    active = imageId == activeId,
    systemVersion = systemVersion,
)

/**
 * §6.1 catalog 发布点。M4 在应用内断点续传（§6.2）落地前，「下载」入口先用
 * 浏览器打开 Release 页（`catalog.json` 与镜像资产同处发布，§6.1 注释处示例 URL）；
 * 下载完成后走「从文件导入 .img」。M4 接管后此入口改为应用内下载。
 */
const val CATALOG_RELEASES_URL = "https://github.com/ltbkq/Podroid-Debian/releases/latest"
