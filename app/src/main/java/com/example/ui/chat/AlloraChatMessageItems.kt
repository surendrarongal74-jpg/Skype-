package com.example.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AutonomousTaskRecord
import com.example.services.BrowserDomCommand
import com.example.services.BrowserLiveActivityState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ChatDateSeparator(
    label: String,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = c.hairline,
            thickness = 0.7.dp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = c.textMuted,
                fontSize = 11.5.sp
            ),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = c.hairline,
            thickness = 0.7.dp
        )
    }
}

@Composable
fun TypingDotsContent(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val transition = rememberInfiniteTransition(label = "typingDots")
    val density = LocalDensity.current
    val maxLiftPx = with(density) { 3.dp.toPx() }

    Row(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("allora_typing_dots"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (idx in 0..2) {
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = if (reducedMotion) 0f else -maxLiftPx,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 380,
                        delayMillis = idx * 120,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$idx"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { translationY = offsetY }
                    .clip(CircleShape)
                    .background(c.alloraBubbleInk.copy(alpha = 0.78f))
            )
        }
    }
}

@Composable
fun WordStreamText(
    fullText: String,
    animateWords: Boolean,
    reducedMotion: Boolean,
    color: Color,
    onStreamingCompleted: () -> Unit = {}
) {
    val words = remember(fullText) { fullText.split(Regex("(?<=\\s)")).filter { it.isNotEmpty() } }
    var visibleWordCount by remember(fullText, animateWords) {
        mutableIntStateOf(if (!animateWords || reducedMotion) words.size else 0)
    }

    LaunchedEffect(fullText, animateWords, reducedMotion) {
        if (!animateWords || reducedMotion) {
            visibleWordCount = words.size
            onStreamingCompleted()
            return@LaunchedEffect
        }
        visibleWordCount = 0
        for (i in 1..words.size) {
            visibleWordCount = i
            delay(24L)
        }
        onStreamingCompleted()
    }

    val shown = if (visibleWordCount >= words.size) fullText else words.take(visibleWordCount).joinToString("")
    Text(
        text = shown,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = color,
            fontSize = 15.sp,
            lineHeight = 21.5.sp
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableChatRow(
    message: AlloraRichMessage,
    isHighlighted: Boolean,
    isPressedForMenu: Boolean,
    showTypingDots: Boolean,
    liveTaskStep: String,
    shouldAnimateEntry: Boolean,
    shouldAnimateWords: Boolean,
    reducedMotion: Boolean,
    onSwipeReply: (AlloraRichMessage) -> Unit,
    onLongPress: (AlloraRichMessage) -> Unit,
    onQuoteTap: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPhotoViewer: (List<ChatImageItem>, Int) -> Unit,
    onPlaceOrder: (String, String) -> Unit,
    onChipClick: (String) -> Unit,
    onRetry: () -> Unit,
    onFinishedWordStream: (String) -> Unit,
    modifier: Modifier = Modifier,
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
    onWalletManage: () -> Unit = {},
    onTaskControl: (taskId: String, action: String) -> Unit = { _, _ -> },
    pendingDomCommand: BrowserDomCommand? = null,
    onEmailReply: (String) -> Unit = {},
    onEmailForward: (String) -> Unit = {},
    onEmailDelete: (String) -> Unit = {},
    onExplainWalletTx: (String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val maxSwipePx = with(density) { 60.dp.toPx() }
    val triggerThresholdPx = with(density) { 38.dp.toPx() }
    val dragOffsetX = remember { Animatable(0f) }
    var crossedThreshold by remember { mutableStateOf(false) }

    // User pop-in animation (scale 0.95 -> 1 with soft spring + subtle upward slide)
    var entered by remember(message.id) { mutableStateOf(!shouldAnimateEntry || reducedMotion) }
    LaunchedEffect(message.id, shouldAnimateEntry) {
        if (shouldAnimateEntry && !reducedMotion) {
            entered = true
        }
    }
    val entryScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.95f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "entryScale"
    )
    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 180),
        label = "entryAlpha"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressedForMenu) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "longPressScale"
    )
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) c.highlightGlow else Color.Transparent,
        animationSpec = tween(durationMillis = 360),
        label = "quoteHighlightGlow"
    )

    val replyIconFraction = (dragOffsetX.value / triggerThresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(highlightColor, RoundedCornerShape(22.dp))
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        val next = (dragOffsetX.value + dragAmount).coerceIn(0f, maxSwipePx)
                        if (next > 0f || dragOffsetX.value > 0f) {
                            change.consume()
                            scope.launch { dragOffsetX.snapTo(next) }
                            if (!crossedThreshold && next >= triggerThresholdPx) {
                                crossedThreshold = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (crossedThreshold && next < triggerThresholdPx * 0.85f) {
                                crossedThreshold = false
                            }
                        }
                    },
                    onDragEnd = {
                        val shouldTrigger = crossedThreshold || dragOffsetX.value >= triggerThresholdPx
                        crossedThreshold = false
                        scope.launch {
                            dragOffsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                        if (shouldTrigger) {
                            onSwipeReply(message)
                        }
                    },
                    onDragCancel = {
                        crossedThreshold = false
                        scope.launch { dragOffsetX.animateTo(0f, spring()) }
                    }
                )
            }
            .testTag("chat_message_${message.id}")
    ) {
        // Reply arrow indicator revealed behind bubble on swipe-right
        if (dragOffsetX.value > 2f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(34.dp)
                    .graphicsLayer {
                        alpha = replyIconFraction
                        val s = 0.65f + 0.35f * replyIconFraction
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(c.headerIconBg)
                    .border(1.dp, c.headerIconBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Reply,
                    contentDescription = "Reply",
                    tint = c.headerIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragOffsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    val s = entryScale * pressScale
                    scaleX = s
                    scaleY = s
                    alpha = entryAlpha
                }
        ) {
            val userMaxWidth = maxWidth * 0.80f
            val assistantMaxWidth = maxWidth * 0.88f

            if (message.isUser) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End
                ) {
                    message.replyTo?.let { quote ->
                        QuotedReplyHeader(
                            quote = quote,
                            alignEnd = true,
                            onClick = { onQuoteTap(quote.messageId) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (message.attachments.isNotEmpty()) {
                        UserAttachmentsRow(
                            attachments = message.attachments,
                            onOpenPhotoViewer = onOpenPhotoViewer
                        )
                        if (message.plainText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    if (message.plainText.isNotBlank()) {
                        val shape = RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp)
                        Box(
                            modifier = Modifier
                                .widthIn(max = userMaxWidth)
                                .clip(shape)
                                .background(c.userBubbleBg)
                                .border(1.dp, c.userBubbleBorder, shape)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongPress(message)
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                        ) {
                            Text(
                                text = message.plainText,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = c.userBubbleInk,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp
                                )
                            )
                        }
                    }
                }
            } else {
                AssistantSingleBlockRow(
                    message = message,
                    block = message.blocks.firstOrNull(),
                    blockIndex = 0,
                    isLastBlock = message.blocks.size <= 1,
                    maxBubbleWidth = assistantMaxWidth,
                    showTypingDots = showTypingDots,
                    liveTaskStep = liveTaskStep,
                    shouldAnimateWords = shouldAnimateWords,
                    reducedMotion = reducedMotion,
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(message)
                    },
                    onQuoteTap = onQuoteTap,
                    onOpenUrl = onOpenUrl,
                    onOpenPhotoViewer = onOpenPhotoViewer,
                    onPlaceOrder = { blockId -> onPlaceOrder(message.id, blockId) },
                    onChipClick = onChipClick,
                    onRetry = onRetry,
                    onFinishedWordStream = { onFinishedWordStream(message.id) },
                    browserLiveActivity = browserLiveActivity,
                    autonomousTasks = autonomousTasks,
                    webViewHooks = webViewHooks,
                    onBrowserAction = onBrowserAction,
                    onInterventionTakeControl = onInterventionTakeControl,
                    onInterventionContinue = onInterventionContinue,
                    onApprovePayment = { blk -> onApprovePayment(message.id, blk) },
                    onRejectPayment = { blk -> onRejectPayment(message.id, blk) },
                    onSendEmailApproval = { blk, r, s, b -> onSendEmailApproval(message.id, blk, r, s, b) },
                    onRejectEmailApproval = { blk -> onRejectEmailApproval(message.id, blk) },
                    onRespondBrowserPermission = { blk, mode -> onRespondBrowserPermission(message.id, blk, mode) },
                    onApproveRegistration = { blk -> onApproveRegistration(message.id, blk) },
                    onRejectRegistration = { blk -> onRejectRegistration(message.id, blk) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableSingleBlockRow(
    message: AlloraRichMessage,
    block: ChatBlock?,
    blockIndex: Int,
    isLastBlock: Boolean,
    isHighlighted: Boolean,
    isPressedForMenu: Boolean,
    showTypingDots: Boolean,
    liveTaskStep: String,
    shouldAnimateEntry: Boolean,
    shouldAnimateWords: Boolean,
    reducedMotion: Boolean,
    onSwipeReply: (AlloraRichMessage) -> Unit,
    onLongPress: (AlloraRichMessage) -> Unit,
    onQuoteTap: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPhotoViewer: (List<ChatImageItem>, Int) -> Unit,
    onPlaceOrder: (String, String) -> Unit,
    onChipClick: (String) -> Unit,
    onRetry: () -> Unit,
    onFinishedWordStream: (String) -> Unit,
    modifier: Modifier = Modifier,
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
    onWalletManage: () -> Unit = {},
    onTaskControl: (taskId: String, action: String) -> Unit = { _, _ -> },
    pendingDomCommand: BrowserDomCommand? = null,
    onEmailReply: (String) -> Unit = {},
    onEmailForward: (String) -> Unit = {},
    onEmailDelete: (String) -> Unit = {},
    onExplainWalletTx: (String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val maxSwipePx = with(density) { 60.dp.toPx() }
    val triggerThresholdPx = with(density) { 38.dp.toPx() }
    val dragOffsetX = remember { Animatable(0f) }
    var crossedThreshold by remember { mutableStateOf(false) }

    // Stagger further blocks (~400ms apart per block index), fading in and sliding up 8.dp
    var entered by remember(message.id, blockIndex) { mutableStateOf(!shouldAnimateEntry || reducedMotion) }
    LaunchedEffect(message.id, blockIndex, shouldAnimateEntry, reducedMotion) {
        if (shouldAnimateEntry && !reducedMotion) {
            if (blockIndex > 0) {
                delay(blockIndex * 400L)
            }
            entered = true
        } else {
            entered = true
        }
    }
    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 220),
        label = "blkEntryAlpha"
    )
    val slideOffsetPx = with(density) { 8.dp.toPx() }
    val entryTranslationY by animateFloatAsState(
        targetValue = if (entered) 0f else slideOffsetPx,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 220, easing = FastOutSlowInEasing),
        label = "blkSlideY"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressedForMenu) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "blkPressScale"
    )
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted && blockIndex == 0) c.highlightGlow else Color.Transparent,
        animationSpec = tween(durationMillis = 360),
        label = "blkQuoteHighlight"
    )
    val replyIconFraction = (dragOffsetX.value / triggerThresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(highlightColor, RoundedCornerShape(22.dp))
            .pointerInput(message.id, blockIndex) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        val next = (dragOffsetX.value + dragAmount).coerceIn(0f, maxSwipePx)
                        if (next > 0f || dragOffsetX.value > 0f) {
                            change.consume()
                            scope.launch { dragOffsetX.snapTo(next) }
                            if (!crossedThreshold && next >= triggerThresholdPx) {
                                crossedThreshold = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (crossedThreshold && next < triggerThresholdPx * 0.85f) {
                                crossedThreshold = false
                            }
                        }
                    },
                    onDragEnd = {
                        val shouldTrigger = crossedThreshold || dragOffsetX.value >= triggerThresholdPx
                        crossedThreshold = false
                        scope.launch {
                            dragOffsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                        if (shouldTrigger) {
                            onSwipeReply(message)
                        }
                    },
                    onDragCancel = {
                        crossedThreshold = false
                        scope.launch { dragOffsetX.animateTo(0f, spring()) }
                    }
                )
            }
            .testTag(if (blockIndex == 0) "chat_message_${message.id}" else "chat_message_${message.id}_blk_$blockIndex")
    ) {
        if (dragOffsetX.value > 2f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .size(34.dp)
                    .graphicsLayer {
                        alpha = replyIconFraction
                        val s = 0.65f + 0.35f * replyIconFraction
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(c.headerIconBg)
                    .border(1.dp, c.headerIconBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Reply,
                    contentDescription = "Reply",
                    tint = c.headerIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragOffsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    alpha = entryAlpha
                    translationY = entryTranslationY
                    scaleX = pressScale
                    scaleY = pressScale
                }
        ) {
            val assistantMaxWidth = maxWidth * 0.88f
            AssistantSingleBlockRow(
                message = message,
                block = block,
                blockIndex = blockIndex,
                isLastBlock = isLastBlock,
                maxBubbleWidth = assistantMaxWidth,
                showTypingDots = showTypingDots,
                liveTaskStep = liveTaskStep,
                shouldAnimateWords = shouldAnimateWords,
                reducedMotion = reducedMotion,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress(message)
                },
                onQuoteTap = onQuoteTap,
                onOpenUrl = onOpenUrl,
                onOpenPhotoViewer = onOpenPhotoViewer,
                onPlaceOrder = { blockId -> onPlaceOrder(message.id, blockId) },
                onChipClick = onChipClick,
                onRetry = onRetry,
                onFinishedWordStream = { onFinishedWordStream(message.id) },
                browserLiveActivity = browserLiveActivity,
                autonomousTasks = autonomousTasks,
                webViewHooks = webViewHooks,
                onBrowserAction = onBrowserAction,
                onInterventionTakeControl = onInterventionTakeControl,
                onInterventionContinue = onInterventionContinue,
                onApprovePayment = { blk -> onApprovePayment(message.id, blk) },
                onRejectPayment = { blk -> onRejectPayment(message.id, blk) },
                onSendEmailApproval = { blk, r, s, b -> onSendEmailApproval(message.id, blk, r, s, b) },
                onRejectEmailApproval = { blk -> onRejectEmailApproval(message.id, blk) },
                onRespondBrowserPermission = { blk, mode -> onRespondBrowserPermission(message.id, blk, mode) },
                onApproveRegistration = { blk -> onApproveRegistration(message.id, blk) },
                onRejectRegistration = { blk -> onRejectRegistration(message.id, blk) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantSingleBlockRow(
    message: AlloraRichMessage,
    block: ChatBlock?,
    blockIndex: Int,
    isLastBlock: Boolean,
    maxBubbleWidth: androidx.compose.ui.unit.Dp,
    showTypingDots: Boolean,
    liveTaskStep: String,
    shouldAnimateWords: Boolean,
    reducedMotion: Boolean,
    onLongPress: () -> Unit,
    onQuoteTap: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPhotoViewer: (List<ChatImageItem>, Int) -> Unit,
    onPlaceOrder: (String) -> Unit,
    onChipClick: (String) -> Unit,
    onRetry: () -> Unit,
    onFinishedWordStream: () -> Unit,
    browserLiveActivity: BrowserLiveActivityState = BrowserLiveActivityState(),
    autonomousTasks: List<AutonomousTaskRecord> = emptyList(),
    webViewHooks: LiveBrowserWebViewHooks = LiveBrowserWebViewHooks(),
    onBrowserAction: (BrowserCardAction) -> Unit = {},
    onInterventionTakeControl: (sessionId: String) -> Unit = {},
    onInterventionContinue: (sessionId: String) -> Unit = {},
    onApprovePayment: (ChatBlock.PaymentApproval) -> Unit = {},
    onRejectPayment: (ChatBlock.PaymentApproval) -> Unit = {},
    onSendEmailApproval: (ChatBlock.EmailApproval, String, String, String) -> Unit = { _, _, _, _ -> },
    onRejectEmailApproval: (ChatBlock.EmailApproval) -> Unit = {},
    onRespondBrowserPermission: (ChatBlock.BrowserPermission, String) -> Unit = { _, _ -> },
    onApproveRegistration: (ChatBlock.RegistrationApproval) -> Unit = {},
    onRejectRegistration: (ChatBlock.RegistrationApproval) -> Unit = {},
    onWalletManage: () -> Unit = {},
    onTaskControl: (taskId: String, action: String) -> Unit = { _, _ -> },
    pendingDomCommand: BrowserDomCommand? = null,
    onEmailReply: (String) -> Unit = {},
    onEmailForward: (String) -> Unit = {},
    onEmailDelete: (String) -> Unit = {},
    onExplainWalletTx: (String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (blockIndex == 0) {
            message.replyTo?.let { quote ->
                QuotedReplyHeader(
                    quote = quote,
                    alignEnd = false,
                    onClick = { onQuoteTap(quote.messageId) }
                )
            }
        }

        if (message.isStreaming && blockIndex == 0) {
            if (liveTaskStep.isNotBlank() && message.plainText.isBlank()) {
                LiveTaskCard(
                    block = ChatBlock.Task(
                        id = "live_${message.id}",
                        steps = listOf(liveTaskStep),
                        status = "running"
                    ),
                    reducedMotion = reducedMotion,
                    modifier = Modifier.widthIn(max = maxBubbleWidth)
                )
            }
            val showStreamingBubble = showTypingDots || message.plainText.isNotBlank()
            AnimatedVisibility(
                visible = showStreamingBubble,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(140))
            ) {
                val morphShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                Box(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .clip(morphShape)
                        .background(c.alloraBubbleBg)
                        .border(1.dp, c.alloraBubbleBorder, morphShape)
                        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                        .padding(
                            horizontal = if (message.plainText.isNotBlank()) 15.dp else 14.dp,
                            vertical = 12.dp
                        )
                        .let { mod ->
                            if (message.plainText.isNotBlank()) mod.testTag("chat_streaming_bubble_${message.id}")
                            else mod
                        }
                ) {
                    if (message.plainText.isBlank()) {
                        TypingDotsContent(reducedMotion = reducedMotion)
                    } else {
                        Text(
                            text = message.plainText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = c.alloraBubbleInk,
                                fontSize = 15.sp,
                                lineHeight = 21.5.sp
                            )
                        )
                    }
                }
            }
        } else if (block != null) {
            val errorInk = if (c.isDark) Color(0xFFFF8A93) else Color(0xFFB3261E)
            val errorBorder = if (c.isDark) Color(0xFFEF7A86).copy(alpha = 0.65f) else Color(0xFFB3261E).copy(alpha = 0.55f)
            if (blockIndex == 0 && block is ChatBlock.Text) {
                val bubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                Box(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .clip(bubbleShape)
                        .background(c.alloraBubbleBg)
                        .border(
                            width = 1.dp,
                            color = if (message.isError) errorBorder else c.alloraBubbleBorder,
                            shape = bubbleShape
                        )
                        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress
                        )
                        .padding(horizontal = 15.dp, vertical = 12.dp)
                        .let { mod ->
                            if (message.isError) mod.testTag("chat_error_bubble_${message.id}") else mod
                        }
                ) {
                    AnimatedContent(
                        targetState = block.content,
                        transitionSpec = {
                            fadeIn(tween(220)).togetherWith(fadeOut(tween(140)))
                        },
                        label = "typingToFirstBubble"
                    ) { textContent ->
                        WordStreamText(
                            fullText = textContent,
                            animateWords = shouldAnimateWords && !message.isError,
                            reducedMotion = reducedMotion,
                            color = if (message.isError) errorInk else c.alloraBubbleInk,
                            onStreamingCompleted = onFinishedWordStream
                        )
                    }
                }
            } else {
                RenderSingleChatBlock(
                    block = block,
                    maxBubbleWidth = maxBubbleWidth,
                    isError = message.isError,
                    shouldAnimateWords = shouldAnimateWords && blockIndex == 0 && !message.isError,
                    reducedMotion = reducedMotion,
                    onLongPress = onLongPress,
                    onOpenUrl = onOpenUrl,
                    onOpenPhotoViewer = onOpenPhotoViewer,
                    onPlaceOrder = onPlaceOrder,
                    onFinishedWordStream = onFinishedWordStream,
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

            if (isLastBlock) {
                if (message.isError) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.cardBg)
                            .border(1.dp, c.cardBorder, RoundedCornerShape(50))
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("retry_ai_button_${message.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Retry",
                            tint = c.textPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = c.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                } else if (message.followUpChips.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        message.followUpChips.take(3).forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(c.headerIconBg.copy(alpha = 0.85f))
                                    .border(1.dp, c.headerIconBorder, RoundedCornerShape(50))
                                    .clickable { onChipClick(chip) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = chip,
                                    style = MaterialTheme.typography.labelSmall.copy(color = c.textSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotedReplyHeader(
    quote: AlloraReplyQuote,
    alignEnd: Boolean,
    onClick: () -> Unit
) {
    val c = LocalAlloraChatColors.current
    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.cardBg.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("quoted_reply_${quote.messageId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .width(2.5.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.quoteAccent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = quote.senderLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = c.quoteAccent,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Text(
                text = quote.snippet,
                style = MaterialTheme.typography.labelSmall.copy(color = c.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UserAttachmentsRow(
    attachments: List<ChatAttachment>,
    onOpenPhotoViewer: (List<ChatImageItem>, Int) -> Unit
) {
    val c = LocalAlloraChatColors.current
    val imageAttachments = attachments.filter { it.isImage }
    val fileAttachments = attachments.filter { !it.isImage }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (imageAttachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                imageAttachments.forEachIndexed { idx, att ->
                    AlloraCardImage(
                        url = att.uriString,
                        contentDescription = att.displayName,
                        modifier = Modifier
                            .size(width = 136.dp, height = 160.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, c.userBubbleBorder, RoundedCornerShape(18.dp))
                            .clickable {
                                onOpenPhotoViewer(
                                    imageAttachments.map { ChatImageItem(it.uriString, it.displayName) },
                                    idx
                                )
                            }
                    )
                }
            }
        }
        fileAttachments.forEach { file ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.userBubbleBg)
                    .border(1.dp, c.userBubbleBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = c.accentBlue,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(color = c.userBubbleInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantMessageColumn(
    message: AlloraRichMessage,
    maxBubbleWidth: androidx.compose.ui.unit.Dp,
    showTypingDots: Boolean,
    liveTaskStep: String,
    shouldAnimateWords: Boolean,
    reducedMotion: Boolean,
    onLongPress: () -> Unit,
    onQuoteTap: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPhotoViewer: (List<ChatImageItem>, Int) -> Unit,
    onPlaceOrder: (String) -> Unit,
    onChipClick: (String) -> Unit,
    onRetry: () -> Unit,
    onFinishedWordStream: () -> Unit,
    browserLiveActivity: BrowserLiveActivityState = BrowserLiveActivityState(),
    autonomousTasks: List<AutonomousTaskRecord> = emptyList(),
    webViewHooks: LiveBrowserWebViewHooks = LiveBrowserWebViewHooks(),
    onBrowserAction: (BrowserCardAction) -> Unit = {},
    onInterventionTakeControl: (sessionId: String) -> Unit = {},
    onInterventionContinue: (sessionId: String) -> Unit = {},
    onApprovePayment: (ChatBlock.PaymentApproval) -> Unit = {},
    onRejectPayment: (ChatBlock.PaymentApproval) -> Unit = {},
    onSendEmailApproval: (ChatBlock.EmailApproval, String, String, String) -> Unit = { _, _, _, _ -> },
    onRejectEmailApproval: (ChatBlock.EmailApproval) -> Unit = {},
    onRespondBrowserPermission: (ChatBlock.BrowserPermission, String) -> Unit = { _, _ -> },
    onApproveRegistration: (ChatBlock.RegistrationApproval) -> Unit = {},
    onRejectRegistration: (ChatBlock.RegistrationApproval) -> Unit = {},
    onWalletManage: () -> Unit = {},
    onTaskControl: (taskId: String, action: String) -> Unit = { _, _ -> },
    pendingDomCommand: BrowserDomCommand? = null,
    onEmailReply: (String) -> Unit = {},
    onEmailForward: (String) -> Unit = {},
    onEmailDelete: (String) -> Unit = {},
    onExplainWalletTx: (String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    val blocks = message.blocks

    // Reveal further blocks cleanly 400ms apart after the first block arrives
    var revealedBlockCount by remember(message.id, blocks.size, shouldAnimateWords) {
        mutableIntStateOf(if (!shouldAnimateWords || reducedMotion) blocks.size else minOf(1, blocks.size))
    }
    LaunchedEffect(message.id, blocks.size, shouldAnimateWords, reducedMotion) {
        if (blocks.isEmpty()) {
            revealedBlockCount = 0
            return@LaunchedEffect
        }
        if (!shouldAnimateWords || reducedMotion) {
            revealedBlockCount = blocks.size
            return@LaunchedEffect
        }
        revealedBlockCount = 1
        for (count in 2..blocks.size) {
            delay(400L)
            revealedBlockCount = count
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        message.replyTo?.let { quote ->
            QuotedReplyHeader(
                quote = quote,
                alignEnd = false,
                onClick = { onQuoteTap(quote.messageId) }
            )
        }

        // Streaming state: either typing dots (morphing cleanly into first block) or live task step
        if (message.isStreaming) {
            if (liveTaskStep.isNotBlank()) {
                LiveTaskCard(
                    block = ChatBlock.Task(
                        id = "live_${message.id}",
                        steps = listOf(liveTaskStep),
                        status = "running"
                    ),
                    reducedMotion = reducedMotion,
                    modifier = Modifier.widthIn(max = maxBubbleWidth)
                )
            }
            AnimatedVisibility(
                visible = showTypingDots,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(140))
            ) {
                val morphShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                Box(
                    modifier = Modifier
                        .clip(morphShape)
                        .background(c.alloraBubbleBg)
                        .border(1.dp, c.alloraBubbleBorder, morphShape)
                        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    TypingDotsContent(reducedMotion = reducedMotion)
                }
            }
        } else if (blocks.isNotEmpty()) {
            // First block morphs cleanly in place from the typing bubble if it's a Text block
            val firstBlock = blocks.first()
            if (firstBlock is ChatBlock.Text) {
                val bubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                Box(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .clip(bubbleShape)
                        .background(c.alloraBubbleBg)
                        .border(
                            width = 1.dp,
                            color = if (message.isError) Color(0xFFD96B78).copy(alpha = 0.6f) else c.alloraBubbleBorder,
                            shape = bubbleShape
                        )
                        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress
                        )
                        .padding(horizontal = 15.dp, vertical = 12.dp)
                ) {
                    AnimatedContent(
                        targetState = firstBlock.content,
                        transitionSpec = {
                            fadeIn(tween(220)).togetherWith(fadeOut(tween(140)))
                        },
                        label = "typingToFirstBubble"
                    ) { textContent ->
                        WordStreamText(
                            fullText = textContent,
                            animateWords = shouldAnimateWords,
                            reducedMotion = reducedMotion,
                            color = if (message.isError) Color(0xFFEF8A95) else c.alloraBubbleInk,
                            onStreamingCompleted = onFinishedWordStream
                        )
                    }
                }
            } else {
                StaggeredBlockItem(visible = true, reducedMotion = reducedMotion) {
                    RenderSingleChatBlock(
                        block = firstBlock,
                        maxBubbleWidth = maxBubbleWidth,
                        isError = message.isError,
                        shouldAnimateWords = shouldAnimateWords,
                        reducedMotion = reducedMotion,
                        onLongPress = onLongPress,
                        onOpenUrl = onOpenUrl,
                        onOpenPhotoViewer = onOpenPhotoViewer,
                        onPlaceOrder = onPlaceOrder,
                        onFinishedWordStream = onFinishedWordStream,
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

            // Further blocks fade in and slide up 8.dp, ~400ms apart
            blocks.drop(1).forEachIndexed { idx, block ->
                val isRevealed = (idx + 2) <= revealedBlockCount
                StaggeredBlockItem(
                    visible = isRevealed,
                    reducedMotion = reducedMotion
                ) {
                    RenderSingleChatBlock(
                        block = block,
                        maxBubbleWidth = maxBubbleWidth,
                        isError = message.isError,
                        shouldAnimateWords = shouldAnimateWords,
                        reducedMotion = reducedMotion,
                        onLongPress = onLongPress,
                        onOpenUrl = onOpenUrl,
                        onOpenPhotoViewer = onOpenPhotoViewer,
                        onPlaceOrder = onPlaceOrder,
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
            }

            if (message.isError) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.cardBg)
                        .border(1.dp, c.cardBorder, RoundedCornerShape(50))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = c.textPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelMedium.copy(color = c.textPrimary)
                    )
                }
            } else if (message.followUpChips.isNotEmpty() && revealedBlockCount >= blocks.size) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    message.followUpChips.take(3).forEach { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(c.headerIconBg.copy(alpha = 0.85f))
                                .border(1.dp, c.headerIconBorder, RoundedCornerShape(50))
                                .clickable { onChipClick(chip) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall.copy(color = c.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaggeredBlockItem(
    visible: Boolean,
    reducedMotion: Boolean,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val slidePx = with(density) { 8.dp.toPx() }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 240),
        label = "blockAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (visible) 0f else slidePx,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 240, easing = FastOutSlowInEasing),
        label = "blockSlideUp"
    )
    if (visible || alpha > 0.01f) {
        Box(
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha
                this.translationY = translationY
            }
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RenderSingleChatBlock(
    block: ChatBlock,
    maxBubbleWidth: androidx.compose.ui.unit.Dp,
    isError: Boolean,
    shouldAnimateWords: Boolean,
    reducedMotion: Boolean,
    onLongPress: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPhotoViewer: (List<ChatImageItem>, Int) -> Unit,
    onPlaceOrder: (String) -> Unit,
    onFinishedWordStream: () -> Unit,
    browserLiveActivity: BrowserLiveActivityState = BrowserLiveActivityState(),
    autonomousTasks: List<AutonomousTaskRecord> = emptyList(),
    webViewHooks: LiveBrowserWebViewHooks = LiveBrowserWebViewHooks(),
    onBrowserAction: (BrowserCardAction) -> Unit = {},
    onInterventionTakeControl: (sessionId: String) -> Unit = {},
    onInterventionContinue: (sessionId: String) -> Unit = {},
    onApprovePayment: (ChatBlock.PaymentApproval) -> Unit = {},
    onRejectPayment: (ChatBlock.PaymentApproval) -> Unit = {},
    onSendEmailApproval: (ChatBlock.EmailApproval, String, String, String) -> Unit = { _, _, _, _ -> },
    onRejectEmailApproval: (ChatBlock.EmailApproval) -> Unit = {},
    onRespondBrowserPermission: (ChatBlock.BrowserPermission, String) -> Unit = { _, _ -> },
    onApproveRegistration: (ChatBlock.RegistrationApproval) -> Unit = {},
    onRejectRegistration: (ChatBlock.RegistrationApproval) -> Unit = {},
    onWalletManage: () -> Unit = {},
    onTaskControl: (taskId: String, action: String) -> Unit = { _, _ -> },
    pendingDomCommand: BrowserDomCommand? = null,
    onEmailReply: (String) -> Unit = {},
    onEmailForward: (String) -> Unit = {},
    onEmailDelete: (String) -> Unit = {},
    onExplainWalletTx: (String) -> Unit = {}
) {
    val c = LocalAlloraChatColors.current
    when (block) {
        is ChatBlock.Text -> {
            val errorInk = if (c.isDark) Color(0xFFFF8A93) else Color(0xFFB3261E)
            val errorBorder = if (c.isDark) Color(0xFFEF7A86).copy(alpha = 0.65f) else Color(0xFFB3261E).copy(alpha = 0.55f)
            val shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(shape)
                    .background(c.alloraBubbleBg)
                    .border(
                        width = 1.dp,
                        color = if (isError) errorBorder else c.alloraBubbleBorder,
                        shape = shape
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    )
                    .padding(horizontal = 15.dp, vertical = 12.dp)
            ) {
                WordStreamText(
                    fullText = block.content,
                    animateWords = shouldAnimateWords,
                    reducedMotion = reducedMotion,
                    color = if (isError) errorInk else c.alloraBubbleInk,
                    onStreamingCompleted = onFinishedWordStream
                )
            }
        }
        is ChatBlock.Links -> LinksCarouselCard(
            block = block,
            onOpenUrl = onOpenUrl
        )
        is ChatBlock.Table -> TableOrPhotoGridCard(
            block = block,
            onOpenUrl = onOpenUrl,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.OrderSummary -> OrderSummaryCard(
            block = block,
            onPlaceOrder = { onPlaceOrder(block.id) },
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.Summary -> SummaryCard(
            block = block,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.Draft -> DraftCard(
            block = block,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.Images -> PhotoDeckSliderCard(
            block = block,
            reducedMotion = reducedMotion,
            onOpenViewer = onOpenPhotoViewer
        )
        is ChatBlock.Task -> LiveTaskCard(
            block = block,
            reducedMotion = reducedMotion,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.LiveBrowser -> {
            val linkedTask = browserLiveActivity.taskId?.let { tid ->
                autonomousTasks.firstOrNull { it.taskId == tid }
            }
            val taskProgressLabel = linkedTask?.let { task ->
                if (task.totalStepsCount > 0) {
                    "Step ${task.completedStepsCount}/${task.totalStepsCount} • ${task.status.name.lowercase().replace('_', ' ')}"
                } else {
                    task.status.name.lowercase().replace('_', ' ')
                }
            }
            LiveBrowserChatCard(
                block = block,
                liveActivity = browserLiveActivity,
                onAction = onBrowserAction,
                webViewHooks = webViewHooks,
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                pendingDomCommand = pendingDomCommand,
                taskProgressSummary = taskProgressLabel
            )
        }
        is ChatBlock.HumanIntervention -> HumanInterventionChatCard(
            block = block,
            onTakeControl = { onInterventionTakeControl(block.sessionId) },
            onContinueTask = { onInterventionContinue(block.sessionId) },
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.PaymentApproval -> PaymentApprovalChatCard(
            block = block,
            onApprove = { onApprovePayment(block) },
            onReject = { onRejectPayment(block) },
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.EmailApproval -> EmailApprovalChatCard(
            block = block,
            onApproveAndSend = { recipient, subject, body ->
                onSendEmailApproval(block, recipient, subject, body)
            },
            onReject = { onRejectEmailApproval(block) },
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.EmailDigest -> EmailDigestChatCard(
            block = block,
            onReplyEmail = onEmailReply,
            onForwardEmail = onEmailForward,
            onDeleteEmail = onEmailDelete,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.BrowserPermission -> BrowserPermissionChatCard(
            block = block,
            onRespond = { mode -> onRespondBrowserPermission(block, mode) },
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.RegistrationApproval -> RegistrationApprovalChatCard(
            block = block,
            onApprove = { onApproveRegistration(block) },
            onReject = { onRejectRegistration(block) },
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.WalletCard -> WalletChatCard(
            block = block,
            onAddMoneyClick = onWalletManage,
            onExplainTransaction = onExplainWalletTx,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        )
        is ChatBlock.TaskTimeline -> {
            val liveTask = autonomousTasks.firstOrNull { it.taskId == block.taskId }
            TaskTimelineChatCard(
                block = block,
                liveTask = liveTask,
                onPauseResumeCancel = onTaskControl,
                modifier = Modifier.widthIn(max = maxBubbleWidth)
            )
        }
    }
}
