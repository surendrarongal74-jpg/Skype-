package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FlightOffer
import com.example.data.model.LodgingOffer
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassBoardingFlightCard
import com.example.ui.components.GlassBottomSheet
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassPhotoOfferCard
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSwitch
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel
import com.example.ui.viewmodel.CarRentalOffer

fun filterFlightOffers(
    flights: List<FlightOffer>,
    originFilter: String,
    destinationFilter: String,
    query: String,
    cabinFilter: String,
    sortLowFirst: Boolean
): List<FlightOffer> {
    return flights.filter { offer ->
        val matchesCabin = cabinFilter == "All" || offer.cabinClass.equals(cabinFilter, true)
        val matchesOrigin = originFilter == "Any Origin" ||
            offer.originIata.equals(originFilter, true) ||
            offer.originCity.equals(originFilter, true) ||
            originFilter.contains("(${offer.originIata})", true) ||
            originFilter.contains(offer.originCity, true)
        val matchesDest = destinationFilter == "Any Destination" ||
            offer.destinationIata.equals(destinationFilter, true) ||
            offer.destinationCity.equals(destinationFilter, true) ||
            destinationFilter.contains("(${offer.destinationIata})", true) ||
            destinationFilter.contains(offer.destinationCity, true)
        val matchesQuery = query.isBlank() ||
            offer.originCity.contains(query, true) ||
            offer.destinationCity.contains(query, true) ||
            offer.airline.contains(query, true) ||
            offer.originIata.contains(query, true) ||
            offer.destinationIata.contains(query, true)
        matchesCabin && matchesOrigin && matchesDest && matchesQuery
    }.let { list ->
        if (sortLowFirst) list.sortedBy { it.priceUsd } else list.sortedBy { it.airline }
    }
}

