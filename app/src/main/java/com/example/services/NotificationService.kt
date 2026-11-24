package com.example.services

import com.example.data.local.dao.UserNotificationDao
import com.example.data.local.entity.UserNotificationEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NotificationService(
    private val notificationDao: UserNotificationDao,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    fun observeNotifications(userId: String): Flow<List<UserNotificationEntity>> =
        notificationDao.observeNotifications(userId)

    suspend fun notify(
        userId: String,
        title: String,
        message: String,
        category: String,
        actionRoute: String? = null
    ): UserNotificationEntity {
        val entity = UserNotificationEntity(
            id = "notif_${UUID.randomUUID().toString().replace("-", "").take(14)}",
            userId = userId,
            title = title.take(160),
            message = message.take(1000),
            category = category.take(40),
            actionRoute = actionRoute?.take(80),
            isUnread = true,
            createdAt = System.currentTimeMillis()
        )
        notificationDao.upsertNotification(entity)
        runCatching { cloudSync?.syncNotification(entity) }
        return entity
    }

    suspend fun markAllRead(userId: String) {
        notificationDao.markAllRead(userId)
    }

    suspend fun clearLocalUserCache(userId: String) {
        notificationDao.deleteAllForUser(userId)
    }
}
