package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceRecognitionHelper(
    private val context: Context,
    private val onAudioLevel: (Float) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onPartialResult: ((String) -> Unit)? = null,
    private val isMutedProvider: (() -> Boolean)? = null,
    private val onFallbackRequested: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "VoiceRecognitionHelper"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    fun isRecognitionAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    fun startListening() {
        mainHandler.post {
            try {
                if (isMutedProvider?.invoke() == true) {
                    onError("Microphone is muted. Tap UNMUTE to speak.")
                    return@post
                }

                if (!isRecognitionAvailable()) {
                    Log.w(TAG, "SpeechRecognizer not directly available, calling fallback dialog")
                    if (onFallbackRequested != null) {
                        onFallbackRequested.invoke()
                    } else {
                        onError("Speech recognition is not available on this device.")
                    }
                    return@post
                }

                stopListeningInternal()

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            onListeningStateChanged(true)
                        }

                        override fun onBeginningOfSpeech() {
                            isListening = true
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            // Map rmsdB (-2 to 10 typical) to 0.0 - 1.0 smoothly
                            val normalized = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
                            onAudioLevel(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            isListening = false
                            onListeningStateChanged(false)
                        }

                        override fun onError(error: Int) {
                            isListening = false
                            onListeningStateChanged(false)

                            val message = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_CLIENT -> {
                                    // Client error often means internal service crashed or needs fallback
                                    onFallbackRequested?.invoke()
                                    "Voice recognition client reset"
                                }
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                                SpeechRecognizer.ERROR_NETWORK -> "Network issue detected"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection timeout"
                                SpeechRecognizer.ERROR_NO_MATCH -> "Listening timed out. Tap mic to speak."
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                                SpeechRecognizer.ERROR_SERVER -> "Server error"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                                else -> "Recognition error ($error)"
                            }
                            Log.w(TAG, "SpeechRecognizer error: $error -> $message")
                            onError(message)
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            onListeningStateChanged(false)
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: ""
                            if (text.isNotBlank()) {
                                onResult(text)
                            } else {
                                onError("No speech recognized")
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partialText = matches?.firstOrNull()?.trim() ?: ""
                            if (partialText.isNotBlank()) {
                                onPartialResult?.invoke(partialText)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val userLang = Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, userLang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, userLang)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                onListeningStateChanged(false)
                Log.e(TAG, "Exception starting speech recognition: ${e.message}")
                if (onFallbackRequested != null) {
                    onFallbackRequested.invoke()
                } else {
                    onError("Failed to start speech recognition: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun stopListeningInternal() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
    }

    fun stopListening() {
        mainHandler.post {
            stopListeningInternal()
            onListeningStateChanged(false)
        }
    }

    fun isCurrentlyListening(): Boolean = isListening
}
