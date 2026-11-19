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

data class Note(
    val id: String, var text: String, var time: Long,
    var pinned: Boolean = false,       // 📌 in alto
    var color: Int? = null,            // indice in NoteTools.COLORS
    var label: String? = null,         // una di NoteTools.LABELS
    var photo: String? = null,         // nome del file in files/note_photos
    var remindAt: Long? = null,        // promemoria collegato
    var remindId: Int? = null
)

/** Parti pure delle note (provate nei test): etichette, colori, ricerca e ordine. */
object NoteTools {
    val LABELS = listOf("🏠 Casa", "💼 Lavoro", "❤️ Salute", "💶 Soldi", "💡 Idee")
    /** (chiaro, scuro) per ogni colore: giallo, verde, azzurro, rosa, viola. */
    val COLORS = listOf(0xFFFFF4CC.toInt() to 0xFF3A3420.toInt(), 0xFFE2F5E8.toInt() to 0xFF1F3527.toInt(), 0xFFE3ECFF.toInt() to 0xFF1E2A44.toInt(),
        0xFFFCE4EC.toInt() to 0xFF3E2230.toInt(), 0xFFEFE6FB.toInt() to 0xFF2E2440.toInt())
    val COLOR_NAMES = listOf("Giallo", "Verde", "Azzurro", "Rosa", "Viola")

    fun matches(n: Note, query: String, label: String?): Boolean {
        if (label != null && n.label != label) return false
        val q = query.trim().lowercase()
        return q.isEmpty() || n.text.lowercase().contains(q) || (n.label?.lowercase()?.contains(q) == true)
    }

    /** Prima le fissate, poi le più recenti. */
    fun sorted(list: List<Note>): List<Note> = list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.time })

    fun title(n: Note): String = n.text.trim().lines().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: if (n.photo != null) "Foto" else "Nota"
}

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
        fun cards(context: Context) =
            JsonStore<LoyaltyCard>(context, "cards.json", object : TypeToken<MutableList<LoyaltyCard>>() {}.type)
        fun wallet(context: Context) =
            JsonStore<WalletItem>(context, "wallet.json", object : TypeToken<MutableList<WalletItem>>() {}.type)
        fun notes(context: Context) =
            JsonStore<Note>(context, "notes.json", object : TypeToken<MutableList<Note>>() {}.type)
    }
}
