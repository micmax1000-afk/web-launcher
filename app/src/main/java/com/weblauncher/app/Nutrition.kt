package com.weblauncher.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Energy needs, BMI and water goal. Pure functions (unit-tested). */
object Nutrition {
    /** Mifflin–St Jeor. sex = "m" | "f". */
    fun bmr(sex: String, weightKg: Double, heightCm: Double, age: Int): Double =
        10 * weightKg + 6.25 * heightCm - 5 * age + (if (sex == "m") 5 else -161)

    val ACTIVITY = listOf(
        "Sedentario (poco movimento)" to 1.2, "Leggero (1-3 allenamenti a settimana)" to 1.375,
        "Moderato (3-5 a settimana)" to 1.55, "Intenso (6-7 a settimana)" to 1.725
    )
    val GOALS = listOf("lose" to "Dimagrire", "keep" to "Mantenere il peso", "gain" to "Mettere massa")

    data class Targets(val kcal: Int, val proteinG: Int, val carbG: Int, val fatG: Int)

    fun targets(sex: String, weightKg: Double, heightCm: Double, age: Int, activity: Double, goal: String): Targets {
        val tdee = bmr(sex, weightKg, heightCm, age) * activity
        val floor = if (sex == "m") 1500.0 else 1200.0                      // never suggest less than a safe minimum
        val kcal = when (goal) { "lose" -> tdee * 0.85; "gain" -> tdee + 300; else -> tdee }.coerceAtLeast(floor).roundToInt()
        val protein = (weightKg * (if (goal == "gain") 1.8 else 1.6)).roundToInt()
        val fat = (kcal * 0.28 / 9).roundToInt()
        val carbs = ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(0)
        return Targets(kcal, protein, carbs, fat)
    }

    fun bmi(weightKg: Double, heightCm: Double) = if (heightCm <= 0) 0.0 else weightKg / Math.pow(heightCm / 100, 2.0)
    fun bmiLabel(b: Double) = when { b <= 0 -> "—"; b < 18.5 -> "Sottopeso"; b < 25 -> "Normopeso"; b < 30 -> "Sovrappeso"; else -> "Obesità" }
    /** Healthy weight range for a height (BMI 18.5–24.9), in kg. */
    fun healthyRange(heightCm: Double): Pair<Double, Double> = (18.5 * Math.pow(heightCm / 100, 2.0)) to (24.9 * Math.pow(heightCm / 100, 2.0))

    /** About 33 ml per kg of weight, in steps of 50 ml, between 1.5 and 3.5 litres. */
    fun waterGoalMl(weightKg: Double): Int = ((weightKg * 33 / 50.0).roundToInt() * 50).coerceIn(1500, 3500)

    fun ageFrom(birthYear: Int, now: Calendar = Calendar.getInstance()) = (now.get(Calendar.YEAR) - birthYear).coerceAtLeast(0)
}

/** Products from Open Food Facts (free, no account). Parsing is pure and unit-tested. */
object OpenFoodFacts {
    data class Product(
        val barcode: String, val name: String, val brand: String, val quantity: String, val nutriScore: String, val nova: Int,
        val kcal100: Double, val protein100: Double, val carbs100: Double, val sugars100: Double, val fat100: Double,
        val satFat100: Double, val fiber100: Double, val salt100: Double,
        val allergens: List<String>, val additives: List<String>, val ingredients: String, val servingG: Double
    )

    val ALLERGENS = linkedMapOf(
        "gluten" to "Glutine", "milk" to "Latte", "eggs" to "Uova", "peanuts" to "Arachidi", "nuts" to "Frutta a guscio",
        "soybeans" to "Soia", "fish" to "Pesce", "crustaceans" to "Crostacei", "molluscs" to "Molluschi", "celery" to "Sedano",
        "mustard" to "Senape", "sesame-seeds" to "Sesamo", "sulphur-dioxide-and-sulphites" to "Solfiti", "lupin" to "Lupini"
    )

    private fun JsonObject.num(k: String): Double = get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
    private fun JsonObject.str(k: String): String = get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty().trim()
    private fun JsonElement?.tags(): List<String> {
        val arr = this?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { runCatching { it.asString }.getOrNull() }
    }

