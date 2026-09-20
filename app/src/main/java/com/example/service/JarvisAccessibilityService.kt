package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessService"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Jarvis Accessibility Screen Control Service connected")
    }

    private var lastA11yMessageKey = ""
    private var lastA11yMessageTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // If NotificationListenerService is not connected, use Accessibility notification event fallback
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            !JarvisNotificationListenerService.isConnected
        ) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                val textList = event.text
                if (!textList.isNullOrEmpty()) {
                    val combined = textList.joinToString(" ").trim()
                    val lower = combined.lowercase()
                    if (combined.isNotBlank() &&
                        !lower.contains("checking for new messages") &&
                        !lower.contains("backup in progress") &&
                        !lower.contains("whatsapp web")
                    ) {
                        val now = System.currentTimeMillis()
                        if (combined != lastA11yMessageKey || (now - lastA11yMessageTime) > 4000L) {
                            lastA11yMessageKey = combined
                            lastA11yMessageTime = now
                            val announcement = "WhatsApp par message aaya hai: $combined"
                            JarvisBackgroundService.instance?.speakAnnouncement(announcement)
                                ?: com.example.MainActivity.instance?.speakFromUI(announcement)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Jarvis Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Jarvis Accessibility Service destroyed")
    }

    /**
     * Dispatches a tap gesture at the specified screen coordinates (x, y)
     */
    fun clickAt(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete?.invoke(false)
            return false
        }

        val clickPath = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture click succeeded at ($x, $y)")
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture click cancelled at ($x, $y)")
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Searches for any UI element containing [text] and clicks it
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return false

        // 1. First attempt: built-in text search
        val nodes = root.findAccessibilityNodeInfosByText(cleanText)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (performClickOnNode(node)) {
                    return true
                }
            }
        }

        // 2. Second attempt: Recursive deep search for case-insensitive and content-description matches
        val targetNode = findNodeRecursive(root, cleanText.lowercase())
        if (targetNode != null) {
            return performClickOnNode(targetNode)
        }

        return false
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, targetLower: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (nodeText.contains(targetLower) || contentDesc.contains(targetLower)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, targetLower)
            if (found != null) return found
        }
        return null
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        // If node itself is clickable
        if (node.isClickable) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) return true
        }

        // Check parent chain if parent is clickable (e.g. Card, Button wrapping TextView)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable) {
                val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
            parent = parent.parent
            depth++
        }

        // Fallback: Calculate bounds on screen and dispatch gesture click
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()
            return clickAt(cx, cy)
        }

        return false
    }

    /**
     * Vertical screen scroll (down = scroll down / swipe up)
     */
    fun scroll(down: Boolean = true): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val action = if (down) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollable.performAction(action)) {
                    return true
                }
            }
        }

        // Fallback to gesture scroll
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dm = resources.displayMetrics
            val startX = dm.widthPixels / 2f
            val startY = if (down) dm.heightPixels * 0.75f else dm.heightPixels * 0.25f
            val endY = if (down) dm.heightPixels * 0.25f else dm.heightPixels * 0.75f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                .build()

            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Clicks at normalized coordinates (0.0 to 1.0)
     */
    fun clickNormalized(pctX: Float, pctY: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (pctX.coerceIn(0f, 1f)) * dm.widthPixels
        val y = (pctY.coerceIn(0f, 1f)) * dm.heightPixels
        return clickAt(x, y)
    }

    /**
     * Swipes between two normalized points
     */
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val dm = resources.displayMetrics
        val p1 = Path().apply {
            moveTo(fromX * dm.widthPixels, fromY * dm.heightPixels)
            lineTo(toX * dm.widthPixels, toY * dm.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Collects all currently visible texts and button labels on screen
     */
    fun collectVisibleScreenTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<String>()
        collectTextsRecursive(root, results)
        return results.distinct().take(30)
    }

    private fun collectTextsRecursive(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length < 80) list.add(t)
        if (!d.isNullOrEmpty() && d.length < 80 && d != t) list.add(d)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextsRecursive(child, list)
        }
    }

    fun clickCenter(): Boolean = clickNormalized(0.5f, 0.5f)

    /**
     * Types text into the currently focused or first available editable field on screen
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                return true
            }
        }

        // Deep search for any editable field
        val editable = findEditableNode(root)
        if (editable != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Double tap at screen coordinates
     */
    fun doubleClickAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val clickPath = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(clickPath, 0, 40)
        val stroke2 = GestureDescription.StrokeDescription(clickPath, 100, 40)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Long press at screen coordinates
     */
    fun longPressAt(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val clickPath = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Intelligent click: understands synonyms like 'search', 'send', 'submit', 'play', 'cancel', 'ok', etc.
     */
    fun smartClick(intent: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val clean = intent.trim().lowercase()

        // 1. Direct match first
        if (clickByText(clean)) return true

        // 2. Synonyms and semantic intent
        val candidateKeywords = when {
            clean in listOf("search", "find", "dhundo", "khojo") -> listOf("search", "find", "explore", "magnify", "go")
            clean in listOf("send", "bhejo", "submit", "enter") -> listOf("send", "submit", "post", "done", "ok", "forward")
            clean in listOf("play", "chalao", "bajao", "start") -> listOf("play", "resume", "watch", "start")
            clean in listOf("pause", "roko", "thamo") -> listOf("pause", "stop", "hold")
            clean in listOf("close", "band karo", "cancel", "hatao") -> listOf("close", "cancel", "dismiss", "x", "no", "not now", "skip")
            clean in listOf("accept", "allow", "yes", "theek hai", "ha") -> listOf("allow", "agree", "accept", "continue", "ok", "yes", "confirm", "grant")
            clean in listOf("next", "aage", "forward") -> listOf("next", "continue", "forward", ">")
            clean in listOf("back", "piche") -> listOf("back", "previous", "<")
            else -> listOf(clean)
        }

        for (kw in candidateKeywords) {
            val node = findNodeRecursive(root, kw)
            if (node != null && performClickOnNode(node)) {
                return true
            }
        }

        return false
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun openPowerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    fun takeScreenshot(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    } else false
    fun lockScreen(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    } else false
}
