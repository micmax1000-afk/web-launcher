package com.weblauncher.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

data class ReadLaterItem(
    val id: String,
    val url: String,
    val title: String,
    val site: String,
    val image: String?,
    val text: String,
    val time: Long,
    var read: Boolean = false
)

data class Note(val id: String, var text: String, var time: Long)

/** Small JSON list stored in the app's private files. */
class JsonStore<T>(context: Context, name: String, private val type: Type) {
    private val file = java.io.File(context.filesDir, name)
    private val gson = Gson()

    fun load(): MutableList<T> = try {
        if (!file.exists()) mutableListOf()
        else gson.fromJson<MutableList<T>>(file.readText(), type)?.filterNotNull()?.toMutableList() ?: mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    fun save(list: List<T>) {
        runCatching {
            val tmp = java.io.File(file.path + ".tmp")
            tmp.writeText(gson.toJson(list))
            tmp.renameTo(file)
        }
    }

    companion object {
        fun readLater(context: Context) =
            JsonStore<ReadLaterItem>(context, "read_later.json", object : TypeToken<MutableList<ReadLaterItem>>() {}.type)
        fun notes(context: Context) =
            JsonStore<Note>(context, "notes.json", object : TypeToken<MutableList<Note>>() {}.type)
    }
}