    fun allergenLabel(tag: String): String {
        val key = tag.substringAfter(':').lowercase(Locale.ROOT)
        return ALLERGENS[key] ?: key.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    /** null if the product is unknown. */
    fun parse(json: String, barcode: String): Product? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("status")?.asInt != 1) return null
        val p = root.getAsJsonObject("product") ?: return null
        val n = p.getAsJsonObject("nutriments") ?: JsonObject()
        val kcal = n.num("energy-kcal_100g").takeIf { it > 0 } ?: (n.num("energy_100g") / 4.184)
        val name = p.str("product_name_it").ifBlank { p.str("product_name") }
        Product(
            barcode = barcode, name = name.ifBlank { "Prodotto $barcode" }, brand = p.str("brands").substringBefore(',').trim(),
            quantity = p.str("quantity"), nutriScore = p.str("nutriscore_grade").lowercase(Locale.ROOT).takeIf { it.length == 1 && it[0] in 'a'..'e' }.orEmpty(),
            nova = p.num("nova_group").toInt(),
            kcal100 = kcal, protein100 = n.num("proteins_100g"), carbs100 = n.num("carbohydrates_100g"), sugars100 = n.num("sugars_100g"),
            fat100 = n.num("fat_100g"), satFat100 = n.num("saturated-fat_100g"), fiber100 = n.num("fiber_100g"), salt100 = n.num("salt_100g"),
            allergens = p.get("allergens_tags").tags().map { allergenLabel(it) }.distinct(),
            additives = p.get("additives_tags").tags().map { it.substringAfter(':').uppercase(Locale.ROOT) }.distinct(),
            ingredients = p.str("ingredients_text_it").ifBlank { p.str("ingredients_text") },
            servingG = p.num("serving_quantity")
        )
    }.getOrNull()

    enum class Level { LOW, MEDIUM, HIGH }
    // EU «traffic light» thresholds per 100 g (solids)
    fun sugarLevel(g: Double) = if (g > 22.5) Level.HIGH else if (g > 5) Level.MEDIUM else Level.LOW
    fun fatLevel(g: Double) = if (g > 17.5) Level.HIGH else if (g > 3) Level.MEDIUM else Level.LOW
    fun satFatLevel(g: Double) = if (g > 5) Level.HIGH else if (g > 1.5) Level.MEDIUM else Level.LOW
    fun saltLevel(g: Double) = if (g > 1.5) Level.HIGH else if (g > 0.3) Level.MEDIUM else Level.LOW

    /** Warnings for the user: allergens they avoid, and nutrients that are high. */
    fun warnings(p: Product, avoid: Set<String>): List<String> = buildList {
        p.allergens.filter { it in avoid }.forEach { add("⚠️ Contiene $it") }
        if (sugarLevel(p.sugars100) == Level.HIGH) add("🍬 Molto zucchero (${fmt(p.sugars100)} g su 100 g)")
        if (saltLevel(p.salt100) == Level.HIGH) add("🧂 Molto sale (${fmt(p.salt100)} g su 100 g)")
        if (satFatLevel(p.satFat100) == Level.HIGH) add("🧈 Molti grassi saturi (${fmt(p.satFat100)} g su 100 g)")
        if (p.nova == 4) add("🏭 Molto lavorato (NOVA 4)")
    }

    fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.ITALIAN, "%.1f", v)

    // network (call off the main thread). Throws on a network error; returns null for an unknown product.
    fun fetch(barcode: String): Product? {
        val fields = "code,product_name,product_name_it,brands,quantity,nutriscore_grade,nova_group,nutriments,allergens_tags,additives_tags,ingredients_text_it,ingredients_text,serving_quantity"
        val c = java.net.URL("https://world.openfoodfacts.org/api/v2/product/${java.net.URLEncoder.encode(barcode, "UTF-8")}.json?fields=$fields")
            .openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "WebLauncher/1.0 (Android; app personale)")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return parse(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }, barcode)
        } finally { c.disconnect() }
    }
}

data class FoodEntry(val id: String, val time: Long, val meal: String, val name: String, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val grams: Double = 0.0)

/** Food diary, water, and the personal profile used for targets. */
object Food {
    val MEALS = listOf("colazione" to "🥐 Colazione", "pranzo" to "🍝 Pranzo", "cena" to "🍽 Cena", "spuntino" to "🍎 Spuntino")
    data class Totals(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)

