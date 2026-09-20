package com.example.service

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class TtsService(
    private val context: Context,
    private val onStateChanged: (isSpeaking: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentProfile: String = "Puck"

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isInitialized = true
                }
                applyVoiceProfile(currentProfile)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onStateChanged(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        onStateChanged(false)
                    }

                    override fun onError(utteranceId: String?) {
                        onStateChanged(false)
                    }
                })
            }
        }
    }

    /**
     * Apply chosen voice profile (e.g. Girl/Female Voice vs Deep Male/Jarvis)
     */
    fun applyVoiceProfile(profile: String) {
        currentProfile = profile
        val engine = tts ?: return
        if (!isInitialized) return

        val lower = profile.lowercase(Locale.ROOT)
        val isGirl = lower.contains("girl") || lower.contains("female") || lower.contains("friday") ||
                lower.contains("sophia") || lower.contains("nova") || lower.contains("aoede") ||
                lower.contains("kore") || lower.contains("leda") || lower.contains("ladki")

        if (isGirl) {
            // Female / Friday Voice: Fast, smooth, responsive
            engine.setPitch(1.20f)
            engine.setSpeechRate(1.18f)

            try {
                val availableVoices = engine.voices
                if (!availableVoices.isNullOrEmpty()) {
                    val femaleVoice = availableVoices.firstOrNull { voice ->
                        !voice.isNetworkConnectionRequired && (
                                voice.name.contains("female", ignoreCase = true) ||
                                        voice.name.contains("f0", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-sfg", ignoreCase = true) ||
                                        voice.name.contains("hi-in-x-hie", ignoreCase = true) ||
                                        voice.name.contains("hi-in-x-hia", ignoreCase = true) ||
                                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                                                voice.quality >= Voice.QUALITY_NORMAL &&
                                                voice.features.contains("gender=female"))
                                )
                    } ?: availableVoices.firstOrNull { voice ->
                        voice.name.contains("female", ignoreCase = true)
                    }

                    if (femaleVoice != null) {
                        engine.voice = femaleVoice
                    }
                }
            } catch (e: Exception) {
                Log.w("TtsService", "Error picking female voice: ${e.message}")
            }
        } else {
            // Male / Jarvis Voice: Fast, crisp, continuous flow without halting
            engine.setPitch(1.00f)
            engine.setSpeechRate(1.22f)

            try {
                val availableVoices = engine.voices
                if (!availableVoices.isNullOrEmpty()) {
                    val maleVoice = availableVoices.firstOrNull { voice ->
                        !voice.isNetworkConnectionRequired && (
                                voice.name.contains("male", ignoreCase = true) ||
                                        voice.name.contains("m0", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-sfg#male", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-iom", ignoreCase = true)
                                )
                    }
                    if (maleVoice != null) {
                        engine.voice = maleVoice
                    }
                }
            } catch (e: Exception) {
                Log.w("TtsService", "Error picking male voice: ${e.message}")
            }
        }
    }

    private fun sanitizeForSpeech(raw: String): String {
        var s = raw
        // Speak creator link naturally
        s = s.replace(Regex("https?://t\\.me/\\S+"), "AK EXPLOITS Telegram channel")
        s = s.replace(Regex("https?://\\S+"), "link")
        // Remove markdown tokens that cause TTS engines to halt or stumble
        s = s.replace(Regex("[*#_`~>|\\[\\]{}()\"]"), " ")
        // Remove list bullets
        s = s.replace(Regex("^[\\s*-•]+", RegexOption.MULTILINE), " ")
        // Remove emojis and symbols
        s = s.replace(Regex("[\\p{So}\\p{Cn}]"), "")
        // Clean multi-punctuation
        s = s.replace(Regex("\\.{2,}"), ".")
        s = s.replace(Regex("-{2,}"), " ")
        s = s.replace(Regex("!{2,}"), "!")
        s = s.replace(Regex("\\?{2,}"), "?")

        // CRITICAL USER DIRECTIVE: Remove filler exclamations and salutations (e.g. 'Sir', 'Ji sir')
        s = s.replace(Regex("^(sir|ji\\s*sir|hello\\s*sir|yes\\s*sir|arre\\s*sir|boss)[,!.\\s]+", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("[,!.\\s]+(sir|ji\\s*sir)[!.]?$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\b(sir|ji\\s*sir)\\b[,!]?", RegexOption.IGNORE_CASE), "")

        // Normalize spaces
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun isHindiText(text: String): Boolean {
        if (text.any { it in '\u0900'..'\u097F' }) return true
        val lower = text.lowercase(Locale.ROOT)
        val hindiMarkers = listOf(
            "hai", "hoon", "aap", "mera", "meri", "karo", "karein", "raha",
            "rahi", "kya", "batao", "tum", "kaun", "banaya", "chalu", "band",
            "awaz", "aawaz", "shant", "badhao", "dheemi", "gaya"
        )
        return hindiMarkers.any { lower.contains(it) }
    }

    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            onStateChanged(false)
            return
        }
        val cleaned = sanitizeForSpeech(text)
        if (cleaned.isBlank()) {
            onStateChanged(false)
            return
        }

        val engine = tts ?: return

        try {
            if (isHindiText(cleaned)) {
                val hiLocale = Locale("hi", "IN")
                val enInLocale = Locale("en", "IN")
                if (engine.isLanguageAvailable(hiLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = hiLocale
                } else if (engine.isLanguageAvailable(enInLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = enInLocale
                }
            } else {
                engine.language = Locale.US
            }
        } catch (_: Exception) {}

        val isGirl = currentProfile.lowercase(Locale.ROOT).let {
            it.contains("girl") || it.contains("female") || it.contains("friday")
        }
        if (isGirl) {
            engine.setSpeechRate(1.18f)
            engine.setPitch(1.20f)
        } else {
            engine.setSpeechRate(1.22f)
            engine.setPitch(1.00f)
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_SPEECH_${System.currentTimeMillis()}")
        }
        engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_SPEECH_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        onStateChanged(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
