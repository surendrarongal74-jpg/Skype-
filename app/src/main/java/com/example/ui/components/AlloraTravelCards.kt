package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.SuccessMint
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.theme.WarningAmber

/**
 * 16. GlassOfferCard — Minimal neutral translucent offer card distinguishing sample vs live providers.
 */
@Composable
fun GlassOfferCard(
    title: String,
    subtitle: String,
    priceLabel: String,
    providerLabel: String,
    isSampleEstimate: Boolean,
    metaBadge: String,
    actionLabel: String,
    isSaved: Boolean = false,
    onToggleSave: (() -> Unit)? = null,
    onActionClick: () -> Unit,
    onCardClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        material = GlassMaterial.FROSTED,
        onClick = onCardClick,
        accentBorder = if (isSampleEstimate) glass.statusWarning.copy(alpha = 0.35f) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )
            }
            if (onToggleSave != null) {
                IconButton(onClick = onToggleSave, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (isSaved) "Remove bookmark" else "Save offer",
                        tint = if (isSaved) glass.textPrimary else glass.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = priceLabel,
                style = TabularNumberStyle.copy(
                    fontSize = 18.sp,
                    color = glass.textPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusGlassPill(
                label = if (isSampleEstimate) "Sample Estimate" else "Adapter Ready",
                color = if (isSampleEstimate) glass.statusWarning else glass.textSecondary
            )
            StatusGlassPill(label = metaBadge, color = glass.textSecondary)
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = providerLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = glass.textMuted,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            GlassButton(
                text = actionLabel,
                onClick = onActionClick,
                isPrimary = true,
                material = GlassMaterial.PEARL
            )
        }
    }
}

/**
 * 16b. GlassBoardingFlightCard — Minimal aviation boarding pass over translucent frosted glass.
 */
