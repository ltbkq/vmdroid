/*
 * VMDroid - system images management screen (DESIGN §8.3).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * §8.3 排版规则：卡片 = 已安装镜像（可操作），列表行 = 在线目录（只读 +
 * 下载），两类用分区标题 + 分隔线区分不混排；卡片信息顺序恒为
 * 名称 → 版本/体积 → 状态（颜色+图标+文字三重编码）→ 操作（§8.0②）。
 * 每屏只有一个 FilledButton：首个非激活卡的「设为启动镜像」；完全无镜像
 * 时整块换空态，FilledButton 换成「下载」（§8.4 空态双入口）。
 *
 * 四态（§8.0④）：加载 = 目录/列表进度；禁用 = VM 运行中（提示
 * 「请先停止虚拟机」）与非 Wi-Fi 下载（文案「仅 Wi-Fi」）；错误 =
 * 目录/列表 inline error（不塌陷布局）；正常 = 上述全部可点。
 */
package io.github.ltbkq.vmdroid.ui.screens.images

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.systemimage.CatalogEntry
import io.github.ltbkq.vmdroid.ui.components.AdaptiveContainer
import io.github.ltbkq.vmdroid.ui.components.ResetConfirmDialog
import io.github.ltbkq.vmdroid.ui.components.VmdroidPrimaryButton
import io.github.ltbkq.vmdroid.ui.components.VmdroidSectionLabel
import io.github.ltbkq.vmdroid.ui.components.VmdroidTopBar
import io.github.ltbkq.vmdroid.ui.screens.home.CATALOG_RELEASES_URL
import io.github.ltbkq.vmdroid.ui.screens.home.ImageRowUi
import io.github.ltbkq.vmdroid.ui.screens.home.formatBytes
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens

