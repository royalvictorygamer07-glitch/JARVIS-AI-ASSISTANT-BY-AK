package com.example.bridge

import android.webkit.JavascriptInterface

class JarvisBridge(
    private val onOrb: () -> Unit,
    private val onMic: () -> Unit,
    private val onPower: () -> Unit,
    private val onSettings: () -> Unit,
    private val onHistory: () -> Unit,
    private val onTelegram: () -> Unit,
    private val onToggleVoice: (String) -> Unit,
    private val onScreenControlSettings: () -> Unit,
    private val onTestScreenClick: (Float, Float) -> Unit,
    private val checkScreenControlActive: () -> Boolean,
    private val getActiveVoice: () -> String,
    private val onTriggerMindThought: (() -> Unit)? = null,
    private val onOpenApp: ((String) -> Unit)? = null,
    private val getInstalledAppsCount: (() -> Int)? = null,
    private val onRequestPerm: ((String) -> Unit)? = null,
    private val onRequestAllPerms: (() -> Unit)? = null,
    private val onGetPermissionsJson: (() -> String)? = null,
    private val onMuteToggle: (() -> Unit)? = null,
    private val isMutedProvider: (() -> Boolean)? = null,
    private val onSendCommand: ((String) -> Unit)? = null,
    private val onClearChat: (() -> Unit)? = null
) {
    @JavascriptInterface
    fun onOrbClicked() {
        onOrb()
    }

    @JavascriptInterface
    fun onMicClicked() {
        onMic()
    }

    @JavascriptInterface
    fun clearChat() {
        onClearChat?.invoke()
    }

    @JavascriptInterface
    fun onMuteClicked() {
        onMuteToggle?.invoke()
    }

    @JavascriptInterface
    fun isMuted(): Boolean {
        return isMutedProvider?.invoke() ?: false
    }

    @JavascriptInterface
    fun sendCommand(text: String) {
        onSendCommand?.invoke(text)
    }

    @JavascriptInterface
    fun onPowerClicked() {
        onPower()
    }

    @JavascriptInterface
    fun onSettingsClicked() {
        onSettings()
    }

    @JavascriptInterface
    fun onHistoryClicked() {
        onHistory()
    }

    @JavascriptInterface
    fun openTelegram() {
        onTelegram()
    }

    @JavascriptInterface
    fun setVoiceProfile(voiceName: String) {
        onToggleVoice(voiceName)
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
    fun isScreenControlActive(): Boolean {
        return checkScreenControlActive()
    }

    @JavascriptInterface
    fun getCurrentVoice(): String {
        return getActiveVoice()
    }

    @JavascriptInterface
    fun triggerMindThought() {
        onTriggerMindThought?.invoke()
    }

    @JavascriptInterface
    fun openApp(appName: String) {
        onOpenApp?.invoke(appName)
    }

    @JavascriptInterface
    fun getAppsCount(): Int {
        return getInstalledAppsCount?.invoke() ?: 0
    }

    @JavascriptInterface
    fun requestPermission(type: String) {
        onRequestPerm?.invoke(type)
    }

    @JavascriptInterface
    fun requestAllPermissions() {
        onRequestAllPerms?.invoke()
    }

    @JavascriptInterface
    fun getPermissionsJson(): String {
        return onGetPermissionsJson?.invoke() ?: "{}"
    }
}