@Composable
fun FlightSearchScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onHoldSubmitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val flights by viewModel.flightOffers.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()

    var query by remember { mutableStateOf("") }
    var originLabel by remember { mutableStateOf("Any Origin") }
    var destLabel by remember { mutableStateOf("Any Destination") }
    var cabinFilter by remember { mutableStateOf("All") }
    var sortLowFirst by remember { mutableStateOf(true) }
    var selectedFlight by remember { mutableStateOf<FlightOffer?>(null) }

    val originOptions = remember { listOf("Any Origin", "Singapore (SIN)", "Tokyo (HND)", "Paris (CDG)", "Dubai (DXB)") }
    val destOptions = remember { listOf("Any Destination", "London (LHR)", "San Francisco (SFO)", "New York (JFK)") }

    BackHandler(onBack = onBack)

    val filtered = remember(flights, originLabel, destLabel, query, cabinFilter, sortLowFirst) {
        filterFlightOffers(
            flights = flights,
            originFilter = originLabel,
            destinationFilter = destLabel,
            query = query,
            cabinFilter = cabinFilter,
            sortLowFirst = sortLowFirst
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = "Available Flights",
                subtitle = "Sample Catalog • Partner Credentials Required for Live Ticketing",
                onBack = onBack
            )
        }

        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("From", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                        Text(originLabel, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(originOptions) { opt ->
                                GlassChip(label = opt, selected = originLabel == opt, onClick = { originLabel = opt })
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("To", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                        Text(destLabel, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(destOptions) { opt ->
                                GlassChip(label = opt, selected = destLabel == opt, onClick = { destLabel = opt })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    GlassCircleIconButton(
                        icon = Icons.Default.SwapVert,
                        contentDescription = "Swap origin and destination",
                        material = GlassMaterial.PEARL,
                        onClick = {
                            val newOrigin = if (destLabel == "Any Destination") "Any Origin" else destLabel
                            val newDest = if (originLabel == "Any Origin") "Any Destination" else originLabel
                            originLabel = newOrigin
                            destLabel = newDest
                        }
                    )
                }
            }
        }

        item {
            GlassSearchBar(
                query = query,
                onQueryChange = { query = it },
                material = GlassMaterial.CLEAR,
                placeholder = "Filter SIN, Tokyo, London, ANA, Emirates..."
            )
        }

        item {
            AlloraWrapRow(
                horizontalSpacing = 8.dp,
                verticalSpacing = 8.dp
            ) {
                listOf("All", "Business", "Premium Economy", "Economy").forEach { cabin ->
                    GlassChip(label = cabin, selected = cabinFilter == cabin, onClick = { cabinFilter = cabin })
                }
                GlassChip(
                    label = if (sortLowFirst) "Lowest Price" else "Airline A–Z",
                    selected = true,
                    onClick = { sortLowFirst = !sortLowFirst }
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                GlassStateView(
                    title = "No flights match route/filter",
                    message = "Reset origin, destination, or cabin filter to view available routes.",
                    actionLabel = "Clear Filters",
                    onAction = {
                        query = ""
                        originLabel = "Any Origin"
                        destLabel = "Any Destination"
                        cabinFilter = "All"
                    }
                )
            }
        } else {
            items(filtered, key = { it.id }) { offer ->
                val saved = savedItems.any { it.title == "${offer.airline} ${offer.flightNumber}" && it.itemType == "FLIGHT" }
                GlassBoardingFlightCard(
                    originIata = offer.originIata,
                    originCity = offer.originCity,
                    destinationIata = offer.destinationIata,
                    destinationCity = offer.destinationCity,
                    departureTime = offer.departureTime,
                    arrivalTime = offer.arrivalTime,
                    duration = offer.duration,
                    airline = offer.airline,
                    flightNumber = offer.flightNumber,
                    stopsLabel = if (offer.stops == 0) "Non-stop" else "${offer.stops} stop",
                    cabinClass = offer.cabinClass,
                    priceUsd = offer.priceUsd,
                    isSampleEstimate = offer.providerSource.contains("Sample", true),
                    isSaved = saved,
                    onToggleSave = {
                        viewModel.toggleSavedItem(
                            itemType = "FLIGHT",
                            title = "${offer.airline} ${offer.flightNumber}",
                            subtitle = "${offer.originCity} to ${offer.destinationCity}",
                            category = offer.cabinClass,
                            priceLabel = "$${offer.priceUsd.toInt()}",
                            rating = 4.8
                        )
                    },
                    onCardClick = { selectedFlight = offer },
                    onStageHold = {
                        viewModel.requestFlightHold(offer)
                        onHoldSubmitted()
                    }
                )
            }
        }
    }

    selectedFlight?.let { flight ->
        val taxes = (flight.priceUsd * 0.14).toInt()
        val baseFare = flight.priceUsd.toInt() - taxes
        GlassBottomSheet(onDismissRequest = { selectedFlight = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                StatusGlassPill(label = "${flight.cabinClass} • ${flight.flightNumber}", color = glass.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${flight.originCity} (${flight.originIata}) ➔ ${flight.destinationCity} (${flight.destinationIata})",
                    style = MaterialTheme.typography.headlineMedium.copy(color = glass.textPrimary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    BreakdownRow("Carrier", flight.airline)
                    BreakdownRow("Schedule", "${flight.departureTime} – ${flight.arrivalTime} (${flight.duration})")
                    BreakdownRow("Base Fare", "$$baseFare")
                    BreakdownRow("Airport Taxes & Carrier Fees", "$$taxes")
                    HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp, modifier = Modifier.padding(vertical = 6.dp))
                    BreakdownRow("Total Hold Amount", "$${flight.priceUsd.toInt()}", highlight = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Baggage & Policy: 2x 23kg checked bags • 24h zero-fee hold cancellation.",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                GlassButton(
                    text = "Stage Flight Hold in Shield",
                    onClick = {
                        viewModel.requestFlightHold(flight)
                        selectedFlight = null
                        onHoldSubmitted()
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.FlightTakeoff,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun HotelSearchScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onHoldSubmitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val hotels by viewModel.lodgingOffers.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()

    var query by remember { mutableStateOf("") }
    var minRating by remember { mutableStateOf(0.0) }
    var selectedHotel by remember { mutableStateOf<LodgingOffer?>(null) }

    BackHandler(onBack = onBack)

    val filtered = remember(hotels, query, minRating) {
        hotels.filter {
            it.rating >= minRating &&
                (query.isBlank() || it.name.contains(query, true) || it.location.contains(query, true))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = "Explore Your Stay",
                subtitle = "Two-Phase Stay Holds • Transparent Glass Catalog",
                onBack = onBack
            )
        }
        item {
            GlassSearchBar(
                query = query,
                onQueryChange = { query = it },
                material = GlassMaterial.CLEAR,
                placeholder = "Search Tokyo, Paris, Singapore suites..."
            )
        }
        item {
            AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                GlassChip(label = "All Ratings", selected = minRating == 0.0, onClick = { minRating = 0.0 })
                GlassChip(label = "4.8★ & Up", selected = minRating >= 4.8, onClick = { minRating = 4.8 })
            }
        }

        items(filtered, key = { it.id }) { hotel ->
            val saved = savedItems.any { it.title == hotel.name && it.itemType == "HOTEL" }
            GlassPhotoOfferCard(
                title = hotel.name,
                subtitle = "${hotel.location} • ${hotel.roomType}",
                priceLabel = "$${hotel.pricePerNightUsd.toInt()}/nt",
                ratingLabel = "${hotel.rating}",
                icon = Icons.Default.Hotel,
                badgeLabel = "Stay",
                isSampleEstimate = true,
                isSaved = saved,
                onToggleSave = {
                    viewModel.toggleSavedItem(
                        itemType = "HOTEL",
                        title = hotel.name,
                        subtitle = hotel.location,
                        category = hotel.roomType,
                        priceLabel = "$${hotel.pricePerNightUsd.toInt()}/nt",
                        rating = hotel.rating
                    )
                },
                onCardClick = { selectedHotel = hotel },
                onActionClick = {
                    viewModel.requestLodgingHold(hotel)
                    onHoldSubmitted()
                },
                actionLabel = "Book Now"
            )
        }
    }

    selectedHotel?.let { hotel ->
        var selectedRoomTier by remember(hotel.id) { mutableStateOf(hotel.roomType) }
        var guestCount by remember(hotel.id) { mutableIntStateOf(2) }
        val saved = savedItems.any { it.title == hotel.name && it.itemType == "HOTEL" }
        val roomSubtotal = (hotel.pricePerNightUsd * 2).toInt()
        val taxesAndFees = (roomSubtotal * 0.16).toInt()
        val totalAmount = roomSubtotal + taxesAndFees

        GlassBottomSheet(onDismissRequest = { selectedHotel = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                // Image-free transparent-glass architectural header
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusGlassPill(label = "2 Nights • Sample Hold", color = glass.textSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = glass.statusWarning, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${hotel.rating}", style = TabularNumberStyle.copy(color = glass.textPrimary, fontSize = 14.sp))
                            }
                            GlassCircleIconButton(
                                icon = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Save hotel",
                                material = GlassMaterial.CLEAR,
                                tint = if (saved) glass.statusError else glass.textPrimary,
                                size = 40.dp,
                                onClick = {
                                    viewModel.toggleSavedItem(
                                        itemType = "HOTEL",
                                        title = hotel.name,
                                        subtitle = hotel.location,
                                        category = selectedRoomTier,
                                        priceLabel = "$${hotel.pricePerNightUsd.toInt()}/nt",
                                        rating = hotel.rating
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(hotel.name, style = MaterialTheme.typography.headlineSmall.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
                    Text(hotel.location, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassCard(
                        material = GlassMaterial.CLEAR,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Dates", style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted))
                                Text("Dec 12–14", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                            }
                        }
                    }
                    GlassCard(
                        material = GlassMaterial.CLEAR,
                        modifier = Modifier.weight(1f),
                        onClick = { guestCount = if (guestCount >= 4) 1 else guestCount + 1 },
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Guests", style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted))
                                Text("1 Room, $guestCount Guests", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                AlloraWrapRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                    listOf(hotel.roomType, "Skyline Club Suite", "Zen Sanctuary King").forEach { tier ->
                        GlassChip(label = tier, selected = selectedRoomTier == tier, onClick = { selectedRoomTier = tier })
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    BreakdownRow("Room Rate (2 Nights)", "$$roomSubtotal")
                    BreakdownRow("Taxes & Sanctuary Fees", "$$taxesAndFees")
                    HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp, modifier = Modifier.padding(vertical = 6.dp))
                    BreakdownRow("Total Estimate", "$$totalAmount", highlight = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Free cancellation up to 48h prior • Stage hold first, confirm inside Shield.", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                }

                Spacer(modifier = Modifier.height(14.dp))
                GlassButton(
                    text = "Book Now • Stage Hold ($$roomSubtotal)",
                    onClick = {
                        viewModel.requestLodgingHold(hotel)
                        selectedHotel = null
                        onHoldSubmitted()
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.Hotel,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun CarRentalSearchScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onHoldSubmitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val savedItems by viewModel.savedItems.collectAsState()
    var query by remember { mutableStateOf("") }
    var rentalDays by remember { mutableIntStateOf(3) }
    var fullInsurance by remember { mutableStateOf(true) }
    var selectedCar by remember { mutableStateOf<CarRentalOffer?>(null) }

    BackHandler(onBack = onBack)

    val filtered = remember(query) {
        viewModel.carRentals.filter {
            query.isBlank() || it.modelName.contains(query, true) || it.pickupHub.contains(query, true) || it.vehicleClass.contains(query, true)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = "Car Rentals",
                subtitle = "Pricing Logic • Explicit Rental Terms",
                onBack = onBack
            )
        }
        item {
            GlassSearchBar(
                query = query,
                onQueryChange = { query = it },
                material = GlassMaterial.CLEAR,
                placeholder = "Pickup hub (Haneda, Tokyo, Paris CDG)..."
            )
        }
        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Rental Duration: $rentalDays Days", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                        Text("Adjust daily pricing & insurance calc", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassChip(label = "-1d", selected = false, onClick = { if (rentalDays > 1) rentalDays -= 1 })
                        GlassChip(label = "+1d", selected = true, onClick = { if (rentalDays < 14) rentalDays += 1 })
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Zero-Deductible Protection", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                        Text("Covers glass, tires & collision waiver", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                    }
                    GlassSwitch(checked = fullInsurance, onCheckedChange = { fullInsurance = it })
                }
            }
        }

        items(filtered, key = { it.id }) { car ->
            val total = (car.dailyRateUsd + if (fullInsurance) car.insurancePerDayUsd else 0.0) * rentalDays
            val saved = savedItems.any { it.title == car.modelName }
            GlassPhotoOfferCard(
                title = car.modelName,
                subtitle = "${car.vehicleClass} • ${car.seats} seats • ${car.pickupHub}",
                priceLabel = "$${total.toInt()} ($rentalDays d)",
                ratingLabel = "4.9",
                icon = Icons.Default.DirectionsCar,
                badgeLabel = car.transmission,
                isSampleEstimate = car.providerSource.contains("Sample", true),
                isSaved = saved,
                onToggleSave = {
                    viewModel.toggleSavedItem(
                        itemType = "DESTINATION",
                        title = car.modelName,
                        subtitle = car.pickupHub,
                        category = car.vehicleClass,
                        priceLabel = "$${total.toInt()}",
                        rating = 4.9
                    )
                },
                onCardClick = { selectedCar = car },
                onActionClick = {
                    viewModel.requestCarRentalHold(car, rentalDays, fullInsurance)
                    onHoldSubmitted()
                },
                actionLabel = "Reserve"
            )
        }
    }

    selectedCar?.let { car ->
        val total = (car.dailyRateUsd + if (fullInsurance) car.insurancePerDayUsd else 0.0) * rentalDays
        GlassBottomSheet(onDismissRequest = { selectedCar = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                StatusGlassPill(label = "${car.vehicleClass} • ${car.transmission}", color = glass.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(car.modelName, style = MaterialTheme.typography.headlineMedium.copy(color = glass.textPrimary))
                Text(car.pickupHub, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                Spacer(modifier = Modifier.height(10.dp))
                GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                    BreakdownRow("Daily Rate × $rentalDays", "$${(car.dailyRateUsd * rentalDays).toInt()}")
                    if (fullInsurance) {
                        BreakdownRow("Zero-Deductible Protection", "$${(car.insurancePerDayUsd * rentalDays).toInt()}")
                    }
                    HorizontalDivider(color = glass.hairlineDivider, thickness = 0.7.dp, modifier = Modifier.padding(vertical = 6.dp))
                    BreakdownRow("Total Hold Amount", "$${total.toInt()}", highlight = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(car.policySummary, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
                Spacer(modifier = Modifier.height(14.dp))
                GlassButton(
                    text = "Stage Vehicle Hold ($${total.toInt()})",
                    onClick = {
                        viewModel.requestCarRentalHold(car, rentalDays, fullInsurance)
                        selectedCar = null
                        onHoldSubmitted()
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.DirectionsCar,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
internal fun ScreenHeaderBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassCircleIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            material = GlassMaterial.CLEAR,
            onClick = onBack,
            modifier = Modifier.testTag("subscreen_back_button")
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineMedium.copy(color = glass.textPrimary, fontWeight = FontWeight.Bold))
            Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, highlight: Boolean = false) {
    val glass = LocalAlloraGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = if (highlight) glass.textPrimary else glass.textSecondary,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal
            )
        )
        Text(
            text = value,
            style = TabularNumberStyle.copy(
                color = if (highlight) glass.statusSuccess else glass.textPrimary,
                fontSize = if (highlight) 16.sp else 14.sp
            )
        )
    }
}
