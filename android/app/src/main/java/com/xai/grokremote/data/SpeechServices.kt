package com.xai.grokremote.data

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

data class VoiceOption(
    /** Engine voice id — must be passed back to [SpeechServices.setVoice]. */
    val name: String,
    /** Human-readable primary line (never the raw engine id). */
    val label: String,
    /** Secondary line (on-device vs network / quality / short code). */
    val detail: String,
    val locale: String,
    val quality: Int,
    val networkRequired: Boolean,
    val score: Int,
)

/**
 * On-device STT + system TTS with reliable voice selection.
 *
 * Google TTS exposes cryptic ids like `en-us-x-sfg-network`. We never show those
 * as the primary label, and we apply voices as setLanguage(locale) → setVoice(voice)
 * (setting language *after* setVoice resets the engine to the default voice).
 */
class SpeechServices(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var preferredVoiceName: String? = null
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onVoicesReady: ((List<VoiceOption>) -> Unit)? = null

    fun initTts(preferredName: String? = null, onReady: (() -> Unit)? = null) {
        preferredVoiceName = preferredName?.takeIf { it.isNotBlank() }
        if (tts != null) {
            applyPreferred()
            publishVoices()
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                if (preferredVoiceName.isNullOrBlank()) {
                    preferredVoiceName = pickBestVoice()?.name
                }
                applyPreferred()
                // Engines often finish populating voices shortly after onInit
                mainHandler.postDelayed({
                    if (preferredVoiceName.isNullOrBlank()) {
                        preferredVoiceName = pickBestVoice()?.name
                        applyPreferred()
                    }
                    publishVoices()
                }, 500)
            }
            publishVoices()
            onReady?.invoke()
        }
    }

    fun listVoiceOptions(): List<VoiceOption> {
        val voices = selectableVoices()
        if (voices.isEmpty()) return emptyList()

        // Number same-locale + gender + device/network groups so labels stay unique
        val counters = mutableMapOf<String, Int>()
        return voices
            .sortedByDescending { scoreVoice(it) }
            .map { v ->
                val key =
                    "${v.locale.toLanguageTag()}|${isNetworkVoice(v)}|${genderHint(v) ?: "?"}"
                val n = (counters[key] ?: 0) + 1
                counters[key] = n
                toOption(v, indexInGroup = n)
            }
    }

    fun currentVoiceName(): String? = preferredVoiceName ?: pickBestVoice()?.name

    /** @return true if the engine accepted the voice */
    fun setVoice(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        preferredVoiceName = name
        return applyPreferred()
    }

    fun previewVoice(name: String? = preferredVoiceName) {
        if (name != null) {
            val ok = setVoice(name)
            if (!ok) {
                onError?.invoke("Could not switch to that voice (engine rejected it)")
                return
            }
        } else {
            applyPreferred()
        }
        // Delay: Google TTS often ignores setVoice if speak() is immediate
        mainHandler.postDelayed({
            speak(
                "This is a preview of the selected voice for Grok replies.",
                preview = true,
            )
        }, 180)
    }

    fun speak(text: String, preview: Boolean = false) {
        val engine = tts
        if (!ttsReady || engine == null) return
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("[#*_`>]{1,6}"), " ")
            .trim()
            .take(if (preview) 220 else 1400)
        if (clean.isBlank()) return

        // Re-apply every speak — engines can reset voice after language changes
        if (!applyPreferred()) {
            Log.w(TAG, "speak() without confirmed voice=$preferredVoiceName")
        }
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)
        engine.stop()
        val id = UUID.randomUUID().toString()
        val spoken = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
        if (spoken == TextToSpeech.ERROR) {
            Log.w(TAG, "speak() returned ERROR for voice=$preferredVoiceName")
            onError?.invoke("TTS failed to speak with the selected voice")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    /** Short mid-frequency sine (~880 Hz, ~80 ms). Used as a thinking heartbeat. */
    fun playThinkingBeep() {
        thread(name = "thinking-beep", isDaemon = true) {
            var track: AudioTrack? = null
            try {
                val sampleRate = 22050
                val durationMs = 80
                val freq = 880.0
                val n = sampleRate * durationMs / 1000
                val pcm = ShortArray(n)
                for (i in 0 until n) {
                    val env = sin(PI * i / n).coerceAtLeast(0.0)
                    val sample = sin(2.0 * PI * freq * i / sampleRate) * 0.22 * env
                    pcm[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = at
                at.write(pcm, 0, n)
                at.play()
                Thread.sleep((durationMs + 40).toLong())
            } catch (e: Exception) {
                Log.w(TAG, "thinking beep failed", e)
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
            }
        }
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
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun publishVoices() {
        onVoicesReady?.invoke(listVoiceOptions())
    }

    /**
     * Critical order for Google TTS / Samsung: setLanguage(locale) then setVoice(voice).
     * Never set language after setVoice — that resets to the default for the locale.
     * Re-resolve the [Voice] from the engine after setLanguage (object identity can stale).
     */
    private fun applyPreferred(): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false

        val wantedName = preferredVoiceName
        var voice = resolveVoice(wantedName) ?: return false

        preferredVoiceName = voice.name

        // Stop any in-flight utterance so the engine accepts the new voice
        engine.stop()

        val langResult = engine.setLanguage(voice.locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "setLanguage failed for ${voice.locale}: $langResult")
        }

        // Re-fetch by name after setLanguage — some engines refresh the voice catalog
        voice = resolveVoice(voice.name) ?: voice

        val voiceResult = engine.setVoice(voice)
        if (voiceResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "setVoice failed for ${voice.name}: $voiceResult")
            return false
        }

        val active = engine.voice
        if (active != null && active.name != voice.name) {
            Log.w(TAG, "voice mismatch: wanted=${voice.name} active=${active.name}")
            engine.setLanguage(voice.locale)
            val refreshed = resolveVoice(voice.name) ?: voice
            val retry = engine.setVoice(refreshed)
            if (retry != TextToSpeech.SUCCESS) return false
            val after = engine.voice
            if (after != null && after.name != refreshed.name) {
                Log.w(TAG, "voice still mismatched after retry: ${after.name}")
                return false
            }
        }
        return true
    }

    private fun resolveVoice(name: String?): Voice? {
        val voices = selectableVoices()
        if (voices.isEmpty()) return null
        if (name.isNullOrBlank()) return pickBestVoice()
        voices.firstOrNull { it.name == name }?.let { return it }
        // Match stem: en-us-x-sfg-network ↔ en-us-x-sfg-local when one disappears
        val stem = voiceStem(name)
        if (stem.isNotBlank()) {
            voices.firstOrNull { voiceStem(it.name) == stem && it.name == name }?.let { return it }
            voices.firstOrNull { voiceStem(it.name) == stem }?.let { return it }
        }
        return null
    }

    private fun selectableVoices(): List<Voice> {
        val all = tts?.voices?.toList().orEmpty()
        return all.filter { v ->
            val langOk =
                v.locale.language == Locale.getDefault().language ||
                    v.locale.language == "en"
            if (!langOk) return@filter false
            val features = v.features ?: emptySet()
            if (features.any { it.equals("notInstalled", ignoreCase = true) }) return@filter false
            true
        }
    }

    private fun pickBestVoice(): Voice? =
        selectableVoices().maxByOrNull { scoreVoice(it) }

    private fun toOption(v: Voice, indexInGroup: Int): VoiceOption {
        val localeDisplay = friendlyLocale(v.locale)
        val gender = genderHint(v)
        val place = if (isNetworkVoice(v)) "Network" else "On-device"
        val quality = qualityLabel(v.quality)
        val code = shortEngineId(v.name)

        // Primary: "English (US) · Female · 2" — never "sfg network"
        val title = buildString {
            append(localeDisplay)
            if (gender != null) {
                append(" · ")
                append(gender)
            } else {
                append(" · Voice ")
                append(indexInGroup)
            }
            // Extra disambiguation when several share locale+gender
            if (gender != null && indexInGroup > 1) {
                append(" · ")
                append(indexInGroup)
            }
        }

        val detail = buildString {
            append(place)
            append(" · ")
            append(quality)
            if (code.isNotBlank()) {
                append(" · ")
                append(code)
            }
        }

        return VoiceOption(
            name = v.name,
            label = title,
            detail = detail,
            locale = v.locale.toLanguageTag(),
            quality = v.quality,
            networkRequired = isNetworkVoice(v),
            score = scoreVoice(v),
        )
    }

    private fun friendlyLocale(locale: Locale): String {
        val lang = locale.getDisplayLanguage(Locale.getDefault()).ifBlank { locale.language }
        val country = locale.getDisplayCountry(Locale.getDefault())
        return if (country.isNotBlank()) {
            // Prefer short region: "English (US)" over "English (United States)"
            val short = locale.country.ifBlank { country }
            "$lang ($short)"
        } else {
            lang
        }
    }

    /** Core id: en-us-x-sfg-network → sfg */
    private fun shortEngineId(raw: String): String {
        val lower = raw.lowercase()
        val afterX = Regex("""-x-([a-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
        if (!afterX.isNullOrBlank() && afterX.length <= 6) return afterX
        return lower
            .removeSuffix("-network")
            .removeSuffix("-local")
            .substringAfterLast("-")
            .take(8)
            .takeIf { it.isNotBlank() && it.length <= 8 }
            .orEmpty()
    }

    private fun voiceStem(raw: String): String {
        return raw.lowercase()
            .removeSuffix("-network")
            .removeSuffix("-local")
            .trim()
    }

    private fun isNetworkVoice(v: Voice): Boolean {
        if (v.isNetworkConnectionRequired) return true
        val n = v.name.lowercase()
        if (n.endsWith("-network") || n.contains("network")) return true
        val features = v.features ?: emptySet()
        return features.any {
            it.contains("network", ignoreCase = true)
        }
    }

    private fun genderHint(v: Voice): String? {
        val features = v.features?.joinToString(" ")?.lowercase().orEmpty()
        val n = v.name.lowercase()
        val blob = "$features $n"
        when {
            blob.contains("female") || blob.contains("woman") -> return "Female"
            Regex("""\bmale\b""").containsMatchIn(blob) && !blob.contains("female") ->
                return "Male"
        }
        // Google 3-letter codes after -x- (community / observed mappings)
        val code = shortEngineId(v.name)
        GOOGLE_GENDER[code]?.let { return it }
        return null
    }

    private fun qualityLabel(q: Int): String = when {
        q >= Voice.QUALITY_VERY_HIGH -> "Very high"
        q >= Voice.QUALITY_HIGH -> "High"
        q >= Voice.QUALITY_NORMAL -> "Normal"
        q >= Voice.QUALITY_LOW -> "Low"
        else -> "Very low"
    }

    private fun scoreVoice(v: Voice): Int {
        var s = 0
        val n = v.name.lowercase()
        if (n.contains("neural") || n.contains("natural") || n.contains("enhanced") || n.contains("wavenet")) {
            s += 50
        }
        // Prefer on-device when quality is decent (more reliable; audible differences)
        if (!isNetworkVoice(v) && v.quality >= Voice.QUALITY_NORMAL) s += 20
        if (isNetworkVoice(v)) s += 8
        if (n.contains("compact") || n.contains("legacy")) s -= 40
        if (v.locale == Locale.getDefault()) s += 40
        else if (v.locale.language == Locale.getDefault().language) s += 20
        else if (v.locale.language == "en") s += 10
        s += when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> 20
            v.quality >= Voice.QUALITY_HIGH -> 14
            v.quality >= Voice.QUALITY_NORMAL -> 8
            else -> 0
        }
        if (genderHint(v) != null) s += 3
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

    companion object {
        private const val TAG = "GrokSpeech"

        /**
         * Approximate gender for Google TTS -x- codes (device-dependent; used only for labels).
         * Unknown codes fall back to "Voice N".
         */
        private val GOOGLE_GENDER = mapOf(
            // Common en-US / en-GB style codes observed on Google TTS
            "sfg" to "Female",
            "tpf" to "Female",
            "iob" to "Female",
            "iom" to "Female",
            "iol" to "Female",
            "iof" to "Female",
            "rfg" to "Female",
            "cfg" to "Female",
            "c-f" to "Female",
            "tpd" to "Male",
            "tpg" to "Male",
            "iog" to "Male",
            "iop" to "Male",
            "spc" to "Male",
            "rmg" to "Male",
            "cmg" to "Male",
            "c-m" to "Male",
        )
    }
}
