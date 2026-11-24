package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.QrCodeGenerator
import com.example.data.model.ApprovalRequest
import com.example.data.model.ApprovalStatus
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassBookingCard
import com.example.ui.components.GlassBottomSheet
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.GlassTabBar
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel

data class UnifiedBookingUi(
    val referenceCode: String,
    val title: String,
    val providerSource: String,
    val providerPnr: String?,
    val amountUsd: Double,
    val status: String,
    val timestamp: String,
    val sha256Hash: String,
    val remoteDocId: String? = null
)

@Composable
fun BookingsHubScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val ledger by viewModel.ledgerTransactions.collectAsState()
    val remoteBookings by viewModel.remoteBookings.collectAsState()
    val cachedBookings by viewModel.cachedBookings.collectAsState()
    val approvals by viewModel.approvalRequests.collectAsState()
    val cancelledRefs by viewModel.cancelledRefs.collectAsState()

    var tabIndex by remember { mutableIntStateOf(0) }
    var selectedBooking by remember { mutableStateOf<UnifiedBookingUi?>(null) }

    val pendingApprovals = approvals.count { it.status == ApprovalStatus.PENDING }

    val combined = remember(ledger, remoteBookings, cachedBookings, cancelledRefs) {
        val map = LinkedHashMap<String, UnifiedBookingUi>()
        remoteBookings.forEach { doc ->
            val st = if (doc.referenceCode in cancelledRefs) "CANCELLED" else doc.status
            map[doc.referenceCode] = UnifiedBookingUi(
                referenceCode = doc.referenceCode,
                title = doc.title,
                providerSource = doc.providerSource,
                providerPnr = doc.providerPnr,
                amountUsd = doc.totalAmountUsd,
                status = st,
                timestamp = "Cloud Vault Synced",
                sha256Hash = doc.sha256AuditHash,
                remoteDocId = doc.id
            )
        }
        cachedBookings.forEach { rec ->
            if (!map.containsKey(rec.referenceCode)) {
                val st = if (rec.referenceCode in cancelledRefs) "CANCELLED" else rec.status
                map[rec.referenceCode] = UnifiedBookingUi(
                    referenceCode = rec.referenceCode,
                    title = rec.title,
                    providerSource = rec.providerSource,
                    providerPnr = rec.providerPnr,
                    amountUsd = rec.totalAmountUsd,
                    status = st,
                    timestamp = "Room Offline Cache",
                    sha256Hash = rec.sha256AuditHash
                )
            }
        }
        ledger.forEach { tx ->
            if (!map.containsKey(tx.referenceCode)) {
                val st = if (tx.referenceCode in cancelledRefs) "CANCELLED" else tx.status
                map[tx.referenceCode] = UnifiedBookingUi(
                    referenceCode = tx.referenceCode,
                    title = tx.title,
                    providerSource = "ALLORA Shield Verified",
                    providerPnr = tx.providerPnr,
                    amountUsd = tx.amountUsd,
                    status = st,
                    timestamp = tx.timestamp,
                    sha256Hash = tx.sha256Hash
                )
            }
        }
        map.values.toList()
    }

    val filtered = remember(combined, tabIndex) {
        when (tabIndex) {
            1 -> combined.filter {
                it.status.contains("HELD", true) ||
                    it.status.contains("REQUESTED", true) ||
                    it.status.contains("USER_APPROVED", true) ||
                    it.status.contains("NOT_BOOKABLE", true) ||
                    it.status.contains("CONFIRMED", true)
            }
            2 -> combined.filter { it.status.contains("COMPLETED", true) }
            3 -> combined.filter { it.status.contains("CANCELLED", true) }
            else -> combined
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bookings & Vault", style = MaterialTheme.typography.headlineLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Boarding passes, Room cache & two-phase Shield", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
                GlassChip(
                    label = "Audit Hub",
                    selected = false,
                    leadingIcon = Icons.Default.VerifiedUser,
                    onClick = { onNavigateSub(SubDestination.AUDIT_HUB) }
                )
            }
        }

        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                material = GlassMaterial.FROSTED,
                accentBorder = if (pendingApprovals > 0) glass.statusWarning.copy(alpha = 0.45f) else glass.statusSuccess.copy(alpha = 0.35f),
                onClick = { onNavigateSub(SubDestination.APPROVALS) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            role = GlassSurfaceRole.CHIP,
                            material = GlassMaterial.CLEAR,
                            shape = CircleShape,
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = "Approval Shield", tint = glass.textPrimary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Glass Shield Approvals", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                            Text(
                                text = if (pendingApprovals > 0) "$pendingApprovals hold(s) waiting for verification" else "All staged holds resolved",
                                style = MaterialTheme.typography.bodySmall.copy(color = if (pendingApprovals > 0) glass.statusWarning else glass.statusSuccess)
                            )
                        }
                    }
                    StatusGlassPill(label = if (pendingApprovals > 0) "$pendingApprovals Open" else "Open", color = glass.textSecondary)
                }
            }
        }

        item {
            GlassTabBar(
                tabs = listOf("All", "Upcoming", "Completed", "Cancelled"),
                selectedIndex = tabIndex,
                onSelectTab = { tabIndex = it }
            )
        }

        if (filtered.isEmpty()) {
            item {
                GlassStateView(
                    title = "No reservations in this filter",
                    message = "Stage a flight, hotel, dining, or car hold and approve it inside Glass Shield to issue a voucher.",
                    actionLabel = "Search Flights",
                    onAction = { onNavigateSub(SubDestination.FLIGHTS) }
                )
            }
        } else {
            items(filtered, key = { it.referenceCode }) { booking ->
                GlassBookingCard(
                    referenceCode = booking.referenceCode,
                    title = booking.title,
                    providerSource = booking.providerSource,
                    providerPnr = booking.providerPnr,
                    amountUsd = booking.amountUsd,
                    status = booking.status,
                    timestamp = booking.timestamp,
                    onClick = { selectedBooking = booking }
                )
            }
        }
    }

    selectedBooking?.let { booking ->
        val hasGenuineRef = booking.referenceCode.isNotBlank() &&
            !booking.status.contains("CANCELLED", true) &&
            !booking.status.contains("NOT_BOOKABLE", true)
        GlassBottomSheet(onDismissRequest = { selectedBooking = null }) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusGlassPill(
                    label = booking.status,
                    color = when {
                        booking.status == "CANCELLED" -> glass.statusError
                        booking.status == "CONFIRMED" -> glass.statusSuccess
                        booking.status == "NOT_BOOKABLE" -> glass.textSecondary
                        else -> glass.statusWarning
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(booking.title, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                Text(
                    text = "Ref: ${booking.referenceCode} • ${booking.providerPnr?.let { "PNR: $it" } ?: "Provider PNR: Not Issued"}",
                    style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary)
                )
                Spacer(modifier = Modifier.height(14.dp))

                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Provider", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                            Text(booking.providerSource, style = MaterialTheme.typography.bodySmall.copy(color = glass.textPrimary))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Hold Amount", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                            Text("$${booking.amountUsd.toInt()}", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 16.sp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                if (hasGenuineRef) {
                    val qrContent = "ALLORA|REF:${booking.referenceCode}|STATUS:${booking.status}|SHA256:${booking.sha256Hash.take(16)}"
                    val qrMatrix = remember(qrContent) {
                        runCatching { QrCodeGenerator.encode(qrContent, preferredSize = 128) }.getOrNull()
                    }
                    Text(
                        text = if (booking.status == "CONFIRMED") "Verified Pass QR" else "Hold Verification QR • Awaiting Provider",
                        style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (qrMatrix != null) {
                        Box(
                            modifier = Modifier
                                .size(164.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFFFAFAFC))
                                .border(1.dp, glass.glassBorder, RoundedCornerShape(22.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            RealZxingQrCanvas(matrix = qrMatrix)
                        }
                    }
                } else {
                    GlassCard(
                        material = GlassMaterial.CLEAR,
                        accentBorder = glass.statusWarning.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (booking.status == "CANCELLED") {
                                    "QR Pass revoked — booking has been cancelled."
                                } else {
                                    "QR Pass not issued — external travel partner credentials are required before provider ticketing."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "SHA-256: ${booking.sha256Hash.take(28)}...",
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted, fontFamily = FontFamily.Monospace)
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (booking.status != "CANCELLED") {
                    GlassButton(
                        text = "Cancel Hold / Booking",
                        onClick = {
                            viewModel.cancelConfirmedBooking(booking.referenceCode, booking.remoteDocId)
                            selectedBooking = null
                        },
                        isPrimary = false,
                        material = GlassMaterial.CLEAR,
                        accentColor = glass.statusError,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun RealZxingQrCanvas(matrix: com.example.core.QrCodeMatrix) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val count = matrix.size
        val cell = size.minDimension / count
        val offsetX = (size.width - cell * count) / 2f
        val offsetY = (size.height - cell * count) / 2f
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = Color(0xFF111318),
                        topLeft = Offset(offsetX + x * cell, offsetY + y * cell),
                        size = Size(cell + 0.35f, cell + 0.35f)
                    )
                }
            }
        }
    }
}

@Composable
fun ApprovalCenterScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val approvals by viewModel.approvalRequests.collectAsState()
    val profile by viewModel.userProfile.collectAsState()
    var thresholdConfirmReq by remember { mutableStateOf<ApprovalRequest?>(null) }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = "Approval Shield",
                subtitle = "Zero Autonomous Spend • Two-Phase Verification",
                onBack = onBack
            )
        }

        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp,
                material = GlassMaterial.FROSTED
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = glass.glassBorder,
                                radius = size.minDimension * 0.44f,
                                style = Stroke(width = 1.6.dp.toPx())
                            )
                        }
                        Icon(Icons.Default.Shield, contentDescription = null, tint = glass.textPrimary, modifier = Modifier.size(26.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Protected Authorization Gate", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                        Text(
                            text = "Biometric threshold: $${profile.biometricThresholdUsd.toInt()} USD. Amounts above require explicit review.",
                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                        )
                    }
                }
            }
        }

        items(approvals, key = { it.id }) { req ->
            val exceedsLimit = req.amountUsd > profile.biometricThresholdUsd
            val statusColor = when (req.status) {
                ApprovalStatus.PENDING -> glass.statusWarning
                ApprovalStatus.APPROVED -> glass.statusSuccess
                ApprovalStatus.REJECTED -> glass.statusError
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                material = if (req.status == ApprovalStatus.PENDING) GlassMaterial.FROSTED else GlassMaterial.CLEAR,
                accentBorder = statusColor.copy(alpha = 0.40f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusGlassPill(label = "${req.status.name} • ${req.riskLevel}", color = statusColor)
                    Text("$${req.amountUsd.toInt()}", style = TabularNumberStyle.copy(fontSize = 20.sp, color = glass.textPrimary))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(req.title, style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Text(req.merchantOrProvider, style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(6.dp))
                Text(req.details, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Policy: ${req.providerPolicy}", style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted))
                if (exceedsLimit && req.status == ApprovalStatus.PENDING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Exceeds $${profile.biometricThresholdUsd.toInt()} pre-auth limit • Expires in ${req.expiresInMinutes}m",
                            style = MaterialTheme.typography.labelSmall.copy(color = glass.statusWarning)
                        )
                    }
                }
                if (req.status == ApprovalStatus.PENDING) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            text = "Decline",
                            onClick = { viewModel.rejectRequest(req.id) },
                            isPrimary = false,
                            material = GlassMaterial.CLEAR,
                            leadingIcon = Icons.Default.Close,
                            modifier = Modifier.weight(1f)
                        )
                        GlassButton(
                            text = "Approve Hold",
                            onClick = {
                                if (exceedsLimit) {
                                    thresholdConfirmReq = req
                                } else {
                                    viewModel.approveRequest(req.id)
                                }
                            },
                            isPrimary = true,
                            material = GlassMaterial.PEARL,
                            leadingIcon = Icons.Default.Check,
                            testTag = "approve_button_${req.id}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    thresholdConfirmReq?.let { req ->
        GlassModal(onDismissRequest = { thresholdConfirmReq = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = glass.statusWarning)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirm Above-Threshold Hold", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${req.title} ($${req.amountUsd.toInt()}) exceeds your $${profile.biometricThresholdUsd.toInt()} Shield limit. This stages a hold record only — no live payment or provider ticket is issued without external partner APIs.",
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(
                    text = "Cancel",
                    onClick = { thresholdConfirmReq = null },
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "Confirm Hold",
                    onClick = {
                        viewModel.approveRequest(req.id)
                        thresholdConfirmReq = null
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun Phase0AuditHubScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val items by viewModel.auditItems.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }

    BackHandler(onBack = onBack)

    val filtered = remember(items, query, filter) {
        items.filter { repo ->
            val matchesCat = when (filter) {
                "Travel" -> repo.category == "Travel Priority"
                "Live API" -> repo.isRealApi
                "Mock/Demo" -> !repo.isRealApi
                else -> true
            }
            val matchesQuery = query.isBlank() ||
                repo.name.contains(query, true) ||
                repo.primaryStack.contains(query, true) ||
                "#${repo.number}".contains(query, true)
            matchesCat && matchesQuery
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeaderBar(title = "Phase 0 Audit Hub", subtitle = "${items.size} Repositories Audited • Read-Only Report", onBack = onBack)
        }
        item {
            GlassSearchBar(
                query = query,
                onQueryChange = { query = it },
                material = GlassMaterial.CLEAR,
                placeholder = "Search repo #9, #80, React, Node.js..."
            )
        }
        item {
            AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                listOf("All", "Travel", "Live API", "Mock/Demo").forEach { tag ->
                    GlassChip(label = tag, selected = filter == tag, onClick = { filter = tag })
                }
            }
        }
        items(filtered, key = { it.number }) { repo ->
            val sevColor = when (repo.securitySeverity) {
                "CRITICAL" -> glass.statusError
                "HIGH" -> glass.statusWarning
                else -> glass.textSecondary
            }
            GlassCard(modifier = Modifier.fillMaxWidth(), accentBorder = sevColor.copy(alpha = 0.35f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusGlassPill(label = "#${repo.number} • ${repo.category}", color = glass.textSecondary)
                    StatusGlassPill(label = repo.securitySeverity, color = sevColor)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(repo.name, style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                Text(repo.primaryStack, style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(6.dp))
                Text("Booking/API Realism: ${repo.bookingRealism}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Security: ${repo.securityFindings}", style = MaterialTheme.typography.bodySmall.copy(color = sevColor))
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Recommendation: ${repo.recommendation}", style = MaterialTheme.typography.labelSmall.copy(color = glass.statusSuccess, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