@Composable
fun GlassBoardingFlightCard(
    originIata: String,
    originCity: String,
    destinationIata: String,
    destinationCity: String,
    departureTime: String,
    arrivalTime: String,
    duration: String,
    airline: String,
    flightNumber: String,
    stopsLabel: String,
    cabinClass: String,
    priceUsd: Double,
    isSampleEstimate: Boolean,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onCardClick: () -> Unit,
    onStageHold: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        material = GlassMaterial.FROSTED,
        onClick = onCardClick,
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(originIata, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Text(originCity, style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
            }

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                        drawLine(
                            color = glass.glassBorder,
                            start = Offset(0f, size.height / 2f),
                            end = Offset(size.width, size.height / 2f),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.FlightTakeoff,
                        contentDescription = null,
                        tint = glass.textPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text("$duration • $stopsLabel", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(destinationIata, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Text(destinationCity, style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(departureTime, style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 13.sp))
                Text(flightNumber, style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted))
            }
            StatusGlassPill(
                label = if (isSampleEstimate) "Sample • $cabinClass" else "Adapter • $cabinClass",
                color = if (isSampleEstimate) glass.statusWarning else glass.textSecondary
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(arrivalTime, style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 13.sp))
                IconButton(onClick = onToggleSave, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Save flight",
                        tint = if (isSaved) glass.textPrimary else glass.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass))
                .border(0.8.dp, resolveGlassBorderBrush(GlassMaterial.PEARL, glass), RoundedCornerShape(20.dp))
                .clickable(onClick = onStageHold)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(glass.pearlInk.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FlightTakeoff, contentDescription = null, tint = glass.pearlInk, modifier = Modifier.size(15.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = airline,
                    style = MaterialTheme.typography.labelLarge.copy(color = glass.pearlInk, fontWeight = FontWeight.SemiBold)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$${priceUsd.toInt()}",
                    style = TabularNumberStyle.copy(color = glass.pearlInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Stage flight hold", tint = glass.pearlInk, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 16c. GlassPhotoOfferCard — Clean image-free transparent-glass offer card for Hotels, Restaurants, Excursions, POIs & Cars.
 */
@Composable
fun GlassPhotoOfferCard(
    title: String,
    subtitle: String,
    priceLabel: String,
    ratingLabel: String,
    icon: ImageVector = Icons.Default.Explore,
    badgeLabel: String,
    isSampleEstimate: Boolean,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onCardClick: () -> Unit,
    onActionClick: () -> Unit,
    actionLabel: String = "Details",
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        material = GlassMaterial.FROSTED,
        onClick = onCardClick,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            GlassSurface(
                modifier = Modifier.size(46.dp),
                role = GlassSurfaceRole.CHIP,
                material = GlassMaterial.CLEAR,
                shape = RoundedCornerShape(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = glass.textPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleSave, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Save item",
                            tint = if (isSaved) glass.statusError else glass.textSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusGlassPill(label = badgeLabel, color = glass.textSecondary)
                GlassSurface(
                    role = GlassSurfaceRole.CHIP,
                    material = GlassMaterial.CLEAR,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(ratingLabel, style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 11.sp))
                    }
                }
            }

            Text(
                text = if (isSampleEstimate) "Sample" else "Live Catalog",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (isSampleEstimate) glass.statusWarning else glass.textMuted,
                    fontSize = 10.sp
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = priceLabel,
                style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass))
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium.copy(color = glass.pearlInk, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/**
 * 17. GlassDestinationCard — Clean image-free transparent-glass destination showcase card.
 */
@Composable
fun GlassDestinationCard(
    city: String,
    country: String,
    tagline: String,
    weatherSummary: String,
    rating: Double,
    @Suppress("UNUSED_PARAMETER") paletteSeed: Int,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    priceEstimateLabel: String? = null
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        modifier = modifier,
        cornerRadius = 24.dp,
        material = GlassMaterial.FROSTED,
        onClick = onClick,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Top row: Live Weather Pill + Bookmark Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassSurface(
                modifier = Modifier.weight(1f, fill = false),
                role = GlassSurfaceRole.CHIP,
                material = GlassMaterial.CLEAR,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WbSunny, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = weatherSummary,
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            GlassCircleIconButton(
                icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "Save destination",
                onClick = onToggleSave,
                tint = if (isSaved) glass.textPrimary else glass.textSecondary,
                size = 40.dp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // City, Country & Rating
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassSurface(
                    modifier = Modifier.size(36.dp),
                    role = GlassSurfaceRole.CHIP,
                    material = GlassMaterial.CLEAR,
                    shape = CircleShape,
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(17.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = city,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = country,
                        style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            GlassSurface(
                role = GlassSurfaceRole.CHIP,
                material = GlassMaterial.CLEAR,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "$rating",
                        style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 12.sp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = tagline,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = priceEstimateLabel ?: "Explore guide",
                style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(resolveGlassSurfaceBrush(GlassMaterial.PEARL, glass))
                    .border(0.8.dp, resolveGlassBorderBrush(GlassMaterial.PEARL, glass), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "View",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = glass.pearlInk,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open $city",
                    tint = glass.pearlInk,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 18. GlassItineraryCard — Timeline card with time window, category pill, transit duration & cost.
 */
@Composable
fun GlassItineraryCard(
    timeRange: String,
    title: String,
    location: String,
    category: String,
    durationMinutes: Int,
    syncStatus: String,
    estimatedCostUsd: Double,
    note: String? = null,
    onMoveUp: (() -> Unit)? = null,
    onEditNote: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        material = GlassMaterial.FROSTED,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusGlassPill(label = timeRange, color = glass.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusGlassPill(label = category, color = glass.textSecondary)
                if (estimatedCostUsd > 0) {
                    Text(
                        text = "$${estimatedCostUsd.toInt()}",
                        style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 13.sp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = location,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        if (!note.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Note: $note",
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textMuted)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = glass.textMuted, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${durationMinutes}m • $syncStatus",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onMoveUp != null) {
                    Text(
                        text = "Move Up",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable(onClick = onMoveUp).padding(4.dp)
                    )
                }
                if (onEditNote != null) {
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable(onClick = onEditNote).padding(4.dp)
                    )
                }
                if (onDelete != null) {
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.statusError, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable(onClick = onDelete).padding(4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 19. GlassBookingCard — Minimal neutral translucent booking voucher card.
 */
@Composable
fun GlassBookingCard(
    referenceCode: String,
    title: String,
    providerSource: String,
    providerPnr: String?,
    amountUsd: Double,
    status: String,
    timestamp: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val statusColor = when {
        status.contains("CONFIRMED", ignoreCase = true) -> glass.statusSuccess
        status.contains("CANCELLED", ignoreCase = true) -> glass.statusError
        status.contains("HELD", ignoreCase = true) || status.contains("REQUESTED", ignoreCase = true) -> glass.statusWarning
        else -> glass.textSecondary
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        material = GlassMaterial.FROSTED,
        onClick = onClick,
        accentBorder = statusColor.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ConfirmationNumber, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = referenceCode,
                    style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 13.sp)
                )
            }
            StatusGlassPill(label = status, color = statusColor)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = providerSource,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (providerPnr != null) "PNR: $providerPnr" else "Hold Ref • Partner PNR Not Issued",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (providerPnr != null) glass.textPrimary else glass.statusWarning,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$${amountUsd.toInt()}",
                    style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 16.sp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(Icons.Default.QrCode2, contentDescription = "View pass QR", tint = glass.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun StatusGlassPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier,
        role = GlassSurfaceRole.CHIP,
        material = GlassMaterial.CLEAR,
        shape = RoundedCornerShape(12.dp),
        borderWidth = 0.75.dp,
        accentBorder = color.copy(alpha = 0.45f)
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(color = color, fontWeight = FontWeight.SemiBold),
                maxLines = 1
            )
        }
    }
}

/**
 * Reusable Empty, Offline, Provider Unavailable, Permission Denied & Error Glass State Card.
 */
@Composable
fun GlassStateView(
    title: String,
    message: String,
    icon: ImageVector = Icons.Default.Explore,
    accentColor: Color? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val resolvedAccent = accentColor ?: glass.textSecondary
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        material = GlassMaterial.FROSTED,
        accentBorder = resolvedAccent.copy(alpha = 0.35f),
        contentPadding = PaddingValues(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlassSurface(
                modifier = Modifier.size(50.dp),
                role = GlassSurfaceRole.CHIP,
                material = GlassMaterial.CLEAR,
                shape = CircleShape,
                accentBorder = resolvedAccent.copy(alpha = 0.45f),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = resolvedAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(14.dp))
                GlassButton(text = actionLabel, onClick = onAction, isPrimary = true, material = GlassMaterial.PEARL)
            }
        }
    }
}
