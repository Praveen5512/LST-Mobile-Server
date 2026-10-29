package com.localserver.core

import android.content.Context
import android.os.Build

data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND_ADDRESS,
    val serverName: String? = null
) {
    init {
        require(isValidPort(port)) {
            "Port must be between $MIN_PORT and $MAX_PORT. Provided: $port"
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        private const val PREFS_NAME = "lms_server_config"
        private const val KEY_SERVER_NAME = "custom_server_name"
        private const val KEY_SERVER_PORT = "custom_server_port"

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        fun getDefaultServerName(): String {
            val cleanModel = Build.MODEL.replace("[^a-zA-Z0-9]".toRegex(), "").ifEmpty { "Android" }.take(12)
            return "LSTServer-$cleanModel"
        }

        fun getSavedServerName(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_SERVER_NAME, null) ?: getDefaultServerName()
        }

        fun saveServerName(context: Context, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SERVER_NAME, name.trim()).apply()
        }

        fun getSavedPort(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT)
        }

        fun savePort(context: Context, port: Int) {
            if (isValidPort(port)) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt(KEY_SERVER_PORT, port).apply()
            }
        }

        private const val KEY_EXTERNAL_AUDIO = "pref_external_audio"

        fun isExternalAudioPlayerEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_EXTERNAL_AUDIO, false)
        }

        fun setExternalAudioPlayerEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_EXTERNAL_AUDIO, enabled).apply()
        }
    }
}
