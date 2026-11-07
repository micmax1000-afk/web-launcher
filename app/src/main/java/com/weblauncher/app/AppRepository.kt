package com.weblauncher.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class RecentSite(val title: String, val url: String)

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

    fun getAutoBackupUri(): String? = prefs.getString(KEY_BACKUP_URI, null)?.takeIf { it.isNotBlank() }

    fun setAutoBackupUri(uri: String?) {
        prefs.edit().putString(KEY_BACKUP_URI, uri).apply()
    }


    fun getThemeMode(): String = prefs.getString(KEY_THEME, "dark") ?: "dark"

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME, mode).apply()
    }

    fun loadRecents(): MutableList<RecentSite> {
        val json = prefs.getString(KEY_RECENTS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<RecentSite>>() {}.type
            gson.fromJson<MutableList<RecentSite>>(json, type) ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }

    fun addRecent(title: String, url: String) {
        if (!url.startsWith("http")) return
        val list = loadRecents()
        list.removeAll { it.url == url }
        list.add(0, RecentSite(title.ifBlank { url }, url))
        prefs.edit().putString(KEY_RECENTS, gson.toJson(list.take(12))).apply()
    }

    fun clearRecents() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    fun removeRecent(url: String) {
        val list = loadRecents()
        if (!list.removeAll { it.url == url }) return
        if (list.isEmpty()) prefs.edit().remove(KEY_RECENTS).apply()
        else prefs.edit().putString(KEY_RECENTS, gson.toJson(list)).apply()
    }


    fun getAccent(): String = prefs.getString(KEY_ACCENT, "blue") ?: "blue"

    fun setAccent(name: String) {
        prefs.edit().putString(KEY_ACCENT, name).apply()
    }

    fun renameCategory(from: String, to: String) {
        val i = loadCategories().indexOf(from)
        if (i < 0 || to.isBlank() || to == from) return
        val cats = loadCategories()
        cats[i] = to
        saveCategories(cats)
    }

    fun deleteCategory(name: String) {
        val cats = loadCategories()
        cats.remove(name)
        if (cats.isEmpty()) cats.add("Utility")
        saveCategories(cats)
    }

    fun moveCategory(name: String, delta: Int) {
        val cats = loadCategories()
        val i = cats.indexOf(name)
        val j = i + delta
        if (i < 0 || j !in cats.indices) return
        val item = cats.removeAt(i)
        cats.add(j, item)
        saveCategories(cats)
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
        private const val KEY_BACKUP_URI = "auto_backup_uri"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_RECENTS = "recents"

        fun accentStyle(context: Context): Int {
            val accent = context.getSharedPreferences("wl_prefs", Context.MODE_PRIVATE)
                .getString(KEY_ACCENT, "blue")
            return when (accent) {
                "amber" -> R.style.Theme_WebLauncher_Amber
                "green" -> R.style.Theme_WebLauncher_Green
                else -> R.style.Theme_WebLauncher
            }
        }

        fun applySavedTheme(context: Context) {
            val mode = context.getSharedPreferences("wl_prefs", Context.MODE_PRIVATE)
                .getString(KEY_THEME, "dark")
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }

        /** WebView resets uiMode to the system theme while it is created. Put the chosen theme back. */
        fun restoreUiMode(context: Context) {
            val mode = context.getSharedPreferences("wl_prefs", Context.MODE_PRIVATE)
                .getString(KEY_THEME, "dark") ?: "dark"
            val expected = when (mode) {
                "light" -> android.content.res.Configuration.UI_MODE_NIGHT_NO
                "dark" -> android.content.res.Configuration.UI_MODE_NIGHT_YES
                else -> return
            }
            val current = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (current == expected) return
            val config = android.content.res.Configuration(context.resources.configuration)
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or expected
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }

        fun isLightUi(context: Context): Boolean {
            val mode = context.getSharedPreferences("wl_prefs", Context.MODE_PRIVATE)
                .getString(KEY_THEME, "dark") ?: "dark"
            return when (mode) {
                "light" -> true
                "dark" -> false
                else -> (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }

        data class UiPalette(
            val bg: Int,
            val elevated: Int,
            val card: Int,
            val text: Int,
            val textMuted: Int,
            val stroke: Int,
            val iconTile: Int
        )

        fun palette(context: Context): UiPalette = if (isLightUi(context)) {
            UiPalette(
                bg = 0xFFF3F5FA.toInt(),
                elevated = 0xFFFFFFFF.toInt(),
                card = 0xFFFFFFFF.toInt(),
                text = 0xFF121826.toInt(),
                textMuted = 0xFF5C6B82.toInt(),
                stroke = 0xFFE1E6F0.toInt(),
                iconTile = 0xFFF7F8FC.toInt()
            )
        } else {
            UiPalette(
                bg = 0xFF0A0E17.toInt(),
                elevated = 0xFF121826.toInt(),
                card = 0xFF1A2333.toInt(),
                text = 0xFFF0F4FA.toInt(),
                textMuted = 0xFF8B9BB4.toInt(),
                stroke = 0xFF2A3548.toInt(),
                iconTile = 0xFF222C3F.toInt()
            )
        }

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
