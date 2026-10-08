package io.github.ltbkq.vmdroid.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ltbkq.vmdroid.BuildConfig
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.engine.VmState
import io.github.ltbkq.vmdroid.engine.avf.AvfFailureGuidance
import io.github.ltbkq.vmdroid.service.VmdroidService
import io.github.ltbkq.vmdroid.ui.components.AdaptiveContainer
import io.github.ltbkq.vmdroid.ui.components.PermissionRows
import io.github.ltbkq.vmdroid.ui.components.ResetConfirmDialog
import io.github.ltbkq.vmdroid.ui.components.VmdroidDestructiveButton
import io.github.ltbkq.vmdroid.ui.components.VmdroidGhostButton
import io.github.ltbkq.vmdroid.ui.components.VmdroidListRow
import io.github.ltbkq.vmdroid.ui.components.VmdroidPrimaryButton
import io.github.ltbkq.vmdroid.ui.components.VmdroidSectionLabel
import io.github.ltbkq.vmdroid.ui.components.VmdroidStatus
import io.github.ltbkq.vmdroid.ui.components.VmdroidStatusColors
import io.github.ltbkq.vmdroid.ui.components.VmdroidTopBar
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens
import io.github.ltbkq.vmdroid.util.AppPermission
import io.github.ltbkq.vmdroid.util.AppPermissions
import io.github.ltbkq.vmdroid.util.isGranted
import io.github.ltbkq.vmdroid.util.missing
import io.github.ltbkq.vmdroid.util.openAppDetailsSettings
import io.github.ltbkq.vmdroid.util.requestBatteryOptimizationExemption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStatus: () -> Unit,
    onNavigateToContainerBackup: () -> Unit,
    onNavigateToImages: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val bootStage by viewModel.bootStage.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val uptimeTick by viewModel.uptimeTicker.collectAsStateWithLifecycle()
    val showAvfHint by viewModel.showAvfHint.collectAsStateWithLifecycle()
    val showBackendFallbackBanner by viewModel.backendFallbackBanner.collectAsStateWithLifecycle()
    val avfBootFailure by viewModel.avfBootFailure.collectAsStateWithLifecycle()
    val avfFailureAdvice by viewModel.avfFailureAdvice.collectAsStateWithLifecycle()
    val avfNetWorkaroundFailed by viewModel.avfNetWorkaroundFailed.collectAsStateWithLifecycle()
    val stopping by viewModel.stopping.collectAsStateWithLifecycle()
    val containerCount by viewModel.containerCount.collectAsStateWithLifecycle()

    val isRunning  = vmState is VmState.Running
    val isStarting = vmState is VmState.Starting
    // Stop is asynchronous: the engine stays Running/Starting until teardown
    // finishes, so gate the indicator on the engine's stopping signal while the
    // state is still active. The instant state goes terminal, this falls back to
    // normal Stopped/Error rendering regardless of signal/state flip ordering.
    val isStopping = stopping && (isRunning || isStarting)
    val uptimeLabel = viewModel.uptimeLabel(uptimeTick)
    // Cache the ConnectivityManager binder call so it doesn't re-run on every
    // 1 Hz tick or incidental recomposition, but refresh it on ON_RESUME so a
    // Wi-Fi / hotspot change (the headline SSH use case) shows the new address
    // instead of a stale one for the screen's lifetime.
    var phoneIp by remember { mutableStateOf(viewModel.phoneIp()) }
    // Bumped on ON_RESUME (and after a grant attempt) so the missing-permissions
    // list is re-read from the live OS grant state, e.g. after the user comes
    // back from the system settings screen.
    var permissionsGrantVersion by remember { mutableIntStateOf(0) }
    val missingPermissions = remember(permissionsGrantVersion) { AppPermissions.missing(context) }
    var permissionsCardDismissed by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                phoneIp = viewModel.phoneIp()
                permissionsGrantVersion++
                // §8.2：回到前台重读 images/（外部导入/删除、别处激活的同步）
                viewModel.refreshImages()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (!granted) {
            val activity = context as? Activity
            if (activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                AppPermissions.openAppDetailsSettings(context)
            }
        }
        permissionsGrantVersion++
    }
    fun grantPermission(permission: AppPermission) {
        when (permission) {
            AppPermission.NOTIFICATIONS -> notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            AppPermission.BATTERY_OPTIMIZATION -> AppPermissions.requestBatteryOptimizationExemption(context)
        }
    }

    // ---- §8.2 启动镜像选择（状态 + 入口） ----
    val imageState by viewModel.images.collectAsStateWithLifecycle()
    val resetPrompt by viewModel.resetPrompt.collectAsStateWithLifecycle()

    // §6.3 SAF 导入：选任意文件（content:// 流交给 install() 校验；非 .img 由
    // codec 拒绝并给出 mkimg 封装指引 —— §13.2「导入非 .img」用例）
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let(viewModel::importImage)
    }

    // §6.1 下载入口：M4 应用内断点续传落地前，先用浏览器打开 Release 页
    // （catalog.json 与镜像资产同处发布）；下载完成后走「从文件导入」。
    fun openCatalog() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CATALOG_RELEASES_URL)))
        }.onFailure {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.image_err_generic, it.message),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            icon  = { Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.update_available)) },
            title = { Text(stringResource(R.string.update_available)) },
            text  = { Text(stringResource(R.string.version_available, info.latestVersion, BuildConfig.VERSION_NAME)) },
            confirmButton = {
                TextButton(onClick = {
                    // Guard against ActivityNotFoundException (no browser) or a
                    // blank/malformed releaseUrl from the remote JSON source.
                    // Surface a toast on failure so the tap isn't a silent no-op.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    }.onFailure {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.update_open_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    viewModel.dismissUpdate()
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) { Text(stringResource(R.string.later)) }
            },
        )
    }

    // §8.5 激活冲突对话框（identity/contract/init 重置确认；共享件 ui/components/ResetConfirmDialog）
    resetPrompt?.let { prompt ->
        ResetConfirmDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmReset,
            onDismiss = viewModel::cancelReset,
        )
    }

    Scaffold(
        topBar = {
            VmdroidTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onNavigateToImages) {
                        Icon(Icons.Default.Storage, contentDescription = stringResource(R.string.images_title))
                    }
                    IconButton(onClick = onNavigateToStatus) {
                        Icon(Icons.Default.MonitorHeart, contentDescription = stringResource(R.string.status_page_title))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = if (isCompactHeight) 900 else 600,
        ) {
            if (isCompactHeight) {
                // Landscape phone / split-screen: hero on left, action column on right.
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = VmdroidTokens.Spacing.XL2, vertical = VmdroidTokens.Spacing.LG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = VmdroidTokens.Spacing.XL2)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (missingPermissions.isNotEmpty() && !permissionsCardDismissed) {
                            PermissionsNeededCard(
                                permissions = missingPermissions,
                                onGrant = ::grantPermission,
                                onDismiss = { permissionsCardDismissed = true },
                            )
                        }
                        if (showAvfHint) {
                            AvfHintBanner(onDismiss = { viewModel.dismissAvfHint() })
                        }
                        if (showBackendFallbackBanner) {
                            AvfFallbackBanner()
                        }
                        HomeStatusBlock(
                            isStarting, isRunning, isStopping, vmState, bootStage, meta, uptimeLabel,
                            containerCount = containerCount,
                            avfBootFailure = avfBootFailure,
                            avfFailureAdvice = avfFailureAdvice,
                            avfNetWorkaroundFailed = avfNetWorkaroundFailed,
                            onUseOneCore = { viewModel.useOneCoreAndRetry() },
                            onSwitchToQemu = { viewModel.switchToQemuAndRetry() },
                            onRetry = { viewModel.restartVm() },
                        )
                        HomeDataSection(
                            isRunning, isStopping, vmState, meta, phoneIp, containerCount,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
                    ) {
                        BootImageSelector(
                            state = imageState,
                            running = isRunning || isStarting || isStopping,
                            onSelect = viewModel::onImageSelected,
                            onImport = { importLauncher.launch(arrayOf("*/*")) },
                            onDownload = { openCatalog() },
                        )
                        HomeActionButtons(
                            isRunning = isRunning,
                            isStarting = isStarting,
                            isStopping = isStopping,
                            vmState = vmState,
                            startMode = startButtonMode(imageState),
                            onStart = { viewModel.startVmdroid() },
                            onStop = { viewModel.stopVm() },
                            onRestart = { viewModel.restartVm() },
                            onOpenTerminal = onNavigateToTerminal,
                            onBackup = onNavigateToContainerBackup,
                            onStatus = onNavigateToStatus,
                            onReverify = viewModel::reverifyImage,
                            onRedownload = { openCatalog() },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = VmdroidTokens.Spacing.XL),
                    verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.MD),
                ) {
                    Spacer(Modifier.height(VmdroidTokens.Spacing.XL))
                    if (missingPermissions.isNotEmpty() && !permissionsCardDismissed) {
                        PermissionsNeededCard(
                            permissions = missingPermissions,
                            onGrant = ::grantPermission,
                            onDismiss = { permissionsCardDismissed = true },
                        )
                    }
                    if (showAvfHint) {
                        AvfHintBanner(onDismiss = { viewModel.dismissAvfHint() })
                    }
                    if (showBackendFallbackBanner) {
                        AvfFallbackBanner()
                    }
                    HomeStatusBlock(
                        isStarting = isStarting,
                        isRunning = isRunning,
                        isStopping = isStopping,
                        vmState = vmState,
                        bootStage = bootStage,
                        meta = meta,
                        uptimeLabel = uptimeLabel,
                        containerCount = containerCount,
                        avfBootFailure = avfBootFailure,
                        avfFailureAdvice = avfFailureAdvice,
                        avfNetWorkaroundFailed = avfNetWorkaroundFailed,
                        onUseOneCore = { viewModel.useOneCoreAndRetry() },
                        onSwitchToQemu = { viewModel.switchToQemuAndRetry() },
                        onRetry = { viewModel.restartVm() },
                    )
                    HomeDataSection(isRunning, isStopping, vmState, meta, phoneIp, containerCount)
                    Spacer(Modifier.weight(1f))
                    BootImageSelector(
                        state = imageState,
                        running = isRunning || isStarting || isStopping,
                        onSelect = viewModel::onImageSelected,
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onDownload = { openCatalog() },
                    )
                    HomeActionButtons(
                        isRunning = isRunning,
                        isStarting = isStarting,
                        isStopping = isStopping,
                        vmState = vmState,
                        startMode = startButtonMode(imageState),
                        onStart = { viewModel.startVmdroid() },
                        onStop = { viewModel.stopVm() },
                        onRestart = { viewModel.restartVm() },
                        onOpenTerminal = onNavigateToTerminal,
                        onBackup = onNavigateToContainerBackup,
                        onStatus = onNavigateToStatus,
                        onReverify = viewModel::reverifyImage,
                        onRedownload = { openCatalog() },
                    )
                    Spacer(Modifier.height(VmdroidTokens.Spacing.XL))
                }
            }
        }
    }
}

