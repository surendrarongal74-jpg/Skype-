package com.example.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.repository.WeatherRepository
import java.util.concurrent.TimeUnit

class WeatherSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val city = inputData.getString(KEY_CITY)?.trim().orEmpty()
        if (city.isBlank()) return Result.success()
        val repo = WeatherRepository.createDefault()
        return repo.getWeatherForCity(city, forceRefresh = true).fold(
            onSuccess = {
                Log.d(TAG, "Background weather sync updated $city (${it.tempCelsius}C)")
                Result.success()
            },
            onFailure = { err ->
                Log.w(TAG, "Background weather sync failed for $city", err)
                Result.retry()
            }
        )
    }

    companion object {
        private const val TAG = "WeatherSyncWorker"
        private const val WORK_NAME = "allora_periodic_weather_sync"
        const val KEY_CITY = "city"

        fun schedulePeriodic(context: Context, city: String) {
            if (city.isBlank()) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WeatherSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_CITY to city))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
