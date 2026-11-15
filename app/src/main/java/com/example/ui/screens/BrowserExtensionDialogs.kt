package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.browser.BrowserExtension
import com.example.data.browser.BrowserExtensionEngine
import com.example.data.browser.ExtensionKind
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange

@Composable
fun BrowserExtensionsDialog(
    extensions: List<BrowserExtension>,
    activePageUrl: String,
    onImportFromStorageClick: () -> Unit,
    onToggleExtension: (BrowserExtension, Boolean) -> Unit,
    onRemoveExtension: (BrowserExtension) -> Unit,
    onOpenExtensionPopup: (BrowserExtension) -> Unit,
    onCreateCustomExtension: (name: String, description: String, matchPattern: String, jsCode: String, cssCode: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateCustomForm by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customDesc by remember { mutableStateOf("") }
    var customMatch by remember { mutableStateOf("<all_urls>") }
    var customJs by remember { mutableStateOf("") }
    var customCss by remember { mutableStateOf("") }

    val enabledCount = remember(extensions) { extensions.count { it.enabled } }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_extensions_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Extensions",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (enabledCount > 0) SuccessGreen.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "$enabledCount/${extensions.size} ON",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (enabledCount > 0) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Compact Top Action Row: Import from Local Storage + Custom Script/CSS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onImportFromStorageClick,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(36.dp)
                            .testTag("btn_import_extension_storage"),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = Color(0xFF04131B)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Import Extension from Local Storage",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Import (.zip/.crx/.js)",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { showCreateCustomForm = !showCreateCustomForm },
                        modifier = Modifier
                            .weight(0.85f)
                            .height(36.dp)
                            .testTag("btn_new_custom_extension"),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = if (showCreateCustomForm) Icons.Default.ExpandLess else Icons.Default.Code,
                            contentDescription = "Create Custom Script or Style",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showCreateCustomForm) "Hide" else "+ Script/CSS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Inline Custom Userscript / UserStyle Creator Form
                AnimatedVisibility(visible = showCreateCustomForm) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Custom Userscript / CSS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = NeonCyan
                            )
                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("Name", fontSize = 10.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_ext_name")
                            )
                            OutlinedTextField(
                                value = customMatch,
                                onValueChange = { customMatch = it },
                                label = { Text("Match URL (<all_urls>)", fontSize = 10.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = customJs,
                                onValueChange = { customJs = it },
                                label = { Text("JS Code (chrome.* / GM_*)", fontSize = 10.sp) },
                                minLines = 2,
                                maxLines = 3,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_ext_js")
                            )
                            OutlinedTextField(
                                value = customCss,
                                onValueChange = { customCss = it },
                                label = { Text("CSS Code (optional)", fontSize = 10.sp) },
                                minLines = 1,
                                maxLines = 2,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_ext_css")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showCreateCustomForm = false },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Cancel", fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = {
                                        if (customJs.isNotBlank() || customCss.isNotBlank()) {
                                            onCreateCustomExtension(
                                                customName.ifBlank { "Custom Script" },
                                                customDesc.ifBlank { "User-authored browser extension" },
                                                customMatch.ifBlank { "<all_urls>" },
                                                customJs,
                                                customCss
                                            )
                                            customName = ""
                                            customDesc = ""
                                            customJs = ""
                                            customCss = ""
                                            showCreateCustomForm = false
                                        }
                                    },
                                    enabled = customJs.isNotBlank() || customCss.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier
                                        .height(30.dp)
                                        .testTag("btn_save_custom_extension")
                                ) {
                                    Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Compact Installed Extensions List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(extensions, key = { it.id }) { ext ->
                        val badgeColor = when (ext.kind) {
                            ExtensionKind.WEB_EXTENSION_MV3 -> NeonCyan
                            ExtensionKind.WEB_EXTENSION_MV2 -> SuccessGreen
                            ExtensionKind.USERSCRIPT -> WarningOrange
                            ExtensionKind.USERSTYLE -> Color(0xFFD8B4FE)
                        }
                        val shortKindLabel = when (ext.kind) {
                            ExtensionKind.WEB_EXTENSION_MV3 -> "MV3"
                            ExtensionKind.WEB_EXTENSION_MV2 -> "MV2"
                            ExtensionKind.USERSCRIPT -> "JS"
                            ExtensionKind.USERSTYLE -> "CSS"
                        }

                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (ext.enabled) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                            },
                            border = BorderStroke(
                                width = if (ext.enabled) 1.dp else 0.6.dp,
                                color = if (ext.enabled) badgeColor.copy(alpha = 0.50f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("extension_card_${ext.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = badgeColor.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = shortKindLabel,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = badgeColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        Text(
                                            text = ext.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Text(
                                            text = "v${ext.version}",
                                            fontSize = 8.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                            maxLines = 1
                                        )
                                    }

                                    Text(
                                        text = ext.description.ifBlank {
                                            if (ext.isBuiltIn) "Built-in extension" else ext.sourceFileName
                                        },
                                        fontSize = 9.5.sp,
                                        lineHeight = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (!ext.popupPath.isNullOrBlank()) {
                                        IconButton(
                                            onClick = { onOpenExtensionPopup(ext) },
                                            modifier = Modifier
                                                .size(26.dp)
                                                .testTag("btn_popup_ext_${ext.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "Open Extension Popup",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    if (!ext.isBuiltIn) {
                                        IconButton(
                                            onClick = { onRemoveExtension(ext) },
                                            modifier = Modifier
                                                .size(26.dp)
                                                .testTag("btn_remove_ext_${ext.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Remove Extension",
                                                tint = DangerRed,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = ext.enabled,
                                        onCheckedChange = { next ->
                                            onToggleExtension(ext, next)
                                        },
                                        modifier = Modifier
                                            .scale(0.66f)
                                            .height(28.dp)
                                            .testTag("switch_ext_${ext.id}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Interactive Modal for running a WebExtension's `popup.html` action page with full `chrome.*` / `browser.*` API support.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserExtensionPopupDialog(
    extension: BrowserExtension,
    extensionEngine: BrowserExtensionEngine,
    onOpenTabFromExtension: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val popupPayload = remember(extension.id) {
        extensionEngine.loadExtensionPopupHtml(extension)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_extension_popup_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = NeonCyan)
                    Column {
                        Text(extension.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            text = extension.popupPath ?: "popup.html",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Popup")
                }
            }
        },
        text = {
            if (popupPayload == null) {
                Text("Extension popup file could not be loaded.")
            } else {
                val (baseUrl, htmlContent) = popupPayload
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = true
                                    allowContentAccess = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun storageGet(extId: String): String =
                                            extensionEngine.getExtensionStorageJson(extId)

                                        @JavascriptInterface
                                        fun storageSet(extId: String, jsonPatch: String): String =
                                            extensionEngine.mergeExtensionStorageJson(extId, jsonPatch)

                                        @JavascriptInterface
                                        fun storageRemove(extId: String, keysArrayJson: String): String =
                                            extensionEngine.removeExtensionStorageKeys(extId, keysArrayJson)

                                        @JavascriptInterface
                                        fun storageClear(extId: String) {
                                            extensionEngine.clearExtensionStorage(extId)
                                        }

                                        @JavascriptInterface
                                        fun gmXhr(requestJson: String): String =
                                            extensionEngine.executeCrossOriginXhr(requestJson)

                                        @JavascriptInterface
                                        fun openTab(url: String) {
                                            if (url.isNotBlank()) {
                                                post {
                                                    onOpenTabFromExtension(url.trim())
                                                    onDismiss()
                                                }
                                            }
                                        }

                                        @JavascriptInterface
                                        fun copyClipboard(text: String) {
                                            post {
                                                clipboard.setText(AnnotatedString(text))
                                            }
                                        }

                                        @JavascriptInterface
                                        fun showToast(message: String) {
                                            post {
                                                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    "DragonExtensionBridge"
                                )
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val reqUrl = request?.url?.toString() ?: return null
                                        return extensionEngine.interceptExtensionRequest(
                                            requestUrl = reqUrl,
                                            pageHost = request.url?.host,
                                            isMainFrame = request.isForMainFrame
                                        ) ?: super.shouldInterceptRequest(view, request)
                                    }
                                }
                                loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
