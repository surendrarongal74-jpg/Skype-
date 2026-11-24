package com.example.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.GeoCoordinate
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LocationTrackingState(
    val isTracking: Boolean = false,
    val currentLocation: GeoCoordinate? = null,
    val accuracyMeters: Float? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val providerLabel: String = "Standby",
    val lastUpdatedMs: Long? = null,
    val errorMessage: String? = null
)

class TravelLocationTracker private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private val _state = MutableStateFlow(LocationTrackingState())
    val state: StateFlow<LocationTrackingState> = _state.asStateFlow()

    private var activeCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun fetchSingleFix(onComplete: (GeoCoordinate?, Float?) -> Unit = { _, _ -> }) {
        if (!hasAnyLocationPermission(appContext)) {
            _state.update {
                it.copy(errorMessage = "Location permission is required to fetch your current position.")
            }
            onComplete(null, null)
            return
        }

        val priority = if (hasFineLocationPermission(appContext)) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cts = CancellationTokenSource()
        try {
            fusedClient.getCurrentLocation(priority, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        publishLocation(location)
                        onComplete(GeoCoordinate(location.latitude, location.longitude), location.accuracy)
                    } else {
                        fallbackToLastKnown(onComplete)
                    }
                }
                .addOnFailureListener {
                    fallbackToLastKnown(onComplete)
                }
        } catch (sec: SecurityException) {
            _state.update { it.copy(errorMessage = sec.localizedMessage ?: "Location permission denied.") }
            onComplete(null, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToLastKnown(onComplete: (GeoCoordinate?, Float?) -> Unit) {
        try {
            fusedClient.lastLocation
                .addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) {
                        publishLocation(lastLoc)
                        onComplete(GeoCoordinate(lastLoc.latitude, lastLoc.longitude), lastLoc.accuracy)
                    } else {
                        _state.update {
                            it.copy(errorMessage = "Waiting for GPS or network fix.")
                        }
                        onComplete(null, null)
                    }
                }
                .addOnFailureListener { err ->
                    _state.update { it.copy(errorMessage = err.localizedMessage ?: "Location unavailable.") }
                    onComplete(null, null)
                }
        } catch (sec: SecurityException) {
            _state.update { it.copy(errorMessage = sec.localizedMessage ?: "Location permission denied.") }
            onComplete(null, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(): Boolean {
        if (!hasAnyLocationPermission(appContext)) {
            _state.update {
                it.copy(
                    isTracking = false,
                    errorMessage = "Grant location permission to start live travel tracking."
                )
            }
            return false
        }
        if (activeCallback != null) {
            _state.update { it.copy(isTracking = true, errorMessage = null) }
            return true
        }

        val hasFine = hasFineLocationPermission(appContext)
        val request = LocationRequest.Builder(
            if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            5_000L
        )
            .setMinUpdateIntervalMillis(2_500L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                publishLocation(loc)
            }
        }

        return try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            activeCallback = callback
            _state.update {
                it.copy(
                    isTracking = true,
                    providerLabel = if (hasFine) "Fused GPS (Active)" else "Balanced Network (Active)",
                    errorMessage = null
                )
            }
            fetchSingleFix()
            true
        } catch (sec: SecurityException) {
            _state.update {
                it.copy(
                    isTracking = false,
                    errorMessage = sec.localizedMessage ?: "Location permission denied."
                )
            }
            false
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    isTracking = false,
                    errorMessage = t.localizedMessage ?: "Unable to start location updates."
                )
            }
            false
        }
    }

    fun stopUpdates(clearLastFix: Boolean = false) {
        activeCallback?.let { cb ->
            runCatching { fusedClient.removeLocationUpdates(cb) }
        }
        activeCallback = null
        _state.update { current ->
            if (clearLastFix) {
                LocationTrackingState()
            } else {
                current.copy(
                    isTracking = false,
                    providerLabel = "Standby"
                )
            }
        }
    }

    internal fun publishSyntheticFixForTest(
        coordinate: GeoCoordinate,
        accuracyMeters: Float = 8.0f,
        isTracking: Boolean = _state.value.isTracking
    ) {
        _state.update {
            it.copy(
                isTracking = isTracking,
                currentLocation = coordinate,
                accuracyMeters = accuracyMeters,
                providerLabel = "Fused GPS",
                lastUpdatedMs = System.currentTimeMillis(),
                errorMessage = null
            )
        }
    }

    private fun publishLocation(location: Location) {
        val coord = GeoCoordinate(location.latitude, location.longitude)
        val hasFine = hasFineLocationPermission(appContext)
        _state.update {
            it.copy(
                currentLocation = coord,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                providerLabel = if (hasFine) "Fused GPS" else "Fused Network",
                lastUpdatedMs = System.currentTimeMillis(),
                errorMessage = null
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: TravelLocationTracker? = null

        fun getInstance(context: Context): TravelLocationTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TravelLocationTracker(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun hasAnyLocationPermission(context: Context): Boolean {
            return hasFineLocationPermission(context) ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasFineLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

class TravelLocationService : Service() {

    private lateinit var tracker: TravelLocationTracker

    override fun onCreate() {
        super.onCreate()
        tracker = TravelLocationTracker.getInstance(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TRACKING -> {
                tracker.stopUpdates(clearLastFix = false)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_TRACKING, null -> {
                if (!TravelLocationTracker.hasAnyLocationPermission(this)) {
                    tracker.stopUpdates(clearLastFix = false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val notification = buildTrackingNotification()
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
                    tracker.startUpdates()
                } catch (_: SecurityException) {
                    tracker.stopUpdates(clearLastFix = false)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tracker.stopUpdates(clearLastFix = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.location_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.location_service_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildTrackingNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TravelLocationService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.location_service_notif_title))
            .setContentText(getString(R.string.location_service_notif_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(
                0,
                getString(R.string.location_service_stop_action),
                stopPending
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "allora_live_location"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START_TRACKING = "com.example.location.ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.location.ACTION_STOP_TRACKING"

        fun startTracking(context: Context): Boolean {
            if (!TravelLocationTracker.hasAnyLocationPermission(context)) {
                return false
            }
            val appCtx = context.applicationContext
            val tracker = TravelLocationTracker.getInstance(appCtx)
            if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                return tracker.startUpdates()
            }
            val intent = Intent(appCtx, TravelLocationService::class.java).apply {
                action = ACTION_START_TRACKING
            }
            return runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
                true
            }.getOrElse {
                tracker.startUpdates()
            }
        }

        fun stopTracking(context: Context, clearLastFix: Boolean = false) {
            val appCtx = context.applicationContext
            TravelLocationTracker.getInstance(appCtx).stopUpdates(clearLastFix = clearLastFix)
            if (!Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                runCatching {
                    appCtx.stopService(Intent(appCtx, TravelLocationService::class.java))
                }
            }
        }
    }
}
