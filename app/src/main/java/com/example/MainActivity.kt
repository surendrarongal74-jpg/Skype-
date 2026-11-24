package com.example

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.core.configureAppCheck
import com.example.live.service.LiveAssistantService
import com.example.ui.screens.AlloraAppRoot
import com.example.ui.screens.SubDestination

class MainActivity : ComponentActivity() {
    private var requestedSubDestination by mutableStateOf<SubDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check immediately after super.onCreate BEFORE any Firebase/UI initialization
        configureAppCheck(this, intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )

        handleLiveDeepLink(intent)

        setContent {
            AlloraAppRoot(
                requestedSubDestination = requestedSubDestination,
                onConsumeRequestedSubDestination = { requestedSubDestination = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
        handleLiveDeepLink(intent)
    }

    private fun handleLiveDeepLink(intent: Intent?) {
        if (intent == null) return
        when {
            intent.getBooleanExtra(LiveAssistantService.EXTRA_OPEN_ALLORA_LIVE, false) -> {
                requestedSubDestination = SubDestination.LIVE_ASSISTANT
            }
            intent.getBooleanExtra(LiveAssistantService.EXTRA_OPEN_AI_CHAT, false) -> {
                requestedSubDestination = SubDestination.AI_ASSISTANT
            }
        }
    }
}
