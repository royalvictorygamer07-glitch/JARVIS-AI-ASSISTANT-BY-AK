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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.bridge.JarvisBridge
import com.example.bridge.JarvisSettingsBridge
import com.example.data.AppDatabase
import com.example.data.JarvisPreferences
import com.example.data.MemoryEntity
import com.example.service.GeminiService
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisBackgroundService
import com.example.service.JarvisMindEngine
import com.example.service.JarvisServiceListener
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

    private val serviceListener = object : JarvisServiceListener {
        override fun onOrbStateChanged(state: String, level: Float) {
            runOnUiThread {
                runJs("window.setOrbState('$state', $level);")
            }
        }

        override fun onAudioLevel(level: Float) {
            runOnUiThread {
                runJs("window.setAudioLevel($level);")
            }
        }

        override fun onUserQuery(query: String, timeStr: String) {
            runOnUiThread {
                runJs("window.addChatMessage('you', ${JSONObject.quote(query)}, '$timeStr');")
            }
        }

        override fun onJarvisResponse(response: String, timeStr: String) {
            runOnUiThread {
                runJs("window.addChatMessage('jarvis', ${JSONObject.quote(response)}, '$timeStr');")
            }
        }

        override fun onPartialTranscript(text: String) {
            runOnUiThread {
                runJs("if (window.setPartialTranscript) window.setPartialTranscript(${JSONObject.quote(text)});")
            }
        }

        override fun onMicMutedChanged(isMuted: Boolean) {
            runOnUiThread {
                runJs("window.setMicMuted($isMuted);")
            }
        }

        override fun onPowerChanged(isOnline: Boolean) {
            runOnUiThread {
                runJs("window.setPowerState($isOnline);")
            }
        }

        override fun onSpeechError(message: String) {
            runOnUiThread {
                runJs("if (window.onSpeechError) window.onSpeechError(${JSONObject.quote(message)});")
            }
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone enabled", Toast.LENGTH_SHORT).show()
            val service = JarvisBackgroundService.instance
            if (service != null) {
                service.setMicMuted(false)
                service.onMicrophonePermissionGranted()
            } else {
                JarvisBackgroundService.startService(this)
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

    private fun cleanCorruptedCacheDirs() {
        try {
            val brokenIndex = java.io.File(cacheDir, "WebView/Default/HTTP Cache/index-dir")
            if (brokenIndex.exists() && brokenIndex.isDirectory) {
                brokenIndex.deleteRecursively()
            }
        } catch (_: Throwable) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        cleanCorruptedCacheDirs()
        super.onCreate(savedInstanceState)

        prefs = JarvisPreferences(this)
        database = AppDatabase.getDatabase(this)

        // Launch Background Execution Foreground Service
        JarvisBackgroundService.startService(this)

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
                    voiceHelper.pauseForSpeech()
                } else {
                    runJs("window.setOrbState('idle', 0.0);")
                    voiceHelper.resumeAfterSpeech()
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
            isPowerOnlineProvider = { prefs.isPowerOnline },
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
            // Use software rendering if hardware graphics driver / mesa rendernode fails in virtualized environments
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
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
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    currentUrl = url ?: ""
                    onWebPageLoaded(currentUrl)
                }
            }
        }

        setupBridges()
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentUrl.contains("settings.html")) {
                    loadIndexPage()
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        loadIndexPage()

        // Check and prompt microphone permission on launch if missing
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupBridges() {
        webView.addJavascriptInterface(
            JarvisBridge(
                onOrb = { runOnUiThread { toggleVoiceInput() } },
                onMic = { runOnUiThread { handleMicOrMuteClick() } },
                onPower = { runOnUiThread { togglePower() } },
                onSettings = { runOnUiThread { loadSettingsPage() } },
                onHistory = { runOnUiThread { showHistoryDialog() } },
                onTelegram = { runOnUiThread { openTelegramChannel() } },
                onToggleVoice = { voiceName ->
                    runOnUiThread {
                        prefs.voice = voiceName
                        ttsService.applyVoiceProfile(voiceName)
                        runJs("if (window.onVoiceProfileChanged) window.onVoiceProfileChanged('$voiceName');")
                        Toast.makeText(this@MainActivity, "Voice profile set to: $voiceName", Toast.LENGTH_SHORT).show()
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

        val service = JarvisBackgroundService.instance
        if (service != null) {
            service.toggleMute()
            val isMuted = prefs.isMicMuted
            runJs("window.setMicMuted($isMuted);")
            Toast.makeText(this, if (isMuted) "Microphone MUTED" else "Microphone UNMUTED (Listening continuously)", Toast.LENGTH_SHORT).show()
            return
        }

        if (prefs.isMicMuted) {
            prefs.isMicMuted = false
            runJs("window.setMicMuted(false);")
            Toast.makeText(this, "Microphone UNMUTED (Listening continuously)", Toast.LENGTH_SHORT).show()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ttsService.stop()
                voiceHelper.startContinuousListening()
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

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        updateSettingsPermissionsInWeb()
        val service = JarvisBackgroundService.instance
        if (service != null) {
            service.setUiListener(serviceListener)
            if (!prefs.isMicMuted && prefs.isPowerOnline && hasAudioPermission()) {
                service.voiceHelper.startContinuousListening()
            }
        } else {
            JarvisBackgroundService.startService(this)
        }
        syncRecentChatHistory()
    }

    override fun onPause() {
        super.onPause()
        // Do NOT stop JarvisBackgroundService when activity is minimized or paused!
        JarvisBackgroundService.instance?.setUiListener(null)
    }

    private fun toggleVoiceInput() {
        handleMicOrMuteClick()
    }

    private fun togglePower() {
        val service = JarvisBackgroundService.instance
        if (service != null) {
            service.togglePower()
            runJs("window.setPowerState(${prefs.isPowerOnline});")
            Toast.makeText(this, if (prefs.isPowerOnline) "Jarvis online" else "Jarvis in sleep mode", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.isPowerOnline = !prefs.isPowerOnline
        runJs("window.setPowerState(${prefs.isPowerOnline});")
        if (!prefs.isPowerOnline) {
            ttsService.stop()
            voiceHelper.stopListening()
            runJs("window.setOrbState('idle', 0.0);")
            Toast.makeText(this, "Jarvis entering standby sleep mode.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Jarvis online.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUserSpeechQuery(query: String) {
        if (query.isBlank()) return
        val service = JarvisBackgroundService.instance
        if (service != null) {
            service.processUserQuery(query)
            return
        }

        val timeNow = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        runJs("window.addChatMessage('you', ${JSONObject.quote(query)}, '$timeNow');")
        runJs("window.setOrbState('thinking', 0.6);")

        lifecycleScope.launch(Dispatchers.IO) {
            database.memoryDao().insertMemory(
                MemoryEntity(category = "CHAT_USER", content = query)
            )

            val response = geminiService.processQuery(query)

            database.memoryDao().insertMemory(
                MemoryEntity(category = "CHAT_JARVIS", content = response)
            )

            withContext(Dispatchers.Main) {
                runJs("window.addChatMessage('jarvis', ${JSONObject.quote(response)}, '$timeNow');")
                if (!prefs.isMicMuted && prefs.isPowerOnline) {
                    ttsService.speak(response)
                } else {
                    runJs("window.setOrbState('idle', 0.0);")
                    if (!prefs.isMicMuted && prefs.isPowerOnline) {
                        voiceHelper.resumeAfterSpeech()
                    }
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
            val recentMemories = database.memoryDao().getRecentMemories(30)
            withContext(Dispatchers.Main) {
                val jsonArr = JSONArray()
                recentMemories.reversed().forEach { mem ->
                    if (mem.category == "CHAT_USER" || mem.category == "CHAT_JARVIS") {
                        val obj = JSONObject().apply {
                            put("role", if (mem.category == "CHAT_USER") "you" else "jarvis")
                            put("text", mem.content)
                            put("time", SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(mem.timestamp)))
                        }
                        jsonArr.put(obj)
                    }
                }
                runJs("if (window.loadHistoricMessages) window.loadHistoricMessages($jsonArr);")
            }
        }
    }

    private fun requestSpecificPermission(perm: String) {
        when (perm) {
            "record_audio", "mic" -> requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            "camera" -> requestAudioPermissionLauncher.launch(Manifest.permission.CAMERA)
            "phone" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS))
            "contacts" -> requestAudioPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            "location" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            "sms" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
            "calendar" -> requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            "bluetooth" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                } else {
                    requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN))
                }
            }
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestAudioPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            "accessibility" -> openScreenControlSettings()
            "overlay" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
            "battery" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            "settings" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        isRequestingSequentialAll = true
        requestMultiplePermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun checkAndPromptNextSpecialPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Screen Overlay Permission")
                .setMessage("Allow Jarvis to display on top of other apps for hands-free assistance.")
                .setPositiveButton("Grant") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            AlertDialog.Builder(this)
                .setTitle("Modify System Settings")
                .setMessage("Allow Jarvis to adjust screen brightness and volume on voice command.")
                .setPositiveButton("Grant") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }

        if (!JarvisAccessibilityService.isRunning()) {
            AlertDialog.Builder(this)
                .setTitle("Screen Automation Service")
                .setMessage("Enable 'Jarvis AI' under Accessibility for 100% voice screen clicks and full screen control.")
                .setPositiveButton("Open Settings") { _, _ ->
                    openScreenControlSettings()
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun getInitialSettingsJson(): String {
        val pm = packageManager
        fun has(perm: String): Boolean = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

        val isAudio = has(Manifest.permission.RECORD_AUDIO)
        val isCamera = has(Manifest.permission.CAMERA)
        val isPhone = has(Manifest.permission.CALL_PHONE)
        val isContacts = has(Manifest.permission.READ_CONTACTS)
        val isLocation = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val isSms = has(Manifest.permission.SEND_SMS)
        val isCalendar = has(Manifest.permission.READ_CALENDAR)
        val isBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            has(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            has(Manifest.permission.BLUETOOTH)
        }
        val isNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        val isOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val isSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(this) else true
        val isBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        } else true
        val isAccessibility = JarvisAccessibilityService.isRunning()

        val json = JSONObject().apply {
            put("userName", prefs.userName)
            put("apiKey", prefs.apiKey)
            put("model", prefs.model)
            put("personality", prefs.personality)
            put("youtubeKey", prefs.youtubeApiKey)
            put("wakeWord", prefs.wakeWord)
            put("voice", prefs.voice)
            put("isMicMuted", prefs.isMicMuted)
            put("isPowerOnline", prefs.isPowerOnline)
            put("screenControlActive", isAccessibility)

            // Direct flags for legacy callers
            put("permAudio", isAudio)
            put("permCamera", isCamera)
            put("permPhone", isPhone)
            put("permContacts", isContacts)
            put("permLocation", isLocation)
            put("permNotifications", isNotif)
            put("permOverlay", isOverlay)
            put("permBattery", isBattery)
            put("permAccessibility", isAccessibility)
            put("permBluetooth", isBluetooth)
            put("permSms", isSms)
            put("permCalendar", isCalendar)
            put("permSettings", isSettings)

            // Nested permissions object for settings.html updatePermissions()
            val permsObj = JSONObject().apply {
                put("mic", isAudio)
                put("camera", isCamera)
                put("notification", isNotif)
                put("contacts", isContacts)
                put("phone", isPhone)
                put("location", isLocation)
                put("sms", isSms)
                put("calendar", isCalendar)
                put("bluetooth", isBluetooth)
                put("battery", isBattery)
                put("overlay", isOverlay)
                put("accessibility", isAccessibility)
                put("settings", isSettings)
            }
            put("permissions", permsObj)
        }
        return json.toString()
    }

    private fun updateSettingsPermissionsInWeb() {
        val json = getInitialSettingsJson()
        runJs("if (window.updatePermissionsState) window.updatePermissionsState($json);")
    }

    private fun saveSettingsFromJson(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            if (json.has("apiKey")) prefs.apiKey = json.getString("apiKey")
            if (json.has("model")) prefs.model = json.getString("model")
            if (json.has("personality")) prefs.personality = json.getString("personality")
            if (json.has("youtubeKey")) prefs.youtubeApiKey = json.getString("youtubeKey")
            if (json.has("voice")) {
                val v = json.getString("voice")
                prefs.voice = v
                ttsService.applyVoiceProfile(v)
            }
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pasteKeyFromClipboard(type: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val pasted = clipData.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasted.isNotBlank()) {
                    runJs("if (window.onPastedKey) window.onPastedKey('$type', ${JSONObject.quote(pasted)});")
                    Toast.makeText(this, "Pasted into $type", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not paste from clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateYouTubeKey(key: String) {
        Toast.makeText(this, "YouTube Search Ready", Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = database.memoryDao().getRecentMemories(40)
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No saved history yet.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val items = list.map { "${it.category}: ${it.content}" }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Memory & History (${list.size})")
                    .setItems(items, null)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Clear History") { _, _ -> clearMemoriesDialog() }
                    .show()
            }
        }
    }

    private fun clearMemoriesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Memory")
            .setMessage("Are you sure you want to wipe all chat memory and interaction logs?")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.memoryDao().clearAll()
                    withContext(Dispatchers.Main) {
                        runJs("if (window.onChatCleared) window.onChatCleared();")
                        Toast.makeText(this@MainActivity, "All memories wiped clean", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runJs(code: String) {
        runOnUiThread {
            webView.evaluateJavascript(code, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        JarvisBackgroundService.instance?.setUiListener(null)
        ttsService.shutdown()
        voiceHelper.stopListening()
    }
}