@Composable
private fun PermissionsNeededCard(
    permissions: List<AppPermission>,
    onGrant: (AppPermission) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VmdroidTokens.Spacing.MD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(VmdroidTokens.Spacing.MD),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        ) {
            Text(
                stringResource(R.string.permissions_needed),
                style = MaterialTheme.typography.titleSmall,
            )
            PermissionRows(
                permissions = permissions,
                isGranted = { it.isGranted(context) },
                onGrant = onGrant,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.not_now))
                }
            }
        }
    }
}

@Composable
private fun AvfHintBanner(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VmdroidTokens.Spacing.MD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(VmdroidTokens.Spacing.MD),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        ) {
            Text(
                stringResource(R.string.avf_available),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.avf_hint_needs_pc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.avf_grant_commands),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}

/** Passive - no dismiss, no actions - just says why AVF (the selected
 *  backend) is not what's actually running. See #66. */
@Composable
private fun AvfFallbackBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VmdroidTokens.Spacing.MD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = stringResource(R.string.backend_fallback_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(VmdroidTokens.Spacing.MD),
        )
    }
}

@Composable
private fun HomeStatusBlock(
    isStarting: Boolean,
    isRunning: Boolean,
    isStopping: Boolean,
    vmState: VmState,
    bootStage: String,
    meta: HomeMeta,
    uptimeLabel: String?,
    containerCount: Int? = null,
    avfBootFailure: Boolean = false,
    avfFailureAdvice: AvfFailureGuidance.Advice = AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
    avfNetWorkaroundFailed: Boolean = false,
    onUseOneCore: () -> Unit = {},
    onSwitchToQemu: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    VmdroidSectionLabel(stringResource(R.string.vm_status))
    val statusText = when {
        isStopping -> stringResource(R.string.status_stopping)
        isStarting -> stringResource(R.string.status_starting)
        isRunning  -> stringResource(R.string.status_running)
        else       -> stringResource(R.string.status_stopped)
    }
    val statusColor = when {
        isStopping -> MaterialTheme.colorScheme.tertiary
        isRunning  -> MaterialTheme.colorScheme.primary
        isStarting -> MaterialTheme.colorScheme.tertiary
        else       -> MaterialTheme.colorScheme.onSurface
    }
    AnimatedContent(targetState = statusText to statusColor, label = "home_vm_status") { (text, color) ->
        Text(
            text = text,
            style = MaterialTheme.typography.displayLarge,
            color = color,
        )
    }
    Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isStopping) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(VmdroidTokens.Spacing.SM))
                Text(
                    text = stringResource(R.string.stopping_vm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val (dot, label) = when {
                isRunning  -> VmdroidStatusColors.Running  to (uptimeLabel ?: stringResource(R.string.up))
                isStarting -> VmdroidStatusColors.Starting to bootStage.ifEmpty { stringResource(R.string.status_starting) }
                else       -> VmdroidStatusColors.Stopped  to stringResource(R.string.status_idle)
            }
            VmdroidStatus(label = label, dotColor = dot)
        }
        Text(
            text = meta.resourcesLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (avfNetWorkaroundFailed) {
        Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
        Text(
            text = stringResource(R.string.avf_net_workaround_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (!isRunning && !isStarting && !isStopping) {
        Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
        VmdroidSectionLabel(stringResource(R.string.home_containers_created))
        Text(
            text = containerCount?.let { stringResource(R.string.home_containers_count, it) }
                ?: stringResource(R.string.home_containers_unknown),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (vmState is VmState.Error) {
        Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
        VmdroidSectionLabel(stringResource(R.string.error_title))
        Text(
            text = vmState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (avfBootFailure) {
            Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
            Text(
                text = stringResource(
                    if (avfFailureAdvice == AvfFailureGuidance.Advice.TRY_ONE_CORE)
                        R.string.avf_boot_failed_try_one_core
                    else
                        R.string.avf_boot_failed_switch_qemu
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
            if (avfFailureAdvice == AvfFailureGuidance.Advice.TRY_ONE_CORE) {
                VmdroidPrimaryButton(
                    text = stringResource(R.string.avf_action_use_one_core),
                    onClick = onUseOneCore,
                )
                Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM)) {
                VmdroidGhostButton(
                    text = stringResource(R.string.avf_action_switch_qemu),
                    onClick = onSwitchToQemu,
                    modifier = Modifier.weight(1f),
                )
                VmdroidGhostButton(
                    text = stringResource(R.string.avf_action_retry),
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    // Starting state: the meta row already shows the amber dot + boot-stage
    // text, which is the canonical boot indicator. No need for a separate ring.
}

@Composable
private fun HomeDataSection(
    isRunning: Boolean,
    isStopping: Boolean,
    vmState: VmState,
    meta: HomeMeta,
    phoneIp: String,
    containerCount: Int?,
) {
    val showStarting = vmState is VmState.Starting
    val showError = vmState is VmState.Error
    if (showStarting || showError || isStopping) return
    Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
    if (isRunning) {
        VmdroidSectionLabel(stringResource(R.string.network))
        VmdroidListRow(
            label = stringResource(R.string.home_containers_created),
            value = containerCount?.toString() ?: stringResource(R.string.home_containers_unknown),
            mono = true,
        )
        VmdroidListRow(label = stringResource(R.string.phone_ip), value = phoneIp, mono = true)
        VmdroidListRow(
            label = stringResource(R.string.ssh),
            value = if (meta.sshEnabled) ":${VmdroidService.SSH_HOST_PORT} · podroid" else stringResource(R.string.off),
            mono = meta.sshEnabled,
        )
        VmdroidListRow(
            label = stringResource(R.string.port_forwards),
            value = if (meta.portForwardCount == 0) stringResource(R.string.none)
                    else "${meta.portForwardCount} ${stringResource(R.string.active)}",
        )
    } else {
        VmdroidSectionLabel(stringResource(R.string.last_session))
        if (meta.lastBootDurationMs > 0L) {
            VmdroidListRow(
                label = stringResource(R.string.booted_in),
                value = formatBootDuration(meta.lastBootDurationMs),
            )
        }
        VmdroidListRow(
            label = stringResource(R.string.build),
            value = "v${BuildConfig.VERSION_NAME} · QEMU ${BuildConfig.QEMU_VERSION}",
            mono = true,
        )
    }
}

private fun formatBootDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return if (totalSec >= 60) {
        val m = totalSec / 60
        val s = totalSec % 60
        if (s == 0L) "${m}m" else "${m}m ${s}s"
    } else {
        val tenths = (ms / 100) % 10
        if (tenths == 0L) "${totalSec}s" else "${totalSec}.${tenths}s"
    }
}

@Composable
private fun HomeActionButtons(
    isRunning: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    vmState: VmState,
    /** §8.2 驱动的启动按钮形态（仅停止态分支生效）。 */
    startMode: StartButtonMode,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenTerminal: () -> Unit,
    onBackup: () -> Unit,
    onStatus: () -> Unit,
    onReverify: () -> Unit,
    onRedownload: () -> Unit,
) {
    if (isStopping) {
        // Teardown in progress: one disabled affordance so the user can't
        // double-stop or start over a stop that's already running. The spinner
        // lives in the status block above.
        VmdroidPrimaryButton(
            text = stringResource(R.string.stopping_action),
            onClick = {},
            enabled = false,
        )
    } else if (isRunning) {
        VmdroidPrimaryButton(text = stringResource(R.string.open_terminal), onClick = onOpenTerminal)
        Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
        HomeQuickActions(onBackup = onBackup, onStatus = onStatus)
        Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
        Row(horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM)) {
            VmdroidGhostButton(text = stringResource(R.string.restart), onClick = onRestart, modifier = Modifier.weight(1f))
            VmdroidDestructiveButton(text = stringResource(R.string.stop), onClick = onStop, modifier = Modifier.weight(1f))
        }
    } else if (isStarting) {
        VmdroidDestructiveButton(text = stringResource(R.string.stop), onClick = onStop)
    } else if (startMode == StartButtonMode.HIDDEN) {
        // §8.2 完全无镜像：隐藏启动按钮（不显示必然失败的按钮），保留快捷入口。
        HomeQuickActions(onBackup = onBackup, onStatus = onStatus)
    } else if (startMode == StartButtonMode.REVERIFY) {
        // §8.2 CORRUPT：主按钮变「重新校验 / 重新下载」（校验通过后恢复启动）。
        VmdroidPrimaryButton(text = stringResource(R.string.image_reverify), onClick = onReverify)
        Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
        VmdroidGhostButton(text = stringResource(R.string.image_redownload), onClick = onRedownload)
        Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
        HomeQuickActions(onBackup = onBackup, onStatus = onStatus)
    } else if (vmState is VmState.Error) {
        VmdroidPrimaryButton(text = stringResource(R.string.try_again), onClick = onStart)
        Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
        HomeQuickActions(onBackup = onBackup, onStatus = onStatus)
    } else {
        VmdroidPrimaryButton(text = stringResource(R.string.start_vm), onClick = onStart)
        Spacer(Modifier.height(VmdroidTokens.Spacing.SM))
        HomeQuickActions(onBackup = onBackup, onStatus = onStatus)
    }
}

@Composable
private fun HomeQuickActions(
    onBackup: () -> Unit,
    onStatus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
    ) {
        VmdroidGhostButton(
            text = stringResource(R.string.home_action_backup),
            onClick = onBackup,
            modifier = Modifier.weight(1f),
        )
        VmdroidGhostButton(
            text = stringResource(R.string.home_action_status),
            onClick = onStatus,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * §8.2 启动镜像选择控件（Home 页、启动按钮上方）。
 *
 * 行为表八态：运行中禁用并说明 / 判据相同直切 / 需重置弹 §8.5 / 仅 1 镜像仍显示
 * （下拉含导入、下载入口）/ 校验中 spinner + 文字 / CORRUPT 三重编码（error 色 +
 * ⚠ + 文字）/ 空态双入口（启动按钮随 [StartButtonMode.HIDDEN] 隐藏）/
 * 加载失败保留上次值 + 顶部 inline error。
 */
@Composable
private fun BootImageSelector(
    state: HomeImageState,
    running: Boolean,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM)) {
        VmdroidSectionLabel(stringResource(R.string.boot_image_label))

        when (state) {
            is HomeImageState.Loading -> {
                // 初始读取中：占位禁用字段（布局与就绪态同高，不塌陷）
                OutlinedTextField(
                    value = stringResource(R.string.boot_image_placeholder),
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is HomeImageState.Loaded -> {
                if (state.loadFailed) {
                    // §8.2「加载失败」：顶部 inline error，选择器保留上次值
                    Text(
                        text = stringResource(R.string.image_list_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.isEmpty) {
                    // §8.2 空态：整块替换为双入口（启动按钮由 HomeActionButtons 隐藏）
                    Card(colors = CardDefaults.outlinedCardColors()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(VmdroidTokens.Spacing.LG),
                            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.MD),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    stringResource(R.string.boot_image_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            VmdroidPrimaryButton(
                                text = stringResource(R.string.image_download_first),
                                onClick = onDownload,
                            )
                            TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.image_import_entry))
                            }
                        }
                    }
                } else {
                    BootImageDropdown(
                        state = state,
                        running = running,
                        onSelect = onSelect,
                        onImport = onImport,
                        onDownload = onDownload,
                    )
                    // 字段下方：常规 = line2（image_id · 体积）；损坏 = 三重编码行
                    val displayed = state.row(state.displayedId)
                    if (displayed != null) {
                        if (state.displayedCorrupt) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "${displayed.line2} · ${stringResource(R.string.image_corrupt)}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            Text(
                                text = displayed.line2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // 一次性提示（激活 / 导入 / 校验结果），下一次动作前由 VM 清空
                state.message?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** §8.2 下拉选择器（Material 3 `ExposedDropdownMenuBox`：一眼看出当前跑哪个系统）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BootImageDropdown(
    state: HomeImageState.Loaded,
    running: Boolean,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val checking = state.checkingId != null
    val enabled = !running && !checking
    val displayed = state.row(state.displayedId)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = displayed?.line1 ?: stringResource(R.string.boot_image_placeholder),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            isError = displayed?.corrupt == true,
            colors = if (displayed?.corrupt == true) {
                TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.error,
                    unfocusedTextColor = MaterialTheme.colorScheme.error,
                )
            } else {
                TextFieldDefaults.colors()
            },
            trailingIcon = {
                if (checking) {
                    // §8.2「校验中」：20dp spinner（文字在 supportingText）
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            supportingText = if (checking || running) {
                {
                    Text(
                        stringResource(
                            if (checking) R.string.boot_image_checking else R.string.boot_image_running_hint,
                        ),
                    )
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )

        if (enabled) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.rows.forEach { row ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.XS),
                                ) {
                                    if (row.corrupt) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Text(
                                        text = row.line1,
                                        color = if (row.corrupt) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Text(
                                    text = row.line2,
                                    color = if (row.corrupt) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (row.corrupt) {
                                    Text(
                                        text = stringResource(R.string.image_corrupt),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        },
                        trailingIcon = if (row.imageId == state.displayedId) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onSelect(row.imageId)
                        },
                    )
                }
                // 导入 / 下载入口常驻（§8.2「仅 1 个镜像」行：仍显示 + 下拉项含入口）
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.image_import_entry)) },
                    onClick = {
                        expanded = false
                        onImport()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.image_download_more)) },
                    onClick = {
                        expanded = false
                        onDownload()
                    },
                )
            }
        }
    }
}
