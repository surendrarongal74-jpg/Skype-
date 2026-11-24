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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ItinerarySlot
import com.example.ui.components.AlloraAiOrb
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassItineraryCard
import com.example.ui.components.GlassLoader
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassProgressBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.OrbState
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.TabularNumberStyle
import com.example.ui.viewmodel.AlloraViewModel

@Composable
fun TripsHubScreen(
    viewModel: AlloraViewModel,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val remoteTrips by viewModel.remoteTrips.collectAsState()
    val wizard by viewModel.wizardState.collectAsState()
    val slots by viewModel.itinerarySlots.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Plan Your Trip",
                style = MaterialTheme.typography.headlineLarge.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "Conversational AI itineraries, routing & budgets",
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
            )
        }

        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                material = GlassMaterial.FROSTED
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlloraAiOrb(state = OrbState.IDLE, size = 56.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusGlassPill(label = "11-Step AI Planner", color = glass.textSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Design a Bespoke Trip", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                        Text("Structured OpenRouter itinerary + route sequencing", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                GlassButton(
                    text = "Launch Planning Wizard",
                    onClick = { onNavigateSub(SubDestination.TRIP_WIZARD) },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    leadingIcon = Icons.Default.AutoAwesome,
                    testTag = "launch_trip_wizard_button",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(
                    modifier = Modifier.weight(1f),
                    material = GlassMaterial.CLEAR,
                    onClick = { onNavigateSub(SubDestination.ITINERARY) }
                ) {
                    Icon(Icons.Default.Timeline, contentDescription = null, tint = glass.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Timeline", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                    Text("${slots.size} stops ready", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                }
                GlassCard(
                    modifier = Modifier.weight(1f),
                    material = GlassMaterial.CLEAR,
                    onClick = { onNavigateSub(SubDestination.BUDGET) }
                ) {
                    Icon(Icons.Default.PieChart, contentDescription = null, tint = glass.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Budget", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                    Text("$${wizard.budgetUsd.toInt()} target", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                }
            }
        }

        item {
            Text("Cloud-Synced Trips (${remoteTrips.size})", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
        }

        if (remoteTrips.isEmpty()) {
            item {
                GlassStateView(
                    title = "No custom cloud trips yet",
                    message = "Complete Step 9 in the AI Planning Wizard to synthesize & persist your first itinerary to Firestore.",
                    actionLabel = "Start Wizard",
                    onAction = { onNavigateSub(SubDestination.TRIP_WIZARD) }
                )
            }
        } else {
            items(remoteTrips, key = { it.id }) { trip ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    material = GlassMaterial.FROSTED,
                    onClick = {
                        viewModel.selectTrip(trip.id)
                        onNavigateSub(SubDestination.ITINERARY)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusGlassPill(label = trip.status, color = glass.statusSuccess)
                        Text("${trip.startDate} – ${trip.endDate}", style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(trip.title, style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
                    Text("${trip.destination} • ${trip.travellersCount} guests • $${trip.budgetUsd.toInt()}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                }
            }
        }
    }
}

@Composable
fun TravelPlanningWizardScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onFinishToItinerary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val state by viewModel.wizardState.collectAsState()
    val slots by viewModel.itinerarySlots.collectAsState()

    BackHandler {
        if (state.currentStep > 1) {
            viewModel.updateWizard { it.copy(currentStep = it.currentStep - 1) }
        } else {
            onBack()
        }
    }

    val stepTitles = listOf(
        "1. Destination",
        "2. Travel Dates",
        "3. Travellers",
        "4. Travel Style",
        "5. Budget Target",
        "6. Transport Mode",
        "7. Lodging Tier",
        "8. Activity Focus",
        "9. OpenRouter AI Synthesis",
        "10. Edit Itinerary",
        "11. Final Overview"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        ScreenHeaderBar(
            title = "AI Planning Wizard",
            subtitle = stepTitles[(state.currentStep - 1).coerceIn(0, 10)],
            onBack = {
                if (state.currentStep > 1) {
                    viewModel.updateWizard { it.copy(currentStep = it.currentStep - 1) }
                } else onBack()
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        GlassProgressBar(progress = state.currentStep / 11f)
        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                when (state.currentStep) {
                    1 -> {
                        Text("Where are we heading?", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassInput(
                            value = state.destination,
                            onValueChange = { v -> viewModel.updateWizard { it.copy(destination = v) } },
                            placeholder = "Tokyo, Japan"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            listOf("Tokyo, Japan", "Paris, France", "Singapore", "London, UK").forEach { city ->
                                GlassChip(label = city, selected = state.destination == city, onClick = { viewModel.updateWizard { it.copy(destination = city) } })
                            }
                        }
                    }

                    2 -> {
                        Text("Select Travel Dates", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassInput(
                            value = state.startDate,
                            onValueChange = { v -> viewModel.updateWizard { it.copy(startDate = v) } },
                            label = "Departure Date",
                            placeholder = "Dec 12, 2026"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassInput(
                            value = state.endDate,
                            onValueChange = { v -> viewModel.updateWizard { it.copy(endDate = v) } },
                            label = "Return Date",
                            placeholder = "Dec 17, 2026"
                        )
                    }

                    3 -> {
                        Text("How many travellers?", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${state.travellersCount} Traveller(s)", style = TabularNumberStyle.copy(fontSize = 24.sp, color = glass.textPrimary))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GlassChip(label = " - ", selected = false, onClick = {
                                    if (state.travellersCount > 1) viewModel.updateWizard { it.copy(travellersCount = it.travellersCount - 1) }
                                })
                                GlassChip(label = " + ", selected = true, onClick = {
                                    if (state.travellersCount < 12) viewModel.updateWizard { it.copy(travellersCount = it.travellersCount + 1) }
                                })
                            }
                        }
                    }

                    4 -> {
                        Text("Choose Travel Style", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(12.dp))
                        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            listOf("Luxury & Culture", "Calm Wellness", "Gastronomy", "Urban Explorer", "Family Easy").forEach { style ->
                                GlassChip(label = style, selected = state.style == style, onClick = { viewModel.updateWizard { it.copy(style = style) } })
                            }
                        }
                    }

                    5 -> {
                        Text("Target Trip Budget", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("$${state.budgetUsd.toInt()} USD", style = TabularNumberStyle.copy(fontSize = 26.sp, color = glass.textPrimary))
                        Slider(
                            value = state.budgetUsd.toFloat(),
                            onValueChange = { v -> viewModel.updateWizard { it.copy(budgetUsd = v.toDouble()) } },
                            valueRange = 800f..15000f,
                            colors = SliderDefaults.colors(
                                thumbColor = glass.textPrimary,
                                activeTrackColor = glass.textPrimary,
                                inactiveTrackColor = glass.glassBorder
                            )
                        )
                    }

                    6 -> {
                        Text("Transport Preference", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(12.dp))
                        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            listOf("Business Flight + Rail", "Economy + Metro", "Private Chauffeur + EV", "Direct Non-Stop Only").forEach { tr ->
                                GlassChip(label = tr, selected = state.transport == tr, onClick = { viewModel.updateWizard { it.copy(transport = tr) } })
                            }
                        }
                    }

                    7 -> {
                        Text("Lodging Preference", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(12.dp))
                        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            listOf("Boutique Luxury Hotel", "5-Star Sanctuary", "Design Apartment", "Resort & Spa").forEach { lodg ->
                                GlassChip(label = lodg, selected = state.lodgingType == lodg, onClick = { viewModel.updateWizard { it.copy(lodgingType = lodg) } })
                            }
                        }
                    }

                    8 -> {
                        Text("Curated Interests", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(12.dp))
                        AlloraWrapRow(horizontalSpacing = 8.dp, verticalSpacing = 8.dp) {
                            listOf("Fine Dining", "Temples & Art", "Skyline Views", "Wellness & Spa", "Hidden Cafés", "Night Photography").forEach { act ->
                                val selected = act in state.activities
                                GlassChip(
                                    label = act,
                                    selected = selected,
                                    onClick = {
                                        viewModel.updateWizard { cur ->
                                            val next = if (selected) cur.activities - act else cur.activities + act
                                            cur.copy(activities = next)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    9 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            AlloraAiOrb(
                                state = when {
                                    state.isGenerating -> OrbState.PROCESSING
                                    state.generationError != null -> OrbState.ERROR
                                    else -> OrbState.IDLE
                                },
                                size = 96.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Generate Real AI Itinerary", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "ALLORA AI will structure a chronological itinerary for ${state.destination} (${state.startDate} – ${state.endDate}) and sync it to Firestore.",
                                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (state.isGenerating) {
                                GlassLoader(statusMessage = "Generating structured JSON itinerary...")
                            } else {
                                if (state.generationError != null) {
                                    Text(
                                        text = state.generationError.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall.copy(color = glass.statusError)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                GlassButton(
                                    text = "Synthesize with OpenRouter AI",
                                    onClick = { viewModel.generateWizardItinerary() },
                                    isPrimary = true,
                                    material = GlassMaterial.PEARL,
                                    leadingIcon = Icons.Default.AutoAwesome,
                                    testTag = "wizard_generate_ai_button",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    10 -> {
                        Text(state.generatedTitle.ifBlank { "Review & Reorder Stops" }, style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
                        if (state.generatedSummary.isNotBlank()) {
                            Text(state.generatedSummary, style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        slots.forEachIndexed { idx, slot ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${idx + 1}. ${slot.title}", style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                                    Text("${slot.timeRange} • ${slot.location}", style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary))
                                }
                                if (idx > 0) {
                                    GlassChip(label = "Move Up", selected = false, onClick = { viewModel.moveItinerarySlotUp(slot.id) })
                                }
                            }
                            if (idx < slots.lastIndex) {
                                HorizontalDivider(color = glass.hairlineDivider, thickness = 0.6.dp)
                            }
                        }
                    }

                    else -> {
                        StatusGlassPill(label = "Trip Ready", color = glass.statusSuccess)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.generatedTitle.ifBlank { "${state.destination} Itinerary" }, style = MaterialTheme.typography.headlineMedium.copy(color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Dates: ${state.startDate} – ${state.endDate}", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary))
                        Text("Budget: $${state.budgetUsd.toInt()} • ${state.travellersCount} travellers", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary))
                        Text("Stops prepared: ${slots.size}", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 86.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.currentStep > 1) {
                GlassButton(
                    text = "Back",
                    onClick = { viewModel.updateWizard { it.copy(currentStep = it.currentStep - 1) } },
                    isPrimary = false,
                    material = GlassMaterial.CLEAR,
                    modifier = Modifier.weight(1f)
                )
            }
            GlassButton(
                text = if (state.currentStep < 11) "Next Step" else "Open Itinerary Timeline",
                onClick = {
                    if (state.currentStep < 11) {
                        viewModel.updateWizard { it.copy(currentStep = it.currentStep + 1) }
                    } else {
                        onFinishToItinerary()
                    }
                },
                isPrimary = true,
                material = GlassMaterial.PEARL,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ItineraryTimelineScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    onNavigateSub: (SubDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val slots by viewModel.itinerarySlots.collectAsState()
    val metaMap by viewModel.itineraryMeta.collectAsState()
    val remoteTrips by viewModel.remoteTrips.collectAsState()
    val selectedTripId by viewModel.selectedTripId.collectAsState()
    val activeTrip = remoteTrips.firstOrNull { it.id == selectedTripId }

    var editingSlot by remember { mutableStateOf<ItinerarySlot?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeaderBar(
                title = activeTrip?.title ?: "Itinerary Timeline",
                subtitle = activeTrip?.let { "${it.destination} • ${slots.size} cloud-synced stops" }
                    ?: "Trip-Scoped Firestore Timeline • ${slots.size} stops",
                onBack = onBack
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(label = "+ Add Block", selected = true, leadingIcon = Icons.Default.Add, onClick = { showAddDialog = true })
                GlassChip(label = "Route Map", selected = false, leadingIcon = Icons.Default.Map, onClick = { onNavigateSub(SubDestination.MAP) })
                GlassChip(label = "Budget", selected = false, leadingIcon = Icons.Default.PieChart, onClick = { onNavigateSub(SubDestination.BUDGET) })
            }
        }

        if (slots.isEmpty()) {
            item {
                GlassStateView(
                    title = "No stops for this trip yet",
                    message = "Add a custom stop above or run the AI Planning Wizard to synthesize and persist your itinerary.",
                    actionLabel = "Add First Stop",
                    onAction = { showAddDialog = true }
                )
            }
        } else {
            itemsIndexed(slots, key = { _, s -> s.id }) { idx, slot ->
                val meta = metaMap[slot.id]
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 14.dp, end = 10.dp)
                    ) {
                        GlassSurface(
                            modifier = Modifier.size(24.dp),
                            role = GlassSurfaceRole.PEARL,
                            material = GlassMaterial.PEARL,
                            shape = CircleShape,
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${idx + 1}", style = TabularNumberStyle.copy(color = glass.pearlInk, fontSize = 11.sp))
                        }
                        if (idx < slots.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.2.dp)
                                    .height(106.dp)
                                    .background(glass.hairlineDivider)
                            )
                        }
                    }

                    GlassItineraryCard(
                        timeRange = "Day ${slot.dayNumber} • ${slot.timeRange}",
                        title = slot.title,
                        location = slot.location,
                        category = slot.type,
                        durationMinutes = slot.durationMinutes,
                        syncStatus = slot.calSyncStatus,
                        estimatedCostUsd = meta?.estimatedCostUsd ?: slot.estimatedCostUsd,
                        note = meta?.note?.ifBlank { slot.notes } ?: slot.notes,
                        onMoveUp = if (idx > 0) ({ viewModel.moveItinerarySlotUp(slot.id) }) else null,
                        onEditNote = { editingSlot = slot },
                        onDelete = { viewModel.deleteItinerarySlot(slot.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    editingSlot?.let { slot ->
        val existingMeta = metaMap[slot.id]
        var titleText by remember(slot.id) { mutableStateOf(slot.title) }
        var timeText by remember(slot.id) { mutableStateOf(slot.timeRange) }
        var dayText by remember(slot.id) { mutableStateOf(slot.dayNumber.toString()) }
        var noteText by remember(slot.id) { mutableStateOf(existingMeta?.note ?: slot.notes) }
        var costText by remember(slot.id) { mutableStateOf((existingMeta?.estimatedCostUsd ?: slot.estimatedCostUsd).toInt().toString()) }

        GlassModal(onDismissRequest = { editingSlot = null }) {
            Text("Edit Activity Block", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
            Spacer(modifier = Modifier.height(10.dp))
            GlassInput(value = titleText, onValueChange = { titleText = it }, label = "Title", placeholder = "Activity title")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = timeText, onValueChange = { timeText = it }, label = "Time Window", placeholder = "09:00 AM – 11:00 AM")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = dayText, onValueChange = { dayText = it.filter { ch -> ch.isDigit() } }, label = "Day Number", placeholder = "1")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = noteText, onValueChange = { noteText = it }, label = "Personal Note", placeholder = "Add gate, table, or dress code note")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = costText, onValueChange = { costText = it.filter { ch -> ch.isDigit() } }, label = "Estimated Expense (USD)", placeholder = "120")
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(text = "Cancel", onClick = { editingSlot = null }, isPrimary = false, material = GlassMaterial.CLEAR, modifier = Modifier.weight(1f))
                GlassButton(
                    text = "Save",
                    onClick = {
                        viewModel.updateItinerarySlotNoteAndCost(
                            slotId = slot.id,
                            note = noteText,
                            costUsd = costText.toDoubleOrNull() ?: 0.0,
                            updatedTitle = titleText,
                            updatedTimeRange = timeText,
                            updatedDayNumber = dayText.toIntOrNull()
                        )
                        editingSlot = null
                    },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showAddDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newLocation by remember { mutableStateOf("") }
        var newTime by remember { mutableStateOf("04:00 PM – 05:30 PM") }
        var newDay by remember { mutableStateOf("1") }
        var newCost by remember { mutableStateOf("80") }

        GlassModal(onDismissRequest = { showAddDialog = false }) {
            Text("Add Itinerary Activity", style = MaterialTheme.typography.titleLarge.copy(color = glass.textPrimary))
            Spacer(modifier = Modifier.height(10.dp))
            GlassInput(value = newTitle, onValueChange = { newTitle = it }, label = "Title", placeholder = "TeamLab Borderless Visit")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = newLocation, onValueChange = { newLocation = it }, label = "Location", placeholder = "Azabudai Hills, Tokyo")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = newTime, onValueChange = { newTime = it }, label = "Time Window", placeholder = "04:00 PM – 05:30 PM")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = newDay, onValueChange = { newDay = it.filter { ch -> ch.isDigit() } }, label = "Day Number", placeholder = "1")
            Spacer(modifier = Modifier.height(8.dp))
            GlassInput(value = newCost, onValueChange = { newCost = it.filter { ch -> ch.isDigit() } }, label = "Est. Cost (USD)", placeholder = "80")
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(text = "Cancel", onClick = { showAddDialog = false }, isPrimary = false, material = GlassMaterial.CLEAR, modifier = Modifier.weight(1f))
                GlassButton(
                    text = "Add Stop",
                    onClick = {
                        viewModel.addItinerarySlot(
                            title = newTitle,
                            location = newLocation,
                            timeRange = newTime,
                            category = "Sights",
                            costUsd = newCost.toDoubleOrNull() ?: 0.0,
                            dayNumber = newDay.toIntOrNull() ?: 1
                        )
                        showAddDialog = false
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
fun TravelBudgetScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val ledger by viewModel.ledgerTransactions.collectAsState()
    val wizard by viewModel.wizardState.collectAsState()
    val profile by viewModel.userProfile.collectAsState()

    BackHandler(onBack = onBack)

    val breakdowns = remember(ledger, wizard.budgetUsd) { viewModel.computeBudgetBreakdowns() }
    val totalTarget = wizard.budgetUsd.coerceAtLeast(1000.0)
    val totalActual = breakdowns.sumOf { it.actualUsd }
    val remaining = (totalTarget - totalActual).coerceAtLeast(0.0)
    val sliceColors = remember(glass.isDark) {
        listOf(
            glass.textPrimary,
            glass.textSecondary,
            glass.textMuted,
            glass.glassBorder
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenHeaderBar(title = "Travel Budget", subtitle = "Ledger Breakdown • ${profile.preferredCurrency}", onBack = onBack)
        }
        item {
            GlassCard(material = GlassMaterial.FROSTED, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Canvas(modifier = Modifier.size(116.dp)) {
                        val stroke = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(color = glass.clearGlass, style = stroke)
                        val totalEst = breakdowns.sumOf { it.estimatedUsd }.coerceAtLeast(1.0)
                        var startAngle = -90f
                        breakdowns.forEachIndexed { idx, cat ->
                            val sweep = ((cat.estimatedUsd / totalEst) * 340.0).toFloat()
                            drawArc(
                                color = sliceColors[idx % sliceColors.size],
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = stroke
                            )
                            startAngle += sweep + 4f
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Committed Spend", style = MaterialTheme.typography.labelMedium.copy(color = glass.textSecondary))
                        Text("$${totalActual.toInt()} USD", style = TabularNumberStyle.copy(fontSize = 26.sp, color = glass.textPrimary))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Target: $${totalTarget.toInt()} • Remaining: $${remaining.toInt()}", style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary))
                    }
                }
            }
        }

        item {
            Text("Category Breakdown (Estimated vs Actual)", style = MaterialTheme.typography.titleMedium.copy(color = glass.textPrimary))
        }

        items(breakdowns, key = { it.label }) { item ->
            val ratio = (item.actualUsd / item.estimatedUsd.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.label, style = MaterialTheme.typography.titleSmall.copy(color = glass.textPrimary))
                    Text(
                        "$${item.actualUsd.toInt()} / $${item.estimatedUsd.toInt()}",
                        style = TabularNumberStyle.copy(color = if (item.actualUsd > item.estimatedUsd) glass.statusWarning else glass.textPrimary, fontSize = 14.sp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                GlassProgressBar(progress = ratio)
            }
        }
    }
}
