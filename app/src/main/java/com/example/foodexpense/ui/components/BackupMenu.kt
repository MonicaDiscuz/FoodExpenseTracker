package com.example.foodexpense.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.foodexpense.ui.theme.NeonGreen
import com.example.foodexpense.viewmodel.BackupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 右上角「更多」菜单：一键导出 / 一键导入全部数据（含评价图片）。
 * 放在有 SnackbarHost 的页面里即可，操作结果通过 Snackbar 提示。
 */
@Composable
fun BackupMenu(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAiSettings: () -> Unit = {}
) {
    val vm: BackupViewModel = viewModel()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var menuExpanded by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
        }
    }

    // 导出：系统文件选择器另存为 zip
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) vm.export(uri)
    }

    // 导入：选择 zip 后先问"覆盖 / 追加"
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showModeDialog = true
        }
    }

    Box(modifier = modifier) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                strokeWidth = 2.dp
            )
        } else {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.22f))
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多（导出 / 导入数据）",
                    tint = NeonGreen
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("导出全部数据") },
                onClick = {
                    menuExpanded = false
                    exportLauncher.launch(exportFileName())
                }
            )
            DropdownMenuItem(
                text = { Text("导入数据（恢复 / 迁移）") },
                onClick = {
                    menuExpanded = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            )
            DropdownMenuItem(
                text = { Text("压缩已有评价图片") },
                onClick = {
                    menuExpanded = false
                    showCompressDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text("AI 识别设置") },
                onClick = {
                    menuExpanded = false
                    onAiSettings()
                }
            )
        }
    }

    if (showCompressDialog) {
        AlertDialog(
            onDismissRequest = { showCompressDialog = false },
            title = { Text("压缩已有评价图片？") },
            text = {
                Text(
                    "已保存的评价图片会被压缩到最长边 1600px（JPEG 85%），" +
                        "相机大图通常能从 3~4MB 降到 200~400KB。\n\n" +
                        "压缩会替换原图且无法恢复，太小的图片会跳过。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompressDialog = false
                    vm.compressExistingImages()
                }) { Text("开始压缩") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressDialog = false }) { Text("取消") }
            }
        )
    }

    if (showModeDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                showModeDialog = false
                pendingImportUri = null
            },
            title = { Text("选择导入方式") },
            text = {
                Text(
                    "覆盖导入：先清空本机现有记录与图片，再导入备份——结果与备份完全一致（推荐）。\n\n" +
                        "追加导入：保留本机记录，把备份里的记录合并进来。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingImportUri
                    showModeDialog = false
                    pendingImportUri = null
                    target?.let { vm.import(it, replace = true) }
                }) { Text("覆盖导入") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val target = pendingImportUri
                        showModeDialog = false
                        pendingImportUri = null
                        target?.let { vm.import(it, replace = false) }
                    }) { Text("追加导入") }
                    TextButton(onClick = {
                        showModeDialog = false
                        pendingImportUri = null
                    }) { Text("取消") }
                }
            }
        )
    }
}

private fun exportFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())
    return "外卖记账备份_$stamp.zip"
}
