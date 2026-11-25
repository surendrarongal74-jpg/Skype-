package com.example.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import com.example.agent.AutonomousTaskRecord
import com.example.data.ai.orchestrator.AiSelectionMode
import com.example.data.ai.orchestrator.AiStatusPhase
import com.example.data.ai.orchestrator.AlloraChatInterfaceState
import com.example.data.ai.orchestrator.ManualModelUnavailableException
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserNavigationPhase
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.SubDestination
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class AlloraTopTab(val label: String) {
    CHAT("Chat"),
    PROJECTS("Projects")
}

private sealed interface ChatListEntry {
    val key: String
    data class DateDivider(override val key: String, val label: String) : ChatListEntry
    data class UserRow(override val key: String, val message: AlloraRichMessage, val topGapDp: Int) : ChatListEntry
    data class AssistantStreamingRow(override val key: String, val message: AlloraRichMessage, val topGapDp: Int) : ChatListEntry
    data class AssistantBlockRow(
        override val key: String,
        val message: AlloraRichMessage,
        val block: ChatBlock,
        val blockIndex: Int,
        val isLastBlock: Boolean,
        val topGapDp: Int
    ) : ChatListEntry
}

@Composable
fun AlloraChatScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val profile by viewModel.userProfile.collectAsState()
    val reducedMotion by viewModel.reducedMotion.collectAsState()
    val threads by viewModel.richThreads.collectAsState()
    val activeThreadId by viewModel.activeThreadId.collectAsState()
    val threadTitle by viewModel.activeThreadTitle.collectAsState()
    val messages by viewModel.richMessages.collectAsState()
    val isTypingDotsVisible by viewModel.chatTypingState.collectAsState()
    val liveTaskStep by viewModel.chatLiveTaskStep.collectAsState()
    val replyingTo by viewModel.replyingToMessage.collectAsState()
    val browserLiveActivity by viewModel.browserLiveActivity.collectAsState()
    val pendingDomCommand by viewModel.pendingBrowserDomCommand.collectAsState()
    val autonomousTasks by viewModel.autonomousTasks.collectAsState()
    val aiStatusPhase by viewModel.aiStatusPhase.collectAsState()
    val chatInterfaceState by viewModel.chatInterfaceState.collectAsState()
    val isRetryingMessage by viewModel.isRetryingMessage.collectAsState()
    val activeAiModel by viewModel.activeAiModelConfig.collectAsState()
    val manualModeBlockedNotice by viewModel.manualModeBlockedNotice.collectAsState()

    val initialChar = profile.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S"
    val webViewHooks = remember(viewModel) {
        LiveBrowserWebViewHooks(
            onNavigationStarted = { sessionId, url ->
                viewModel.syncBrowserWebViewNavigationStarted(sessionId, url)
            },
            onRedirect = { url ->
                viewModel.syncBrowserRedirect(url)
            },
            onSchemeBlocked = { url, scheme ->
                viewModel.syncBrowserSchemeBlocked(url, scheme)
            },
            onProgressChanged = { url, progress ->
                viewModel.syncBrowserWebViewProgress(url, progress)
            },
            onPageFinished = { url, title, domSnippet ->
                viewModel.syncBrowserWebViewPageFinished(url, title, domSnippet)
            },
            onVisualFrameCaptured = { pngBytes, w, h ->
                viewModel.syncBrowserVisualFrame(pngBytes, w, h)
            },
            onConsumedPendingScript = {
                viewModel.consumeBrowserPendingScript()
            },
            onReturnControlSynced = { url, title, domSnippet ->
                viewModel.returnBrowserControlWithSync(url, title, domSnippet)
            },
            onHttpError = { url, statusCode, reason ->
                viewModel.syncBrowserHttpError(url, statusCode, reason)
            },
            onNetworkError = { url, errorCode, description ->
                viewModel.syncBrowserNetworkError(url, errorCode, description)
            },
            onSslError = { url ->
                viewModel.syncBrowserSslError(url)
            },
            onTimeoutError = { url ->
                viewModel.syncBrowserTimeout(url)
            },
            onStopLoading = {
                viewModel.stopBrowserPageLoading()
            },
            onRetryRequested = {
                viewModel.retryBrowserNavigation()
            },
            onOpenExternalBrowser = { url ->
                viewModel.openExternalBrowserHandoff(url)
            },
            onRestoreExternalSession = {
                viewModel.restoreFromExternalBrowser()
            },
            onWebViewRuntimeDetected = { pkg, ver ->
                viewModel.syncWebViewRuntimeInfo(pkg, ver)
            },
            onDomCommandCompleted = { cmdId, result ->
                viewModel.completeBrowserDomCommand(cmdId, result)
            },
            onDownloadRequested = { url, fileName, mimeType ->
                viewModel.downloadFromLiveBrowser(url, fileName, mimeType)
            }
        )
    }

    AlloraChatThemeScope(darkTheme = glass.isDark) {
        AlloraChatContent(
            threadTitle = threadTitle,
            userInitial = initialChar,
            isDarkTheme = glass.isDark,
            reducedMotion = reducedMotion,
            messages = messages,
            threads = threads,
            activeThreadId = activeThreadId,
            isTypingDotsVisible = isTypingDotsVisible,
            liveTaskStep = liveTaskStep,
            replyingTo = replyingTo,
            onBack = onBack,
            onSendPrompt = { prompt, attachments ->
                viewModel.sendRichChatPrompt(prompt, attachments, replyingTo)
            },
            onRetryMessage = { failedMsgId ->
                viewModel.retryRichChatMessage(failedMsgId)
            },
            onSetReplyingTo = { viewModel.setReplyingTo(it) },
            onPlaceOrder = { msgId, blkId -> viewModel.markOrderPlaced(msgId, blkId) },
            onSelectThread = { tid -> viewModel.selectChatThread(tid) },
            onCreateThread = { title, prompt ->
                viewModel.createNewChatThread(title = title, category = "Projects", initialPrompt = prompt)
            },
            onClearConversation = { viewModel.clearAiConversation() },
            onToggleDarkTheme = { dark -> viewModel.setDarkGlassTheme(dark) },
            onToggleReducedMotion = { enabled -> viewModel.setReducedMotion(enabled) },
            onNavigateSub = onNavigateSub,
            modifier = modifier,
            browserLiveActivity = browserLiveActivity,
            autonomousTasks = autonomousTasks,
            webViewHooks = webViewHooks,
            onBrowserAction = { action -> viewModel.handleLiveBrowserAction(action) },
            onInterventionTakeControl = { sid -> viewModel.takeControlForIntervention(sid) },
            onInterventionContinue = { sid -> viewModel.continueAfterIntervention(sid) },
            onApprovePayment = { msgId, blk -> viewModel.approveChatPayment(msgId, blk) },
            onRejectPayment = { msgId, blk -> viewModel.rejectChatPayment(msgId, blk) },
            onSendEmailApproval = { msgId, blk, r, s, b ->
                viewModel.approveAndSendChatEmail(msgId, blk, r, s, b)
            },
            onRejectEmailApproval = { msgId, blk -> viewModel.rejectChatEmail(msgId, blk) },
            onRespondBrowserPermission = { msgId, blk, mode ->
                viewModel.respondBrowserPermission(msgId, blk, mode)
            },
            onApproveRegistration = { msgId, blk -> viewModel.approveRegistration(msgId, blk) },
            onRejectRegistration = { msgId, blk -> viewModel.rejectRegistration(msgId, blk) },
            onWalletManage = { onNavigateSub(SubDestination.WALLET) },
            onTaskControl = { taskId, action -> viewModel.controlAutonomousTask(taskId, action) },
            aiStatusPhase = aiStatusPhase,
            activeAiModelName = activeAiModel?.displayName,
            manualModeBlockedNotice = manualModeBlockedNotice,
            onAcceptRecommendedFallback = { viewModel.acceptRecommendedManualFallback() },
            onSwitchToAutomaticMode = { viewModel.setAiSelectionMode(AiSelectionMode.AUTOMATIC) },
            onDismissManualNotice = { viewModel.dismissManualModeBlockedNotice() },
            pendingDomCommand = pendingDomCommand,
            onEmailReply = { emailId -> viewModel.stageReplyOrForwardInChat(emailId, isForward = false) },
            onEmailForward = { emailId -> viewModel.stageReplyOrForwardInChat(emailId, isForward = true) },
            onEmailDelete = { emailId -> viewModel.stageDeleteConfirmationInChat(emailId) },
            onExplainWalletTx = { txId -> viewModel.explainWalletTxInChat(txId) },
            chatInterfaceState = chatInterfaceState,
            isRetryingMessage = isRetryingMessage,
            onVerifySessionRequested = { viewModel.requestSessionReverification() }
        )
    }
}

