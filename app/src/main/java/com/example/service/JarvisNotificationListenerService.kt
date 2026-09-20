package com.example.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.MainActivity
import com.example.data.JarvisPreferences

class JarvisNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotifListener"

        @Volatile
        var isConnected = false
            private set

        @Volatile
        private var lastMessageKey: String = ""

        @Volatile
        private var lastMessageTime: Long = 0L

        fun isPermissionGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            return enabledListeners.contains(context.packageName)
        }

        fun openSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open notification listener settings: ${e.message}")
            }
        }
    }

    private lateinit var prefs: JarvisPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = JarvisPreferences(this)
        Log.d(TAG, "Jarvis Notification Listener Service created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.d(TAG, "Jarvis Notification Listener Service connected successfully")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.d(TAG, "Jarvis Notification Listener Service disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        val isWhatsApp = packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b"
        if (!isWhatsApp) return

        if (!prefs.isWhatsAppReaderEnabled) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract Title (Sender Name or Group)
        val title = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""

        // Extract Message Body
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        if (text.isBlank()) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (!lines.isNullOrEmpty()) {
                text = lines.lastOrNull()?.toString() ?: ""
            }
        }

        if (title.isBlank() || text.isBlank()) return

        // Filter out system or technical noise
        val lowerText = text.lowercase()
        val lowerTitle = title.lowercase()
        if (lowerText.contains("checking for new messages") ||
            lowerText.contains("whatsapp web is currently active") ||
            lowerText.contains("backup in progress") ||
            lowerText.contains("incoming voice call") ||
            lowerText.contains("incoming video call") ||
            lowerText.contains("calling...") ||
            (lowerTitle.contains("whatsapp") && lowerText.contains("messages from"))
        ) {
            return
        }

        // Deduplicate notifications arriving in rapid succession (e.g., grouped updates)
        val key = "$title:$text"
        val now = System.currentTimeMillis()
        if (key == lastMessageKey && (now - lastMessageTime) < 4000L) {
            return
        }
        lastMessageKey = key
        lastMessageTime = now

        Log.d(TAG, "Processing incoming WhatsApp message from '$title': '$text'")
        announceIncomingMessage(title, text)
    }

    private fun announceIncomingMessage(sender: String, message: String) {
        val announcement = "WhatsApp par $sender ne message kiya hai: $message"

        // 1. Speak through background service if active
        val bgService = JarvisBackgroundService.instance
        if (bgService != null) {
            bgService.speakAnnouncement(announcement)
        } else {
            MainActivity.instance?.speakFromUI(announcement)
        }

        // 2. Display in UI chat log if MainActivity is open
        MainActivity.instance?.runOnUiThread {
            MainActivity.instance?.displayIncomingNotification("WhatsApp", sender, message)
        }
    }
}