@Composable
fun ImagesScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: ImagesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val vmBusy by viewModel.vmBusy.collectAsStateWithLifecycle()

    // §6.3 SAF 导入（与 Home 同链路：*/* → install() 拒非 .img 并给 mkimg 指引）
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::importImage)
    }

    // 回到前台补扫（Settings 的仅 Wi-Fi 开关、外部导入的同步；断点补扫在 refresh 内）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // §8.5 激活冲突对话框（共享件）
    state.resetPrompt?.let { prompt ->
        ResetConfirmDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmReset,
            onDismiss = viewModel::cancelReset,
        )
    }

    // 移除二次确认（§8.0 破坏性操作）
    state.removePromptId?.let { id ->
        state.row(id)?.let { row ->
            RemoveConfirmDialog(
                row = row,
                onConfirm = viewModel::confirmRemove,
                onDismiss = viewModel::cancelRemove,
            )
        }
    }

    if (state.factoryResetPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::cancelFactoryReset,
            title = { Text(stringResource(R.string.images_factory_confirm_title)) },
            text = { Text(stringResource(R.string.images_factory_confirm_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFactoryReset) {
                    Text(
                        stringResource(R.string.images_factory_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelFactoryReset) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 订阅设置（§6.1 catalog URL）
    if (state.showSubscribe) {
        var url by remember(state.catalogUrl) { mutableStateOf(state.catalogUrl) }
        AlertDialog(
            onDismissRequest = viewModel::closeSubscribe,
            title = { Text(stringResource(R.string.images_subscribe)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.images_subscribe_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setCatalogUrl(url) }) {
                    Text(stringResource(R.string.images_subscribe_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeSubscribe) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            VmdroidTopBar(
                title = stringResource(R.string.images_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(VmdroidTokens.Spacing.XL),
                verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.MD),
            ) {
                // 一次性提示（导入 / 激活 / 校验 / 删除结果），动作前由 VM 清空
                state.message?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // §8.3 禁用态：VM 运行中 → 动作禁用 + 说明（v1 不做热切换）
                if (vmBusy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.images_stop_vm_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── 已安装（卡片） ─────────────────────────────────
                VmdroidSectionLabel(stringResource(R.string.images_installed_section))

                val firstNonActiveId = state.rows.firstOrNull { !it.active }?.imageId
                when {
                    state.installedLoading && state.rows.isEmpty() -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(VmdroidTokens.Spacing.LG),
                        )
                    }

                    state.installedFailed && state.rows.isEmpty() -> {
                        // §8.0④ 错误态：inline error + 可执行动作，布局不塌陷
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.image_list_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.images_refresh))
                            }
                        }
                    }

                    state.rows.isEmpty() -> {
                        // §8.4 空态双入口（Filled = 下载；本屏无卡片 → 不违单 Filled 规则）
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
                        ) {
                            Text(
                                text = stringResource(R.string.images_empty),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            VmdroidPrimaryButton(
                                text = stringResource(R.string.image_download_first),
                                onClick = {
                                    val entry = state.catalog?.firstOrNull()
                                    if (entry != null) viewModel.download(entry)
                                    else openCatalogReleases(context)
                                },
                            )
                            TextButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.image_import_entry))
                            }
                        }
                    }

                    else -> {
                        state.rows.forEach { row ->
                            InstalledImageCard(
                                row = row,
                                primaryFilled = row.imageId == firstNonActiveId,
                                vmBusy = vmBusy,
                                cardBusy = row.imageId in state.busyIds,
                                verifying = row.imageId == state.verifyingId,
                                anyVerifying = state.verifyingId != null,
                                onActivate = { viewModel.activate(row.imageId) },
                                onVerify = { viewModel.verify(row.imageId) },
                                onFactoryReset = viewModel::promptFactoryReset,
                                onRemove = { viewModel.promptRemove(row.imageId) },
                            )
                        }
                    }
                }

                if (state.installedFailed && state.rows.isNotEmpty()) {
                    // 列表保留上次值 + 顶部 inline error（§8.2 同款，不塌陷）
                    Text(
                        text = stringResource(R.string.image_list_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                // ── 在线目录（行） ─────────────────────────────────
                VmdroidSectionLabel(stringResource(R.string.images_catalog_section))

                when {
                    state.catalogLoading && state.catalog == null -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.images_catalog_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    state.catalogError != null -> {
                        Text(
                            text = state.catalogError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    state.catalog.isNullOrEmpty() -> {
                        Text(
                            text = stringResource(R.string.images_catalog_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        state.catalog.orEmpty().forEach { entry ->
                            CatalogRow(
                                entry = entry,
                                wifiBlocked = state.wifiBlocked,
                                download = state.downloads[entry.imageId],
                                onDownload = { viewModel.download(entry) },
                                onCancel = { viewModel.cancelDownload(entry.imageId) },
                            )
                        }
                    }
                }

                HorizontalDivider()

                // ── 底部动作（均 TextButton / 图标按钮） ─────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
                ) {
                    TextButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !state.importing,
                    ) {
                        if (state.importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.images_import))
                        }
                    }
                    TextButton(onClick = viewModel::refresh, enabled = !state.catalogLoading) {
                        Text(stringResource(R.string.images_refresh))
                    }
                    TextButton(onClick = viewModel::openSubscribe) {
                        Text(stringResource(R.string.images_subscribe))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 已安装卡片

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledImageCard(
    row: ImageRowUi,
    /** 本屏唯一 FilledButton 归属（首个非激活卡的「设为启动镜像」）。 */
    primaryFilled: Boolean,
    vmBusy: Boolean,
    cardBusy: Boolean,
    verifying: Boolean,
    anyVerifying: Boolean,
    onActivate: () -> Unit,
    onVerify: () -> Unit,
    onFactoryReset: () -> Unit,
    onRemove: () -> Unit,
) {
    val actionsEnabled = !vmBusy && !cardBusy
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(VmdroidTokens.Spacing.LG),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
        ) {
            // ① 名称
            Text(
                text = row.line1,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            // ② image_id · 体积
            Text(
                text = row.line2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ② 版本信息（identity · system_version）
            Text(
                text = row.versionLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ③ 状态（颜色 + 图标 + 文字三重编码）
            Row(
                horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    row.corrupt -> {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.image_corrupt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    row.active -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.images_status_active),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    else -> {
                        Icon(
                            Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.images_installed_section),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (cardBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // 校验中：20dp 进度圈 + 文字「校验中」，控件禁用
            if (verifying) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.images_verifying),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ④ 操作（卡片内均 TextButton；FilledButton 全屏唯一）
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
            ) {
                if (row.active) {
                    TextButton(
                        onClick = onVerify,
                        enabled = actionsEnabled && !anyVerifying,
                    ) {
                        Text(stringResource(R.string.images_verify_now))
                    }
                    TextButton(onClick = onFactoryReset, enabled = actionsEnabled) {
                        Text(stringResource(R.string.images_factory_reset))
                    }
                    TextButton(onClick = onRemove, enabled = !cardBusy) {
                        Text(
                            text = stringResource(R.string.images_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    if (primaryFilled) {
                        Button(
                            onClick = onActivate,
                            enabled = actionsEnabled,
                            contentPadding = PaddingValues(
                                horizontal = VmdroidTokens.Spacing.MD,
                                vertical = VmdroidTokens.Spacing.XS,
                            ),
                        ) {
                            Text(stringResource(R.string.images_set_active))
                        }
                    } else {
                        TextButton(onClick = onActivate, enabled = actionsEnabled) {
                            Text(stringResource(R.string.images_set_active))
                        }
                    }
                    TextButton(
                        onClick = onVerify,
                        enabled = actionsEnabled && !anyVerifying,
                    ) {
                        Text(stringResource(R.string.images_verify))
                    }
                    TextButton(onClick = onRemove, enabled = !cardBusy) {
                        Text(
                            text = stringResource(R.string.images_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 在线目录行

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    wifiBlocked: Boolean,
    download: DownloadRowUi?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val line1 = buildString {
        append(entry.displayName)
        if (!entry.variant.isNullOrBlank()) append(" · ").append(entry.variant)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
        ) {
            Text(text = line1, style = MaterialTheme.typography.titleSmall)
            Text(
                text = buildString {
                    append(formatBytes(entry.size))
                    append(" · sha256 ✓")
                    if (entry.systemVersion != null) append(" · v").append(entry.systemVersion)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (download) {
            is DownloadRowUi.Running -> {
                // 行内进度条 + %/速度/剩余；点击进度区取消（.part 保留供续传）
                Column(
                    modifier = Modifier
                        .width(150.dp)
                        .clickable(onClick = onCancel),
                    verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                ) {
                    LinearProgressIndicator(
                        progress = { download.progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val p = download.progress
                        val etaLabel = if (p.etaSeconds >= 0) {
                            stringResource(R.string.images_eta, formatEta(p.etaSeconds))
                        } else {
                            null
                        }
                        Text(
                            text = buildString {
                                append(p.percent).append('%')
                                if (p.bytesPerSec > 0) {
                                    append(" · ").append(formatBytes(p.bytesPerSec)).append("/s")
                                }
                                if (etaLabel != null) {
                                    append(" · ").append(etaLabel)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is DownloadRowUi.Failed -> {
                // 「下载失败，点击重试」= 可执行文案（§8.0④）
                Text(
                    text = stringResource(R.string.images_download_retry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDownload),
                )
            }

            null -> {
                if (wifiBlocked) {
                    // 禁用态文案「仅 Wi-Fi」（§6.2 计量网络门控）
                    Text(
                        text = stringResource(R.string.images_wifi_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.download))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 移除确认

@Composable
private fun RemoveConfirmDialog(
    row: ImageRowUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.images_remove_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS)) {
                Text(stringResource(R.string.images_remove_confirm_body, row.imageId))
                if (row.active) {
                    Text(
                        text = stringResource(R.string.images_remove_active_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.images_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// ---------------------------------------------------------------- 工具

/** §6.2 进度剩余时间（`12s` / `3m42s` / `1h05m`，供 images_eta 占位）。 */
private fun formatEta(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600}h${(sec % 3600) / 60}m"
    sec >= 60 -> "${sec / 60}m${sec % 60}s"
    else -> "${sec}s"
}

/** §6.1 目录入口兜底：无目录条目时用浏览器打开 Release 页（同 Home）。 */
private fun openCatalogReleases(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CATALOG_RELEASES_URL)))
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.image_err_generic, it.message),
            Toast.LENGTH_LONG,
        ).show()
    }
}
