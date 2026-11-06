package com.weblauncher.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class AppRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wl_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadApps(): MutableList<AppItem> {
        val json = prefs.getString(KEY_APPS, null)
        if (json.isNullOrBlank()) {
            val defaults = defaultApps()
            saveApps(defaults)
            return defaults
        }
        return try {
            val type = object : TypeToken<MutableList<AppItem>>() {}.type
            val list = gson.fromJson<MutableList<AppItem>>(json, type) ?: defaultApps()
            list.forEachIndexed { i, a -> if (a.sortOrder == 0) a.sortOrder = i }
            list.sortBy { it.sortOrder }
            list
        } catch (_: Exception) {
            defaultApps()
        }
    }

    fun saveApps(apps: List<AppItem>) {
        apps.forEachIndexed { i, a -> a.sortOrder = i }
        prefs.edit().putString(KEY_APPS, gson.toJson(apps)).apply()
    }

    fun loadCategories(): MutableList<String> {
        val json = prefs.getString(KEY_CATS, null)
        if (json.isNullOrBlank()) return defaultCategories().toMutableList()
        return try {
            val type = object : TypeToken<MutableList<String>>() {}.type
            gson.fromJson<MutableList<String>>(json, type) ?: defaultCategories().toMutableList()
        } catch (_: Exception) {
            defaultCategories().toMutableList()
        }
    }

    fun saveCategories(cats: List<String>) {
        prefs.edit().putString(KEY_CATS, gson.toJson(cats)).apply()
    }

    fun getSearchEngine(): SearchEngine =
        SearchEngine.fromName(prefs.getString(KEY_ENGINE, SearchEngine.GOOGLE.name))

    fun setSearchEngine(engine: SearchEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun resetDefaults(): MutableList<AppItem> {
        val apps = defaultApps()
        saveApps(apps)
        saveCategories(defaultCategories())
        return apps
    }

    companion object {
        private const val KEY_APPS = "apps"
        private const val KEY_CATS = "categories"
        private const val KEY_ENGINE = "search_engine"

        fun defaultCategories() = listOf(
            "AI Tools", "Viaggi", "Shopping", "Produttivita", "Intrattenimento", "Utility"
        )

        fun defaultApps() = mutableListOf(
            AppItem(UUID.randomUUID().toString(), "ChatGPT", "https://chatgpt.com", "AI Tools", true, 0),
            AppItem(UUID.randomUUID().toString(), "Claude", "https://claude.ai", "AI Tools", false, 1),
            AppItem(UUID.randomUUID().toString(), "Gemini", "https://gemini.google.com", "AI Tools", false, 2),
            AppItem(UUID.randomUUID().toString(), "DeepSeek", "https://chat.deepseek.com", "AI Tools", false, 3),
            AppItem(UUID.randomUUID().toString(), "Grok", "https://x.com/i/grok", "AI Tools", false, 4),
            AppItem(UUID.randomUUID().toString(), "Booking", "https://booking.com", "Viaggi", false, 5),
            AppItem(UUID.randomUUID().toString(), "YouTube", "https://youtube.com", "Intrattenimento", true, 6),
            AppItem(UUID.randomUUID().toString(), "Wikipedia", "https://it.wikipedia.org", "Utility", false, 7),
            AppItem(UUID.randomUUID().toString(), "GitHub", "https://github.com", "Produttivita", false, 8),
            AppItem(UUID.randomUUID().toString(), "Gmail", "https://mail.google.com", "Produttivita", false, 9)
        )
    }
}
