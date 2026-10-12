package io.github.ltbkq.vmdroid.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens

/**
 * §7.1 启动过程可视化 —— 展示模型与 Home 启动日志面板。
 *
 * 数据源是 `VmEngine.consoleText`（全量累积串，§7.1 第 5 条：本期不改引擎
 * 语义），HomeViewModel 侧做 conflate + ~200ms 节流后取尾，这里只负责渲染：
 * 启动中显示实时尾巴，Running/Stopped/Error 收起为一行摘要。
 */

/** 尾巴最多保留的字符数（全量 consoleText 可能是 MB 级，只看最后一段）。 */
private const val BOOT_LOG_TAIL_CHARS = 1500

/** 面板最多渲染的行数（短行日志也不会把面板撑爆）。 */
private const val BOOT_LOG_TAIL_LINES = 12

/** 节流窗口：UI 至多 5Hz 拿到一次新尾巴，避免每个 chunk 触发重组。 */
const val BOOT_LOG_SAMPLE_MS = 200L

/** 面板高度上限（12 行等宽小字 + 内边距；超出由反向 LazyColumn 滚动兜底）。 */
private val BootLogPanelMaxHeight = 200.dp

/**
 * 启动日志"尾巴"—— UI 只看这一小段，绝不携带全量串。
 *
 * @param lines 末尾 ≤12 行、**旧 → 新**；面板喂给反向 LazyColumn 时再倒序
 * @param lineCount 全文行数（收起态「N 行日志」摘要用）
 */
data class BootLogTail(
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
) {
    val isEmpty: Boolean get() = lineCount == 0

    companion object {
        val EMPTY = BootLogTail()
    }
}

/**
 * Home 启动日志面板的展示模型（HomeViewModel.bootLog 产出，HomeScreen 只渲染）。
 *
 * @param tail 面板要显示的尾巴：live 时是实时的，否则是最近一次启动的冻结快照
 * @param live true = 启动中实时跟随 consoleText
 * @param collapsed true = 只显示一行摘要（点行展开）
 * @param hasLog false 时整个面板不出现（还没产出任何日志）
 */
data class BootLogUi(
    val tail: BootLogTail = BootLogTail.EMPTY,
    val live: Boolean = false,
    val collapsed: Boolean = false,
    val hasLog: Boolean = false,
) {
    companion object {
        val EMPTY = BootLogUi()
    }
}

/**
 * 从全量 consoleText 取尾巴。顺序不能反，且不许用正则按行全扫：
 * 1. 先 `takeLast(1500)` 截字符 —— O(尾长)，不随总量增长；
 * 2. 行数用 `count { it == '\n' }` 单次遍历（引擎侧现有 64KB 环形上限，
 *    即便将来变 MB 级也只是一次线性扫描）；
 * 3. 截完的尾巴再按行切、只留末尾 12 行（1.5KB 内的 `split` 只碰这一段）。
 */
fun buildBootLogTail(raw: String): BootLogTail {
    if (raw.isEmpty()) return BootLogTail.EMPTY
    val lineCount = raw.count { it == '\n' } + if (raw.endsWith('\n')) 0 else 1
    val split = raw.takeLast(BOOT_LOG_TAIL_CHARS).split('\n')
    // takeLast 可能切在行中间；split 对结尾换行还会多出一个空串。
    val trimmed = if (split.lastOrNull()?.isEmpty() == true) split.dropLast(1) else split
    val lines = if (trimmed.size > BOOT_LOG_TAIL_LINES) trimmed.takeLast(BOOT_LOG_TAIL_LINES) else trimmed
    return BootLogTail(lines = lines, lineCount = lineCount)
}

/**
 * §7.1 启动过程可视化：Home 启动状态区的日志面板。
 *
 * 与上面的 `bootStage` 阶段标签是并列的两种语义：阶段 = 进度（一行文字），
 * 日志 = 明细（等宽小字尾巴）。启动中实时滚动；Running/Stopped/Error 收起
 * 成一行摘要，点开可看最近一次启动的尾巴。
 *
 * @param isRunning 摘要文案按状态区分（"启动完成" vs "上次启动"）
 */
@Composable
fun HomeBootLogPanel(
    bootLog: BootLogUi,
    isRunning: Boolean,
    onToggle: () -> Unit,
) {
    if (!bootLog.hasLog) return

    Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
    val summary = stringResource(
        if (isRunning) R.string.boot_log_summary_ok else R.string.boot_log_summary_last,
        bootLog.tail.lineCount,
    )

    if (bootLog.collapsed) {
        // 收起态：一行摘要，整行可点开（Running 后日志不再滚动，占位越小越好）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 展开态：表头（实时 = 标题 + 阶段标签在上一行；冻结 = 摘要文案，可收起）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (bootLog.live) Modifier else Modifier.clickable(onClick = onToggle)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (bootLog.live) stringResource(R.string.boot_log_live) else summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!bootLog.live) {
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(VmdroidTokens.Spacing.XS))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = BootLogPanelMaxHeight)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(VmdroidTokens.Radius.Chip),
            )
            .padding(VmdroidTokens.Spacing.SM),
    ) {
        // 反向列表：item[0]（最新行）锚在可视底部，新内容到来时旧行整体向上推，
        // 新行常驻可视底部 —— 这是 Compose 里最省的"自动滚到底"写法，
        // 无需 LaunchedEffect + scrollToItem，也只组合可见的十几行。
        LazyColumn(
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(bootLog.tail.lines.asReversed()) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
