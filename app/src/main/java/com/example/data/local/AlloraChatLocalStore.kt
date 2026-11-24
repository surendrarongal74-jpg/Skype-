package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.ui.chat.AlloraChatThread
import com.example.ui.chat.ChatBlockJsonParser

/**
 * Local persistence for rich structured Allora chat threads (blocks, quotes, order state, attachments)
 * scoped by authenticated userId so conversations survive app restarts immediately offline.
 */
class AlloraChatLocalStore(
    private val prefs: SharedPreferences
) {
    fun loadThreads(): List<AlloraChatThread> {
        val raw = prefs.getString(KEY_THREADS_JSON, null).orEmpty()
        return ChatBlockJsonParser.deserializeThreads(raw)
    }

    fun loadActiveThreadId(): String? = prefs.getString(KEY_ACTIVE_THREAD_ID, null)

    fun saveState(threads: List<AlloraChatThread>, activeThreadId: String) {
        val serialized = ChatBlockJsonParser.serializeThreads(threads.take(25))
        prefs.edit()
            .putString(KEY_THREADS_JSON, serialized)
            .putString(KEY_ACTIVE_THREAD_ID, activeThreadId)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_THREADS_JSON = "threads_json"
        private const val KEY_ACTIVE_THREAD_ID = "active_thread_id"

        fun forUser(context: Context, userId: String): AlloraChatLocalStore {
            val safeUid = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "default" }
            val prefs = context.applicationContext.getSharedPreferences(
                "allora_rich_chat_$safeUid",
                Context.MODE_PRIVATE
            )
            return AlloraChatLocalStore(prefs)
        }
    }
}
