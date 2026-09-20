package com.example.service

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    var speakAutonomousThoughts: Boolean = true
    private var lastUserInteractionTime: Long = System.currentTimeMillis()
    private var thoughtIndex = 0

    fun onUserInteracted() {
        lastUserInteractionTime = System.currentTimeMillis()
    }

    fun start() {
        if (mindJob?.isActive == true) return
        mindJob = scope.launch {
            Log.d(TAG, "Jarvis Autonomous Mind Engine activated.")
            // Initial boot thought
            delay(3000)
            generateProactiveGreeting()

            while (isActive) {
                // Wait between 35 and 60 seconds before next proactive mind cycle
                val nextDelay = Random.nextLong(35_000, 65_000)
                delay(nextDelay)

                if (!isAutonomousActive) continue

                val idleSeconds = (System.currentTimeMillis() - lastUserInteractionTime) / 1000
                // If user has been idle for at least 25 seconds, Jarvis thinks aloud
                if (idleSeconds >= 25) {
                    generateAutonomousThought()
                }
            }
        }
    }

    fun stop() {
        mindJob?.cancel()
        mindJob = null
        Log.d(TAG, "Jarvis Autonomous Mind Engine paused.")
    }

    private fun generateProactiveGreeting() {
        val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toIntOrNull() ?: 12
        val greetingTime = when (hour) {
            in 5..11 -> "Subah ka namaskar"
            in 12..16 -> "Shubh dopahar"
            in 17..21 -> "Shubh sandhya"
            else -> "Raat ke waqt bhi active hoon"
        }

        val greeting = "$greetingTime sir! Jarvis Autonomous Mind active ho chuka hai. Main aapke phone ke sabhi apps aur screen control ke liye tayar hoon."
        onThoughtGenerated(greeting, speakAutonomousThoughts)
    }

    fun generateAutonomousThought() {
        val thoughts = listOf(
            "Sir, mera autonomous mind active hai. Main screen aur background processes monitor kar raha hoon. Batayein kaun sa app open karoon?",
            "Screen Control Engine 100% ready hai, sir. Kisi bhi app me click karwana ho to bas hukum karein.",
            "Phone ke sabhi installed apps mere memory me mapped hain. YouTube, WhatsApp, Free Fire ya koi bhi app bas bol kar open kar sakte hain.",
            "Sir, system telemetries perfectly normal hain. AK EXPLOITS AI core continuous standby mode me hai.",
            "Aapka personal assistant awake hai. Kahin par bhi tap ya scroll karna ho, main turant execute kar dunga, sir.",
            "Battery aur network status stable hai. Main aapki nayi command ke liye taiyar hoon sir."
        )

        val selected = thoughts[thoughtIndex % thoughts.size]
        thoughtIndex++

        Log.d(TAG, "Jarvis Autonomous Thought: $selected")
        onThoughtGenerated(selected, speakAutonomousThoughts)
    }

    fun triggerCustomThought(customPrompt: String) {
        onThoughtGenerated(customPrompt, speakAutonomousThoughts)
    }
}
