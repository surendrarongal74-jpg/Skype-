package com.example.live.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PermissionKind(
    val title: String,
    val whyNeeded: String,
    val whatAlloraAccesses: String,
    val androidPermission: String? = null,
    val isSpecialSystemFlow: Boolean = false,
    val isOptional: Boolean = false
) {
    MICROPHONE(
        title = "Microphone",
        whyNeeded = "Required for voice conversations, speech transcription, and optional \"Hey ALLORA\" wake-word detection.",
        whatAlloraAccesses = "Live microphone audio while an active voice session or opted-in wake-word listener is running. Raw audio is never saved by default.",
        androidPermission = Manifest.permission.RECORD_AUDIO
    ),
    NOTIFICATIONS(
        title = "Notifications",
        whyNeeded = "Required on Android 13+ to display the mandatory active microphone and screen-sharing status controls.",
        whatAlloraAccesses = "Posts a persistent notification with Pause and End Session controls while ALLORA LIVE foreground services are active.",
        androidPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    ),
    OVERLAY(
        title = "Display Over Other Apps",
        whyNeeded = "Allows the compact frosted-glass ALLORA floating orb and panel to remain accessible while you use other apps.",
        whatAlloraAccesses = "Draws the movable ALLORA floating bubble only while a floating session is active. Never blocks critical system controls.",
        isSpecialSystemFlow = true
    ),
    SCREEN_CAPTURE(
        title = "Screen Sharing (MediaProjection)",
        whyNeeded = "Lets ALLORA analyze visible content on your screen when you ask questions like \"Explain this page\" or \"Where should I tap?\".",
        whatAlloraAccesses = "Captures only on-demand screen frames during an authorized session. Protected/FLAG_SECURE windows are excluded and frames are not saved.",
        isSpecialSystemFlow = true
    ),
    LOCATION(
        title = "Location",
        whyNeeded = "Provides accurate nearby places, walking/driving routes, and local travel context.",
        whatAlloraAccesses = "Approximate or precise device coordinates only while using maps or location-aware travel queries.",
        androidPermission = Manifest.permission.ACCESS_FINE_LOCATION,
        isOptional = true
    ),
    CAMERA(
        title = "Camera",
        whyNeeded = "Used only when you open the Field Camera to capture travel landmarks.",
        whatAlloraAccesses = "Camera viewfinder only inside the Camera screen.",
        androidPermission = Manifest.permission.CAMERA,
        isOptional = true
    ),
    CALENDAR(
        title = "Calendar",
        whyNeeded = "ALLORA creates calendar events safely via the system Calendar sheet (zero-permission intent). Direct read access is optional.",
        whatAlloraAccesses = "Only launches the pre-filled Calendar event editor after you tap Approve.",
        androidPermission = Manifest.permission.READ_CALENDAR,
        isOptional = true
    ),
    ACCESSIBILITY(
        title = "Accessibility (System Info)",
        whyNeeded = "Evaluated strictly for explicit user-directed assistance under Android & Google Play policies. ALLORA uses safe Android Intents by default.",
        whatAlloraAccesses = "ALLORA does not register a hidden or unrestricted AccessibilityService. Cross-app actions use transparent Android Intents and Sharesheet.",
        isSpecialSystemFlow = true,
        isOptional = true
    )
}

data class PermissionItemState(
    val kind: PermissionKind,
    val isGranted: Boolean,
    val statusText: String
)

class PermissionManager private constructor(private val appContext: Context) {

    private val _permissions = MutableStateFlow<List<PermissionItemState>>(emptyList())
    val permissions: StateFlow<List<PermissionItemState>> = _permissions.asStateFlow()

    @Volatile
    private var mediaProjectionAuthorized: Boolean = false

    init {
        refresh()
    }

    fun setMediaProjectionAuthorized(authorized: Boolean) {
        mediaProjectionAuthorized = authorized
        refresh()
    }

    fun isGranted(kind: PermissionKind): Boolean {
        return when (kind) {
            PermissionKind.MICROPHONE -> isRuntimeGranted(Manifest.permission.RECORD_AUDIO)
            PermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isRuntimeGranted(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }
            PermissionKind.OVERLAY -> Settings.canDrawOverlays(appContext)
            PermissionKind.SCREEN_CAPTURE -> mediaProjectionAuthorized
            PermissionKind.LOCATION -> isRuntimeGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isRuntimeGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            PermissionKind.CAMERA -> isRuntimeGranted(Manifest.permission.CAMERA)
            PermissionKind.CALENDAR -> isRuntimeGranted(Manifest.permission.READ_CALENDAR)
            PermissionKind.ACCESSIBILITY -> hasAccessibilityServicesEnabled()
        }
    }

    fun refresh(): List<PermissionItemState> {
        val items = PermissionKind.entries.map { kind ->
            val granted = isGranted(kind)
            val statusText = when {
                kind == PermissionKind.SCREEN_CAPTURE && granted -> "Active Session Authorized"
                kind == PermissionKind.SCREEN_CAPTURE -> "Per-Session Consent Required"
                kind == PermissionKind.CALENDAR && !granted -> "Zero-Permission Intent Active"
                kind == PermissionKind.ACCESSIBILITY && !granted -> "Disabled (Intent-Only Safe Mode)"
                granted -> "Enabled"
                else -> "Not Enabled"
            }
            PermissionItemState(
                kind = kind,
                isGranted = granted,
                statusText = statusText
            )
        }
        _permissions.value = items
        return items
    }

    fun isAndroidPermissionGranted(permission: String): Boolean {
        return isRuntimeGranted(permission)
    }

    private fun isRuntimeGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAccessibilityServicesEnabled(): Boolean {
        val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabled = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrDefault(emptyList())
        return enabled.any { it.resolveInfo?.serviceInfo?.packageName == appContext.packageName }
    }

    fun createOverlayPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createNotificationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            createAppDetailsIntent()
        }
    }

    fun createAppDetailsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        @Volatile
        private var INSTANCE: PermissionManager? = null

        fun getInstance(context: Context): PermissionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PermissionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
