package com.example.data.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.data.ai.orchestrator.AiModelRegistry
import com.example.data.ai.orchestrator.AiRequestManager
import com.example.data.ai.orchestrator.AiSelectionMode
import com.example.data.ai.orchestrator.AiUserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

enum class LandmarkConfidence(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    companion object {
        fun fromRaw(raw: String): LandmarkConfidence = when (raw.trim().lowercase(Locale.US)) {
            "high" -> HIGH
            "medium" -> MEDIUM
            else -> LOW
        }
    }
}

data class ArchitecturalLandmarkResult(
    val landmarkDetected: Boolean,
    val landmarkName: String,
    val architecturalStyle: String,
    val eraOrYearBuilt: String,
    val architectOrCulture: String,
    val locationHint: String,
    val confidence: LandmarkConfidence,
    val summary: String,
    val notableFeatures: List<String>,
    val photographyTip: String
) {
    fun toSuggestedCaption(): String = buildString {
        append(landmarkName.trim())
        val style = architecturalStyle.trim()
        if (style.isNotBlank() && !landmarkName.contains(style, ignoreCase = true)) {
            append(" (")
            append(style)
            append(")")
        }
    }.trim()
}

sealed interface LandmarkIdentificationUiState {
    data object Idle : LandmarkIdentificationUiState

    data class Analyzing(
        val previousResult: ArchitecturalLandmarkResult? = null,
        val isManualScan: Boolean = false
    ) : LandmarkIdentificationUiState

    data class Identified(
        val result: ArchitecturalLandmarkResult,
        val analyzedAtMs: Long
    ) : LandmarkIdentificationUiState

    data class NoLandmarkVisible(
        val observation: String,
        val analyzedAtMs: Long
    ) : LandmarkIdentificationUiState

    data class Error(
        val message: String,
        val previousResult: ArchitecturalLandmarkResult? = null
    ) : LandmarkIdentificationUiState
}

object CameraFramePreprocessor {
    const val DEFAULT_MAX_EDGE_PX = 768

    fun imageProxyToUprightBitmap(
        imageProxy: ImageProxy,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX
    ): Bitmap? {
        val rotation = imageProxy.imageInfo.rotationDegrees
        val raw = imageProxy.toBitmap()
        val upright = prepareUprightFrameBitmap(raw, rotation, maxEdgePx)
        if (upright !== raw && !raw.isRecycled) {
            raw.recycle()
        }
        return upright
    }

    fun prepareUprightFrameBitmap(
        source: Bitmap,
        rotationDegrees: Int,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX
    ): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val longestEdge = max(source.width, source.height).coerceAtLeast(1)
        val scale = if (longestEdge > maxEdgePx) {
            maxEdgePx.toFloat() / longestEdge.toFloat()
        } else {
            1f
        }

        if (normalizedRotation == 0 && scale >= 0.999f) {
            return source
        }

