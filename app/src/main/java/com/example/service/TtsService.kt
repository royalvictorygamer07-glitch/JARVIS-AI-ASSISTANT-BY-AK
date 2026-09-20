package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class TtsService(
    private val context: Context,
    private val onStateChanged: (isSpeaking: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsService"
    }

    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    private var currentProfile: String = "Puck"
    private val speechQueue = ConcurrentLinkedQueue<String>()

    init {
        initEngine()
    }

    private fun initEngine() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                var configured = false
                val preferredLocales = listOf(
                    Locale("en", "IN"),
                    Locale.getDefault(),
                    Locale.US,
                    Locale("hi", "IN")
                )
                for (loc in preferredLocales) {
                    val res = engine.setLanguage(loc)
                    if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                        configured = true
                        break
                    }
                }
                if (!configured) {
                    engine.language = Locale.getDefault()
                }

                isInitialized = true
                applyVoiceProfile(currentProfile)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onStateChanged(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        onStateChanged(false)
                        processNextQueuedSpeech()
                    }

                    override fun onError(utteranceId: String?) {
                        onStateChanged(false)
                        processNextQueuedSpeech()
                    }
                })

                processNextQueuedSpeech()
            }
        } else {
            Log.e(TAG, "TextToSpeech onInit failed with status $status")
            // Attempt fallback initialization once
            try {
                tts = TextToSpeech(context, this)
            } catch (_: Exception) {}
        }
    }

    private fun processNextQueuedSpeech() {
        val next = speechQueue.poll() ?: return
        speak(next)
    }

    private fun ensureAudibleVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == 0 || (current.toFloat() / max.toFloat()) < 0.35f) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.75f).toInt().coerceAtLeast(1), 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttrs)
                    .build()
                am.requestAudioFocus(focusReq)
            }
        } catch (_: Exception) {}
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

        try {
            if (isGirl) {
                engine.setPitch(1.18f)
                engine.setSpeechRate(1.05f)

                val availableVoices = engine.voices
                if (!availableVoices.isNullOrEmpty()) {
                    val femaleVoice = availableVoices.firstOrNull { voice ->
                        !voice.isNetworkConnectionRequired && (
                                voice.name.contains("female", ignoreCase = true) ||
                                        voice.name.contains("f0", ignoreCase = true) ||
                                        voice.name.contains("en-in-x-end", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-sfg", ignoreCase = true) ||
                                        voice.name.contains("hi-in-x-hie", ignoreCase = true) ||
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
            } else {
                engine.setPitch(1.00f)
                engine.setSpeechRate(1.06f)

                val availableVoices = engine.voices
                if (!availableVoices.isNullOrEmpty()) {
                    val maleVoice = availableVoices.firstOrNull { voice ->
                        !voice.isNetworkConnectionRequired && (
                                voice.name.contains("male", ignoreCase = true) ||
                                        voice.name.contains("m0", ignoreCase = true) ||
                                        voice.name.contains("en-in-x-ena", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-iom", ignoreCase = true) ||
                                        voice.name.contains("en-us-x-sfg#male", ignoreCase = true)
                                )
                    }
                    if (maleVoice != null) {
                        engine.voice = maleVoice
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying voice profile: ${e.message}")
        }
    }

    private fun sanitizeForSpeech(raw: String): String {
        var s = raw
        // Clean URL to friendly words
        s = s.replace(Regex("https?://t\\.me/\\S+"), "AK EXPLOITS Telegram channel")
        s = s.replace(Regex("https?://\\S+"), "link")
        // Remove markdown tokens
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

    private fun containsDevanagari(text: String): Boolean {
        return text.any { it in '\u0900'..'\u097F' }
    }

    fun speak(text: String) {
        val cleaned = sanitizeForSpeech(text)
        if (cleaned.isBlank()) {
            onStateChanged(false)
            return
        }

        ensureAudibleVolume()

        if (!isInitialized || tts == null) {
            speechQueue.offer(cleaned)
            initEngine()
            return
        }

        val engine = tts ?: return

        try {
            if (containsDevanagari(cleaned)) {
                val hiLocale = Locale("hi", "IN")
                if (engine.isLanguageAvailable(hiLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = hiLocale
                }
            } else {
                val enInLocale = Locale("en", "IN")
                if (engine.isLanguageAvailable(enInLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = enInLocale
                } else {
                    engine.language = Locale.getDefault()
                }
            }
        } catch (_: Exception) {}

        applyVoiceProfile(currentProfile)

        val utteranceId = "JARVIS_SPEECH_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "engine.speak failed code $result, retrying with default language and engine")
            try {
                engine.language = Locale.getDefault()
                engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } catch (retryEx: Exception) {
                Log.e(TAG, "Retry speak error: ${retryEx.message}")
            }
        }
    }

    fun testVoice(message: String = "Jarvis audio output is 100% online and functional.") {
        speak(message)
    }

    fun stop() {
        speechQueue.clear()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        onStateChanged(false)
    }

    fun shutdown() {
        speechQueue.clear()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }
}
