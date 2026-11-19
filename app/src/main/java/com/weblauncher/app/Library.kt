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
    var photo: String? = null,         // vecchio: una sola foto (files/note_photos)
    var remindAt: Long? = null,        // promemoria collegato
    var remindId: Int? = null,
    var photos: List<String>? = null,  // più foto (files/note_photos)
    var archived: Boolean = false,     // 🗄 tolta dall'elenco, non cancellata
    var deletedAt: Long? = null,       // 🗑 nel cestino da questo momento
    var audio: String? = null,         // 🎙 nota vocale (files/note_audio)
    var audioMs: Long? = null,
    var locked: Boolean = false        // 🔒 si apre con l'impronta
)

/** Parti pure delle note (provate nei test): etichette, colori, ricerca, ordine, spunte, cestino. */
object NoteTools {
    val LABELS = listOf("🏠 Casa", "💼 Lavoro", "❤️ Salute", "💶 Soldi", "💡 Idee")
    /** (chiaro, scuro) per ogni colore: giallo, verde, azzurro, rosa, viola. */
    val COLORS = listOf(0xFFFFF4CC.toInt() to 0xFF3A3420.toInt(), 0xFFE2F5E8.toInt() to 0xFF1F3527.toInt(), 0xFFE3ECFF.toInt() to 0xFF1E2A44.toInt(),
        0xFFFCE4EC.toInt() to 0xFF3E2230.toInt(), 0xFFEFE6FB.toInt() to 0xFF2E2440.toInt())
    val COLOR_NAMES = listOf("Giallo", "Verde", "Azzurro", "Rosa", "Viola")
    const val TRASH_DAYS = 7
    val SORTS = listOf("time" to "Ultima modifica", "title" to "Titolo (A–Z)", "color" to "Colore")

    fun isItem(line: String) = line.startsWith("☐ ") || line.startsWith("☑ ")

    /** Testo libero e spunte di una nota: (testo, [(fatto, voce)]). */
    fun split(text: String): Pair<String, List<Pair<Boolean, String>>> {
        val lines = text.lines()
        val prose = lines.filter { !isItem(it) }.joinToString("\n").trim()
        return prose to lines.filter { isItem(it) }.map { it.startsWith("☑ ") to it.drop(2) }
    }
    fun join(prose: String, items: List<Pair<Boolean, String>>): String =
        (listOf(prose.trim()).filter { it.isNotEmpty() } + items.filter { it.second.isNotBlank() }.map { (if (it.first) "☑ " else "☐ ") + it.second.trim() }).joinToString("\n")

    /** Una lista «pura» (titolo + spunte, niente altro): si apre nella pagina della lista. */
    fun isPureList(n: Note): Boolean {
        val (prose, items) = split(n.text)
        return items.isNotEmpty() && prose.lines().count { it.isNotBlank() } <= 1 && photosOf(n).isEmpty() && n.audio == null && !n.locked
    }

    fun photosOf(n: Note): List<String> = ((n.photos ?: emptyList()) + listOfNotNull(n.photo)).distinct()

    fun isTrashed(n: Note) = n.deletedAt != null
    /** Note nel cestino da più di [TRASH_DAYS] giorni: da eliminare per sempre. */
    fun expired(n: Note, now: Long) = n.deletedAt?.let { now - it > TRASH_DAYS * 86_400_000L } == true
    fun daysLeft(n: Note, now: Long): Int = n.deletedAt?.let { (TRASH_DAYS - ((now - it) / 86_400_000L)).toInt().coerceAtLeast(0) } ?: TRASH_DAYS

    fun matches(n: Note, query: String, label: String?): Boolean {
        if (label != null && n.label != label) return false
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val body = if (n.locked) title(n) else n.text   // di una nota bloccata si cerca solo il titolo
        return body.lowercase().contains(q) || (n.label?.lowercase()?.contains(q) == true)
    }

    /** Prima le fissate, poi nell'ordine scelto. */
    fun sorted(list: List<Note>, by: String = "time"): List<Note> {
        val rest: Comparator<Note> = when (by) {
            "title" -> compareBy<Note> { title(it).lowercase() }
            "color" -> compareBy<Note> { it.color ?: 99 }.thenByDescending { it.time }
            else -> compareByDescending<Note> { it.time }
        }
        return list.sortedWith(compareByDescending<Note> { it.pinned }.then(rest))
    }

    fun title(n: Note): String = split(n.text).first.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
        ?: split(n.text).second.firstOrNull()?.second?.take(80)
        ?: if (n.audio != null) "Nota vocale" else if (photosOf(n).isNotEmpty()) "Foto" else "Nota"

    fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
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