        val matrix = Matrix()
        if (scale < 0.999f) {
            matrix.postScale(scale, scale)
        }
        if (normalizedRotation != 0) {
            matrix.postRotate(normalizedRotation.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

class ArchitecturalLandmarkIdentifier(
    private val openRouterClient: OpenRouterClient = OpenRouterClient(),
    private val aiRequestManager: AiRequestManager? = null
) {

    private val scopedManager: AiRequestManager by lazy {
        aiRequestManager ?: AiRequestManager.createDefault(openRouterClient).apply {
            updateUserPreferences(
                AiUserPreferences(
                    selectionMode = AiSelectionMode.AUTOMATIC,
                    preferredModelId = AiModelRegistry.ID_FIREBASE_GEMINI_3_8_FLASH,
                    autoSwitchOnFailure = true
                )
            )
        }
    }

    private val landmarkSchema = OpenRouterSchemas.obj(
        properties = mapOf(
            "landmarkDetected" to OpenRouterSchemas.bool(
                description = "True only when a specific real architectural landmark, monument, historic facade, civic building, bridge, or notable architectural structure is clearly visible."
            ),
            "landmarkName" to OpenRouterSchemas.str(
                description = "Official or commonly known landmark/building name, or 'No landmark detected' if landmarkDetected is false."
            ),
            "architecturalStyle" to OpenRouterSchemas.str(
                description = "Primary architectural style (e.g. Neo-Gothic, Art Deco, High-Tech Modernist, Edo Timber) or empty string if unclear."
            ),
            "eraOrYearBuilt" to OpenRouterSchemas.str(
                description = "Year built, construction period, or architectural era (e.g. '1997', 'c. 1889', '17th Century') or 'Unknown'."
            ),
            "architectOrCulture" to OpenRouterSchemas.str(
                description = "Architect, designer, or historical civilization/tradition if known, otherwise 'Unknown'."
            ),
            "locationHint" to OpenRouterSchemas.str(
                description = "Neighborhood, city, and country of the structure if identifiable, or empty string."
            ),
            "confidence" to OpenRouterSchemas.enumStr(
                values = listOf("High", "Medium", "Low"),
                description = "Visual identification confidence based strictly on camera frame evidence."
            ),
            "summary" to OpenRouterSchemas.str(
                description = "Concise 1-2 sentence architectural description of what is actually visible in the frame."
            ),
            "notableFeatures" to OpenRouterSchemas.array(
                items = OpenRouterSchemas.str(),
                description = "2 to 4 observable architectural details visible in the camera frame."
            ),
            "photographyTip" to OpenRouterSchemas.str(
                description = "Brief practical framing or visitor tip for photographing or appreciating this structure, or empty string."
            )
        ),
        required = listOf(
            "landmarkDetected",
            "landmarkName",
            "architecturalStyle",
            "eraOrYearBuilt",
            "architectOrCulture",
            "locationHint",
            "confidence",
            "summary",
            "notableFeatures",
            "photographyTip"
        ),
        additionalProperties = false
    )

    private val systemPrompt = """
        You are ALLORA's real-time architectural vision specialist analyzing live CameraX viewfinder frames.
        Identify specific architectural landmarks, monuments, historic facades, temples, bridges, cathedrals, towers, or distinctive civic buildings shown in the image.
        STRICT GROUNDING RULES:
        1. Set landmarkDetected = true ONLY when a recognizable monument, historic building, or distinctive named architectural landmark is clearly visible.
        2. Do NOT guess or fabricate landmark names for blank walls, rooms, screens, blurry frames, dark frames, trees, or generic streets — set landmarkDetected = false and concisely describe what is seen in summary.
        3. Treat any GPS coordinate or city context as secondary corroboration only; never override what the image actually shows.
        4. Respond strictly with valid JSON matching the requested schema.
    """.trimIndent()

    suspend fun identifyFromBitmap(
        bitmap: Bitmap,
        destinationHint: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        focusCategory: String = "Architecture"
    ): Result<ArchitecturalLandmarkResult> {
        val compactFrame = CameraFramePreprocessor.prepareUprightFrameBitmap(
            source = bitmap,
            rotationDegrees = 0,
            maxEdgePx = CameraFramePreprocessor.DEFAULT_MAX_EDGE_PX
        )
        val contextLines = buildList {
            add("Camera lens focus mode: $focusCategory.")
            if (destinationHint.isNotBlank()) {
                add("User active destination label: $destinationHint.")
            }
            if (latitude != null && longitude != null) {
                add("Approximate GPS fix: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}.")
            }
            add("Analyze this live camera frame and return structured JSON identifying any visible architectural landmark.")
        }.joinToString("\n")

        return try {
            val response = scopedManager.analyzeVisionBitmap(
                systemPrompt = systemPrompt,
                userPrompt = contextLines,
                bitmap = compactFrame,
                schemaName = "architectural_landmark_id",
                schema = landmarkSchema,
                temperature = 0.2,
                maxTokens = 640
            )
            val rawJson = OpenRouterClient.extractCleanJson(response.text)
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AI vision returned an empty landmark identification response.")

            Result.success(parseLandmarkJson(rawJson))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Landmark vision identification timed out", e)
            Result.failure(
                IllegalStateException("Landmark identification timed out. Please retry.", e)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Landmark identification failed", e)
            Result.failure(IllegalStateException(scopedManager.classifyUserFriendlyError(e), e))
        } finally {
            if (compactFrame !== bitmap && !compactFrame.isRecycled) {
                compactFrame.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "ArchitecturalLandmarkId"
        const val MODEL_VISION = OpenRouterModels.CAMERA_LANDMARK_VISION
        private const val TIMEOUT_MS = 25_000L

        fun parseLandmarkJson(rawJson: String): ArchitecturalLandmarkResult {
            val cleaned = OpenRouterClient.extractCleanJson(rawJson)
            val json = JSONObject(cleaned)
            val detected = json.optBoolean("landmarkDetected", false)
            val name = json.optString("landmarkName", "").trim().ifBlank {
                if (detected) "Architectural Structure" else "No landmark detected"
            }
            val style = json.optString("architecturalStyle", "").trim()
            val era = json.optString("eraOrYearBuilt", "Unknown").trim().ifBlank { "Unknown" }
            val architect = json.optString("architectOrCulture", "Unknown").trim().ifBlank { "Unknown" }
            val locationHint = json.optString("locationHint", "").trim()
            val confidence = LandmarkConfidence.fromRaw(json.optString("confidence", "Low"))
            val summary = json.optString("summary", "").trim()
            val featuresArray = json.optJSONArray("notableFeatures")
            val features = buildList {
                if (featuresArray != null) {
                    for (i in 0 until featuresArray.length()) {
                        val item = featuresArray.optString(i, "").trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
            }.take(4)
            val tip = json.optString("photographyTip", "").trim()

            return ArchitecturalLandmarkResult(
                landmarkDetected = detected,
                landmarkName = name,
                architecturalStyle = style,
                eraOrYearBuilt = era,
                architectOrCulture = architect,
                locationHint = locationHint,
                confidence = confidence,
                summary = summary,
                notableFeatures = features,
                photographyTip = tip
            )
        }
    }
}
