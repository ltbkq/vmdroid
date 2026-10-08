package io.github.ltbkq.vmdroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens
import io.github.ltbkq.vmdroid.util.DeviceResourcePolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmRamChips(
    currentMb: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.padding(bottom = VmdroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.ram_label)}  ·  ${formatRam(currentMb)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = VmdroidTokens.Spacing.MD,
                bottom = VmdroidTokens.Spacing.SM,
            ),
        )
        val totalRamMb = DeviceResourcePolicy.deviceTotalRamMb(LocalContext.current)
        val ramOptions = DeviceResourcePolicy.ramOptionsFor(totalRamMb).let { options ->
            if (currentMb in options) options else options + currentMb
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        ) {
            ramOptions.forEach { mb ->
                FilterChip(
                    selected = mb == currentMb,
                    enabled = enabled,
                    onClick = { onChange(mb) },
                    label = { Text(formatRam(mb)) },
                    shape = RoundedCornerShape(VmdroidTokens.Radius.Chip),
                    colors = VmdroidChipColors(),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = VmdroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmCpuChips(
    currentCpus: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.padding(bottom = VmdroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.cpu_cores)}  ·  $currentCpus",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = VmdroidTokens.Spacing.MD,
                bottom = VmdroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        ) {
            DeviceResourcePolicy.CPU_OPTIONS.forEach { n ->
                FilterChip(
                    selected = n == currentCpus,
                    enabled = enabled,
                    onClick = { onChange(n) },
                    label = { Text("$n") },
                    shape = RoundedCornerShape(VmdroidTokens.Radius.Chip),
                    colors = VmdroidChipColors(),
                )
            }
        }
        // The high chips read as "more power" and are the opposite under emulation,
        // so say so where the choice is made rather than leaving it to be discovered.
        Text(
            text = stringResource(R.string.cpu_cores_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = VmdroidTokens.Spacing.SM),
        )
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = VmdroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmStorageChips(
    currentGb: Int,
    onChange: (Int) -> Unit,
    minGb: Int = 0,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.padding(bottom = VmdroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.storage)}  ·  $currentGb GB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = VmdroidTokens.Spacing.MD,
                bottom = VmdroidTokens.Spacing.SM,
            ),
        )
        val availableGb = DeviceResourcePolicy.deviceAvailableStorageGb(LocalContext.current)
        val storageOptions = DeviceResourcePolicy.storageOptionsFor(availableGb).let { options ->
            if (currentGb in options) options else options + currentGb
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        ) {
            storageOptions.forEach { gb ->
                FilterChip(
                    selected = gb == currentGb,
                    enabled = enabled && gb >= minGb,
                    onClick = { onChange(gb) },
                    label = { Text("$gb GB") },
                    shape = RoundedCornerShape(VmdroidTokens.Radius.Chip),
                    colors = VmdroidChipColors(),
                )
            }
        }
        Text(
            text = stringResource(R.string.storage_grow_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = VmdroidTokens.Spacing.SM),
        )
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = VmdroidTokens.Spacing.MD),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmBandwidthChips(
    currentMbps: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.padding(bottom = VmdroidTokens.Spacing.SM)) {
        Text(
            "${stringResource(R.string.bandwidth_limit)}  ·  ${formatBandwidth(currentMbps)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = VmdroidTokens.Spacing.MD,
                bottom = VmdroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(VmdroidTokens.Spacing.SM),
        ) {
            DeviceResourcePolicy.BANDWIDTH_OPTIONS_MBPS.forEach { mbps ->
                FilterChip(
                    selected = mbps == currentMbps,
                    enabled = enabled,
                    onClick = { onChange(mbps) },
                    label = { Text(formatBandwidth(mbps)) },
                    shape = RoundedCornerShape(VmdroidTokens.Radius.Chip),
                    colors = VmdroidChipColors(),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
                modifier = Modifier.padding(top = VmdroidTokens.Spacing.MD),
            )
        }
    }
}

@Composable
private fun formatRam(mb: Int): String =
    if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

@Composable
private fun formatBandwidth(mbps: Int): String =
    if (mbps <= 0) stringResource(R.string.bandwidth_unlimited) else "$mbps Mbps"
