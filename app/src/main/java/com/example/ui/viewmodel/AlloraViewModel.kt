package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.AutonomousTaskRecord
import com.example.agent.ExternalConnectorDescriptor
import com.example.agent.TaskExecutionEngine
import com.example.core.SecurityUtils
import com.example.data.ai.AlloraAiService
import com.example.data.ai.LandmarkIdentificationUiState
import com.example.data.ai.orchestrator.AiCapability
import com.example.data.ai.orchestrator.AiFailoverEvent
import com.example.data.ai.orchestrator.AiModelConfig
import com.example.data.ai.orchestrator.AiModelHealthSnapshot
import com.example.data.ai.orchestrator.AiProviderType
import com.example.data.ai.orchestrator.AiQuotaSnapshot
import com.example.data.ai.orchestrator.AiRequestDiagnostics
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiSelectionMode
import com.example.data.ai.orchestrator.AiStatusPhase
import com.example.data.ai.orchestrator.AiTaskProfile
import com.example.data.ai.orchestrator.AiTelemetrySink
import com.example.data.ai.orchestrator.AiUserPreferences
import com.example.data.ai.orchestrator.AlloraChatInterfaceState
import com.example.data.ai.orchestrator.ConversationSerializer
import com.example.data.ai.orchestrator.ManualModelUnavailableException
import com.example.data.local.AlloraChatLocalStore
import com.example.data.local.AlloraDatabase
import com.example.data.local.AppearanceMode
import com.example.data.local.AppearancePreferencesStore
import com.example.data.local.entity.BookingRecord
import com.example.data.local.entity.MemoryEntryEntity
import com.example.data.local.entity.PersonalityProfileEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.TravelPhotoRecord
import com.example.data.local.entity.UserPreference
import com.example.data.local.entity.WorkspaceFileEntity
import com.example.data.local.entity.WorkspaceNoteEntity
import com.example.live.actions.AlloraAppActionBridge
import com.example.ui.chat.AlloraChatThread
import com.example.ui.chat.AlloraReplyQuote
import com.example.ui.chat.AlloraRichMessage
import com.example.ui.chat.ChatAttachment
import com.example.ui.chat.ChatBlock
import com.example.ui.chat.ChatBlockJsonParser
import com.example.location.LocationTrackingState
import com.example.location.TravelLocationTracker
import com.example.data.model.ApprovalRequest
import com.example.data.model.ApprovalStatus
import com.example.data.model.DestinationSearchResult
import com.example.data.model.FlightOffer
import com.example.data.model.GeoCoordinate
import com.example.data.model.ItinerarySlot
import com.example.data.model.LedgerTransaction
import com.example.data.model.LodgingOffer
import com.example.data.model.PointOfInterest
import com.example.data.model.RepoAuditItem
import com.example.data.model.RouteResult
import com.example.data.model.ToolExecutionTrace
import com.example.data.model.WeatherLoadState
import com.example.data.model.WeatherReport
import com.example.data.remote.AiFailoverLogDoc
import com.example.data.remote.AiMessageDoc
import com.example.data.remote.AiPreferencesDoc
import com.example.data.remote.AlloraCloudRepository
import com.example.data.remote.BookingDoc
import com.example.data.remote.ItinerarySlotDoc
import com.example.data.remote.SavedItemDoc
import com.example.data.remote.TripPlanDoc
import com.example.data.remote.UserProfileDoc
import com.example.data.repository.AlloraRepository
import com.example.data.repository.GeospatialRepository
import com.example.data.repository.WeatherRepository
import com.example.services.AgentSkillId
import com.example.services.AgentSkillRegistry
import com.example.services.BrowserControlOwner
import com.example.services.BrowserDomAction
import com.example.services.BrowserDomCommand
import com.example.services.BrowserDomCommandResult
import com.example.services.BrowserEngineState
import com.example.services.BrowserInterventionKind
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserNavigationPhase
import com.example.services.BrowserSessionService
import com.example.services.MemoryCategory
import com.example.services.MemoryService
import com.example.services.PermissionService
import com.example.services.PersonalityPresetMode
import com.example.services.ProjectStatus
import com.example.services.SecurityAuditService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.example.ui.chat.BrowserCardAction
import com.example.ui.components.OrbState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DestinationSpotlight(
    val id: String,
    val city: String,
    val country: String,
    val tagline: String,
    val highlights: String,
    val rating: Double,
    val paletteSeed: Int,
    val category: String,
    val estimatedFlightUsd: Double,
    val estimatedHotelNightUsd: Double
)

data class CarRentalOffer(
    val id: String,
    val modelName: String,
    val vehicleClass: String,
    val seats: Int,
    val transmission: String,
    val dailyRateUsd: Double,
    val insurancePerDayUsd: Double,
    val pickupHub: String,
    val providerSource: String,
    val policySummary: String
)

data class AlloraNotificationItem(
    val id: String,
    val title: String,
    val message: String,
    val timestamp: String,
    val category: String,
    val isUnread: Boolean = true
)

data class AiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val timestamp: String,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val followUpChips: List<String> = emptyList(),
    val toolTraces: List<ToolExecutionTrace> = emptyList()
)

data class TripWizardState(
    val currentStep: Int = 1,
    val destination: String = "Tokyo, Japan",
    val startDate: String = "Dec 12, 2026",
    val endDate: String = "Dec 17, 2026",
    val travellersCount: Int = 2,
    val style: String = "Luxury & Culture",
    val budgetUsd: Double = 4200.0,
    val transport: String = "Business Flight + Rail",
    val lodgingType: String = "Boutique Luxury Hotel",
    val activities: List<String> = listOf("Fine Dining", "Temples & Art", "Skyline Views"),
    val generatedTitle: String = "",
    val generatedSummary: String = "",
    val isGenerating: Boolean = false,
    val generationError: String? = null
)

data class ItineraryMeta(
    val estimatedCostUsd: Double = 0.0,
    val note: String = ""
)

data class BudgetCategoryBreakdown(
    val label: String,
    val estimatedUsd: Double,
    val actualUsd: Double
)

enum class AiChatState {
    IDLE,
    SENDING,
    THINKING,
    STREAMING,
    COMPLETED,
    ERROR
}

