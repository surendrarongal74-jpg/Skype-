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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.MemoryEntryEntity
import com.example.services.MemoryCategory
import com.example.ui.components.AlloraWrapRow
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassSearchBar
import com.example.ui.components.GlassStateView
import com.example.ui.components.GlassSwitch
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val memories by viewModel.memories.collectAsState()
    BackHandler(onBack = onBack)

    MemoryScreenContent(
        memories = memories,
        onBack = onBack,
        onSaveMemory = { cat, title, content, retention ->
            viewModel.saveMemoryEntry(
                category = cat,
                title = title,
                content = content,
                retentionDays = retention
            )
        },
        onUpdateMemory = { id, title, content, cat, approved, retention ->
            viewModel.updateMemoryEntry(
                memoryId = id,
                title = title,
                content = content,
                category = cat,
                userApproved = approved,
                retentionDays = retention
            )
        },
        onDeleteMemory = { id -> viewModel.deleteMemoryEntry(id) },
        onPurgeExpired = { viewModel.purgeExpiredMemories() },
        modifier = modifier
    )
}

@Composable
fun MemoryScreenContent(
    memories: List<MemoryEntryEntity>,
    onBack: () -> Unit,
    onSaveMemory: (MemoryCategory, String, String, Int) -> Unit,
    onUpdateMemory: (String, String, String, MemoryCategory, Boolean, Int) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onPurgeExpired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    var selectedCategory by remember { mutableStateOf<MemoryCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showEditorDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntryEntity?>(null) }

    var draftCategory by remember { mutableStateOf(MemoryCategory.USER_PREFERENCES) }
    var draftTitle by remember { mutableStateOf("") }
    var draftContent by remember { mutableStateOf("") }
    var draftRetentionDays by remember { mutableIntStateOf(90) }
    var draftApproved by remember { mutableStateOf(true) }

    val filtered = remember(memories, selectedCategory, searchQuery) {
        val q = searchQuery.trim().lowercase()
        memories.filter { item ->
            val matchesCat = selectedCategory == null || item.category == selectedCategory?.name
            val matchesQ = q.isBlank() ||
                item.title.lowercase().contains(q) ||
                item.content.lowercase().contains(q)
            matchesCat && matchesQ
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("memory_screen"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    modifier = Modifier.testTag("memory_back_btn")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ALLORA Memory",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Transparent context with user control & retention",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
                GlassCircleIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "Add Memory",
                    onClick = {
                        editingMemory = null
                        draftCategory = selectedCategory ?: MemoryCategory.USER_PREFERENCES
                        draftTitle = ""
                        draftContent = ""
                        draftRetentionDays = 90
                        draftApproved = true
                        showEditorDialog = true
                    },
                    modifier = Modifier.testTag("memory_add_btn")
                )
            }
        }

        item {
            GlassCard(material = GlassMaterial.CLEAR, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = glass.accentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Passwords, PINs, CVVs, API keys, and tokens are blocked automatically from Memory. Review, correct, approve, or delete entries anytime.",
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                }
            }
        }

        item {
            GlassSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search saved memories…",
                onSubmit = {},
                modifier = Modifier.testTag("memory_search_bar")
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    GlassChip(
                        label = "All (${memories.size})",
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        modifier = Modifier.testTag("memory_filter_all")
                    )
                }
                items(MemoryCategory.entries) { category ->
                    val count = memories.count { it.category == category.name }
                    GlassChip(
                        label = "${category.displayName} ($count)",
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        modifier = Modifier.testTag("memory_filter_${category.name.lowercase()}")
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filtered.size} entries",
                    style = MaterialTheme.typography.labelLarge.copy(color = glass.textSecondary)
                )
                GlassChip(
                    label = "Purge Expired",
                    selected = false,
                    leadingIcon = Icons.Default.AutoDelete,
                    onClick = onPurgeExpired,
                    modifier = Modifier.testTag("memory_purge_btn")
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                GlassStateView(
                    icon = Icons.Default.Psychology,
                    title = "No Memory Entries Yet",
                    message = "Add approved preferences, project background, or workflow facts so ALLORA stays context-aware without guessing."
                )
            }
        } else {
            items(filtered, key = { it.id }) { entry ->
                val cat = MemoryCategory.entries.firstOrNull { it.name == entry.category }
                val retentionLabel = if (entry.retentionDays <= 0) {
                    "Never expires"
                } else {
                    val dateFmt = remember { SimpleDateFormat("MMM d", Locale.US) }
                    "Expires ${dateFmt.format(Date(entry.expiresAt))} (${entry.retentionDays}d)"
                }

                GlassCard(
                    material = GlassMaterial.FROSTED,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_card_${entry.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusGlassPill(
                            label = cat?.displayName ?: entry.category,
                            color = glass.accentPrimary
                        )
                        StatusGlassPill(
                            label = if (entry.userApproved) "Approved" else "Review Pending",
                            color = if (entry.userApproved) glass.statusSuccess else glass.statusWarning
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entry.content,
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${entry.source} • $retentionLabel",
                            style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted),
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    editingMemory = entry
                                    draftCategory = cat ?: MemoryCategory.USER_PREFERENCES
                                    draftTitle = entry.title
                                    draftContent = entry.content
                                    draftRetentionDays = entry.retentionDays
                                    draftApproved = entry.userApproved
                                    showEditorDialog = true
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Memory", tint = glass.textPrimary)
                            }
                            IconButton(
                                onClick = { onDeleteMemory(entry.id) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Memory", tint = glass.statusError)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditorDialog) {
        GlassModal(onDismissRequest = { showEditorDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("memory_editor_modal"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (editingMemory == null) "Save New Memory" else "Edit Memory Entry",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = glass.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )

                Text("Category", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                AlloraWrapRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                    MemoryCategory.entries.forEach { cat ->
                        GlassChip(
                            label = cat.displayName,
                            selected = draftCategory == cat,
                            onClick = { draftCategory = cat }
                        )
                    }
                }

                GlassInput(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    label = "Title",
                    placeholder = "e.g. Preferred report structure",
                    modifier = Modifier.testTag("memory_title_input")
                )

                GlassInput(
                    value = draftContent,
                    onValueChange = { draftContent = it },
                    label = "Details",
                    placeholder = "What should ALLORA remember?",
                    singleLine = false,
                    modifier = Modifier.testTag("memory_content_input")
                )

                Text("Retention Policy", style = MaterialTheme.typography.labelLarge.copy(color = glass.textPrimary))
                AlloraWrapRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                    listOf(30 to "30 Days", 90 to "90 Days", 365 to "1 Year", 0 to "Keep Until Deleted").forEach { (days, label) ->
                        GlassChip(
                            label = label,
                            selected = draftRetentionDays == days,
                            onClick = { draftRetentionDays = days }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("User Approved for AI Context", style = MaterialTheme.typography.bodyMedium.copy(color = glass.textPrimary))
                    GlassSwitch(checked = draftApproved, onCheckedChange = { draftApproved = it })
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        text = "Cancel",
                        onClick = { showEditorDialog = false },
                        material = GlassMaterial.CLEAR,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = "Save",
                        onClick = {
                            val currentEdit = editingMemory
                            if (currentEdit == null) {
                                onSaveMemory(draftCategory, draftTitle, draftContent, draftRetentionDays)
                            } else {
                                onUpdateMemory(
                                    currentEdit.id,
                                    draftTitle,
                                    draftContent,
                                    draftCategory,
                                    draftApproved,
                                    draftRetentionDays
                                )
                            }
                            showEditorDialog = false
                        },
                        enabled = draftTitle.isNotBlank() && draftContent.isNotBlank(),
                        isPrimary = true,
                        leadingIcon = Icons.Default.Check,
                        material = GlassMaterial.PEARL,
                        testTag = "memory_save_confirm_btn",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
