package com.weblauncher.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ======================= Expenses =======================

data class Expense(val id: String, val amount: Double, val desc: String, val cat: String, val time: Long)

object Expenses {
    val CATS = listOf(
        "🛒 Spesa", "🍕 Ristoranti e bar", "⛽ Trasporti", "🏠 Casa e bollette", "💊 Salute",
        "🛍 Shopping", "🎬 Svago", "📦 Altro"
    )
    private val keywords = mapOf(
        "🛒 Spesa" to listOf("spesa", "supermercato", "esselunga", "coop", "conad", "lidl", "carrefour", "eurospin", "pam", "despar", "penny", "aldi", "md ", "pane", "latte", "frutta", "alimentari", "mercato"),
        "🍕 Ristoranti e bar" to listOf("pizza", "ristorante", "bar", "caffè", "caffe", "aperitivo", "pranzo", "cena", "colazione", "gelato", "sushi", "kebab", "trattoria", "pub", "just eat", "deliveroo", "glovo"),
        "⛽ Trasporti" to listOf("benzina", "gasolio", "diesel", "carburante", "treno", "biglietto", "autobus", "bus", "metro", "taxi", "uber", "parcheggio", "autostrada", "pedaggio", "telepass", "trenitalia", "italo", "flixbus", "ryanair", "volo"),
        "🏠 Casa e bollette" to listOf("bolletta", "luce", "gas", "acqua", "affitto", "condominio", "internet", "telefono", "tim", "vodafone", "iliad", "fastweb", "enel", "tari", "imu", "rata", "mutuo"),
        "💊 Salute" to listOf("farmacia", "medicina", "medico", "dentista", "visita", "ticket", "ottico", "analisi"),
        "🛍 Shopping" to listOf("amazon", "vestiti", "scarpe", "zalando", "regalo", "ikea", "decathlon", "mediaworld", "elettronica", "libro", "shein", "temu"),
        "🎬 Svago" to listOf("cinema", "netflix", "spotify", "concerto", "teatro", "palestra", "abbonamento", "museo", "giochi", "disney", "dazn", "viaggio", "hotel", "booking")
    )

    fun guess(desc: String): String {
        val d = " " + desc.lowercase(Locale.ITALIAN) + " "
        return keywords.entries.firstOrNull { (_, words) -> words.any { d.contains(it) } }?.key ?: "📦 Altro"
    }

