package com.example.live.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.live.LiveSessionController
import com.example.live.model.toOrbState
import com.example.live.service.LiveAssistantService
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.LiveFloatingGlassPanel
import com.example.ui.theme.AlloraTheme
import com.example.ui.theme.GlassMaterial
import kotlin.math.roundToInt

private class OverlayLifecycleOwner :
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun performCreate() {
        savedStateController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun performDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

class FloatingAssistantService : Service() {

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastExpandedFlag: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        attachOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            LiveSessionController.getInstance(applicationContext).setFloatingOverlayActive(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (composeView == null) {
            attachOverlayWindow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        detachOverlayWindow()
        LiveSessionController.getInstance(applicationContext).setFloatingOverlayActive(false)
    }

    private fun attachOverlayWindow() {
        val wm = windowManager ?: return
        val controller = LiveSessionController.getInstance(applicationContext)
        controller.setFloatingOverlayActive(true)

        val owner = OverlayLifecycleOwner().apply { performCreate() }
        lifecycleOwner = owner

        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - (84 * density)).roundToInt().coerceAtLeast(16)
            y = (screenH * 0.22f).roundToInt()
        }
        layoutParams = params

        val openInAppLive = {
            val openIntent = Intent(this@FloatingAssistantService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(LiveAssistantService.EXTRA_OPEN_ALLORA_LIVE, true)
            }
            startActivity(openIntent)
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FloatingOverlayContent(
                    controller = controller,
                    onDragDelta = { dx, dy -> updateOverlayPosition(dx, dy) },
                    onExpandedChanged = { expanded -> updateFocusableForExpanded(expanded) },
                    onExpandToChat = {
                        openInAppLive()
                        controller.setFloatingExpanded(false)
                    },
                    onOpenScreenShareConsent = { openInAppLive() },
                    onRequestMicPermission = { openInAppLive() },
                    onStopSession = {
                        controller.endLiveSession()
                        stopSelf()
                    }
                )
            }
        }
        composeView = view
        runCatching { wm.addView(view, params) }.onFailure {
            detachOverlayWindow()
            stopSelf()
        }
    }

    private fun updateOverlayPosition(dx: Float, dy: Float) {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val bottomNavReserve = (72 * density).roundToInt()
        val statusBarReserve = (40 * density).roundToInt()
        val viewW = view.width.coerceAtLeast((68 * density).roundToInt())
        val viewH = view.height.coerceAtLeast((68 * density).roundToInt())
        val maxX = (metrics.widthPixels - viewW - 12).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - viewH - bottomNavReserve).coerceAtLeast(statusBarReserve)
        params.x = (params.x + dx.roundToInt()).coerceIn(8, maxX)
        params.y = (params.y + dy.roundToInt()).coerceIn(statusBarReserve, maxY)
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun updateFocusableForExpanded(expanded: Boolean) {
        if (lastExpandedFlag == expanded) return
        lastExpandedFlag = expanded
        val wm = windowManager ?: return
        val view = composeView ?: return
        val params = layoutParams ?: return

        params.flags = if (expanded) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        val metrics = resources.displayMetrics
        val density = metrics.density
        val maxW = if (expanded) (356 * density).roundToInt() else (76 * density).roundToInt()
        val maxH = if (expanded) (512 * density).roundToInt() else (76 * density).roundToInt()
        val bottomNavReserve = (72 * density).roundToInt()
        params.x = params.x.coerceIn(8, (metrics.widthPixels - maxW - 8).coerceAtLeast(8))
        params.y = params.y.coerceIn((40 * density).roundToInt(), (metrics.heightPixels - maxH - bottomNavReserve).coerceAtLeast(40))
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun detachOverlayWindow() {
        val wm = windowManager
        val view = composeView
        composeView = null
        if (wm != null && view != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        lifecycleOwner?.performDestroy()
        lifecycleOwner = null
        layoutParams = null
    }

    companion object {
        fun startOverlay(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            context.startService(Intent(context, FloatingAssistantService::class.java))
            return true
        }

        fun stopOverlay(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingAssistantService::class.java)) }
        }
    }
}

@Composable
private fun FloatingOverlayContent(
    controller: LiveSessionController,
    onDragDelta: (Float, Float) -> Unit,
    onExpandedChanged: (Boolean) -> Unit,
    onExpandToChat: () -> Unit,
    onOpenScreenShareConsent: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onStopSession: () -> Unit
) {
    val state by controller.uiState.collectAsState()
    LaunchedEffect(state.isFloatingExpanded) {
        onExpandedChanged(state.isFloatingExpanded)
    }

    AlloraTheme(darkTheme = state.isDarkTheme) {
        if (!state.isFloatingExpanded) {
            GlassSurface(
                modifier = Modifier
                    .padding(4.dp)
                    .size(64.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    },
                role = GlassSurfaceRole.CARD,
                material = GlassMaterial.FROSTED,
                shape = RoundedCornerShape(20.dp),
                enableBackdropBlur = false,
                contentAlignment = Alignment.Center
            ) {
                AlloraAiOrb(
                    state = state.status.toOrbState(),
                    size = 46.dp,
                    onClick = { controller.setFloatingExpanded(true) }
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .widthIn(min = 300.dp, max = 352.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    }
            ) {
                LiveFloatingGlassPanel(
                    state = state,
                    compactMode = true,
                    onMicToggle = { controller.toggleListeningOrSpeak() },
                    onInterrupt = { controller.interruptAssistant() },
                    onPauseResume = { controller.togglePauseSession() },
                    onStopSession = onStopSession,
                    onMinimize = { controller.setFloatingExpanded(false) },
                    onExpandToChat = onExpandToChat,
                    onToggleScreenShare = {
                        if (state.screenShare.isAuthorized) {
                            controller.togglePauseScreenShare()
                        } else {
                            onOpenScreenShareConsent()
                        }
                    },
                    onApproveAction = { controller.approveAction(it) },
                    onCancelAction = { controller.cancelAction(it) },
                    onSubmitText = { controller.submitUserUtterance(it) },
                    onRetry = { controller.retryLastTurn() },
                    onAudioTranscribeToggle = { controller.toggleAudioCaptureTranscribe() },
                    onSelectLanguage = { controller.setLanguage(it) },
                    onRequestMicPermission = onRequestMicPermission
                )
            }
        }
    }
}
