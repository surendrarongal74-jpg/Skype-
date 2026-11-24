package com.example.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.services.BrowserControlOwner
import com.example.services.BrowserDomAction
import com.example.services.BrowserDomCommand
import com.example.services.BrowserDomCommandResult
import com.example.services.BrowserEngineState
import com.example.services.BrowserErrorInfo
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserNavigationPhase
import com.example.services.BrowserSessionService
import com.example.services.PermissionService
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

enum class BrowserCardAction {
    PAUSE,
    RESUME,
    STOP,
    TAKE_CONTROL,
    RETURN_CONTROL,
    ZOOM_IN,
    ZOOM_OUT
}

data class LiveBrowserWebViewHooks(
    val onNavigationStarted: (sessionId: String, url: String) -> Unit = { _, _ -> },
    val onRedirect: (url: String) -> Unit = {},
    val onSchemeBlocked: (url: String, scheme: String) -> Unit = { _, _ -> },
    val onProgressChanged: (url: String, progress: Int) -> Unit = { _, _ -> },
    val onPageFinished: (url: String, title: String, domSnippet: String) -> Unit = { _, _, _ -> },
    val onVisualFrameCaptured: (pngBytes: ByteArray, widthPx: Int, heightPx: Int) -> Unit = { _, _, _ -> },
    val onConsumedPendingScript: () -> Unit = {},
    val onReturnControlSynced: (url: String, title: String, domSnippet: String) -> Unit = { _, _, _ -> },
    val onHttpError: (url: String, statusCode: Int, reason: String) -> Unit = { _, _, _ -> },
    val onNetworkError: (url: String, errorCode: Int, description: String) -> Unit = { _, _, _ -> },
    val onSslError: (url: String) -> Unit = { _ -> },
    val onTimeoutError: (url: String) -> Unit = { _ -> },
    val onStopLoading: () -> Unit = {},
    val onRetryRequested: () -> Unit = {},
    val onOpenExternalBrowser: (url: String) -> Unit = {},
    val onRestoreExternalSession: () -> Unit = {},
    val onWebViewRuntimeDetected: (packageName: String, versionName: String) -> Unit = { _, _ -> },
    val onDomCommandCompleted: (commandId: String, result: BrowserDomCommandResult) -> Unit = { _, _ -> },
    val onDownloadRequested: (url: String, fileName: String, mimeType: String) -> Unit = { _, _, _ -> }
)

fun getWebViewEngineInfo(context: Context): Pair<String, String> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        if (pkg != null) {
            return pkg.packageName to (pkg.versionName ?: "")
        }
    }
    val pm = context.packageManager
    val candidates = listOf("com.google.android.webview", "com.android.chrome", "com.android.webview")
    for (pkgName in candidates) {
        val info = runCatching { pm.getPackageInfo(pkgName, 0) }.getOrNull()
        if (info != null) {
            return info.packageName to (info.versionName ?: "")
        }
    }
    return "android.webkit" to ""
}

/**
 * Launches a Chrome Custom Tab using discovered CustomTabsService providers (preferring Chrome),
 * falling back cleanly to the default secure system browser while preserving ALLORA task state.
 */
