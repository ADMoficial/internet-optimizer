package com.internetoptimizer.app

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistence of [AppConfig] via SharedPreferences.
 *
 * The config is stored as a JSON string for easy extensibility.
 * All reads are synchronous (SharedPreferences is fast for small data).
 */
class ConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadConfig(): AppConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
        return try {
            gson.fromJson(json, AppConfig::class.java)
        } catch (_: Exception) {
            AppConfig() // Fall back to defaults on parse error
        }
    }

    fun saveConfig(config: AppConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString(KEY_CONFIG, json).apply()
    }

    fun clearConfig() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    companion object {
        const val PREFS_NAME = "internet_optimizer_prefs"
        const val KEY_CONFIG = "app_config"
    }
}
