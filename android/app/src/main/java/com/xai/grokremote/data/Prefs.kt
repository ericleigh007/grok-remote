package com.xai.grokremote.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "grok_remote_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String
        get() = prefs.getString(KEY_BASE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BASE, v).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(v) = prefs.edit().putBoolean(KEY_TTS, v).apply()

    var ttsVoiceName: String
        get() = prefs.getString(KEY_TTS_VOICE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TTS_VOICE, v).apply()

    /** Short beep while the model is thinking. Off by default. */
    var thinkingSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_SOUND, false)
        set(v) = prefs.edit().putBoolean(KEY_THINKING_SOUND, v).apply()

    var lastSessionId: String
        get() = prefs.getString(KEY_LAST_SESSION, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_SESSION, v).apply()

    /** Keep the mic open across pauses. Off = old one-shot that cuts on silence. */
    var pauseTolerantStt: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_TOLERANT_STT, true)
        set(v) = prefs.edit().putBoolean(KEY_PAUSE_TOLERANT_STT, v).apply()

    /** Send the transcript when a listen session ends. Off = leave it in the composer. */
    var autoSendVoice: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND_VOICE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SEND_VOICE, v).apply()

    /** Stay put if you scroll up during a long reply. Off = always jump to latest. */
    var holdScroll: Boolean
        get() = prefs.getBoolean(KEY_HOLD_SCROLL, true)
        set(v) = prefs.edit().putBoolean(KEY_HOLD_SCROLL, v).apply()

    /** Silence after last words before auto-send. 0 = tap mic only. */
    var voiceIdleSendMs: Long
        get() = prefs.getLong(KEY_VOICE_IDLE_SEND_MS, 5_000L)
        set(v) = prefs.edit().putLong(KEY_VOICE_IDLE_SEND_MS, v).apply()

    fun clearPairing() {
        prefs.edit().remove(KEY_BASE).remove(KEY_TOKEN).apply()
    }

    fun hasPairing(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_TTS = "tts"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_THINKING_SOUND = "thinking_sound"
        private const val KEY_LAST_SESSION = "last_session"
        private const val KEY_PAUSE_TOLERANT_STT = "pause_tolerant_stt"
        private const val KEY_AUTO_SEND_VOICE = "auto_send_voice"
        private const val KEY_HOLD_SCROLL = "hold_scroll"
        private const val KEY_VOICE_IDLE_SEND_MS = "voice_idle_send_ms"
    }
}
