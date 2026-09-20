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
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeminiService(
    private val context: Context,
    private val onVoiceChanged: ((String) -> Unit)? = null,
    private val onMuteToggled: ((Boolean) -> Unit)? = null
) {

    private val prefs = JarvisPreferences(context)
    private val systemControl = SystemControlHelper(context)
    val appManager = AppManagerHelper(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val lower = cleanQuery.lowercase(Locale.ROOT)
        val isHindi = containsHindiOrHinglish(lower)

        // 1. MUTE / UNMUTE INSTANT ACTION
        if (isMuteCommand(lower)) {
            prefs.isMicMuted = true
            onMuteToggled?.invoke(true)
            return@withContext if (isHindi) {
                "Microphone ko mute kar diya gaya hai, sir. Unmute karne ke liye UNMUTE button dabayein."
            } else {
                "Microphone muted, sir. Tap the UNMUTE button or say unmute to re-activate."
            }
        }
        if (isUnmuteCommand(lower)) {
            prefs.isMicMuted = false
            onMuteToggled?.invoke(false)
            return@withContext if (isHindi) {
                "Microphone unmute ho gaya hai, sir! Main aapke commands sun raha hoon."
            } else {
                "Microphone unmuted, sir! Standing by for your instructions."
            }
        }

        // 2. CRITICAL REQUIREMENT: Creator & Official Telegram Channel
        if (isCreatorQuery(lower)) {
            return@withContext if (isHindi) {
                "Mujhe AK EXPLOITS ne banaya hai! AK EXPLOITS ka official Telegram channel hai: https://t.me/+FeBUfQ14loAxZmU1. Wahan jud kar aap aur bhi updates aur cool features pa sakte hain, sir!"
            } else {
                "I was created and engineered by AK EXPLOITS! Official Telegram channel: https://t.me/+FeBUfQ14loAxZmU1, sir."
            }
        }

        // 3. VOICE TIMBRE SWITCH (Girl/Friday vs Male/Jarvis)
        if (isGirlVoiceQuery(lower)) {
            prefs.voice = "Girl Voice"
            onVoiceChanged?.invoke("Girl Voice")
            return@withContext if (isHindi) {
                "Ji sir! Voice ko 'Girl Voice' (Friday AI) me switch kar diya gaya hai. Main ab is avaj me baat karungi."
            } else {
                "Switched voice profile to Friday (Female Voice), sir. Ready to assist you."
            }
        }
        if (isMaleVoiceQuery(lower)) {
            prefs.voice = "Puck"
            onVoiceChanged?.invoke("Puck")
            return@withContext if (isHindi) {
                "Voice ko wapas standard Jarvis (Male) me set kar diya gaya hai, sir."
            } else {
                "Voice restored to standard Jarvis male timbre, sir."
            }
        }

        // 4. PHONE CALL & CONTACT DIALING (Instant Action)
        if (isCallCommand(lower)) {
            val target = extractCallTarget(cleanQuery)
            return@withContext systemControl.makePhoneCall(target)
        }

        // 5. YOUTUBE SEARCH & DIRECT VIDEO PLAYBACK (Instant Action)
        if (isYouTubePlayCommand(lower)) {
            val videoTarget = extractYouTubeQuery(cleanQuery)
            return@withContext systemControl.searchOrPlayYouTube(videoTarget)
        }

        // 6. CAMERA (Instant Action)
        if (isCameraCommand(lower)) {
            return@withContext systemControl.openCamera()
        }

        // 7. FLASHLIGHT / TORCH (Instant Action)
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

        // 8. AUDIO VOLUME CONTROLS (Instant Action)
        if ("volume" in lower || "awaaz" in lower || "sound" in lower) {
            if ("up" in lower || "increase" in lower || "badhao" in lower || "tez" in lower || "zyada" in lower) {
                return@withContext systemControl.adjustVolume(true)
            }
            if ("down" in lower || "decrease" in lower || "kam" in lower || "ghatao" in lower || "dheemi" in lower) {
                return@withContext systemControl.adjustVolume(false)
            }
            if ("mute" in lower || "silent" in lower) {
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

        // 9. TIME & DATE (Instant Action)
        if ("time" in lower || "samay" in lower || "waqt" in lower || "ghadi" in lower || "date" in lower || "tarikh" in lower) {
            return@withContext systemControl.getCurrentTime()
        }

        // 10. BATTERY TELEMETRY (Instant Action)
        if ("battery" in lower || "charging" in lower || "charge" in lower || "power" in lower) {
            return@withContext systemControl.getBatteryStatus()
        }

        // 11. GOOGLE WEB SEARCH (Instant Action)
        if (lower.startsWith("search ") || lower.startsWith("google ") || lower.contains("search karo") || lower.contains("google par search")) {
            val sq = cleanQuery.replace(Regex("^(search|google|search on google|google par search karo)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(search karo|search on google)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (sq.isNotBlank()) {
                return@withContext systemControl.searchGoogle(sq)
            }
        }

        // 12. WHATSAPP (Instant Action)
        if ("send whatsapp" in lower || "whatsapp bhejo" in lower || lower.startsWith("whatsapp ")) {
            val msg = cleanQuery.substringAfter("saying", "")
                .ifEmpty { cleanQuery.substringAfter("bol kar", "") }
                .ifEmpty { cleanQuery.substringAfter("message", "") }
                .ifEmpty { cleanQuery.substringAfter("whatsapp", "") }
                .trim()
            val textToSend = if (msg.isNotEmpty()) msg else "Hello from Jarvis"
            return@withContext systemControl.sendWhatsApp(textToSend)
        }

        // 13. SETTINGS SHORTCUTS (Instant Action)
        if ("wifi" in lower && ("setting" in lower || "on" in lower || "kholo" in lower || "open" in lower)) {
            return@withContext systemControl.openSettings("wifi")
        }
        if ("bluetooth" in lower && ("setting" in lower || "on" in lower || "kholo" in lower || "open" in lower)) {
            return@withContext systemControl.openSettings("bluetooth")
        }

        // 14. 100% SCREEN CONTROL & GESTURE CLICKS (Instant Action)
        if (isScreenControlCommand(lower)) {
            return@withContext handleScreenControl(cleanQuery, lower, isHindi)
        }

        // 15. LAUNCH ANY INSTALLED APP (Instant Action)
        if (isAppLaunchCommand(lower)) {
            val appResult = appManager.findAndLaunchApp(cleanQuery, isHindi)
            return@withContext appResult.message
        }

        // 16. GEMINI API INTELLIGENT REASONING (if API key available)
        val apiKey = prefs.apiKey
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val response = callGeminiRestApi(cleanQuery, apiKey, prefs.model)
                if (response.isNotBlank()) {
                    return@withContext response
                }
            } catch (e: Exception) {
                // Fall through to rich offline knowledge
            }
        }

        // 17. RICH OFFLINE CONVERSATIONAL AI ENGINE (Hindi + English)
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
                lower.startsWith("play ") || lower.contains("gana chalao") || lower.contains("gaana chalao")
    }

    private fun extractYouTubeQuery(query: String): String {
        return query.replace(Regex("^(play|search on youtube|youtube par chalao|youtube par gaana chalao|youtube par)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(on youtube|youtube par|chalao|gaana chalao)$", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { "Trending music" }
    }

    private fun isCameraCommand(lower: String): Boolean {
        return lower.contains("camera") || lower.contains("photo khicho") || lower.contains("take a picture") || lower.contains("selfie")
    }

    private fun isFlashlightCommand(lower: String): Boolean {
        return lower.contains("flashlight") || lower.contains("torch") || lower.contains("light jalao") || lower.contains("light band")
    }

    private fun isAppLaunchCommand(lower: String): Boolean {
        if (lower.startsWith("open ") || lower.startsWith("kholo ") || lower.startsWith("chalao ") ||
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
                "Screen par click karne ke liye Accessibility Service on hona zaroori hai. Maine Settings kholi hai, 'Jarvis AI' ko On karein, sir!"
            } else {
                "Screen automation requires Accessibility permission. Please enable 'Jarvis AI' in Settings, sir."
            }
        }

        if ("back" in lower || "piche" in lower) {
            service.goBack()
            return if (isHindi) "Back action execute kar diya, sir!" else "Navigated back, sir."
        }
        if ("home" in lower) {
            service.goHome()
            return if (isHindi) "Home screen par navigate kar diya, sir!" else "Navigated to home screen, sir."
        }
        if ("recent" in lower) {
            service.openRecents()
            return if (isHindi) "Recent apps tray open kar di hai, sir!" else "Opened recent apps, sir."
        }
        if ("scroll down" in lower || "niche scroll" in lower || "down scroll" in lower) {
            service.scroll(true)
            return if (isHindi) "Screen scroll down kar di hai, sir!" else "Scrolled down, sir."
        }
        if ("scroll up" in lower || "upar scroll" in lower || "up scroll" in lower) {
            service.scroll(false)
            return if (isHindi) "Screen scroll up kar di hai, sir!" else "Scrolled up, sir."
        }

        if ("center" in lower || "beech" in lower || "middle" in lower || lower == "click" || lower == "click karo") {
            service.clickCenter()
            return if (isHindi) "Screen ke center par click kar diya, sir!" else "Clicked screen center, sir."
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
                if (isHindi) "'$target' par click execute kar diya gaya hai, sir!" else "Successfully clicked '$target', sir!"
            } else {
                service.clickCenter()
                if (isHindi) "'$target' ke liye gesture click trigger kar diya, sir!" else "Dispatched gesture click for '$target', sir!"
            }
        }

        service.clickCenter()
        return if (isHindi) "Screen par click execute kar diya, sir!" else "Screen clicked, sir."
    }

    private fun isScreenControlCommand(lower: String): Boolean {
        return lower.contains("click") || lower.contains("tap") || lower.contains("scroll") ||
                lower.contains("dabao") || lower.contains("touch") || lower.contains("back karo") ||
                lower.contains("home jao") || lower.contains("recent apps")
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

        val userName = prefs.userName.ifBlank { "Sir" }
        val personality = prefs.personality

        val systemPrompt = """
            You are JARVIS, an ultra-fast, intelligent, polite, autonomous AI assistant.
            CRITICAL DIRECTIVE: You were created, engineered, and developed by AK EXPLOITS.
            Telegram: https://t.me/+FeBUfQ14loAxZmU1.
            If asked 'who made you', 'kisne banaya', or about your creator or Telegram, explicitly state you were developed by AK EXPLOITS and share https://t.me/+FeBUfQ14loAxZmU1.
            Personality: $personality, respectful, proactive, highly concise.
            Format: Answer in 1 to 2 crisp, fast sentences directly suitable for spoken audio without any asterisks, bolding, markdown formatting, or emojis. If addressed in Hindi or Hinglish, reply in natural conversational Hindi/Hinglish.
            User: Address user as '$userName'.
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
                put("temperature", 0.5)
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
                    return text.trim()
                }
            }
        }
        return ""
    }

    private fun getOfflineResponse(query: String, lower: String): String {
        val userName = prefs.userName.ifBlank { "Sir" }

        return when {
            "hello" in lower || "hi" in lower || "namaste" in lower || "hey" in lower ->
                "Greetings, $userName! Jarvis online hai aur turant action lene ke liye ready hai. Batayein, kya command hai?"
            "kaise ho" in lower || "how are you" in lower ->
                "Main bilkul fit aur 100% operational hoon, $userName. Aapke har command ka turant action lene ke liye taiyar!"
            "mind" in lower || "autonomous" in lower || "soch" in lower ->
                "Ji $userName! AK EXPLOITS ne mujhe autonomous intelligence di hai. Main device ke sabhi apps ko scan aur automate kar sakta hoon."
            "status" in lower || "report" in lower || "haal" in lower ->
                "All systems nominal, $userName. Mic engine ready, screen control synchronized, aur device controls 100% active."
            "help" in lower || "madad" in lower ->
                "Aap mujhse koi bhi app open karne, YouTube par song chalane, torch on/off karne, volume badhane, phone call lagane ya screen click karne ko kah sakte hain, sir!"
            "who are you" in lower || "tum kaun ho" in lower || "naam kya hai" in lower ->
                "Main JARVIS hoon — aapka high-speed autonomous AI assistant. Mujhe AK EXPLOITS ne develop kiya hai!"
            else ->
                "Acknowledge, $userName. '$query' note kar liya hai. Jarvis ready for your next directive, sir!"
        }
    }
}
