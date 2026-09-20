package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class JarvisMindEngine(
    private val context: Context,
    private val ttsService: TtsService,
    private val appManagerHelper: AppManagerHelper,
    private val onThoughtGenerated: (thought: String, speakAloud: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "JarvisMindEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var mindJob: Job? = null
    var isAutonomousActive: Boolean = true
    // Strict compliance: Never speak unprompted audio unless user explicitly asks a question
    var speakAutonomousThoughts: Boolean = false
    private var lastUserInteractionTime: Long = System.currentTimeMillis()
    private var thoughtIndex = 0

    fun onUserInteracted() {
        lastUserInteractionTime = System.currentTimeMillis()
    }

    fun start() {
        if (mindJob?.isActive == true) return
        mindJob = scope.launch {
            Log.d(TAG, "Jarvis Autonomous Mind Engine initialized in silent standby.")

            while (isActive) {
                val nextDelay = Random.nextLong(45_000, 75_000)
                delay(nextDelay)

                if (!isAutonomousActive) continue

                val idleSeconds = (System.currentTimeMillis() - lastUserInteractionTime) / 1000
                if (idleSeconds >= 40) {
                    generateSilentTelemetryThought()
                }
            }
        }
    }

    fun stop() {
        mindJob?.cancel()
        mindJob = null
        Log.d(TAG, "Jarvis Autonomous Mind Engine stopped.")
    }

    fun generateAutonomousThought() {
        generateSilentTelemetryThought()
    }

    private fun generateSilentTelemetryThought() {
        val thoughts = listOf(
            "System telemetry normal. Background voice listener and device automation active.",
            "Screen automation engine synchronized. Ready for voice directives.",
            "Device apps mapped. Fast launch engine standby.",
            "Core operational. Standing by for commands."
        )

        val selected = thoughts[thoughtIndex % thoughts.size]
        thoughtIndex++

        Log.d(TAG, "Jarvis Silent Mind Log: $selected")
        // Passed with speakAloud = false so it NEVER speaks unprompted
        onThoughtGenerated(selected, false)
    }

    fun triggerCustomThought(customPrompt: String) {
        onThoughtGenerated(customPrompt, false)
    }
}
