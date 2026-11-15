package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.downloader.DragonDownloadManager
import com.example.data.model.BatchDownloadConfig
import com.example.data.model.MediaFormatOption
import com.example.data.model.MediaInfo
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

fun requiredStoragePermissionsForDownload(): Array<String> {
    return buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
}

fun hasStoragePermissionsForDownload(context: Context): Boolean {
    val required = requiredStoragePermissionsForDownload()
    if (required.isEmpty()) return true
    return required.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

fun openAppStoragePermissionSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

/**
 * Confirmation pop-up window displayed before starting any file/media download.
 * Verifies & requests storage permission, ensures Internal Storage > Download > DDM exists,
 * allows renaming and choosing DDM parallel connection threads, then launches the download.
 */
@Composable
fun StartDownloadPopupDialog(
    mediaInfo: MediaInfo,
    formatOption: MediaFormatOption,
    downloadManager: DragonDownloadManager,
    maxConnections: Int,
    onDismiss: () -> Unit,
    onDownloadStarted: (taskId: String, savedFilename: String) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current
    val warpDnsEnabled by downloadManager.warpDnsEnabledFlow.collectAsState()
    val requiredPerms = remember { requiredStoragePermissionsForDownload() }
    var hasPermission by remember { mutableStateOf(hasStoragePermissionsForDownload(context)) }
    var didAskOnce by remember { mutableStateOf(false) }
    var pendingStartAfterPermission by remember { mutableStateOf(false) }

    val defaultExt = remember(formatOption) {
        formatOption.container.lowercase(Locale.US).ifBlank {
            when {
                formatOption.isAudioOnly -> "m4a"
                formatOption.isGenericFile -> "bin"
                else -> "mp4"
            }
        }
    }
    var filenameInput by remember(mediaInfo.canonicalUrl, formatOption.id) {
        mutableStateOf(
            downloadManager.buildSuggestedFilename(
                title = mediaInfo.title,
                qualityLabel = formatOption.qualityLabel,
                ext = defaultExt,
                isAudioOnly = formatOption.isAudioOnly,
                isGenericFile = formatOption.isGenericFile
            )
        )
    }

    val targetDisplayPath = remember(formatOption) {
        DragonDownloadManager.displayFolderForType(
            isAudioOnly = formatOption.isAudioOnly,
            isGenericFile = formatOption.isGenericFile
        )
    }

    LaunchedEffect(Unit) {
        downloadManager.ensureDragonToolDirectories()
        hasPermission = hasStoragePermissionsForDownload(context)
    }

    val startAction = {
        downloadManager.ensureDragonToolDirectories()
        val chosenName = filenameInput.trim().ifBlank { null }
        val taskId = downloadManager.startDownload(
            mediaInfo = mediaInfo,
            format = formatOption,
            customFilename = chosenName
        )
        onDownloadStarted(taskId, chosenName ?: mediaInfo.title)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantMap ->
        didAskOnce = true
        hasPermission = grantMap.values.all { it } || hasStoragePermissionsForDownload(context)
        if (hasPermission) {
            downloadManager.ensureDragonToolDirectories()
        }
        if (pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            startAction()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("start_download_popup_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SuccessGreen.copy(alpha = 0.18f))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.55f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DownloadForOffline,
                        contentDescription = "Start Download",
                        tint = SuccessGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Start Download",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${mediaInfo.platform.displayName} • ${formatOption.qualityLabel}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Editable File Name
                OutlinedTextField(
                    value = filenameInput,
                    onValueChange = { filenameInput = it },
                    label = { Text("File Name", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("popup_filename_input")
                )

                // Format & Size Info Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FORMAT & SIZE",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = formatOption.formattedSize(mediaInfo.durationSeconds),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = SuccessGreen
                            )
                        }
                        Text(
                            text = formatOption.technicalSummary(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Target Save Folder: Internal Storage > Download > DDM
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "Save Folder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SAVE LOCATION",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = targetDisplayPath,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Storage Permission Status Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasPermission) SuccessGreen.copy(alpha = 0.10f) else WarningOrange.copy(alpha = 0.12f),
                    border = BorderStroke(
                        1.dp,
                        if (hasPermission) SuccessGreen.copy(alpha = 0.45f) else WarningOrange.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (hasPermission) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = "Storage Permission",
                                tint = if (hasPermission) SuccessGreen else WarningOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = if (hasPermission) "Storage Permission Granted" else "Storage Permission Needed",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (hasPermission) "Ready to write to $targetDisplayPath" else "Grant storage access to save files in $targetDisplayPath",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!hasPermission && requiredPerms.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    haptics.tap()
                                    if (didAskOnce) {
                                        openAppStoragePermissionSettings(context)
                                    } else {
                                        permissionLauncher.launch(requiredPerms)
                                    }
                                }
                            ) {
                                Text(
                                    text = if (didAskOnce) "Settings" else "Allow",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = WarningOrange
                                )
                            }
                        }
                    }
                }

                // IDM Multi-Connection Threads Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "DDM THREADS",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                        }
                        Text(
                            text = "${maxConnections}x Parallel",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 4, 8, 12, 16).forEach { threads ->
                            val selected = maxConnections == threads
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) NeonCyan.copy(alpha = 0.22f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    width = if (selected) 1.2.dp else 1.dp,
                                    color = if (selected) NeonCyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptics.tap()
                                        downloadManager.setMaxConnections(threads)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${threads}x",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                                        color = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Cloudflare WARP DNS (1.1.1.1) Toggle in Popup
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (warpDnsEnabled) WarningOrange.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                        border = BorderStroke(
                            1.dp,
                            if (warpDnsEnabled) WarningOrange.copy(alpha = 0.50f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !warpDnsEnabled
                                haptics.toggle(next)
                                downloadManager.setWarpDnsEnabled(next)
                            }
                            .testTag("popup_warp_dns_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Cloudflare WARP DNS",
                                    tint = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (warpDnsEnabled) "Cloudflare WARP DNS (1.1.1.1) • ON" else "Cloudflare WARP DNS (1.1.1.1) • OFF",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (warpDnsEnabled) WarningOrange else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = warpDnsEnabled,
                                onCheckedChange = { next ->
                                    haptics.toggle(next)
                                    downloadManager.setWarpDnsEnabled(next)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.heavyAction()
                    val grantedNow = hasStoragePermissionsForDownload(context)
                    hasPermission = grantedNow
                    if (!grantedNow && requiredPerms.isNotEmpty() && !didAskOnce) {
                        pendingStartAfterPermission = true
                        permissionLauncher.launch(requiredPerms)
                    } else {
                        startAction()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_start_download_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Start Download",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.tap()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Confirmation pop-up window for Batch Downloads showing item count, target Download/DDM folder,
 * and storage permission check before queueing.
 */
@Composable
fun StartBatchDownloadPopupDialog(
    urls: List<String>,
    config: BatchDownloadConfig,
    downloadManager: DragonDownloadManager,
    onDismiss: () -> Unit,
    onConfirmStart: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current
    val requiredPerms = remember { requiredStoragePermissionsForDownload() }
    var hasPermission by remember { mutableStateOf(hasStoragePermissionsForDownload(context)) }
    var pendingStart by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        downloadManager.ensureDragonToolDirectories()
        hasPermission = hasStoragePermissionsForDownload(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantMap ->
        hasPermission = grantMap.values.all { it } || hasStoragePermissionsForDownload(context)
        if (pendingStart) {
            pendingStart = false
            downloadManager.ensureDragonToolDirectories()
            onConfirmStart()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("start_batch_popup_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Start Batch Download",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "${urls.size} links • ${config.downloadMode.displayName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "SAVE FOLDER",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Downloads/DragonTool/Videos, Audio & Files",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = "Concurrent Workers: ${config.maxConcurrentDownloads} • DDM Threads: ${downloadManager.maxConnectionsFlow.value}x",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.heavyAction()
                    val granted = hasStoragePermissionsForDownload(context)
                    hasPermission = granted
                    if (!granted && requiredPerms.isNotEmpty()) {
                        pendingStart = true
                        permissionLauncher.launch(requiredPerms)
                    } else {
                        downloadManager.ensureDragonToolDirectories()
                        onConfirmStart()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Start Batch (${urls.size})", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.tap()
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}
