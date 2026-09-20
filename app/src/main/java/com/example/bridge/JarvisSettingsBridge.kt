package com.example.bridge

import android.webkit.JavascriptInterface

class JarvisSettingsBridge(
    private val onBack: () -> Unit,
    private val onSave: (String) -> Unit,
    private val onGetInitial: () -> String,
    private val onPasteKey: (type: String) -> Unit,
    private val onRequestPerm: (String) -> Unit,
    private val onRequestAllPerms: () -> Unit,
    private val onClearMem: () -> Unit,
    private val onViewMem: () -> Unit,
    private val onValidateYt: (String) -> Unit,
    private val onTelegram: () -> Unit,
    private val onScreenControlSettings: () -> Unit,
    private val onTestScreenClick: (Float, Float) -> Unit = { _, _ -> },
    private val onTriggerMindThought: () -> Unit = {},
    private val onOpenApp: (String) -> Unit = {},
    private val getInstalledAppsCount: () -> Int = { 0 },
    private val checkScreenControlActive: () -> Boolean = { false },
    private val onOpenUrl: (String) -> Unit = {}
) {
    @JavascriptInterface
    fun onBackClicked() {
        onBack()
    }

    @JavascriptInterface
    fun saveSettings(json: String) {
        onSave(json)
    }

    @JavascriptInterface
    fun getInitialSettings(): String {
        return onGetInitial()
    }

    @JavascriptInterface
    fun openGetApiKey(type: String) {
        val url = if (type.lowercase() == "youtube") {
            "https://console.cloud.google.com/apis/credentials"
        } else {
            "https://aistudio.google.com/app/apikey"
        }
        onOpenUrl(url)
    }

    @JavascriptInterface
    fun pasteApiKey() {
        onPasteKey("gemini")
    }

    @JavascriptInterface
    fun pasteYouTubeKey() {
        onPasteKey("youtube")
    }

    @JavascriptInterface
    fun requestPermission(type: String) {
        onRequestPerm(type)
    }

    @JavascriptInterface
    fun requestAllPermissions() {
        onRequestAllPerms()
    }

    @JavascriptInterface
    fun clearMemories() {
        onClearMem()
    }

    @JavascriptInterface
    fun viewMemories() {
        onViewMem()
    }

    @JavascriptInterface
    fun validateYouTubeKey(key: String) {
        onValidateYt(key)
    }

    @JavascriptInterface
    fun openTelegram() {
        onTelegram()
    }

    @JavascriptInterface
    fun openScreenControlSettings() {
        onScreenControlSettings()
    }

    @JavascriptInterface
    fun testScreenClick(x: Float, y: Float) {
        onTestScreenClick(x, y)
    }

    @JavascriptInterface
    fun triggerMindThought() {
        onTriggerMindThought()
    }

    @JavascriptInterface
    fun openApp(appName: String) {
        onOpenApp(appName)
    }

    @JavascriptInterface
    fun getAppsCount(): Int {
        return getInstalledAppsCount()
    }

    @JavascriptInterface
    fun isScreenControlActive(): Boolean {
        return checkScreenControlActive()
    }
}
