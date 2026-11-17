package com.weblauncher.app

import android.content.Intent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

// ======================= Cibo e ricette → l'app «Cibo e ricette» =======================

const val CIBO_APP = "com.micmax.cibo"

/** Il formato del file che l'app «Cibo e ricette» sa leggere (i nomi dei campi sono gli stessi dei dati di questa app). */
internal object CiboExport {
    data class Data(
        val version: Int = 1,
        val entries: List<FoodEntry>, val water: Map<String, Int>, val waterGoal: Int, val glass: Int, val avoid: List<String>,
        val sex: String, val birthYear: Int, val heightCm: Double, val activity: Double, val goal: String, val weightKg: Double,
        val recipes: List<Recipe>,
        val measures: List<Measure> = emptyList(), val meds: List<Med> = emptyList(), val visits: List<Visit> = emptyList(), val emergency: EmergencyCard? = null,
        val plans: List<Plan> = emptyList(), val acts: List<Act> = emptyList(), val weeklyGoal: Int = 0
    )

    /** I giorni con acqua registrata, da prefs «water_AAAA-MM-GG». */
    fun waterDays(all: Map<String, *>): Map<String, Int> =
        all.filterKeys { it.startsWith("water_2") }.mapKeys { it.key.removePrefix("water_") }.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
}

internal fun MainActivity.exportCiboJson(): String {
    val p = Food.profile(this)
    val water = CiboExport.waterDays(getSharedPreferences("wl_food", android.content.Context.MODE_PRIVATE).all)
    return Gson().toJson(CiboExport.Data(1, Food.load(this), water, Food.waterGoal(this), Food.glass(this), Food.avoid(this).toList(),
        p.sex, p.birthYear, p.heightCm, p.activity, p.goal, Food.lastWeight(this), Recipes.load(this),
        Health.load(this), Meds.load(this), Visits.load(this), Emergency.load(this).takeIf { !Emergency.isEmpty(it) },
        Fitness.plans(this).takeIf { p -> p != Fitness.PRESETS }.orEmpty(), Fitness.acts(this), Fitness.weeklyGoal(this)))
}

/** Manda i dati di cibo, ricette e salute all'app «Benessere e cibo» (o li salva/condivide come file). */
internal fun MainActivity.sendFoodToCiboApp() {
    val entries = Food.load(this).size; val recipes = Recipes.load(this).size
    val installed = packageManager.getLaunchIntentForPackage(CIBO_APP) != null
    MaterialAlertDialogBuilder(this).setTitle("🍽 Benessere e cibo")
        .setMessage("Porto nella nuova app $entries cibi del diario, $recipes ricette, l'acqua, il profilo e la salute (misure, sonno, farmaci, visite, scheda d'emergenza) e gli allenamenti (schede e attività). Qui non si cancella niente. I promemoria dei farmaci e delle visite si rifanno nella nuova app: poi spegni quelli vecchi qui.\n\n" +
            if (installed) "Nella nuova app: ⚙️ → «Importa da Web Launcher» e scegli il file." else "La nuova app non è ancora installata: scaricala da github.com/micmax1000-afk/Fitness-/releases.")
        .setPositiveButton("Crea il file") { _, _ ->
            runCatching {
                val dir = File(cacheDir, "report").apply { mkdirs() }
                val f = File(dir, "cibo-e-ricette.json").apply { writeText(exportCiboJson()) }
                val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Salva il file (per esempio nei Download o su Drive)"))
            }.onFailure { toast("Non riesco a creare il file") }
        }
        .setNeutralButton(if (installed) "Apri l'app" else "Scarica l'app") { _, _ ->
            if (installed) packageManager.getLaunchIntentForPackage(CIBO_APP)?.let { startActivity(it) } else openInBrowser("https://github.com/micmax1000-afk/Fitness-/releases")
        }
        .setNegativeButton("Chiudi", null).show()
}
