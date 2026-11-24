package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.ai.CameraFramePreprocessor
import com.example.data.ai.LandmarkIdentificationUiState
import com.example.data.local.entity.TravelPhotoRecord
import com.example.ui.components.ArchitecturalLandmarkHudCard
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.components.GlassCircleIconButton
import com.example.ui.components.GlassInput
import com.example.ui.components.GlassModal
import com.example.ui.components.GlassSurface
import com.example.ui.components.GlassSurfaceRole
import com.example.ui.components.StatusGlassPill
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.viewmodel.AlloraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
fun TravelCameraScreen(
    viewModel: AlloraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photos by viewModel.travelPhotos.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val activeCity by viewModel.activeWeatherCity.collectAsState()
    val wizard by viewModel.wizardState.collectAsState()
    val trackingState by viewModel.locationTrackingState.collectAsState()
    val landmarkState by viewModel.landmarkIdentificationState.collectAsState()
    val liveScanEnabled by viewModel.liveLandmarkScanEnabled.collectAsState()

    val resolvedLocation = trackingState.currentLocation ?: userLocation
    val destinationLabel = wizard.destination.ifBlank { activeCity }

    TravelCameraContent(
        currentUserId = viewModel.currentUserId,
        photos = photos,
        destinationLabel = destinationLabel,
        latitude = resolvedLocation?.latitude,
        longitude = resolvedLocation?.longitude,
        landmarkState = landmarkState,
        liveLandmarkScanEnabled = liveScanEnabled,
        onToggleLiveLandmarkScan = { enabled -> viewModel.setLiveLandmarkScanEnabled(enabled) },
        onAnalyzeCameraFrame = { bitmap, focusCategory, isManualScan ->
            viewModel.analyzeCameraFrameForLandmark(
                bitmap = bitmap,
                destinationLabel = destinationLabel,
                latitude = resolvedLocation?.latitude,
                longitude = resolvedLocation?.longitude,
                focusCategory = focusCategory,
                isManualScan = isManualScan
            )
        },
        onDismissLandmarkResult = { viewModel.dismissLandmarkIdentification() },
        onSavePhoto = { filePath, caption, category ->
            viewModel.saveCapturedPhoto(
                filePath = filePath,
                caption = caption,
                category = category,
                destinationName = destinationLabel,
                latitude = resolvedLocation?.latitude,
                longitude = resolvedLocation?.longitude
            )
        },
        onDeletePhoto = { viewModel.deleteCapturedPhoto(it) },
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
fun TravelCameraContent(
    currentUserId: String,
    photos: List<TravelPhotoRecord>,
    destinationLabel: String,
    latitude: Double?,
    longitude: Double?,
    onSavePhoto: (filePath: String, caption: String, category: String) -> Unit,
    onDeletePhoto: (TravelPhotoRecord) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    landmarkState: LandmarkIdentificationUiState = LandmarkIdentificationUiState.Idle,
    liveLandmarkScanEnabled: Boolean = true,
    onToggleLiveLandmarkScan: (Boolean) -> Unit = {},
    onAnalyzeCameraFrame: (bitmap: Bitmap, focusCategory: String, isManualScan: Boolean) -> Unit = { bmp, _, _ ->
        if (!bmp.isRecycled) bmp.recycle()
    },
    onDismissLandmarkResult: () -> Unit = {}
) {
    val glass = LocalAlloraGlassColors.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRobolectric = remember { Build.FINGERPRINT.equals("robolectric", ignoreCase = true) }

    BackHandler(onBack = onBack)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var useFrontCamera by remember { mutableStateOf(false) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var showArchitecturalGrid by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf("Architecture") }
    var captionDraft by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var inspectingPhoto by remember { mutableStateOf<TravelPhotoRecord?>(null) }

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    val manualScanPending = remember { AtomicBoolean(false) }
    val lastFrameAnalyzedMs = remember { AtomicLong(0L) }
    val currentLiveScanEnabled by rememberUpdatedState(liveLandmarkScanEnabled)
    val currentIsAnalyzing by rememberUpdatedState(landmarkState is LandmarkIdentificationUiState.Analyzing)
    val currentCategory by rememberUpdatedState(selectedCategory)
    val currentOnAnalyzeFrame by rememberUpdatedState(onAnalyzeCameraFrame)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdownNow()
        }
    }

    if (!isRobolectric && hasCameraPermission && previewViewRef != null) {
        val currentPreviewView = previewViewRef!!
        DisposableEffect(lifecycleOwner, currentPreviewView, useFrontCamera, flashMode) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            var boundProvider: ProcessCameraProvider? = null
            var activeAnalysis: ImageAnalysis? = null

            val listener = Runnable {
                runCatching {
                    val provider = cameraProviderFuture.get()
                    boundProvider = provider
                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = currentPreviewView.surfaceProvider
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(flashMode)
                        .build()

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    activeAnalysis = imageAnalysis

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val isManual = manualScanPending.getAndSet(false)
                            val now = System.currentTimeMillis()
                            val allowAuto = currentLiveScanEnabled &&
                                !currentIsAnalyzing &&
                                (now - lastFrameAnalyzedMs.get() >= LIVE_LANDMARK_SCAN_INTERVAL_MS)
                            if (isManual || allowAuto) {
                                lastFrameAnalyzedMs.set(now)
                                val upright = CameraFramePreprocessor.imageProxyToUprightBitmap(imageProxy)
                                if (upright != null) {
                                    val categorySnapshot = currentCategory
                                    mainExecutor.execute {
                                        currentOnAnalyzeFrame(upright, categorySnapshot, isManual)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "CameraX frame preprocessing failed", e)
                        } finally {
                            imageProxy.close()
                        }
                    }

                    val primarySelector = if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    val resolvedSelector = if (provider.hasCamera(primarySelector)) {
                        primarySelector
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        resolvedSelector,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )
                    imageCaptureRef = imageCapture
                    cameraError = null
                }.onFailure { err ->
                    Log.w(TAG, "Failed to bind CameraX use cases", err)
                    cameraError = err.localizedMessage ?: "Camera preview unavailable on this device."
                }
            }

            cameraProviderFuture.addListener(listener, mainExecutor)

            onDispose {
                activeAnalysis?.clearAnalyzer()
                boundProvider?.unbindAll()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!isRobolectric && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glass.canvasBase)
    ) {
        // Viewfinder Surface
        when {
            !hasCameraPermission -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassCard(
                        material = GlassMaterial.FROSTED,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("camera_permission_card"),
                        contentPadding = PaddingValues(22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassSurface(
                                modifier = Modifier.size(48.dp),
                                role = GlassSurfaceRole.CHIP,
                                material = GlassMaterial.CLEAR,
                                shape = CircleShape,
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = glass.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.camera_permission_title),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = destinationLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.camera_permission_rationale),
                            style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        GlassButton(
                            text = stringResource(R.string.camera_grant_permission),
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            isPrimary = true,
                            material = GlassMaterial.PEARL,
                            leadingIcon = Icons.Default.PhotoCamera,
                            testTag = "camera_grant_permission_btn",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            isRobolectric -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("camera_preview_surface"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Camera viewfinder preview",
                            tint = glass.textSecondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "CameraX Architectural Viewfinder ($selectedCategory)",
                            style = MaterialTheme.typography.bodyMedium.copy(color = glass.textSecondary)
                        )
                    }
                }
            }

            else -> {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewViewRef = this
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("camera_preview_surface")
                )
            }
        }

        // Architectural Rule-of-Thirds & Horizon Plumb Overlay
        if (hasCameraPermission && showArchitecturalGrid) {
            ArchitecturalGridOverlay(
                lineColor = glass.textPrimary.copy(alpha = 0.28f),
                horizonColor = glass.accentPrimary.copy(alpha = 0.42f),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("camera_architecture_grid")
            )
        }

        // Top Translucent Glass Controls Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    GlassCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        material = GlassMaterial.CLEAR,
                        onClick = onBack,
                        modifier = Modifier.testTag("camera_back_button")
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    GlassSurface(
                        role = GlassSurfaceRole.CHIP,
                        material = GlassMaterial.FROSTED,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.camera_title),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = glass.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val geotagLabel = if (latitude != null && longitude != null) {
                                "$destinationLabel • ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}"
                            } else {
                                destinationLabel
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = glass.statusSuccess,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = geotagLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassCircleIconButton(
                        icon = if (showArchitecturalGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                        contentDescription = "Toggle architectural grid",
                        material = if (showArchitecturalGrid) GlassMaterial.PEARL else GlassMaterial.CLEAR,
                        onClick = { showArchitecturalGrid = !showArchitecturalGrid },
                        modifier = Modifier.testTag("camera_grid_toggle")
                    )
                    GlassCircleIconButton(
                        icon = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        },
                        contentDescription = "Cycle flash mode",
                        material = GlassMaterial.CLEAR,
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                                else -> ImageCapture.FLASH_MODE_AUTO
                            }
                        },
                        modifier = Modifier.testTag("camera_flash_toggle")
                    )
                    GlassCircleIconButton(
                        icon = Icons.Default.Cameraswitch,
                        contentDescription = "Switch camera lens",
                        material = GlassMaterial.CLEAR,
                        onClick = { useFrontCamera = !useFrontCamera },
                        modifier = Modifier.testTag("camera_switch_lens")
                    )
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    GlassChip(
                        label = if (liveLandmarkScanEnabled) "Live AI On" else "Live AI Off",
                        selected = liveLandmarkScanEnabled,
                        selectedMaterial = GlassMaterial.PEARL,
                        leadingIcon = Icons.Default.AutoAwesome,
                        onClick = { onToggleLiveLandmarkScan(!liveLandmarkScanEnabled) },
                        modifier = Modifier.testTag("camera_live_id_toggle")
                    )
                }
                items(listOf("Architecture", "Landmark", "Streetscape", "Interior")) { category ->
                    GlassChip(
                        label = category,
                        selected = selectedCategory == category,
                        selectedMaterial = GlassMaterial.PEARL,
                        onClick = { selectedCategory = category }
                    )
                }
            }

            cameraError?.let { err ->
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
                            text = err,
                            style = MaterialTheme.typography.labelMedium.copy(color = glass.textPrimary),
                            modifier = Modifier.weight(1f)
                        )
                        GlassCircleIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Dismiss camera notice",
                            material = GlassMaterial.CLEAR,
                            size = 32.dp,
                            onClick = { cameraError = null }
                        )
                    }
                }
            }
        }

        // Bottom Translucent Capture Dock & Real-Time Landmark HUD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hasCameraPermission) {
                ArchitecturalLandmarkHudCard(
                    landmarkState = landmarkState,
                    liveScanEnabled = liveLandmarkScanEnabled,
                    onApplyCaption = { suggested -> captionDraft = suggested },
                    onRescanNow = {
                        lastFrameAnalyzedMs.set(0L)
                        manualScanPending.set(true)
                    },
                    onRetry = {
                        onToggleLiveLandmarkScan(true)
                        lastFrameAnalyzedMs.set(0L)
                        manualScanPending.set(true)
                    },
                    onDismiss = onDismissLandmarkResult
                )
            }

            if (photos.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("camera_photo_strip")
                ) {
                    items(photos, key = { it.id }) { photo ->
                        TravelPhotoThumbnailCard(
                            photo = photo,
                            onClick = { inspectingPhoto = photo }
                        )
                    }
                }
            }

            GlassCard(
                material = GlassMaterial.DEEP,
                cornerRadius = 26.dp,
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassInput(
                        value = captionDraft,
                        onValueChange = { captionDraft = it },
                        placeholder = "Note structure, facade, or street...",
                        modifier = Modifier.weight(1f)
                    )

                    GlassSurface(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(60.dp),
                        role = GlassSurfaceRole.PEARL,
                        material = GlassMaterial.PEARL,
                        shape = CircleShape,
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics(mergeDescendants = true) {}
                                .clickable(enabled = !isCapturing) {
                                    if (!hasCameraPermission) {
                                        permissionLauncher.launch(Manifest.permission.CAMERA)
                                        return@clickable
                                    }
                                    val capture = imageCaptureRef
                                    if (isRobolectric || capture == null) {
                                        val file = createUserPhotoFile(context, currentUserId)
                                        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
                                        onSavePhoto(file.absolutePath, captionDraft, selectedCategory)
                                        captionDraft = ""
                                        return@clickable
                                    }
                                    isCapturing = true
                                    val outFile = createUserPhotoFile(context, currentUserId)
                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
                                    capture.takePicture(
                                        outputOptions,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                isCapturing = false
                                                onSavePhoto(outFile.absolutePath, captionDraft, selectedCategory)
                                                captionDraft = ""
                                                cameraError = null
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                isCapturing = false
                                                cameraError = exception.localizedMessage ?: "Failed to save photo."
                                            }
                                        }
                                    )
                                }
                                .testTag("camera_shutter_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCapturing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = glass.pearlInk
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Capture architectural photo",
                                    tint = glass.pearlInk,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    inspectingPhoto?.let { photo ->
        val fullBitmap by produceState<ImageBitmap?>(initialValue = null, photo.filePath) {
            value = withContext(Dispatchers.IO) { decodeSampledBitmap(photo.filePath, maxDim = 1080) }
        }
        GlassModal(onDismissRequest = { inspectingPhoto = null }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusGlassPill(label = photo.category, color = glass.textSecondary)
                Text(
                    text = formatCaptureTime(photo.capturedAt),
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textMuted)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (fullBitmap != null) {
                Image(
                    bitmap = fullBitmap!!,
                    contentDescription = photo.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(glass.clearGlass),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = glass.textSecondary)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = photo.caption,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            val locText = if (photo.latitude != null && photo.longitude != null) {
                "${photo.destinationName} • ${String.format(Locale.US, "%.4f, %.4f", photo.latitude, photo.longitude)}"
            } else {
                photo.destinationName
            }
            Text(
                text = locText,
                style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(
                    text = "Delete",
                    onClick = {
                        onDeletePhoto(photo)
                        inspectingPhoto = null
                    },
                    isPrimary = false,
                    accentColor = glass.statusError,
                    leadingIcon = Icons.Default.Delete,
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    text = "Done",
                    onClick = { inspectingPhoto = null },
                    isPrimary = true,
                    material = GlassMaterial.PEARL,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ArchitecturalGridOverlay(
    lineColor: Color,
    horizonColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f

        // Rule of thirds lines
        drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = 1.2f)
        drawLine(lineColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), strokeWidth = 1.2f)
        drawLine(lineColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = 1.2f)
        drawLine(lineColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), strokeWidth = 1.2f)

        // Subtle center architectural level crosshair
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(horizonColor, Offset(cx - 28f, cy), Offset(cx + 28f, cy), strokeWidth = 2f)
        drawLine(horizonColor, Offset(cx, cy - 28f), Offset(cx, cy + 28f), strokeWidth = 2f)
    }
}

@Composable
private fun TravelPhotoThumbnailCard(
    photo: TravelPhotoRecord,
    onClick: () -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, photo.filePath) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(photo.filePath, maxDim = 240) }
    }

    GlassSurface(
        modifier = Modifier
            .size(width = 112.dp, height = 84.dp)
            .clickable(onClick = onClick),
        role = GlassSurfaceRole.CARD,
        material = GlassMaterial.FROSTED,
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = photo.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = photo.caption,
                        tint = glass.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(glass.deepGlass)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = photo.caption,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = glass.textPrimary,
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun createUserPhotoFile(context: Context, userId: String): File {
    val safeUid = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "default" }
    val dir = File(context.filesDir, "travel_photos/$safeUid")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return File(dir, "ALLORA_$stamp.jpg")
}

private fun decodeSampledBitmap(path: String, maxDim: Int): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.length() <= 16L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sampleSize = 1
    while (longest / sampleSize > maxDim * 2) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, decodeOpts)?.asImageBitmap()
}

private fun formatCaptureTime(epochMs: Long): String {
    return SimpleDateFormat("MMM d • HH:mm", Locale.US).format(Date(epochMs))
}

private const val TAG = "TravelCameraScreen"
private const val LIVE_LANDMARK_SCAN_INTERVAL_MS = 3500L
