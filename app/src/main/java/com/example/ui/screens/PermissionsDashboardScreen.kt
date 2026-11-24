package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.live.LiveSessionController
import com.example.live.permissions.PermissionItemState
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.live.service.LiveAssistantService
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassModal
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors

@Composable
fun ContextualPermissionModal(
    kind: PermissionKind,
    onEnable: () -> Unit,
    onNotNow: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassModal(onDismissRequest = onNotNow) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = permissionIconFor(kind),
                contentDescription = null,
                tint = glass.textPrimary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = "Allow ${kind.title}?",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.testTag("permission_rationale_title")
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Why ALLORA asks:",
            style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary)
        )
        Text(
            text = kind.whyNeeded,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "What ALLORA accesses:",
            style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary)
        )
        Text(
            text = kind.whatAlloraAccesses,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                text = "Not now",
                onClick = onNotNow,
                isPrimary = false,
                material = GlassMaterial.CLEAR,
                testTag = "permission_not_now_btn",
                modifier = Modifier.weight(1f)
            )
            GlassButton(
                text = "Enable",
                onClick = onEnable,
                isPrimary = true,
                material = GlassMaterial.PEARL,
                testTag = "permission_enable_btn",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun PermissionsDashboardScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onStartScreenShareRequest: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val glass = LocalAlloraGlassColors.current
    val controller = remember(context) { LiveSessionController.getInstance(context) }
    val permManager = controller.permissionManager
    val permissions by permManager.permissions.collectAsState()
    var rationaleTarget by remember { mutableStateOf<PermissionKind?>(null) }

    LaunchedEffect(Unit) {
        permManager.refresh()
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permManager.refresh()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        permManager.refresh()
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            LiveAssistantService.attachMediaProjection(context, res.resultCode, data)
        }
        permManager.refresh()
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("permissions_dashboard_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("permissions_back_btn")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Permissions & Privacy",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "ALLORA LIVE transparent permission hub",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
            }
            GlassCircleIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = "Refresh permission status",
                onClick = { permManager.refresh() }
            )
        }

        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = glass.statusSuccess)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy-First Controls",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ALLORA requests permissions only when you activate a feature. Raw audio and screen frames stay in memory and are never saved to disk by default.",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
            }

            items(permissions, key = { it.kind.name }) { item ->
                PermissionRowCard(
                    item = item,
                    onConfigure = { rationaleTarget = item.kind },
                    onStopScreenShare = { controller.stopScreenShare() }
                )
            }
        }
    }

    rationaleTarget?.let { target ->
        ContextualPermissionModal(
            kind = target,
            onNotNow = { rationaleTarget = null },
            onEnable = {
                rationaleTarget = null
                when (target) {
                    PermissionKind.MICROPHONE -> runtimePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runtimePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        settingsLauncher.launch(permManager.createNotificationSettingsIntent())
                    }
                    PermissionKind.LOCATION -> runtimePermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    PermissionKind.CAMERA -> runtimePermLauncher.launch(Manifest.permission.CAMERA)
                    PermissionKind.CALENDAR -> runtimePermLauncher.launch(Manifest.permission.READ_CALENDAR)
                    PermissionKind.OVERLAY -> settingsLauncher.launch(permManager.createOverlayPermissionIntent())
                    PermissionKind.SCREEN_CAPTURE -> {
                        if (onStartScreenShareRequest != null) {
                            onStartScreenShareRequest()
                        } else {
                            controller.screenCaptureManager.createScreenCaptureIntent()?.let {
                                screenCaptureLauncher.launch(it)
                            }
                        }
                    }
                    PermissionKind.ACCESSIBILITY -> settingsLauncher.launch(permManager.createAccessibilitySettingsIntent())
                }
            }
        )
    }
}

@Composable
private fun PermissionRowCard(
    item: PermissionItemState,
    onConfigure: () -> Unit,
    onStopScreenShare: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    GlassCard(
        material = GlassMaterial.CLEAR,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_card_${item.kind.name}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = permissionIconFor(item.kind),
                    contentDescription = null,
                    tint = glass.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.kind.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = item.statusText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (item.isGranted) glass.statusSuccess else glass.textMuted
                        )
                    )
                }
            }
            StatusGlassPill(
                label = if (item.isGranted) "Active" else "Off",
                color = if (item.isGranted) glass.statusSuccess else glass.textMuted
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.kind.whyNeeded,
            style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (item.kind == PermissionKind.SCREEN_CAPTURE && item.isGranted) {
                GlassButton(
                    text = "Stop Screen Share",
                    onClick = onStopScreenShare,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    accentColor = glass.statusError,
                    modifier = Modifier.weight(1f)
                )
            } else {
                GlassButton(
                    text = if (item.isGranted) "Review in Settings" else "Configure",
                    onClick = onConfigure,
                    isPrimary = !item.isGranted,
                    material = if (item.isGranted) GlassMaterial.CLEAR else GlassMaterial.PEARL,
                    testTag = "permission_btn_${item.kind.name}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun permissionIconFor(kind: PermissionKind): ImageVector = when (kind) {
    PermissionKind.MICROPHONE -> Icons.Default.Mic
    PermissionKind.NOTIFICATIONS -> Icons.Default.Notifications
    PermissionKind.OVERLAY -> Icons.Default.Layers
    PermissionKind.SCREEN_CAPTURE -> Icons.Default.Cast
    PermissionKind.LOCATION -> Icons.Default.LocationOn
    PermissionKind.CAMERA -> Icons.Default.PhotoCamera
    PermissionKind.CALENDAR -> Icons.Default.CalendarMonth
    PermissionKind.ACCESSIBILITY -> Icons.Default.AccessibilityNew
}
