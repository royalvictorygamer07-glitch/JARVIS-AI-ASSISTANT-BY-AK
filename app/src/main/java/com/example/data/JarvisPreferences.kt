package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig

class JarvisPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() {
            val key = prefs.getString(KEY_API_KEY, "") ?: ""
            return if (key.isNotBlank()) key else BuildConfig.GEMINI_API_KEY
        }
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() {
            val m = prefs.getString(KEY_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash"
            return if (m.contains("3.5") || m.isBlank()) "gemini-2.5-flash" else m
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, "Puck") ?: "Puck"
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, "Assistant Mode") ?: "Assistant Mode"
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Sir") ?: "Sir"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var youtubeEnabled: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply()

    var youtubeApiKey: String
        get() = prefs.getString(KEY_YOUTUBE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YOUTUBE_API_KEY, value.trim()).apply()

    var isPowerOnline: Boolean
        get() = prefs.getBoolean(KEY_POWER_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_POWER_ONLINE, value).apply()

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_PERSONALITY = "personality"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
        private const val KEY_YOUTUBE_API_KEY = "youtube_api_key"
        private const val KEY_POWER_ONLINE = "power_online"
        private const val KEY_MIC_MUTED = "mic_muted"
    }
}
