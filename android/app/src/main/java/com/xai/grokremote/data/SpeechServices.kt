package com.xai.grokremote.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

data class VoiceOption(
    val name: String,
    val label: String,
    val locale: String,
    val quality: Int,
    val networkRequired: Boolean,
    val score: Int,
)

/**
 * On-device STT (Android SpeechRecognizer) + system TTS with voice selection.
 */
class SpeechServices(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var preferredVoiceName: String? = null
    private var recognizer: SpeechRecognizer? = null

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onVoicesReady: ((List<VoiceOption>) -> Unit)? = null

    fun initTts(preferredName: String? = null, onReady: (() -> Unit)? = null) {
        preferredVoiceName = preferredName?.takeIf { it.isNotBlank() }
        if (tts != null) {
            applyPreferred()
            onVoicesReady?.invoke(listVoiceOptions())
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                if (preferredVoiceName.isNullOrBlank()) {
                    preferredVoiceName = pickBestVoice()?.name
                }
                applyPreferred()
                // Some engines populate voices slightly later
                tts?.setOnUtteranceProgressListener(null)
            }
            onVoicesReady?.invoke(listVoiceOptions())
            onReady?.invoke()
        }
    }

    fun listVoiceOptions(): List<VoiceOption> {
        val voices = tts?.voices?.toList().orEmpty()
        if (voices.isEmpty()) return emptyList()
        return voices
            .filter {
                it.locale.language == Locale.getDefault().language ||
                    it.locale.language == "en"
            }
            .map { v ->
                VoiceOption(
                    name = v.name,
                    label = friendlyLabel(v),
                    locale = v.locale.toLanguageTag(),
                    quality = v.quality,
                    networkRequired = v.isNetworkConnectionRequired,
                    score = scoreVoice(v),
                )
            }
            .sortedByDescending { it.score }
    }

    fun currentVoiceName(): String? = preferredVoiceName ?: pickBestVoice()?.name

    fun setVoice(name: String?) {
        preferredVoiceName = name
        applyPreferred()
    }

    fun previewVoice(name: String? = preferredVoiceName) {
        val prev = preferredVoiceName
        if (name != null) setVoice(name)
        speak("This is how I sound when reading Grok replies.", preview = true)
        // keep selection (preview uses same voice)
        preferredVoiceName = name ?: prev
        applyPreferred()
    }

    fun speak(text: String, preview: Boolean = false) {
        if (!ttsReady) return
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("[#*_`>]{1,6}"), " ")
            .trim()
            .take(if (preview) 200 else 1400)
        if (clean.isBlank()) return
        applyPreferred()
        tts?.setSpeechRate(1.0f)
        tts?.setPitch(1.0f)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition not available on this device")
            return
        }
        stopListening()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onListeningChanged?.invoke(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                onListeningChanged?.invoke(false)
            }
            override fun onError(error: Int) {
                onListeningChanged?.invoke(false)
                if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    onError?.invoke(sttErrorLabel(error))
                }
            }
            override fun onResults(results: Bundle?) {
                onListeningChanged?.invoke(false)
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull().orEmpty()
                if (best.isNotBlank()) onFinal?.invoke(best)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull().orEmpty()
                if (best.isNotBlank()) onPartial?.invoke(best)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        r.startListening(intent)
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        onListeningChanged?.invoke(false)
    }

    fun shutdown() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun applyPreferred() {
        val t = tts ?: return
        val name = preferredVoiceName
        val voice = when {
            !name.isNullOrBlank() -> t.voices?.firstOrNull { it.name == name }
            else -> pickBestVoice()
        }
        if (voice != null) {
            preferredVoiceName = voice.name
            t.voice = voice
            t.language = voice.locale
        }
    }

    private fun pickBestVoice(): Voice? {
        return tts?.voices
            ?.filter {
                it.locale.language == Locale.getDefault().language ||
                    it.locale.language == "en"
            }
            ?.maxByOrNull { scoreVoice(it) }
    }

    private fun friendlyLabel(v: Voice): String {
        val raw = v.name
            .replace(Regex("(?i)en[-_]?us|en[-_]?gb|en[-_]?au"), "")
            .replace(Regex("(?i)x-|<.*?>"), " ")
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val net = if (v.isNetworkConnectionRequired) " · cloud" else " · on-device"
        val locale = v.locale.toLanguageTag()
        val base = raw.ifBlank { v.name }
        return "$base ($locale$net)"
    }

    private fun scoreVoice(v: Voice): Int {
        var s = 0
        val n = v.name.lowercase()
        // Prefer higher quality / neural when available
        if (n.contains("neural") || n.contains("natural") || n.contains("enhanced") || n.contains("wavenet")) {
            s += 50
        }
        if (n.contains("network") || v.isNetworkConnectionRequired) s += 15
        if (n.contains("compact") || n.contains("legacy")) s -= 35
        if (v.locale == Locale.getDefault()) s += 35
        else if (v.locale.language == Locale.getDefault().language) s += 18
        else if (v.locale.language == "en") s += 8
        s += (v.quality / 40).coerceIn(0, 15)
        // mild preference for female/clear names often better for long tech reads — optional boost
        if (n.contains("aria") || n.contains("jenny") || n.contains("sara") || n.contains("zira") ||
            n.contains("sonia") || n.contains("natasha") || n.contains("emma")
        ) {
            s += 8
        }
        if (n.contains("guy") || n.contains("ryan") || n.contains("davis") || n.contains("andrew") ||
            n.contains("brian") || n.contains("david")
        ) {
            s += 6
        }
        return s
    }

    private fun sttErrorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Mic audio error"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "STT needs network for this engine"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission denied"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
        else -> "STT error ($error)"
    }
}
