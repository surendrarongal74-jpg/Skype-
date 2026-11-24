package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WeatherLoadState
import com.example.data.model.WeatherReport
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassBottomSheet
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassDestinationCard
import com.example.ui.components.GlassPhotoOfferCard
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import com.example.ui.viewmodel.DestinationSpotlight
import java.util.Locale
import kotlin.math.roundToInt

private data class DiningCuratedPlace(
    val id: String,
    val name: String,
    val neighborhood: String,
    val cuisine: String,
    val pricePerGuestUsd: Double,
    val rating: Double,
    val badge: String,
    val highlightDish: String,
    val icon: ImageVector = Icons.Default.Restaurant
)

private data class ExcursionOffer(
    val id: String,
    val title: String,
    val location: String,
    val category: String,
    val duration: String,
    val priceUsd: Double,
    val rating: Double,
    val summary: String,
    val icon: ImageVector = Icons.Default.Explore
)

@Composable
fun DiscoverScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val weatherStates by viewModel.weatherStates.collectAsState()
    val pois by viewModel.poiList.collectAsState()
    val hotels by viewModel.lodgingOffers.collectAsState()
    val profile by viewModel.userProfile.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedDestination by remember { mutableStateOf<DestinationSpotlight?>(null) }
    var selectedWeatherReport by remember { mutableStateOf<WeatherReport?>(null) }

    val categories = listOf("All", "Luxury", "Culture", "Nature", "City")
    val filteredDestinations = remember(query, selectedCategory) {
        viewModel.destinations.filter { dest ->
            (selectedCategory == "All" || dest.category.equals(selectedCategory, true)) &&
                (query.isBlank() || dest.city.contains(query, true) || dest.country.contains(query, true))
        }
    }

    val filteredPois = remember(query, pois) {
        pois.filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) || it.category.contains(query, true) }
    }

    val weatherRadarCities = remember(weatherStates) {
        val defaultOrder = viewModel.destinations.map { it.city }
        val extra = weatherStates.keys.filter { key ->
            defaultOrder.none { it.equals(key, ignoreCase = true) }
        }
        (defaultOrder + extra).distinctBy { it.lowercase() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Discover Your\nNext Journey",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Live Open-Meteo forecasts, OSM places & curated mobility",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
                GlassChip(
                    label = "Live Map",
                    selected = true,
                    leadingIcon = Icons.Default.Map,
                    onClick = { onNavigateSub(SubDestination.MAP) }
                )
            }
        }

        item {
            GlassSearchBar(
                query = query,
                onQueryChange = { query = it },
                material = GlassMaterial.CLEAR,
                placeholder = "Search Tokyo, any city forecast, OSM places...",
                onSubmit = {
                    val trimmed = query.trim()
                    if (trimmed.isNotBlank()) {
                        viewModel.loadWeatherForCity(trimmed, forceRefresh = true)
                    }
                }
            )
        }

        item {
            AlloraWrapRow(
                horizontalSpacing = 8.dp,
                verticalSpacing = 8.dp
            ) {
                categories.forEach { cat ->
                    GlassChip(
                        label = cat,
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat }
                    )
                }
            }
        }

        // Booking Vertical Entry Strip
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    GlassChip(
                        label = "Flights",
                        selected = false,
                        leadingIcon = Icons.Default.Flight,
                        onClick = { onNavigateSub(SubDestination.FLIGHTS) }
                    )
                }
                item {
                    GlassChip(
                        label = "Hotels",
                        selected = false,
                        leadingIcon = Icons.Default.Hotel,
                        onClick = { onNavigateSub(SubDestination.HOTELS) }
                    )
                }
                item {
                    GlassChip(
                        label = "Dining",
                        selected = false,
                        leadingIcon = Icons.Default.Restaurant,
                        onClick = { onNavigateSub(SubDestination.DINING) }
                    )
                }
                item {
                    GlassChip(
                        label = "Excursions",
                        selected = false,
                        leadingIcon = Icons.Default.Explore,
                        onClick = { onNavigateSub(SubDestination.ACTIVITIES) }
                    )
                }
                item {
                    GlassChip(
                        label = "Cars",
                        selected = false,
                        leadingIcon = Icons.Default.DirectionsCar,
                        onClick = { onNavigateSub(SubDestination.CARS) }
                    )
                }
                item {
                    GlassChip(
                        label = "Field Cam",
                        selected = false,
                        leadingIcon = Icons.Default.PhotoCamera,
                        onClick = { onNavigateSub(SubDestination.CAMERA) }
                    )
                }
            }
        }

        // Live Open-Meteo Weather Radar Strip (Loading / Success / Error + Retry)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Atmosphere & Weather Radar",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary)
                )
                if (query.trim().length >= 2) {
                    GlassChip(
                        label = "Check \"${query.trim().take(12)}\"",
                        selected = true,
                        leadingIcon = Icons.Default.WbSunny,
                        onClick = { viewModel.loadWeatherForCity(query.trim(), forceRefresh = true) }
                    )
                } else {
                    GlassChip(
                        label = "Refresh",
                        selected = false,
                        leadingIcon = Icons.Default.Refresh,
                        onClick = { viewModel.loadWeatherForFeaturedCities(forceRefresh = true) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(weatherRadarCities, key = { it }) { city ->
                    val loadState = weatherStates[city] ?: WeatherLoadState.Loading
                    when (loadState) {
                        is WeatherLoadState.Loading -> {
                            GlassCard(
                                modifier = Modifier.width(168.dp),
                                material = GlassMaterial.CLEAR,
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = glass.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(city, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Fetching Open-Meteo...",
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                                )
                            }
                        }
                        is WeatherLoadState.Error -> {
                            GlassCard(
                                modifier = Modifier
                                    .width(196.dp)
                                    .testTag("weather_error_${city.lowercase()}"),
                                material = GlassMaterial.CLEAR,
                                accentBorder = glass.statusError.copy(alpha = 0.45f),
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = glass.statusError, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(city, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = loadState.message,
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.statusError),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                GlassChip(
                                    label = "Retry",
                                    selected = false,
                                    leadingIcon = Icons.Default.Refresh,
                                    onClick = { viewModel.loadWeatherForCity(city, forceRefresh = true) }
                                )
                            }
                        }
                        is WeatherLoadState.Success -> {
                            val report = loadState.report
                            val tempDisplay = formatTemp(report.tempCelsius, profile.temperatureUnit)
                            val feelsDisplay = formatTemp(report.feelsLikeCelsius, profile.temperatureUnit)
                            GlassCard(
                                modifier = Modifier
                                    .width(184.dp)
                                    .testTag("weather_card_${report.city.lowercase()}"),
                                material = GlassMaterial.CLEAR,
                                onClick = { selectedWeatherReport = report },
                                contentPadding = PaddingValues(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.WbSunny, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = report.city,
                                            style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "UV ${report.uvIndex}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(tempDisplay, style = TabularNumberStyle.copy(fontSize = 22.sp, color = glass.textPrimary))
                                Text(
                                    text = "${report.condition} • Feels $feelsDisplay",
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${report.windKmh.toInt()} km/h ${report.windCardinal} • ${report.humidityPercent}% RH",
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Destinations
        item {
            Text(
                text = "Popular Destinations",
                style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary)
            )
        }

        if (filteredDestinations.isEmpty()) {
            item {
                GlassStateView(
                    title = "No matching destinations",
                    message = "Try clearing filters, searching on the live OpenStreetMap screen, or asking ALLORA AI.",
                    actionLabel = "Reset Filters",
                    onAction = {
                        query = ""
                        selectedCategory = "All"
                    }
                )
            }
        } else {
            items(filteredDestinations, key = { it.id }) { dest ->
                val weatherState = weatherStates[dest.city]
                val weatherStr = when (weatherState) {
                    is WeatherLoadState.Success -> "${formatTemp(weatherState.report.tempCelsius, profile.temperatureUnit)} • ${weatherState.report.condition}"
                    is WeatherLoadState.Error -> "Weather unavailable"
                    else -> "Syncing Open-Meteo..."
                }
                val isSaved = savedItems.any { it.title == dest.city && it.itemType == "DESTINATION" }
                GlassDestinationCard(
                    city = dest.city,
                    country = dest.country,
                    tagline = dest.tagline,
                    weatherSummary = weatherStr,
                    rating = dest.rating,
                    paletteSeed = dest.paletteSeed,
                    isSaved = isSaved,
                    priceEstimateLabel = "Flight from $${dest.estimatedFlightUsd.toInt()}",
                    onToggleSave = {
                        viewModel.toggleSavedItem(
                            itemType = "DESTINATION",
                            title = dest.city,
                            subtitle = dest.country,
                            category = dest.category,
                            priceLabel = "From $${dest.estimatedFlightUsd.toInt()}",
                            rating = dest.rating
                        )
                    },
                    onClick = {
                        selectedDestination = dest
                        if (weatherStates[dest.city] !is WeatherLoadState.Success) {
                            viewModel.loadWeatherForCity(dest.city)
                        }
                    }
                )
            }
        }

        // OpenStreetMap Overpass POIs & Nearby Dining
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby OpenStreetMap Places",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary)
                )
                Text(
                    text = "Open Map",
                    style = MaterialTheme.typography.labelLarge.copy(color = glass.textSecondary, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { onNavigateSub(SubDestination.MAP) }
                )
            }
        }

        if (filteredPois.isEmpty()) {
            item {
                GlassStateView(
                    title = "No OpenStreetMap places loaded",
                    message = "Open the Live Map or refresh Overpass POIs around your selected destination.",
                    actionLabel = "Refresh Nearby",
                    onAction = { viewModel.refreshNearbyPois() }
                )
            }
        } else {
            items(filteredPois, key = { it.id }) { poi ->
                val isDining = poi.category.equals("Restaurant", true)
                val isHotel = poi.category.equals("Hotel", true)
                val distText = poi.distanceKm?.let { String.format(Locale.US, "%.1f km • ", it) } ?: ""
                GlassPhotoOfferCard(
                    title = poi.name,
                    subtitle = "$distText${poi.address}",
                    priceLabel = poi.providerSource.replace("OpenStreetMap ", "OSM "),
                    ratingLabel = poi.rating?.toString() ?: poi.tag,
                    icon = when {
                        isDining -> Icons.Default.Restaurant
                        isHotel -> Icons.Default.Hotel
                        else -> Icons.Default.Place
                    },
                    badgeLabel = poi.tag,
                    isSampleEstimate = false,
                    isSaved = savedItems.any { it.title == poi.name },
                    onToggleSave = {
                        viewModel.toggleSavedItem(
                            itemType = "DESTINATION",
                            title = poi.name,
                            subtitle = poi.address,
                            category = poi.category,
                            priceLabel = poi.distanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "OSM POI",
                            rating = poi.rating ?: 4.7
                        )
                    },
                    onCardClick = { onNavigateSub(SubDestination.MAP) },
                    onActionClick = {
                        viewModel.addItinerarySlot(
                            title = poi.name,
                            location = poi.address,
                            timeRange = if (isDining) "07:00 PM – 09:00 PM" else "02:00 PM – 04:00 PM",
                            category = if (isDining) "Dining" else "Sights",
                            costUsd = 0.0,
                            latitude = poi.lat,
                            longitude = poi.lng
                        )
                        onNavigateSub(SubDestination.ITINERARY)
                    },
                    actionLabel = "Add Stop"
                )
            }
        }

        // Featured Hotels Preview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Signature Stays",
                    style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary)
                )
                Text(
                    text = "See all stays",
                    style = MaterialTheme.typography.labelLarge.copy(color = glass.textSecondary, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { onNavigateSub(SubDestination.HOTELS) }
                )
            }
        }

        items(hotels.take(2), key = { it.id }) { hotel ->
            val saved = savedItems.any { it.title == hotel.name && it.itemType == "HOTEL" }
            GlassPhotoOfferCard(
                title = hotel.name,
                subtitle = "${hotel.location} • ${hotel.roomType}",
                priceLabel = "$${hotel.pricePerNightUsd.toInt()}/nt",
                ratingLabel = "${hotel.rating}",
                icon = Icons.Default.Hotel,
                badgeLabel = "Hotel",
                isSampleEstimate = true,
                isSaved = saved,
                onToggleSave = {
                    viewModel.toggleSavedItem(
                        itemType = "HOTEL",
                        title = hotel.name,
                        subtitle = hotel.location,
                        category = "Stay",
                        priceLabel = "$${hotel.pricePerNightUsd.toInt()}/nt",
                        rating = hotel.rating
                    )
                },
                onCardClick = { onNavigateSub(SubDestination.HOTELS) },
                onActionClick = {
                    viewModel.requestLodgingHold(hotel)
                    onNavigateSub(SubDestination.APPROVALS)
                },
                actionLabel = "Hold 2 Nt"
            )
        }
    }

    selectedDestination?.let { dest ->
        GlassBottomSheet(onDismissRequest = { selectedDestination = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    material = GlassMaterial.CLEAR,
                    cornerRadius = 22.dp,
                    contentPadding = PaddingValues(16.dp)
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
                                material = GlassMaterial.FROSTED,
                                shape = CircleShape,
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null, tint = glass.textPrimary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                StatusGlassPill(label = dest.category, color = glass.textSecondary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${dest.city}, ${dest.country}",
                                    style = MaterialTheme.typography.headlineSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        Text(
                            text = "${dest.rating}★",
                            style = TabularNumberStyle.copy(color = glass.statusWarning, fontSize = 16.sp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(dest.tagline, style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Key Districts: ${dest.highlights}", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(12.dp))
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Est. Flight", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                            Text("$${dest.estimatedFlightUsd.toInt()}", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 18.sp))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Est. Sanctuary Stay", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                            Text("$${dest.estimatedHotelNightUsd.toInt()}/night", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 18.sp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(
                        text = "Search Flights",
                        onClick = {
                            selectedDestination = null
                            onNavigateSub(SubDestination.FLIGHTS)
                        },
                        isPrimary = false,
                        material = GlassMaterial.CLEAR,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = "Start Trip",
                        onClick = {
                            viewModel.updateWizard { it.copy(destination = "${dest.city}, ${dest.country}") }
                            selectedDestination = null
                            onNavigateSub(SubDestination.TRIP_WIZARD)
                        },
                        isPrimary = true,
                        material = GlassMaterial.PEARL,
                        leadingIcon = Icons.Default.AutoAwesome,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    selectedWeatherReport?.let { report ->
        WeatherDetailBottomSheet(
            report = report,
            temperatureUnit = profile.temperatureUnit,
            onRefresh = { viewModel.loadWeatherForCity(report.city, forceRefresh = true) },
            onDismiss = { selectedWeatherReport = null }
        )
    }
}

@Composable
private fun WeatherDetailBottomSheet(
    report: WeatherReport,
    temperatureUnit: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val firstDay = report.daily.firstOrNull()

    GlassBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    StatusGlassPill(label = "Open-Meteo Live • ${report.timezoneAbbreviation.ifBlank { report.timezone }}", color = glass.textSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = listOf(report.city, report.country).filter { it.isNotBlank() }.joinToString(", "),
                        style = MaterialTheme.typography.headlineSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Updated ${report.lastUpdatedDisplay}",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                    )
                }
                Text(
                    text = formatTemp(report.tempCelsius, temperatureUnit),
                    style = TabularNumberStyle.copy(fontSize = 34.sp, color = glass.textPrimary, fontWeight = FontWeight.Bold)
                )
            }

            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${report.condition} • Feels like ${formatTemp(report.feelsLikeCelsius, temperatureUnit)}",
                    style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    WeatherMetricMini(
                        icon = Icons.Default.WaterDrop,
                        label = "Humidity / Precip",
                        value = "${report.humidityPercent}% • ${String.format(Locale.US, "%.1f", report.precipitationMm)} mm"
                    )
                    WeatherMetricMini(
                        icon = Icons.Default.Air,
                        label = "Wind",
                        value = "${report.windKmh.toInt()} km/h ${report.windCardinal} (${report.windDirectionDegrees}°)"
                    )
                }
                if (firstDay?.sunriseIso != null && firstDay.sunsetIso != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WbTwilight, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sunrise ${firstDay.sunriseIso.substringAfter("T")} • Sunset ${firstDay.sunsetIso.substringAfter("T")}",
                            style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary)
                        )
                    }
                }
            }

            if (report.hourly.isNotEmpty()) {
                Text(
                    text = "Next 24 Hours",
                    style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(report.hourly.take(24), key = { it.timeIso }) { pt ->
                        GlassCard(
                            modifier = Modifier.width(96.dp),
                            material = GlassMaterial.CLEAR,
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Text(
                                text = pt.timeIso.substringAfter("T", pt.timeIso),
                                style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatTemp(pt.tempCelsius, temperatureUnit),
                                style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${pt.precipitationProbPercent}% rain",
                                style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                            )
                        }
                    }
                }
            }

            if (report.daily.isNotEmpty()) {
                Text(
                    text = "7-Day Forecast",
                    style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary)
                )
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.daily.forEachIndexed { idx, day ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(day.dateIso, style = MaterialTheme.typography.labelMedium.copy(color = glass.textPrimary))
                                Text(day.condition, style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                                Text(
                                    text = "${formatTemp(day.tempMinCelsius, temperatureUnit)} / ${formatTemp(day.tempMaxCelsius, temperatureUnit)}",
                                    style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 13.sp)
                                )
                            }
                            if (idx < report.daily.lastIndex) {
                                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(
                    text = "Refresh",
                    onClick = onRefresh,
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    leadingIcon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "Done",
                    onClick = onDismiss,
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WeatherMetricMini(
    icon: ImageVector,
    label: String,
    value: String
) {
    val glass = LocalAlloraGlassColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted))
            Text(value, style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 13.sp))
        }
    }
}

private fun formatTemp(celsius: Double, unit: String): String {
    return if (unit.equals("IMPERIAL", ignoreCase = true)) {
        "${((celsius * 9.0 / 5.0) + 32.0).roundToInt()}°F"
    } else {
        "${celsius.roundToInt()}°C"
    }
}

@Composable
fun RestaurantDiningScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onHoldSubmitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val savedItems by viewModel.savedItems.collectAsState()
    var guestCount by remember { mutableIntStateOf(2) }
    var selectedSlot by remember { mutableStateOf("07:30 PM") }
    var cuisineFilter by remember { mutableStateOf("All") }
    var selectedRestaurant by remember { mutableStateOf<DiningCuratedPlace?>(null) }

    BackHandler(onBack = onBack)

    val restaurants = remember {
        listOf(
            DiningCuratedPlace(
                id = "din-1",
                name = "Sushi Sawada",
                neighborhood = "Ginza, Tokyo",
                cuisine = "Omakase",
                pricePerGuestUsd = 130.0,
                rating = 4.9,
                badge = "Michelin 2★",
                highlightDish = "Edomae Aged Tuna & Bafun Uni Course",
                icon = Icons.Default.Restaurant
            ),
            DiningCuratedPlace(
                id = "din-2",
                name = "L'Oiseau Blanc Terrace",
                neighborhood = "16th Arr., Paris",
                cuisine = "French Fine Dining",
                pricePerGuestUsd = 165.0,
                rating = 4.8,
                badge = "Eiffel View",
                highlightDish = "Line-Caught Turbot & Champagne Jus",
                icon = Icons.Default.Restaurant
            ),
            DiningCuratedPlace(
                id = "din-3",
                name = "Odette Conservatory",
                neighborhood = "National Gallery, Singapore",
                cuisine = "Modern Gastronomy",
                pricePerGuestUsd = 155.0,
                rating = 4.9,
                badge = "Asia Top 10",
                highlightDish = "Kampot Pepper Miyazaki Tasting",
                icon = Icons.Default.Restaurant
            )
        )
    }

    val filtered = remember(cuisineFilter, restaurants) {
        if (cuisineFilter == "All") restaurants else restaurants.filter { it.cuisine.contains(cuisineFilter, true) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = "Dining Concierge",
                subtitle = "Two-Phase Table Holds",
                onBack = onBack
            )
        }

        // Translucent Party & Time Bar
        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Table for $guestCount Guests", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                        Text("Seating window: $selectedSlot", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip(label = "-1", selected = false, onClick = { if (guestCount > 1) guestCount -= 1 })
                        GlassChip(label = "+1", selected = true, onClick = { if (guestCount < 8) guestCount += 1 })
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                    listOf("06:00 PM", "07:30 PM", "08:45 PM").forEach { slot ->
                        GlassChip(label = slot, selected = selectedSlot == slot, onClick = { selectedSlot = slot })
                    }
                }
            }
        }

        item {
            AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                listOf("All", "Omakase", "French", "Modern").forEach { filter ->
                    GlassChip(label = filter, selected = cuisineFilter == filter, onClick = { cuisineFilter = filter })
                }
            }
        }

        items(filtered, key = { it.id }) { place ->
            val saved = savedItems.any { it.title == place.name }
            GlassPhotoOfferCard(
                title = place.name,
                subtitle = "${place.neighborhood} • ${place.highlightDish}",
                priceLabel = "$${(place.pricePerGuestUsd * guestCount).toInt()} deposit",
                ratingLabel = "${place.rating}",
                icon = place.icon,
                badgeLabel = place.badge,
                isSampleEstimate = false,
                isSaved = saved,
                onToggleSave = {
                    viewModel.toggleSavedItem(
                        itemType = "DESTINATION",
                        title = place.name,
                        subtitle = place.neighborhood,
                        category = "Dining",
                        priceLabel = "$${place.pricePerGuestUsd.toInt()}/guest",
                        rating = place.rating
                    )
                },
                onCardClick = { selectedRestaurant = place },
                onActionClick = {
                    viewModel.requestDiningHold(
                        restaurantName = place.name,
                        guests = guestCount,
                        timeSlot = selectedSlot,
                        depositUsd = place.pricePerGuestUsd * guestCount,
                        address = place.neighborhood
                    )
                    onHoldSubmitted()
                },
                actionLabel = "Hold Table"
            )
        }
    }

    selectedRestaurant?.let { place ->
        val deposit = place.pricePerGuestUsd * guestCount
        GlassBottomSheet(onDismissRequest = { selectedRestaurant = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    material = GlassMaterial.CLEAR,
                    cornerRadius = 22.dp,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            role = GlassSurfaceRole.CHIP,
                            material = GlassMaterial.FROSTED,
                            shape = CircleShape,
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(place.icon, contentDescription = null, tint = glass.textPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            StatusGlassPill(label = place.badge, color = glass.textSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(place.name, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("${place.neighborhood} • ${place.cuisine}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(6.dp))
                Text("Chef Tasting Highlight: ${place.highlightDish}", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary))
                Spacer(modifier = Modifier.height(12.dp))
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$guestCount Guests • $selectedSlot", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary))
                        Text("$${deposit.toInt()} USD", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 18.sp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Refundable up to 12 hours prior • Deposit applied to final check.", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                }
                Spacer(modifier = Modifier.height(16.dp))
                GlassButton(
                    text = "Stage Table Hold in Shield",
                    onClick = {
                        viewModel.requestDiningHold(
                            restaurantName = place.name,
                            guests = guestCount,
                            timeSlot = selectedSlot,
                            depositUsd = deposit,
                            address = place.neighborhood
                        )
                        selectedRestaurant = null
                        onHoldSubmitted()
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.Restaurant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun ActivitiesExcursionsScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onHoldSubmitted: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val savedItems by viewModel.savedItems.collectAsState()
    var filter by remember { mutableStateOf("All") }
    var selectedExcursion by remember { mutableStateOf<ExcursionOffer?>(null) }

    BackHandler(onBack = onBack)

    val excursions = remember {
        listOf(
            ExcursionOffer(
                id = "exc-1",
                title = "Meiji Jingu Private Morning Walk",
                location = "Shibuya, Tokyo",
                category = "Culture",
                duration = "2.5h",
                priceUsd = 85.0,
                rating = 4.9,
                summary = "Curator-led cedar forest sanctuary walk before peak hours.",
                icon = Icons.Default.Explore
            ),
            ExcursionOffer(
                id = "exc-2",
                title = "Turquoise Lagoon Yacht Excursion",
                location = "Sentosa Cove, Singapore",
                category = "Ocean",
                duration = "4.0h",
                priceUsd = 195.0,
                rating = 4.8,
                summary = "Sunlit island circuit with snorkeling gear and sunset refreshments.",
                icon = Icons.Default.Explore
            ),
            ExcursionOffer(
                id = "exc-3",
                title = "Aman Onsen & Holistic Reset",
                location = "Otemachi 33F, Tokyo",
                category = "Wellness",
                duration = "2.0h",
                priceUsd = 220.0,
                rating = 4.9,
                summary = "Private mineral bath circuit overlooking Mount Fuji twilight horizon.",
                icon = Icons.Default.Spa
            )
        )
    }

    val visible = remember(filter, excursions) {
        if (filter == "All") excursions else excursions.filter { it.category.equals(filter, true) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = "Excursions & Wellness",
                subtitle = "Private Experiences • Itinerary & Shield Ready",
                onBack = onBack
            )
        }
        item {
            AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                listOf("All", "Culture", "Ocean", "Wellness").forEach { tag ->
                    GlassChip(label = tag, selected = filter == tag, onClick = { filter = tag })
                }
            }
        }
        items(visible, key = { it.id }) { exc ->
            val saved = savedItems.any { it.title == exc.title }
            GlassPhotoOfferCard(
                title = exc.title,
                subtitle = "${exc.location} • ${exc.duration} • ${exc.summary}",
                priceLabel = "$${exc.priceUsd.toInt()}/guest",
                ratingLabel = "${exc.rating}",
                icon = exc.icon,
                badgeLabel = exc.category,
                isSampleEstimate = false,
                isSaved = saved,
                onToggleSave = {
                    viewModel.toggleSavedItem(
                        itemType = "DESTINATION",
                        title = exc.title,
                        subtitle = exc.location,
                        category = exc.category,
                        priceLabel = "$${exc.priceUsd.toInt()}",
                        rating = exc.rating
                    )
                },
                onCardClick = { selectedExcursion = exc },
                onActionClick = {
                    viewModel.requestActivityHold(
                        experienceTitle = exc.title,
                        guests = 2,
                        sessionWindow = "Tomorrow • 10:00 AM",
                        pricePerGuestUsd = exc.priceUsd,
                        location = exc.location
                    )
                    onHoldSubmitted()
                },
                actionLabel = "Stage Hold"
            )
        }
    }

    selectedExcursion?.let { exc ->
        GlassBottomSheet(onDismissRequest = { selectedExcursion = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    material = GlassMaterial.CLEAR,
                    cornerRadius = 22.dp,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassSurface(
                            modifier = Modifier.size(44.dp),
                            role = GlassSurfaceRole.CHIP,
                            material = GlassMaterial.FROSTED,
                            shape = CircleShape,
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(exc.icon, contentDescription = null, tint = glass.textPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            StatusGlassPill(label = "${exc.category} • ${exc.duration}", color = glass.textSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(exc.title, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(exc.summary, style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    GlassButton(
                        text = "Add to Timeline",
                        onClick = {
                            viewModel.addItinerarySlot(
                                title = exc.title,
                                location = exc.location,
                                timeRange = "10:00 AM – 12:30 PM",
                                category = "Sights",
                                costUsd = exc.priceUsd
                            )
                            selectedExcursion = null
                            onNavigateSub(SubDestination.ITINERARY)
                        },
                        isPrimary = false,
                        material = GlassMaterial.CLEAR,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = "Hold ($${(exc.priceUsd * 2).toInt()})",
                        onClick = {
                            viewModel.requestActivityHold(
                                experienceTitle = exc.title,
                                guests = 2,
                                sessionWindow = "Tomorrow • 10:00 AM",
                                pricePerGuestUsd = exc.priceUsd,
                                location = exc.location
                            )
                            selectedExcursion = null
                            onHoldSubmitted()
                        },
                        isPrimary = true,
                        material = GlassMaterial.PEARL,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
