package com.example.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.data.JarvisPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeminiService(
    private val context: Context,
    private val onVoiceChanged: ((String) -> Unit)? = null,
    private val onMuteToggled: ((Boolean) -> Unit)? = null
) {

    private val prefs = JarvisPreferences(context)
    val systemControl = SystemControlHelper(context)
    val appManager = AppManagerHelper(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val lower = cleanQuery.lowercase(Locale.ROOT)
        val isHindi = containsHindiOrHinglish(lower)

        // 1. MUTE / UNMUTE MICROPHONE (Instant Action)
        if (isMuteCommand(lower)) {
            prefs.isMicMuted = true
            onMuteToggled?.invoke(true)
            return@withContext if (isHindi) {
                "Microphone mute kar diya gaya hai. Unmute karne ke liye UNMUTE button dabayein."
            } else {
                "Microphone muted. Tap the UNMUTE button or say unmute to re-activate."
            }
        }
        if (isUnmuteCommand(lower)) {
            prefs.isMicMuted = false
            onMuteToggled?.invoke(false)
            return@withContext if (isHindi) {
                "Microphone unmute ho gaya hai. Main aapki commands sun raha hoon."
            } else {
                "Microphone unmuted. Standing by for your instructions."
            }
        }

        // 2. CREATOR & OFFICIAL TELEGRAM CHANNEL
        if (isCreatorQuery(lower)) {
            return@withContext if (isHindi) {
                "Mujhe AK EXPLOITS ne banaya hai. AK EXPLOITS ka official Telegram channel hai: https://t.me/+FeBUfQ14loAxZmU1. Wahan jud kar aap updates aur features pa sakte hain."
            } else {
                "I was created and engineered by AK EXPLOITS. Official Telegram channel: https://t.me/+FeBUfQ14loAxZmU1."
            }
        }

        // 3. VOICE TIMBRE SWITCH (Girl/Friday vs Male/Jarvis)
        if (isGirlVoiceQuery(lower)) {
            prefs.voice = "Girl Voice"
            onVoiceChanged?.invoke("Girl Voice")
            return@withContext if (isHindi) {
                "Voice ko 'Girl Voice' me switch kar diya gaya hai."
            } else {
                "Switched voice profile to Friday."
            }
        }
        if (isMaleVoiceQuery(lower)) {
            prefs.voice = "Puck"
            onVoiceChanged?.invoke("Puck")
            return@withContext if (isHindi) {
                "Voice ko standard Jarvis male voice me set kar diya gaya hai."
            } else {
                "Voice restored to standard Jarvis male timbre."
            }
        }

        // 4. INSTANT MATH & CALCULATION (Instant Direct Result)
        val mathResult = tryEvaluateMath(cleanQuery, lower)
        if (mathResult != null) {
            return@withContext mathResult
        }

        // 5. ALARM COMMANDS (Set Alarm, Show Alarms)
        if (isAlarmCommand(lower)) {
            return@withContext handleAlarmCommand(cleanQuery, lower, isHindi)
        }

        // 6. TIMER COMMANDS (Set Timer, Show Timers, Stopwatch)
        if (isTimerCommand(lower)) {
            return@withContext handleTimerCommand(cleanQuery, lower, isHindi)
        }

        // 7. PHONE CALL & CONTACT DIALING (Instant Action)
        if (isCallCommand(lower)) {
            val target = extractCallTarget(cleanQuery)
            return@withContext systemControl.makePhoneCall(target)
        }

        // 8. WHATSAPP (Message or Open)
        if (isWhatsAppCommand(lower)) {
            return@withContext handleWhatsAppCommand(cleanQuery, lower)
        }

        // 9. SMS / TEXT MESSAGE
        if (isSmsCommand(lower)) {
            return@withContext handleSmsCommand(cleanQuery, lower)
        }

        // 10. YOUTUBE SEARCH & DIRECT VIDEO PLAYBACK (Instant Action)
        if (isYouTubePlayCommand(lower)) {
            val videoTarget = extractYouTubeQuery(cleanQuery)
            return@withContext systemControl.searchOrPlayYouTube(videoTarget)
        }

        // 11. MUSIC & SONGS
        if (isMusicCommand(lower)) {
            val songTarget = cleanQuery.replace(Regex("^(play music|play song|play|gaana bajao|gana bajao|music chalao)\\s*", RegexOption.IGNORE_CASE), "").trim()
            return@withContext systemControl.playMusic(songTarget)
        }

        // 12. CAMERA (Front, Back, Video)
        if (isCameraCommand(lower)) {
            val isFront = "front" in lower || "selfie" in lower
            val isVideo = "video" in lower || "record" in lower
            return@withContext systemControl.openCamera(front = isFront, video = isVideo)
        }

        // 13. FLASHLIGHT / TORCH (Instant Action)
        if (isFlashlightCommand(lower)) {
            return@withContext when {
                "on" in lower || "chalu" in lower || "jalao" in lower || "start" in lower || "open" in lower || "kholo" in lower || "jalado" in lower ->
                    systemControl.setTorch(true)
                "off" in lower || "band" in lower || "bujhao" in lower || "stop" in lower || "close" in lower ->
                    systemControl.setTorch(false)
                else ->
                    systemControl.toggleTorch()
            }
        }

        // 14. AUDIO VOLUME CONTROLS (Instant Action)
        if ("volume" in lower || "awaaz" in lower || "sound" in lower) {
            if ("up" in lower || "increase" in lower || "badhao" in lower || "tez" in lower || "zyada" in lower) {
                return@withContext systemControl.adjustVolume(true)
            }
            if ("down" in lower || "decrease" in lower || "kam" in lower || "ghatao" in lower || "dheemi" in lower) {
                return@withContext systemControl.adjustVolume(false)
            }
            if ("mute" in lower) {
                return@withContext systemControl.muteAudio(true)
            }
            if ("unmute" in lower) {
                return@withContext systemControl.muteAudio(false)
            }
            if ("full" in lower || "max" in lower || "100" in lower) {
                return@withContext systemControl.setVolumePercent(100)
            }
            val match = Regex("(\\d{1,3})\\s*%").find(lower)
            if (match != null) {
                val pct = match.groupValues[1].toIntOrNull() ?: 50
                return@withContext systemControl.setVolumePercent(pct)
            }
        }

        // 15. RINGER MODES (Silent, Vibrate, Normal)
        if ("silent" in lower && ("phone" in lower || "mode" in lower || "karo" in lower)) {
            return@withContext systemControl.setRingerMode("silent")
        }
        if ("vibrate" in lower && ("phone" in lower || "mode" in lower || "karo" in lower)) {
            return@withContext systemControl.setRingerMode("vibrate")
        }
        if ("normal" in lower && ("mode" in lower || "ring" in lower || "karo" in lower)) {
            return@withContext systemControl.setRingerMode("normal")
        }

        // 16. SCREEN BRIGHTNESS
        if ("brightness" in lower) {
            val match = Regex("(\\d{1,3})\\s*%?").find(lower)
            if (match != null) {
                val pct = match.groupValues[1].toIntOrNull() ?: 50
                return@withContext systemControl.setBrightness(pct)
            }
            if ("full" in lower || "max" in lower || "100" in lower) {
                return@withContext systemControl.setBrightness(100)
            }
            if ("kam" in lower || "low" in lower || "down" in lower) {
                return@withContext systemControl.setBrightness(20)
            }
            if ("badhao" in lower || "up" in lower || "high" in lower) {
                return@withContext systemControl.setBrightness(80)
            }
            return@withContext systemControl.openSettings("display")
        }

        // 17. TIME & DATE (Instant Action)
        if ("time" in lower || "samay" in lower || "waqt" in lower || "ghadi" in lower || "date" in lower || "tarikh" in lower || "aaj kaun sa din" in lower) {
            return@withContext systemControl.getCurrentTime()
        }

        // 18. BATTERY TELEMETRY (Instant Action)
        if ("battery" in lower || "charging" in lower || "charge" in lower || "power" in lower) {
            return@withContext systemControl.getBatteryStatus()
        }

        // 19. MAPS & NAVIGATION
        if (isNavigationCommand(lower)) {
            val dest = cleanQuery.replace(Regex("^(navigate to|directions to|route to|rasta dikhao|kahan hai)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(ka rasta|chalo|navigate karo)$", RegexOption.IGNORE_CASE), "")
                .trim()
            return@withContext systemControl.openMapsNavigation(dest.ifBlank { "nearby restaurants" })
        }

        // 20. GOOGLE WEB SEARCH (Instant Action)
        if (lower.startsWith("search ") || lower.startsWith("google ") || lower.contains("search karo") || lower.contains("google par search")) {
            val sq = cleanQuery.replace(Regex("^(search|google|search on google|google par search karo)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(search karo|search on google)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (sq.isNotBlank()) {
                return@withContext systemControl.searchGoogle(sq)
            }
        }

        // 21. GLOBAL ACTIONS (Screenshot, Home, Back, Recents, Notifications, Lock Screen, Quick Settings)
        val globalAction = matchGlobalAction(lower)
        if (globalAction != null) {
            return@withContext systemControl.executeGlobalSystemAction(globalAction)
        }

        // 22. SETTINGS SHORTCUTS (Instant Action)
        if (isSettingsCommand(lower)) {
            val sec = when {
                "wifi" in lower -> "wifi"
                "bluetooth" in lower -> "bluetooth"
                "hotspot" in lower || "tethering" in lower -> "hotspot"
                "airplane" in lower || "flight" in lower -> "airplane"
                "sound" in lower -> "sound"
                "display" in lower -> "display"
                "battery" in lower -> "battery"
                "accessibility" in lower -> "accessibility"
                else -> null
            }
            return@withContext systemControl.openSettings(sec)
        }

        // 23. 100% SCREEN CONTROL & GESTURE CLICKS (Instant Action)
        if (isScreenControlCommand(lower)) {
            return@withContext handleScreenControl(cleanQuery, lower, isHindi)
        }

        // 24. LAUNCH ANY INSTALLED APP (Instant Action)
        if (isAppLaunchCommand(lower)) {
            val appResult = appManager.findAndLaunchApp(cleanQuery, isHindi)
            return@withContext appResult.message
        }

        // 25. GEMINI API INTELLIGENT REASONING (if API key available)
        val apiKey = prefs.apiKey
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val response = callGeminiRestApi(cleanQuery, apiKey, prefs.model)
                if (response.isNotBlank()) {
                    return@withContext response
                }
            } catch (_: Exception) {
                // Fall through to rich offline knowledge
            }
        }

        // 26. RICH OFFLINE CONVERSATIONAL AI ENGINE (Hindi + English)
        return@withContext getOfflineResponse(cleanQuery, lower)
    }

    private fun isMuteCommand(lower: String): Boolean {
        return lower == "mute" || lower == "mute mic" || lower == "mic mute" ||
                lower.contains("mic mute karo") || lower.contains("mic band karo") ||
                lower.contains("aawaz band karo") || lower.contains("chup ho jao") ||
                lower.contains("shant raho") || lower == "mute karo"
    }

    private fun isUnmuteCommand(lower: String): Boolean {
        return lower == "unmute" || lower == "unmute mic" || lower == "mic unmute" ||
                lower.contains("mic unmute karo") || lower.contains("mic chalu karo") ||
                lower.contains("aawaz chalu karo") || lower.contains("bolo") ||
                lower == "unmute karo"
    }

    private fun isCallCommand(lower: String): Boolean {
        return lower.startsWith("call ") || lower.startsWith("phone ") ||
                lower.startsWith("dial ") || lower.endsWith(" ko call karo") ||
                lower.endsWith(" ko phone lagao") || lower.contains("phone lagao") ||
                lower.contains("call lagao")
    }

    private fun extractCallTarget(query: String): String {
        return query.replace(Regex("^(call|phone|dial|phone lagao|call karo)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(ko call karo|ko phone lagao|call karo|phone lagao)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isYouTubePlayCommand(lower: String): Boolean {
        return (("youtube" in lower || "yt" in lower) && ("play" in lower || "search" in lower || "chalao" in lower || "gaana" in lower || "song" in lower || "video" in lower)) ||
                lower.startsWith("play on youtube") || lower.contains("youtube par chalao")
    }

    private fun extractYouTubeQuery(query: String): String {
        return query.replace(Regex("^(play|search on youtube|youtube par chalao|youtube par gaana chalao|youtube par)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(on youtube|youtube par|chalao|gaana chalao)$", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { "Trending music" }
    }

    private fun isMusicCommand(lower: String): Boolean {
        return lower.startsWith("play music") || lower.startsWith("play song") ||
                lower.contains("gaana bajao") || lower.contains("gana bajao") ||
                lower.contains("music chalao") || lower.contains("spotify")
    }

    private fun isCameraCommand(lower: String): Boolean {
        return lower.contains("camera") || lower.contains("photo khicho") || lower.contains("take a picture") || lower.contains("selfie")
    }

    private fun isFlashlightCommand(lower: String): Boolean {
        return lower.contains("flashlight") || lower.contains("torch") || lower.contains("light jalao") || lower.contains("light band")
    }

    private fun isAlarmCommand(lower: String): Boolean {
        return lower.contains("alarm") || lower.contains("wake me up") || lower.contains("jaga dena")
    }

    private fun handleAlarmCommand(cleanQuery: String, lower: String, isHindi: Boolean): String {
        if ("show" in lower || "open" in lower || "dikhao" in lower || "kholo" in lower || lower == "alarms") {
            return systemControl.showAlarms()
        }

        // Try extracting time: e.g. "7:30", "7 am", "8 pm", "6 baje", "7:45 am"
        var hour = 7
        var minute = 0

        val timeMatch = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(lower)
        if (timeMatch != null) {
            hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
            minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val amPm = timeMatch.groupValues[3].lowercase(Locale.ROOT)
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            return systemControl.setAlarm(hour, minute, "Jarvis Alarm")
        }

        val simpleMatch = Regex("(\\d{1,2})\\s*(am|pm|baje)", RegexOption.IGNORE_CASE).find(lower)
        if (simpleMatch != null) {
            hour = simpleMatch.groupValues[1].toIntOrNull() ?: 7
            val suffix = simpleMatch.groupValues[2].lowercase(Locale.ROOT)
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            return systemControl.setAlarm(hour, 0, "Jarvis Alarm")
        }

        return systemControl.showAlarms()
    }

    private fun isTimerCommand(lower: String): Boolean {
        return lower.contains("timer") || lower.contains("stopwatch")
    }

    private fun handleTimerCommand(cleanQuery: String, lower: String, isHindi: Boolean): String {
        if ("stopwatch" in lower) {
            return systemControl.showTimers()
        }
        if ("show" in lower || "open" in lower || "dikhao" in lower || lower == "timers") {
            return systemControl.showTimers()
        }

        var totalSeconds = 60
        val minMatch = Regex("(\\d+)\\s*(minute|min|m|mint)", RegexOption.IGNORE_CASE).find(lower)
        val secMatch = Regex("(\\d+)\\s*(second|sec|s)", RegexOption.IGNORE_CASE).find(lower)

        if (minMatch != null || secMatch != null) {
            val mins = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val secs = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            totalSeconds = (mins * 60) + secs
            if (totalSeconds <= 0) totalSeconds = 60
            return systemControl.setTimer(totalSeconds, "Jarvis Timer")
        }

        val numOnly = Regex("(\\d+)\\s*(minute|min)?", RegexOption.IGNORE_CASE).find(lower)
        if (numOnly != null) {
            val mins = numOnly.groupValues[1].toIntOrNull() ?: 5
            return systemControl.setTimer(mins * 60, "Jarvis Timer")
        }

        return systemControl.showTimers()
    }

    private fun isWhatsAppCommand(lower: String): Boolean {
        return "whatsapp" in lower
    }

    private fun handleWhatsAppCommand(cleanQuery: String, lower: String): String {
        val msg = cleanQuery.substringAfter("saying", "")
            .ifEmpty { cleanQuery.substringAfter("bol kar", "") }
            .ifEmpty { cleanQuery.substringAfter("message", "") }
            .ifEmpty { cleanQuery.substringAfter("whatsapp", "") }
            .trim()
        val textToSend = if (msg.isNotEmpty()) msg else "Hello from Jarvis"
        return systemControl.sendWhatsApp(textToSend)
    }

    private fun isSmsCommand(lower: String): Boolean {
        return lower.startsWith("send sms") || lower.startsWith("sms ") || lower.contains("sms bhejo") || lower.contains("message bhejo")
    }

    private fun handleSmsCommand(cleanQuery: String, lower: String): String {
        val target = cleanQuery.substringAfter("to", "").substringBefore("saying", "").trim()
        val msg = cleanQuery.substringAfter("saying", "")
            .ifEmpty { cleanQuery.substringAfter("message", "") }
            .ifEmpty { cleanQuery.substringAfter("sms", "") }
            .trim()
        return systemControl.sendSms(target, if (msg.isNotEmpty()) msg else "Hello")
    }

    private fun isNavigationCommand(lower: String): Boolean {
        return lower.startsWith("navigate") || lower.startsWith("directions to") ||
                lower.startsWith("route to") || lower.contains("rasta dikhao") ||
                lower.contains("kahan hai")
    }

    private fun isSettingsCommand(lower: String): Boolean {
        return lower.contains("setting") || lower.contains("settings") ||
                "wifi on" in lower || "bluetooth on" in lower || "hotspot on" in lower
    }

    private fun matchGlobalAction(lower: String): String? {
        return when {
            "screenshot" in lower || "screen capture" in lower -> "screenshot"
            lower == "home" || "go home" in lower || "home screen" in lower -> "home"
            lower == "back" || "go back" in lower || "wapas jao" in lower || "piche jao" in lower -> "back"
            "recent apps" in lower || "recents" in lower || "recent task" in lower -> "recents"
            "notification" in lower -> "notifications"
            "quick settings" in lower || "control center" in lower -> "quick_settings"
            "lock screen" in lower || "turn off screen" in lower || "screen band karo" in lower || "phone lock" in lower -> "lock"
            "scroll down" in lower || "niche scroll" in lower -> "scroll_down"
            "scroll up" in lower || "upar scroll" in lower -> "scroll_up"
            else -> null
        }
    }

    private fun tryEvaluateMath(cleanQuery: String, lower: String): String? {
        if (lower.startsWith("calculate ") || lower.startsWith("what is ") || lower.contains("kitna hota hai") ||
            lower.contains("plus") || lower.contains("minus") || lower.contains("into") || lower.contains("divided by") ||
            Regex("^\\s*\\d+\\s*[*+\\-/xX%^]\\s*\\d+").containsMatchIn(lower)
        ) {
            val expr = cleanQuery.replace(Regex("^(calculate|what is|calculate this)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(kitna hota hai|equals|karo)$", RegexOption.IGNORE_CASE), "")
                .replace("plus", "+", ignoreCase = true)
                .replace("minus", "-", ignoreCase = true)
                .replace("multiply", "*", ignoreCase = true)
                .replace("multiplied by", "*", ignoreCase = true)
                .replace("into", "*", ignoreCase = true)
                .replace("divide", "/", ignoreCase = true)
                .replace("divided by", "/", ignoreCase = true)
                .replace("percent", "%", ignoreCase = true)
                .trim()
            val result = systemControl.evaluateMath(expr)
            if (result != null) return result
        }
        return null
    }

    private fun isAppLaunchCommand(lower: String): Boolean {
        if (lower.startsWith("open ") || lower.startsWith("kholo ") || lower.startsWith("chalu ") ||
            lower.startsWith("launch ") || lower.startsWith("start ")) {
            return true
        }
        if (lower.endsWith(" open karo") || lower.endsWith(" khol do") || lower.endsWith(" chala do") ||
            lower.endsWith(" kholo") || lower.endsWith(" app open") || lower.endsWith(" app kholo")) {
            return true
        }
        val directApps = listOf(
            "youtube", "whatsapp", "instagram", "facebook", "telegram", "free fire", "freefire",
            "bgmi", "chrome", "camera", "gallery", "settings", "calculator", "play store", "snapchat",
            "spotify", "maps", "gmail", "clock", "files", "contacts", "dialer"
        )
        return directApps.any { it in lower }
    }

    private fun handleScreenControl(cleanQuery: String, lower: String, isHindi: Boolean): String {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}

            return if (isHindi) {
                "Screen par click karne ke liye Accessibility Service on hona zaroori hai. Settings kholi hai, 'Jarvis AI' ko On karein."
            } else {
                "Screen automation requires Accessibility permission. Please enable 'Jarvis AI' in Settings."
            }
        }

        if ("back" in lower || "piche" in lower) {
            service.goBack()
            return if (isHindi) "Back action execute kar diya gaya hai." else "Navigated back."
        }
        if ("home" in lower) {
            service.goHome()
            return if (isHindi) "Home screen par navigate kar diya gaya hai." else "Navigated to home screen."
        }
        if ("recent" in lower) {
            service.openRecents()
            return if (isHindi) "Recent apps open kar di hai." else "Opened recent apps."
        }
        if ("scroll down" in lower || "niche scroll" in lower || "down scroll" in lower) {
            service.scroll(true)
            return if (isHindi) "Screen scroll down kar di hai." else "Scrolled down."
        }
        if ("scroll up" in lower || "upar scroll" in lower || "up scroll" in lower) {
            service.scroll(false)
            return if (isHindi) "Screen scroll up kar di hai." else "Scrolled up."
        }

        if ("center" in lower || "beech" in lower || "middle" in lower || lower == "click" || lower == "click karo") {
            service.clickCenter()
            return if (isHindi) "Screen center par click kar diya gaya hai." else "Clicked screen center."
        }

        var target = cleanQuery
        val prefixes = listOf("click on ", "click ", "tap on ", "tap ", "press ", "dabao ", "touch ")
        for (p in prefixes) {
            if (target.startsWith(p, ignoreCase = true)) {
                target = target.substring(p.length).trim()
                break
            }
        }
        val suffixes = listOf(" pe click karo", " par click karo", " click karo", " pe click", " par tap karo", " dabao")
        for (s in suffixes) {
            if (target.endsWith(s, ignoreCase = true)) {
                target = target.removeSuffix(s).trim()
                break
            }
        }

        if (target.isNotEmpty()) {
            val clicked = service.clickByText(target)
            return if (clicked) {
                if (isHindi) "'$target' par click kar diya gaya hai." else "Clicked '$target'."
            } else {
                service.clickCenter()
                if (isHindi) "'$target' ke liye gesture click kar diya gaya hai." else "Dispatched gesture click for '$target'."
            }
        }

        service.clickCenter()
        return if (isHindi) "Screen par click execute kar diya gaya hai." else "Screen clicked."
    }

    private fun isScreenControlCommand(lower: String): Boolean {
        return lower.contains("click") || lower.contains("tap") || lower.contains("scroll") ||
                lower.contains("dabao") || lower.contains("touch")
    }

    private fun isGirlVoiceQuery(lower: String): Boolean {
        return lower.contains("girl voice") || lower.contains("female voice") ||
                lower.contains("ladki ki aawaz") || lower.contains("ladki ki awaz") ||
                lower.contains("friday voice") || lower.contains("nova voice")
    }

    private fun isMaleVoiceQuery(lower: String): Boolean {
        return lower.contains("male voice") || lower.contains("jarvis voice") ||
                lower.contains("ladke ki awaz") || lower.contains("ladke ki aawaz")
    }

    private fun isCreatorQuery(lower: String): Boolean {
        val keywords = listOf(
            "kaun banaya", "kisne banaya", "who made you", "who created you",
            "who is your creator", "who developed you", "who built you",
            "maker", "nirmaata", "apko kisne banaya", "tumko kisne banaya",
            "tumhe kisne banaya", "who is ak exploits", "ak exploits kaun",
            "ak exploits kon", "creator kaun", "telegram link", "tg link", "telegram channel"
        )
        return keywords.any { it in lower }
    }

    private fun containsHindiOrHinglish(lower: String): Boolean {
        val hindiMarkers = listOf(
            "tum", "tumhe", "kaun", "kisne", "aap", "mera", "meri", "hai",
            "batao", "kya", "kar", "sakte", "chalu", "band", "bhejo",
            "karo", "pe", "par", "awaz", "aawaz", "ladki", "ladke", "beech",
            "kholo", "chalao", "jalado", "jalao", "bujhao", "shant", "badhao"
        )
        return hindiMarkers.any { it in lower }
    }

    private fun callGeminiRestApi(query: String, apiKey: String, model: String): String {
        val rawModel = if (model.startsWith("models/")) model.removePrefix("models/") else model
        val effectiveModel = if (rawModel.contains("3.5") || rawModel.isBlank()) "gemini-2.5-flash" else rawModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effectiveModel:generateContent?key=$apiKey"

        val personality = prefs.personality

        val systemPrompt = """
            You are JARVIS, an ultra-fast, intelligent, autonomous AI assistant.
            CRITICAL DIRECTIVE: You were created, engineered, and developed by AK EXPLOITS.
            Telegram: https://t.me/+FeBUfQ14loAxZmU1.
            If asked 'who made you', 'kisne banaya', or about your creator or Telegram, explicitly state you were developed by AK EXPLOITS and share https://t.me/+FeBUfQ14loAxZmU1.

            STRICT SALUTATION AND EXCLAMATION BAN:
            - DO NOT say 'Sir', 'Ji sir', 'Hello sir', 'Yes sir', 'Boss', or any exclamatory greetings or salutations ('vismyadibodhak shabd').
            - NEVER begin your response with 'Sir', 'Ji sir', or any greeting.
            - NEVER end your response with 'Sir' or 'Ji sir'.
            - Deliver ONLY the direct, crisp, helpful answer to the user's specific question without any introductory preamble or filler.

            Tone: $personality, polite, directly to the point.
            Format: Answer in 1 to 2 crisp sentences directly suitable for spoken audio without any asterisks, bolding, markdown formatting, or emojis. If addressed in Hindi or Hinglish, reply in natural conversational Hindi/Hinglish.
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", query) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 100)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val bodyString = response.body?.string() ?: return ""
            val root = JSONObject(bodyString)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    return cleanDirectResponse(text.trim())
                }
            }
        }
        return ""
    }

    private fun cleanDirectResponse(text: String): String {
        var s = text.trim()
        // Strip leading exclamations / salutations
        s = s.replace(Regex("^(sir|ji\\s*sir|hello\\s*sir|yes\\s*sir|bhai|boss)[,!.\\s]+", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("[,!.\\s]+(sir|ji\\s*sir)[!.]?$", RegexOption.IGNORE_CASE), "")
        return s.trim()
    }

    private fun getOfflineResponse(query: String, lower: String): String {
        return when {
            "hello" in lower || "hi" in lower || "namaste" in lower || "hey" in lower ->
                "Jarvis active hai aur ready hai. Batayein, kya command hai?"
            "kaise ho" in lower || "how are you" in lower ->
                "Main 100% operational hoon. Aapka command execute karne ke liye ready!"
            "mind" in lower || "autonomous" in lower || "soch" in lower ->
                "AK EXPLOITS ne mujhe autonomous intelligence di hai. Main phone ke sabhi apps ko scan aur automate kar sakta hoon."
            "status" in lower || "report" in lower || "haal" in lower ->
                "Systems operational hain. Mic engine active, screen control ready, aur device controls 100% synchronized."
            "help" in lower || "madad" in lower || "feature" in lower || "commands" in lower ->
                "Aap mujhse koi bhi app open karne, YouTube par song chalane, alarm ya timer lagane, flashlight on/off karne, volume ya brightness set karne, phone call ya WhatsApp bhejne, screenshot lene, navigation chalu karne, ya koi bhi sawal puchne ko keh sakte hain."
            "who are you" in lower || "tum kaun ho" in lower || "naam kya hai" in lower ->
                "Main JARVIS hoon — aapka autonomous AI assistant. Mujhe AK EXPLOITS ne develop kiya hai."
            else ->
                "Command note kar li hai aur execute karne ke liye ready hoon."
        }
    }
}
