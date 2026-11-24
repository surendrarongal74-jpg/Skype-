package com.example.live.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.live.permissions.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

data class CapturedScreenFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long = System.currentTimeMillis()
)

class ScreenCaptureManager private constructor(private val appContext: Context) {

    private val projectionManager: MediaProjectionManager? by lazy {
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    private val permissionManager: PermissionManager by lazy {
        PermissionManager.getInstance(appContext)
    }

    private var mediaProjection: MediaProjection? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user")
            cleanupProjectionInternal(notifyStop = false)
        }
    }

    fun createScreenCaptureIntent(): Intent? {
        return runCatching { projectionManager?.createScreenCaptureIntent() }.getOrNull()
    }

    /**
     * Must be called ONLY after a foreground service with FOREGROUND_SERVICE_MEDIA_PROJECTION
     * is already running (enforced on Android 14+).
     */
    fun attachProjection(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            _lastError.value = "Screen sharing consent was declined."
            _isAuthorized.value = false
            permissionManager.setMediaProjectionAuthorized(false)
            return false
        }
        val manager = projectionManager
        if (manager == null) {
            _lastError.value = "MediaProjectionManager is unavailable on this device."
            return false
        }

        cleanupProjectionInternal(notifyStop = true)

        return try {
            val thread = HandlerThread("AlloraScreenCapture").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            val projection = manager.getMediaProjection(resultCode, data)
            if (projection == null) {
                _lastError.value = "Android did not grant a MediaProjection session."
                cleanupProjectionInternal(notifyStop = false)
                false
            } else {
                projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                mediaProjection = projection
                _isAuthorized.value = true
                _isPaused.value = false
                _lastError.value = null
                permissionManager.setMediaProjectionAuthorized(true)
                true
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException attaching MediaProjection", se)
            _lastError.value = "MediaProjection requires an active foreground screen-sharing service."
            cleanupProjectionInternal(notifyStop = false)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Error attaching MediaProjection", t)
            _lastError.value = t.localizedMessage ?: "Failed to start screen sharing."
            cleanupProjectionInternal(notifyStop = false)
            false
        }
    }

    fun setPaused(paused: Boolean) {
        if (!_isAuthorized.value) return
        _isPaused.value = paused
    }

    fun stopSharing() {
        cleanupProjectionInternal(notifyStop = true)
    }

    /**
     * Captures a single on-demand in-memory frame ONLY while authorized and unpaused.
     * Caller owns recycling the returned Bitmap. Nothing is ever written to disk.
     */
    suspend fun captureCurrentFrame(maxDimensionPx: Int = 1080): Result<CapturedScreenFrame> =
        withContext(Dispatchers.IO) {
            val projection = mediaProjection
            if (!_isAuthorized.value || projection == null) {
                return@withContext Result.failure(
                    IllegalStateException("Screen sharing is not active. Start screen sharing and grant Android consent first.")
                )
            }
            if (_isPaused.value) {
                return@withContext Result.failure(
                    IllegalStateException("Screen sharing is paused. Resume screen sharing to analyze the current screen.")
                )
            }

            val metrics = resolveDisplayMetrics()
            val rawW = metrics.widthPixels.coerceAtLeast(360)
            val rawH = metrics.heightPixels.coerceAtLeast(640)
            val scale = min(1f, maxDimensionPx.toFloat() / maxOf(rawW, rawH).toFloat())
            val targetW = (rawW * scale).toInt().coerceAtLeast(240)
            val targetH = (rawH * scale).toInt().coerceAtLeast(320)
            val densityDpi = metrics.densityDpi.coerceAtLeast(160)

            var reader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null

            try {
                reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection.createVirtualDisplay(
                    "AlloraScreenReader",
                    targetW,
                    targetH,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    captureHandler
                )

                var bitmap: Bitmap? = null
                repeat(12) { attempt ->
                    delay(if (attempt == 0) 90L else 60L)
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val plane = image.planes.firstOrNull()
                            if (plane != null) {
                                val buffer = plane.buffer
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride
                                val rowPadding = (rowStride - pixelStride * targetW).coerceAtLeast(0)
                                val paddedWidth = targetW + rowPadding / pixelStride.coerceAtLeast(1)
                                val rawBitmap = Bitmap.createBitmap(
                                    paddedWidth,
                                    targetH,
                                    Bitmap.Config.ARGB_8888
                                )
                                rawBitmap.copyPixelsFromBuffer(buffer)
                                bitmap = if (paddedWidth != targetW) {
                                    val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, targetW, targetH)
                                    rawBitmap.recycle()
                                    cropped
                                } else {
                                    rawBitmap
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    if (bitmap != null) return@repeat
                }

                val captured = bitmap
                if (captured == null) {
                    Result.failure(
                        IllegalStateException(
                            "No readable frame was returned. Protected (FLAG_SECURE) or blank windows cannot be captured."
                        )
                    )
                } else {
                    Result.success(
                        CapturedScreenFrame(
                            bitmap = captured,
                            width = captured.width,
                            height = captured.height
                        )
                    )
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "MediaProjection frame capture rejected by OS", se)
                cleanupProjectionInternal(notifyStop = false)
                Result.failure(
                    IllegalStateException("Screen sharing permission expired or was revoked by Android.")
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Frame capture failed", t)
                Result.failure(t)
            } finally {
                runCatching { virtualDisplay?.release() }
                runCatching { reader?.close() }
            }
        }

    suspend fun captureJpegBytes(maxDimensionPx: Int = 896, quality: Int = 72): Result<ByteArray> {
        val frameResult = captureCurrentFrame(maxDimensionPx)
        val frame = frameResult.getOrElse { return Result.failure(it) }
        return try {
            val stream = ByteArrayOutputStream()
            frame.bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            Result.success(stream.toByteArray())
        } finally {
            if (!frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        }
    }

    private fun resolveDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            val resMetrics = appContext.resources.displayMetrics
            metrics.setTo(resMetrics)
        }
        return metrics
    }

    private fun cleanupProjectionInternal(notifyStop: Boolean) {
        val existing = mediaProjection
        mediaProjection = null
        if (existing != null) {
            runCatching { existing.unregisterCallback(projectionCallback) }
            if (notifyStop) {
                runCatching { existing.stop() }
            }
        }
        captureHandler = null
        runCatching { captureThread?.quitSafely() }
        captureThread = null

        _isAuthorized.value = false
        _isPaused.value = false
        permissionManager.setMediaProjectionAuthorized(false)
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"

        @Volatile
        private var INSTANCE: ScreenCaptureManager? = null

        fun getInstance(context: Context): ScreenCaptureManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScreenCaptureManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
