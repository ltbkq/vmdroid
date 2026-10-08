package io.github.ltbkq.vmdroid.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    Scaffold(
        topBar = {
            VmdroidTopBar(
                title = stringResource(R.string.app_name),
                actions = {
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
                        HomeActionButtons(
                            isRunning = isRunning,
                            isStarting = isStarting,
                            isStopping = isStopping,
                            vmState = vmState,
                            onStart = { viewModel.startVmdroid() },
                            onStop = { viewModel.stopVm() },
                            onRestart = { viewModel.restartVm() },
                            onOpenTerminal = onNavigateToTerminal,
                            onBackup = onNavigateToContainerBackup,
                            onStatus = onNavigateToStatus,
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
                    HomeActionButtons(
                        isRunning = isRunning,
                        isStarting = isStarting,
                        isStopping = isStopping,
                        vmState = vmState,
                        onStart = { viewModel.startVmdroid() },
                        onStop = { viewModel.stopVm() },
                        onRestart = { viewModel.restartVm() },
                        onOpenTerminal = onNavigateToTerminal,
                        onBackup = onNavigateToContainerBackup,
                        onStatus = onNavigateToStatus,
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenTerminal: () -> Unit,
    onBackup: () -> Unit,
    onStatus: () -> Unit,
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
