package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.local.AppearanceMode
import com.example.data.local.AppearancePreferencesStore
import com.example.data.model.ApprovalRequest
import com.example.data.model.ApprovalStatus
import com.example.data.model.LedgerTransaction
import com.example.data.model.WeatherReport
import com.example.data.remote.UserProfileDoc
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.AlloraAtmosphericBackground
import com.example.ui.components.AlloraGlassNavigationRail
import com.example.ui.components.AlloraOrbSpringDriver
import com.example.ui.components.AlloraTab
import com.example.ui.components.AtmosphereMood
import com.example.ui.components.FloatingNavigation
import com.example.ui.components.GlassBottomSheet
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassLiveBackdropScope
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.OrbState
import com.example.ui.components.rememberAlloraOrbSpringDriver
import com.example.ui.components.toMotionProfile
import com.example.ui.screens.AppearanceSelectorSection
import com.example.ui.screens.HomeScreenContent
import com.example.ui.screens.UnauthenticatedFlowScreen
import com.example.ui.theme.AlloraTheme
import com.example.ui.theme.ClearGlassDark
import com.example.ui.theme.ClearGlassLight
import com.example.ui.theme.DarkCanvasBase
import com.example.ui.theme.DarkTextPrimary
import com.example.ui.theme.DarkTextSecondary
import com.example.ui.theme.DeepGlassDark
import com.example.ui.theme.DeepGlassLight
import com.example.ui.theme.FrostedGlassDark
import com.example.ui.theme.FrostedGlassLight
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LightCanvasBase
import com.example.ui.theme.LightTextPrimary
import com.example.ui.theme.LightTextSecondary
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.DestinationSpotlight
import com.example.ui.viewmodel.TripWizardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlloraGlassUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleProfile() = UserProfileDoc(
        id = "user-1",
        userId = "user-1",
        displayName = "Martin Butler",
        temperatureUnit = "METRIC",
        homeAirportIata = "SFO"
    )

    private fun sampleWizard() = TripWizardState(
        destination = "Tokyo, Japan",
        startDate = "Dec 12, 2026",
        endDate = "Dec 17, 2026",
        travellersCount = 2,
        style = "Luxury & Culture",
        budgetUsd = 4200.0,
        generatedTitle = "Winter Tokyo Glass & Culinary Circuit"
    )

    private fun sampleDestinations() = listOf(
        DestinationSpotlight(
            id = "dest-1",
            city = "Tokyo",
            country = "Japan",
            tagline = "Neon skyline, Shibuya & winter culinary trails",
            highlights = "Ginza • Meiji",
            rating = 4.9,
            paletteSeed = 0,
            category = "City",
            estimatedFlightUsd = 740.0,
            estimatedHotelNightUsd = 310.0
        ),
        DestinationSpotlight(
            id = "dest-2",
            city = "Reykjavik",
            country = "Iceland",
            tagline = "Glacier lagoons & midnight northern lights",
            highlights = "Blue Lagoon",
            rating = 4.8,
            paletteSeed = 1,
            category = "Adventure",
            estimatedFlightUsd = 600.0,
            estimatedHotelNightUsd = 260.0
        )
    )

    private fun sampleWeather() = mapOf(
        "Tokyo" to WeatherReport("Tokyo", 14.0, "Clear", 48, 9.0, 2),
        "Reykjavik" to WeatherReport("Reykjavik", 3.0, "Aurora", 62, 16.0, 1)
    )

    private fun sampleApprovals() = listOf(
        ApprovalRequest(
            id = "apr-1",
            title = "ANA Business Hold — SFO to HND",
            category = "Flight",
            merchantOrProvider = "All Nippon Airways",
            amountUsd = 1480.0,
            riskLevel = "Low Risk",
            status = ApprovalStatus.PENDING,
            details = "Refundable 24h fare hold",
            providerPolicy = "24h Free Cancellation",
            expiresInMinutes = 120
        )
    )

    private fun sampleLedger() = listOf(
        LedgerTransaction(
            id = "ldg-1",
            referenceCode = "ALR-9042",
            title = "Park Hyatt Tokyo — 5 Nights",
            category = "Hotel",
            amountUsd = 1850.0,
            providerPnr = "PHT-88412",
            timestamp = "Dec 12, 2026",
            status = "CONFIRMED",
            sha256Hash = "8f4e2a"
        )
    )

    private fun renderHomeViewport(
        widthDp: Dp,
        heightDp: Dp = 800.dp,
        darkTheme: Boolean = false,
        browserActive: Boolean = false
    ) {
        composeRule.setContent {
            AlloraTheme(darkTheme = darkTheme) {
                Box(
                    modifier = Modifier
                        .width(widthDp)
                        .height(heightDp)
                        .testTag("viewport_root")
                ) {
                    AlloraAtmosphericBackground(mood = AtmosphereMood.HOME) {
                        val liveActivity = com.example.services.BrowserLiveActivityState(
                            active = browserActive,
                            currentUrl = if (browserActive) "https://flights.example.com" else "",
                            pageTitle = if (browserActive) "Searching for best travel plans…" else "",
                            progressPercent = if (browserActive) 55 else 0
                        )
                        if (widthDp >= 720.dp) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                AlloraGlassNavigationRail(
                                    selectedTab = AlloraTab.HOME,
                                    pendingApprovalsCount = 1,
                                    orbState = OrbState.IDLE,
                                    onSelectTab = {},
                                    onOrbClick = {},
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .testTag("tablet_nav_rail")
                                )
                                HomeScreenContent(
                                    profile = sampleProfile(),
                                    orbState = OrbState.IDLE,
                                    reducedMotion = true,
                                    recentSearches = listOf("Haneda fast-track lounge", "Kyoto ryokan"),
                                    approvals = sampleApprovals(),
                                    ledger = sampleLedger(),
                                    unreadNotifs = 2,
                                    savedCount = 3,
                                    weatherMap = sampleWeather(),
                                    wizard = sampleWizard(),
                                    destinations = sampleDestinations(),
                                    isDestinationSaved = { it == "Tokyo" },
                                    onToggleDestinationSave = {},
                                    onNavigateSub = {},
                                    onOpenAssistantWithPrompt = {},
                                    browserLiveActivity = liveActivity,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            GlassLiveBackdropScope(
                                modifier = Modifier.fillMaxSize(),
                                overlayContent = {
                                    FloatingNavigation(
                                        selectedTab = AlloraTab.HOME,
                                        pendingApprovalsCount = 1,
                                        orbState = OrbState.IDLE,
                                        reducedMotion = true,
                                        onSelectTab = {},
                                        onOrbClick = {},
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                    )
                                }
                            ) {
                                HomeScreenContent(
                                    profile = sampleProfile(),
                                    orbState = OrbState.IDLE,
                                    reducedMotion = true,
                                    recentSearches = listOf("Haneda fast-track lounge", "Kyoto ryokan"),
                                    approvals = sampleApprovals(),
                                    ledger = sampleLedger(),
                                    unreadNotifs = 2,
                                    savedCount = 3,
                                    weatherMap = sampleWeather(),
                                    wizard = sampleWizard(),
                                    destinations = sampleDestinations(),
                                    isDestinationSaved = { it == "Tokyo" },
                                    onToggleDestinationSave = {},
                                    onNavigateSub = {},
                                    onOpenAssistantWithPrompt = {},
                                    browserLiveActivity = liveActivity
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertHomeResponsiveLayout(isTablet: Boolean, expectActiveComputerCard: Boolean = false) {
        val rootBounds = composeRule.onNodeWithTag("viewport_root").getBoundsInRoot()
        val topBarBounds = composeRule.onNodeWithTag("home_top_bar").getBoundsInRoot()
        val switcherBounds = composeRule.onNodeWithTag("home_profile_switcher").getBoundsInRoot()
        val notifBounds = composeRule.onNodeWithTag("home_notifications_button").getBoundsInRoot()

        assertTrue("Top bar exceeds viewport width", topBarBounds.right <= rootBounds.right + 1.dp)
        assertTrue("Profile switcher overlaps notifications button", switcherBounds.right <= notifBounds.left + 0.5.dp)

        composeRule.onNodeWithTag("home_hero_ai_orb").assertIsDisplayed()
        val orbBounds = composeRule.onNodeWithTag("home_hero_ai_orb").getBoundsInRoot()
        assertTrue("Hero orb exceeds viewport width", orbBounds.right <= rootBounds.right + 1.dp)

        composeRule.onNodeWithTag("home_scroll_list")
            .performScrollToNode(hasTestTag("home_search_bar"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_search_bar").assertIsDisplayed()
        val searchBounds = composeRule.onNodeWithTag("home_search_bar").getBoundsInRoot()
        assertTrue("Search bar exceeds viewport width", searchBounds.right <= rootBounds.right + 1.dp)

        if (isTablet) {
            composeRule.onNodeWithTag("tablet_nav_rail").assertIsDisplayed()
        } else {
            val navBounds = composeRule.onNodeWithTag("allora_floating_navigation").getBoundsInRoot()
            val orbPillBounds = composeRule.onNodeWithTag("floating_assistant_orb_pill").getBoundsInRoot()
            assertTrue("Floating nav capsule overflows right edge", navBounds.right <= rootBounds.right)
            assertTrue("Floating orb pill overflows right edge", orbPillBounds.right <= rootBounds.right)
            assertTrue("Floating nav overlaps orb pill", navBounds.right <= orbPillBounds.left + 0.5.dp)

            val homeTab = composeRule.onNodeWithTag("nav_tab_home").getBoundsInRoot()
            val walletTab = composeRule.onNodeWithTag("nav_tab_wallet").getBoundsInRoot()
            val mailTab = composeRule.onNodeWithTag("nav_tab_mail").getBoundsInRoot()
            val settingsTab = composeRule.onNodeWithTag("nav_tab_settings").getBoundsInRoot()
            assertTrue("Home tab overlaps Wallet tab", homeTab.right <= walletTab.left + 0.5.dp)
            assertTrue("Wallet tab overlaps Mail tab", walletTab.right <= mailTab.left + 0.5.dp)
            assertTrue("Mail tab overlaps Settings tab", mailTab.right <= settingsTab.left + 0.5.dp)
        }

        if (expectActiveComputerCard) {
            composeRule.onNodeWithTag("home_scroll_list")
                .performScrollToNode(hasTestTag("home_active_task_card"))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("home_active_task_card").assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("home_active_task_card").assertDoesNotExist()
        }

        // Starter prompt pills are displayed on unified chat-first home
        composeRule.onNodeWithTag("home_scroll_list")
            .performScrollToNode(hasTestTag("home_shortcuts_row"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Plan a trip").assertIsDisplayed()
        composeRule.onNodeWithText("Check my email").assertIsDisplayed()
    }

    @Test
    fun `home layout renders cleanly without overlap at 320dp ultra compact width`() {
        renderHomeViewport(widthDp = 320.dp, heightDp = 640.dp)
        assertHomeResponsiveLayout(isTablet = false)
    }

    @Test
    fun `home layout renders cleanly without overlap at 360dp compact phone width`() {
        renderHomeViewport(widthDp = 360.dp, heightDp = 760.dp)
        assertHomeResponsiveLayout(isTablet = false)
    }

    @Test
    fun `home layout renders cleanly without overlap at 412dp standard phone width`() {
        renderHomeViewport(widthDp = 412.dp, heightDp = 892.dp)
        assertHomeResponsiveLayout(isTablet = false)
    }

    @Test
    fun `home layout switches cleanly to rail at 840dp tablet width`() {
        renderHomeViewport(widthDp = 840.dp, heightDp = 1080.dp)
        assertHomeResponsiveLayout(isTablet = true)
    }

    @Test
    fun `home shows embedded computer card only when browser or task is active`() {
        renderHomeViewport(widthDp = 412.dp, heightDp = 892.dp, browserActive = true)
        assertHomeResponsiveLayout(isTablet = false, expectActiveComputerCard = true)
    }

    @Test
    fun `onboarding renders ALLORA intro and skips to Google Sign In screen`() {
        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                UnauthenticatedFlowScreen(
                    initialShowOnboarding = true,
                    onAuthenticated = {}
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_next_button").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_skip_button").performClick()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
    }

    @Test
    fun `floating navigation switches 4 tabs and triggers AI orb click`() {
        var selected = AlloraTab.HOME
        var orbTapped = false

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                FloatingNavigation(
                    selectedTab = selected,
                    pendingApprovalsCount = 2,
                    orbState = OrbState.IDLE,
                    reducedMotion = true,
                    onSelectTab = { selected = it },
                    onOrbClick = { orbTapped = true }
                )
            }
        }

        composeRule.onNodeWithTag("nav_tab_wallet").performClick()
        assertEquals(AlloraTab.WALLET, selected)

        composeRule.onNodeWithTag("nav_tab_mail").performClick()
        assertEquals(AlloraTab.MAIL, selected)

        composeRule.onNodeWithTag("floating_assistant_orb_pill").performClick()
        assertTrue(orbTapped)
    }

    @Test
    fun `allora theme provides dark and light glass color palettes`() {
        var darkFlag = false
        var lightFlag = true

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                darkFlag = LocalAlloraGlassColors.current.isDark
            }
            AlloraTheme(darkTheme = false) {
                lightFlag = LocalAlloraGlassColors.current.isDark
            }
        }

        assertTrue(darkFlag)
        assertFalse(lightFlag)
    }

    @Test
    fun `glass material tiers render clear card and pearl primary cta`() {
        var clicked = false
        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                GlassCard(material = GlassMaterial.CLEAR) {
                    GlassButton(
                        text = "Book Now",
                        onClick = { clicked = true },
                        isPrimary = true,
                        material = GlassMaterial.PEARL,
                        testTag = "pearl_cta_test"
                    )
                }
            }
        }

        composeRule.onNodeWithTag("pearl_cta_test").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `orb spring driver transitions fluidly across idle listening and processing states`() {
        val idleProfile = OrbState.IDLE.toMotionProfile()
        val listeningProfile = OrbState.LISTENING.toMotionProfile()
        val processingProfile = OrbState.PROCESSING.toMotionProfile()
        assertTrue(processingProfile.spinDegreesPerSec > listeningProfile.spinDegreesPerSec)
        assertTrue(listeningProfile.spinDegreesPerSec > idleProfile.spinDegreesPerSec)
        assertTrue(listeningProfile.ringExpansion > processingProfile.ringExpansion)

        composeRule.mainClock.autoAdvance = false
        var activeState by mutableStateOf(OrbState.IDLE)
        var capturedDriver: AlloraOrbSpringDriver? = null

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                capturedDriver = rememberAlloraOrbSpringDriver(
                    state = activeState,
                    reducedMotion = false,
                    isPressed = false
                )
            }
        }

        val driver = capturedDriver!!
        repeat(15) { composeRule.mainClock.advanceTimeByFrame() }
        val idleVelocity = driver.spinVelocityAnim.value
        assertEquals(idleProfile.spinDegreesPerSec, idleVelocity, 2f)

        // Transition IDLE -> LISTENING
        activeState = OrbState.LISTENING
        composeRule.runOnIdle { }
        repeat(35) { composeRule.mainClock.advanceTimeByFrame() }
        val listeningVelocity = driver.spinVelocityAnim.value
        assertTrue("Expected spin velocity to accelerate smoothly toward LISTENING ($listeningVelocity)", listeningVelocity > 50f)
        assertTrue("Expected acoustic ring expansion during LISTENING", driver.ringExpansionAnim.value > 0.65f)

        // Transition LISTENING -> PROCESSING
        activeState = OrbState.PROCESSING
        composeRule.runOnIdle { }
        repeat(45) { composeRule.mainClock.advanceTimeByFrame() }
        val processingVelocity = driver.spinVelocityAnim.value
        assertTrue("Expected high orbital velocity during PROCESSING ($processingVelocity)", processingVelocity > 130f)

        // Transition PROCESSING -> IDLE smoothly decelerates
        activeState = OrbState.IDLE
        composeRule.runOnIdle { }
        repeat(60) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue("Expected velocity to decelerate cleanly toward IDLE", driver.spinVelocityAnim.value < processingVelocity)
    }

    @Test
    fun `allora ai orb composable steps smoothly across idle listening processing and reduced motion`() {
        composeRule.mainClock.autoAdvance = false
        var orbState by mutableStateOf(OrbState.IDLE)
        var reducedMotion by mutableStateOf(false)

        composeRule.setContent {
            AlloraTheme(darkTheme = true) {
                AlloraAiOrb(
                    state = orbState,
                    size = 148.dp,
                    reducedMotion = reducedMotion,
                    modifier = Modifier.testTag("spring_orb_test")
                )
            }
        }

        composeRule.onNodeWithTag("spring_orb_test").assertIsDisplayed()
        repeat(10) { composeRule.mainClock.advanceTimeByFrame() }

        orbState = OrbState.LISTENING
        repeat(10) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onNodeWithTag("spring_orb_test").assertIsDisplayed()

        orbState = OrbState.PROCESSING
        repeat(10) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onNodeWithTag("spring_orb_test").assertIsDisplayed()

        reducedMotion = true
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("spring_orb_test").assertIsDisplayed()
    }

    @Test
    fun `appearance mode resolves light dark and system defaults accurately`() {
        assertFalse(AppearanceMode.LIGHT.resolveDarkTheme(isSystemDark = true))
        assertFalse(AppearanceMode.LIGHT.resolveDarkTheme(isSystemDark = false))
        assertTrue(AppearanceMode.DARK.resolveDarkTheme(isSystemDark = false))
        assertTrue(AppearanceMode.DARK.resolveDarkTheme(isSystemDark = true))
        assertFalse(AppearanceMode.SYSTEM.resolveDarkTheme(isSystemDark = false))
        assertTrue(AppearanceMode.SYSTEM.resolveDarkTheme(isSystemDark = true))

        assertEquals(AppearanceMode.LIGHT, AppearanceMode.fromStorageKey(null))
        assertEquals(AppearanceMode.LIGHT, AppearanceMode.fromStorageKey("light"))
        assertEquals(AppearanceMode.DARK, AppearanceMode.fromStorageKey("DARK"))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStorageKey("system"))
    }

    @Test
    fun `appearance preferences store persists selected mode with datastore`() = runBlocking {
        val tempFile = File.createTempFile("allora_prefs_${UUID.randomUUID()}", ".preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tempFile }
            )
            val store = AppearancePreferencesStore(dataStore)
            assertEquals(AppearanceMode.LIGHT, store.appearanceModeFlow.first())

            store.setAppearanceMode(AppearanceMode.DARK)
            assertEquals(AppearanceMode.DARK, store.appearanceModeFlow.first())

            store.setAppearanceMode(AppearanceMode.SYSTEM)
            assertEquals(AppearanceMode.SYSTEM, store.appearanceModeFlow.first())

            store.setAppearanceMode(AppearanceMode.LIGHT)
            assertEquals(AppearanceMode.LIGHT, store.appearanceModeFlow.first())
        } finally {
            scope.cancel()
            tempFile.delete()
        }
    }

    @Test
    fun `allora theme switches immediately between crystal light tokens and obsidian dark tokens`() {
        var modeState by mutableStateOf(AppearanceMode.LIGHT)
        var capturedDark = true
        var capturedCanvas = Color.Transparent
        var capturedPrimaryText = Color.Transparent
        var capturedSecondaryText = Color.Transparent

        composeRule.setContent {
            AlloraTheme(darkTheme = modeState.resolveDarkTheme(isSystemDark = true)) {
                val glass = LocalAlloraGlassColors.current
                capturedDark = glass.isDark
                capturedCanvas = glass.canvasBase
                capturedPrimaryText = glass.textPrimary
                capturedSecondaryText = glass.textSecondary
            }
        }

        composeRule.waitForIdle()
        assertFalse(capturedDark)
        assertEquals(LightCanvasBase, capturedCanvas)
        assertEquals(LightTextPrimary, capturedPrimaryText)
        assertEquals(LightTextSecondary, capturedSecondaryText)

        modeState = AppearanceMode.DARK
        composeRule.waitForIdle()
        assertTrue(capturedDark)
        assertEquals(DarkCanvasBase, capturedCanvas)
        assertEquals(DarkTextPrimary, capturedPrimaryText)
        assertEquals(DarkTextSecondary, capturedSecondaryText)
    }

    @Test
    fun `appearance selector updates mode immediately without restarting`() {
        var modeState by mutableStateOf(AppearanceMode.SYSTEM)
        var reducedMotion by mutableStateOf(false)

        composeRule.setContent {
            AlloraTheme(darkTheme = modeState.resolveDarkTheme(isSystemDark = true)) {
                AlloraAtmosphericBackground(mood = AtmosphereMood.HOME) {
                    AppearanceSelectorSection(
                        selectedMode = modeState,
                        onSelectMode = { modeState = it },
                        reducedMotion = reducedMotion,
                        onReducedMotionChange = { reducedMotion = it }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("appearance_selector_card").assertIsDisplayed()
        composeRule.onNodeWithTag("appearance_mode_system").assertIsSelected()

        composeRule.onNodeWithTag("appearance_mode_light").performClick()
        composeRule.waitForIdle()
        assertEquals(AppearanceMode.LIGHT, modeState)
        composeRule.onNodeWithTag("appearance_mode_light").assertIsSelected()

        composeRule.onNodeWithTag("appearance_mode_dark").performClick()
        composeRule.waitForIdle()
        assertEquals(AppearanceMode.DARK, modeState)
        composeRule.onNodeWithTag("appearance_mode_dark").assertIsSelected()
    }

    @Test
    fun `home and components render cleanly in crystal light mode`() {
        renderHomeViewport(widthDp = 412.dp, heightDp = 892.dp, darkTheme = false)
        assertHomeResponsiveLayout(isTablet = false)
    }

    @Test
    fun `home and components render cleanly in obsidian dark mode`() {
        renderHomeViewport(widthDp = 412.dp, heightDp = 892.dp, darkTheme = true)
        assertHomeResponsiveLayout(isTablet = false)
    }

    @Test
    fun `glass surfaces use genuinely translucent alphas and neutral canvases in light and dark modes`() {
        // Light mode canvas is soft neutral cool mist (not pure white) and cards/overlays are translucent
        assertTrue(LightCanvasBase != Color.White)
        assertTrue(ClearGlassLight.alpha < 0.35f)
        assertTrue(FrostedGlassLight.alpha < 0.45f)
        assertTrue(DeepGlassLight.alpha < 0.65f)

        // Dark mode canvas is rich charcoal slate (not pure pitch-black) and cards/overlays are translucent
        assertTrue(DarkCanvasBase != Color.Black)
        assertTrue(ClearGlassDark.alpha < 0.20f)
        assertTrue(FrostedGlassDark.alpha < 0.25f)
        assertTrue(DeepGlassDark.alpha < 0.60f)
    }

    @Test
    fun `all glass surface roles render cleanly over atmospheric backdrop in light and dark mode`() {
        var darkTheme by mutableStateOf(false)
        composeRule.setContent {
            AlloraTheme(darkTheme = darkTheme) {
                AlloraAtmosphericBackground(mood = AtmosphereMood.HOME) {
                    GlassLiveBackdropScope(
                        modifier = Modifier.fillMaxSize(),
                        overlayContent = {
                            GlassSurface(
                                role = GlassSurfaceRole.NAV,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .testTag("role_nav_surface")
                            ) {
                                Text("Floating Nav")
                            }
                        }
                    ) {
                        GlassCard(modifier = Modifier.testTag("role_card_surface")) {
                            GlassChip(
                                label = "Filter Chip",
                                selected = false,
                                onClick = {},
                                modifier = Modifier.testTag("role_chip_surface")
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("role_card_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("role_chip_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("role_nav_surface").assertIsDisplayed()

        darkTheme = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("role_card_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("role_nav_surface").assertIsDisplayed()
    }

    @Test
    fun `onboarding glass search bar and modal render cleanly in crystal light mode`() {
        var searchQuery by mutableStateOf("")
        var showModal by mutableStateOf(true)

        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                AlloraAtmosphericBackground(mood = AtmosphereMood.AUTH) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GlassSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "Search Tokyo...",
                            modifier = Modifier.testTag("light_search_bar")
                        )
                        if (showModal) {
                            GlassModal(onDismissRequest = { showModal = false }) {
                                Text("Crystal Glass Modal")
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("light_search_bar").assertIsDisplayed()
        composeRule.onNodeWithText("Crystal Glass Modal").assertIsDisplayed()
    }

    @Test
    fun `onboarding flow navigates cleanly across 3 beginner friendly steps to Start chat`() {
        composeRule.setContent {
            AlloraTheme(darkTheme = false) {
                UnauthenticatedFlowScreen(
                    initialShowOnboarding = true,
                    onAuthenticated = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_next_button").assertIsDisplayed()

        // Step 0 -> Step 1 ("Tailor Your Companion")
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tailor Your Companion").assertIsDisplayed()
        composeRule.onNodeWithText("How should ALLORA help?").assertIsDisplayed()
        composeRule.onNodeWithText("Balanced & Friendly").assertIsDisplayed()
        composeRule.onNodeWithText("Calm Wellness").assertIsDisplayed()
        composeRule.onNodeWithText("Default Recommendation Tier").assertIsDisplayed()

        composeRule.onNodeWithText("Calm Wellness").performClick()
        composeRule.waitForIdle()

        // Step 1 -> Step 2 ("Always Your Choice" + "Start chat")
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Always Your Choice").assertIsDisplayed()
        composeRule.onNodeWithText("Start chat").assertIsDisplayed()

        // Tap Start chat -> Google Sign In
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
    }
}
