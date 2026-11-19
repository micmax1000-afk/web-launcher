package com.weblauncher.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Le conversazioni della finestra «Chiedi»: si salvano sul telefono e si riprendono dalla Cronologia. */
object AskChats {
    data class Msg(val role: String, val text: String)
    data class Chat(val id: String, val title: String, val time: Long, val msgs: List<Msg>)

    const val MAX_CHATS = 30
    private const val KEY = "chats"
    private fun prefs(c: Context) = c.getSharedPreferences("wl_ask", Context.MODE_PRIVATE)

    fun encode(list: List<Chat>): String = Gson().toJson(list)
    fun decode(s: String?): List<Chat> = runCatching {
        Gson().fromJson<List<Chat>>(s, object : TypeToken<List<Chat>>() {}.type)
    }.getOrNull().orEmpty().filterNotNull().filter { it.id.isNotBlank() && it.msgs != null }

    /** Il titolo è l'inizio della prima domanda. */
    fun titleOf(first: String): String {
        val t = first.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= 40) t.ifBlank { "Conversazione" } else t.take(40).trimEnd() + "…"
    }

    /** Mette [chat] in testa (sostituendo la versione vecchia) e tiene al massimo [MAX_CHATS] conversazioni. */
    fun upsert(list: List<Chat>, chat: Chat): List<Chat> = (listOf(chat) + list.filter { it.id != chat.id }).take(MAX_CHATS)

    fun load(c: Context): List<Chat> = decode(prefs(c).getString(KEY, null)).sortedByDescending { it.time }
    fun save(c: Context, chat: Chat) { prefs(c).edit().putString(KEY, encode(upsert(load(c), chat))).apply() }
    fun delete(c: Context, id: String) { prefs(c).edit().putString(KEY, encode(load(c).filter { it.id != id })).apply() }
    fun clear(c: Context) { prefs(c).edit().remove(KEY).apply() }
}
