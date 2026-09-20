package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemControlHelper(private val context: Context) {

    companion object {
        private const val TAG = "SystemControlHelper"
    }

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private var isTorchOn = false

    fun toggleTorch(): String {
        return setTorch(!isTorchOn)
    }

    fun setTorch(enable: Boolean): String {
        return try {
            val cm = cameraManager ?: return "Camera hardware service is unavailable."
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList.firstOrNull() ?: return "No camera flash detected on this device."

            cm.setTorchMode(cameraId, enable)
            isTorchOn = enable
            if (enable) "Flashlight turned on, sir." else "Flashlight turned off, sir."
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle error: ${e.message}")
            "Unable to toggle flashlight: ${e.localizedMessage}"
        }
    }

    fun adjustVolume(increase: Boolean): String {
        return try {
            val am = audioManager ?: return "Audio service unavailable."
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = (currentVol * 100) / maxVol
            "Media volume adjusted to $pct%, sir."
        } catch (e: Exception) {
            "Error adjusting volume: ${e.localizedMessage}"
        }
    }

    fun setVolumePercent(percent: Int): String {
        return try {
            val am = audioManager ?: return "Audio service unavailable."
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (percent * maxVol / 100).coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            "Volume set to $percent%, sir."
        } catch (e: Exception) {
            "Error setting volume: ${e.localizedMessage}"
        }
    }

    fun muteAudio(mute: Boolean): String {
        return try {
            val am = audioManager ?: return "Audio service unavailable."
            if (mute) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                "Audio output muted, sir."
            } else {
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, AudioManager.FLAG_SHOW_UI)
                "Audio output unmuted, sir."
            }
        } catch (e: Exception) {
            "Error updating audio mute: ${e.localizedMessage}"
        }
    }

    fun getBatteryStatus(): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = bm?.isCharging == true
            val chargingState = if (isCharging) "charging" else "on battery power"
            if (level >= 0) {
                "Battery power cell is at $level%, currently $chargingState, sir."
            } else {
                "Battery telemetry is currently unreadable."
            }
        } catch (e: Exception) {
            "Error reading battery status: ${e.localizedMessage}"
        }
    }

    fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("h:mm a, EEEE, MMMM d", Locale.getDefault())
        return "Current time is ${sdf.format(Date())}, sir."
    }

    fun openCamera(): String {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Camera opened, sir."
        } catch (e: Exception) {
            "Unable to launch camera: ${e.localizedMessage}"
        }
    }

    fun searchOrPlayYouTube(query: String): String {
        return try {
            val cleanQuery = query.trim()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery))
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Verify if YouTube app is available, otherwise open in browser
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            }
            "Searching and playing '$cleanQuery' on YouTube, sir."
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search error: ${e.message}")
            "Error launching YouTube search: ${e.localizedMessage}"
        }
    }

    fun searchGoogle(query: String): String {
        return try {
            val cleanQuery = query.trim()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(cleanQuery))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Searching Google for '$cleanQuery', sir."
        } catch (e: Exception) {
            "Unable to search Google: ${e.localizedMessage}"
        }
    }

    fun makePhoneCall(target: String): String {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            return "Please provide a name or phone number to call, sir."
        }

        // Check if digits
        val isNumeric = cleanTarget.replace(Regex("[+\\-\\s()]"), "").all { it.isDigit() }
        var phoneNumber = if (isNumeric) cleanTarget else ""
        var resolvedName = cleanTarget

        if (!isNumeric) {
            // Lookup in contacts
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val cursor: Cursor? = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$cleanTarget%"),
                        null
                    )
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            if (nameIdx >= 0) resolvedName = c.getString(nameIdx)
                            if (numIdx >= 0) phoneNumber = c.getString(numIdx)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Contact search error: ${e.message}")
                }
            }
        }

        return try {
            if (phoneNumber.isNotEmpty()) {
                val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val intent = Intent(action, Uri.parse("tel:" + Uri.encode(phoneNumber))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Calling $resolvedName ($phoneNumber), sir."
            } else {
                // Open dialer with searched name or direct dialer
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Could not find phone number for '$cleanTarget'. Opening dialer, sir."
            }
        } catch (e: Exception) {
            "Unable to place call: ${e.localizedMessage}"
        }
    }

    fun sendWhatsApp(message: String, targetPhone: String? = null): String {
        return try {
            val encoded = Uri.encode(message)
            val uriStr = if (!targetPhone.isNullOrBlank()) {
                val cleanPhone = targetPhone.replace(Regex("[^0-9+]"), "")
                "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encoded"
            } else {
                "https://api.whatsapp.com/send?text=$encoded"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening WhatsApp with your message, sir."
        } catch (e: Exception) {
            "Unable to open WhatsApp: ${e.localizedMessage}"
        }
    }

    fun openSettings(section: String? = null): String {
        return try {
            val action = when (section?.lowercase(Locale.ROOT)) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening ${section ?: "System"} Settings, sir."
        } catch (e: Exception) {
            "Unable to open settings: ${e.localizedMessage}"
        }
    }
}
