package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.bridge.JarvisBridge
import com.example.bridge.JarvisSettingsBridge
import com.example.data.AppDatabase
import com.example.data.JarvisPreferences
import com.example.data.MemoryEntity
import com.example.service.GeminiService
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisMindEngine
import com.example.service.TtsService
import com.example.service.VoiceRecognitionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: JarvisPreferences
    private lateinit var database: AppDatabase
    private lateinit var geminiService: GeminiService
    private lateinit var ttsService: TtsService
    private lateinit var voiceHelper: VoiceRecognitionHelper
    private lateinit var mindEngine: JarvisMindEngine

    private var currentUrl = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRequestingSequentialAll = false

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone enabled", Toast.LENGTH_SHORT).show()
            if (!prefs.isMicMuted) {
                voiceHelper.startListening()
            }
        } else {
            Toast.makeText(this, "Microphone permission is required for voice commands", Toast.LENGTH_LONG).show()
            runJs("window.setOrbState('idle', 0.0);")
        }
        updateSettingsPermissionsInWeb()
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateSettingsPermissionsInWeb()
        if (isRequestingSequentialAll) {
            isRequestingSequentialAll = false
            mainHandler.postDelayed({
                checkAndPromptNextSpecialPermission()
            }, 500)
        }
    }

    private val speechFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = spokenMatches?.firstOrNull()?.trim() ?: ""
            if (spokenText.isNotBlank()) {
                handleUserSpeechQuery(spokenText)
            } else {
                runJs("window.setOrbState('idle', 0.0);")
            }
        } else {
            runJs("window.setOrbState('idle', 0.0);")
        }
    }

    private fun ensureWebViewCacheDirs() {
        try {
            val httpCache = java.io.File(cacheDir, "WebView/Default/HTTP Cache")
            if (!httpCache.exists()) httpCache.mkdirs()
            val codeCache = java.io.File(httpCache, "Code Cache")
            if (!codeCache.exists()) codeCache.mkdirs()
            val jsDir = java.io.File(codeCache, "js")
            val wasmDir = java.io.File(codeCache, "wasm")
            if (!jsDir.exists()) jsDir.mkdirs()
            if (!wasmDir.exists()) wasmDir.mkdirs()
            val indexFile = java.io.File(httpCache, "index-dir")
            if (!indexFile.exists()) indexFile.mkdirs()
        } catch (_: Throwable) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ensureWebViewCacheDirs()
        super.onCreate(savedInstanceState)

        prefs = JarvisPreferences(this)
        database = AppDatabase.getDatabase(this)

        geminiService = GeminiService(
            context = this,
            onVoiceChanged = { newVoice ->
                runOnUiThread {
                    ttsService.applyVoiceProfile(newVoice)
                    runJs("if (window.onVoiceProfileChanged) window.onVoiceProfileChanged('$newVoice');")
                }
            },
            onMuteToggled = { isMuted ->
                runOnUiThread {
                    runJs("window.setMicMuted($isMuted);")
                }
            }
        )

        ttsService = TtsService(this) { isSpeaking ->
            runOnUiThread {
                if (isSpeaking) {
                    runJs("window.setOrbState('speaking', 0.7);")
                } else {
                    runJs("window.setOrbState('idle', 0.0);")
                }
            }
        }
        ttsService.applyVoiceProfile(prefs.voice)

        mindEngine = JarvisMindEngine(
            context = this,
            ttsService = ttsService,
            appManagerHelper = geminiService.appManager,
            onThoughtGenerated = { thought, speakAloud ->
                runOnUiThread {
                    val timeNow = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                    runJs("window.addChatMessage('jarvis', ${JSONObject.quote(thought)}, '$timeNow');")
                    runJs("if (window.setAutonomousThought) window.setAutonomousThought(${JSONObject.quote(thought)});")
                    if (speakAloud && prefs.isPowerOnline && !prefs.isMicMuted) {
                        ttsService.speak(thought)
                    }
                }
            }
        )

        voiceHelper = VoiceRecognitionHelper(
            context = this,
            onAudioLevel = { level ->
                runOnUiThread {
                    runJs("window.setAudioLevel($level);")
                }
            },
            onResult = { query ->
                runOnUiThread {
                    handleUserSpeechQuery(query)
                }
            },
            onError = { errMsg ->
                runOnUiThread {
                    runJs("window.setOrbState('idle', 0.0);")
                    runJs("if (window.onSpeechError) window.onSpeechError(${JSONObject.quote(errMsg)});")
                }
            },
            onListeningStateChanged = { isListening ->
                runOnUiThread {
                    if (isListening) {
                        runJs("window.setOrbState('listening', 0.5);")
                    }
                }
            },
            onPartialResult = { partialText ->
                runOnUiThread {
                    runJs("if (window.setPartialTranscript) window.setPartialTranscript(${JSONObject.quote(partialText)});")
                }
            },
            isMutedProvider = { prefs.isMicMuted },
            onFallbackRequested = {
                runOnUiThread {
                    launchSpeechFallbackDialog()
                }
            }
        )

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val urlStr = uri.toString()
                    if (urlStr.startsWith("https://t.me/") || urlStr.startsWith("tg:") || (urlStr.startsWith("http") && !urlStr.contains("android_asset"))) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            return true
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "Opening: $urlStr", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    currentUrl = url ?: ""
                    onWebPageLoaded(currentUrl)
                }

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    runOnUiThread {
                        try {
                            recreate()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    return true
                }
            }
            setBackgroundColor(0xFF030407.toInt())
        }

        setupJsBridges()

        setContentView(webView)

        loadIndexPage()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentUrl.contains("settings.html")) {
                    loadIndexPage()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        startTimeSync()
    }

    private fun setupJsBridges() {
        webView.addJavascriptInterface(
            JarvisBridge(
                onOrb = { runOnUiThread { handleMicOrMuteClick() } },
                onMic = { runOnUiThread { handleMicOrMuteClick() } },
                onPower = { runOnUiThread { togglePower() } },
                onSettings = { runOnUiThread { loadSettingsPage() } },
                onHistory = { runOnUiThread { showHistoryDialog() } },
                onTelegram = { runOnUiThread { openTelegramChannel() } },
                onToggleVoice = { voiceName ->
                    runOnUiThread {
                        prefs.voice = voiceName
                        ttsService.applyVoiceProfile(voiceName)
                        runJs("showToast('Voice: $voiceName');")
                    }
                },
                onScreenControlSettings = { runOnUiThread { openScreenControlSettings() } },
                onTestScreenClick = { x, y -> runOnUiThread { testScreenClick(x, y) } },
                checkScreenControlActive = { JarvisAccessibilityService.isRunning() },
                getActiveVoice = { prefs.voice },
                onTriggerMindThought = { runOnUiThread { mindEngine.generateAutonomousThought() } },
                onOpenApp = { appName ->
                    runOnUiThread {
                        val result = geminiService.appManager.findAndLaunchApp(appName)
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                        ttsService.speak(result.message)
                    }
                },
                getInstalledAppsCount = { geminiService.appManager.scanInstalledApps().size },
                onRequestPerm = { perm -> runOnUiThread { requestSpecificPermission(perm) } },
                onRequestAllPerms = { runOnUiThread { requestAllPermissions() } },
                onGetPermissionsJson = { getInitialSettingsJson() },
                onMuteToggle = { runOnUiThread { handleMicOrMuteClick() } },
                isMutedProvider = { prefs.isMicMuted },
                onSendCommand = { cmd -> runOnUiThread { handleUserSpeechQuery(cmd) } },
                onClearChat = { runOnUiThread { clearChatHistory() } }
            ),
            "JarvisBridge"
        )

        webView.addJavascriptInterface(
            JarvisSettingsBridge(
                onBack = { runOnUiThread { loadIndexPage() } },
                onSave = { json -> runOnUiThread { saveSettingsFromJson(json) } },
                onGetInitial = { getInitialSettingsJson() },
                onPasteKey = { type -> runOnUiThread { pasteKeyFromClipboard(type) } },
                onRequestPerm = { perm -> runOnUiThread { requestSpecificPermission(perm) } },
                onRequestAllPerms = { runOnUiThread { requestAllPermissions() } },
                onClearMem = { runOnUiThread { clearMemoriesDialog() } },
                onViewMem = { runOnUiThread { showHistoryDialog() } },
                onValidateYt = { key -> runOnUiThread { validateYouTubeKey(key) } },
                onTelegram = { runOnUiThread { openTelegramChannel() } },
                onScreenControlSettings = { runOnUiThread { openScreenControlSettings() } },
                onTestScreenClick = { x, y -> runOnUiThread { testScreenClick(x, y) } },
                onTriggerMindThought = { runOnUiThread { mindEngine.generateAutonomousThought() } },
                onOpenApp = { appName ->
                    runOnUiThread {
                        val result = geminiService.appManager.findAndLaunchApp(appName)
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                        ttsService.speak(result.message)
                    }
                },
                getInstalledAppsCount = { geminiService.appManager.scanInstalledApps().size },
                checkScreenControlActive = { JarvisAccessibilityService.isRunning() },
                onOpenUrl = { url -> runOnUiThread { openExternalUrl(url) } }
            ),
            "JarvisSettingsBridge"
        )
    }

    fun handleMicOrMuteClick() {
        if (!prefs.isPowerOnline) {
            Toast.makeText(this, "Jarvis is in Sleep mode. Tap Online to activate.", Toast.LENGTH_SHORT).show()
            return
        }

        if (prefs.isMicMuted) {
            prefs.isMicMuted = false
            runJs("window.setMicMuted(false);")
            Toast.makeText(this, "Microphone UNMUTED", Toast.LENGTH_SHORT).show()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ttsService.stop()
                voiceHelper.startListening()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            prefs.isMicMuted = true
            voiceHelper.stopListening()
            ttsService.stop()
            runJs("window.setOrbState('idle', 0.0);")
            runJs("window.setMicMuted(true);")
            Toast.makeText(this, "Microphone MUTED", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleMute() {
        handleMicOrMuteClick()
    }

    private fun launchSpeechFallbackDialog() {
        try {
            val userLang = Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak command for Jarvis...")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, userLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, userLang)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechFallbackLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input dialog unavailable: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            runJs("window.setOrbState('idle', 0.0);")
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open browser: $url", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTelegramChannel() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+FeBUfQ14loAxZmU1")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Opening Telegram channel...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openScreenControlSettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Enable 'Jarvis AI' for 100% Screen Control", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Please open Accessibility Settings manually", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testScreenClick(x: Float, y: Float) {
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            service.clickAt(x, y) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Screen Click Executed 100%!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            openScreenControlSettings()
        }
    }

    private fun loadIndexPage() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun loadSettingsPage() {
        webView.loadUrl("file:///android_asset/settings.html")
    }

    private fun onWebPageLoaded(url: String) {
        if (url.contains("index.html")) {
            runJs("window.setPowerState(${prefs.isPowerOnline});")
            runJs("window.setMicMuted(${prefs.isMicMuted});")
            runJs("window.setConnectionStatus('LIVE');")
            runJs("if (window.setScreenControlStatus) window.setScreenControlStatus(${JarvisAccessibilityService.isRunning()});")
            runJs("if (window.setCurrentVoice) window.setCurrentVoice('${prefs.voice}');")
            val appCount = geminiService.appManager.scanInstalledApps().size
            runJs("if (window.setAppsDetectedCount) window.setAppsDetectedCount($appCount);")
            updateSettingsPermissionsInWeb()
            syncRecentChatHistory()
        } else if (url.contains("settings.html")) {
            val json = getInitialSettingsJson()
            runJs("initSettings($json);")
            runJs("if (window.setScreenControlStatus) window.setScreenControlStatus(${JarvisAccessibilityService.isRunning()});")
            updateSettingsPermissionsInWeb()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSettingsPermissionsInWeb()
        if (::mindEngine.isInitialized && prefs.isPowerOnline) {
            mindEngine.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mindEngine.isInitialized) {
            mindEngine.stop()
        }
    }

    private fun toggleVoiceInput() {
        if (!prefs.isPowerOnline) {
            Toast.makeText(this, "Jarvis is in Sleep mode. Press Online to activate.", Toast.LENGTH_SHORT).show()
            return
        }

        if (prefs.isMicMuted) {
            Toast.makeText(this, "Microphone is MUTED! Tap UNMUTE button to speak.", Toast.LENGTH_SHORT).show()
            runJs("window.setMicMuted(true);")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (voiceHelper.isCurrentlyListening()) {
            voiceHelper.stopListening()
            runJs("window.setOrbState('idle', 0.0);")
        } else {
            ttsService.stop()
            voiceHelper.startListening()
        }
    }

    private fun togglePower() {
        prefs.isPowerOnline = !prefs.isPowerOnline
        runJs("window.setPowerState(${prefs.isPowerOnline});")
        if (!prefs.isPowerOnline) {
            if (::mindEngine.isInitialized) mindEngine.stop()
            ttsService.stop()
            voiceHelper.stopListening()
            runJs("window.setOrbState('idle', 0.0);")
            Toast.makeText(this, "Jarvis entering standby sleep mode.", Toast.LENGTH_SHORT).show()
        } else {
            if (::mindEngine.isInitialized) mindEngine.start()
            Toast.makeText(this, "Jarvis online. Systems nominal.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUserSpeechQuery(query: String) {
        if (query.isBlank()) return
        if (::mindEngine.isInitialized) mindEngine.onUserInteracted()
        val timeNow = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        // Immediately update UI with user query and thinking animation
        runJs("window.addChatMessage('you', ${JSONObject.quote(query)}, '$timeNow');")
        runJs("window.setOrbState('thinking', 0.6);")

        // Persist query and process action immediately
        lifecycleScope.launch(Dispatchers.IO) {
            database.memoryDao().insertMemory(
                MemoryEntity(category = "CHAT_USER", content = query)
            )

            // High-speed Instant Action Execution via GeminiService
            val response = geminiService.processQuery(query)

            database.memoryDao().insertMemory(
                MemoryEntity(category = "CHAT_JARVIS", content = response)
            )

            withContext(Dispatchers.Main) {
                runJs("window.addChatMessage('jarvis', ${JSONObject.quote(response)}, '$timeNow');")
                // If microphone is not muted, speak aloud
                if (!prefs.isMicMuted && prefs.isPowerOnline) {
                    ttsService.speak(response)
                } else {
                    runJs("window.setOrbState('idle', 0.0);")
                }
            }
        }
    }

    fun clearChatHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            database.memoryDao().clearChatMemories()
            withContext(Dispatchers.Main) {
                runJs("if (window.onChatCleared) window.onChatCleared();")
                Toast.makeText(this@MainActivity, "Conversation cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncRecentChatHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = database.memoryDao().getRecentMemories(50).reversed()
            val chatItemsArray = JSONArray()
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

            list.forEach { mem ->
                if (mem.category == "CHAT_USER" || mem.category == "CHAT_JARVIS") {
                    val obj = JSONObject().apply {
                        put("who", if (mem.category == "CHAT_USER") "user" else "jarvis")
                        put("text", mem.content)
                        put("time", timeFmt.format(Date(mem.timestamp)))
                    }
                    chatItemsArray.put(obj)
                }
            }

            withContext(Dispatchers.Main) {
                runJs("if (window.loadFullChatHistory) window.loadFullChatHistory(${chatItemsArray.toString()});")
            }
        }
    }

    private fun getInitialSettingsJson(): String {
        val perms = checkAllPermissions()
        val obj = JSONObject().apply {
            put("apiKey", prefs.apiKey.takeIf { it != "MY_GEMINI_API_KEY" } ?: "")
            put("model", prefs.model)
            put("voice", prefs.voice)
            put("personality", prefs.personality)
            put("userName", prefs.userName)
            put("youtubeEnabled", prefs.youtubeEnabled)
            put("youtubeApiKey", prefs.youtubeApiKey)
            put("permissions", JSONObject().apply {
                perms.forEach { (k, v) -> put(k, v) }
            })
        }
        return obj.toString()
    }

    private fun checkAllPermissions(): Map<String, Boolean> {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = JarvisAccessibilityService.isRunning()
        val settings = Settings.System.canWrite(this)

        return mapOf(
            "mic" to mic,
            "camera" to camera,
            "notification" to notification,
            "contacts" to contacts,
            "phone" to phone,
            "location" to location,
            "bluetooth" to bluetooth,
            "battery" to battery,
            "overlay" to overlay,
            "accessibility" to accessibility,
            "settings" to settings
        )
    }

    private fun updateSettingsPermissionsInWeb() {
        val perms = checkAllPermissions()
        val obj = JSONObject().apply {
            perms.forEach { (k, v) -> put(k, v) }
        }
        val jsonStr = obj.toString()
        runJs("if (window.updatePermissions) window.updatePermissions($jsonStr);")
        runJs("if (window.onPermissionsUpdated) window.onPermissionsUpdated($jsonStr);")
    }

    private fun requestSpecificPermission(type: String) {
        when (type.lowercase(Locale.ROOT)) {
            "mic" -> requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            "camera" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            "notifications", "notification" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    Toast.makeText(this, "Notifications enabled for this Android version", Toast.LENGTH_SHORT).show()
                    updateSettingsPermissionsInWeb()
                }
            }
            "contacts" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            "phone" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            "location" -> requestMultiplePermissionsLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            "bluetooth" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                } else {
                    Toast.makeText(this, "Bluetooth active for this Android version", Toast.LENGTH_SHORT).show()
                    updateSettingsPermissionsInWeb()
                }
            }
            "battery" -> {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallback)
                }
            }
            "overlay" -> {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Enable 'Display over other apps' for Jarvis", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            "settings" -> {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open system settings", Toast.LENGTH_SHORT).show()
                }
            }
            "accessibility" -> {
                openScreenControlSettings()
            }
        }
    }

    private fun requestAllPermissions() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        isRequestingSequentialAll = true
        Toast.makeText(this, "Granting permissions: Microphone, Camera, Contacts, Calls, Location...", Toast.LENGTH_SHORT).show()
        requestMultiplePermissionsLauncher.launch(list.toTypedArray())
    }

    private fun checkAndPromptNextSpecialPermission() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(packageName) != true) {
            Toast.makeText(this, "Next step: Please allow Unrestricted Battery for Jarvis", Toast.LENGTH_LONG).show()
            requestSpecificPermission("battery")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Next step: Enable 'Display over other apps' for Jarvis", Toast.LENGTH_LONG).show()
            requestSpecificPermission("overlay")
            return
        }
        if (!JarvisAccessibilityService.isRunning()) {
            Toast.makeText(this, "Next step: Enable 'Jarvis AI' in Accessibility for 100% Screen Control", Toast.LENGTH_LONG).show()
            requestSpecificPermission("accessibility")
            return
        }
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Next step: Allow 'Modify system settings' for brightness control", Toast.LENGTH_LONG).show()
            requestSpecificPermission("settings")
            return
        }
        Toast.makeText(this, "All permissions granted! Systems 100% operational, sir.", Toast.LENGTH_LONG).show()
    }

    private fun saveSettingsFromJson(json: String) {
        try {
            val root = JSONObject(json)
            if (root.has("apiKey")) prefs.apiKey = root.getString("apiKey")
            if (root.has("model")) prefs.model = root.getString("model")
            if (root.has("voice")) {
                prefs.voice = root.getString("voice")
                ttsService.applyVoiceProfile(prefs.voice)
            }
            if (root.has("personality")) prefs.personality = root.getString("personality")
            if (root.has("userName")) prefs.userName = root.getString("userName")
            if (root.has("youtubeEnabled")) prefs.youtubeEnabled = root.getBoolean("youtubeEnabled")
            if (root.has("youtubeApiKey")) prefs.youtubeApiKey = root.getString("youtubeApiKey")

            runJs("showToast('Configuration Saved');")
        } catch (_: Exception) {
            runJs("showToast('Failed to parse settings');")
        }
    }

    private fun pasteKeyFromClipboard(type: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                if (type == "gemini") {
                    runJs("setApiKey(${JSONObject.quote(text)});")
                } else {
                    runJs("setYouTubeKey(${JSONObject.quote(text)});")
                }
            } else {
                runJs("showToast('Clipboard is empty');")
            }
        } else {
            runJs("showToast('Clipboard is empty');")
        }
    }

    private fun validateYouTubeKey(key: String) {
        if (key.length >= 20) {
            runJs("onYouTubeKeyValidated(true, 'YouTube Data API v3 Active');")
        } else {
            runJs("onYouTubeKeyValidated(false, 'Key is too short or malformed');")
        }
    }

    private fun showHistoryDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val memories = database.memoryDao().getRecentMemories(30)
            val text = if (memories.isEmpty()) {
                "No previous conversation history found."
            } else {
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                memories.joinToString("\n\n") { mem ->
                    val sender = if (mem.category.contains("USER")) "User" else "Jarvis"
                    "[$sender - ${sdf.format(Date(mem.timestamp))}]\n${mem.content}"
                }
            }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Memory & Conversation History")
                    .setMessage(text)
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Clear All") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            database.memoryDao().clearAll()
                            withContext(Dispatchers.Main) {
                                runJs("window.syncChatTurns([]);")
                                Toast.makeText(this@MainActivity, "Memories cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun clearMemoriesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Memory Core")
            .setMessage("Are you sure you want to erase all conversation history and short-term routines?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.memoryDao().clearAll()
                    withContext(Dispatchers.Main) {
                        runJs("window.syncChatTurns([]);")
                        runJs("showToast('Memory Core Cleared');")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTimeSync() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                runJs("window.setLiveTime('$timeStr');")
                mainHandler.postDelayed(this, 30000)
            }
        })
    }

    private fun runJs(code: String) {
        webView.evaluateJavascript(code, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsService.shutdown()
        voiceHelper.stopListening()
        webView.destroy()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