    private fun file(c: Context) = File(c.filesDir, "expenses.json")
    fun load(c: Context): MutableList<Expense> = runCatching {
        Gson().fromJson<MutableList<Expense>>(file(c).readText(), object : TypeToken<MutableList<Expense>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun save(c: Context, list: List<Expense>) { runCatching { file(c).writeText(Gson().toJson(list)) } }
    fun add(c: Context, e: Expense) = save(c, load(c) + e)

    /** "speso 12 euro pizza", "ho speso 12,50 al supermercato", "45 euro benzina". */
    fun parseVoice(text: String): Pair<Double, String>? {
        val m = Regex("^(?:ho\\s+)?(?:speso|pagato|spesa di)?\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*(?:euro|€|eur)?\\s*(?:per|in|al|allo|alla|ai|alle|di|da|dal)?\\s*(.*)$",
            RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        if (!text.trim().lowercase(Locale.ITALIAN).let { it.startsWith("speso") || it.startsWith("ho speso") || it.startsWith("pagato") || it.contains("euro") }) return null
        val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return amount to m.groupValues[2].trim().replaceFirstChar { it.uppercase() }.ifBlank { "Spesa" }
    }

    fun fmt(v: Double): String = String.format(Locale.ITALIAN, "%,.2f €", v)
}

// ======================= Places & parking =======================

data class Place(
    val id: String, val name: String, val lat: Double, val lon: Double, val time: Long,
    val note: String = "", val photo: String? = null, val parking: Boolean = false, val until: Long = 0L
)

object Places {
    private fun file(c: Context) = File(c.filesDir, "places.json")
    fun dir(c: Context) = File(c.filesDir, "places").apply { mkdirs() }
    fun load(c: Context): MutableList<Place> = runCatching {
        Gson().fromJson<MutableList<Place>>(file(c).readText(), object : TypeToken<MutableList<Place>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun save(c: Context, list: List<Place>) { runCatching { file(c).writeText(Gson().toJson(list)) } }
    fun parking(c: Context) = load(c).firstOrNull { it.parking }
}

// ======================= Vault (encrypted with a key kept in the Android Keystore) =======================

data class VaultItem(val id: String, val title: String, val kind: String, val time: Long)

object Vault {
    private const val ALIAS = "wl_vault_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(data)
    }

    fun decrypt(b: ByteArray): ByteArray {
        val n = b[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 1, n))
        return c.doFinal(b, 1 + n, b.size - 1 - n)
    }

    fun dir(c: Context) = File(c.filesDir, "vault").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.bin")

    fun load(c: Context): MutableList<VaultItem> = runCatching {
        val json = String(decrypt(index(c).readBytes()), Charsets.UTF_8)
        Gson().fromJson<MutableList<VaultItem>>(json, object : TypeToken<MutableList<VaultItem>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()

    fun save(c: Context, list: List<VaultItem>) {
        index(c).writeBytes(encrypt(Gson().toJson(list).toByteArray(Charsets.UTF_8)))
    }

    fun put(c: Context, item: VaultItem, data: ByteArray) {
        File(dir(c), item.id + ".bin").writeBytes(encrypt(data))
        save(c, listOf(item) + load(c))
    }

    fun read(c: Context, id: String): ByteArray = decrypt(File(dir(c), "$id.bin").readBytes())

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.bin").delete()
        save(c, load(c).filter { it.id != id })
    }
}

// ======================= Deadlines, subscriptions, warranties =======================

data class Deadline(
    val id: String, val kind: String, val title: String, val date: Long,
    val amount: Double = 0.0, val period: String? = null, val photo: String? = null,
    val note: String = "", val reminders: List<Int> = emptyList()
)

object Deadlines {
    const val DEADLINE = "scadenza"; const val SUBSCRIPTION = "abbonamento"; const val WARRANTY = "garanzia"
    private fun file(c: Context) = File(c.filesDir, "deadlines.json")
    fun dir(c: Context) = File(c.filesDir, "deadlines").apply { mkdirs() }
    fun load(c: Context): MutableList<Deadline> = runCatching {
        Gson().fromJson<MutableList<Deadline>>(file(c).readText(), object : TypeToken<MutableList<Deadline>>() {}.type)
    }.getOrNull()?.filterNotNull()?.map { it.copy(reminders = (it.reminders as List<Int>?) ?: emptyList(), note = (it.note as String?) ?: "") }
        ?.toMutableList() ?: mutableListOf()
    fun save(c: Context, list: List<Deadline>) { runCatching { file(c).writeText(Gson().toJson(list)) } }

    fun monthly(d: Deadline): Double = when (d.period) { "anno" -> d.amount / 12; "settimana" -> d.amount * 52 / 12; else -> d.amount }

    private fun at9(day: Long, minusDays: Int): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = day; add(java.util.Calendar.DAY_OF_YEAR, -minusDays)
        set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
    }.timeInMillis

    /** (Re)creates the reminders of an item; returns the item with the new reminder ids. */
    fun schedule(c: Context, d: Deadline): Deadline {
        d.reminders.forEach { Reminders.remove(c, it) }
        val f = java.text.SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(java.util.Date(d.date))
        val now = System.currentTimeMillis()
        val plan: List<Pair<Long, String>> = when (d.kind) {
            SUBSCRIPTION -> listOf(at9(d.date, 3) to "🔁 ${d.title} si rinnova il $f (${Expenses.fmt(d.amount)}): disdici se non lo usi")
            WARRANTY -> listOf(at9(d.date, 30) to "🧾 La garanzia di ${d.title} scade il $f")
            else -> listOf(30, 7, 1).map { at9(d.date, it) to "📆 ${d.title} scade il $f" }
        }
        val ids = plan.filter { it.first > now }.map { (at, text) -> Reminders.add(c, text, at).id }
        return d.copy(reminders = ids)
    }

    /** Subscriptions whose renewal has passed move to the next period. */
    fun advance(c: Context) {
        val now = System.currentTimeMillis()
        var changed = false
        val list = load(c).map { d ->
            if (d.kind != SUBSCRIPTION || d.date > now) d else {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = d.date }
                while (cal.timeInMillis <= now) when (d.period) {
                    "anno" -> cal.add(java.util.Calendar.YEAR, 1)
                    "settimana" -> cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
                    else -> cal.add(java.util.Calendar.MONTH, 1)
                }
                changed = true
                schedule(c, d.copy(date = cal.timeInMillis))
            }
        }
        if (changed) save(c, list)
    }
}

// ======================= Recipes =======================

data class Recipe(
    val id: String, val title: String, val url: String, val image: String?, val servings: String,
    val time: String, val ingredients: List<String>, val steps: List<String>, val saved: Long
)

object Recipes {
    private fun file(c: Context) = File(c.filesDir, "recipes.json")
    fun load(c: Context): MutableList<Recipe> = runCatching {
        Gson().fromJson<MutableList<Recipe>>(file(c).readText(), object : TypeToken<MutableList<Recipe>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun save(c: Context, list: List<Recipe>) { runCatching { file(c).writeText(Gson().toJson(list)) } }
}
