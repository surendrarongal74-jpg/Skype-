package com.example.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import com.example.ui.theme.PlayfairDisplayFontFamily
import com.example.ui.theme.TabularNumberStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AlloraCardImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val c = LocalAlloraChatColors.current
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier.background(c.cardInnerBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(26.dp)
            )
        }
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(c.shimmerBase, c.shimmerHighlight, c.shimmerBase)
                            )
                        )
                        .blur(10.dp)
                )
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(c.cardInnerBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = c.textMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )
    }
}

/**
 * Stacked photo slider deck matching the center deck in the screenshots:
 * top photo full width, 1-2 cards peeking behind offset ~8.dp rotated ±2.5°-3°,
 * swipe left/right to spring top out and bring next up, tap to open full-screen viewer.
 */
@Composable
fun PhotoDeckSliderCard(
    block: ChatBlock.Images,
    reducedMotion: Boolean,
    onOpenViewer: (List<ChatImageItem>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val items = block.items
    if (items.isEmpty()) return

    var frontIndex by remember(block.id, items.size) { mutableIntStateOf(0) }
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 82.dp.toPx() }
    val flyOutPx = with(density) { 360.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("photo_deck_${block.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 264.dp)
                    .fillMaxWidth(0.76f)
                    .aspectRatio(0.88f),
                contentAlignment = Alignment.Center
            ) {
                val visibleStackCount = minOf(3, items.size)
                // Draw back cards first (layer = visibleStackCount - 1 downTo 0)
                for (layer in (visibleStackCount - 1) downTo 0) {
                    val idx = (frontIndex + layer) % items.size
                    val photo = items[idx]
                    val isTop = (layer == 0)

                    val stackRotation = when (layer) {
                        0 -> (dragX.value / flyOutPx) * 10f
                        1 -> 3.0f
                        else -> -2.6f
                    }
                    val offsetX = when (layer) {
                        0 -> dragX.value
                        1 -> with(density) { 8.dp.toPx() }
                        else -> with(density) { (-8).dp.toPx() }
                    }
                    val offsetY = when (layer) {
                        0 -> 0f
                        1 -> with(density) { 8.dp.toPx() }
                        else -> with(density) { 14.dp.toPx() }
                    }
                    val stackScale = when (layer) {
                        0 -> 1f
                        1 -> 0.95f
                        else -> 0.90f
                    }

                    val cardModifier = Modifier
                        .fillMaxSize()
                        .zIndex((visibleStackCount - layer).toFloat())
                        .graphicsLayer {
                            translationX = offsetX
                            translationY = offsetY
                            rotationZ = stackRotation
                            scaleX = stackScale
                            scaleY = stackScale
                        }
                        .shadow(
                            elevation = if (isTop) 14.dp else 6.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = Color.Black.copy(alpha = 0.45f),
                            spotColor = Color.Black.copy(alpha = 0.45f)
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            width = 1.dp,
                            color = c.cardBorder,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .then(
                            if (isTop && items.size > 1) {
                                Modifier.pointerInput(items.size, frontIndex) {
                                    detectHorizontalDragGestures(
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            scope.launch {
                                                dragX.snapTo(dragX.value + dragAmount)
                                            }
                                        },
                                        onDragEnd = {
                                            scope.launch {
                                                if (abs(dragX.value) >= swipeThresholdPx) {
                                                    val targetOut = if (dragX.value > 0) flyOutPx else -flyOutPx
                                                    if (!reducedMotion) {
                                                        dragX.animateTo(
                                                            targetValue = targetOut,
                                                            animationSpec = spring(
                                                                dampingRatio = 0.8f,
                                                                stiffness = Spring.StiffnessMedium
                                                            )
                                                        )
                                                    }
                                                    frontIndex = if (dragX.value < 0) {
                                                        (frontIndex + 1) % items.size
                                                    } else {
                                                        (frontIndex - 1 + items.size) % items.size
                                                    }
                                                    dragX.snapTo(0f)
                                                } else {
                                                    dragX.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(
                                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            scope.launch {
                                                dragX.animateTo(0f, spring())
                                            }
                                        }
                                    )
                                }
                            } else Modifier
                        )
                        .clickable(enabled = isTop) {
                            onOpenViewer(items, frontIndex)
                        }

                    Box(modifier = cardModifier) {
                        AlloraCardImage(
                            url = photo.url,
                            contentDescription = photo.caption ?: block.caption ?: "Photo ${idx + 1}",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Expand viewer circular button beside deck matching screenshot 2
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(c.headerIconBg)
                    .border(1.dp, c.headerIconBorder, CircleShape)
                    .clickable { onOpenViewer(items, frontIndex) }
                    .testTag("photo_deck_expand_${block.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CropFree,
                    contentDescription = "Open full screen viewer",
                    tint = c.headerIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (items.size > 1) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { index ->
                    val active = index == frontIndex
                    val dotWidth by animateDpAsState(
                        targetValue = if (active) 16.dp else 6.dp,
                        animationSpec = spring(),
                        label = "dotWidth"
                    )
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(if (active) c.textPrimary else c.textMuted.copy(alpha = 0.5f))
                    )
                }
            }
        }

        val activeCaption = items.getOrNull(frontIndex)?.caption ?: block.caption
        if (!activeCaption.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = activeCaption,
                style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * Full-screen black photo viewer with swipe pager, pinch-to-zoom, double-tap zoom,
 * and swipe-down to dismiss shrinking cleanly.
 */
@Composable
fun FullScreenPhotoViewerOverlay(
    images: List<ChatImageItem>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.lastIndex)) {
        images.size
    }
    var dismissDragY by remember { mutableFloatStateOf(0f) }
    val bgAlpha = (1f - (abs(dismissDragY) / 680f)).coerceIn(0.25f, 1f)
    val dragScale = (1f - (abs(dismissDragY) / 1400f)).coerceIn(0.74f, 1f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = bgAlpha))
                .testTag("fullscreen_photo_viewer")
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                var zoom by remember(page) { mutableFloatStateOf(1f) }
                var pan by remember(page) { mutableStateOf(Offset.Zero) }
                val animatedZoom by animateFloatAsState(
                    targetValue = zoom,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "viewerZoom"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoom > 1.15f) {
                                        zoom = 1f
                                        pan = Offset.Zero
                                    } else {
                                        zoom = 2.4f
                                    }
                                }
                            )
                        }
                        .pointerInput(page) {
                            detectTransformGestures { _, panDelta, zoomDelta, _ ->
                                val nextZoom = (zoom * zoomDelta).coerceIn(1f, 4f)
                                zoom = nextZoom
                                if (nextZoom > 1.05f) {
                                    pan = Offset(
                                        x = (pan.x + panDelta.x).coerceIn(-600f, 600f),
                                        y = (pan.y + panDelta.y).coerceIn(-600f, 600f)
                                    )
                                } else {
                                    pan = Offset.Zero
                                }
                            }
                        }
                        .pointerInput(page, zoom) {
                            if (zoom <= 1.05f) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        if (dismissDragY + dragAmount >= -40f) {
                                            change.consume()
                                            dismissDragY += dragAmount
                                        }
                                    },
                                    onDragEnd = {
                                        if (dismissDragY > 180f) {
                                            onDismiss()
                                        } else {
                                            dismissDragY = 0f
                                        }
                                    },
                                    onDragCancel = { dismissDragY = 0f }
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AlloraCardImage(
                        url = images[page].url,
                        contentDescription = images[page].caption ?: "Full screen photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = pan.x
                                translationY = pan.y + dismissDragY
                                scaleX = animatedZoom * dragScale
                                scaleY = animatedZoom * dragScale
                            }
                    )
                }
            }

            // Top Chrome
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    style = TabularNumberStyle.copy(color = Color.White, fontSize = 14.sp)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .testTag("fullscreen_viewer_close")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close viewer",
                        tint = Color.White
                    )
                }
            }

            val caption = images.getOrNull(pagerState.currentPage)?.caption
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.9f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 32.dp)
                )
            }
        }
    }
}