class AlloraViewModel(
    private val localRepo: AlloraRepository,
    private val database: AlloraDatabase,
    private val firestoreRepo: AlloraCloudRepository,
    private val weatherRepo: WeatherRepository,
    private val geospatialRepo: GeospatialRepository,
    private val aiService: AlloraAiService,
    val currentUserId: String,
    initialDisplayName: String = "Companion",
    initialEmail: String = "",
    private val appearanceStore: AppearancePreferencesStore? = null,
    private val initialOnboardingTravelStyle: String? = null,
    private val initialOnboardingBudgetTier: String? = null,
    private val initialOnboardingHomeAirport: String? = null,
    private val locationTracker: TravelLocationTracker? = null,
    private val onStopTrackingService: (() -> Unit)? = null,
    private val onAuthInvalidated: ((String) -> Unit)? = null,
    private val onVerifySessionRequested: (() -> Unit)? = null,
    private val chatLocalStore: AlloraChatLocalStore? = null,
    private val attachmentBitmapLoader: ((String) -> Bitmap?)? = null,
    private val attachmentTextReader: ((String, String) -> String?)? = null,
    private val taskEngine: TaskExecutionEngine? = null,
    private val workspaceController: AlloraAgentWorkspaceController? = null,
    private val preloadedChatThreads: List<com.example.ui.chat.AlloraChatThread>? = null,
    private val preloadedActiveThreadId: String? = null,
    private val aiRequestManager: AiRequestManager = AiRequestManager.getInstance(),
    private val preloadedThreadsAlreadyRepaired: Boolean = false
) : ViewModel() {

    val auditItems: StateFlow<List<RepoAuditItem>> = localRepo.auditItems
    val flightOffers: StateFlow<List<FlightOffer>> = localRepo.flightOffers
    val lodgingOffers: StateFlow<List<LodgingOffer>> = localRepo.lodgingOffers
    val approvalRequests: StateFlow<List<ApprovalRequest>> = localRepo.approvalRequests
    val ledgerTransactions: StateFlow<List<LedgerTransaction>> = localRepo.ledgerTransactions

    // Weather state backed purely by Open-Meteo API responses (zero fake defaults)
    private val _weatherStates = MutableStateFlow<Map<String, WeatherLoadState>>(emptyMap())
    val weatherStates: StateFlow<Map<String, WeatherLoadState>> = _weatherStates.asStateFlow()

    val weatherReports: StateFlow<Map<String, WeatherReport>> = _weatherStates
        .map { map ->
            map.mapNotNull { (city, state) ->
                (state as? WeatherLoadState.Success)?.let { city to it.report }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    private val _activeWeatherCity = MutableStateFlow("Tokyo")
    val activeWeatherCity: StateFlow<String> = _activeWeatherCity.asStateFlow()

    // Geospatial & Map state backed by OpenStreetMap Nominatim, Overpass & OSRM
    private val _destinationSearchResults = MutableStateFlow<List<DestinationSearchResult>>(emptyList())
    val destinationSearchResults: StateFlow<List<DestinationSearchResult>> = _destinationSearchResults.asStateFlow()

    private val _poiList = MutableStateFlow<List<PointOfInterest>>(emptyList())
    val poiList: StateFlow<List<PointOfInterest>> = _poiList.asStateFlow()

    private val _mapCenter = MutableStateFlow(GeoCoordinate(latitude = 35.6762, longitude = 139.6503))
    val mapCenter: StateFlow<GeoCoordinate> = _mapCenter.asStateFlow()

    private val _userLocation = MutableStateFlow<GeoCoordinate?>(null)
    val userLocation: StateFlow<GeoCoordinate?> = _userLocation.asStateFlow()

    private val _activeRoute = MutableStateFlow<RouteResult?>(null)
    val activeRoute: StateFlow<RouteResult?> = _activeRoute.asStateFlow()

    private val _mapLoading = MutableStateFlow(false)
    val mapLoading: StateFlow<Boolean> = _mapLoading.asStateFlow()

    private val _mapError = MutableStateFlow<String?>(null)
    val mapError: StateFlow<String?> = _mapError.asStateFlow()

    private val _locationPermissionDenied = MutableStateFlow(false)
    val locationPermissionDenied: StateFlow<Boolean> = _locationPermissionDenied.asStateFlow()

    val locationTrackingState: StateFlow<LocationTrackingState> = locationTracker?.state
        ?: MutableStateFlow(LocationTrackingState()).asStateFlow()

    val travelPhotos: StateFlow<List<TravelPhotoRecord>> = database.travelPhotoDao()
        .observePhotosForUser(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _landmarkIdentificationState = MutableStateFlow<LandmarkIdentificationUiState>(
        LandmarkIdentificationUiState.Idle
    )
    val landmarkIdentificationState: StateFlow<LandmarkIdentificationUiState> =
        _landmarkIdentificationState.asStateFlow()

    private val _liveLandmarkScanEnabled = MutableStateFlow(true)
    val liveLandmarkScanEnabled: StateFlow<Boolean> = _liveLandmarkScanEnabled.asStateFlow()

    private var landmarkAnalysisJob: Job? = null

    // Trip-scoped Itinerary Slots persisted in Firestore
    private val _selectedTripId = MutableStateFlow<String?>(null)
    val selectedTripId: StateFlow<String?> = _selectedTripId.asStateFlow()

    private val _itinerarySlots = MutableStateFlow<List<ItinerarySlot>>(emptyList())
    val itinerarySlots: StateFlow<List<ItinerarySlot>> = _itinerarySlots.asStateFlow()

    private val _itineraryMeta = MutableStateFlow<Map<String, ItineraryMeta>>(emptyMap())
    val itineraryMeta: StateFlow<Map<String, ItineraryMeta>> = _itineraryMeta.asStateFlow()

    // User-scoped Room cached bookings (strictly filtered by currentUserId)
    val cachedBookings: StateFlow<List<BookingRecord>> = database.bookingRecordDao()
        .observeBookingsForUser(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Autonomous AI Task Execution Engine state flows (empty/null-safe when engine not injected)
    val autonomousTasks: StateFlow<List<AutonomousTaskRecord>> = taskEngine?.tasks
        ?: MutableStateFlow<List<AutonomousTaskRecord>>(emptyList()).asStateFlow()

    val browserLiveActivity: StateFlow<BrowserLiveActivityState> =
        workspaceController?.browserAutomationService?.liveActivity
            ?: MutableStateFlow(BrowserLiveActivityState()).asStateFlow()

    val pendingBrowserDomCommand: StateFlow<BrowserDomCommand?> =
        workspaceController?.pendingBrowserDomCommand
            ?: MutableStateFlow<BrowserDomCommand?>(null).asStateFlow()

    val selectedAutonomousTask: StateFlow<AutonomousTaskRecord?> = if (taskEngine != null) {
        combine(taskEngine.tasks, taskEngine.selectedTaskId) { list, selectedId ->
            if (selectedId != null) {
                list.firstOrNull { it.taskId == selectedId } ?: list.firstOrNull()
            } else {
                list.firstOrNull()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)
    } else {
        MutableStateFlow<AutonomousTaskRecord?>(null).asStateFlow()
    }

    val workspaceNotes: StateFlow<List<WorkspaceNoteEntity>> = (taskEngine?.workspaceNotes ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val workspaceFiles: StateFlow<List<WorkspaceFileEntity>> =
        workspaceController?.workspaceFiles ?: MutableStateFlow(emptyList<WorkspaceFileEntity>()).asStateFlow()

    val memories: StateFlow<List<MemoryEntryEntity>> =
        workspaceController?.memories ?: MutableStateFlow(emptyList<MemoryEntryEntity>()).asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> =
        workspaceController?.projects ?: MutableStateFlow(emptyList<ProjectEntity>()).asStateFlow()

    val personalityProfile: StateFlow<PersonalityProfileEntity> =
        workspaceController?.personalityProfile ?: MutableStateFlow(PersonalityProfileEntity(userId = currentUserId)).asStateFlow()

    val agentSkills: List<AgentSkillId> = AgentSkillRegistry.allSkills()

    val walletAccount: StateFlow<com.example.data.local.entity.WalletAccountEntity?> =
        workspaceController?.walletAccount ?: MutableStateFlow<com.example.data.local.entity.WalletAccountEntity?>(null).asStateFlow()

    val unreadNotificationsCount: StateFlow<Int> =
        workspaceController?.workspaceNotifications?.map { list -> list.count { it.isUnread } }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)
            ?: MutableStateFlow(0).asStateFlow()

    private val _remoteBookings = MutableStateFlow<List<BookingDoc>>(emptyList())
    val remoteBookings: StateFlow<List<BookingDoc>> = _remoteBookings.asStateFlow()

    private val _remoteTrips = MutableStateFlow<List<TripPlanDoc>>(emptyList())
    val remoteTrips: StateFlow<List<TripPlanDoc>> = _remoteTrips.asStateFlow()

    private val _savedItems = MutableStateFlow<List<SavedItemDoc>>(emptyList())
    val savedItems: StateFlow<List<SavedItemDoc>> = _savedItems.asStateFlow()

    private val _userProfile = MutableStateFlow(
        UserProfileDoc(
            userId = currentUserId,
            displayName = initialDisplayName.ifBlank { "Traveler" }
        )
    )
    val userProfile: StateFlow<UserProfileDoc> = _userProfile.asStateFlow()

    private val _userEmail = MutableStateFlow(initialEmail)
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _appearanceMode = MutableStateFlow(AppearanceMode.LIGHT)
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _darkGlassTheme = MutableStateFlow(false)
    val darkGlassTheme: StateFlow<Boolean> = _darkGlassTheme.asStateFlow()

    private val _reducedMotion = MutableStateFlow(false)
    val reducedMotion: StateFlow<Boolean> = _reducedMotion.asStateFlow()

    private val defaultStarterSearches = listOf(
        "Tokyo 4-day weather & sights",
        "Find dining near Ginza",
        "Paris weekend itinerary"
    )
    private val _recentSearches = MutableStateFlow(defaultStarterSearches)
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    private val _aiChatState = MutableStateFlow(AiChatState.IDLE)
    val aiChatState: StateFlow<AiChatState> = _aiChatState.asStateFlow()

    private val _chatInterfaceState = MutableStateFlow(AlloraChatInterfaceState.IDLE)
    val chatInterfaceState: StateFlow<AlloraChatInterfaceState> = _chatInterfaceState.asStateFlow()

    private val _lastChatErrorCategory = MutableStateFlow<AlloraChatInterfaceState?>(null)
    val lastChatErrorCategory: StateFlow<AlloraChatInterfaceState?> = _lastChatErrorCategory.asStateFlow()

    private val _aiStatusLabel = MutableStateFlow("Ready • Allora Gemini AI")
    val aiStatusLabel: StateFlow<String> = _aiStatusLabel.asStateFlow()

    val aiStatusPhase: StateFlow<AiStatusPhase> = aiRequestManager.activeStatusPhase
    val activeAiModelConfig: StateFlow<AiModelConfig?> = aiRequestManager.activeModelConfig
    val latestAiDiagnostics: StateFlow<AiRequestDiagnostics?> = aiRequestManager.latestDiagnostics
    val aiModelConfigs: StateFlow<List<AiModelConfig>> = aiRequestManager.models
    val aiModelHealthSnapshots: StateFlow<Map<String, AiModelHealthSnapshot>> = aiRequestManager.healthSnapshots
    val aiUserPreferences: StateFlow<AiUserPreferences> = aiRequestManager.userPreferences
    val recentFailoverEvents: StateFlow<List<AiFailoverEvent>> = aiRequestManager.recentFailoverEvents
    val aiQuotaSnapshot: StateFlow<AiQuotaSnapshot> = aiRequestManager.quotaSnapshot

    private val _manualModeBlockedNotice = MutableStateFlow<ManualModelUnavailableException?>(null)
    val manualModeBlockedNotice: StateFlow<ManualModelUnavailableException?> = _manualModeBlockedNotice.asStateFlow()

    private val defaultWelcomeMessage = AiChatMessage(
        id = "msg-welcome",
        isUser = false,
        text = "Hi! I'm ALLORA, your personal AI companion. Ask me a question, check your inbox, review your wallet, or let ALLORA Computer help browse a site.",
        timestamp = "Now",
        followUpChips = listOf(
            "Plan a trip",
            "Check my email",
            "My wallet balance"
        )
    )

    private val _chatMessages = MutableStateFlow(listOf(defaultWelcomeMessage))
    val chatMessages: StateFlow<List<AiChatMessage>> = _chatMessages.asStateFlow()

    private val defaultRichWelcome = AlloraRichMessage(
        id = "msg-welcome",
        isUser = false,
        plainText = "I'm here for you — chat, plan, browse, manage your emails, wallet, and tasks!",
        blocks = listOf(
            ChatBlock.Text(
                id = "blk_welcome_0",
                content = "I'm here for you — chat, plan, browse, manage your emails, wallet, and tasks!"
            )
        ),
        timestampLabel = "Now",
        followUpChips = listOf(
            "Plan a trip",
            "Check my email",
            "My wallet"
        )
    )

    private val defaultThreadId = "thread_main"
    private val _richThreads = MutableStateFlow(
        listOf(
            AlloraChatThread(
                id = defaultThreadId,
                title = "Allora",
                category = "Chat",
                messages = listOf(defaultRichWelcome)
            )
        )
    )
    val richThreads: StateFlow<List<AlloraChatThread>> = _richThreads.asStateFlow()

    private val _activeThreadId = MutableStateFlow(defaultThreadId)
    val activeThreadId: StateFlow<String> = _activeThreadId.asStateFlow()

    val activeThreadTitle: StateFlow<String> = kotlinx.coroutines.flow.combine(_richThreads, _activeThreadId) { list, id ->
        list.firstOrNull { it.id == id }?.title ?: "Allora"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Allora")

    val richMessages: StateFlow<List<AlloraRichMessage>> = kotlinx.coroutines.flow.combine(_richThreads, _activeThreadId) { list, id ->
        list.firstOrNull { it.id == id }?.messages ?: listOf(defaultRichWelcome)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(defaultRichWelcome))

    private val _chatTypingState = MutableStateFlow(false)
    val chatTypingState: StateFlow<Boolean> = _chatTypingState.asStateFlow()

    private val _chatLiveTaskStep = MutableStateFlow("")
    val chatLiveTaskStep: StateFlow<String> = _chatLiveTaskStep.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<AlloraRichMessage?>(null)
    val replyingToMessage: StateFlow<AlloraRichMessage?> = _replyingToMessage.asStateFlow()

    private val _wizardState = MutableStateFlow(TripWizardState())
    val wizardState: StateFlow<TripWizardState> = _wizardState.asStateFlow()

    private val _notifications = MutableStateFlow(
        listOf(
            AlloraNotificationItem(
                id = "notif-1",
                title = "Approval Shield Active",
                message = "Review staged holds in Shield before authorizing. Live ticketing requires external provider credentials.",
                timestamp = "Just now",
                category = "Approval"
            )
        )
    )
    val notifications: StateFlow<List<AlloraNotificationItem>> = _notifications.asStateFlow()

    private val _cancelledRefs = MutableStateFlow<Set<String>>(emptySet())
    val cancelledRefs: StateFlow<Set<String>> = _cancelledRefs.asStateFlow()

    private val _cloudSyncError = MutableStateFlow<String?>(null)
    val cloudSyncError: StateFlow<String?> = _cloudSyncError.asStateFlow()

    val destinations: List<DestinationSpotlight> = listOf(
        DestinationSpotlight(
            id = "dest-tokyo",
            city = "Tokyo",
            country = "Japan",
            tagline = "Serene temples, precision transit & sky-high culinary craft",
            highlights = "Otemachi • Ginza • Shibuya • Meiji Jingu",
            rating = 4.9,
            paletteSeed = 0,
            category = "Culture",
            estimatedFlightUsd = 720.0,
            estimatedHotelNightUsd = 620.0
        ),
        DestinationSpotlight(
            id = "dest-paris",
            city = "Paris",
            country = "France",
            tagline = "Architectural elegance, ateliers & Seine twilight salons",
            highlights = "Avenue Montaigne • Louvre • Le Marais",
            rating = 4.8,
            paletteSeed = 1,
            category = "Luxury",
            estimatedFlightUsd = 610.0,
            estimatedHotelNightUsd = 780.0
        ),
        DestinationSpotlight(
            id = "dest-singapore",
            city = "Singapore",
            country = "Singapore",
            tagline = "Biophilic skyline gardens, Marina waterfront & effortless hub",
            highlights = "Marina Bay • Gardens by the Bay • Sentosa",
            rating = 4.8,
            paletteSeed = 2,
            category = "Nature",
            estimatedFlightUsd = 840.0,
            estimatedHotelNightUsd = 450.0
        ),
        DestinationSpotlight(
            id = "dest-london",
            city = "London",
            country = "United Kingdom",
            tagline = "Historic townhouses, West End theatre & Mayfair galleries",
            highlights = "Mayfair • South Bank • Kensington",
            rating = 4.7,
            paletteSeed = 3,
            category = "City",
            estimatedFlightUsd = 580.0,
            estimatedHotelNightUsd = 510.0
        )
    )

    val carRentals: List<CarRentalOffer> = listOf(
        CarRentalOffer(
            id = "car-01",
            modelName = "Porsche Taycan Cross Turismo",
            vehicleClass = "Electric Grand Tourer",
            seats = 4,
            transmission = "Automatic • EV",
            dailyRateUsd = 215.0,
            insurancePerDayUsd = 35.0,
            pickupHub = "Tokyo Haneda VIP Terminal",
            providerSource = "Sample Catalog • Partner Credentials Required",
            policySummary = "Unlimited mileage • Sample hold only (fleet partner not connected)"
        ),
        CarRentalOffer(
            id = "car-02",
            modelName = "BMW i5 eDrive40 Touring",
            vehicleClass = "Executive Sedan",
            seats = 5,
            transmission = "Automatic • EV",
            dailyRateUsd = 148.0,
            insurancePerDayUsd = 26.0,
            pickupHub = "Tokyo Station / Marunouchi",
            providerSource = "Sample Catalog • Amadeus Car Credentials Required",
            policySummary = "Full-to-full energy policy • Collision waiver optional • Staged hold"
        ),
        CarRentalOffer(
            id = "car-03",
            modelName = "Range Rover Velar Autobiography",
            vehicleClass = "Luxury SUV",
            seats = 5,
            transmission = "Automatic • Hybrid",
            dailyRateUsd = 185.0,
            insurancePerDayUsd = 32.0,
            pickupHub = "Paris CDG Terminal 2E",
            providerSource = "Sample Catalog • Partner Credentials Required",
            policySummary = "Cross-border EU transit allowed • Staged rental hold"
        )
    )

    private data class FailedChatPrompt(
        val threadId: String,
        val prompt: String,
        val attachments: List<ChatAttachment>,
        val replyTo: AlloraReplyQuote?
    )

    private var activeAiJob: Job? = null
    private var activePromptSignature: String? = null
    private var activeRichPlaceholderId: String? = null
    private var activeRichThreadId: String? = null
    private val failedPromptsByAssistantId = mutableMapOf<String, FailedChatPrompt>()
    private val submissionLock = Any()
    private val seenSubmissionIds = LinkedHashSet<String>()
    private val recentSubmissionTimestampsMs = LinkedHashMap<String, Long>()
    private val _isSendingMessage = MutableStateFlow(false)
    val isSendingMessage: StateFlow<Boolean> = _isSendingMessage.asStateFlow()
    private val _isRetryingMessage = MutableStateFlow(false)
    val isRetryingMessage: StateFlow<Boolean> = _isRetryingMessage.asStateFlow()
    private var itineraryObserverJob: Job? = null
    private val firestoreObserverJobs = mutableListOf<Job>()
    private var hasLoadedServerProfile = false

    private var lastAutoPoiRefreshCoord: GeoCoordinate? = null

    init {
        loadPersistedRichThreads()
        observeAppearancePreferences()
        loadInitialRoomPreferences()
        observeLocationTracker()
        viewModelScope.launch {
            kotlinx.coroutines.yield()
            startFirestoreObservers()
            attachAiOrchestratorTelemetryAndSync()
            withContext(Dispatchers.IO) {
                taskEngine?.bindUser(currentUserId)
            }
        }
        viewModelScope.launch {
            delay(450L)
            loadWeatherForFeaturedCities()
            refreshNearbyPois(35.6762, 139.6503, "All")
        }
    }

    private fun attachAiOrchestratorTelemetryAndSync() {
        aiRequestManager.attachTelemetrySink(
            object : AiTelemetrySink {
                override fun onFailoverRecorded(userId: String, event: AiFailoverEvent, taskProfile: AiTaskProfile) {
                    if (currentUserId.isBlank()) return
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            firestoreRepo.logAiFailoverEvent(
                                AiFailoverLogDoc(
                                    userId = currentUserId,
                                    requestId = "fo_${event.timestampMs}",
                                    taskProfile = taskProfile.name,
                                    fromModelId = event.fromModelId,
                                    toModelId = event.toModelId,
                                    reasonCategory = event.reasonCategory.name,
                                    statusCode = (event.statusCode ?: 0).toLong(),
                                    detail = event.detail.take(300),
                                    delayMs = event.delayMs
                                )
                            )
                        }
                    }
                }

                override fun onRequestCompleted(userId: String, diagnostics: AiRequestDiagnostics) {
                    if (diagnostics.errorSummary == null) {
                        _aiStatusLabel.value = "Ready • ${diagnostics.selectedModelName}"
                    }
                }
            }
        )

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                firestoreRepo.fetchRemoteAiModelConfigs().getOrNull()?.takeIf { it.isNotEmpty() }?.let { docs ->
                    val mapped = docs.mapNotNull { doc ->
                        val id = doc.modelId.ifBlank { doc.id }.trim()
                        if (id.isEmpty()) return@mapNotNull null
                        val provider = runCatching { AiProviderType.valueOf(doc.providerType) }
                            .getOrDefault(AiProviderType.FIREBASE_AI)
                        val caps = doc.capabilitiesCsv.split(",")
                            .mapNotNull { raw -> runCatching { AiCapability.valueOf(raw.trim()) }.getOrNull() }
                            .toSet()
                            .ifEmpty { setOf(AiCapability.CHAT) }
                        AiModelConfig(
                            modelId = id,
                            displayName = doc.displayName.ifBlank { id },
                            providerType = provider,
                            providerModelName = doc.providerModelName.ifBlank { id },
                            tier = doc.tier.ifBlank { "Primary" },
                            priority = doc.priority.toInt(),
                            enabled = doc.enabled,
                            timeoutMs = doc.timeoutMs.coerceIn(5_000L, 120_000L),
                            contextWindowTokens = doc.contextWindowTokens.toInt().coerceAtLeast(4_096),
                            costPer1kInputUsd = doc.costPer1kInputUsd,
                            costPer1kOutputUsd = doc.costPer1kOutputUsd,
                            capabilities = caps,
                            speedRating = doc.speedRating.ifBlank { "Fast" }
                        )
                    }
                    aiRequestManager.applyRemoteModelConfigs(mapped)
                }
            }
            runCatching {
                firestoreRepo.loadAiUserPreferences(currentUserId).getOrNull()?.let { doc ->
                    val mode = runCatching { AiSelectionMode.valueOf(doc.selectionMode) }
                        .getOrDefault(AiSelectionMode.AUTOMATIC)
                    aiRequestManager.updateUserPreferences(
                        AiUserPreferences(
                            selectionMode = mode,
                            preferredModelId = doc.preferredModelId.ifBlank { "firebase_gemini_3_8_flash" },
                            manualModelId = doc.manualModelId.ifBlank { "firebase_gemini_3_8_flash" },
                            autoSwitchOnFailure = doc.autoSwitchOnFailure
                        )
                    )
                }
            }
        }
    }

    private fun observeLocationTracker() {
        val tracker = locationTracker ?: return
        viewModelScope.launch {
            tracker.state.collect { locState ->
                val coord = locState.currentLocation ?: return@collect
                _userLocation.value = coord
                _locationPermissionDenied.value = false
                if (locState.isTracking) {
                    val prev = lastAutoPoiRefreshCoord
                    val movedKm = if (prev == null) {
                        Double.MAX_VALUE
                    } else {
                        GeospatialRepository.haversineDistanceKm(
                            prev.latitude,
                            prev.longitude,
                            coord.latitude,
                            coord.longitude
                        )
                    }
                    if (movedKm >= 0.4) {
                        lastAutoPoiRefreshCoord = coord
                        _mapCenter.value = coord
                        refreshNearbyPois(coord.latitude, coord.longitude, "All")
                    }
                }
            }
        }
    }

    private fun observeAppearancePreferences() {
        val store = appearanceStore ?: return
        viewModelScope.launch {
            store.appearanceModeForUserFlow(currentUserId).collect { mode ->
                _appearanceMode.value = mode
                if (mode != AppearanceMode.SYSTEM) {
                    _darkGlassTheme.value = (mode == AppearanceMode.DARK)
                }
            }
        }
    }

    private fun loadInitialRoomPreferences() {
        viewModelScope.launch {
            database.userPreferenceDao().observePreferences(currentUserId).collect { pref ->
                if (pref != null) {
                    _userProfile.update { current ->
                        current.copy(
                            userId = currentUserId,
                            displayName = pref.displayName.ifBlank { current.displayName },
                            preferredCurrency = pref.preferredCurrency,
                            temperatureUnit = pref.temperatureUnit,
                            preferredCabinClass = pref.preferredCabinClass,
                            homeAirportIata = pref.homeAirportIata,
                            travelStyle = pref.travelStyle,
                            budgetTier = pref.budgetTier,
                            biometricThresholdUsd = pref.biometricThresholdUsd,
                            darkGlassTheme = pref.darkGlassTheme
                        )
                    }
                    if (_userEmail.value.isBlank() && pref.email.isNotBlank()) {
                        _userEmail.value = pref.email
                    }
                    val restoredSearches = deserializeRecentSearches(pref.recentSearchesSerialized)
                    if (restoredSearches.isNotEmpty()) {
                        _recentSearches.value = restoredSearches
                    }
                }
            }
        }
    }

    private fun startFirestoreObservers() {
        stopFirestoreObservers()
        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeUserProfileSnapshot(currentUserId)
                .catch { error -> handleStreamException("profile", error) }
                .collect { snapshot ->
                    val remote = snapshot.profile
                    if (remote != null) {
                        hasLoadedServerProfile = true
                        _userProfile.value = remote
                        withContext(Dispatchers.IO) {
                            val existingLocal = database.userPreferenceDao().getPreferences(currentUserId)
                            database.userPreferenceDao().upsertPreferences(
                                UserPreference(
                                    userId = currentUserId,
                                    displayName = remote.displayName,
                                    email = _userEmail.value.ifBlank { existingLocal?.email.orEmpty() },
                                    preferredCurrency = remote.preferredCurrency,
                                    temperatureUnit = remote.temperatureUnit,
                                    preferredCabinClass = remote.preferredCabinClass,
                                    homeAirportIata = remote.homeAirportIata,
                                    travelStyle = remote.travelStyle,
                                    budgetTier = remote.budgetTier,
                                    biometricThresholdUsd = remote.biometricThresholdUsd,
                                    darkGlassTheme = remote.darkGlassTheme,
                                    recentSearchesSerialized = existingLocal?.recentSearchesSerialized.orEmpty()
                                )
                            )
                        }
                        if (appearanceStore != null &&
                            !appearanceStore.hasUserAppearancePreference(currentUserId) &&
                            remote.darkGlassTheme
                        ) {
                            appearanceStore.setAppearanceModeForUser(currentUserId, AppearanceMode.DARK)
                        }
                    } else if (!snapshot.isFromCache && !hasLoadedServerProfile) {
                        hasLoadedServerProfile = true
                        val seedProfile = _userProfile.value.copy(
                            userId = currentUserId,
                            travelStyle = initialOnboardingTravelStyle?.takeIf { it.isNotBlank() }
                                ?: _userProfile.value.travelStyle,
                            budgetTier = initialOnboardingBudgetTier?.takeIf { it.isNotBlank() }
                                ?: _userProfile.value.budgetTier,
                            homeAirportIata = initialOnboardingHomeAirport?.takeIf { it.isNotBlank() }
                                ?: _userProfile.value.homeAirportIata
                        )
                        _userProfile.value = seedProfile
                        firestoreRepo.upsertUserProfile(seedProfile)
                    }
                }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeTrips(currentUserId)
                .catch { error -> handleStreamException("trips", error) }
                .collect { trips ->
                    _remoteTrips.value = trips
                    val currentSelected = _selectedTripId.value
                    if ((currentSelected == null || trips.none { it.id == currentSelected }) && trips.isNotEmpty()) {
                        selectTrip(trips.first().id)
                    }
                }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeBookings(currentUserId)
                .catch { error -> handleStreamException("bookings", error) }
                .collect { docs ->
                    _remoteBookings.value = docs
                    if (docs.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val records = docs.map { doc ->
                                val effectiveStatus = if (doc.referenceCode in _cancelledRefs.value) {
                                    "CANCELLED"
                                } else {
                                    doc.status
                                }
                                val createdMs = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                                BookingRecord(
                                    id = doc.id,
                                    userId = currentUserId,
                                    referenceCode = doc.referenceCode,
                                    bookingType = doc.bookingType,
                                    title = doc.title,
                                    providerSource = doc.providerSource,
                                    providerPnr = doc.providerPnr,
                                    originOrLocation = doc.originOrLocation,
                                    destination = doc.destination,
                                    startTimestamp = createdMs,
                                    endTimestamp = null,
                                    totalAmountUsd = doc.totalAmountUsd,
                                    status = effectiveStatus,
                                    sha256AuditHash = doc.sha256AuditHash,
                                    isSynced = true,
                                    lastSyncedAt = System.currentTimeMillis(),
                                    createdAt = createdMs
                                )
                            }
                            database.bookingRecordDao().upsertBookings(records)
                        }
                    }
                }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeSavedItems(currentUserId)
                .catch { error -> handleStreamException("saved items", error) }
                .collect { items -> _savedItems.value = items }
        }

        firestoreObserverJobs += viewModelScope.launch {
            firestoreRepo.observeAiMessages(currentUserId)
                .catch { error -> handleStreamException("ai messages", error) }
                .collect { docs ->
                    if (docs.isNotEmpty() && activeAiJob?.isActive != true) {
                        val mappedLegacy = docs.map { doc ->
                            val parsedTraces = doc.toolTraceSummary
                                ?.split(" | ")
                                ?.filter { it.isNotBlank() }
                                ?.map { entry ->
                                    ToolExecutionTrace(
                                        toolName = entry.substringBefore(":").trim(),
                                        summary = entry.substringAfter(":", "").trim(),
                                        succeeded = !entry.contains("[failed]")
                                    )
                                }
                                ?: emptyList()
                            val rawText = ConversationSerializer.sanitizeUserFacingErrorText(doc.text)
                            val isRoleErr = ConversationSerializer.isRoleCompatibilityErrorMessage(doc.text)
                            val cleanText = if (isRoleErr) {
                                "Previous AI turn encountered a compatibility error. Tap Retry to continue."
                            } else {
                                rawText
                            }
                            AiChatMessage(
                                id = doc.id,
                                isUser = doc.isUser,
                                text = cleanText,
                                timestamp = "Synced",
                                isError = doc.isError || isRoleErr,
                                toolTraces = parsedTraces
                            )
                        }
                        _chatMessages.value = mappedLegacy

                        // Also hydrate default rich thread on fresh device / reinstall when no local user messages exist
                        val mainThread = _richThreads.value.firstOrNull { it.id == defaultThreadId }
                        if (mainThread != null && mainThread.messages.none { it.isUser }) {
                            val syncedRich = docs
                                .filter { !it.isError && !ConversationSerializer.isRoleCompatibilityErrorMessage(it.text) && it.text.isNotBlank() }
                                .map { doc ->
                                    val cleanDocText = ConversationSerializer.sanitizeUserFacingErrorText(doc.text)
                                    val blocks = if (doc.isUser) {
                                        listOf(ChatBlock.Text(id = "${doc.id}_txt", content = cleanDocText))
                                    } else {
                                        ChatBlockJsonParser.splitTextIntoShortBubbles(cleanDocText)
                                    }
                                    AlloraRichMessage(
                                        id = doc.id,
                                        isUser = doc.isUser,
                                        plainText = cleanDocText,
                                        blocks = blocks,
                                        timestampLabel = "Synced",
                                        createdAtMs = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                                        isStreaming = false
                                    )
                                }
                            if (syncedRich.isNotEmpty()) {
                                _richThreads.update { list ->
                                    list.map { t ->
                                        if (t.id == defaultThreadId) {
                                            t.copy(
                                                messages = ConversationSerializer.repairRichMessages(
                                                    listOf(defaultRichWelcome) + syncedRich
                                                )
                                            )
                                        } else t
                                    }
                                }
                                persistRichThreadsNow()
                            }
                        }
                    }
                }
        }
    }

    private fun stopFirestoreObservers() {
        firestoreObserverJobs.forEach { it.cancel() }
        firestoreObserverJobs.clear()
        itineraryObserverJob?.cancel()
        itineraryObserverJob = null
    }

    private fun handleStreamException(scopeLabel: String, error: Throwable) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: error
        Log.w(TAG, "Error observing $scopeLabel", root)
        if (firestoreError?.code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
            onAuthInvalidated?.invoke("Your ALLORA session expired. Please sign in again.")
            return
        }
        if (firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            onVerifySessionRequested?.invoke()
        }
        _cloudSyncError.value = root.localizedMessage ?: "Cloud sync unavailable ($scopeLabel)"
    }

    fun dismissCloudSyncError() {
        _cloudSyncError.value = null
    }

    // ---- PART 2: Real Weather Methods ----

    fun loadWeatherForFeaturedCities(forceRefresh: Boolean = false) {
        destinations.forEach { dest ->
            loadWeatherForCity(dest.city, forceRefresh = forceRefresh)
        }
    }

    fun loadWeatherForCity(city: String, forceRefresh: Boolean = false) {
        val clean = city.trim().substringBefore(",").trim()
        if (clean.isBlank()) return
        _activeWeatherCity.value = clean
        _weatherStates.update { it + (clean to WeatherLoadState.Loading) }
        viewModelScope.launch {
            val result = weatherRepo.getWeatherForCity(clean, forceRefresh = forceRefresh)
            result.fold(
                onSuccess = { report ->
                    _weatherStates.update {
                        it + (clean to WeatherLoadState.Success(report)) + (report.city to WeatherLoadState.Success(report))
                    }
                },
                onFailure = { err ->
                    val message = err.localizedMessage ?: "Failed to load weather for $clean."
                    _weatherStates.update { it + (clean to WeatherLoadState.Error(message)) }
                }
            )
        }
    }

    // ---- PART 3 & 4: Real Geospatial & Map Methods ----

    fun searchMapDestinations(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        _mapLoading.value = true
        _mapError.value = null
        viewModelScope.launch {
            geospatialRepo.searchDestinations(clean, limit = 8).fold(
                onSuccess = { results ->
                    _destinationSearchResults.value = results
                    val first = results.firstOrNull()
                    if (first != null) {
                        _mapCenter.value = GeoCoordinate(first.latitude, first.longitude)
                        _activeRoute.value = null
                        refreshNearbyPois(first.latitude, first.longitude, "All")
                        loadWeatherForCity(first.name)
                    } else {
                        _mapError.value = "No matching geographic locations found for \"$clean\"."
                    }
                    _mapLoading.value = false
                },
                onFailure = { err ->
                    _mapError.value = err.localizedMessage ?: "Destination search failed."
                    _mapLoading.value = false
                }
            )
        }
    }

    fun selectDestinationOnMap(dest: DestinationSearchResult, category: String = "All") {
        _mapCenter.value = GeoCoordinate(dest.latitude, dest.longitude)
        _activeRoute.value = null
        refreshNearbyPois(dest.latitude, dest.longitude, category)
        loadWeatherForCity(dest.name)
    }

    fun refreshNearbyPois(
        latitude: Double = _mapCenter.value.latitude,
        longitude: Double = _mapCenter.value.longitude,
        category: String = "All"
    ) {
        _mapLoading.value = true
        _mapError.value = null
        viewModelScope.launch {
            geospatialRepo.searchNearbyPlaces(latitude, longitude, category).fold(
                onSuccess = { places ->
                    _poiList.value = places
                    _mapLoading.value = false
                },
                onFailure = { err ->
                    _mapError.value = err.localizedMessage ?: "Nearby places lookup failed."
                    _mapLoading.value = false
                }
            )
        }
    }

    fun computeRouteToPoi(poi: PointOfInterest, profile: String = "driving") {
        val origin = _userLocation.value ?: _mapCenter.value
        _mapLoading.value = true
        _mapError.value = null
        viewModelScope.launch {
            geospatialRepo.calculateRoute(
                originLat = origin.latitude,
                originLng = origin.longitude,
                destLat = poi.lat,
                destLng = poi.lng,
                profile = profile
            ).fold(
                onSuccess = { route ->
                    _activeRoute.value = route
                    _mapLoading.value = false
                },
                onFailure = { err ->
                    _mapError.value = err.localizedMessage ?: "Route calculation unavailable."
                    _mapLoading.value = false
                }
            )
        }
    }

    fun clearActiveRoute() {
        _activeRoute.value = null
    }

    fun updateUserLocation(latitude: Double, longitude: Double, category: String = "All") {
        val coord = GeoCoordinate(latitude, longitude)
        _userLocation.value = coord
        _mapCenter.value = coord
        _locationPermissionDenied.value = false
        lastAutoPoiRefreshCoord = coord
        refreshNearbyPois(latitude, longitude, category)
    }

    fun requestFreshLocationFix(category: String = "All") {
        val tracker = locationTracker ?: return
        tracker.fetchSingleFix { coord, _ ->
            if (coord != null) {
                updateUserLocation(coord.latitude, coord.longitude, category)
            } else {
                refreshNearbyPois(category = category)
            }
        }
    }

    fun onLocationPermissionDenied() {
        _locationPermissionDenied.value = true
        _mapError.value = "Location permission was denied. You can still search any destination manually."
    }

    fun clearMapError() {
        _mapError.value = null
    }

    // ---- Travel & Architectural Photography ----

    fun saveCapturedPhoto(
        filePath: String,
        caption: String,
        category: String,
        destinationName: String,
        latitude: Double? = _userLocation.value?.latitude,
        longitude: Double? = _userLocation.value?.longitude
    ) {
        val id = "photo_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val record = TravelPhotoRecord(
            id = id,
            userId = currentUserId,
            filePath = filePath,
            caption = caption.trim().ifBlank { "$category capture" },
            category = category,
            destinationName = destinationName.trim().ifBlank { _wizardState.value.destination },
            latitude = latitude,
            longitude = longitude,
            capturedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            database.travelPhotoDao().insertPhoto(record)
        }
        addNotification(
            title = "Architectural Photo Saved",
            message = "${record.caption} (${record.category}) added to your personal field log.",
            category = "Journal"
        )
    }

    fun deleteCapturedPhoto(photo: TravelPhotoRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            database.travelPhotoDao().deletePhotoForUser(currentUserId, photo.id)
            runCatching { java.io.File(photo.filePath).delete() }
        }
    }

    fun setLiveLandmarkScanEnabled(enabled: Boolean) {
        _liveLandmarkScanEnabled.value = enabled
        if (!enabled) {
            landmarkAnalysisJob?.cancel()
            val current = _landmarkIdentificationState.value
            if (current is LandmarkIdentificationUiState.Analyzing) {
                val prev = current.previousResult
                _landmarkIdentificationState.value = if (prev != null && prev.landmarkDetected) {
                    LandmarkIdentificationUiState.Identified(prev, System.currentTimeMillis())
                } else {
                    LandmarkIdentificationUiState.Idle
                }
            }
        }
    }

    fun dismissLandmarkIdentification() {
        landmarkAnalysisJob?.cancel()
        _landmarkIdentificationState.value = LandmarkIdentificationUiState.Idle
    }

    fun analyzeCameraFrameForLandmark(
        bitmap: Bitmap,
        destinationLabel: String,
        latitude: Double?,
        longitude: Double?,
        focusCategory: String,
        isManualScan: Boolean = false
    ) {
        if (!isManualScan && !_liveLandmarkScanEnabled.value) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        if (!isManualScan && landmarkAnalysisJob?.isActive == true) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }

        val previousResult = when (val cur = _landmarkIdentificationState.value) {
            is LandmarkIdentificationUiState.Identified -> cur.result
            is LandmarkIdentificationUiState.Analyzing -> cur.previousResult
            is LandmarkIdentificationUiState.Error -> cur.previousResult
            else -> null
        }

        landmarkAnalysisJob?.cancel()
        _landmarkIdentificationState.value = LandmarkIdentificationUiState.Analyzing(
            previousResult = previousResult,
            isManualScan = isManualScan
        )

        landmarkAnalysisJob = viewModelScope.launch {
            try {
                val res = aiService.identifyArchitecturalLandmark(
                    bitmap = bitmap,
                    destinationHint = destinationLabel,
                    latitude = latitude,
                    longitude = longitude,
                    focusCategory = focusCategory
                )
                res.fold(
                    onSuccess = { parsed ->
                        val now = System.currentTimeMillis()
                        _landmarkIdentificationState.value = if (parsed.landmarkDetected) {
                            LandmarkIdentificationUiState.Identified(parsed, now)
                        } else {
                            LandmarkIdentificationUiState.NoLandmarkVisible(
                                observation = parsed.summary.ifBlank {
                                    "Point camera at an architectural landmark or building facade."
                                },
                                analyzedAtMs = now
                            )
                        }
                    },
                    onFailure = { err ->
                        if (err is CancellationException) throw err
                        // Pause automatic live polling on transport/App Check error so user can inspect and tap Retry
                        _liveLandmarkScanEnabled.value = false
                        _landmarkIdentificationState.value = LandmarkIdentificationUiState.Error(
                            message = err.localizedMessage ?: "Landmark identification failed.",
                            previousResult = previousResult
                        )
                    }
                )
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    // ---- PART 5: Real AI Tool Orchestration & Persistent Messages ----

    fun sendAiPrompt(
        prompt: String,
        clientSubmissionId: String = "sub_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        val legacyKey = "legacy|$trimmed"
        val nowMs = System.currentTimeMillis()
        synchronized(submissionLock) {
            if (!seenSubmissionIds.add(clientSubmissionId)) return
            if (seenSubmissionIds.size > 128) {
                seenSubmissionIds.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            if (activeAiJob?.isActive == true && activePromptSignature == legacyKey) return
            val lastAt = recentSubmissionTimestampsMs[legacyKey]
            if (lastAt != null && (nowMs - lastAt) < DUPLICATE_SUBMISSION_WINDOW_MS) return
            recentSubmissionTimestampsMs[legacyKey] = nowMs
        }

        val updatedSearches = (listOf(trimmed) + _recentSearches.value).distinct().take(5)
        _recentSearches.value = updatedSearches
        viewModelScope.launch(Dispatchers.IO) {
            val currentPref = database.userPreferenceDao().getPreferences(currentUserId)
                ?: UserPreference(
                    userId = currentUserId,
                    displayName = _userProfile.value.displayName,
                    email = _userEmail.value
                )
            database.userPreferenceDao().upsertPreferences(
                currentPref.copy(
                    recentSearchesSerialized = serializeRecentSearches(updatedSearches),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val userMsg = AiChatMessage(
            id = "msg-u-$clientSubmissionId",
            isUser = true,
            text = trimmed,
            timestamp = "Now"
        )
        val assistantMsgId = "msg-a-${UUID.randomUUID().toString().take(8)}"
        val placeholderMsg = AiChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            timestamp = "Thinking",
            isStreaming = true
        )

        val historyPairs = ConversationSerializer.normalizeHistoryPairs(
            _chatMessages.value
                .filter { !it.isError && it.text.isNotBlank() && it.id != "msg-welcome" }
                .map { it.isUser to it.text }
        )

        // Clean any interrupted streaming rows before starting a new request
        _chatMessages.update { list ->
            list.map { if (it.isStreaming) it.copy(isStreaming = false) else it } + userMsg + placeholderMsg
        }
        _aiChatState.value = AiChatState.SENDING
        _chatInterfaceState.value = AlloraChatInterfaceState.SENDING
        _lastChatErrorCategory.value = null
        _orbState.value = OrbState.PROCESSING
        _aiStatusLabel.value = "Thinking with Allora..."

        _manualModeBlockedNotice.value = null
        activeAiJob?.cancel()
        activePromptSignature = "legacy|$trimmed"
        activeAiJob = viewModelScope.launch {
            // Non-blocking Firestore persistence so offline/slow network never delays or blocks AI inference
            launch {
                runCatching {
                    firestoreRepo.saveAiMessage(
                        AiMessageDoc(id = userMsg.id, userId = currentUserId, isUser = true, text = trimmed)
                    )
                }.onFailure { err ->
                    Log.w(TAG, "Background user message cloud sync skipped (${err::class.java.simpleName})")
                }
            }

            _aiChatState.value = AiChatState.THINKING
            _chatInterfaceState.value = AlloraChatInterfaceState.THINKING
            try {
                val result = aiService.sendAssistantWithTools(
                    prompt = trimmed,
                    historyPairs = historyPairs,
                    onToolProgress = { label ->
                        _aiStatusLabel.value = label
                    },
                    onStreamChunk = { delta ->
                        _aiChatState.value = AiChatState.STREAMING
                        _chatInterfaceState.value = AlloraChatInterfaceState.RECEIVING_RESPONSE
                        _aiStatusLabel.value = "Allora is replying..."
                        _chatMessages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(text = msg.text + delta, isStreaming = true)
                                } else msg
                            }
                        }
                    },
                    onAttemptReset = {
                        _chatMessages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) msg.copy(text = "", isStreaming = true)
                                else msg
                            }
                        }
                    }
                )

                result.fold(
                    onSuccess = { reply ->
                        _aiChatState.value = AiChatState.COMPLETED
                        _chatInterfaceState.value = AlloraChatInterfaceState.SUCCESS
                        _lastChatErrorCategory.value = null
                        _chatMessages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        text = reply.text,
                                        timestamp = "Now",
                                        isStreaming = false,
                                        followUpChips = reply.followUpChips,
                                        toolTraces = reply.toolTraces
                                    )
                                } else msg
                            }
                        }
                        val traceSummary = reply.toolTraces.joinToString(" | ") { trace ->
                            val suffix = if (trace.succeeded) "" else " [failed]"
                            "${trace.toolName}: ${trace.summary}$suffix"
                        }.takeIf { it.isNotBlank() }

                        launch {
                            runCatching {
                                firestoreRepo.saveAiMessage(
                                    AiMessageDoc(
                                        id = assistantMsgId,
                                        userId = currentUserId,
                                        isUser = false,
                                        text = reply.text,
                                        toolTraceSummary = traceSummary,
                                        isError = false
                                    )
                                )
                            }.onFailure { err ->
                                Log.w(TAG, "Background AI reply cloud sync skipped", err)
                            }
                        }
                        reply.createdTripId?.let { selectTrip(it) }
                        _orbState.value = OrbState.IDLE
                        _aiStatusLabel.value = "Ready • Allora Gemini AI"
                        _aiChatState.value = AiChatState.IDLE
                        viewModelScope.launch {
                            delay(600L)
                            _chatInterfaceState.compareAndSet(
                                AlloraChatInterfaceState.SUCCESS,
                                AlloraChatInterfaceState.IDLE
                            )
                        }
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        val manualEx = (e as? ManualModelUnavailableException)
                            ?: (e.cause as? ManualModelUnavailableException)
                        if (manualEx != null) {
                            _manualModeBlockedNotice.value = manualEx
                        }
                        val interfaceErrState = aiRequestManager.classifyInterfaceState(e)
                        _aiChatState.value = AiChatState.ERROR
                        _chatInterfaceState.value = interfaceErrState
                        _lastChatErrorCategory.value = interfaceErrState
                        _orbState.value = OrbState.ERROR
                        _aiStatusLabel.value = interfaceErrState.displayLabel
                        val friendlyError = aiRequestManager.classifyUserFriendlyError(e)
                            .ifBlank { "Unable to reach ALLORA AI right now. Tap Retry." }
                        _chatMessages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(
                                        text = friendlyError,
                                        timestamp = "Error",
                                        isError = true,
                                        isStreaming = false
                                    )
                                } else msg
                            }
                        }
                    }
                )
            } catch (ce: CancellationException) {
                _chatMessages.update { list ->
                    list.filterNot { it.id == assistantMsgId && it.text.isBlank() }
                        .map { if (it.id == assistantMsgId) it.copy(isStreaming = false) else it }
                }
                if (_aiChatState.value != AiChatState.ERROR) {
                    _aiChatState.value = AiChatState.IDLE
                    _chatInterfaceState.value = AlloraChatInterfaceState.IDLE
                    _orbState.value = OrbState.IDLE
                    _aiStatusLabel.value = "Ready • Allora Gemini AI"
                }
                throw ce
            } finally {
                if (activePromptSignature == "legacy|$trimmed") {
                    activePromptSignature = null
                }
            }
        }
    }

    private fun loadPersistedRichThreads() {
        val rawThreads = preloadedChatThreads ?: chatLocalStore?.loadThreads().orEmpty()
        val savedThreads = if (preloadedThreadsAlreadyRepaired && preloadedChatThreads != null) {
            rawThreads
        } else {
            rawThreads.map { thread ->
                thread.copy(messages = ConversationSerializer.repairRichMessages(thread.messages))
            }
        }
        if (savedThreads.isNotEmpty()) {
            _richThreads.value = savedThreads
            val savedActiveId = preloadedActiveThreadId ?: chatLocalStore?.loadActiveThreadId()
            _activeThreadId.value = savedThreads.firstOrNull { it.id == savedActiveId }?.id
                ?: savedThreads.first().id
        }
    }

    private fun persistRichThreadsNow() {
        val settled = _richThreads.value.map { thread ->
            thread.copy(
                messages = thread.messages.filterNot { msg ->
                    msg.isStreaming && msg.plainText.isBlank() && msg.blocks.isEmpty()
                }.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                }
            )
        }
        chatLocalStore?.saveState(settled, _activeThreadId.value)
    }

    fun setReplyingTo(message: AlloraRichMessage?) {
        _replyingToMessage.value = message
    }

    fun selectChatThread(threadId: String) {
        if (_richThreads.value.any { it.id == threadId }) {
            _activeThreadId.value = threadId
            _replyingToMessage.value = null
            persistRichThreadsNow()
        }
    }

    fun createNewChatThread(
        title: String = "New Project",
        category: String = "Projects",
        initialPrompt: String? = null
    ) {
        val cleanTitle = title.trim().ifBlank { "Allora" }
        val threadId = "thread_${UUID.randomUUID().toString().take(8)}"
        val welcome = defaultRichWelcome.copy(
            id = "msg-w-${UUID.randomUUID().toString().take(8)}",
            createdAtMs = System.currentTimeMillis()
        )
        val newThread = AlloraChatThread(
            id = threadId,
            title = cleanTitle,
            category = category,
            updatedAtMs = System.currentTimeMillis(),
            messages = listOf(welcome)
        )
        _richThreads.update { listOf(newThread) + it }
        _activeThreadId.value = threadId
        _replyingToMessage.value = null
        persistRichThreadsNow()
        if (!initialPrompt.isNullOrBlank()) {
            sendRichChatPrompt(initialPrompt)
        }
    }

    fun markOrderPlaced(messageId: String, blockId: String) {
        val tid = _activeThreadId.value
        _richThreads.update { threads ->
            threads.map { t ->
                if (t.id != tid) t else t.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = t.messages.map { msg ->
                        if (msg.id != messageId) msg else msg.copy(
                            blocks = msg.blocks.map { blk ->
                                if (blk is ChatBlock.OrderSummary && blk.id == blockId) {
                                    blk.copy(orderPlaced = true)
                                } else blk
                            }
                        )
                    }
                )
            }
        }
        persistRichThreadsNow()
    }

    // ---- ALLORA 2.0 Unified Chat Workspace Inline Action Handlers ----

    fun handleLiveBrowserAction(action: BrowserCardAction) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        val uid = currentUserId
        when (action) {
            BrowserCardAction.PAUSE -> {
                browserSvc.pauseAutomation(uid)
                viewModelScope.launch {
                    taskEngine?.onBrowserVerificationStateChanged(uid, browserLiveActivity.value)
                }
            }
            BrowserCardAction.RESUME -> {
                val resumed = browserSvc.resumeAutomation(uid)
                val updated = browserLiveActivity.value
                if (!resumed || updated.engineState != BrowserEngineState.AGENT_CONTROL || updated.isPaused) {
                    val reason = updated.lastResumeRejectionReason
                        ?: "Cannot resume automation until the page loads cleanly."
                    appendSystemNoticeInActiveChat(reason)
                } else {
                    updated.taskId?.takeIf { it.isNotBlank() }?.let { tid ->
                        viewModelScope.launch { taskEngine?.resumeTask(tid) }
                    }
                }
            }
            BrowserCardAction.STOP -> {
                val cur = browserLiveActivity.value
                val sid = cur.sessionId
                browserSvc.stopAutomationForUser(uid, sid)
                cur.taskId?.takeIf { it.isNotBlank() }?.let { tid ->
                    viewModelScope.launch { taskEngine?.pauseTask(tid) }
                }
                appendSystemNoticeInActiveChat("Stopped the ALLORA Computer session.")
            }
            BrowserCardAction.TAKE_CONTROL -> {
                viewModelScope.launch {
                    browserSvc.takeUserControl(uid)
                    taskEngine?.onBrowserVerificationStateChanged(uid, browserLiveActivity.value)
                }
            }
            BrowserCardAction.RETURN_CONTROL -> {
                viewModelScope.launch {
                    val cur = browserLiveActivity.value
                    val wasAuthTransition = cur.authTransitionDetected
                    val ready = browserSvc.returnControlToAllora(uid, cur.currentUrl, cur.pageTitle, cur.lastVisibleDomSummary)
                    val after = browserLiveActivity.value
                    if (!ready || after.controlOwner != BrowserControlOwner.AI || after.engineState != BrowserEngineState.AGENT_CONTROL) {
                        if (after.errorInfo != null || after.engineState == BrowserEngineState.NAVIGATION_FAILED) {
                            appendSystemNoticeInActiveChat(
                                after.lastResumeRejectionReason
                                    ?: "Cannot return control on an error page. Retry or open in Secure Browser."
                            )
                        } else {
                            emitInterventionCardIfNeeded(after)
                        }
                    } else {
                        cur.taskId?.takeIf { it.isNotBlank() }?.let { tid ->
                            taskEngine?.resumeTask(tid)
                        }
                        if (wasAuthTransition) {
                            appendSystemNoticeInActiveChat(
                                "Sign-in transition detected on ${PermissionService.normalizeDomain(cur.currentUrl)}. Control returned to ALLORA to continue."
                            )
                        }
                    }
                }
            }
            BrowserCardAction.ZOOM_IN -> {
                val next = (browserLiveActivity.value.zoomScale + 0.15f).coerceAtMost(2.0f)
                browserSvc.setZoomScale(uid, next)
            }
            BrowserCardAction.ZOOM_OUT -> {
                val next = (browserLiveActivity.value.zoomScale - 0.15f).coerceAtLeast(0.75f)
                browserSvc.setZoomScale(uid, next)
            }
        }
    }

    fun syncBrowserWebViewNavigationStarted(sessionId: String, url: String) {
        workspaceController?.browserAutomationService?.onWebViewNavigationStarted(currentUserId, sessionId, url)
    }

    fun syncBrowserRedirect(url: String) {
        workspaceController?.browserAutomationService?.onRedirectHop(currentUserId, url)
    }

    fun syncBrowserSchemeBlocked(url: String, scheme: String) {
        workspaceController?.browserAutomationService?.onSchemeBlocked(currentUserId, url, scheme)
    }

    fun stopBrowserPageLoading() {
        workspaceController?.browserAutomationService?.stopPageLoading(currentUserId)
    }

    fun syncWebViewRuntimeInfo(packageName: String, versionName: String) {
        workspaceController?.browserAutomationService?.updateWebViewRuntimeInfo(packageName, versionName)
    }

    fun syncBrowserWebViewProgress(url: String, progress: Int) {
        workspaceController?.browserAutomationService?.onWebViewProgressChanged(currentUserId, url, progress)
    }

    fun syncBrowserWebViewPageFinished(url: String, title: String, snippet: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        browserSvc.onWebViewPageFinished(currentUserId, url, title, snippet)
        val after = browserLiveActivity.value
        if (after.errorInfo == null && after.engineState != BrowserEngineState.NAVIGATION_FAILED) {
            viewModelScope.launch {
                val needsIntervention = browserSvc.inspectPageForManualIntervention(currentUserId, url, title, snippet)
                if (needsIntervention) {
                    val live = browserLiveActivity.value
                    taskEngine?.onBrowserVerificationStateChanged(currentUserId, live)
                    emitInterventionCardIfNeeded(live)
                }
            }
        }
    }

    fun syncBrowserHttpError(url: String, statusCode: Int, reason: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        browserSvc.onWebViewHttpError(currentUserId, url, statusCode, reason)
        if (url.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                browserSvc.probeOriginCompatibility(url, currentUserId)
            }
        }
    }

    fun syncBrowserNetworkError(url: String, errorCode: Int, description: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        browserSvc.onWebViewNetworkError(currentUserId, url, errorCode, description)
        if (url.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                browserSvc.probeOriginCompatibility(url, currentUserId)
            }
        }
    }

    fun syncBrowserSslError(url: String) {
        workspaceController?.browserAutomationService?.onWebViewSslError(currentUserId, url)
    }

    fun syncBrowserTimeout(url: String) {
        workspaceController?.browserAutomationService?.onWebViewTimeout(currentUserId, url)
    }

    fun retryBrowserNavigation() {
        workspaceController?.browserAutomationService?.clearNavigationError(currentUserId)
    }

    fun openExternalBrowserHandoff(url: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        viewModelScope.launch {
            browserSvc.markExternalCustomTabHandoff(currentUserId, url)
        }
    }

    fun restoreFromExternalBrowser() {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        viewModelScope.launch {
            val explanation = browserSvc.restoreSessionAfterExternalBrowser(currentUserId)
            if (explanation.isNotBlank()) {
                appendSystemNoticeInActiveChat(explanation)
            }
        }
    }

    fun syncBrowserVisualFrame(pngBytes: ByteArray, widthPx: Int, heightPx: Int) {
        workspaceController?.browserAutomationService?.publishVisualFrameIfChanged(
            currentUserId, pngBytes, widthPx, heightPx
        )
    }

    fun consumeBrowserPendingScript() {
        workspaceController?.browserAutomationService?.clearPendingScript()
    }

    fun completeBrowserDomCommand(commandId: String, result: BrowserDomCommandResult) {
        workspaceController?.browserAutomationService?.completeDomCommand(commandId, result)
    }

    fun downloadFromLiveBrowser(url: String, fileName: String, mimeType: String) {
        val resolved = BrowserSessionService.resolveUserUrlOrSearch(url) ?: run {
            appendSystemNoticeInActiveChat("Blocked download from unsupported URL scheme.")
            return
        }
        val controller = workspaceController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url(resolved).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code} while downloading $fileName")
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.size > 15 * 1024 * 1024) {
                        throw IllegalStateException("Download exceeds 15 MB limit.")
                    }
                    controller.browserSessionService.saveIsolatedDownload(
                        userId = currentUserId,
                        url = resolved,
                        fileName = fileName,
                        mimeType = mimeType,
                        bytes = bytes
                    ).getOrThrow()
                }
            }.onSuccess { saved ->
                appendSystemNoticeInActiveChat("Saved download '${saved.title}' privately to your workspace.")
            }.onFailure { err ->
                appendSystemNoticeInActiveChat("Download failed for $fileName: ${err.message ?: "network error"}")
            }
        }
    }

    fun returnBrowserControlWithSync(url: String, title: String, snippet: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        viewModelScope.launch {
            val cur = browserLiveActivity.value
            val wasAuthTransition = cur.authTransitionDetected
            val ready = browserSvc.returnControlToAllora(currentUserId, url, title, snippet)
            val after = browserLiveActivity.value
            if (!ready || after.controlOwner != BrowserControlOwner.AI || after.engineState != BrowserEngineState.AGENT_CONTROL) {
                if (after.errorInfo != null || after.engineState == BrowserEngineState.NAVIGATION_FAILED) {
                    appendSystemNoticeInActiveChat(
                        after.lastResumeRejectionReason
                            ?: "Cannot resume agent control on an error page. Retry navigation or use Secure Browser."
                    )
                } else {
                    emitInterventionCardIfNeeded(after)
                }
            } else {
                cur.taskId?.takeIf { it.isNotBlank() }?.let { tid ->
                    taskEngine?.resumeTask(tid)
                }
                val domain = PermissionService.normalizeDomain(url)
                val notice = if (wasAuthTransition) {
                    "Authentication change detected on $domain. Control returned to ALLORA — resuming your task."
                } else {
                    "Control returned to ALLORA on $domain."
                }
                appendSystemNoticeInActiveChat(notice)
            }
        }
    }

    fun takeControlForIntervention(sessionId: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        viewModelScope.launch {
            runCatching { browserSvc.assertSessionOwner(currentUserId, sessionId) }
                .onSuccess { browserSvc.takeUserControl(currentUserId) }
        }
    }

    fun continueAfterIntervention(sessionId: String) {
        val browserSvc = workspaceController?.browserAutomationService ?: return
        viewModelScope.launch {
            runCatching { browserSvc.assertSessionOwner(currentUserId, sessionId) }
                .onSuccess {
                    val cur = browserLiveActivity.value
                    val resolution = browserSvc.verifyAndResolveManualIntervention(
                        userId = currentUserId,
                        syncedUrl = cur.currentUrl,
                        syncedTitle = cur.pageTitle,
                        visibleSnippet = cur.lastVisibleDomSummary
                    )
                    val after = browserLiveActivity.value
                    if (!resolution.verified || after.requiresManualIntervention) {
                        appendSystemNoticeInActiveChat(
                            after.lastResumeRejectionReason
                                ?: resolution.reason.ifBlank {
                                    "Verification check is still incomplete on ${PermissionService.normalizeDomain(after.currentUrl)}. Complete verification in the live browser first."
                                }
                        )
                        return@onSuccess
                    }
                    val tid = after.taskId
                    if (!tid.isNullOrBlank()) {
                        taskEngine?.resumeTask(tid)
                    }
                    appendSystemNoticeInActiveChat(
                        "Verification verified cleanly on ${PermissionService.normalizeDomain(after.currentUrl)}. Resuming ALLORA from checkpoint."
                    )
                }
        }
    }

    fun approveChatPayment(messageId: String, block: ChatBlock.PaymentApproval) {
        if (block.userId.isNotBlank() && block.userId != currentUserId) {
            appendSystemNoticeInActiveChat("Blocked cross-user payment approval attempt.")
            return
        }
        if (block.expiresAtMs > 0L && System.currentTimeMillis() > block.expiresAtMs) {
            updateRichBlock(messageId, block.id) { blk ->
                if (blk is ChatBlock.PaymentApproval) blk.copy(status = "EXPIRED") else blk
            }
            appendSystemNoticeInActiveChat("This payment approval card has expired. Request a fresh payment check if needed.")
            return
        }
        val controller = workspaceController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val authRes = controller.paymentAuthService.authorizeDisclosure(currentUserId, block.idempotencyKey)
            if (authRes.isFailure) {
                val errMsg = authRes.exceptionOrNull()?.message ?: "Authorization rejected."
                val expired = errMsg.contains("expired", ignoreCase = true)
                updateRichBlock(messageId, block.id) { blk ->
                    if (blk is ChatBlock.PaymentApproval) blk.copy(status = if (expired) "EXPIRED" else "REJECTED") else blk
                }
                appendSystemNoticeInActiveChat("Payment authorization halted: $errMsg")
                return@launch
            }
            controller.walletService.executeAuthorizedPurchase(currentUserId, block.idempotencyKey).fold(
                onSuccess = { tx ->
                    updateRichBlock(messageId, block.id) { blk ->
                        if (blk is ChatBlock.PaymentApproval) blk.copy(status = "APPROVED") else blk
                    }
                    appendSystemNoticeInActiveChat(
                        "Payment approved & verified for ${block.productName} (${block.totalDisplay}). Provider ref: ${tx.providerTransactionId}."
                    )
                },
                onFailure = { err ->
                    updateRichBlock(messageId, block.id) { blk ->
                        if (blk is ChatBlock.PaymentApproval) blk.copy(status = "FAILED") else blk
                    }
                    appendSystemNoticeInActiveChat(
                        "Payment could not be finalized by provider: ${err.message ?: "Provider verification failed."}"
                    )
                }
            )
        }
    }

    fun rejectChatPayment(messageId: String, block: ChatBlock.PaymentApproval) {
        if (block.userId.isNotBlank() && block.userId != currentUserId) return
        viewModelScope.launch(Dispatchers.IO) {
            workspaceController?.paymentAuthService?.declineDisclosure(currentUserId, block.idempotencyKey)
            updateRichBlock(messageId, block.id) { blk ->
                if (blk is ChatBlock.PaymentApproval) blk.copy(status = "REJECTED") else blk
            }
            appendSystemNoticeInActiveChat("Payment declined for ${block.productName}. No wallet funds were charged.")
        }
    }

    fun approveAndSendChatEmail(
        messageId: String,
        block: ChatBlock.EmailApproval,
        recipient: String,
        subject: String,
        body: String
    ) {
        val controller = workspaceController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (block.isDeleteConfirmation && block.targetItemId.isNotBlank()) {
                controller.emailService.deleteEmail(
                    userId = currentUserId,
                    itemId = block.targetItemId,
                    ownerApproved = true
                ).fold(
                    onSuccess = {
                        updateRichBlock(messageId, block.id) { blk ->
                            if (blk is ChatBlock.EmailApproval) blk.copy(status = "DELETED") else blk
                        }
                        appendSystemNoticeInActiveChat("Moved '${block.subject}' to trash.")
                    },
                    onFailure = { err ->
                        appendSystemNoticeInActiveChat("Could not delete email: ${err.message ?: "Try reconnecting your email account."}")
                    }
                )
                return@launch
            }
            controller.emailService.sendEmail(
                userId = currentUserId,
                draftId = block.draftId.takeIf { it.isNotBlank() },
                recipient = recipient.trim(),
                subject = subject.trim(),
                body = body,
                ownerApproved = true,
                threadId = block.threadId.takeIf { it.isNotBlank() },
                accountEmail = block.accountEmail.takeIf { it.isNotBlank() }
            ).fold(
                onSuccess = { sent ->
                    updateRichBlock(messageId, block.id) { blk ->
                        if (blk is ChatBlock.EmailApproval) {
                            blk.copy(
                                recipient = sent.recipient,
                                subject = sent.subject,
                                body = sent.bodyText,
                                status = "SENT"
                            )
                        } else blk
                    }
                    val via = sent.accountEmail.ifBlank { "your connected email" }
                    appendSystemNoticeInActiveChat("Email sent to ${sent.recipient} via $via (${sent.subject}).")
                },
                onFailure = { err ->
                    appendSystemNoticeInActiveChat("Email was not sent: ${err.message ?: "Connect an email account in Mail first."}")
                }
            )
        }
    }

    fun rejectChatEmail(messageId: String, block: ChatBlock.EmailApproval) {
        updateRichBlock(messageId, block.id) { blk ->
            if (blk is ChatBlock.EmailApproval) blk.copy(status = "REJECTED") else blk
        }
        val msg = if (block.isDeleteConfirmation) {
            "Kept '${block.subject}' in your inbox."
        } else {
            "Email draft to ${block.recipient} was cancelled."
        }
        appendSystemNoticeInActiveChat(msg)
    }

    fun stageReplyOrForwardInChat(emailId: String, isForward: Boolean) {
        val controller = workspaceController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val item = controller.database.emailDao().getEmail(currentUserId, emailId) ?: return@launch
            val cleanSubject = if (isForward) {
                if (item.subject.startsWith("Fwd:", ignoreCase = true)) item.subject else "Fwd: ${item.subject}"
            } else {
                if (item.subject.startsWith("Re:", ignoreCase = true)) item.subject else "Re: ${item.subject}"
            }
            val cleanRecipient = if (isForward) "" else item.sender
            val quotedBody = if (isForward) {
                "\n\n---------- Forwarded message ----------\nFrom: ${item.sender}\nSubject: ${item.subject}\n\n${item.bodyText.ifBlank { item.snippet }}"
            } else {
                "\n\nOn ${item.sender} wrote:\n> ${item.snippet}"
            }
            val approval = ChatBlock.EmailApproval(
                id = "emappr_${UUID.randomUUID().toString().take(8)}",
                draftId = "",
                recipient = cleanRecipient,
                subject = cleanSubject,
                body = quotedBody,
                dateLabel = if (isForward) "Forward Draft" else "Reply Draft",
                status = "PENDING",
                threadId = if (isForward) "" else item.threadId,
                accountEmail = item.accountEmail
            )
            appendSystemNoticeInActiveChat(
                text = if (isForward) "Prepared a forward draft for '${item.subject}'. Review and confirm before sending."
                else "Prepared a reply draft to ${item.sender}. Review and confirm before sending.",
                extraBlocks = listOf(approval)
            )
        }
    }

    fun stageDeleteConfirmationInChat(emailId: String) {
        val controller = workspaceController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val item = controller.database.emailDao().getEmail(currentUserId, emailId) ?: return@launch
            val confirmCard = ChatBlock.EmailApproval(
                id = "emdel_${item.id}",
                draftId = "",
                recipient = item.sender,
                subject = item.subject,
                body = item.aiSummary.ifBlank { item.snippet },
                dateLabel = "Confirm Delete",
                status = "PENDING",
                threadId = item.threadId,
                accountEmail = item.accountEmail,
                isDeleteConfirmation = true,
                targetItemId = item.id
            )
            appendSystemNoticeInActiveChat(
                text = "Please confirm before moving '${item.subject}' to trash.",
                extraBlocks = listOf(confirmCard)
            )
        }
    }

    fun explainWalletTxInChat(txId: String) {
        sendRichChatPrompt("Explain wallet transaction $txId in plain English.")
    }

    fun respondBrowserPermission(messageId: String, block: ChatBlock.BrowserPermission, mode: String) {
        updateRichBlock(messageId, block.id) { blk ->
            if (blk is ChatBlock.BrowserPermission) blk.copy(status = mode) else blk
        }
        val controller = workspaceController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (mode == "DENIED") {
                controller.permissionService.revokeDomain(currentUserId, block.domain)
                appendSystemNoticeInActiveChat("Blocked browser agent access to ${block.domain}.")
                return@launch
            }
            if (mode == "ALLOW_LIMITED") {
                controller.permissionService.restoreDomain(currentUserId, block.domain)
            }
            val res = controller.browserAutomationService.navigateOrSearch(
                userId = currentUserId,
                urlOrQuery = "https://${block.domain}",
                ownerConfirmed = true,
                taskLabel = block.actionLabel
            )
            res.fold(
                onSuccess = { openedUrl ->
                    val sid = browserLiveActivity.value.sessionId.ifBlank {
                        "bsess_${UUID.randomUUID().toString().take(8)}"
                    }
                    appendSystemNoticeInActiveChat(
                        text = "Browser permission granted ($mode). Opening ${block.domain} live:",
                        extraBlocks = listOf(
                            ChatBlock.LiveBrowser(
                                id = "blk_live_${sid}",
                                sessionId = sid,
                                initialUrl = openedUrl,
                                taskLabel = block.actionLabel
                            )
                        )
                    )
                },
                onFailure = { err ->
                    appendSystemNoticeInActiveChat("Could not open ${block.domain}: ${err.message ?: "Blocked by policy."}")
                }
            )
        }
    }

    fun approveRegistration(messageId: String, block: ChatBlock.RegistrationApproval) {
        val forbidden = block.fields.entries.firstNotNullOfOrNull { (k, v) ->
            SecurityAuditService.containsForbiddenSecretInput("$k $v")
        }
        if (forbidden != null) {
            updateRichBlock(messageId, block.id) { blk ->
                if (blk is ChatBlock.RegistrationApproval) blk.copy(status = "REJECTED") else blk
            }
            appendSystemNoticeInActiveChat("Registration blocked: $forbidden")
            return
        }
        updateRichBlock(messageId, block.id) { blk ->
            if (blk is ChatBlock.RegistrationApproval) blk.copy(status = "APPROVED") else blk
        }
        val browserSvc = workspaceController?.browserAutomationService
        viewModelScope.launch(Dispatchers.IO) {
            val domain = PermissionService.normalizeDomain(block.url)
            var filledCount = 0
            var blockedCount = 0
            var missedCount = 0
            if (browserSvc != null && block.fields.isNotEmpty()) {
                for ((field, value) in block.fields) {
                    val lower = field.lowercase().replace("'", "")
                    val selector = "input[name*='$lower'], input[id*='$lower'], textarea[name*='$lower']"
                    val domRes = browserSvc.executeSafeFormFill(
                        userId = currentUserId,
                        domain = domain,
                        selector = selector,
                        value = value
                    ).getOrNull()
                    if (domRes != null && domRes.success) {
                        filledCount++
                    } else if (domRes?.statusCode == "BLOCKED_SENSITIVE_FIELD") {
                        blockedCount++
                    } else {
                        missedCount++
                    }
                }
            }
            val summary = when {
                filledCount > 0 && missedCount == 0 && blockedCount == 0 ->
                    "Verified live WebView fill of $filledCount non-secret field(s) for ${block.siteName}. Complete any password/OTP/CAPTCHA directly in the live browser."
                filledCount > 0 ->
                    "Filled $filledCount non-secret field(s) on ${block.siteName} ($missedCount not matched on page, $blockedCount blocked sensitive inputs). Review in the live browser."
                else ->
                    "Approved non-secret registration fields for ${block.siteName}, but no matching fields were reachable in the mounted WebView right now. Open ${block.siteName} live and enter password/OTP directly."
            }
            appendSystemNoticeInActiveChat(summary)
        }
    }

    fun rejectRegistration(messageId: String, block: ChatBlock.RegistrationApproval) {
        updateRichBlock(messageId, block.id) { blk ->
            if (blk is ChatBlock.RegistrationApproval) blk.copy(status = "REJECTED") else blk
        }
        appendSystemNoticeInActiveChat("Cancelled form registration on ${block.siteName}.")
    }

    fun controlAutonomousTask(taskId: String, action: String) {
        val engine = taskEngine ?: return
        viewModelScope.launch {
            when (action.uppercase()) {
                "PAUSE" -> engine.pauseTask(taskId)
                "RESUME" -> engine.resumeTask(taskId)
                "CANCEL", "STOP" -> engine.cancelTask(taskId)
            }
        }
    }

    private fun emitInterventionCardIfNeeded(state: BrowserLiveActivityState) {
        if (!state.requiresManualIntervention || state.interventionKind == BrowserInterventionKind.NONE) return
        val tid = _activeThreadId.value
        val activeMsgs = _richThreads.value.firstOrNull { it.id == tid }?.messages.orEmpty()
        val alreadyShown = activeMsgs.takeLast(2).any { msg ->
            msg.blocks.any { blk ->
                blk is ChatBlock.HumanIntervention &&
                    blk.sessionId == state.sessionId &&
                    blk.website == state.currentUrl &&
                    blk.kind == state.interventionKind.name
            }
        }
        if (alreadyShown) return
        val reason = state.interventionReason ?: "Manual assistance required inside the live browser."
        appendSystemNoticeInActiveChat(
            text = "Paused live browser: $reason",
            extraBlocks = listOf(
                ChatBlock.HumanIntervention(
                    id = "blk_int_${UUID.randomUUID().toString().take(8)}",
                    sessionId = state.sessionId,
                    website = state.currentUrl,
                    kind = state.interventionKind.name,
                    reason = reason
                )
            )
        )
    }

    private fun updateRichBlock(
        messageId: String,
        blockId: String,
        transform: (ChatBlock) -> ChatBlock
    ) {
        _richThreads.update { threads ->
            threads.map { t ->
                t.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = t.messages.map { msg ->
                        if (msg.id != messageId) msg else msg.copy(
                            blocks = msg.blocks.map { blk ->
                                if (blk.id == blockId) transform(blk) else blk
                            }
                        )
                    }
                )
            }
        }
        persistRichThreadsNow()
    }

    private fun appendSystemNoticeInActiveChat(text: String, extraBlocks: List<ChatBlock> = emptyList()) {
        val tid = _activeThreadId.value
        val msgId = "msg-sys-${UUID.randomUUID().toString().take(8)}"
        val blocks = buildList {
            add(ChatBlock.Text(id = "blk_${msgId}_t", content = text))
            addAll(extraBlocks)
        }
        val notice = AlloraRichMessage(
            id = msgId,
            isUser = false,
            plainText = text,
            blocks = blocks,
            timestampLabel = "Now",
            createdAtMs = System.currentTimeMillis(),
            isStreaming = false
        )
        _richThreads.update { threads ->
            threads.map { t ->
                if (t.id != tid) t else t.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = t.messages + notice
                )
            }
        }
        persistRichThreadsNow()
    }

    fun sendRichChatPrompt(
        prompt: String,
        attachments: List<ChatAttachment> = emptyList(),
        explicitReplyTo: AlloraRichMessage? = _replyingToMessage.value,
        clientSubmissionId: String = "sub_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        val promptToSend = if (trimmed.isNotEmpty()) trimmed else {
            if (attachments.any { it.isImage }) "What can you tell me about the attached photo?"
            else "Summarize the attached file cleanly."
        }

        val tid = _activeThreadId.value
        val attachmentKey = attachments.joinToString(",") { "${it.id}:${it.displayName}" }
        val signature = "$tid|$promptToSend|${attachments.size}|${explicitReplyTo?.id.orEmpty()}"
        val dedupKey = "$tid|${promptToSend.lowercase()}|$attachmentKey"
        val userMsgId = "msg-u-$clientSubmissionId"
        val nowMs = System.currentTimeMillis()

        val currentThreadMessages = _richThreads.value.firstOrNull { it.id == tid }?.messages.orEmpty()
        synchronized(submissionLock) {
            if (!seenSubmissionIds.add(clientSubmissionId)) {
                return
            }
            if (seenSubmissionIds.size > 128) {
                seenSubmissionIds.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            if (currentThreadMessages.any { it.id == userMsgId }) {
                return
            }
            if (activeAiJob?.isActive == true && activePromptSignature == signature) {
                return
            }
            val lastAt = recentSubmissionTimestampsMs[dedupKey]
            if (lastAt != null && (nowMs - lastAt) < DUPLICATE_SUBMISSION_WINDOW_MS) {
                return
            }
            recentSubmissionTimestampsMs[dedupKey] = nowMs
            if (recentSubmissionTimestampsMs.size > 64) {
                recentSubmissionTimestampsMs.entries.iterator().let {
                    if (it.hasNext()) { it.next(); it.remove() }
                }
            }
            _isSendingMessage.value = true
            _isRetryingMessage.value = false
        }

        val quote = explicitReplyTo?.let { target ->
            AlloraReplyQuote(
                messageId = target.id,
                senderLabel = if (target.isUser) "You" else "Allora",
                snippet = target.plainText.ifBlank {
                    ChatBlockJsonParser.summarizePlainText(target.blocks)
                }.take(90)
            )
        }
        _replyingToMessage.value = null

        val userBlocks = buildList {
            if (trimmed.isNotEmpty()) {
                add(ChatBlock.Text(id = "blk_u_$clientSubmissionId", content = trimmed))
            }
        }
        val userMsg = AlloraRichMessage(
            id = userMsgId,
            isUser = true,
            plainText = trimmed,
            blocks = userBlocks,
            timestampLabel = "Now",
            createdAtMs = nowMs,
            replyTo = quote,
            attachments = attachments
        )

        val assistantMsgId = "msg-a-$clientSubmissionId"
        val historyPairs = ConversationSerializer.normalizeHistoryPairs(
            currentThreadMessages
                .filter { !it.isError && !it.isStreaming && it.plainText.isNotBlank() && !it.id.startsWith("msg-w") }
                .map { it.isUser to it.plainText }
        )

        val placeholderMsg = AlloraRichMessage(
            id = assistantMsgId,
            isUser = false,
            plainText = "",
            blocks = emptyList(),
            timestampLabel = "Thinking",
            createdAtMs = nowMs,
            isStreaming = true
        )

        val autoTitle = if (currentThreadMessages.count { it.isUser } == 0 && trimmed.isNotBlank()) {
            trimmed.split(Regex("\\s+")).take(5).joinToString(" ").take(32)
        } else null

        // Clean up any older unfinished streaming placeholder before launching this turn
        finalizeDanglingPlaceholder()
        activeAiJob?.cancel()

        _richThreads.update { threads ->
            threads.map { t ->
                if (t.id != tid) t else t.copy(
                    title = if (autoTitle != null && t.title == "Allora") autoTitle else t.title,
                    updatedAtMs = nowMs,
                    messages = t.messages + userMsg + placeholderMsg
                )
            }
        }
        persistRichThreadsNow()

        launchRichInference(
            tid = tid,
            assistantMsgId = assistantMsgId,
            userMsgIdToPersist = userMsgId,
            promptToSend = promptToSend,
            historyPairs = historyPairs,
            quote = quote,
            attachments = attachments,
            signature = signature,
            isRetry = false
        )
    }

    fun requestSessionReverification() {
        onVerifySessionRequested?.invoke()
    }

    fun retryRichChatMessage(failedAssistantMessageId: String) {
        synchronized(submissionLock) {
            if (_isSendingMessage.value || _isRetryingMessage.value) {
                return
            }
            if (activeAiJob?.isActive == true && activeRichPlaceholderId == failedAssistantMessageId) {
                return
            }
            _isSendingMessage.value = true
            _isRetryingMessage.value = true
        }

        val recorded = failedPromptsByAssistantId[failedAssistantMessageId]
        val targetThread = if (recorded != null) {
            _richThreads.value.firstOrNull { it.id == recorded.threadId }
        } else {
            _richThreads.value.firstOrNull { t -> t.messages.any { it.id == failedAssistantMessageId } }
        }
        if (targetThread == null) {
            _isSendingMessage.value = false
            _isRetryingMessage.value = false
            return
        }

        val failedIndex = targetThread.messages.indexOfFirst { it.id == failedAssistantMessageId }
        if (failedIndex < 0) {
            _isSendingMessage.value = false
            _isRetryingMessage.value = false
            return
        }

        val fallbackUserMsg = targetThread.messages
            .take(failedIndex)
            .lastOrNull { it.isUser }

        val promptToRetry = recorded?.prompt
            ?: fallbackUserMsg?.plainText?.takeIf { it.isNotBlank() }
        if (promptToRetry == null) {
            _isSendingMessage.value = false
            _isRetryingMessage.value = false
            return
        }
        val attachmentsToRetry = recorded?.attachments ?: fallbackUserMsg?.attachments.orEmpty()
        val quoteToRetry = recorded?.replyTo ?: fallbackUserMsg?.replyTo

        val historyPairs = ConversationSerializer.normalizeHistoryPairs(
            targetThread.messages
                .take(failedIndex)
                .dropLastWhile { it.isUser && it.id == fallbackUserMsg?.id }
                .filter { !it.isError && !it.isStreaming && it.plainText.isNotBlank() && !it.id.startsWith("msg-w") }
                .map { it.isUser to it.plainText }
        )

        finalizeDanglingPlaceholder()
        activeAiJob?.cancel()

        // Replace error bubble in-place with a fresh retrying/thinking placeholder (no duplicate user bubble)
        _richThreads.update { threads ->
            threads.map { t ->
                if (t.id != targetThread.id) t else t.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = t.messages.map { m ->
                        if (m.id == failedAssistantMessageId) {
                            m.copy(
                                plainText = "",
                                blocks = emptyList(),
                                timestampLabel = "Retrying",
                                isError = false,
                                isStreaming = true
                            )
                        } else m
                    }
                )
            }
        }
        persistRichThreadsNow()

        launchRichInference(
            tid = targetThread.id,
            assistantMsgId = failedAssistantMessageId,
            userMsgIdToPersist = fallbackUserMsg?.id,
            promptToSend = promptToRetry,
            historyPairs = historyPairs,
            quote = quoteToRetry,
            attachments = attachmentsToRetry,
            signature = "${targetThread.id}|retry|$failedAssistantMessageId",
            isRetry = true
        )
    }

    private fun finalizeDanglingPlaceholder() {
        val oldId = activeRichPlaceholderId ?: return
        val oldTid = activeRichThreadId ?: return
        _richThreads.update { threads ->
            threads.map { t ->
                if (t.id != oldTid) t else t.copy(
                    messages = t.messages.mapNotNull { m ->
                        when {
                            m.id != oldId -> m
                            m.plainText.isBlank() && m.blocks.isEmpty() -> null
                            m.isStreaming -> m.copy(
                                isStreaming = false,
                                blocks = m.blocks.ifEmpty {
                                    ChatBlockJsonParser.splitTextIntoShortBubbles(m.plainText)
                                }
                            )
                            else -> m
                        }
                    }
                )
            }
        }
    }

    private fun launchRichInference(
        tid: String,
        assistantMsgId: String,
        userMsgIdToPersist: String?,
        promptToSend: String,
        historyPairs: List<Pair<Boolean, String>>,
        quote: AlloraReplyQuote?,
        attachments: List<ChatAttachment>,
        signature: String,
        isRetry: Boolean = false
    ) {
        _aiChatState.value = AiChatState.SENDING
        _chatInterfaceState.value = AlloraChatInterfaceState.SENDING
        _lastChatErrorCategory.value = null
        _orbState.value = OrbState.PROCESSING
        _aiStatusLabel.value = if (isRetry) "Retrying with Allora..." else "Thinking with Allora..."
        _chatTypingState.value = false
        _chatLiveTaskStep.value = if (isRetry) "Retrying..." else ""
        _manualModeBlockedNotice.value = null

        activePromptSignature = signature
        activeRichPlaceholderId = assistantMsgId
        activeRichThreadId = tid

        activeAiJob = viewModelScope.launch {
            val typingDelayJob = launch {
                delay(280L)
                if (_aiChatState.value == AiChatState.THINKING || _aiChatState.value == AiChatState.SENDING) {
                    _chatTypingState.value = true
                }
            }

            // Persist user message to Firestore in background (deterministic ID makes retry idempotent)
            if (userMsgIdToPersist != null) {
                launch {
                    runCatching {
                        firestoreRepo.saveAiMessage(
                            AiMessageDoc(id = userMsgIdToPersist, userId = currentUserId, isUser = true, text = promptToSend)
                        )
                    }.onFailure { err ->
                        Log.w(TAG, "Background user chat message cloud sync skipped (${err::class.java.simpleName})")
                    }
                }
            }

            _aiChatState.value = AiChatState.THINKING
            _chatInterfaceState.value = AlloraChatInterfaceState.THINKING

            val bitmaps = if (attachments.any { it.isImage }) {
                withContext(Dispatchers.IO) {
                    attachments.filter { it.isImage }.mapNotNull { att ->
                        runCatching { attachmentBitmapLoader?.invoke(att.uriString) }.getOrNull()
                    }
                }
            } else emptyList()

            val fileContext = if (attachments.any { !it.isImage }) {
                withContext(Dispatchers.IO) {
                    attachments.filter { !it.isImage }.mapNotNull { att ->
                        runCatching {
                            val content = attachmentTextReader?.invoke(att.uriString, att.displayName)
                            if (!content.isNullOrBlank()) "File ${att.displayName}:\n$content"
                            else "Attached file: ${att.displayName} (${att.mimeType.ifBlank { "document" }})"
                        }.getOrNull()
                    }.joinToString("\n\n").takeIf { it.isNotBlank() }
                }
            } else null

            try {
                val liveBrowser = browserLiveActivity.value
                val activeTask = autonomousTasks.value.firstOrNull {
                    it.status.isActiveExecution ||
                        it.status == com.example.agent.AgentTaskStatus.PAUSED ||
                        it.status == com.example.agent.AgentTaskStatus.AWAITING_INPUT ||
                        it.status == com.example.agent.AgentTaskStatus.WAITING_FOR_APPROVAL ||
                        it.status == com.example.agent.AgentTaskStatus.WAITING_FOR_PERMISSION
                }
                val result = aiService.sendStructuredChatTurn(
                    prompt = promptToSend,
                    historyPairs = historyPairs,
                    quotedSnippet = quote?.snippet,
                    attachedBitmaps = bitmaps,
                    attachedFileSummary = fileContext,
                    taskId = liveBrowser.taskId ?: activeTask?.taskId,
                    browserSessionId = liveBrowser.sessionId.takeIf { liveBrowser.active && it.isNotBlank() },
                    activeBrowserUrl = liveBrowser.currentUrl.takeIf { liveBrowser.active && it.isNotBlank() },
                    onTaskStep = { step ->
                        _chatLiveTaskStep.value = step
                        _aiStatusLabel.value = step
                    },
                    onAiStatusPhase = { phase, detail ->
                        if (phase != AiStatusPhase.IDLE) {
                            _aiStatusLabel.value = detail
                        }
                    },
                    onTextStream = { streamedPreview ->
                        typingDelayJob.cancel()
                        _chatTypingState.value = false
                        _aiChatState.value = AiChatState.STREAMING
                        _chatInterfaceState.value = AlloraChatInterfaceState.RECEIVING_RESPONSE
                        _aiStatusLabel.value = "Allora is replying..."
                        _richThreads.update { threads ->
                            threads.map { t ->
                                if (t.id != tid) t else t.copy(
                                    messages = t.messages.map { m ->
                                        if (m.id == assistantMsgId) {
                                            m.copy(
                                                plainText = streamedPreview,
                                                timestampLabel = "Streaming",
                                                isStreaming = true
                                            )
                                        } else m
                                    }
                                )
                            }
                        }
                    },
                    onAttemptReset = {
                        _richThreads.update { threads ->
                            threads.map { t ->
                                if (t.id != tid) t else t.copy(
                                    messages = t.messages.map { m ->
                                        if (m.id == assistantMsgId) {
                                            m.copy(
                                                plainText = "",
                                                timestampLabel = "Switching",
                                                isStreaming = true
                                            )
                                        } else m
                                    }
                                )
                            }
                        }
                    }
                )

                typingDelayJob.cancel()
                _chatTypingState.value = false
                _chatLiveTaskStep.value = ""

                result.fold(
                    onSuccess = { reply ->
                        failedPromptsByAssistantId.remove(assistantMsgId)
                        _aiChatState.value = AiChatState.COMPLETED
                        _chatInterfaceState.value = AlloraChatInterfaceState.SUCCESS
                        _lastChatErrorCategory.value = null
                        val completedMsg = AlloraRichMessage(
                            id = assistantMsgId,
                            isUser = false,
                            plainText = reply.plainText,
                            blocks = reply.blocks,
                            timestampLabel = "Now",
                            createdAtMs = System.currentTimeMillis(),
                            isStreaming = false,
                            followUpChips = reply.followUpChips,
                            toolTraces = reply.toolTraces
                        )
                        _richThreads.update { threads ->
                            threads.map { t ->
                                if (t.id != tid) t else t.copy(
                                    updatedAtMs = System.currentTimeMillis(),
                                    messages = t.messages.map { m -> if (m.id == assistantMsgId) completedMsg else m }
                                )
                            }
                        }
                        persistRichThreadsNow()

                        val traceSummary = reply.toolTraces.joinToString(" | ") { trace ->
                            val suffix = if (trace.succeeded) "" else " [failed]"
                            "${trace.toolName}: ${trace.summary}$suffix"
                        }.takeIf { it.isNotBlank() }

                        launch {
                            runCatching {
                                firestoreRepo.saveAiMessage(
                                    AiMessageDoc(
                                        id = assistantMsgId,
                                        userId = currentUserId,
                                        isUser = false,
                                        text = reply.plainText,
                                        toolTraceSummary = traceSummary,
                                        isError = false
                                    )
                                )
                            }.onFailure { err ->
                                Log.w(TAG, "Background AI chat reply cloud sync skipped (${err::class.java.simpleName})")
                            }
                        }
                        reply.createdTripId?.let { selectTrip(it) }
                        _orbState.value = OrbState.IDLE
                        _aiStatusLabel.value = "Ready • Allora Gemini AI"
                        _aiChatState.value = AiChatState.IDLE
                        viewModelScope.launch {
                            delay(600L)
                            _chatInterfaceState.compareAndSet(
                                AlloraChatInterfaceState.SUCCESS,
                                AlloraChatInterfaceState.IDLE
                            )
                        }
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        val manualEx = (e as? ManualModelUnavailableException)
                            ?: (e.cause as? ManualModelUnavailableException)
                        if (manualEx != null) {
                            _manualModeBlockedNotice.value = manualEx
                        }
                        val interfaceErrState = aiRequestManager.classifyInterfaceState(e)
                        _aiChatState.value = AiChatState.ERROR
                        _chatInterfaceState.value = interfaceErrState
                        _lastChatErrorCategory.value = interfaceErrState
                        _orbState.value = OrbState.ERROR
                        _aiStatusLabel.value = interfaceErrState.displayLabel
                        val errText = aiRequestManager.classifyUserFriendlyError(e)
                            .ifBlank { "Couldn't reach Allora right now. Tap Retry." }
                        failedPromptsByAssistantId[assistantMsgId] = FailedChatPrompt(
                            threadId = tid,
                            prompt = promptToSend,
                            attachments = attachments,
                            replyTo = quote
                        )
                        val errMsg = AlloraRichMessage(
                            id = assistantMsgId,
                            isUser = false,
                            plainText = errText,
                            blocks = listOf(ChatBlock.Text(id = "blk_err_${assistantMsgId}", content = errText)),
                            timestampLabel = "Error",
                            createdAtMs = System.currentTimeMillis(),
                            isError = true,
                            isStreaming = false
                        )
                        _richThreads.update { threads ->
                            threads.map { t ->
                                if (t.id != tid) t else t.copy(
                                    updatedAtMs = System.currentTimeMillis(),
                                    messages = t.messages.map { m -> if (m.id == assistantMsgId) errMsg else m }
                                )
                            }
                        }
                        persistRichThreadsNow()
                    }
                )
            } catch (ce: CancellationException) {
                typingDelayJob.cancel()
                if (activeRichPlaceholderId == assistantMsgId) {
                    _chatTypingState.value = false
                    _chatLiveTaskStep.value = ""
                    finalizeDanglingPlaceholder()
                    _aiChatState.value = AiChatState.IDLE
                    _chatInterfaceState.value = AlloraChatInterfaceState.IDLE
                    _orbState.value = OrbState.IDLE
                    _aiStatusLabel.value = "Ready • Allora Gemini AI"
                }
                throw ce
            } finally {
                bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                if (activeRichPlaceholderId == assistantMsgId) {
                    activePromptSignature = null
                    activeRichPlaceholderId = null
                    activeRichThreadId = null
                }
                _isSendingMessage.value = false
                _isRetryingMessage.value = false
            }
        }
    }

    fun clearAiConversation() {
        activeAiJob?.cancel()
        activePromptSignature = null
        activeRichPlaceholderId = null
        activeRichThreadId = null
        failedPromptsByAssistantId.clear()
        _isSendingMessage.value = false
        _isRetryingMessage.value = false
        _aiChatState.value = AiChatState.IDLE
        _chatInterfaceState.value = AlloraChatInterfaceState.IDLE
        _lastChatErrorCategory.value = null
        _orbState.value = OrbState.IDLE
        _aiStatusLabel.value = "Ready • Allora Gemini AI"
        _chatTypingState.value = false
        _chatLiveTaskStep.value = ""
        _replyingToMessage.value = null
        _chatMessages.value = listOf(defaultWelcomeMessage)
        val tid = _activeThreadId.value
        val freshWelcome = defaultRichWelcome.copy(
            id = "msg-w-${UUID.randomUUID().toString().take(8)}",
            createdAtMs = System.currentTimeMillis()
        )
        _richThreads.update { threads ->
            threads.map { t ->
                if (t.id == tid) t.copy(title = "Allora", updatedAtMs = System.currentTimeMillis(), messages = listOf(freshWelcome))
                else t
            }
        }
        persistRichThreadsNow()
        viewModelScope.launch {
            runCatching { firestoreRepo.clearAiMessages() }
        }
    }

    // ---- PART 6: Trip-Scoped Persistent Itineraries ----

    fun selectTrip(tripId: String) {
        if (_selectedTripId.value == tripId && itineraryObserverJob?.isActive == true) return
        _selectedTripId.value = tripId
        itineraryObserverJob?.cancel()
        itineraryObserverJob = viewModelScope.launch {
            firestoreRepo.observeItinerarySlots(currentUserId, tripId)
                .catch { error -> handleStreamException("itinerary slots", error) }
                .collect { docs ->
                    _itinerarySlots.value = docs.map { doc ->
                        ItinerarySlot(
                            id = doc.id,
                            timeRange = doc.timeRange,
                            title = doc.title,
                            location = doc.location,
                            type = doc.category,
                            durationMinutes = doc.durationMinutes.toInt(),
                            routeSequence = doc.routeSequence.toInt(),
                            calSyncStatus = doc.calSyncStatus,
                            dayNumber = doc.dayNumber.toInt(),
                            latitude = doc.latitude,
                            longitude = doc.longitude,
                            notes = doc.notes,
                            estimatedCostUsd = doc.estimatedCostUsd
                        )
                    }
                    _itineraryMeta.value = docs.associate { doc ->
                        doc.id to ItineraryMeta(estimatedCostUsd = doc.estimatedCostUsd, note = doc.notes)
                    }
                }
        }
    }

    fun updateWizard(transform: (TripWizardState) -> TripWizardState) {
        _wizardState.update(transform)
    }

    fun generateWizardItinerary() {
        val state = _wizardState.value
        if (state.isGenerating) return

        _wizardState.update { it.copy(isGenerating = true, generationError = null) }
        _orbState.value = OrbState.PROCESSING
        _aiStatusLabel.value = "Synthesizing ${state.destination} itinerary..."

        viewModelScope.launch {
            val result = aiService.generateStructuredItinerary(
                destination = state.destination,
                startDate = state.startDate,
                endDate = state.endDate,
                travellers = state.travellersCount,
                style = state.style,
                budgetUsd = state.budgetUsd,
                transport = state.transport,
                lodgingType = state.lodgingType,
                activities = state.activities
            )
            result.onSuccess { generated ->
                val tripRes = firestoreRepo.createTrip(
                    TripPlanDoc(
                        title = generated.title,
                        destination = state.destination,
                        startDate = state.startDate,
                        endDate = state.endDate,
                        travellersCount = state.travellersCount.toLong(),
                        budgetUsd = state.budgetUsd,
                        style = state.style,
                        status = "PLANNED"
                    )
                )
                tripRes.onSuccess { tripId ->
                    val slotDocs = generated.slots.map { slot ->
                        ItinerarySlotDoc(
                            id = slot.id,
                            tripId = tripId,
                            dayNumber = slot.dayNumber.toLong(),
                            timeRange = slot.timeRange,
                            title = slot.title,
                            location = slot.location,
                            latitude = slot.latitude,
                            longitude = slot.longitude,
                            category = slot.type,
                            durationMinutes = slot.durationMinutes.toLong(),
                            routeSequence = slot.routeSequence.toLong(),
                            notes = slot.notes,
                            estimatedCostUsd = slot.estimatedCostUsd,
                            calSyncStatus = "Cloud Synced"
                        )
                    }
                    firestoreRepo.saveAllItinerarySlots(tripId, slotDocs)
                    selectTrip(tripId)
                }.onFailure { err ->
                    _cloudSyncError.value = err.localizedMessage ?: "Failed to save trip to Firestore."
                }

                _itinerarySlots.value = generated.slots
                _itineraryMeta.value = generated.slots.associate { slot ->
                    slot.id to ItineraryMeta(slot.estimatedCostUsd, slot.notes)
                }
                _wizardState.update {
                    it.copy(
                        isGenerating = false,
                        generatedTitle = generated.title,
                        generatedSummary = generated.summary,
                        currentStep = 10
                    )
                }
                _orbState.value = OrbState.IDLE
                _aiStatusLabel.value = "Ready • Allora Gemini AI"

                addNotification(
                    title = "AI Itinerary Saved",
                    message = "${generated.title} (${generated.slots.size} stops) persisted to Trips.",
                    category = "Itinerary"
                )
            }.onFailure { err ->
                _orbState.value = OrbState.ERROR
                _aiStatusLabel.value = "Itinerary generation failed"
                _wizardState.update {
                    it.copy(
                        isGenerating = false,
                        generationError = err.localizedMessage ?: "Failed to generate itinerary via AI service."
                    )
                }
            }
        }
    }

    private suspend fun ensureActiveTripId(): String? {
        _selectedTripId.value?.let { return it }
        val wiz = _wizardState.value
        val createRes = firestoreRepo.createTrip(
            TripPlanDoc(
                title = wiz.generatedTitle.ifBlank { "${wiz.destination} Itinerary" },
                destination = wiz.destination,
                startDate = wiz.startDate,
                endDate = wiz.endDate,
                travellersCount = wiz.travellersCount.toLong(),
                budgetUsd = wiz.budgetUsd,
                style = wiz.style,
                status = "PLANNED"
            )
        )
        return createRes.getOrNull()?.also { selectTrip(it) }
    }

    fun moveItinerarySlotUp(slotId: String) {
        val current = _itinerarySlots.value
        val idx = current.indexOfFirst { it.id == slotId }
        if (idx <= 0) return
        val mutable = current.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(idx - 1, item)
        val reordered = mutable.mapIndexed { newIdx, slot -> slot.copy(routeSequence = newIdx + 1) }
        _itinerarySlots.value = reordered

        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            val docs = reordered.map { slot ->
                val meta = _itineraryMeta.value[slot.id]
                ItinerarySlotDoc(
                    id = slot.id,
                    tripId = tripId,
                    dayNumber = slot.dayNumber.toLong(),
                    timeRange = slot.timeRange,
                    title = slot.title,
                    location = slot.location,
                    latitude = slot.latitude,
                    longitude = slot.longitude,
                    category = slot.type,
                    durationMinutes = slot.durationMinutes.toLong(),
                    routeSequence = slot.routeSequence.toLong(),
                    notes = meta?.note ?: slot.notes,
                    estimatedCostUsd = meta?.estimatedCostUsd ?: slot.estimatedCostUsd,
                    calSyncStatus = slot.calSyncStatus
                )
            }
            firestoreRepo.saveAllItinerarySlots(tripId, docs)
        }
    }

    fun updateItinerarySlotNoteAndCost(
        slotId: String,
        note: String,
        costUsd: Double,
        updatedTitle: String? = null,
        updatedTimeRange: String? = null,
        updatedDayNumber: Int? = null
    ) {
        val cleanCost = costUsd.coerceAtLeast(0.0)
        _itineraryMeta.update { map ->
            map + (slotId to ItineraryMeta(estimatedCostUsd = cleanCost, note = note))
        }
        var updatedSlot: ItinerarySlot? = null
        _itinerarySlots.update { list ->
            list.map { slot ->
                if (slot.id == slotId) {
                    slot.copy(
                        title = updatedTitle?.takeIf { it.isNotBlank() } ?: slot.title,
                        timeRange = updatedTimeRange?.takeIf { it.isNotBlank() } ?: slot.timeRange,
                        dayNumber = updatedDayNumber?.coerceIn(1, 30) ?: slot.dayNumber,
                        notes = note,
                        estimatedCostUsd = cleanCost
                    ).also { updatedSlot = it }
                } else slot
            }
        }
        val slotToSave = updatedSlot ?: return
        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            firestoreRepo.upsertItinerarySlot(
                tripId = tripId,
                slot = ItinerarySlotDoc(
                    id = slotToSave.id,
                    tripId = tripId,
                    dayNumber = slotToSave.dayNumber.toLong(),
                    timeRange = slotToSave.timeRange,
                    title = slotToSave.title,
                    location = slotToSave.location,
                    latitude = slotToSave.latitude,
                    longitude = slotToSave.longitude,
                    category = slotToSave.type,
                    durationMinutes = slotToSave.durationMinutes.toLong(),
                    routeSequence = slotToSave.routeSequence.toLong(),
                    notes = note,
                    estimatedCostUsd = cleanCost,
                    calSyncStatus = slotToSave.calSyncStatus
                )
            )
        }
    }

    fun deleteItinerarySlot(slotId: String) {
        _itinerarySlots.update { list ->
            list.filterNot { it.id == slotId }
                .mapIndexed { idx, slot -> slot.copy(routeSequence = idx + 1) }
        }
        _itineraryMeta.update { it - slotId }
        val tripId = _selectedTripId.value ?: return
        viewModelScope.launch {
            firestoreRepo.deleteItinerarySlot(tripId, slotId)
        }
    }

    fun addItinerarySlot(
        title: String,
        location: String,
        timeRange: String,
        category: String,
        costUsd: Double,
        dayNumber: Int = 1,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        val id = "slot_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val nextSeq = _itinerarySlots.value.size + 1
        val cleanCost = costUsd.coerceAtLeast(0.0)
        val newSlot = ItinerarySlot(
            id = id,
            timeRange = timeRange.ifBlank { "Flexible Window" },
            title = title.ifBlank { "Custom Activity" },
            location = location.ifBlank { _wizardState.value.destination },
            type = category,
            durationMinutes = 90,
            routeSequence = nextSeq,
            calSyncStatus = "Cloud Synced",
            dayNumber = dayNumber.coerceIn(1, 30),
            latitude = latitude,
            longitude = longitude,
            notes = "",
            estimatedCostUsd = cleanCost
        )
        _itinerarySlots.update { it + newSlot }
        _itineraryMeta.update { it + (id to ItineraryMeta(cleanCost, "")) }

        viewModelScope.launch {
            val tripId = ensureActiveTripId() ?: return@launch
            firestoreRepo.upsertItinerarySlot(
                tripId = tripId,
                slot = ItinerarySlotDoc(
                    id = id,
                    tripId = tripId,
                    dayNumber = newSlot.dayNumber.toLong(),
                    timeRange = newSlot.timeRange,
                    title = newSlot.title,
                    location = newSlot.location,
                    latitude = latitude,
                    longitude = longitude,
                    category = newSlot.type,
                    durationMinutes = 90L,
                    routeSequence = nextSeq.toLong(),
                    notes = "",
                    estimatedCostUsd = cleanCost,
                    calSyncStatus = "Cloud Synced"
                )
            )
        }
    }

    // ---- ALLORA LIVE Action Bridge Helpers ----

    suspend fun fetchWeatherForLiveBridge(city: String): Result<String> {
        val clean = city.trim().substringBefore(",").trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("City name is required."))
        }
        _activeWeatherCity.value = clean
        _weatherStates.update { it + (clean to WeatherLoadState.Loading) }
        val result = weatherRepo.getWeatherForCity(clean, forceRefresh = false)
        return result.map { report ->
            _weatherStates.update {
                it + (clean to WeatherLoadState.Success(report)) + (report.city to WeatherLoadState.Success(report))
            }
            val unitIsImperial = _userProfile.value.temperatureUnit == "IMPERIAL"
            val tempStr = if (unitIsImperial) {
                "${((report.tempCelsius * 9 / 5) + 32).toInt()}°F"
            } else {
                "${report.tempCelsius.toInt()}°C"
            }
            "${report.city}, ${report.country}: $tempStr, ${report.condition} (humidity ${report.humidityPercent}%, wind ${report.windKmh.toInt()} km/h)"
        }.onFailure { err ->
            val msg = err.localizedMessage ?: "Failed to fetch live weather for $clean."
            _weatherStates.update { it + (clean to WeatherLoadState.Error(msg)) }
        }
    }

    suspend fun searchDestinationForLiveBridge(query: String): Result<String> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Destination query cannot be empty."))
        }
        val res = geospatialRepo.searchDestinations(clean, limit = 5)
        return res.mapCatching { results ->
            _destinationSearchResults.value = results
            val first = results.firstOrNull()
                ?: throw IllegalStateException("No matching geographic locations found for \"$clean\".")
            _mapCenter.value = GeoCoordinate(first.latitude, first.longitude)
            _activeRoute.value = null
            "Found ${first.name} (${first.country}) at ${"%.3f".format(first.latitude)}, ${"%.3f".format(first.longitude)}"
        }
    }

    suspend fun addItinerarySlotFromLive(
        title: String,
        location: String,
        timeRange: String,
        category: String,
        costUsd: Double,
        dayNumber: Int
    ): Result<String> {
        val cleanTitle = title.trim().ifBlank { "Live Concierge Note" }
        val cleanLocation = location.trim().ifBlank { _wizardState.value.destination }
        val id = "slot_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val nextSeq = _itinerarySlots.value.size + 1
        val cleanCost = costUsd.coerceAtLeast(0.0)
        val newSlot = ItinerarySlot(
            id = id,
            timeRange = timeRange.ifBlank { "Flexible Window" },
            title = cleanTitle,
            location = cleanLocation,
            type = category.ifBlank { "Activity" },
            durationMinutes = 90,
            routeSequence = nextSeq,
            calSyncStatus = "Cloud Synced",
            dayNumber = dayNumber.coerceIn(1, 30),
            latitude = null,
            longitude = null,
            notes = "Added via ALLORA LIVE",
            estimatedCostUsd = cleanCost
        )

        val tripId = ensureActiveTripId()
            ?: return Result.failure(IllegalStateException("Could not create or load an active trip in Firestore."))

        val saveRes = firestoreRepo.upsertItinerarySlot(
            tripId = tripId,
            slot = ItinerarySlotDoc(
                id = id,
                tripId = tripId,
                dayNumber = newSlot.dayNumber.toLong(),
                timeRange = newSlot.timeRange,
                title = newSlot.title,
                location = newSlot.location,
                category = newSlot.type,
                durationMinutes = 90L,
                routeSequence = nextSeq.toLong(),
                notes = "Added via ALLORA LIVE",
                estimatedCostUsd = cleanCost,
                calSyncStatus = "Cloud Synced"
            )
        )
        return saveRes.map {
            _itinerarySlots.update { it + newSlot }
            _itineraryMeta.update { it + (id to ItineraryMeta(cleanCost, "Added via ALLORA LIVE")) }
            "Added \"$cleanTitle\" to your itinerary ($cleanLocation)."
        }
    }

    suspend fun createTripFromLive(
        destination: String,
        days: Int,
        budgetUsd: Double,
        style: String
    ): Result<String> {
        val cleanDest = destination.trim().ifBlank { "Tokyo, Japan" }
        val safeDays = days.coerceIn(1, 10)
        val generated = aiService.generateStructuredItinerary(
            destination = cleanDest,
            startDate = _wizardState.value.startDate,
            endDate = "$safeDays days",
            travellers = _wizardState.value.travellersCount,
            style = style.ifBlank { "Curated Discovery" },
            budgetUsd = budgetUsd.coerceAtLeast(300.0),
            transport = _wizardState.value.transport,
            lodgingType = _wizardState.value.lodgingType,
            activities = _wizardState.value.activities
        ).getOrElse { return Result.failure(it) }

        val tripId = firestoreRepo.createTrip(
            TripPlanDoc(
                title = generated.title,
                destination = cleanDest,
                startDate = _wizardState.value.startDate,
                endDate = "$safeDays days",
                travellersCount = _wizardState.value.travellersCount.toLong(),
                budgetUsd = budgetUsd.coerceAtLeast(300.0),
                style = style.ifBlank { "Curated Discovery" },
                status = "PLANNED"
            )
        ).getOrElse { return Result.failure(it) }

        val slotDocs = generated.slots.map { slot ->
            ItinerarySlotDoc(
                id = slot.id,
                tripId = tripId,
                dayNumber = slot.dayNumber.toLong(),
                timeRange = slot.timeRange,
                title = slot.title,
                location = slot.location,
                latitude = slot.latitude,
                longitude = slot.longitude,
                category = slot.type,
                durationMinutes = slot.durationMinutes.toLong(),
                routeSequence = slot.routeSequence.toLong(),
                notes = slot.notes,
                estimatedCostUsd = slot.estimatedCostUsd,
                calSyncStatus = "Cloud Synced"
            )
        }
        firestoreRepo.saveAllItinerarySlots(tripId, slotDocs).getOrElse { return Result.failure(it) }
        selectTrip(tripId)
        _itinerarySlots.value = generated.slots
        _wizardState.update {
            it.copy(
                destination = cleanDest,
                budgetUsd = budgetUsd.coerceAtLeast(300.0),
                generatedTitle = generated.title,
                generatedSummary = generated.summary
            )
        }
        return Result.success("Created trip \"${generated.title}\" (${generated.slots.size} stops) in Trips.")
    }

    // ---- PART 1: Bookings, Holds, Approval & Cancellation Persistence ----

    fun requestFlightHold(offer: FlightOffer) {
        localRepo.requestBookingHold(offer)
        addNotification(
            title = "Flight Hold Queued",
            message = "${offer.airline} ${offer.flightNumber} ($${offer.priceUsd.toInt()}) awaiting Shield review.",
            category = "Approval"
        )
    }

    fun requestLodgingHold(offer: LodgingOffer) {
        localRepo.requestLodgingHold(offer)
        addNotification(
            title = "Hotel Hold Queued",
            message = "${offer.name} ($${(offer.pricePerNightUsd * 2).toInt()} for 2 nights) awaiting Shield review.",
            category = "Approval"
        )
    }

    fun requestDiningHold(
        restaurantName: String,
        guests: Int,
        timeSlot: String,
        depositUsd: Double,
        address: String
    ) {
        localRepo.requestCustomHold(
            title = "Dining Hold Request ($restaurantName)",
            category = "Dining Reservation",
            merchantOrProvider = "$restaurantName (Partner API Required)",
            amountUsd = depositUsd,
            details = "$guests guests • $timeSlot • $address",
            providerPolicy = "Staged hold only — restaurant partner integration required for live table lock.",
            expiresInMinutes = 45
        )
        addNotification(
            title = "Dining Hold Queued",
            message = "$restaurantName ($guests guests at $timeSlot) staged in Shield Approval.",
            category = "Approval"
        )
    }

    fun requestActivityHold(
        experienceTitle: String,
        guests: Int,
        sessionWindow: String,
        pricePerGuestUsd: Double,
        location: String
    ) {
        val total = pricePerGuestUsd * guests.coerceAtLeast(1)
        localRepo.requestCustomHold(
            title = "Experience Hold ($experienceTitle)",
            category = "Excursion & Activity",
            merchantOrProvider = "$experienceTitle (Partner API Required)",
            amountUsd = total,
            details = "$guests guest(s) • $sessionWindow • $location",
            providerPolicy = "Staged hold only — excursion partner confirmation required before execution.",
            expiresInMinutes = 90
        )
        addNotification(
            title = "Activity Hold Queued",
            message = "$experienceTitle ($${total.toInt()}) staged in Approval Shield.",
            category = "Approval"
        )
    }

    fun requestCarRentalHold(offer: CarRentalOffer, days: Int, includeInsurance: Boolean) {
        val total = (offer.dailyRateUsd + if (includeInsurance) offer.insurancePerDayUsd else 0.0) * days
        val nowMs = System.currentTimeMillis()
        val ref = "ALR-${UUID.randomUUID().toString().take(4).uppercase()}-CAR"
        val docId = "car_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val title = "${offer.modelName} ($days days)"
        val digest = SecurityUtils.sha256Hex(
            SecurityUtils.buildBookingAuditPayload(
                userId = currentUserId,
                referenceCode = ref,
                bookingType = "CAR_RENTAL",
                title = title,
                amountUsd = total,
                createdAtMs = nowMs
            )
        )

        addNotification(
            title = "Vehicle Hold Saved",
            message = "$title ($${total.toInt()}) saved as a HELD request.",
            category = "Approval"
        )

        viewModelScope.launch {
            database.bookingRecordDao().upsertBooking(
                BookingRecord(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = ref,
                    bookingType = "CAR_RENTAL",
                    title = title,
                    providerSource = offer.providerSource,
                    providerPnr = null,
                    originOrLocation = offer.pickupHub,
                    destination = null,
                    startTimestamp = nowMs,
                    endTimestamp = nowMs + days * 86_400_000L,
                    totalAmountUsd = total,
                    status = "HELD",
                    sha256AuditHash = digest
                )
            )
            firestoreRepo.createBooking(
                BookingDoc(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = ref,
                    bookingType = "CAR_RENTAL",
                    title = title,
                    providerSource = offer.providerSource,
                    providerPnr = null,
                    originOrLocation = offer.pickupHub,
                    totalAmountUsd = total,
                    status = "HELD",
                    sha256AuditHash = digest
                )
            ).onFailure { err ->
                _cloudSyncError.value = err.localizedMessage ?: "Failed to sync vehicle hold to Firestore."
            }
        }
    }

    fun approveRequest(requestId: String) {
        val req = localRepo.approvalRequests.value.firstOrNull { it.id == requestId } ?: return
        val newTx = localRepo.approveRequest(requestId, userId = currentUserId) ?: return
        val docId = "bk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val safeStatus = if (req.isBookableWithProvider) "USER_APPROVED" else "NOT_BOOKABLE"
        val nowMs = System.currentTimeMillis()

        viewModelScope.launch {
            val bookingType = when {
                req.category.contains("Flight", ignoreCase = true) -> "FLIGHT"
                req.category.contains("Accommodation", ignoreCase = true) || req.category.contains("Hotel", ignoreCase = true) -> "HOTEL"
                req.category.contains("Dining", ignoreCase = true) -> "DINING"
                else -> "ACTIVITY"
            }
            database.bookingRecordDao().upsertBooking(
                BookingRecord(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = newTx.referenceCode,
                    bookingType = bookingType,
                    title = req.title,
                    providerSource = req.merchantOrProvider,
                    providerPnr = null,
                    originOrLocation = req.details.take(60),
                    destination = null,
                    startTimestamp = nowMs,
                    endTimestamp = null,
                    totalAmountUsd = req.amountUsd,
                    status = safeStatus,
                    sha256AuditHash = newTx.sha256Hash
                )
            )
            firestoreRepo.createBooking(
                BookingDoc(
                    id = docId,
                    userId = currentUserId,
                    referenceCode = newTx.referenceCode,
                    bookingType = bookingType,
                    title = req.title,
                    providerSource = req.merchantOrProvider,
                    providerPnr = null,
                    originOrLocation = req.details.take(60),
                    totalAmountUsd = req.amountUsd,
                    status = safeStatus,
                    sha256AuditHash = newTx.sha256Hash
                )
            ).onFailure { err ->
                _cloudSyncError.value = err.localizedMessage ?: "Failed to persist approved hold to Firestore."
            }
        }
        addNotification(
            title = if (req.isBookableWithProvider) "Hold Authorized • ${newTx.referenceCode}" else "Hold Staged • ${newTx.referenceCode}",
            message = if (req.isBookableWithProvider) {
                "${req.title} approved and queued for backend provider confirmation."
            } else {
                "${req.title} recorded as NOT_BOOKABLE (partner API credentials required for issuance)."
            },
            category = "Booking"
        )
    }

    fun rejectRequest(requestId: String) {
        val req = localRepo.approvalRequests.value.firstOrNull { it.id == requestId }
        localRepo.rejectRequest(requestId)
        if (req != null) {
            addNotification(
                title = "Hold Declined",
                message = "${req.title} was declined. Zero charge executed.",
                category = "Security"
            )
        }
    }

    fun cancelConfirmedBooking(referenceCode: String, remoteBookingId: String?) {
        _cancelledRefs.update { it + referenceCode }
        localRepo.markLedgerCancelled(referenceCode)

        val resolvedRemoteId = remoteBookingId
            ?: _remoteBookings.value.firstOrNull { it.referenceCode == referenceCode }?.id

        viewModelScope.launch {
            database.bookingRecordDao().updateStatusByReference(
                userId = currentUserId,
                referenceCode = referenceCode,
                status = "CANCELLED"
            )
            if (!resolvedRemoteId.isNullOrBlank()) {
                firestoreRepo.updateBookingStatus(resolvedRemoteId, "CANCELLED")
                    .onFailure { err ->
                        _cloudSyncError.value = err.localizedMessage ?: "Failed to persist cancellation to Firestore."
                    }
            }
        }
        addNotification(
            title = "Cancellation Recorded",
            message = "Reference $referenceCode marked as CANCELLED in local vault and cloud.",
            category = "Booking"
        )
    }

    fun isItemSaved(title: String, itemType: String): Boolean {
        return _savedItems.value.any { it.title == title && it.itemType == itemType }
    }

    fun toggleSavedItem(
        itemType: String,
        title: String,
        subtitle: String,
        category: String,
        priceLabel: String,
        rating: Double
    ) {
        val existing = _savedItems.value.firstOrNull { it.title == title && it.itemType == itemType }
        viewModelScope.launch {
            if (existing != null) {
                firestoreRepo.deleteSavedItem(existing.id)
            } else {
                firestoreRepo.saveItem(
                    SavedItemDoc(
                        itemType = itemType,
                        title = title,
                        subtitle = subtitle,
                        category = category,
                        priceLabel = priceLabel,
                        rating = rating
                    )
                )
            }
        }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
        if (mode != AppearanceMode.SYSTEM) {
            val dark = (mode == AppearanceMode.DARK)
            _darkGlassTheme.value = dark
            if (hasLoadedServerProfile) {
                updateUserProfile(_userProfile.value.copy(darkGlassTheme = dark))
            }
        }
        viewModelScope.launch {
            appearanceStore?.setAppearanceModeForUser(currentUserId, mode)
        }
    }

    fun setDarkGlassTheme(enabled: Boolean) {
        val mode = if (enabled) AppearanceMode.DARK else AppearanceMode.LIGHT
        _darkGlassTheme.value = enabled
        setAppearanceMode(mode)
        updateUserProfile(_userProfile.value.copy(darkGlassTheme = enabled))
    }

    fun setReducedMotion(enabled: Boolean) {
        _reducedMotion.value = enabled
    }

    fun updateUserProfile(updated: UserProfileDoc) {
        _userProfile.value = updated
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existingLocal = database.userPreferenceDao().getPreferences(currentUserId)
                database.userPreferenceDao().upsertPreferences(
                    UserPreference(
                        userId = currentUserId,
                        displayName = updated.displayName,
                        email = _userEmail.value.ifBlank { existingLocal?.email.orEmpty() },
                        preferredCurrency = updated.preferredCurrency,
                        temperatureUnit = updated.temperatureUnit,
                        preferredCabinClass = updated.preferredCabinClass,
                        homeAirportIata = updated.homeAirportIata,
                        travelStyle = updated.travelStyle,
                        budgetTier = updated.budgetTier,
                        biometricThresholdUsd = updated.biometricThresholdUsd,
                        darkGlassTheme = updated.darkGlassTheme,
                        recentSearchesSerialized = existingLocal?.recentSearchesSerialized.orEmpty()
                    )
                )
            }
            firestoreRepo.upsertUserProfile(updated)
        }
    }

    fun clearLocalCacheAndResync() {
        viewModelScope.launch {
            database.bookingRecordDao().deleteBookingsForUser(currentUserId)
            addNotification(
                title = "Local Cache Cleared",
                message = "Room offline booking cache cleared for your account.",
                category = "System"
            )
        }
    }

    fun attachAutonomousActionBridge(bridge: AlloraAppActionBridge?) {
        taskEngine?.attachBridge(bridge)
    }

    fun getExternalConnectors(): List<ExternalConnectorDescriptor> =
        taskEngine?.getExternalConnectors().orEmpty()

    fun submitAutonomousGoal(goal: String, projectId: String? = null) {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return
        val hint = "${_userProfile.value.travelStyle}, budget tier ${_userProfile.value.budgetTier}, airport ${_userProfile.value.homeAirportIata}"
        viewModelScope.launch {
            runCatching {
                taskEngine?.submitGoal(
                    goal = trimmed,
                    userProfileHint = hint,
                    startImmediately = true,
                    projectId = projectId
                )
            }.onFailure { err ->
                Log.w(TAG, "Failed submitting autonomous goal: ${err.message}")
            }
        }
    }

    fun getRecentChatHistoryPairs(): List<Pair<Boolean, String>> {
        val tid = _activeThreadId.value
        val currentMsgs = _richThreads.value.firstOrNull { it.id == tid }?.messages.orEmpty()
        return ConversationSerializer.normalizeHistoryPairs(
            currentMsgs
                .filter { !it.isError && !it.isStreaming && it.plainText.isNotBlank() && !it.id.startsWith("msg-w") }
                .map { it.isUser to it.plainText }
                .takeLast(10)
        )
    }

    fun appendVoiceExchangeToActiveThread(userText: String, assistantText: String) {
        val cleanU = userText.trim()
        val cleanA = assistantText.trim()
        if (cleanU.isBlank() || cleanA.isBlank()) return
        val tid = _activeThreadId.value
        val now = System.currentTimeMillis()
        val uMsg = AlloraRichMessage(
            id = "msg-vu-${UUID.randomUUID().toString().take(8)}",
            isUser = true,
            plainText = cleanU,
            blocks = listOf(ChatBlock.Text(id = "blk_vu_${now % 10000}", content = cleanU)),
            timestampLabel = "Voice",
            createdAtMs = now,
            isStreaming = false
        )
        val aMsg = AlloraRichMessage(
            id = "msg-va-${UUID.randomUUID().toString().take(8)}",
            isUser = false,
            plainText = cleanA,
            blocks = listOf(ChatBlock.Text(id = "blk_va_${now % 10000}", content = cleanA)),
            timestampLabel = "Voice",
            createdAtMs = now + 1,
            isStreaming = false
        )
        _richThreads.update { list ->
            list.map { thread ->
                if (thread.id != tid) thread else thread.copy(
                    updatedAtMs = now,
                    messages = thread.messages + uMsg + aMsg
                )
            }
        }
        persistRichThreadsNow()
    }

    fun saveMemoryEntry(
        category: MemoryCategory,
        title: String,
        content: String,
        retentionDays: Int = MemoryService.DEFAULT_RETENTION_DAYS
    ) {
        workspaceController?.saveMemory(category, title, content, userApproved = true, retentionDays = retentionDays)
    }

    fun updateMemoryEntry(
        memoryId: String,
        title: String,
        content: String,
        category: MemoryCategory,
        userApproved: Boolean,
        retentionDays: Int
    ) {
        workspaceController?.updateMemory(memoryId, title, content, category, userApproved, retentionDays)
    }

    fun deleteMemoryEntry(memoryId: String) {
        workspaceController?.deleteMemory(memoryId)
    }

    fun purgeExpiredMemories() {
        workspaceController?.purgeExpiredMemories()
    }

    fun createProject(title: String, description: String, tags: List<String> = emptyList()) {
        workspaceController?.createProject(title, description, tags, linkedThreadId = _activeThreadId.value)
    }

    fun updateProjectStatus(projectId: String, status: ProjectStatus, progressPercent: Int) {
        workspaceController?.updateProject(projectId, status = status, progressPercent = progressPercent)
    }

    fun linkTaskToProject(projectId: String, taskId: String) {
        workspaceController?.linkTaskToProject(projectId, taskId)
    }

    fun linkFileToProject(projectId: String, fileId: String) {
        workspaceController?.linkFileToProject(projectId, fileId)
    }

    fun linkActiveThreadToProject(projectId: String) {
        workspaceController?.updateProject(projectId, linkedThreadId = _activeThreadId.value)
    }

    fun deleteProject(projectId: String) {
        workspaceController?.deleteProject(projectId)
    }

    fun updatePersonalityProfile(
        presetMode: PersonalityPresetMode? = null,
        warmthLevel: Int? = null,
        concisenessLevel: Int? = null,
        proactivityLevel: Int? = null,
        explanationTransparency: Boolean? = null,
        voiceConsistencyEnabled: Boolean? = null,
        customSignatureNote: String? = null
    ) {
        workspaceController?.updatePersonality(
            presetMode = presetMode,
            warmthLevel = warmthLevel,
            concisenessLevel = concisenessLevel,
            proactivityLevel = proactivityLevel,
            explanationTransparency = explanationTransparency,
            voiceConsistencyEnabled = voiceConsistencyEnabled,
            customSignatureNote = customSignatureNote
        )
    }

    fun submitTaskClarification(taskId: String, clarification: String) {
        viewModelScope.launch {
            taskEngine?.provideMissingInput(taskId, clarification)
        }
    }

    fun selectAutonomousTask(taskId: String) {
        taskEngine?.selectTask(taskId)
    }

    fun pauseAutonomousTask(taskId: String) {
        viewModelScope.launch { taskEngine?.pauseTask(taskId) }
    }

    fun resumeAutonomousTask(taskId: String) {
        viewModelScope.launch { taskEngine?.resumeTask(taskId) }
    }

    fun approveAutonomousStep(taskId: String, stepId: String) {
        viewModelScope.launch { taskEngine?.approvePendingStep(taskId, stepId) }
    }

    fun rejectAutonomousStep(taskId: String, stepId: String) {
        viewModelScope.launch { taskEngine?.rejectPendingStep(taskId, stepId) }
    }

    fun notifyTaskPermissionsUpdated(taskId: String) {
        viewModelScope.launch { taskEngine?.notifyPermissionUpdated(taskId) }
    }

    fun cancelAutonomousTask(taskId: String) {
        viewModelScope.launch { taskEngine?.cancelTask(taskId) }
    }

    fun stopAllAutonomousActions() {
        viewModelScope.launch { taskEngine?.stopAllActions() }
    }

    fun scheduleBackgroundAutonomousTask(context: Context, taskId: String) {
        taskEngine?.scheduleDeferrableWork(context, taskId)
    }

    fun deleteAutonomousTask(taskId: String) {
        viewModelScope.launch { taskEngine?.deleteTaskFromHistory(taskId) }
    }

    fun deleteWorkspaceNote(noteId: String) {
        viewModelScope.launch { taskEngine?.deleteWorkspaceNote(noteId) }
    }

    suspend fun prepareForSignOut() {
        activeAiJob?.cancel()
        landmarkAnalysisJob?.cancel()
        onStopTrackingService?.invoke()
        taskEngine?.haltAllExecutionForSignOut()
        workspaceController?.prepareForSignOut()
        stopFirestoreObservers()
        withContext(Dispatchers.IO) {
            database.bookingRecordDao().deleteBookingsForUser(currentUserId)
            database.userPreferenceDao().deletePreferences(currentUserId)
        }
    }

    fun setAiSelectionMode(mode: AiSelectionMode) {
        val updated = aiRequestManager.userPreferences.value.copy(selectionMode = mode)
        aiRequestManager.updateUserPreferences(updated)
        if (mode != AiSelectionMode.MANUAL_MODE) {
            _manualModeBlockedNotice.value = null
        }
        persistAiPreferences(updated)
    }

    fun setPreferredAiModel(modelId: String) {
        val updated = aiRequestManager.userPreferences.value.copy(preferredModelId = modelId)
        aiRequestManager.updateUserPreferences(updated)
        persistAiPreferences(updated)
    }

    fun setManualAiModel(modelId: String) {
        val updated = aiRequestManager.userPreferences.value.copy(
            selectionMode = AiSelectionMode.MANUAL_MODE,
            manualModelId = modelId
        )
        aiRequestManager.updateUserPreferences(updated)
        _manualModeBlockedNotice.value = null
        persistAiPreferences(updated)
    }

    fun setAutoSwitchOnFailure(enabled: Boolean) {
        val updated = aiRequestManager.userPreferences.value.copy(autoSwitchOnFailure = enabled)
        aiRequestManager.updateUserPreferences(updated)
        if (enabled) {
            _manualModeBlockedNotice.value = null
        }
        persistAiPreferences(updated)
    }

    fun setAiModelEnabled(modelId: String, enabled: Boolean) {
        aiRequestManager.setModelEnabled(modelId, enabled)
    }

    fun resetAiModelCircuit(modelId: String) {
        aiRequestManager.resetModelCircuit(modelId)
    }

    fun acceptRecommendedManualFallback() {
        val notice = _manualModeBlockedNotice.value ?: return
        val fallbackId = notice.recommendedFallbackId
        val updated = if (!fallbackId.isNullOrBlank()) {
            aiRequestManager.userPreferences.value.copy(
                selectionMode = AiSelectionMode.PREFERRED_MODEL,
                preferredModelId = fallbackId,
                autoSwitchOnFailure = true
            )
        } else {
            aiRequestManager.userPreferences.value.copy(
                selectionMode = AiSelectionMode.AUTOMATIC,
                autoSwitchOnFailure = true
            )
        }
        aiRequestManager.updateUserPreferences(updated)
        _manualModeBlockedNotice.value = null
        persistAiPreferences(updated)
    }

    fun dismissManualModeBlockedNotice() {
        _manualModeBlockedNotice.value = null
    }

    private fun persistAiPreferences(prefs: AiUserPreferences) {
        if (currentUserId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                firestoreRepo.saveAiUserPreferences(
                    AiPreferencesDoc(
                        id = "default",
                        userId = currentUserId,
                        selectionMode = prefs.selectionMode.name,
                        preferredModelId = prefs.preferredModelId,
                        manualModelId = prefs.manualModelId,
                        autoSwitchOnFailure = prefs.autoSwitchOnFailure
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeAiJob?.cancel()
        landmarkAnalysisJob?.cancel()
        taskEngine?.attachBridge(null)
        aiRequestManager.attachTelemetrySink(null)
        stopFirestoreObservers()
    }

    private fun serializeRecentSearches(searches: List<String>): String =
        searches.map { it.replace("\n", " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .joinToString("\n")

    private fun deserializeRecentSearches(raw: String): List<String> =
        raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)

    fun markAllNotificationsRead() {
        _notifications.update { list -> list.map { it.copy(isUnread = false) } }
    }

    fun computeBudgetBreakdowns(): List<BudgetCategoryBreakdown> {
        val activeTx = ledgerTransactions.value.filter {
            it.referenceCode !in _cancelledRefs.value && !it.status.contains("CANCELLED", true)
        }
        val flightActual = activeTx.filter { it.category.contains("Flight", true) }.sumOf { it.amountUsd }
        val lodgingActual = activeTx.filter {
            it.category.contains("Lodg", true) || it.category.contains("Hotel", true) || it.category.contains("Accomm", true)
        }.sumOf { it.amountUsd }
        val diningActual = activeTx.filter { it.category.contains("Dining", true) }.sumOf { it.amountUsd }
        val itineraryExtra = _itineraryMeta.value.values.sumOf { it.estimatedCostUsd }

        val target = _wizardState.value.budgetUsd.coerceAtLeast(1000.0)
        return listOf(
            BudgetCategoryBreakdown("Flights", target * 0.35, flightActual),
            BudgetCategoryBreakdown("Lodging", target * 0.40, lodgingActual),
            BudgetCategoryBreakdown("Dining", target * 0.15, diningActual),
            BudgetCategoryBreakdown("Activities & Transit", target * 0.10, itineraryExtra)
        )
    }

    private fun addNotification(title: String, message: String, category: String) {
        val item = AlloraNotificationItem(
            id = "notif-${UUID.randomUUID().toString().take(6)}",
            title = title,
            message = message,
            timestamp = "Just now",
            category = category,
            isUnread = true
        )
        _notifications.update { list -> listOf(item) + list }
    }

    private companion object {
        const val TAG = "AlloraViewModel"
        const val DUPLICATE_SUBMISSION_WINDOW_MS = 1800L
    }
}