    private fun prefs(c: Context) = c.getSharedPreferences("wl_food", Context.MODE_PRIVATE)
    fun load(c: Context): MutableList<FoodEntry> = runCatching {
        Gson().fromJson<MutableList<FoodEntry>>(prefs(c).getString("entries", null), object : TypeToken<MutableList<FoodEntry>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun save(c: Context, l: List<FoodEntry>) {
        val cutoff = System.currentTimeMillis() - 400L * 86_400_000L
        prefs(c).edit().putString("entries", Gson().toJson(l.filter { it.time >= cutoff })).apply()
    }
    fun add(c: Context, e: FoodEntry) = save(c, load(c) + e)
    fun remove(c: Context, id: String) = save(c, load(c).filter { it.id != id })

    fun dayKey(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
    fun day(entries: List<FoodEntry>, key: String) = entries.filter { dayKey(it.time) == key }.sortedBy { it.time }
    fun totals(entries: List<FoodEntry>) = Totals(entries.sumOf { it.kcal }, entries.sumOf { it.protein }, entries.sumOf { it.carbs }, entries.sumOf { it.fat })

    /** Breakfast until 10:30, lunch until 15:30, snack until 18:30, then dinner. */
    fun mealFor(hour: Int, minute: Int = 0): String { val t = hour * 60 + minute; return when { t < 630 -> "colazione"; t < 930 -> "pranzo"; t < 1110 -> "spuntino"; else -> "cena" } }

    fun fromProduct(p: OpenFoodFacts.Product, grams: Double, meal: String, time: Long = System.currentTimeMillis()): FoodEntry {
        val k = grams / 100.0
        return FoodEntry(UUID.randomUUID().toString(), time, meal, listOf(p.brand, p.name).filter { it.isNotBlank() }.joinToString(" · "),
            p.kcal100 * k, p.protein100 * k, p.carbs100 * k, p.fat100 * k, grams)
    }

    /** Distinct recent foods, newest first (to add them again with one tap). */
    fun recents(entries: List<FoodEntry>, n: Int = 8) = entries.sortedByDescending { it.time }.distinctBy { it.name.lowercase(Locale.ITALIAN) }.take(n)

    data class Estimate(val name: String, val grams: Double, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val note: String)

    /** The AI's answer about a photographed meal: {"piatto":"…","porzione_g":300,"kcal":550,"proteine_g":25,"carboidrati_g":60,"grassi_g":20,"note":"…"}. */
    fun parseAi(text: String): Estimate? = runCatching {
        val o = com.google.gson.JsonParser.parseString(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).asJsonObject
        fun num(vararg keys: String): Double = keys.firstNotNullOfOrNull { k -> o.get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString.replace(',', '.').filter { ch -> ch.isDigit() || ch == '.' }.toDouble() }.getOrNull() } } ?: 0.0
        fun str(vararg keys: String): String = keys.firstNotNullOfOrNull { k -> o.get(k)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()
        val kcal = num("kcal", "calorie")
        if (kcal <= 0 || kcal > 5000) return null
        Estimate(str("piatto", "nome", "name").ifBlank { "Pasto" }, num("porzione_g", "grammi", "g"), kcal, num("proteine_g", "proteine"), num("carboidrati_g", "carboidrati"), num("grassi_g", "grassi"), str("note"))
    }.getOrNull()

    // ----- water -----
    fun waterMl(c: Context, key: String = dayKey(System.currentTimeMillis())) = prefs(c).getInt("water_$key", 0)
    fun addWater(c: Context, ml: Int, key: String = dayKey(System.currentTimeMillis())) = prefs(c).edit().putInt("water_$key", (waterMl(c, key) + ml).coerceAtLeast(0)).apply()
    fun waterGoal(c: Context) = prefs(c).getInt("water_goal", 2000)
    fun setWaterGoal(c: Context, ml: Int) = prefs(c).edit().putInt("water_goal", ml).apply()
    fun glass(c: Context) = prefs(c).getInt("glass", 250)
    fun setGlass(c: Context, ml: Int) = prefs(c).edit().putInt("glass", ml).apply()
    fun waterReminders(c: Context) = prefs(c).getBoolean("water_rem", false)
    fun setWaterReminders(c: Context, on: Boolean) = prefs(c).edit().putBoolean("water_rem", on).apply()
    fun waterReminderIds(c: Context): List<Int> = prefs(c).getString("water_rem_ids", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    fun setWaterReminderIds(c: Context, ids: List<Int>) = prefs(c).edit().putString("water_rem_ids", ids.joinToString(",")).apply()

    // ----- allergens to avoid, profile -----
    fun avoid(c: Context): Set<String> = prefs(c).getStringSet("avoid", emptySet()).orEmpty()
    fun setAvoid(c: Context, s: Set<String>) = prefs(c).edit().putStringSet("avoid", s).apply()

    data class Profile(val sex: String = "", val birthYear: Int = 0, val heightCm: Double = 0.0, val activity: Double = 1.375, val goal: String = "keep")
    fun profile(c: Context) = Profile(prefs(c).getString("sex", "") ?: "", prefs(c).getInt("birthYear", 0), prefs(c).getFloat("height", 0f).toDouble(),
        prefs(c).getFloat("activity", 1.375f).toDouble(), prefs(c).getString("goal", "keep") ?: "keep")
    fun saveProfile(c: Context, p: Profile) = prefs(c).edit().putString("sex", p.sex).putInt("birthYear", p.birthYear).putFloat("height", p.heightCm.toFloat())
        .putFloat("activity", p.activity.toFloat()).putString("goal", p.goal).apply()
    fun profileComplete(p: Profile) = p.sex in setOf("m", "f") && p.birthYear in 1900..2100 && p.heightCm in 100.0..250.0

    /** Last recorded weight (from the health diary). */
    fun lastWeight(c: Context): Double = Health.load(c).filter { it.type == "peso" }.maxByOrNull { it.time }?.v1 ?: 0.0
    fun targets(c: Context): Nutrition.Targets? {
        val p = profile(c); val w = lastWeight(c)
        if (!profileComplete(p) || w <= 0) return null
        return Nutrition.targets(p.sex, w, p.heightCm, Nutrition.ageFrom(p.birthYear), p.activity, p.goal)
    }
}