fun launchSecureCustomTab(context: Context, rawUrl: String): Boolean {
    val resolved = BrowserSessionService.resolveUserUrlOrSearch(rawUrl) ?: return false
    val pm = context.packageManager
    val serviceIntent = Intent("android.support.customtabs.action.CustomTabsService")
    val resolvedPackages = runCatching {
        pm.queryIntentServices(serviceIntent, 0).mapNotNull { it.serviceInfo?.packageName }
    }.getOrDefault(emptyList())
    val preferredPackage = resolvedPackages.firstOrNull { it == "com.android.chrome" }
        ?: resolvedPackages.firstOrNull()

    val customTabIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved)).apply {
        preferredPackage?.let { setPackage(it) }
        putExtras(
            Bundle().apply {
                putBinder("android.support.customtabs.extra.SESSION", null)
            }
        )
        putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launchedCustomTab = runCatching {
        context.startActivity(customTabIntent)
        true
    }.getOrDefault(false)
    if (launchedCustomTab) return true

    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(fallbackIntent)
        true
    }.getOrElse { false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveBrowserChatCard(
    block: ChatBlock.LiveBrowser,
    liveActivity: BrowserLiveActivityState,
    onAction: (BrowserCardAction) -> Unit,
    webViewHooks: LiveBrowserWebViewHooks = LiveBrowserWebViewHooks(),
    modifier: Modifier = Modifier,
    pendingDomCommand: BrowserDomCommand? = null,
    taskProgressSummary: String? = null
) {
    val c = LocalAlloraChatColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var inlineExpanded by remember { mutableStateOf(false) }
    var fullscreenExpanded by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var activeNavigationSeq by remember { mutableIntStateOf(0) }
    var failedNavigationSeq by remember { mutableIntStateOf(-1) }
    var activeLoadingUrl by remember { mutableStateOf("") }
    var lastAutoLoadedUrl by remember { mutableStateOf("") }

    val matchingSession = (block.sessionId.isNotBlank() && liveActivity.sessionId == block.sessionId) ||
        (liveActivity.sessionId.isBlank() && liveActivity.active)
    val effectiveUrl = if (matchingSession && liveActivity.currentUrl.isNotBlank()) {
        liveActivity.currentUrl
    } else block.initialUrl
    val effectiveTitle = if (matchingSession && liveActivity.pageTitle.isNotBlank()) {
        liveActivity.pageTitle
    } else PermissionService.normalizeDomain(effectiveUrl)
    val effectiveTask = if (matchingSession && liveActivity.currentTaskLabel.isNotBlank()) {
        liveActivity.currentTaskLabel
    } else block.taskLabel
    val isUserControlled = matchingSession && liveActivity.controlOwner == BrowserControlOwner.USER
    val isPaused = matchingSession && liveActivity.isPaused
    val isLoading = matchingSession && liveActivity.isLoading
    val progress = if (matchingSession) liveActivity.progressPercent else 100
    val zoomScale = if (matchingSession) liveActivity.zoomScale else 1.0f
    val activeError = if (matchingSession) liveActivity.errorInfo else null
    val activePhase = if (matchingSession) liveActivity.browserPhase else BrowserNavigationPhase.IDLE
    val activeEngineState = if (matchingSession) liveActivity.engineState else BrowserEngineState.AGENT_CONTROL

    // Single persistent WebView preserved across inline <-> fullscreen toggle so login inputs & DOM survive
    val persistentWebView = remember(block.sessionId, block.id) {
        WebView(context).apply {
            configureAlloraWebViewSettings(this)
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val rawScheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
                if (rawScheme == "http" || rawScheme == "https") {
                    val resolved = BrowserSessionService.resolveUserUrlOrSearch(url)
                    if (resolved != null) {
                        val fileName = URLUtil.guessFileName(resolved, contentDisposition, mimeType)
                            .ifBlank { "allora_download.bin" }
                        webViewHooks.onDownloadRequested(
                            resolved,
                            fileName,
                            mimeType?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(persistentWebView) {
        val (pkg, ver) = getWebViewEngineInfo(context)
        webViewHooks.onWebViewRuntimeDetected(pkg, ver)
    }

    DisposableEffect(persistentWebView) {
        persistentWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val raw = request?.url?.toString().orEmpty()
                val scheme = runCatching { Uri.parse(raw).scheme?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                if (request?.isRedirect == true && request.isForMainFrame) {
                    webViewHooks.onRedirect(raw)
                }
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                if (request?.isForMainFrame == true && scheme.isNotBlank()) {
                    webViewHooks.onSchemeBlocked(raw, scheme)
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val navUrl = url ?: return
                if (!BrowserSessionService.isUsableWebUrl(navUrl)) return
                activeLoadingUrl = navUrl
                activeNavigationSeq++
                failedNavigationSeq = -1
                canGoBack = view?.canGoBack() == true
                canGoForward = view?.canGoForward() == true
                webViewHooks.onNavigationStarted(block.sessionId, navUrl)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                activeLoadingUrl = ""
                canGoBack = view?.canGoBack() == true
                canGoForward = view?.canGoForward() == true
                runCatching { CookieManager.getInstance().flush() }
                val finalUrl = url ?: return
                if (failedNavigationSeq == activeNavigationSeq || !BrowserSessionService.isUsableWebUrl(finalUrl)) {
                    return
                }
                val title = view?.title.orEmpty()
                view?.evaluateJavascript(
                    "(document.body && document.body.innerText ? document.body.innerText.slice(0, 800) : '')"
                ) { snippet ->
                    if (failedNavigationSeq != activeNavigationSeq) {
                        webViewHooks.onPageFinished(finalUrl, title, snippet.orEmpty().trim('"'))
                    }
                }
                if (failedNavigationSeq != activeNavigationSeq) {
                    view?.let { wv ->
                        captureVisualFrameIfValid(wv, webViewHooks)
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
                    webViewHooks.onHttpError(
                        request.url?.toString().orEmpty().ifBlank { effectiveUrl },
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
                    webViewHooks.onNetworkError(
                        request.url?.toString().orEmpty().ifBlank { effectiveUrl },
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
                // Strictly refuse insecure certificate overrides; surface clean TLS diagnostic error
                handler?.cancel()
                failedNavigationSeq = activeNavigationSeq
                activeLoadingUrl = ""
                webViewHooks.onSslError(error?.url.orEmpty().ifBlank { effectiveUrl })
            }
        }
        persistentWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    activeLoadingUrl = ""
                }
                val currentViewUrl = view?.url.orEmpty()
                if (failedNavigationSeq != activeNavigationSeq && BrowserSessionService.isUsableWebUrl(currentViewUrl)) {
                    webViewHooks.onProgressChanged(currentViewUrl, newProgress)
                }
            }
        }
        val initial = BrowserSessionService.resolveUserUrlOrSearch(block.initialUrl)
            ?: BrowserSessionService.DEFAULT_HOME_URL
        if (persistentWebView.url.isNullOrBlank() && activeError == null) {
            lastAutoLoadedUrl = initial
            persistentWebView.loadUrl(initial)
        }
        onDispose {
            (persistentWebView.parent as? ViewGroup)?.removeView(persistentWebView)
            persistentWebView.stopLoading()
            persistentWebView.destroy()
        }
    }

    // 25s navigation timeout watchdog
    LaunchedEffect(activeNavigationSeq, isLoading) {
        val watchedUrl = activeLoadingUrl
        if (isLoading && watchedUrl.isNotBlank()) {
            delay(25_000L)
            if (activeLoadingUrl == watchedUrl && liveActivity.isLoading && failedNavigationSeq != activeNavigationSeq) {
                failedNavigationSeq = activeNavigationSeq
                persistentWebView.stopLoading()
                activeLoadingUrl = ""
                webViewHooks.onTimeoutError(watchedUrl)
            }
        }
    }

    // Restore session cleanly when user returns from Chrome Custom Tab
    DisposableEffect(lifecycleOwner, matchingSession, liveActivity.externalHandoffPending) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && matchingSession && liveActivity.externalHandoffPending) {
                webViewHooks.onRestoreExternalSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Never auto-reload when already in error state, disconnected, or repeating an already-dispatched URL
    LaunchedEffect(effectiveUrl, activeError, activePhase) {
        if (activeError != null ||
            activePhase == BrowserNavigationPhase.NAVIGATION_FAILED ||
            activePhase == BrowserNavigationPhase.DISCONNECTED ||
            isLoading
        ) {
            return@LaunchedEffect
        }
        val resolved = BrowserSessionService.resolveUserUrlOrSearch(effectiveUrl) ?: return@LaunchedEffect
        val curLoaded = persistentWebView.url.orEmpty()
        if (curLoaded != resolved && lastAutoLoadedUrl != resolved) {
            lastAutoLoadedUrl = resolved
            persistentWebView.loadUrl(resolved)
        }
    }

    LaunchedEffect(liveActivity.pendingJsScript, isPaused, isUserControlled) {
        val script = liveActivity.pendingJsScript ?: return@LaunchedEffect
        if (!isPaused && !isUserControlled && matchingSession && activeError == null) {
            persistentWebView.evaluateJavascript(script) {
                webViewHooks.onConsumedPendingScript()
            }
        }
    }

    LaunchedEffect(zoomScale) {
        runCatching {
            persistentWebView.settings.textZoom = (zoomScale * 100).roundToInt().coerceIn(75, 200)
            persistentWebView.evaluateJavascript(
                "(function(){if(document&&document.documentElement){document.documentElement.style.zoom='${zoomScale}';}})()",
                null
            )
        }
    }

    LaunchedEffect(pendingDomCommand?.commandId, activeError) {
        val cmd = pendingDomCommand ?: return@LaunchedEffect
        val userMatches = cmd.userId.isBlank() || liveActivity.userId.isBlank() || cmd.userId == liveActivity.userId
        val sessionMatches = cmd.sessionId == block.sessionId || (matchingSession && liveActivity.sessionId == cmd.sessionId)
        if (!sessionMatches) return@LaunchedEffect
        if (!userMatches) {
            webViewHooks.onDomCommandCompleted(
                cmd.commandId,
                BrowserDomCommandResult(false, "UNAUTHORIZED", errorMessage = "User mismatch on DOM command.")
            )
            return@LaunchedEffect
        }
        if (activeError != null) {
            webViewHooks.onDomCommandCompleted(
                cmd.commandId,
                BrowserDomCommandResult(
                    success = false,
                    statusCode = "NAVIGATION_FAILED",
                    finalUrl = persistentWebView.url.orEmpty().ifBlank { effectiveUrl },
                    finalTitle = persistentWebView.title.orEmpty().ifBlank { effectiveTitle },
                    errorMessage = activeError.userMessage.ifBlank { activeError.technicalDetail }
                )
            )
            return@LaunchedEffect
        }
        val currentUrlSnapshot = persistentWebView.url.orEmpty().ifBlank { effectiveUrl }
        val currentTitleSnapshot = persistentWebView.title.orEmpty().ifBlank { effectiveTitle }
        when (cmd.action) {
            BrowserDomAction.GO_BACK -> {
                if (persistentWebView.canGoBack()) {
                    persistentWebView.goBack()
                    webViewHooks.onDomCommandCompleted(
                        cmd.commandId,
                        BrowserDomCommandResult(true, "GO_BACK", "Navigated back in live browser.", currentUrlSnapshot, currentTitleSnapshot)
                    )
                } else {
                    webViewHooks.onDomCommandCompleted(
                        cmd.commandId,
                        BrowserDomCommandResult(false, "CANNOT_GO_BACK", "", currentUrlSnapshot, currentTitleSnapshot, "No back history entry.")
                    )
                }
            }
            BrowserDomAction.GO_FORWARD -> {
                if (persistentWebView.canGoForward()) {
                    persistentWebView.goForward()
                    webViewHooks.onDomCommandCompleted(
                        cmd.commandId,
                        BrowserDomCommandResult(true, "GO_FORWARD", "Navigated forward in live browser.", currentUrlSnapshot, currentTitleSnapshot)
                    )
                } else {
                    webViewHooks.onDomCommandCompleted(
                        cmd.commandId,
                        BrowserDomCommandResult(false, "CANNOT_GO_FORWARD", "", currentUrlSnapshot, currentTitleSnapshot, "No forward history entry.")
                    )
                }
            }
            BrowserDomAction.RELOAD -> {
                persistentWebView.reload()
                webViewHooks.onDomCommandCompleted(
                    cmd.commandId,
                    BrowserDomCommandResult(true, "RELOADING", "Triggered live WebView reload.", currentUrlSnapshot, currentTitleSnapshot)
                )
            }
            BrowserDomAction.STOP -> {
                activeLoadingUrl = ""
                persistentWebView.stopLoading()
                webViewHooks.onStopLoading()
                webViewHooks.onDomCommandCompleted(
                    cmd.commandId,
                    BrowserDomCommandResult(true, "STOPPED", "Stopped active page load.", currentUrlSnapshot, currentTitleSnapshot)
                )
            }
            BrowserDomAction.GET_STATE -> {
                webViewHooks.onDomCommandCompleted(
                    cmd.commandId,
                    BrowserDomCommandResult(true, activeEngineState.name, currentTitleSnapshot, currentUrlSnapshot, currentTitleSnapshot)
                )
            }
            BrowserDomAction.CLICK,
            BrowserDomAction.SCROLL,
            BrowserDomAction.FILL_SAFE,
            BrowserDomAction.EXTRACT_DOM,
            BrowserDomAction.EXTRACT_LINKS -> {
                if (cmd.script.isBlank()) {
                    webViewHooks.onDomCommandCompleted(
                        cmd.commandId,
                        BrowserDomCommandResult(false, "EMPTY_SCRIPT", "", currentUrlSnapshot, currentTitleSnapshot, "Empty DOM command script.")
                    )
                } else {
                    persistentWebView.evaluateJavascript(cmd.script) { raw ->
                        val decoded = decodeLiveJsString(raw)
                        val liveUrl = persistentWebView.url.orEmpty().ifBlank { effectiveUrl }
                        val liveTitle = persistentWebView.title.orEmpty().ifBlank { effectiveTitle }
                        val result = when {
                            decoded == "BLOCKED_PASSWORD_FIELD" -> BrowserDomCommandResult(
                                success = false,
                                statusCode = "BLOCKED_SENSITIVE_FIELD",
                                finalUrl = liveUrl,
                                finalTitle = liveTitle,
                                errorMessage = "Blocked automatic fill on password/OTP/CVV/PIN input."
                            )
                            decoded == "NOT_FOUND" -> BrowserDomCommandResult(
                                success = false,
                                statusCode = "ELEMENT_NOT_FOUND",
                                finalUrl = liveUrl,
                                finalTitle = liveTitle,
                                errorMessage = "Target element selector was not found on the current page."
                            )
                            else -> BrowserDomCommandResult(
                                success = true,
                                statusCode = if (decoded.isBlank()) "EXECUTED" else decoded.take(32),
                                outputText = decoded,
                                finalUrl = liveUrl,
                                finalTitle = liveTitle
                            )
                        }
                        webViewHooks.onDomCommandCompleted(cmd.commandId, result)
                    }
                }
            }
        }
    }

    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(c.cardBg)
            .border(
                width = 1.dp,
                color = when {
                    activeError != null -> Color(0xFFD96B78).copy(alpha = 0.75f)
                    isUserControlled -> c.accentWarning.copy(alpha = 0.75f)
                    else -> c.cardBorder
                },
                shape = shape
            )
            .padding(12.dp)
            .testTag("live_browser_card_${block.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header strip: browser state dot + actual title/URL + Back/Forward/Stop-or-Reload + Expand + Fullscreen
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            activeError != null -> Color(0xFFD96B78)
                            liveActivity.requiresManualIntervention || activePhase == BrowserNavigationPhase.AUTH_REQUIRED -> c.accentWarning
                            isUserControlled -> c.accentBlue
                            isPaused -> c.textMuted
                            else -> c.accentSuccess
                        }
                    )
            )
            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    text = if (effectiveTitle.isNotBlank()) "ALLORA Computer • $effectiveTitle" else "ALLORA Computer • $effectiveTask",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = c.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("browser_page_title_${block.id}")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = c.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (matchingSession) "${liveActivity.computerStage.displayLabel} • $effectiveUrl" else effectiveUrl,
                        style = MaterialTheme.typography.bodySmall.copy(color = c.textMuted, fontSize = 11.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("browser_current_url_${block.id}")
                    )
                }
            }
            IconButton(
                onClick = { if (persistentWebView.canGoBack()) persistentWebView.goBack() },
                enabled = canGoBack,
                modifier = Modifier.size(48.dp).testTag("browser_back_${block.id}")
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = if (canGoBack) c.textSecondary else c.textMuted.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { if (persistentWebView.canGoForward()) persistentWebView.goForward() },
                enabled = canGoForward,
                modifier = Modifier.size(48.dp).testTag("browser_forward_${block.id}")
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward", tint = if (canGoForward) c.textSecondary else c.textMuted.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }
            if (isLoading) {
                IconButton(
                    onClick = {
                        activeLoadingUrl = ""
                        persistentWebView.stopLoading()
                        webViewHooks.onStopLoading()
                    },
                    modifier = Modifier.size(48.dp).testTag("browser_stop_loading_${block.id}")
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Stop loading", tint = Color(0xFFD96B78), modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(
                    onClick = {
                        lastAutoLoadedUrl = ""
                        webViewHooks.onRetryRequested()
                        val target = BrowserSessionService.resolveUserUrlOrSearch(effectiveUrl)
                        if (activeError != null && target != null) {
                            persistentWebView.loadUrl(target)
                        } else {
                            persistentWebView.reload()
                        }
                    },
                    modifier = Modifier.size(48.dp).testTag("browser_reload_${block.id}")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reload page", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(
                onClick = { inlineExpanded = !inlineExpanded },
                modifier = Modifier.size(48.dp).testTag("browser_expand_${block.id}")
            ) {
                Icon(
                    imageVector = if (inlineExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                    contentDescription = if (inlineExpanded) "Collapse browser in chat" else "Expand browser in chat",
                    tint = c.textSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(
                onClick = { fullscreenExpanded = true },
                modifier = Modifier.size(48.dp).testTag("browser_fullscreen_${block.id}")
            ) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = "Fullscreen live browser", tint = c.accentBlue, modifier = Modifier.size(20.dp))
            }
        }

        // Secondary compact strip: task context, inline zoom controls, and loading % indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = effectiveTask,
                style = MaterialTheme.typography.labelSmall.copy(color = c.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.labelSmall.copy(color = c.accentBlue, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.testTag("browser_progress_percent_${block.id}")
                )
            }
            IconButton(
                onClick = { onAction(BrowserCardAction.ZOOM_OUT) },
                modifier = Modifier.size(48.dp).testTag("browser_inline_zoom_out_${block.id}")
            ) {
                Icon(Icons.Rounded.ZoomOut, contentDescription = "Zoom out browser", tint = c.textSecondary, modifier = Modifier.size(16.dp))
            }
            Text(
                text = "${(zoomScale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(color = c.textMuted, fontSize = 11.sp),
                modifier = Modifier.testTag("browser_zoom_label_${block.id}")
            )
            IconButton(
                onClick = { onAction(BrowserCardAction.ZOOM_IN) },
                modifier = Modifier.size(48.dp).testTag("browser_inline_zoom_in_${block.id}")
            ) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = "Zoom in browser", tint = c.textSecondary, modifier = Modifier.size(16.dp))
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).testTag("browser_progress_bar_${block.id}"),
                color = c.accentBlue,
                trackColor = c.hairline
            )
        }

        // Compact Module 5 status strip: Control badge, Connection badge, Verification badge, Task progress
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrowserStatusChip(
                text = if (isUserControlled) "Your Control" else "ALLORA Control",
                accentColor = if (isUserControlled) c.accentWarning else c.accentBlue,
                modifier = Modifier.testTag("browser_control_badge_${block.id}")
            )
            val stageColor = when {
                activeError != null || activePhase == BrowserNavigationPhase.DISCONNECTED || activeEngineState == BrowserEngineState.SESSION_DISCONNECTED -> Color(0xFFD96B78)
                matchingSession && liveActivity.requiresManualIntervention -> c.accentWarning
                else -> c.accentSuccess
            }
            val stageLabel = when {
                activePhase == BrowserNavigationPhase.DISCONNECTED || activeEngineState == BrowserEngineState.SESSION_DISCONNECTED -> "Disconnected"
                matchingSession -> liveActivity.computerStage.displayLabel
                else -> "Ready"
            }
            BrowserStatusChip(
                text = stageLabel,
                accentColor = stageColor,
                modifier = Modifier.testTag("browser_connection_badge_${block.id}")
            )
            if (matchingSession && liveActivity.requiresManualIntervention) {
                BrowserStatusChip(
                    text = liveActivity.interventionKind.displayLabel,
                    accentColor = c.accentWarning,
                    modifier = Modifier.testTag("browser_verification_badge_${block.id}")
                )
            }
            if (!taskProgressSummary.isNullOrBlank()) {
                BrowserStatusChip(
                    text = taskProgressSummary,
                    accentColor = c.textSecondary,
                    modifier = Modifier.testTag("browser_task_progress_badge_${block.id}")
                )
            }
        }

        val bannerNotice = liveActivity.lastResumeRejectionReason ?: liveActivity.lastAuthTransitionNotice
        if (matchingSession && !bannerNotice.isNullOrBlank()) {
            val isRejection = !liveActivity.lastResumeRejectionReason.isNullOrBlank()
            val noticeColor = if (isRejection) Color(0xFFD96B78) else c.accentSuccess
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(noticeColor.copy(alpha = 0.14f))
                    .border(1.dp, noticeColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = bannerNotice,
                    style = MaterialTheme.typography.bodySmall.copy(color = c.textPrimary, fontSize = 12.sp)
                )
            }
        }

        // Embedded real WebView OR diagnostic error fallback
        if (!fullscreenExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (inlineExpanded) 460.dp else 270.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                    .testTag("browser_viewport_${block.id}")
            ) {
                if (activeError != null) {
                    BrowserDiagnosticErrorPane(
                        error = activeError,
                        webViewVersionSummary = listOf(liveActivity.webViewPackage, liveActivity.webViewVersion)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                        onRetry = {
                            lastAutoLoadedUrl = ""
                            webViewHooks.onRetryRequested()
                            val target = BrowserSessionService.resolveUserUrlOrSearch(activeError.url) ?: effectiveUrl
                            persistentWebView.loadUrl(target)
                        },
                        onOpenSecureBrowser = {
                            if (launchSecureCustomTab(context, activeError.url)) {
                                webViewHooks.onOpenExternalBrowser(activeError.url)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PersistentWebViewHost(
                        webView = persistentWebView,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.headerIconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ALLORA Computer open in full-screen ($effectiveTitle)",
                    style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary)
                )
            }
        }

        // Controls Row: Pause/Resume, Take/Return Control, Open in Browser, Stop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrowserControlPill(
                label = if (isPaused) "Resume" else "Pause",
                icon = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                accentColor = c.accentBlue,
                onClick = { onAction(if (isPaused) BrowserCardAction.RESUME else BrowserCardAction.PAUSE) },
                modifier = Modifier.testTag("browser_pause_resume_${block.id}")
            )
            BrowserControlPill(
                label = if (isUserControlled) "Return Control" else "Take Control",
                icon = if (isUserControlled) Icons.Rounded.SmartToy else Icons.Rounded.PanTool,
                accentColor = if (isUserControlled) c.accentSuccess else c.accentWarning,
                onClick = {
                    if (isUserControlled) {
                        persistentWebView.evaluateJavascript(
                            "(document.body && document.body.innerText ? document.body.innerText.slice(0, 800) : '')"
                        ) { snippet ->
                            webViewHooks.onReturnControlSynced(
                                persistentWebView.url ?: effectiveUrl,
                                persistentWebView.title ?: effectiveTitle,
                                snippet.orEmpty().trim('"')
                            )
                        }
                    } else {
                        onAction(BrowserCardAction.TAKE_CONTROL)
                    }
                },
                modifier = Modifier.testTag("browser_control_toggle_${block.id}")
            )
            IconButton(
                onClick = {
                    if (launchSecureCustomTab(context, effectiveUrl)) {
                        webViewHooks.onOpenExternalBrowser(effectiveUrl)
                    }
                },
                modifier = Modifier.size(48.dp).testTag("browser_external_tab_${block.id}")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "Open in Secure Browser",
                    tint = c.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            BrowserControlPill(
                label = "Stop",
                icon = Icons.Rounded.Stop,
                accentColor = Color(0xFFD96B78),
                onClick = { onAction(BrowserCardAction.STOP) },
                modifier = Modifier.testTag("browser_stop_${block.id}")
            )
        }
    }

    if (fullscreenExpanded) {
        Dialog(
            onDismissRequest = { fullscreenExpanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag("browser_fullscreen_dialog_${block.id}"),
                color = c.cardBg
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { if (persistentWebView.canGoBack()) persistentWebView.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = c.textPrimary)
                        }
                        IconButton(
                            onClick = { if (persistentWebView.canGoForward()) persistentWebView.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward", tint = c.textPrimary)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(effectiveTitle, style = MaterialTheme.typography.titleMedium.copy(color = c.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${activeEngineState.displayLabel} • $effectiveUrl", style = MaterialTheme.typography.bodySmall.copy(color = c.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onAction(BrowserCardAction.ZOOM_OUT) }, modifier = Modifier.size(48.dp).testTag("browser_zoom_out_${block.id}")) {
                            Icon(Icons.Rounded.ZoomOut, contentDescription = "Zoom out browser", tint = c.textSecondary)
                        }
                        IconButton(onClick = { onAction(BrowserCardAction.ZOOM_IN) }, modifier = Modifier.size(48.dp).testTag("browser_zoom_in_${block.id}")) {
                            Icon(Icons.Rounded.ZoomIn, contentDescription = "Zoom in browser", tint = c.textSecondary)
                        }
                        if (isLoading) {
                            IconButton(
                                onClick = {
                                    activeLoadingUrl = ""
                                    persistentWebView.stopLoading()
                                    webViewHooks.onStopLoading()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Stop loading", tint = Color(0xFFD96B78))
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    lastAutoLoadedUrl = ""
                                    webViewHooks.onRetryRequested()
                                    persistentWebView.reload()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Reload page", tint = c.textPrimary)
                            }
                        }
                        IconButton(onClick = { fullscreenExpanded = false }, modifier = Modifier.size(48.dp).testTag("browser_fullscreen_close_${block.id}")) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close full-screen browser", tint = c.textPrimary)
                        }
                    }
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { (progress / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = c.accentBlue,
                            trackColor = c.hairline
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                        if (activeError != null) {
                            BrowserDiagnosticErrorPane(
                                error = activeError,
                                webViewVersionSummary = listOf(liveActivity.webViewPackage, liveActivity.webViewVersion)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" "),
                                onRetry = {
                                    lastAutoLoadedUrl = ""
                                    webViewHooks.onRetryRequested()
                                    val target = BrowserSessionService.resolveUserUrlOrSearch(activeError.url) ?: effectiveUrl
                                    persistentWebView.loadUrl(target)
                                },
                                onOpenSecureBrowser = {
                                    if (launchSecureCustomTab(context, activeError.url)) {
                                        webViewHooks.onOpenExternalBrowser(activeError.url)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            PersistentWebViewHost(
                                webView = persistentWebView,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersistentWebViewHost(
    webView: WebView,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        update = { view ->
            if (view.parent == null) {
                // Re-attach safeguard if recomposed
            }
        }
    )
}

@Composable
fun BrowserDiagnosticErrorPane(
    error: BrowserErrorInfo,
    onRetry: () -> Unit,
    onOpenSecureBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    webViewVersionSummary: String = ""
) {
    val c = LocalAlloraChatColors.current
    Column(
        modifier = modifier
            .background(c.headerIconBg)
            .padding(16.dp)
            .testTag("browser_error_pane"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = error.category.displayLabel,
            tint = Color(0xFFD96B78),
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = buildString {
                append(error.category.displayLabel)
                error.httpStatusCode?.let { append(" (HTTP $it)") }
            },
            style = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = error.userMessage.ifBlank { error.technicalDetail },
            style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary, fontSize = 12.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        val diagLine = buildString {
            if (error.technicalDetail.isNotBlank()) append(error.technicalDetail)
            if (webViewVersionSummary.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(webViewVersionSummary)
            }
        }
        if (diagLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = diagLine,
                style = MaterialTheme.typography.labelSmall.copy(color = c.textMuted, fontSize = 11.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("browser_error_retry_btn")
            ) {
                Text("Retry")
            }
            if (error.supportsCustomTabFallback) {
                Button(
                    onClick = onOpenSecureBrowser,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("browser_error_custom_tab_btn")
                ) {
                    Text("Open in Secure Browser")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun configureAlloraWebViewSettings(webView: WebView) {
    runCatching {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
}

private fun captureVisualFrameIfValid(webView: WebView, hooks: LiveBrowserWebViewHooks) {
    if (webView.width <= 0 || webView.height <= 0) return
    runCatching {
        val w = webView.width.coerceAtMost(480)
        val h = webView.height.coerceAtMost(320)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.scale(w.toFloat() / webView.width, h.toFloat() / webView.height)
        webView.draw(canvas)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 85, baos)
        bmp.recycle()
        hooks.onVisualFrameCaptured(baos.toByteArray(), w, h)
    }
}

@Composable
private fun BrowserControlPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.42f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = accentColor, modifier = Modifier.size(15.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun BrowserStatusChip(
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accentColor.copy(alpha = 0.12f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accentColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = c.textPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 10.5.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun decodeLiveJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return runCatching {
        JSONArray("[$raw]").getString(0)
    }.getOrElse {
        raw.trim('"')
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
    }
}
