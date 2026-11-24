package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.data.model.DestinationSearchResult
import com.example.data.model.GeoCoordinate
import com.example.data.model.PointOfInterest
import com.example.data.model.RouteResult
import com.example.location.LocationTrackingState
import com.example.location.TravelLocationService
import com.example.location.TravelLocationTracker
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassLoader
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private enum class PendingLocationAction {
    NONE,
    SINGLE_FIX,
    TOGGLE_TRACKING
}

@Composable
fun FullScreenMapScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pois by viewModel.poiList.collectAsState()
    val searchResults by viewModel.destinationSearchResults.collectAsState()
    val mapCenter by viewModel.mapCenter.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val mapLoading by viewModel.mapLoading.collectAsState()
    val mapError by viewModel.mapError.collectAsState()
    val locationPermissionDenied by viewModel.locationPermissionDenied.collectAsState()
    val trackingState by viewModel.locationTrackingState.collectAsState()

    var pendingAction by remember { mutableStateOf(PendingLocationAction.NONE) }
    var activeFilter by remember { mutableStateOf("All") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            when (pendingAction) {
                PendingLocationAction.TOGGLE_TRACKING -> {
                    TravelLocationService.startTracking(context)
                }
                else -> {
                    viewModel.requestFreshLocationFix(activeFilter)
                }
            }
        } else {
            viewModel.onLocationPermissionDenied()
        }
        pendingAction = PendingLocationAction.NONE
    }

    FullScreenMapContent(
        pois = pois,
        searchResults = searchResults,
        mapCenter = mapCenter,
        userLocation = trackingState.currentLocation ?: userLocation,
        activeRoute = activeRoute,
        mapLoading = mapLoading,
        mapError = mapError,
        locationPermissionDenied = locationPermissionDenied,
        trackingState = trackingState,
        onBack = onBack,
        onSearchDestinations = { viewModel.searchMapDestinations(it) },
        onSelectDestination = { dest, filter -> viewModel.selectDestinationOnMap(dest, filter) },
        onRefreshPois = { filter ->
            activeFilter = filter
            viewModel.refreshNearbyPois(category = filter)
        },
        onComputeRoute = { poi, profile -> viewModel.computeRouteToPoi(poi, profile) },
        onClearRoute = { viewModel.clearActiveRoute() },
        onClearError = { viewModel.clearMapError() },
        onRequestMyLocation = { filter ->
            activeFilter = filter
            if (TravelLocationTracker.hasAnyLocationPermission(context)) {
                viewModel.requestFreshLocationFix(filter)
            } else {
                pendingAction = PendingLocationAction.SINGLE_FIX
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        },
        onToggleTracking = {
            if (trackingState.isTracking) {
                TravelLocationService.stopTracking(context)
            } else if (TravelLocationTracker.hasAnyLocationPermission(context)) {
                TravelLocationService.startTracking(context)
            } else {
                pendingAction = PendingLocationAction.TOGGLE_TRACKING
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        },
        onAddStop = { poi ->
            viewModel.addItinerarySlot(
                title = poi.name,
                location = poi.address,
                timeRange = "Flexible Slot",
                category = if (poi.category == "Restaurant") "Dining" else "Sights",
                costUsd = 0.0,
                latitude = poi.lat,
                longitude = poi.lng
            )
            onNavigateSub(SubDestination.ITINERARY)
        },
        onOpenCamera = { onNavigateSub(SubDestination.CAMERA) },
        modifier = modifier
    )
}

@Composable
fun FullScreenMapContent(
    pois: List<PointOfInterest>,
    searchResults: List<DestinationSearchResult>,
    mapCenter: GeoCoordinate,
    userLocation: GeoCoordinate?,
    activeRoute: RouteResult?,
    mapLoading: Boolean,
    mapError: String?,
    locationPermissionDenied: Boolean,
    trackingState: LocationTrackingState,
    onBack: () -> Unit,
    onSearchDestinations: (String) -> Unit,
    onSelectDestination: (DestinationSearchResult, String) -> Unit,
    onRefreshPois: (String) -> Unit,
    onComputeRoute: (PointOfInterest, String) -> Unit,
    onClearRoute: () -> Unit,
    onClearError: () -> Unit,
    onRequestMyLocation: (String) -> Unit,
    onToggleTracking: () -> Unit,
    onAddStop: (PointOfInterest) -> Unit,
    onOpenCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRobolectric = remember { Build.FINGERPRINT.equals("robolectric", ignoreCase = true) }

    var filter by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var selectedPoi by remember { mutableStateOf<PointOfInterest?>(null) }
    var routeProfile by remember { mutableStateOf("driving") }
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var keyBannerDismissed by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(pois) {
        if (selectedPoi == null || pois.none { it.id == selectedPoi?.id }) {
            selectedPoi = pois.firstOrNull()
        }
    }

    val visiblePois = remember(pois, filter, search, searchResults) {
        pois.filter { poi ->
            (filter == "All" || poi.category.equals(filter, true)) &&
                (search.isBlank() || poi.name.contains(search, true) || poi.address.contains(search, true) || searchResults.isNotEmpty())
        }
    }

    val hasLocationPermission = remember(userLocation, trackingState.isTracking, locationPermissionDenied) {
        TravelLocationTracker.hasAnyLocationPermission(context)
    }

    val mapsKeyMissing = remember {
        val k = BuildConfig.MAPS_API_KEY
        k.isBlank() || k == "YOUR_GOOGLE_MAPS_API_KEY"
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(mapCenter.latitude, mapCenter.longitude),
            13.0f
        )
    }

    if (!isRobolectric) {
        LaunchedEffect(mapCenter) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(mapCenter.latitude, mapCenter.longitude),
                    13.5f
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glass.canvasBase)
    ) {
        if (isRobolectric) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("map_preview_surface"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Google Maps Viewport (${String.format(Locale.US, "%.4f, %.4f", mapCenter.latitude, mapCenter.longitude)})",
                    style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                )
            }
        } else {
            val mapColorScheme = if (glass.isDark) {
                ComposeMapColorScheme.DARK
            } else {
                ComposeMapColorScheme.LIGHT
            }
            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("google_map_view"),
                cameraPositionState = cameraPositionState,
                mapColorScheme = mapColorScheme,
                properties = MapProperties(
                    isMyLocationEnabled = hasLocationPermission,
                    mapType = mapType
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    compassEnabled = true,
                    mapToolbarEnabled = false
                )
            ) {
                visiblePois.forEach { poi ->
                    val hue = when {
                        poi.id == selectedPoi?.id -> BitmapDescriptorFactory.HUE_AZURE
                        poi.category == "Hotel" -> BitmapDescriptorFactory.HUE_VIOLET
                        poi.category == "Restaurant" -> BitmapDescriptorFactory.HUE_ORANGE
                        else -> BitmapDescriptorFactory.HUE_CYAN
                    }
                    Marker(
                        state = MarkerState(position = LatLng(poi.lat, poi.lng)),
                        title = poi.name,
                        snippet = "${poi.category} • ${poi.address}",
                        icon = BitmapDescriptorFactory.defaultMarker(hue),
                        onClick = {
                            selectedPoi = poi
                            false
                        }
                    )
                }

                userLocation?.let { loc ->
                    Marker(
                        state = MarkerState(position = LatLng(loc.latitude, loc.longitude)),
                        title = if (trackingState.isTracking) "Live Position (${trackingState.providerLabel})" else "Your Location",
                        snippet = trackingState.accuracyMeters?.let { "Accuracy ±${it.roundToInt()} m" },
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                    )
                }

                val routePoints = activeRoute?.polyline.orEmpty()
                if (routePoints.size >= 2) {
                    Polyline(
                        points = routePoints.map { LatLng(it.latitude, it.longitude) },
                        color = glass.accentPrimary,
                        width = 11f
                    )
                }
            }
        }

        // Top Floating Glass Search, Tracking Status & Category Pills
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    material = GlassMaterial.CLEAR,
                    onClick = onBack,
                    modifier = Modifier.testTag("map_back_button")
                )
                GlassSearchBar(
                    query = search,
                    onQueryChange = { search = it },
                    material = GlassMaterial.CLEAR,
                    placeholder = "Search city or destination...",
                    onSubmit = {
                        if (search.isNotBlank()) {
                            onSearchDestinations(search)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                GlassCircleIconButton(
                    icon = Icons.Default.MyLocation,
                    contentDescription = "My Location",
                    material = GlassMaterial.CLEAR,
                    onClick = { onRequestMyLocation(filter) },
                    modifier = Modifier.testTag("map_location_button")
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val accLabel = trackingState.accuracyMeters?.let { " ±${it.roundToInt()}m" } ?: ""
                    GlassChip(
                        label = if (trackingState.isTracking) "Live GPS On$accLabel" else "Track Live",
                        selected = trackingState.isTracking,
                        selectedMaterial = GlassMaterial.PEARL,
                        leadingIcon = Icons.Default.GpsFixed,
                        onClick = onToggleTracking,
                        modifier = Modifier.testTag("map_live_tracking_chip")
                    )
                }
                item {
                    GlassChip(
                        label = when (mapType) {
                            MapType.SATELLITE -> "Satellite"
                            MapType.TERRAIN -> "Terrain"
                            else -> "Map"
                        },
                        selected = mapType != MapType.NORMAL,
                        leadingIcon = Icons.Default.Layers,
                        onClick = {
                            mapType = when (mapType) {
                                MapType.NORMAL -> MapType.TERRAIN
                                MapType.TERRAIN -> MapType.SATELLITE
                                else -> MapType.NORMAL
                            }
                        },
                        modifier = Modifier.testTag("map_layer_chip")
                    )
                }
                item {
                    GlassChip(
                        label = "Field Cam",
                        selected = false,
                        leadingIcon = Icons.Default.PhotoCamera,
                        onClick = onOpenCamera,
                        modifier = Modifier.testTag("map_camera_chip")
                    )
                }
                items(listOf("All", "Attraction", "Restaurant", "Hotel")) { f ->
                    GlassChip(
                        label = f,
                        selected = filter == f,
                        onClick = {
                            filter = f
                            onRefreshPois(f)
                        }
                    )
                }
            }

            if (searchResults.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(searchResults, key = { it.id }) { dest ->
                        val active = kotlin.math.abs(dest.latitude - mapCenter.latitude) < 0.01 &&
                            kotlin.math.abs(dest.longitude - mapCenter.longitude) < 0.01
                        GlassChip(
                            label = listOf(dest.name, dest.country).filter { it.isNotBlank() }.joinToString(", "),
                            selected = active,
                            leadingIcon = Icons.Default.LocationOn,
                            onClick = { onSelectDestination(dest, filter) }
                        )
                    }
                }
            }

            if (mapsKeyMissing && !keyBannerDismissed) {
                GlassCard(
                    material = GlassMaterial.FROSTED,
                    accentBorder = glass.statusWarning.copy(alpha = 0.55f),
                    contentPadding = PaddingValues(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("maps_api_key_notice")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.maps_key_missing_banner),
                            style = MaterialTheme.typography.labelSmall.copy(color = glass.textPrimary),
                            modifier = Modifier.weight(1f)
                        )
                        GlassCircleIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Dismiss Maps key notice",
                            material = GlassMaterial.CLEAR,
                            size = 30.dp,
                            onClick = { keyBannerDismissed = true }
                        )
                    }
                }
            }

            if (mapLoading) {
                GlassLoader(statusMessage = "Syncing places & routing...")
            }

            val combinedError = mapError ?: trackingState.errorMessage
            if (combinedError != null || locationPermissionDenied) {
                GlassCard(
                    material = GlassMaterial.DEEP,
                    accentBorder = glass.statusError.copy(alpha = 0.5f),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = combinedError ?: "Location permission denied. Search any destination manually.",
                            style = MaterialTheme.typography.labelMedium.copy(color = glass.textPrimary),
                            modifier = Modifier.weight(1f)
                        )
                        GlassCircleIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            material = GlassMaterial.CLEAR,
                            size = 32.dp,
                            onClick = onClearError
                        )
                    }
                }
            }
        }

        // Right-side Google Maps Camera Zoom Controls
        if (!isRobolectric) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassCircleIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "Zoom in",
                    material = GlassMaterial.FROSTED,
                    onClick = {
                        scope.launch {
                            cameraPositionState.animate(CameraUpdateFactory.zoomIn())
                        }
                    }
                )
                GlassCircleIconButton(
                    icon = Icons.Default.Remove,
                    contentDescription = "Zoom out",
                    material = GlassMaterial.FROSTED,
                    onClick = {
                        scope.launch {
                            cameraPositionState.animate(CameraUpdateFactory.zoomOut())
                        }
                    }
                )
            }
        }

        // Bottom Controls: Active Route Summary + POI Selector Strip + Selected POI Glass Card
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            activeRoute?.let { route ->
                val distKm = String.format(Locale.US, "%.1f km", route.distanceMeters / 1000.0)
                val durMin = (route.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
                GlassCard(
                    material = GlassMaterial.DEEP,
                    accentBorder = glass.statusSuccess.copy(alpha = 0.45f),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OSRM Route (${route.profile}) • $distKm • $durMin min",
                                style = MaterialTheme.typography.titleSmall.copy(color = glass.statusSuccess, fontWeight = FontWeight.Bold)
                            )
                            if (route.viaStreets.isNotEmpty()) {
                                Text(
                                    text = "Via ${route.viaStreets.take(3).joinToString(" → ")}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        GlassChip(label = "Clear", selected = false, onClick = onClearRoute)
                    }
                }
            }

            if (visiblePois.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visiblePois, key = { it.id }) { poi ->
                        val distLabel = poi.distanceKm?.let { " • ${String.format(Locale.US, "%.1f", it)} km" } ?: ""
                        GlassChip(
                            label = "${poi.name.take(18)}$distLabel",
                            selected = selectedPoi?.id == poi.id,
                            selectedMaterial = GlassMaterial.PEARL,
                            leadingIcon = when (poi.category) {
                                "Hotel" -> Icons.Default.Hotel
                                "Restaurant" -> Icons.Default.Restaurant
                                else -> Icons.Default.LocationOn
                            },
                            onClick = { selectedPoi = poi }
                        )
                    }
                }
            }

            val currentPoi = selectedPoi
            if (currentPoi != null) {
                val distText = currentPoi.distanceKm?.let { String.format(Locale.US, "%.2f km away", it) }
                    ?: String.format(Locale.US, "%.4f, %.4f", currentPoi.lat, currentPoi.lng)
                val poiIcon = when (currentPoi.category) {
                    "Hotel" -> Icons.Default.Hotel
                    "Restaurant" -> Icons.Default.Restaurant
                    else -> Icons.Default.Place
                }
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 26.dp,
                    material = GlassMaterial.DEEP
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassSurface(
                            modifier = Modifier.size(52.dp),
                            role = GlassSurfaceRole.CHIP,
                            material = GlassMaterial.CLEAR,
                            shape = RoundedCornerShape(15.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = poiIcon,
                                contentDescription = null,
                                tint = glass.textPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusGlassPill(label = "${currentPoi.category} • ${currentPoi.tag}", color = glass.textSecondary)
                                Text(
                                    text = distText,
                                    style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 12.sp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = currentPoi.name,
                                style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${currentPoi.address} • ${currentPoi.providerSource}",
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassChip(
                            label = if (routeProfile == "driving") "Drive" else "Walk",
                            selected = false,
                            onClick = {
                                routeProfile = if (routeProfile == "driving") "walking" else "driving"
                            }
                        )
                        GlassButton(
                            text = "Route",
                            onClick = { onComputeRoute(currentPoi, routeProfile) },
                            isPrimary = false,
                            material = GlassMaterial.CLEAR,
                            leadingIcon = Icons.Default.Directions,
                            modifier = Modifier.weight(1f)
                        )
                        GlassButton(
                            text = "Add Stop",
                            onClick = { onAddStop(currentPoi) },
                            isPrimary = true,
                            material = GlassMaterial.PEARL,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    material = GlassMaterial.DEEP,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Text(
                        text = "Search any city above or tap Refresh Nearby to explore landmarks on Google Maps.",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
            }
        }
    }
}
