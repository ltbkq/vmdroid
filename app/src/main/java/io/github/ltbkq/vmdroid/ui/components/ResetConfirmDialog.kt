/*
 * VMDroid - §8.5 activation-conflict confirmation dialog (shared).
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Home（§8.2 选择控件）与 Images（§8.3 设为启动镜像）两处激活入口共用：
 * 默认「重置并切换」= 清零 storage.img → activate(allowReset=true)（§5.2 规则 4
 * 顺序）；勾选「高级：保留数据」跳过清零直接切换（用户自担无法启动风险）。
 */
package io.github.ltbkq.vmdroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ltbkq.vmdroid.R
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens
import io.github.ltbkq.vmdroid.ui.screens.home.ResetPrompt

@Composable
fun ResetConfirmDialog(
    prompt: ResetPrompt,
    onConfirm: (keepData: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var keepData by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { keepData = false; onDismiss() },
        title = { Text(stringResource(R.string.reset_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.reset_dialog_body, prompt.fromIdentity, prompt.toIdentity))
                Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
                Text(stringResource(R.string.reset_dialog_deletes))
                Spacer(Modifier.height(VmdroidTokens.Spacing.XS))
                Text(stringResource(R.string.reset_dialog_keeps))
                Spacer(Modifier.height(VmdroidTokens.Spacing.MD))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keepData = !keepData },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keepData, onCheckedChange = { keepData = it })
                    Text(stringResource(R.string.reset_advanced_keep))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val keep = keepData
                keepData = false
                onConfirm(keep)
            }) {
                Text(
                    stringResource(
                        if (keepData) R.string.reset_confirm_keep else R.string.reset_confirm_switch,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { keepData = false; onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
