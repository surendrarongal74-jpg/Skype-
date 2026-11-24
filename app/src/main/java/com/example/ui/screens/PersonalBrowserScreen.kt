package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.local.entity.BrowserRecordEntity
import com.example.data.local.entity.BrowserTabEntity
import com.example.services.BrowserAutomationService
import com.example.services.BrowserControlOwner
import com.example.services.BrowserDomAction
import com.example.services.BrowserDomCommand
import com.example.services.BrowserDomCommandResult
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserSessionService
import com.example.services.PermissionService
import com.example.ui.chat.BrowserDiagnosticErrorPane
import com.example.ui.chat.configureAlloraWebViewSettings
import com.example.ui.chat.getWebViewEngineInfo
import com.example.ui.chat.launchSecureCustomTab
import com.example.ui.components.GlassBottomSheet
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassProgressBar
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraAgentWorkspaceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private data class PendingDownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String
)

private data class ExtractedPageLink(
    val title: String,
    val url: String
)

@Composable
fun MyBrowserScreen(
    controller: AlloraAgentWorkspaceController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSummarizePage: (url: String, title: String, content: String) -> Unit = { _, _, _ -> }
) {
    val tabs by controller.browserTabs.collectAsState()
    val records by controller.browserRecords.collectAsState()
    val liveActivity by controller.browserLiveActivity.collectAsState()
    val pendingDomCommand by controller.pendingBrowserDomCommand.collectAsState()
    val banner by controller.statusBanner.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var localNotice by remember { mutableStateOf<String?>(null) }

    var uploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uploadCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else emptyArray())
        uploadCallback = null
    }

    BackHandler(onBack = onBack)

    MyBrowserScreenContent(
        tabs = tabs,
        records = records,
        liveActivity = liveActivity,
        statusBanner = localNotice ?: banner,
        onBack = onBack,
        onNewTab = { controller.openNewTab() },
        onSelectTab = { controller.selectTab(it) },
        onCloseTab = { controller.closeTab(it) },
        onNavigate = { tabId, input ->
            val resolved = BrowserSessionService.resolveUserUrlOrSearch(input)
            if (resolved != null) {
                controller.recordPageVisit(tabId, resolved, resolved)
            } else {
                localNotice = "Blocked unsafe URL scheme. Only https:// and http:// are allowed."
            }
        },
        onPageLoaded = { tabId, url, title -> controller.recordPageVisit(tabId, url, title) },
        onBookmarkCurrent = { url, title -> controller.addBookmark(url, title) },
        onStopAutomation = { controller.stopBrowserAutomation() },
        onResolveManualIntervention = { controller.resolveManualIntervention() },
        onDeleteRecord = { controller.deleteBrowserRecord(it) },
        onUpdateSitePermission = { domain, mode -> controller.updateSitePermission(domain, mode) },
        onLaunchFileUpload = { callback ->
            uploadCallback?.onReceiveValue(emptyArray())
            uploadCallback = callback
            uploadPicker.launch(arrayOf("*/*"))
        },
        onDownloadBytes = { url, fileName, mimeType, bytes ->
            controller.addDownloadBytes(url, fileName, mimeType, bytes)
        },
        onSummarizePage = onSummarizePage,
        onPrepareSafeFormFill = { domain, selector, value, onScriptReady ->
            coroutineScope.launch(Dispatchers.IO) {
                val res = controller.browserAutomationService.buildSafeFormFillScript(
                    userId = controller.currentUserId,
                    domain = domain,
                    selector = selector,
                    value = value
                )
                withContext(Dispatchers.Main) {
                    res.fold(
                        onSuccess = { script ->
                            localNotice = "Prepared non-secret form fill for $domain."
                            onScriptReady(script)
                        },
                        onFailure = { err ->
                            localNotice = err.message ?: "Form prep blocked by policy."
                        }
                    )
                }
            }
        },
        onNavigationStarted = { sessionId, url ->
            controller.browserAutomationService.onWebViewNavigationStarted(
                controller.currentUserId,
                sessionId,
                url
            )
        },
        onProgressChanged = { url, progress ->
            controller.browserAutomationService.onWebViewProgressChanged(
                controller.currentUserId,
                url,
                progress
            )
        },
        onPageDomExtracted = { url, title, snippet ->
            controller.browserAutomationService.onWebViewPageFinished(
                controller.currentUserId,
                url,
                title,
                snippet
            )
        },
        onVisualFrameCaptured = { pngBytes, w, h ->
            controller.browserAutomationService.publishVisualFrameIfChanged(
                controller.currentUserId,
                pngBytes,
                w,
                h
            )
        },
        onConsumedPendingScript = {
            controller.browserAutomationService.clearPendingScript()
        },
        onHttpError = { url, code, reason ->
            controller.browserAutomationService.onWebViewHttpError(controller.currentUserId, url, code, reason)
        },
        onNetworkError = { url, code, desc ->
            controller.browserAutomationService.onWebViewNetworkError(controller.currentUserId, url, code, desc)
            coroutineScope.launch(Dispatchers.IO) {
                controller.browserAutomationService.probeOriginCompatibility(url, controller.currentUserId)
            }
        },
        onSslError = { url ->
            controller.browserAutomationService.onWebViewSslError(controller.currentUserId, url)
        },
        onTimeoutError = { url ->
            controller.browserAutomationService.onWebViewTimeout(controller.currentUserId, url)
        },
        onRedirectHop = { url ->
            controller.browserAutomationService.onRedirectHop(controller.currentUserId, url)
        },
        onSchemeBlocked = { url, scheme ->
            controller.browserAutomationService.onSchemeBlocked(controller.currentUserId, url, scheme)
        },
        onStopLoading = {
            controller.browserAutomationService.stopPageLoading(controller.currentUserId)
        },
        onWebViewRuntimeDetected = { pkg, ver ->
            controller.browserAutomationService.updateWebViewRuntimeInfo(pkg, ver)
        },
        onRetryNavigation = {
            controller.browserAutomationService.clearNavigationError(controller.currentUserId)
        },
        onOpenExternalCustomTab = { url ->
            coroutineScope.launch {
                controller.browserAutomationService.markExternalCustomTabHandoff(controller.currentUserId, url)
            }
        },
        pendingDomCommand = pendingDomCommand,
        onDomCommandCompleted = { commandId, result ->
            controller.browserAutomationService.completeDomCommand(commandId, result)
        },
        modifier = modifier
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MyBrowserScreenContent(
    tabs: List<BrowserTabEntity>,
    records: List<BrowserRecordEntity>,
    liveActivity: BrowserLiveActivityState,
    statusBanner: String?,
    onBack: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNavigate: (String, String) -> Unit,
    onPageLoaded: (String, String, String) -> Unit,
    onBookmarkCurrent: (String, String) -> Unit,
    onStopAutomation: () -> Unit,
    onResolveManualIntervention: () -> Unit,
    onDeleteRecord: (String) -> Unit,
    onUpdateSitePermission: (String, String) -> Unit,
    onLaunchFileUpload: (ValueCallback<Array<Uri>>) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadBytes: (url: String, fileName: String, mimeType: String, bytes: ByteArray) -> Unit = { _, _, _, _ -> },
    onSummarizePage: (url: String, title: String, content: String) -> Unit = { _, _, _ -> },
    onPrepareSafeFormFill: (domain: String, selector: String, value: String, onScriptReady: (String) -> Unit) -> Unit = { _, _, _, _ -> },
    onNavigationStarted: (sessionId: String, url: String) -> Unit = { _, _ -> },
    onProgressChanged: (url: String, progress: Int) -> Unit = { _, _ -> },
    onPageDomExtracted: (url: String, title: String, snippet: String) -> Unit = { _, _, _ -> },
    onVisualFrameCaptured: (pngBytes: ByteArray, widthPx: Int, heightPx: Int) -> Unit = { _, _, _ -> },
    onConsumedPendingScript: () -> Unit = {},
    onHttpError: (url: String, statusCode: Int, reason: String) -> Unit = { _, _, _ -> },
    onNetworkError: (url: String, errorCode: Int, description: String) -> Unit = { _, _, _ -> },
    onSslError: (url: String) -> Unit = { _ -> },
    onTimeoutError: (url: String) -> Unit = { _ -> },
    onRedirectHop: (url: String) -> Unit = {},
    onSchemeBlocked: (url: String, scheme: String) -> Unit = { _, _ -> },
    onStopLoading: () -> Unit = {},
    onWebViewRuntimeDetected: (packageName: String, versionName: String) -> Unit = { _, _ -> },
    onRetryNavigation: () -> Unit = {},
    onOpenExternalCustomTab: (url: String) -> Unit = {},
    pendingDomCommand: BrowserDomCommand? = null,
    onDomCommandCompleted: (commandId: String, result: BrowserDomCommandResult) -> Unit = { _, _ -> }
) {
    val glass = LocalAlloraGlassColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activeTab = tabs.firstOrNull { it.isActive } ?: tabs.firstOrNull()
    var addressInput by remember(activeTab?.id, activeTab?.url) {
        mutableStateOf(activeTab?.url ?: BrowserSessionService.DEFAULT_HOME_URL)
    }
    var showDrawerSheet by remember { mutableStateOf(false) }
    var sheetTabIndex by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var activeNavigationSeq by remember { mutableIntStateOf(0) }
    var failedNavigationSeq by remember { mutableIntStateOf(-1) }
    var activeLoadingUrl by remember { mutableStateOf("") }
    var lastAutoLoadedUrl by remember { mutableStateOf("") }

    var readerText by remember { mutableStateOf<String?>(null) }
    var extractedLinks by remember { mutableStateOf<List<ExtractedPageLink>?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingDownloadRequest?>(null) }
    var downloadStatusNote by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var showFormPrepModal by remember { mutableStateOf(false) }
    var formSelectorInput by remember { mutableStateOf("input[name='q']") }
    var formValueInput by remember { mutableStateOf("") }

    val downloadClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    LaunchedEffect(activeTab?.url, liveActivity.errorInfo) {
        val url = activeTab?.url ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (liveActivity.errorInfo == null && lastAutoLoadedUrl != url && wv.url != url) {
            lastAutoLoadedUrl = url
            wv.loadUrl(url)
        }
    }

    LaunchedEffect(liveActivity.isLoading, activeLoadingUrl) {
        if (liveActivity.isLoading && activeLoadingUrl.isNotBlank()) {
            val loadingUrlSnapshot = activeLoadingUrl
            val seqSnapshot = activeNavigationSeq
            delay(25_000L)
            if (liveActivity.isLoading && activeNavigationSeq == seqSnapshot && failedNavigationSeq != seqSnapshot) {
                failedNavigationSeq = seqSnapshot
                webViewRef?.stopLoading()
                onTimeoutError(loadingUrlSnapshot)
            }
        }
    }

    LaunchedEffect(zoomScale) {
        val wv = webViewRef ?: return@LaunchedEffect
        wv.settings.textZoom = (zoomScale * 100f).roundToInt().coerceIn(75, 200)
        wv.evaluateJavascript("document.documentElement.style.zoom='${zoomScale}';", null)
    }

    LaunchedEffect(liveActivity.pendingJsScript, liveActivity.isPaused, liveActivity.controlOwner) {
        val script = liveActivity.pendingJsScript ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (!liveActivity.isPaused && liveActivity.controlOwner == BrowserControlOwner.AI) {
            wv.evaluateJavascript(script) { _ ->
                onConsumedPendingScript()
            }
        }
    }

    LaunchedEffect(pendingDomCommand?.commandId, webViewRef) {
        val cmd = pendingDomCommand ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (cmd.userId.isNotBlank() && liveActivity.userId.isNotBlank() && cmd.userId != liveActivity.userId) {
            onDomCommandCompleted(
                cmd.commandId,
                BrowserDomCommandResult(false, "UNAUTHORIZED", errorMessage = "User mismatch on DOM command.")
            )
            return@LaunchedEffect
        }
        val curUrl = wv.url.orEmpty().ifBlank { liveActivity.currentUrl }
        val curTitle = wv.title.orEmpty().ifBlank { liveActivity.pageTitle }
        when (cmd.action) {
            BrowserDomAction.GO_BACK -> {
                if (wv.canGoBack()) {
                    wv.goBack()
                    onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(true, "GO_BACK", "Navigated back", curUrl, curTitle))
                } else {
                    onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(false, "CANNOT_GO_BACK", "", curUrl, curTitle, "No back history entry"))
                }
            }
            BrowserDomAction.GO_FORWARD -> {
                if (wv.canGoForward()) {
                    wv.goForward()
                    onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(true, "GO_FORWARD", "Navigated forward", curUrl, curTitle))
                } else {
                    onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(false, "CANNOT_GO_FORWARD", "", curUrl, curTitle, "No forward history entry"))
                }
            }
            BrowserDomAction.RELOAD -> {
                wv.reload()
                onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(true, "RELOADING", "Reloading current page", curUrl, curTitle))
            }
            BrowserDomAction.STOP -> {
                activeLoadingUrl = ""
                wv.stopLoading()
                onStopLoading()
                onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(true, "STOPPED", "Stopped loading", curUrl, curTitle))
            }
            BrowserDomAction.GET_STATE -> {
                onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(true, liveActivity.engineState.name, curTitle, curUrl, curTitle))
            }
            BrowserDomAction.CLICK,
            BrowserDomAction.SCROLL,
            BrowserDomAction.FILL_SAFE,
            BrowserDomAction.EXTRACT_DOM,
            BrowserDomAction.EXTRACT_LINKS -> {
                if (cmd.script.isBlank()) {
                    onDomCommandCompleted(cmd.commandId, BrowserDomCommandResult(false, "EMPTY_SCRIPT", "", curUrl, curTitle, "Empty DOM script"))
                } else {
                    wv.evaluateJavascript(cmd.script) { raw ->
                        val decoded = decodeJsString(raw)
                        val liveUrl = wv.url.orEmpty().ifBlank { liveActivity.currentUrl }
                        val liveTitle = wv.title.orEmpty().ifBlank { liveActivity.pageTitle }
                        val res = when (decoded) {
                            "BLOCKED_PASSWORD_FIELD" -> BrowserDomCommandResult(false, "BLOCKED_SENSITIVE_FIELD", "", liveUrl, liveTitle, "Blocked password/OTP/CVV/PIN field.")
                            "NOT_FOUND" -> BrowserDomCommandResult(false, "ELEMENT_NOT_FOUND", "", liveUrl, liveTitle, "Selector not found.")
                            else -> BrowserDomCommandResult(true, if (decoded.isBlank()) "EXECUTED" else decoded.take(32), decoded, liveUrl, liveTitle)
                        }
                        onDomCommandCompleted(cmd.commandId, res)
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("personal_browser_screen")
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Exit Browser",
                    onClick = onBack,
                    modifier = Modifier.testTag("browser_exit_btn")
                )
                Spacer(modifier = Modifier.width(6.dp))
                GlassSearchBar(
                    query = addressInput,
                    onQueryChange = { addressInput = it },
                    placeholder = "Search or enter https:// URL…",
                    onSubmit = {
                        val tabId = activeTab?.id ?: return@GlassSearchBar
                        onNavigate(tabId, addressInput)
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                GlassCircleIconButton(
                    icon = Icons.Default.BookmarkAdd,
                    contentDescription = "Bookmark Page",
                    onClick = {
                        onBookmarkCurrent(
                            activeTab?.url ?: addressInput,
                            activeTab?.title ?: "Saved Page"
                        )
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                GlassCircleIconButton(
                    icon = Icons.Default.History,
                    contentDescription = "Browser Vault & History",
                    onClick = { showDrawerSheet = true },
                    modifier = Modifier.testTag("browser_vault_btn")
                )
            }

            if (liveActivity.isLoading) {
                Spacer(modifier = Modifier.height(6.dp))
                GlassProgressBar(
                    progress = (liveActivity.progressPercent.coerceIn(0, 100) / 100f),
                    modifier = Modifier.testTag("browser_loading_bar")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                item {
                    GlassCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Page Back",
                        onClick = {
                            if (webViewRef?.canGoBack() == true) webViewRef?.goBack()
                        },
                        enabled = canGoBack,
                        modifier = Modifier.testTag("browser_back_page_btn")
                    )
                }
                item {
                    GlassCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Page Forward",
                        onClick = {
                            if (webViewRef?.canGoForward() == true) webViewRef?.goForward()
                        },
                        enabled = canGoForward,
                        modifier = Modifier.testTag("browser_forward_page_btn")
                    )
                }
                items(tabs, key = { it.id }) { tab ->
                    GlassChip(
                        label = tab.title.take(16),
                        selected = tab.id == activeTab?.id,
                        onClick = { onSelectTab(tab.id) }
                    )
                }
                item {
                    GlassChip(label = "+ Tab", selected = false, leadingIcon = Icons.Default.Add, onClick = onNewTab)
                }
                if (activeTab != null && tabs.size > 1) {
                    item {
                        GlassChip(label = "Close", selected = false, leadingIcon = Icons.Default.Close, onClick = { onCloseTab(activeTab.id) })
                    }
                }
                if (liveActivity.isLoading) {
                    item {
                        GlassChip(
                            label = "Stop",
                            selected = false,
                            leadingIcon = Icons.Default.Close,
                            onClick = {
                                activeLoadingUrl = ""
                                webViewRef?.stopLoading()
                                onStopLoading()
                            },
                            modifier = Modifier.testTag("browser_stop_loading_chip")
                        )
                    }
                } else {
                    item {
                        GlassChip(
                            label = "Reload",
                            selected = false,
                            leadingIcon = Icons.Default.Refresh,
                            onClick = {
                                lastAutoLoadedUrl = ""
                                onRetryNavigation()
                                webViewRef?.reload()
                            }
                        )
                    }
                }
                item {
                    GlassCircleIconButton(
                        icon = Icons.Default.ZoomOut,
                        contentDescription = "Zoom Out",
                        onClick = { zoomScale = (zoomScale - 0.15f).coerceAtLeast(0.75f) },
                        modifier = Modifier.testTag("browser_personal_zoom_out")
                    )
                }
                item {
                    Text(
                        text = "${(zoomScale * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                    )
                }
                item {
                    GlassCircleIconButton(
                        icon = Icons.Default.ZoomIn,
                        contentDescription = "Zoom In",
                        onClick = { zoomScale = (zoomScale + 0.15f).coerceAtMost(2.0f) },
                        modifier = Modifier.testTag("browser_personal_zoom_in")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item {
                    GlassChip(
                        label = "Read Page",
                        selected = false,
                        leadingIcon = Icons.Default.Article,
                        onClick = {
                            webViewRef?.evaluateJavascript(
                                "(function(){return (document.body && document.body.innerText ? document.body.innerText.slice(0, 6000) : '');})()"
                            ) { raw ->
                                readerText = decodeJsString(raw).ifBlank { "No readable text found on the current page." }
                            }
                        },
                        modifier = Modifier.testTag("browser_read_page_btn")
                    )
                }
                item {
                    GlassChip(
                        label = "Summarize",
                        selected = false,
                        leadingIcon = Icons.Default.AutoAwesome,
                        onClick = {
                            val currentUrl = webViewRef?.url ?: activeTab?.url ?: addressInput
                            val currentTitle = webViewRef?.title ?: activeTab?.title ?: "Webpage"
                            webViewRef?.evaluateJavascript(
                                "(function(){return (document.body && document.body.innerText ? document.body.innerText.slice(0, 6000) : '');})()"
                            ) { raw ->
                                val cleanText = decodeJsString(raw)
                                val wrapped = BrowserAutomationService.wrapUntrustedWebContent(
                                    PermissionService.normalizeDomain(currentUrl),
                                    cleanText.ifBlank { "Page had no extractable body text." }
                                )
                                onSummarizePage(currentUrl, currentTitle, wrapped)
                            }
                        },
                        modifier = Modifier.testTag("browser_summarize_page_btn")
                    )
                }
                item {
                    GlassChip(
                        label = "Extract Links",
                        selected = false,
                        leadingIcon = Icons.Default.Link,
                        onClick = {
                            webViewRef?.evaluateJavascript(
                                "(function(){try{var out=[];var els=document.querySelectorAll('a[href]');for(var i=0;i<els.length&&out.length<20;i++){var h=els[i].href||'';if(h.indexOf('http://')===0||h.indexOf('https://')===0){out.push({t:(els[i].innerText||h).trim().slice(0,80),u:h});}}return JSON.stringify(out);}catch(e){return '[]';}})()"
                            ) { raw ->
                                val jsonStr = decodeJsString(raw)
                                extractedLinks = parseExtractedLinksJson(jsonStr)
                            }
                        },
                        modifier = Modifier.testTag("browser_extract_links_btn")
                    )
                }
                item {
                    GlassChip(
                        label = "AI Form Prep",
                        selected = false,
                        leadingIcon = Icons.Default.DynamicForm,
                        onClick = { showFormPrepModal = true },
                        modifier = Modifier.testTag("browser_form_prep_btn")
                    )
                }
            }
        }

        val combinedBanner = downloadStatusNote ?: statusBanner
        if (!combinedBanner.isNullOrBlank()) {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(combinedBanner, style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
            }
        }

        if (liveActivity.active || liveActivity.requiresManualIntervention) {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (liveActivity.requiresManualIntervention) "Manual Takeover Required" else "ALLORA Browser Assist Active",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = if (liveActivity.requiresManualIntervention) glass.statusWarning else glass.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = liveActivity.interventionReason ?: liveActivity.statusText,
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (liveActivity.requiresManualIntervention) {
                        GlassButton(text = "Completed Manually", onClick = onResolveManualIntervention, isPrimary = true, leadingIcon = Icons.Default.VerifiedUser)
                    } else {
                        GlassButton(text = "Stop", onClick = onStopAutomation, accentColor = glass.statusError, leadingIcon = Icons.Default.StopCircle)
                    }
                }
            }
        }

        liveActivity.errorInfo?.let { err ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = buildString {
                            append(err.category.displayLabel)
                            err.httpStatusCode?.let { append(" (HTTP $it)") }
                        },
                        style = MaterialTheme.typography.titleSmall.copy(color = glass.statusError, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = err.userMessage,
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(
                            text = "Retry",
                            onClick = {
                                onRetryNavigation()
                                webViewRef?.loadUrl(err.url)
                            }
                        )
                        if (err.supportsCustomTabFallback) {
                            GlassButton(
                                text = "Open in Secure Browser",
                                isPrimary = true,
                                onClick = {
                                    if (launchSecureCustomTab(ctx, err.url)) {
                                        onOpenExternalCustomTab(err.url)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val initialUrl = activeTab?.url ?: BrowserSessionService.DEFAULT_HOME_URL
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val (wvPkg, wvVer) = getWebViewEngineInfo(ctx)
                    onWebViewRuntimeDetected(wvPkg, wvVer)
                    WebView(ctx).apply {
                        configureAlloraWebViewSettings(this)
                        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                            val rawScheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
                            if (rawScheme != "http" && rawScheme != "https") {
                                downloadStatusNote = "Blocked download from unsupported URL scheme."
                                return@setDownloadListener
                            }
                            val resolved = BrowserSessionService.resolveUserUrlOrSearch(url)
                            if (resolved == null) {
                                downloadStatusNote = "Blocked download from invalid URL."
                                return@setDownloadListener
                            }
                            val fileName = URLUtil.guessFileName(resolved, contentDisposition, mimeType)
                                .ifBlank { "allora_download.bin" }
                            pendingDownload = PendingDownloadRequest(
                                url = resolved,
                                fileName = fileName,
                                mimeType = mimeType?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
                            )
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val reqUri = request?.url ?: return true
                                val scheme = reqUri.scheme?.lowercase(Locale.ROOT).orEmpty()
                                val raw = reqUri.toString()
                                if (scheme == "http" || scheme == "https") {
                                    if (request.isForMainFrame) {
                                        onRedirectHop(raw)
                                    }
                                    return false
                                }
                                if (request.isForMainFrame) {
                                    failedNavigationSeq = activeNavigationSeq
                                    onSchemeBlocked(raw, scheme.ifBlank { "unknown" })
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                val navUrl = url ?: return
                                if (!BrowserSessionService.isUsableWebUrl(navUrl)) return
                                activeNavigationSeq += 1
                                failedNavigationSeq = -1
                                activeLoadingUrl = navUrl
                                addressInput = navUrl
                                onNavigationStarted(liveActivity.sessionId, navUrl)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                activeLoadingUrl = ""
                                runCatching { CookieManager.getInstance().flush() }
                                val finalUrl = url ?: return
                                if (failedNavigationSeq == activeNavigationSeq || !BrowserSessionService.isUsableWebUrl(finalUrl)) {
                                    return
                                }
                                addressInput = finalUrl
                                val pageTitle = view?.title.orEmpty()
                                activeTab?.id?.let { tid ->
                                    onPageLoaded(tid, finalUrl, pageTitle)
                                }
                                view?.evaluateJavascript("(document.body && document.body.innerText ? document.body.innerText.slice(0, 800) : '')") { snippet ->
                                    onPageDomExtracted(finalUrl, pageTitle, decodeJsString(snippet))
                                }
                                view?.let { wv ->
                                    if (wv.width > 0 && wv.height > 0) {
                                        runCatching {
                                            val w = wv.width.coerceAtMost(480)
                                            val h = wv.height.coerceAtMost(320)
                                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                                            val canvas = Canvas(bmp)
                                            canvas.scale(w.toFloat() / wv.width, h.toFloat() / wv.height)
                                            wv.draw(canvas)
                                            val baos = ByteArrayOutputStream()
                                            bmp.compress(Bitmap.CompressFormat.PNG, 85, baos)
                                            bmp.recycle()
                                            onVisualFrameCaptured(baos.toByteArray(), w, h)
                                        }
                                    }
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    failedNavigationSeq = activeNavigationSeq
                                    activeLoadingUrl = ""
                                    onHttpError(
                                        request.url?.toString().orEmpty(),
                                        errorResponse?.statusCode ?: 400,
                                        errorResponse?.reasonPhrase.orEmpty()
                                    )
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    failedNavigationSeq = activeNavigationSeq
                                    activeLoadingUrl = ""
                                    onNetworkError(
                                        request.url?.toString().orEmpty(),
                                        error?.errorCode ?: -1,
                                        error?.description?.toString().orEmpty()
                                    )
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                failedNavigationSeq = activeNavigationSeq
                                activeLoadingUrl = ""
                                handler?.cancel()
                                onSslError(error?.url.orEmpty())
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                val progressUrl = view?.url.orEmpty()
                                if (BrowserSessionService.isUsableWebUrl(progressUrl)) {
                                    onProgressChanged(progressUrl, newProgress)
                                }
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                if (filePathCallback == null) return false
                                onLaunchFileUpload(filePathCallback)
                                return true
                            }
                        }
                        lastAutoLoadedUrl = initialUrl
                        loadUrl(initialUrl)
                        webViewRef = this
                    }
                }
            )
            liveActivity.errorInfo?.let { err ->
                BrowserDiagnosticErrorPane(
                    error = err,
                    webViewVersionSummary = listOf(liveActivity.webViewPackage, liveActivity.webViewVersion)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    onRetry = {
                        lastAutoLoadedUrl = ""
                        onRetryNavigation()
                        val target = BrowserSessionService.resolveUserUrlOrSearch(err.url) ?: initialUrl
                        webViewRef?.loadUrl(target)
                    },
                    onOpenSecureBrowser = {
                        if (launchSecureCustomTab(context, err.url)) {
                            onOpenExternalCustomTab(err.url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    readerText?.let { bodyText ->
        GlassModal(onDismissRequest = { readerText = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("browser_reader_modal"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Page Reader View",
                        style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                    )
                    StatusGlassPill(label = "Untrusted Input Isolated", color = glass.statusWarning)
                }
                Text(
                    text = activeTab?.title ?: addressInput,
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(text = "Close", onClick = { readerText = null }, modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Summarize with AI",
                        onClick = {
                            val currentUrl = webViewRef?.url ?: activeTab?.url ?: addressInput
                            val currentTitle = webViewRef?.title ?: activeTab?.title ?: "Webpage"
                            val wrapped = BrowserAutomationService.wrapUntrustedWebContent(
                                PermissionService.normalizeDomain(currentUrl),
                                bodyText
                            )
                            readerText = null
                            onSummarizePage(currentUrl, currentTitle, wrapped)
                        },
                        isPrimary = true,
                        leadingIcon = Icons.Default.AutoAwesome,
                        material = GlassMaterial.PEARL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    extractedLinks?.let { links ->
        GlassModal(onDismissRequest = { extractedLinks = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("browser_links_modal"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Extracted Page Links (${links.size})",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                )
                if (links.isEmpty()) {
                    Text(
                        text = "No standard HTTP/HTTPS links detected on this page.",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(links) { link ->
                            GlassCard(
                                material = GlassMaterial.CLEAR,
                                onClick = {
                                    activeTab?.id?.let { tid -> onNavigate(tid, link.url) }
                                    extractedLinks = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = link.title.ifBlank { link.url },
                                    style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = link.url,
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                GlassButton(text = "Close", onClick = { extractedLinks = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    pendingDownload?.let { dl ->
        GlassModal(onDismissRequest = { if (!isDownloading) pendingDownload = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("browser_download_dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Confirm Isolated Download",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "File: ${dl.fileName}\nType: ${dl.mimeType}\nDomain: ${PermissionService.normalizeDomain(dl.url)}\nSaved strictly inside your private UID workspace.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(
                        text = "Cancel",
                        onClick = { pendingDownload = null },
                        enabled = !isDownloading,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = if (isDownloading) "Saving…" else "Save to Vault",
                        onClick = {
                            isDownloading = true
                            coroutineScope.launch {
                                val bytesResult = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val req = Request.Builder().url(dl.url).get().build()
                                        downloadClient.newCall(req).execute().use { resp ->
                                            if (!resp.isSuccessful) error("HTTP ${resp.code}")
                                            val raw = resp.body?.bytes() ?: ByteArray(0)
                                            require(raw.size <= 15 * 1024 * 1024) { "Download exceeds 15 MB safety limit." }
                                            raw
                                        }
                                    }
                                }
                                bytesResult.fold(
                                    onSuccess = { bytes ->
                                        onDownloadBytes(dl.url, dl.fileName, dl.mimeType, bytes)
                                        downloadStatusNote = "Saved '${dl.fileName}' to isolated workspace."
                                    },
                                    onFailure = { err ->
                                        downloadStatusNote = "Download failed: ${err.message ?: "Unable to fetch file."}"
                                    }
                                )
                                isDownloading = false
                                pendingDownload = null
                            }
                        },
                        enabled = !isDownloading,
                        isPrimary = true,
                        leadingIcon = Icons.Default.Download,
                        material = GlassMaterial.PEARL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showFormPrepModal) {
        val currentDomain = PermissionService.normalizeDomain(webViewRef?.url ?: activeTab?.url ?: addressInput)
        GlassModal(onDismissRequest = { showFormPrepModal = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("browser_form_prep_modal"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "AI-Assisted Form Preparation",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Domain: $currentDomain. Passwords, PINs, OTPs, CVVs, card numbers, and CAPTCHAs are strictly excluded and require manual input.",
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
                GlassInput(
                    value = formSelectorInput,
                    onValueChange = { formSelectorInput = it },
                    label = "Non-sensitive CSS Selector",
                    placeholder = "input[name='q']"
                )
                GlassInput(
                    value = formValueInput,
                    onValueChange = { formValueInput = it },
                    label = "Prepared Field Value",
                    placeholder = "e.g. Research query or non-secret name"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(text = "Cancel", onClick = { showFormPrepModal = false }, modifier = Modifier.weight(1f))
                    GlassButton(
                        text = "Fill Field",
                        onClick = {
                            onPrepareSafeFormFill(currentDomain, formSelectorInput, formValueInput) { script ->
                                webViewRef?.evaluateJavascript(script, null)
                            }
                            showFormPrepModal = false
                        },
                        enabled = formSelectorInput.isNotBlank() && formValueInput.isNotBlank(),
                        isPrimary = true,
                        material = GlassMaterial.PEARL,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showDrawerSheet) {
        GlassBottomSheet(onDismissRequest = { showDrawerSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Personal Browser Workspace", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(8.dp))
                GlassTabBar(
                    tabs = listOf("History", "Bookmarks", "Downloads", "Permissions"),
                    selectedIndex = sheetTabIndex,
                    onSelectTab = { sheetTabIndex = it }
                )
                Spacer(modifier = Modifier.height(10.dp))

                val targetType = when (sheetTabIndex) {
                    1 -> "BOOKMARK"
                    2 -> "DOWNLOAD"
                    3 -> "SITE_PERMISSION"
                    else -> "HISTORY"
                }
                val subset = records.filter { it.recordType == targetType }

                if (subset.isEmpty()) {
                    GlassStateView(icon = Icons.Default.Language, title = "Nothing Recorded Yet", message = "Records are strictly isolated to your signed-in ALLORA workspace.")
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subset, key = { it.id }) { rec ->
                            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(rec.title, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(rec.url, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row {
                                        if (targetType == "BOOKMARK" || targetType == "HISTORY") {
                                            GlassButton(
                                                text = "Open",
                                                onClick = {
                                                    activeTab?.id?.let { tid -> onNavigate(tid, rec.url) }
                                                    showDrawerSheet = false
                                                }
                                            )
                                        } else if (targetType == "SITE_PERMISSION") {
                                            GlassChip(
                                                label = if (rec.permissionMode == "DENIED") "Allow" else "Block",
                                                selected = rec.permissionMode == "DENIED",
                                                onClick = {
                                                    onUpdateSitePermission(rec.domain, if (rec.permissionMode == "DENIED") "ALLOWED" else "DENIED")
                                                }
                                            )
                                        }
                                        IconButton(onClick = { onDeleteRecord(rec.id) }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = glass.textMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return runCatching {
        JSONArray("[$raw]").getString(0)
    }.getOrElse {
        raw.trim('"')
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
    }
}

private fun parseExtractedLinksJson(jsonStr: String): List<ExtractedPageLink> {
    if (jsonStr.isBlank() || jsonStr == "[]") return emptyList()
    return runCatching {
        val arr = JSONArray(jsonStr)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("u").trim()
                val title = obj.optString("t").trim()
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    add(ExtractedPageLink(title = title.ifBlank { url }, url = url))
                }
            }
        }
    }.getOrDefault(emptyList())
}