@Composable
fun AlloraChatContent(
    threadTitle: String,
    userInitial: String,
    isDarkTheme: Boolean,
    reducedMotion: Boolean,
    messages: List<AlloraRichMessage>,
    threads: List<AlloraChatThread>,
    activeThreadId: String,
    isTypingDotsVisible: Boolean,
    liveTaskStep: String,
    replyingTo: AlloraRichMessage?,
    onBack: () -> Unit,
    onSendPrompt: (String, List<ChatAttachment>) -> Unit,
    onSetReplyingTo: (AlloraRichMessage?) -> Unit,
    onPlaceOrder: (String, String) -> Unit,
    onSelectThread: (String) -> Unit,
    onCreateThread: (String, String?) -> Unit,
    onClearConversation: () -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleReducedMotion: (Boolean) -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier,
    composerSpeechBackend: ComposerSpeechBackend? = null,
    onRetryMessage: ((String) -> Unit)? = null,
    browserLiveActivity: BrowserLiveActivityState = BrowserLiveActivityState(),
    autonomousTasks: List<AutonomousTaskRecord> = emptyList(),
    webViewHooks: LiveBrowserWebViewHooks = LiveBrowserWebViewHooks(),
    onBrowserAction: (BrowserCardAction) -> Unit = {},
    onInterventionTakeControl: (sessionId: String) -> Unit = {},
    onInterventionContinue: (sessionId: String) -> Unit = {},
    onApprovePayment: (msgId: String, block: ChatBlock.PaymentApproval) -> Unit = { _, _ -> },
    onRejectPayment: (msgId: String, block: ChatBlock.PaymentApproval) -> Unit = { _, _ -> },
    onSendEmailApproval: (msgId: String, block: ChatBlock.EmailApproval, recipient: String, subject: String, body: String) -> Unit = { _, _, _, _, _ -> },
    onRejectEmailApproval: (msgId: String, block: ChatBlock.EmailApproval) -> Unit = { _, _ -> },
    onRespondBrowserPermission: (msgId: String, block: ChatBlock.BrowserPermission, mode: String) -> Unit = { _, _, _ -> },
    onApproveRegistration: (msgId: String, block: ChatBlock.RegistrationApproval) -> Unit = { _, _ -> },
    onRejectRegistration: (msgId: String, block: ChatBlock.RegistrationApproval) -> Unit = { _, _ -> },
    onWalletManage: () -> Unit = { onNavigateSub(SubDestination.WALLET) },
    onTaskControl: (taskId: String, action: String) -> Unit = { _, _ -> },
    aiStatusPhase: AiStatusPhase = AiStatusPhase.IDLE,
    activeAiModelName: String? = null,
    manualModeBlockedNotice: ManualModelUnavailableException? = null,
    onAcceptRecommendedFallback: () -> Unit = {},
    onSwitchToAutomaticMode: () -> Unit = {},
    onDismissManualNotice: () -> Unit = {},
    pendingDomCommand: com.example.services.BrowserDomCommand? = null,
    onEmailReply: (String) -> Unit = {},
    onEmailForward: (String) -> Unit = {},
    onEmailDelete: (String) -> Unit = {},
    onExplainWalletTx: (String) -> Unit = {},
    chatInterfaceState: AlloraChatInterfaceState = AlloraChatInterfaceState.IDLE,
    isRetryingMessage: Boolean = false,
    onVerifySessionRequested: () -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(AlloraTopTab.CHAT) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }
    var lastSubmitTimestampMs by remember { mutableStateOf(0L) }
    var lastSubmittedSignature by remember { mutableStateOf("") }
    val pendingAttachments = remember { mutableStateListOf<ChatAttachment>() }

    val resolvedSpeechBackend = remember(context, composerSpeechBackend) {
        composerSpeechBackend ?: DefaultComposerSpeechBackend(context.applicationContext)
    }
    val voiceController = remember(scope, resolvedSpeechBackend) {
        ComposerVoiceInputController(scope, resolvedSpeechBackend)
    }
    val voiceState by voiceController.state.collectAsState()
    DisposableEffect(voiceController) {
        onDispose { voiceController.cancel() }
    }

    var longPressedMessage by remember { mutableStateOf<AlloraRichMessage?>(null) }
    var viewerImages by remember { mutableStateOf<List<ChatImageItem>?>(null) }
    var viewerInitialIndex by remember { mutableIntStateOf(0) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val filteredMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) messages
        else messages.filter { msg ->
            msg.plainText.contains(searchQuery, ignoreCase = true) ||
                msg.blocks.any { blk ->
                    when (blk) {
                        is ChatBlock.Text -> blk.content.contains(searchQuery, ignoreCase = true)
                        is ChatBlock.Summary -> blk.title.contains(searchQuery, true) || blk.body.contains(searchQuery, true)
                        is ChatBlock.Draft -> blk.title.contains(searchQuery, true) || blk.body.contains(searchQuery, true)
                        is ChatBlock.Table -> blk.title.contains(searchQuery, true)
                        is ChatBlock.OrderSummary -> blk.merchantName.contains(searchQuery, true)
                        is ChatBlock.Links -> blk.items.any { it.name.contains(searchQuery, true) }
                        is ChatBlock.Images -> blk.caption?.contains(searchQuery, true) == true
                        is ChatBlock.Task -> blk.steps.any { it.contains(searchQuery, true) }
                        is ChatBlock.LiveBrowser -> blk.initialUrl.contains(searchQuery, true) || blk.taskLabel.contains(searchQuery, true)
                        is ChatBlock.HumanIntervention -> blk.website.contains(searchQuery, true) || blk.reason.contains(searchQuery, true)
                        is ChatBlock.PaymentApproval -> blk.productName.contains(searchQuery, true) || blk.merchant.contains(searchQuery, true)
                        is ChatBlock.EmailApproval -> blk.recipient.contains(searchQuery, true) || blk.subject.contains(searchQuery, true)
                        is ChatBlock.EmailDigest -> blk.title.contains(searchQuery, true) || blk.items.any { it.subject.contains(searchQuery, true) || it.sender.contains(searchQuery, true) }
                        is ChatBlock.BrowserPermission -> blk.domain.contains(searchQuery, true) || blk.actionLabel.contains(searchQuery, true)
                        is ChatBlock.RegistrationApproval -> blk.siteName.contains(searchQuery, true) || blk.url.contains(searchQuery, true)
                        is ChatBlock.WalletCard -> blk.availableBalance.contains(searchQuery, true) || blk.currency.contains(searchQuery, true)
                        is ChatBlock.TaskTimeline -> blk.title.contains(searchQuery, true) || blk.goal.contains(searchQuery, true)
                    }
                }
        }
    }

    val listEntries = remember(filteredMessages) {
        buildList {
            if (filteredMessages.isNotEmpty()) {
                add(
                    ChatListEntry.DateDivider(
                        key = "date_divider_top",
                        label = ChatBlockJsonParser.formatDateDivider(filteredMessages.first().createdAtMs)
                    )
                )
            }
            filteredMessages.forEachIndexed { index, msg ->
                val prevSameSpeaker = index > 0 && filteredMessages[index - 1].isUser == msg.isUser
                val speakerGap = if (index == 0) 4 else if (prevSameSpeaker) 5 else 15

                if (msg.isUser) {
                    add(ChatListEntry.UserRow(key = msg.id, message = msg, topGapDp = speakerGap))
                } else if (msg.isStreaming) {
                    add(
                        ChatListEntry.AssistantStreamingRow(
                            key = "${msg.id}_blk_0",
                            message = msg,
                            topGapDp = speakerGap
                        )
                    )
                } else {
                    val blocks = msg.blocks.ifEmpty {
                        listOf(ChatBlock.Text(id = "${msg.id}_txt", content = msg.plainText))
                    }
                    blocks.forEachIndexed { blkIdx, block ->
                        add(
                            ChatListEntry.AssistantBlockRow(
                                key = "${msg.id}_blk_$blkIdx",
                                message = msg,
                                block = block,
                                blockIndex = blkIdx,
                                isLastBlock = blkIdx == blocks.lastIndex,
                                topGapDp = if (blkIdx == 0) speakerGap else 6
                            )
                        )
                    }
                }
            }
        }
    }

    // Track message IDs already loaded initially vs newly added during this session
    val initialSnapshotIds = remember(activeThreadId) {
        messages.map { it.id }.toMutableSet()
    }
    val completedWordAnimations = remember(activeThreadId) { mutableSetOf<String>() }

    // Scroll tracking + unread badge while scrolled up
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }
    var unreadWhileScrolledUp by remember { mutableIntStateOf(0) }
    var previousMsgCount by remember(activeThreadId) { mutableIntStateOf(messages.size) }
    var initialScrollDone by remember(activeThreadId) { mutableStateOf(false) }

    LaunchedEffect(
        activeThreadId,
        listEntries.size,
        messages.lastOrNull()?.isStreaming,
        messages.lastOrNull()?.plainText?.length
    ) {
        val lastIdx = listEntries.lastIndex
        if (lastIdx >= 0) {
            if (!initialScrollDone) {
                initialScrollDone = true
                listState.scrollToItem(lastIdx)
            } else if (isNearBottom) {
                unreadWhileScrolledUp = 0
                listState.animateScrollToItem(lastIdx)
            } else if (messages.size > previousMsgCount) {
                unreadWhileScrolledUp += (messages.size - previousMsgCount)
            }
        }
        previousMsgCount = messages.size
    }

    LaunchedEffect(isNearBottom) {
        if (isNearBottom) unreadWhileScrolledUp = 0
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1400L)
            highlightedMessageId = null
        }
    }

    // Photo & File pickers (Play-policy compliant zero-permission Photo Picker + OpenDocument)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingAttachments.add(
                ChatAttachment(
                    uriString = uri.toString(),
                    displayName = resolveDisplayName(context, uri) ?: "Photo",
                    isImage = true,
                    mimeType = context.contentResolver.getType(uri).orEmpty()
                )
            )
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingAttachments.add(
                ChatAttachment(
                    uriString = uri.toString(),
                    displayName = resolveDisplayName(context, uri) ?: "Document",
                    isImage = false,
                    mimeType = context.contentResolver.getType(uri).orEmpty()
                )
            )
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceController.startRecording(
                currentDraft = inputText,
                languageTag = Locale.getDefault().toLanguageTag(),
                onUpdateInput = { populated -> inputText = populated }
            )
        } else {
            voiceController.setStatusError(
                "Microphone permission is required to record voice input."
            )
        }
    }

    val handleMicButtonClick: () -> Unit = {
        when {
            voiceState.isRecording -> voiceController.stopAndFinalize()
            voiceState.phase == ComposerVoicePhase.TRANSCRIBING -> Unit
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                voiceController.toggleRecording(
                    currentDraft = inputText,
                    languageTag = Locale.getDefault().toLanguageTag(),
                    onUpdateInput = { populated -> inputText = populated }
                )
            }
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler {
        when {
            voiceState.isActive -> voiceController.cancel { restored -> inputText = restored }
            viewerImages != null -> viewerImages = null
            longPressedMessage != null -> longPressedMessage = null
            searchExpanded -> {
                searchExpanded = false
                searchQuery = ""
            }
            activeTab != AlloraTopTab.CHAT -> activeTab = AlloraTopTab.CHAT
            else -> onBack()
        }
    }

    val handleOpenUrl: (String) -> Unit = { url ->
        val safeUrl = url.trim()
        if (safeUrl.startsWith("http://") || safeUrl.startsWith("https://") || safeUrl.startsWith("geo:") || safeUrl.startsWith("mailto:")) {
            runCatching { uriHandler.openUri(safeUrl) }.onFailure {
                Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
            }
        } else if (safeUrl.isNotBlank()) {
            val encoded = Uri.encode(safeUrl)
            runCatching { uriHandler.openUri("https://www.google.com/search?q=$encoded") }
        }
    }

    AlloraChatAtmosphericCanvas(
        modifier = modifier.fillMaxSize()
    ) {
        val mainBlurRadius = if (longPressedMessage != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 14.dp else 0.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .then(if (mainBlurRadius > 0.dp) Modifier.blur(mainBlurRadius) else Modifier)
        ) {
            // 1. TOP BAR: House icon left, conversation title middle, Search + round avatar right
            AlloraChatTopBar(
                title = threadTitle,
                userInitial = userInitial,
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                activeTab = activeTab,
                isDarkTheme = isDarkTheme,
                reducedMotion = reducedMotion,
                profileMenuExpanded = profileMenuExpanded,
                onHomeClick = onBack,
                onSearchToggle = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) searchQuery = ""
                },
                onSearchQueryChange = { searchQuery = it },
                onAvatarClick = { profileMenuExpanded = true },
                onDismissProfileMenu = { profileMenuExpanded = false },
                onSelectTab = { activeTab = it },
                onToggleDarkTheme = {
                    profileMenuExpanded = false
                    onToggleDarkTheme(!isDarkTheme)
                },
                onToggleReducedMotion = {
                    profileMenuExpanded = false
                    onToggleReducedMotion(!reducedMotion)
                },
                onOpenLive = {
                    profileMenuExpanded = false
                    onNavigateSub(SubDestination.LIVE_ASSISTANT)
                },
                onOpenAutonomousTasks = {
                    profileMenuExpanded = false
                    onNavigateSub(SubDestination.AUTONOMOUS_TASKS)
                },
                onOpenWallet = {
                    profileMenuExpanded = false
                    onNavigateSub(SubDestination.WALLET)
                },
                onOpenEmail = {
                    profileMenuExpanded = false
                    onNavigateSub(SubDestination.PERSONAL_EMAIL)
                },
                onOpenPermissions = {
                    profileMenuExpanded = false
                    onNavigateSub(SubDestination.PERMISSION_CENTER)
                },
                onOpenAiModels = {
                    profileMenuExpanded = false
                    onNavigateSub(SubDestination.AI_MODELS)
                },
                onNewChat = {
                    profileMenuExpanded = false
                    onClearConversation()
                }
            )

            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(tween(180)).togetherWith(fadeOut(tween(140)))
                },
                label = "chatVsProjectsTab",
                modifier = Modifier.weight(1f)
            ) { tab ->
                if (tab == AlloraTopTab.PROJECTS) {
                    AlloraProjectsTabPanel(
                        threads = threads,
                        activeThreadId = activeThreadId,
                        onSelectThread = { tid ->
                            onSelectThread(tid)
                            activeTab = AlloraTopTab.CHAT
                        },
                        onCreateProject = { title, starterPrompt ->
                            onCreateThread(title, starterPrompt)
                            activeTab = AlloraTopTab.CHAT
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("allora_chat_list"),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 20.dp)
                        ) {
                            itemsIndexed(
                                items = listEntries,
                                key = { _, entry -> entry.key }
                            ) { _, entry ->
                                val jumpToQuote: (String) -> Unit = { quotedId ->
                                    val targetIndex = listEntries.indexOfFirst { item ->
                                        when (item) {
                                            is ChatListEntry.UserRow -> item.message.id == quotedId
                                            is ChatListEntry.AssistantStreamingRow -> item.message.id == quotedId
                                            is ChatListEntry.AssistantBlockRow -> item.message.id == quotedId
                                            is ChatListEntry.DateDivider -> false
                                        }
                                    }
                                    if (targetIndex >= 0) {
                                        scope.launch {
                                            listState.animateScrollToItem(targetIndex)
                                            highlightedMessageId = quotedId
                                        }
                                    }
                                }
                                val retryThisMessage: () -> Unit = {
                                    if (!isRetryingMessage) {
                                        val failedId = when (entry) {
                                            is ChatListEntry.AssistantBlockRow -> entry.message.id
                                            is ChatListEntry.AssistantStreamingRow -> entry.message.id
                                            is ChatListEntry.UserRow -> entry.message.id
                                            is ChatListEntry.DateDivider -> ""
                                        }
                                        if (onRetryMessage != null && failedId.isNotBlank()) {
                                            onRetryMessage(failedId)
                                        } else {
                                            val lastUser = messages.lastOrNull { it.isUser }?.plainText
                                            if (!lastUser.isNullOrBlank()) {
                                                onSendPrompt(lastUser, emptyList())
                                            }
                                        }
                                    }
                                }

                                when (entry) {
                                    is ChatListEntry.DateDivider -> {
                                        ChatDateSeparator(label = entry.label)
                                    }
                                    is ChatListEntry.UserRow -> {
                                        val isNewlyArrived = entry.message.id !in initialSnapshotIds
                                        Spacer(modifier = Modifier.height(entry.topGapDp.dp))
                                        SwipeableChatRow(
                                            message = entry.message,
                                            isHighlighted = highlightedMessageId == entry.message.id,
                                            isPressedForMenu = longPressedMessage?.id == entry.message.id,
                                            showTypingDots = false,
                                            liveTaskStep = "",
                                            shouldAnimateEntry = isNewlyArrived,
                                            shouldAnimateWords = false,
                                            reducedMotion = reducedMotion,
                                            onSwipeReply = { target -> onSetReplyingTo(target) },
                                            onLongPress = { target -> longPressedMessage = target },
                                            onQuoteTap = jumpToQuote,
                                            onOpenUrl = handleOpenUrl,
                                            onOpenPhotoViewer = { imgs, startIdx ->
                                                viewerImages = imgs
                                                viewerInitialIndex = startIdx
                                            },
                                            onPlaceOrder = onPlaceOrder,
                                            onChipClick = { chip -> onSendPrompt(chip, emptyList()) },
                                            onRetry = retryThisMessage,
                                            onFinishedWordStream = { doneId -> initialSnapshotIds.add(doneId) },
                                            browserLiveActivity = browserLiveActivity,
                                            autonomousTasks = autonomousTasks,
                                            webViewHooks = webViewHooks,
                                            onBrowserAction = onBrowserAction,
                                            onInterventionTakeControl = onInterventionTakeControl,
                                            onInterventionContinue = onInterventionContinue,
                                            onApprovePayment = onApprovePayment,
                                            onRejectPayment = onRejectPayment,
                                            onSendEmailApproval = onSendEmailApproval,
                                            onRejectEmailApproval = onRejectEmailApproval,
                                            onRespondBrowserPermission = onRespondBrowserPermission,
                                            onApproveRegistration = onApproveRegistration,
                                            onRejectRegistration = onRejectRegistration,
                                            onWalletManage = onWalletManage,
                                            onTaskControl = onTaskControl,
                                            pendingDomCommand = pendingDomCommand,
                                            onEmailReply = onEmailReply,
                                            onEmailForward = onEmailForward,
                                            onEmailDelete = onEmailDelete,
                                            onExplainWalletTx = onExplainWalletTx
                                        )
                                    }
                                    is ChatListEntry.AssistantStreamingRow -> {
                                        Spacer(modifier = Modifier.height(entry.topGapDp.dp))
                                        SwipeableSingleBlockRow(
                                            message = entry.message,
                                            block = null,
                                            blockIndex = 0,
                                            isLastBlock = true,
                                            isHighlighted = false,
                                            isPressedForMenu = false,
                                            showTypingDots = isTypingDotsVisible,
                                            liveTaskStep = liveTaskStep,
                                            shouldAnimateEntry = false,
                                            shouldAnimateWords = false,
                                            reducedMotion = reducedMotion,
                                            onSwipeReply = { target -> onSetReplyingTo(target) },
                                            onLongPress = { target -> longPressedMessage = target },
                                            onQuoteTap = jumpToQuote,
                                            onOpenUrl = handleOpenUrl,
                                            onOpenPhotoViewer = { imgs, startIdx ->
                                                viewerImages = imgs
                                                viewerInitialIndex = startIdx
                                            },
                                            onPlaceOrder = onPlaceOrder,
                                            onChipClick = { chip -> onSendPrompt(chip, emptyList()) },
                                            onRetry = retryThisMessage,
                                            onFinishedWordStream = {},
                                            browserLiveActivity = browserLiveActivity,
                                            autonomousTasks = autonomousTasks,
                                            webViewHooks = webViewHooks,
                                            onBrowserAction = onBrowserAction,
                                            onInterventionTakeControl = onInterventionTakeControl,
                                            onInterventionContinue = onInterventionContinue,
                                            onApprovePayment = onApprovePayment,
                                            onRejectPayment = onRejectPayment,
                                            onSendEmailApproval = onSendEmailApproval,
                                            onRejectEmailApproval = onRejectEmailApproval,
                                            onRespondBrowserPermission = onRespondBrowserPermission,
                                            onApproveRegistration = onApproveRegistration,
                                            onRejectRegistration = onRejectRegistration,
                                            onWalletManage = onWalletManage,
                                            onTaskControl = onTaskControl,
                                            pendingDomCommand = pendingDomCommand,
                                            onEmailReply = onEmailReply,
                                            onEmailForward = onEmailForward,
                                            onEmailDelete = onEmailDelete,
                                            onExplainWalletTx = onExplainWalletTx
                                        )
                                    }
                                    is ChatListEntry.AssistantBlockRow -> {
                                        val isNewlyArrived = entry.message.id !in initialSnapshotIds
                                        val shouldAnimateWords = isNewlyArrived && entry.blockIndex == 0 && entry.message.id !in completedWordAnimations
                                        Spacer(modifier = Modifier.height(entry.topGapDp.dp))
                                        SwipeableSingleBlockRow(
                                            message = entry.message,
                                            block = entry.block,
                                            blockIndex = entry.blockIndex,
                                            isLastBlock = entry.isLastBlock,
                                            isHighlighted = highlightedMessageId == entry.message.id,
                                            isPressedForMenu = longPressedMessage?.id == entry.message.id,
                                            showTypingDots = false,
                                            liveTaskStep = "",
                                            shouldAnimateEntry = isNewlyArrived,
                                            shouldAnimateWords = shouldAnimateWords,
                                            reducedMotion = reducedMotion,
                                            onSwipeReply = { target -> onSetReplyingTo(target) },
                                            onLongPress = { target -> longPressedMessage = target },
                                            onQuoteTap = jumpToQuote,
                                            onOpenUrl = handleOpenUrl,
                                            onOpenPhotoViewer = { imgs, startIdx ->
                                                viewerImages = imgs
                                                viewerInitialIndex = startIdx
                                            },
                                            onPlaceOrder = onPlaceOrder,
                                            onChipClick = { chip -> onSendPrompt(chip, emptyList()) },
                                            onRetry = retryThisMessage,
                                            onFinishedWordStream = { doneId ->
                                                completedWordAnimations.add(doneId)
                                                initialSnapshotIds.add(doneId)
                                            },
                                            browserLiveActivity = browserLiveActivity,
                                            autonomousTasks = autonomousTasks,
                                            webViewHooks = webViewHooks,
                                            onBrowserAction = onBrowserAction,
                                            onInterventionTakeControl = onInterventionTakeControl,
                                            onInterventionContinue = onInterventionContinue,
                                            onApprovePayment = onApprovePayment,
                                            onRejectPayment = onRejectPayment,
                                            onSendEmailApproval = onSendEmailApproval,
                                            onRejectEmailApproval = onRejectEmailApproval,
                                            onRespondBrowserPermission = onRespondBrowserPermission,
                                            onApproveRegistration = onApproveRegistration,
                                            onRejectRegistration = onRejectRegistration,
                                            onWalletManage = onWalletManage,
                                            onTaskControl = onTaskControl,
                                            pendingDomCommand = pendingDomCommand,
                                            onEmailReply = onEmailReply,
                                            onEmailForward = onEmailForward,
                                            onEmailDelete = onEmailDelete,
                                            onExplainWalletTx = onExplainWalletTx
                                        )
                                    }
                                }
                            }
                        }

                        // Floating circular Scroll-to-Bottom button with unread count
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isNearBottom && filteredMessages.isNotEmpty(),
                            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.85f),
                            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .shadow(8.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(c.scrollFabBg)
                                    .border(1.dp, c.scrollFabBorder, CircleShape)
                                    .clickable {
                                        unreadWhileScrolledUp = 0
                                        scope.launch {
                                            val last = listEntries.lastIndex.coerceAtLeast(0)
                                            listState.animateScrollToItem(last)
                                        }
                                    }
                                    .padding(horizontal = if (unreadWhileScrolledUp > 0) 12.dp else 10.dp, vertical = 10.dp)
                                    .testTag("scroll_to_bottom_fab"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Scroll to bottom",
                                        tint = c.headerIconTint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    if (unreadWhileScrolledUp > 0) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(c.accentBlue)
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = unreadWhileScrolledUp.toString(),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Subtle AI orchestrator status pill + separated Browser status pill + optional manual-mode fallback banner
            if (activeTab == AlloraTopTab.CHAT) {
                val isErrorInterfaceState = chatInterfaceState == AlloraChatInterfaceState.TEMPORARY_NETWORK_FAILURE ||
                    chatInterfaceState == AlloraChatInterfaceState.AUTHENTICATION_FAILURE ||
                    chatInterfaceState == AlloraChatInterfaceState.PROVIDER_UNAVAILABLE ||
                    chatInterfaceState == AlloraChatInterfaceState.RATE_LIMITED
                val showAiPill = (aiStatusPhase != AiStatusPhase.IDLE && aiStatusPhase != AiStatusPhase.GENERATING) ||
                    (chatInterfaceState != AlloraChatInterfaceState.IDLE &&
                        chatInterfaceState != AlloraChatInterfaceState.RECEIVING_RESPONSE) ||
                    isRetryingMessage
                val showBrowserPill = browserLiveActivity.active &&
                    browserLiveActivity.browserPhase != BrowserNavigationPhase.IDLE &&
                    browserLiveActivity.browserPhase != BrowserNavigationPhase.DISCONNECTED
                AnimatedVisibility(
                    visible = showAiPill || showBrowserPill,
                    enter = fadeIn(tween(140)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(140))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showAiPill) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(c.cardBg)
                                    .border(1.dp, c.cardBorder, RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                                    .testTag("chat_ai_status_pill"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when {
                                    isErrorInterfaceState -> Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = chatInterfaceState.displayLabel,
                                        tint = Color(0xFFD9534F),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    chatInterfaceState == AlloraChatInterfaceState.SUCCESS -> Icon(
                                        imageVector = Icons.Rounded.CheckCircleOutline,
                                        contentDescription = "Success",
                                        tint = Color(0xFF2E9D64),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    else -> CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.6.dp,
                                        color = c.accentBlue
                                    )
                                }
                                Text(
                                    text = buildString {
                                        when {
                                            isRetryingMessage -> append("Retrying request...")
                                            isErrorInterfaceState -> append(chatInterfaceState.displayLabel)
                                            chatInterfaceState == AlloraChatInterfaceState.SUCCESS -> append("Response ready")
                                            aiStatusPhase == AiStatusPhase.SWITCHING_MODEL -> {
                                                append(aiStatusPhase.subtleMessage)
                                                if (!activeAiModelName.isNullOrBlank()) {
                                                    append(" • ")
                                                    append(activeAiModelName)
                                                }
                                            }
                                            aiStatusPhase != AiStatusPhase.IDLE -> append(aiStatusPhase.subtleMessage)
                                            else -> append(chatInterfaceState.displayLabel)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = c.textSecondary,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (chatInterfaceState == AlloraChatInterfaceState.AUTHENTICATION_FAILURE) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(c.accentBlue.copy(alpha = 0.14f))
                                            .clickable(onClick = onVerifySessionRequested)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                            .testTag("chat_reauth_action_button")
                                    ) {
                                        Text(
                                            text = "Sign in again",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = c.accentBlue,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        if (showBrowserPill) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(c.cardBg)
                                    .border(1.dp, c.cardBorder, RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                                    .testTag("chat_browser_status_pill"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "ALLORA Computer: ${browserLiveActivity.computerStage.displayLabel}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = c.textSecondary,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = manualModeBlockedNotice != null,
                    enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(140))
                ) {
                    manualModeBlockedNotice?.let { notice ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(c.cardBg)
                                .border(1.dp, c.cardBorder, RoundedCornerShape(16.dp))
                                .padding(12.dp)
                                .testTag("chat_manual_fallback_banner"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${notice.selectedModelName} is unavailable",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = c.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onDismissManualNotice,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss model notice",
                                        tint = c.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = notice.reason,
                                style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(c.accentBlue)
                                        .clickable(onClick = onAcceptRecommendedFallback)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("chat_manual_use_recommended_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = notice.recommendedFallbackName?.let { "Use $it" } ?: "Use Recommended",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, c.cardBorder, RoundedCornerShape(12.dp))
                                        .clickable(onClick = onSwitchToAutomaticMode)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("chat_manual_auto_mode_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Auto Mode",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = c.textPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Composer (visible in Chat tab)
                AlloraChatComposer(
                    text = inputText,
                    onTextChange = { updated ->
                        inputText = updated
                        voiceController.clearStatus()
                    },
                    replyingTo = replyingTo,
                    attachments = pendingAttachments,
                    voiceState = voiceState,
                    attachMenuExpanded = attachMenuExpanded,
                    reducedMotion = reducedMotion,
                    onDismissReply = { onSetReplyingTo(null) },
                    onRemoveAttachment = { att -> pendingAttachments.removeAll { it.id == att.id } },
                    onPlusClick = { attachMenuExpanded = !attachMenuExpanded },
                    onDismissAttachMenu = { attachMenuExpanded = false },
                    onPickPhoto = {
                        attachMenuExpanded = false
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickFile = {
                        attachMenuExpanded = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onMicClick = handleMicButtonClick,
                    onCancelVoice = {
                        if (voiceState.isActive) {
                            voiceController.cancel { restored -> inputText = restored }
                        } else {
                            voiceController.clearStatus()
                        }
                    },
                    onSendClick = {
                        val nowMs = System.currentTimeMillis()
                        val candidateSig = "${inputText.trim()}|${pendingAttachments.joinToString(",") { it.id }}"
                        if (candidateSig == lastSubmittedSignature && (nowMs - lastSubmitTimestampMs) < 450L) {
                            // Debounce double-tap / simultaneous IME onSend + button click for the same payload
                        } else {
                            if (voiceState.isRecording) {
                                voiceController.cancel()
                            }
                            if ((inputText.isNotBlank() || pendingAttachments.isNotEmpty()) &&
                                voiceState.phase != ComposerVoicePhase.TRANSCRIBING
                            ) {
                                lastSubmitTimestampMs = nowMs
                                lastSubmittedSignature = candidateSig
                                val prompt = inputText
                                val attachedCopy = pendingAttachments.toList()
                                inputText = ""
                                voiceController.clearStatus()
                                pendingAttachments.clear()
                                onSendPrompt(prompt, attachedCopy)
                            }
                        }
                    }
                )
            }
        }

        // Long-Press Dimmed & Focused Context Menu Overlay
        AnimatedVisibility(
            visible = longPressedMessage != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(130))
        ) {
            val target = longPressedMessage
            if (target != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.48f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { longPressedMessage = null }
                        .testTag("long_press_overlay"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = if (target.isUser) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val snippet = target.plainText.ifBlank {
                            ChatBlockJsonParser.summarizePlainText(target.blocks)
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(max = 310.dp)
                                .graphicsLayer { scaleX = 0.97f; scaleY = 0.97f }
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (target.isUser) c.userBubbleBg else c.alloraBubbleBg)
                                .border(1.dp, c.headerIconBorder, RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = snippet.ifBlank { "Allora message" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (target.isUser) c.userBubbleInk else c.alloraBubbleInk
                                ),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Action menu: Copy, Reply, Share
                        Column(
                            modifier = Modifier
                                .width(196.dp)
                                .shadow(18.dp, RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp))
                                .background(c.cardBg)
                                .border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
                        ) {
                            LongPressActionRow(
                                label = "Copy",
                                icon = Icons.Rounded.ContentCopy,
                                onClick = {
                                    clipboard.setText(AnnotatedString(snippet))
                                    longPressedMessage = null
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            )
                            HorizontalDivider(color = c.hairline, thickness = 0.7.dp)
                            LongPressActionRow(
                                label = "Reply",
                                icon = Icons.AutoMirrored.Rounded.Reply,
                                onClick = {
                                    onSetReplyingTo(target)
                                    longPressedMessage = null
                                }
                            )
                            HorizontalDivider(color = c.hairline, thickness = 0.7.dp)
                            LongPressActionRow(
                                label = "Share",
                                icon = Icons.Rounded.Share,
                                onClick = {
                                    longPressedMessage = null
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, snippet)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share from Allora"))
                                }
                            )
                        }
                    }
                }
            }
        }

        // Full-Screen Black Zoomable Photo Viewer
        viewerImages?.let { images ->
            FullScreenPhotoViewerOverlay(
                images = images,
                initialIndex = viewerInitialIndex,
                onDismiss = { viewerImages = null }
            )
        }
    }
}

@Composable
private fun LongPressActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val c = LocalAlloraChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("long_press_action_${label.lowercase()}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = c.textPrimary,
                fontWeight = FontWeight.Medium
            )
        )
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = c.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AlloraChatTopBar(
    title: String,
    userInitial: String,
    searchExpanded: Boolean,
    searchQuery: String,
    activeTab: AlloraTopTab,
    isDarkTheme: Boolean,
    reducedMotion: Boolean,
    profileMenuExpanded: Boolean,
    onHomeClick: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAvatarClick: () -> Unit,
    onDismissProfileMenu: () -> Unit,
    onSelectTab: (AlloraTopTab) -> Unit,
    onToggleDarkTheme: () -> Unit,
    onToggleReducedMotion: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenAutonomousTasks: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenEmail: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAiModels: () -> Unit = {},
    onNewChat: () -> Unit
) {
    val c = LocalAlloraChatColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1: House icon far left, conversation title middle, search icon + round profile avatar right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onHomeClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_home_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.headerIconBg)
                        .border(1.dp, c.headerIconBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Home,
                        contentDescription = "Home",
                        tint = c.headerIconTint,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (searchExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(c.composerBg)
                            .border(1.dp, c.composerBorder, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.textPrimary),
                            cursorBrush = SolidColor(c.accentBlue),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_search_input"),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search messages...",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = c.composerPlaceholder)
                                    )
                                }
                                inner()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = c.textMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onSearchQueryChange("") }
                            )
                        }
                    }
                } else {
                    Text(
                        text = title.ifBlank { "Allora" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = c.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("chat_top_title")
                    )
                }
            }

            IconButton(
                onClick = onSearchToggle,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_search_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.headerIconBg)
                        .border(1.dp, c.headerIconBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = if (searchExpanded) "Close search" else "Search chat",
                        tint = c.headerIconTint,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onAvatarClick)
                        .testTag("chat_profile_avatar"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(c.avatarBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = userInitial,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = c.avatarInk,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                DropdownMenu(
                    expanded = profileMenuExpanded,
                    onDismissRequest = onDismissProfileMenu
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isDarkTheme) "Light Mode" else "Dark Mode") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = null
                            )
                        },
                        onClick = onToggleDarkTheme
                    )
                    DropdownMenuItem(
                        text = { Text("ALLORA LIVE Voice") },
                        leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        onClick = onOpenLive
                    )
                    DropdownMenuItem(
                        text = { Text("My Wallet") },
                        leadingIcon = { Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null) },
                        onClick = onOpenWallet
                    )
                    DropdownMenuItem(
                        text = { Text("My Mail") },
                        leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                        onClick = onOpenEmail
                    )
                    DropdownMenuItem(
                        text = { Text("Safety & Permissions") },
                        leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                        onClick = onOpenPermissions
                    )
                    DropdownMenuItem(
                        text = { Text("Assistant Preferences") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                        onClick = onOpenAiModels,
                        modifier = Modifier.testTag("chat_menu_ai_models")
                    )
                    DropdownMenuItem(
                        text = { Text(if (reducedMotion) "Full Motion" else "Reduced Motion") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        onClick = onToggleReducedMotion
                    )
                    DropdownMenuItem(
                        text = { Text("Clear Conversation") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        onClick = onNewChat
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: Chat and Projects tab switch centered below the top bar
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(c.tabTrackBg)
                .border(1.dp, c.hairline, RoundedCornerShape(50))
                .padding(4.dp)
                .testTag("chat_projects_switcher"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlloraTopTab.entries.forEach { tab ->
                val selected = activeTab == tab
                val pillBg by animateColorAsState(
                    targetValue = if (selected) c.tabActiveBg else Color.Transparent,
                    animationSpec = tween(durationMillis = 180),
                    label = "tabBg_${tab.name}"
                )
                val pillInk by animateColorAsState(
                    targetValue = if (selected) c.tabActiveInk else c.tabInactiveInk,
                    animationSpec = tween(durationMillis = 180),
                    label = "tabInk_${tab.name}"
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pillBg)
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("tab_${tab.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = pillInk,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AlloraChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    replyingTo: AlloraRichMessage?,
    attachments: List<ChatAttachment>,
    voiceState: ComposerVoiceState,
    attachMenuExpanded: Boolean,
    reducedMotion: Boolean,
    onDismissReply: () -> Unit,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    onPlusClick: () -> Unit,
    onDismissAttachMenu: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onMicClick: () -> Unit,
    onCancelVoice: () -> Unit,
    onSendClick: () -> Unit
) {
    val c = LocalAlloraChatColors.current
    val hasContentToSend = text.isNotBlank() || attachments.isNotEmpty()
    val recordingColor = Color(0xFFEF4444)

    val morphRotation by animateFloatAsState(
        targetValue = if (hasContentToSend && !voiceState.isRecording) 0f else -90f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "micToSendRotation"
    )
    val animatedLevel by animateFloatAsState(
        targetValue = (voiceState.levelDb / 12f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 90),
        label = "voiceLevelPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Live recording / transcription / mic status strip above composer
        AnimatedVisibility(
            visible = voiceState.isActive || !voiceState.statusMessage.isNullOrBlank(),
            enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically { it / 2 }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.cardBg)
                    .border(
                        width = 1.dp,
                        color = if (voiceState.isRecording) recordingColor.copy(alpha = 0.55f) else c.cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .testTag("composer_voice_status_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    voiceState.isRecording -> {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .graphicsLayer {
                                    val pulse = if (reducedMotion) 1f else 0.9f + (0.45f * animatedLevel)
                                    scaleX = pulse
                                    scaleY = pulse
                                }
                                .clip(CircleShape)
                                .background(recordingColor)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(16.dp)
                        ) {
                            val barWeights = listOf(0.55f, 1.0f, 0.78f, 0.45f)
                            barWeights.forEach { weight ->
                                val barHeight = (4f + (12f * animatedLevel * weight)).dp
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(c.accentBlue)
                                )
                            }
                        }
                    }
                    voiceState.phase == ComposerVoicePhase.TRANSCRIBING -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = c.accentBlue,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = if (voiceState.isError) recordingColor else c.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = voiceState.statusMessage ?: when (voiceState.phase) {
                        ComposerVoicePhase.LISTENING -> "Listening..."
                        ComposerVoicePhase.RECORDING_HD -> "Recording audio..."
                        ComposerVoicePhase.TRANSCRIBING -> "Transcribing speech..."
                        ComposerVoicePhase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (voiceState.isError && !voiceState.isActive) {
                            recordingColor
                        } else {
                            c.textPrimary
                        }
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("composer_voice_status")
                )

                if (voiceState.phase != ComposerVoicePhase.TRANSCRIBING) {
                    IconButton(
                        onClick = onCancelVoice,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_voice_cancel")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = if (voiceState.isRecording) "Cancel recording" else "Dismiss status",
                            tint = c.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Quoted Preview slides up cleanly above the composer with X cancel
        AnimatedVisibility(
            visible = replyingTo != null,
            enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(130)) + slideOutVertically { it / 2 }
        ) {
            replyingTo?.let { target ->
                val snippet = target.plainText.ifBlank {
                    ChatBlockJsonParser.summarizePlainText(target.blocks)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.cardBg)
                        .border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                        .testTag("composer_reply_preview"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.quoteAccent)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (target.isUser) "Replying to you" else "Replying to Allora",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = c.quoteAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismissReply,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("composer_reply_cancel")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel reply",
                            tint = c.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Attachment previews above composer
        AnimatedVisibility(visible = attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { att ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.cardBg)
                            .border(1.dp, c.cardBorder, RoundedCornerShape(14.dp))
                            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (att.isImage) Icons.Rounded.PhotoLibrary else Icons.Rounded.Description,
                            contentDescription = null,
                            tint = c.accentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = att.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(color = c.textPrimary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove attachment",
                            tint = c.textMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemoveAttachment(att) }
                        )
                    }
                }
            }
        }

        // Pinned Rounded Pill Composer: input left ("Message Allora", up to 5 lines), + and Morphing Mic/Send right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(28.dp))
                .background(c.composerBg)
                .border(1.dp, c.composerBorder, RoundedCornerShape(28.dp))
                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .testTag("allora_composer_pill"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = voiceState.phase != ComposerVoicePhase.TRANSCRIBING,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = c.textPrimary,
                    fontSize = 15.5.sp,
                    lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(c.accentBlue),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .testTag("composer_text_input"),
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        val placeholderText = when (voiceState.phase) {
                            ComposerVoicePhase.LISTENING -> "Listening — speak now..."
                            ComposerVoicePhase.RECORDING_HD -> "Recording audio — speak now..."
                            ComposerVoicePhase.TRANSCRIBING -> "Transcribing your speech..."
                            ComposerVoicePhase.IDLE -> "Message Allora"
                        }
                        Text(
                            text = placeholderText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (voiceState.isRecording) c.accentBlue else c.composerPlaceholder,
                                fontSize = 15.5.sp
                            )
                        )
                    }
                    innerTextField()
                }
            )

            Box {
                IconButton(
                    onClick = onPlusClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("composer_plus_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add photo or file",
                        tint = c.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                DropdownMenu(
                    expanded = attachMenuExpanded,
                    onDismissRequest = onDismissAttachMenu
                ) {
                    DropdownMenuItem(
                        text = { Text("Photo") },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                        onClick = onPickPhoto
                    )
                    DropdownMenuItem(
                        text = { Text("File") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        onClick = onPickFile
                    )
                }
            }

            val trailingTag = when {
                voiceState.isRecording -> "composer_stop_recording_button"
                voiceState.phase == ComposerVoicePhase.TRANSCRIBING -> "composer_transcribing_button"
                hasContentToSend -> "composer_send_button"
                else -> "composer_mic_button"
            }

            IconButton(
                onClick = {
                    when {
                        voiceState.isRecording -> onMicClick()
                        voiceState.phase == ComposerVoicePhase.TRANSCRIBING -> Unit
                        hasContentToSend -> onSendClick()
                        else -> onMicClick()
                    }
                },
                enabled = voiceState.phase != ComposerVoicePhase.TRANSCRIBING,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(trailingTag)
            ) {
                when {
                    voiceState.isRecording -> {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .graphicsLayer {
                                    val pulse = if (reducedMotion) 1f else 1f + (0.14f * animatedLevel)
                                    scaleX = pulse
                                    scaleY = pulse
                                }
                                .clip(CircleShape)
                                .background(recordingColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "Stop recording",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    voiceState.phase == ComposerVoicePhase.TRANSCRIBING -> {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c.headerIconBg)
                                .border(1.dp, c.headerIconBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = c.accentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    hasContentToSend -> {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c.sendBtnBg)
                                .graphicsLayer { rotationZ = morphRotation },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = "Send",
                                tint = c.sendBtnInk,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "Speak to Allora",
                            tint = c.textSecondary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlloraProjectsTabPanel(
    threads: List<AlloraChatThread>,
    activeThreadId: String,
    onSelectThread: (String) -> Unit,
    onCreateProject: (String, String?) -> Unit
) {
    val c = LocalAlloraChatColors.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("projects_tab_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conversations & Projects",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = c.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.tabActiveBg)
                        .clickable { onCreateProject("New Project", null) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("new_project_chat_button")
                ) {
                    Text(
                        text = "+ New Chat",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = c.tabActiveInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }

        itemsIndexed(threads, key = { _, t -> t.id }) { _, thread ->
            val isActive = thread.id == activeThreadId
            val lastSnippet = thread.messages.lastOrNull()?.let { m ->
                m.plainText.ifBlank { ChatBlockJsonParser.summarizePlainText(m.blocks) }
            }.orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.cardBg)
                    .border(
                        width = if (isActive) 1.4.dp else 1.dp,
                        color = if (isActive) c.accentBlue else c.cardBorder,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clickable { onSelectThread(thread.id) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c.cardInnerBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubbleOutline,
                        contentDescription = null,
                        tint = if (isActive) c.accentBlue else c.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = c.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (lastSnippet.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = lastSnippet,
                            style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.accentBlue.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = c.accentBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