/**
 * Horizontal snap carousel of Link cards (image, name, small role label: Menu, Map, Reserve)
 * with the next card peeking cleanly on the right.
 */
@Composable
fun LinksCarouselCard(
    block: ChatBlock.Links,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LazyRow(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(end = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("links_carousel_${block.id}")
    ) {
        items(block.items) { item ->
            Column(
                modifier = Modifier
                    .width(214.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.cardBg)
                    .border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
                    .clickable { onOpenUrl(item.url) }
            ) {
                if (!item.imageUrl.isNullOrBlank()) {
                    AlloraCardImage(
                        url = item.imageUrl,
                        contentDescription = item.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(108.dp)
                    )
                }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.roleChipBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = item.role,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = c.roleChipInk,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = c.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = c.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Table card: title, columns, rows, tappable link column.
 * If any column is named "Photo" (case-insensitive), automatically renders as a 2-column grid of tiles
 * showing picture, name, and price.
 */
@Composable
fun TableOrPhotoGridCard(
    block: ChatBlock.Table,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val photoColIndex = block.columns.indexOfFirst { it.equals("Photo", ignoreCase = true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
            .testTag("table_card_${block.id}")
    ) {
        Text(
            text = block.title,
            style = MaterialTheme.typography.titleMedium.copy(
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (photoColIndex >= 0) {
            // 2-column photo grid mode
            val nameColIdx = block.columns.indexOfFirst {
                it.contains("name", true) || it.contains("item", true) || it.contains("title", true)
            }.takeIf { it >= 0 } ?: block.columns.indices.firstOrNull { it != photoColIndex } ?: 0
            val priceColIdx = block.columns.indexOfFirst {
                it.contains("price", true) || it.contains("cost", true) || it.contains("rate", true)
            }
            val linkColIdx = block.linkColumn?.let { linkColName ->
                block.columns.indexOfFirst { it.equals(linkColName, ignoreCase = true) }
            } ?: -1

            val chunkedRows = block.rows.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                chunkedRows.forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { row ->
                            val photoUrl = row.getOrNull(photoColIndex)
                            val title = row.getOrNull(nameColIdx).orEmpty()
                            val price = if (priceColIdx >= 0) row.getOrNull(priceColIdx).orEmpty() else ""
                            val linkUrl = if (linkColIdx >= 0) row.getOrNull(linkColIdx) else null
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(c.cardInnerBg)
                                    .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                                    .then(
                                        if (!linkUrl.isNullOrBlank()) {
                                            Modifier.clickable { onOpenUrl(linkUrl) }
                                        } else Modifier
                                    )
                            ) {
                                AlloraCardImage(
                                    url = photoUrl,
                                    contentDescription = title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(98.dp)
                                )
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = c.textPrimary,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (price.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = price,
                                            style = TabularNumberStyle.copy(
                                                color = c.accentBlue,
                                                fontSize = 13.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            // Standard scrollable structured table with tappable link column
            val linkColIdx = block.linkColumn?.let { linkColName ->
                block.columns.indexOfFirst { it.equals(linkColName, ignoreCase = true) }
            } ?: -1

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                if (block.columns.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.cardInnerBg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        block.columns.forEach { col ->
                            Text(
                                text = col,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = c.textSecondary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.widthIn(min = 84.dp, max = 140.dp)
                            )
                        }
                    }
                }
                block.rows.forEachIndexed { rIdx, row ->
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEachIndexed { cIdx, cell ->
                            val isLink = cIdx == linkColIdx && cell.isNotBlank()
                            Text(
                                text = if (isLink) "Open ↗" else cell,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (isLink) c.accentBlue else c.textPrimary,
                                    fontWeight = if (isLink) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(min = 84.dp, max = 140.dp)
                                    .then(
                                        if (isLink) Modifier.clickable { onOpenUrl(cell) } else Modifier
                                    )
                            )
                        }
                    }
                    if (rIdx < block.rows.lastIndex) {
                        HorizontalDivider(color = c.hairline, thickness = 0.6.dp)
                    }
                }
            }
        }
    }
}

private enum class OrderButtonPhase { READY, ORDERING, CHECKING, PLACED }

/**
 * Order card: merchant logo + name, line items with thumbnail/qty/price, fees, total, payment method,
 * and full-width button that shrinks -> "Ordering" spinner -> animated checkmark draws in -> "Order Placed".
 */
@Composable
fun OrderSummaryCard(
    block: ChatBlock.OrderSummary,
    onPlaceOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    var phase by remember(block.id, block.orderPlaced) {
        mutableStateOf(if (block.orderPlaced) OrderButtonPhase.PLACED else OrderButtonPhase.READY)
    }
    var orderingStarted by remember(block.id, block.orderPlaced) {
        mutableStateOf(false)
    }
    val checkProgress = remember(block.id) { Animatable(if (block.orderPlaced) 1f else 0f) }

    LaunchedEffect(block.id, orderingStarted) {
        if (orderingStarted && phase != OrderButtonPhase.PLACED) {
            phase = OrderButtonPhase.ORDERING
            delay(950L)
            phase = OrderButtonPhase.CHECKING
            checkProgress.snapTo(0f)
            checkProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
            )
            delay(300L)
            phase = OrderButtonPhase.PLACED
            onPlaceOrder()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .testTag("order_card_${block.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!block.merchantLogo.isNullOrBlank()) {
                AlloraCardImage(
                    url = block.merchantLogo,
                    contentDescription = block.merchantName,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(c.cardInnerBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Storefront,
                        contentDescription = null,
                        tint = c.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = block.merchantName,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = c.hairline, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlloraCardImage(
                        url = item.thumbnail,
                        contentDescription = item.name,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = c.textPrimary,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Qty ${item.qty}",
                            style = MaterialTheme.typography.labelSmall.copy(color = c.textMuted)
                        )
                    }
                    Text(
                        text = item.price,
                        style = TabularNumberStyle.copy(color = c.textPrimary, fontSize = 14.sp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = c.hairline, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Fees & Tax", style = MaterialTheme.typography.bodySmall.copy(color = c.textSecondary))
            Text(block.fees, style = TabularNumberStyle.copy(color = c.textSecondary, fontSize = 13.sp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Total",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = c.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                block.total,
                style = TabularNumberStyle.copy(
                    color = c.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CreditCard,
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = block.paymentMethod,
                style = MaterialTheme.typography.labelMedium.copy(color = c.textSecondary)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val targetWidth = when (phase) {
                OrderButtonPhase.READY -> maxWidth
                OrderButtonPhase.ORDERING -> 156.dp
                OrderButtonPhase.CHECKING -> 52.dp
                OrderButtonPhase.PLACED -> maxWidth
            }
            val animatedWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "orderBtnWidth"
            )
            val btnBg = if (phase == OrderButtonPhase.PLACED || phase == OrderButtonPhase.CHECKING) {
                c.accentSuccess
            } else {
                c.sendBtnBg
            }
            val btnInk = if (phase == OrderButtonPhase.PLACED || phase == OrderButtonPhase.CHECKING) {
                Color(0xFF0A140E)
            } else {
                c.sendBtnInk
            }

            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(btnBg)
                    .clickable(enabled = phase == OrderButtonPhase.READY && !orderingStarted) {
                        phase = OrderButtonPhase.ORDERING
                        orderingStarted = true
                    }
                    .testTag("order_action_button_${block.id}"),
                contentAlignment = Alignment.Center
            ) {
                when (phase) {
                    OrderButtonPhase.READY -> {
                        Text(
                            text = "Place Order",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = btnInk,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                    OrderButtonPhase.ORDERING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = btnInk,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Ordering",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = btnInk,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                    OrderButtonPhase.CHECKING -> {
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val checkPath = Path().apply {
                                moveTo(size.width * 0.2f, size.height * 0.54f)
                                lineTo(size.width * 0.43f, size.height * 0.76f)
                                lineTo(size.width * 0.82f, size.height * 0.28f)
                            }
                            val pm = PathMeasure()
                            pm.setPath(checkPath, false)
                            val segment = Path()
                            pm.getSegment(0f, pm.length * checkProgress.value, segment, true)
                            drawPath(
                                path = segment,
                                color = btnInk,
                                style = Stroke(
                                    width = 3.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                    OrderButtonPhase.PLACED -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = btnInk,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Order Placed",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = btnInk,
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

/**
 * Summary card matching screenshot 3 ("Free AI Roblox Video Workflow"):
 * Bold high-contrast serif headline, recessed inner rounded container, fade bottom edge when collapsed,
 * and animated "Show more" / "Show less" toggle.
 */
@Composable
fun SummaryCard(
    block: ChatBlock.Summary,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    var expanded by remember(block.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(22.dp))
            .padding(18.dp)
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .testTag("summary_card_${block.id}")
    ) {
        Text(
            text = block.title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = PlayfairDisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 23.sp,
                lineHeight = 29.sp,
                color = c.textPrimary
            )
        )
        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.cardInnerBg)
                .border(1.dp, c.hairline, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Text(
                text = block.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = c.textPrimary.copy(alpha = 0.92f),
                    lineHeight = 21.sp
                ),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis
            )

            if (!expanded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, c.cardInnerBg)
                            )
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .testTag("summary_toggle_${block.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = c.accentBlue,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = c.accentBlue,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Draft card: title, clean copyable text, Copy button that transitions briefly to a Copied checkmark.
 */
@Composable
fun DraftCard(
    block: ChatBlock.Draft,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.id) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800L)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .testTag("draft_card_${block.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (copied) c.accentSuccess.copy(alpha = 0.18f) else c.roleChipBg)
                    .clickable {
                        clipboard.setText(AnnotatedString(block.body))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("draft_copy_button_${block.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "Copy draft",
                    tint = if (copied) c.accentSuccess else c.roleChipInk,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (copied) c.accentSuccess else c.roleChipInk,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.cardInnerBg)
                .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                text = block.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = c.textPrimary.copy(alpha = 0.94f),
                    lineHeight = 20.sp
                )
            )
        }
    }
}

/**
 * Live task card: globe icon, crossfading step text, shimmer sweep while running, check pop when done.
 */
@Composable
fun LiveTaskCard(
    block: ChatBlock.Task,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val c = LocalAlloraChatColors.current
    val isDone = block.status.equals("done", ignoreCase = true)
    var stepIndex by remember(block.id, block.steps) { mutableIntStateOf(0) }

    LaunchedEffect(block.id, block.steps, isDone) {
        if (!isDone && block.steps.size > 1) {
            while (true) {
                delay(1500L)
                stepIndex = (stepIndex + 1) % block.steps.size
            }
        } else if (isDone && block.steps.isNotEmpty()) {
            stepIndex = block.steps.lastIndex
        }
    }

    val shimmerTransition = rememberInfiniteTransition(label = "taskShimmer")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isDone) 1f else 0.7f,
        animationSpec = if (reducedMotion) tween(0) else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "taskCheckScale"
    )

    val activeText = block.steps.getOrNull(stepIndex) ?: "Completed"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.cardBg)
            .border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
            .testTag("task_card_${block.id}")
    ) {
        if (!isDone && !reducedMotion) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.32f)
                    .background(
                        Brush.linearGradient(
                            colorStops = arrayOf(
                                (shimmerOffset - 0.25f).coerceIn(0f, 1f) to Color.Transparent,
                                shimmerOffset.coerceIn(0f, 1f) to c.shimmerHighlight,
                                (shimmerOffset + 0.25f).coerceIn(0f, 1f) to Color.Transparent
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = if (isDone) c.textSecondary else c.accentBlue,
                modifier = Modifier.size(18.dp)
            )

            AnimatedContent(
                targetState = activeText,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 3 })
                        .togetherWith(fadeOut(tween(180)))
                },
                label = "taskStepCrossfade",
                modifier = Modifier.weight(1f)
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = c.textPrimary,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isDone) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Task completed",
                    tint = c.accentSuccess,
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        }
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = c.accentBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
