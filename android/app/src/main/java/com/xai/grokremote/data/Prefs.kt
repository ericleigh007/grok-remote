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

    fun clearPairing() {
        prefs.edit().remove(KEY_BASE).remove(KEY_TOKEN).apply()
    }

    fun hasPairing(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_TTS = "tts"
        private const val KEY_TTS_VOICE = "tts_voice"
    }
}
