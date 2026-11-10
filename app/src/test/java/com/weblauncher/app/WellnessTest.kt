package com.weblauncher.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WellnessTest {
    // ---------- calories ----------
    @Test fun fabbisogno() {
        assertEquals(1648.75, Nutrition.bmr("m", 70.0, 175.0, 30), 0.01)       // 700 + 1093,75 - 150 + 5
        assertEquals(1295.25, Nutrition.bmr("f", 60.0, 165.0, 35), 0.01)
        val t = Nutrition.targets("m", 70.0, 175.0, 30, 1.55, "keep")
        assertEquals(2556, t.kcal)                                                   // 1648,75 x 1,55
        val dim = Nutrition.targets("m", 70.0, 175.0, 30, 1.55, "lose")
        assertTrue(dim.kcal < t.kcal && dim.kcal > 2000)
        assertEquals(112, t.proteinG)                                              // 1,6 g per kg
        assertEquals(t.kcal.toDouble(), (t.proteinG * 4 + t.carbG * 4 + t.fatG * 9).toDouble(), 20.0)
        // mai sotto il minimo di sicurezza
        assertEquals(1200, Nutrition.targets("f", 45.0, 150.0, 70, 1.2, "lose").kcal)
    }

    @Test fun bmiEAcqua() {
        assertEquals(22.86, Nutrition.bmi(70.0, 175.0), 0.01)
        assertEquals("Normopeso", Nutrition.bmiLabel(22.9)); assertEquals("Sovrappeso", Nutrition.bmiLabel(27.0))
        assertEquals("Sottopeso", Nutrition.bmiLabel(17.0)); assertEquals("Obesità", Nutrition.bmiLabel(31.0))
        val (lo, hi) = Nutrition.healthyRange(175.0); assertEquals(56.7, lo, 0.1); assertEquals(76.3, hi, 0.1)
        assertEquals(2300, Nutrition.waterGoalMl(70.0)); assertEquals(1500, Nutrition.waterGoalMl(40.0)); assertEquals(3500, Nutrition.waterGoalMl(150.0))
    }

    // ---------- Open Food Facts ----------
    private val json = """{"code":"8076800195057","status":1,"product":{
        "product_name":"Spaghetti n.5","product_name_it":"Spaghetti n. 5","brands":"Barilla,Altro","quantity":"500 g","nutriscore_grade":"a","nova_group":1,
        "nutriments":{"energy-kcal_100g":359,"proteins_100g":13,"carbohydrates_100g":71.2,"sugars_100g":3.5,"fat_100g":2,"saturated-fat_100g":0.5,"fiber_100g":3,"salt_100g":"0.013"},
        "allergens_tags":["en:gluten"],"additives_tags":["en:e330","en:e300"],"ingredients_text_it":"Semola di grano duro","serving_quantity":80}}"""

    @Test fun prodotto() {
        val p = OpenFoodFacts.parse(json, "8076800195057")!!
        assertEquals("Spaghetti n. 5", p.name); assertEquals("Barilla", p.brand); assertEquals("a", p.nutriScore); assertEquals(1, p.nova)
        assertEquals(359.0, p.kcal100, 1e-9); assertEquals(13.0, p.protein100, 1e-9); assertEquals(0.013, p.salt100, 1e-9)   // numero scritto come testo
        assertEquals(listOf("Glutine"), p.allergens); assertEquals(listOf("E330", "E300"), p.additives); assertEquals(80.0, p.servingG, 1e-9)
    }

    @Test fun prodottoSconosciutoOMalformato() {
        assertNull(OpenFoodFacts.parse("""{"status":0,"status_verbose":"product not found"}""", "1"))
        assertNull(OpenFoodFacts.parse("non json", "1"))
        val minimo = OpenFoodFacts.parse("""{"status":1,"product":{"nutriments":{"energy_100g":1000}}}""", "123")!!
        assertEquals("Prodotto 123", minimo.name); assertEquals(239.0, minimo.kcal100, 1.0)                                // da kJ a kcal
    }

    @Test fun avvisiNutrizionali() {
        val p = OpenFoodFacts.parse("""{"status":1,"product":{"product_name":"Biscotti","nova_group":4,"nutriments":{"energy-kcal_100g":480,"sugars_100g":28,"salt_100g":1.8,"saturated-fat_100g":9},"allergens_tags":["en:gluten","en:milk","en:eggs"]}}""", "9")!!
        val w = OpenFoodFacts.warnings(p, setOf("Glutine", "Uova"))
        assertTrue(w.any { it.contains("Contiene Glutine") } && w.any { it.contains("Contiene Uova") })
        assertFalse(w.any { it.contains("Contiene Latte") })                       // non è tra quelli da evitare
        assertTrue(w.any { it.contains("zucchero") } && w.any { it.contains("sale") } && w.any { it.contains("saturi") } && w.any { it.contains("lavorato") })
        assertEquals(OpenFoodFacts.Level.LOW, OpenFoodFacts.saltLevel(0.1)); assertEquals(OpenFoodFacts.Level.MEDIUM, OpenFoodFacts.sugarLevel(10.0))
    }

    @Test fun diarioAlimentare() {
        val p = OpenFoodFacts.parse(json, "1")!!
        val e = Food.fromProduct(p, 80.0, "pranzo", 0L)
        assertEquals(287.2, e.kcal, 0.1); assertEquals(10.4, e.protein, 0.1); assertEquals("Barilla · Spaghetti n. 5", e.name)
        val t = Food.totals(listOf(e, e.copy(kcal = 100.0, protein = 0.0, carbs = 0.0, fat = 0.0)))
        assertEquals(387.2, t.kcal, 0.1)
        assertEquals("colazione", Food.mealFor(8)); assertEquals("pranzo", Food.mealFor(12, 30)); assertEquals("spuntino", Food.mealFor(17)); assertEquals("cena", Food.mealFor(20))
        val r = Food.recents(listOf(e.copy(time = 1), e.copy(id = "x", time = 5, name = "SPAGHETTI"), e.copy(id = "y", time = 3, name = "Pane")))
        assertEquals(listOf("Barilla · Spaghetti n. 5".lowercase().let { _ -> "Pane" }, "SPAGHETTI").sorted(), r.map { it.name }.filter { it == "Pane" || it == "SPAGHETTI" }.sorted())
    }

    // ---------- GPS and training ----------
    @Test fun tracciaGps() {
        val t = Track()
        assertTrue(t.add(45.0000, 7.0000, 0, 5f))
        assertFalse(t.add(45.0001, 7.0000, 1000, 80f))                              // segnale impreciso: scartato
        assertFalse(t.add(45.00001, 7.0, 2000, 5f))                                 // meno di 3 m: rumore
        assertTrue(t.add(45.0009, 7.0000, 60_000, 5f))                              // ~100 m in un minuto
        assertEquals(100.0, t.distanceM, 2.0); assertEquals(60_000L, t.movingMs)
        assertFalse(t.add(46.0, 7.0, 61_000, 5f))                                   // salto di 100 km in 1 s: impossibile
        assertEquals(6.0, t.avgKmh(), 0.3)
    }

    @Test fun calorieERitmo() {
        assertEquals("6:00", Fitness.pace(10.0)); assertEquals("5:30", Fitness.pace(10.909)); assertEquals("—", Fitness.pace(0.5))
        // 30 minuti di corsa a 10 km/h, 70 kg: MET 10,5 → 367 kcal
        assertEquals(367.5, Fitness.kcal("corsa", 10.0, 70.0, 30 * 60_000L), 1.0)
        assertTrue(Fitness.kcal("camminata", 5.0, 70.0, 3_600_000L) < Fitness.kcal("corsa", 10.0, 70.0, 3_600_000L))
        assertEquals("850 m", Fitness.dist(850.0)); assertEquals("5,25 km", Fitness.dist(5250.0)); assertEquals("1:05:03", Fitness.clock(3_903_000)); assertEquals("12:07", Fitness.clock(727_000))
    }

    @Test fun obiettivoESerie() {
        fun cal(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 10, 0) }.timeInMillis
        // lunedì 9 novembre 2026 è la settimana corrente; 2 allenamenti questa, 3 la scorsa, 3 quella prima, 1 quella prima ancora
        val times = listOf(cal(2026, 11, 9), cal(2026, 11, 10), cal(2026, 11, 2), cal(2026, 11, 4), cal(2026, 11, 6),
            cal(2026, 10, 26), cal(2026, 10, 28), cal(2026, 10, 30), cal(2026, 10, 19))
        val now = cal(2026, 11, 11)
        assertEquals(2, Fitness.weekCount(times, now))
        assertEquals(2, Fitness.streakWeeks(times, 3, now))                         // questa settimana non ancora, ma le due prima sì
        assertEquals(3, Fitness.streakWeeks(times + cal(2026, 11, 11), 3, now))     // oggi chiude anche la settimana corrente
        assertEquals(0, Fitness.streakWeeks(emptyList(), 3, now))
        assertEquals(listOf(cal(2026, 11, 9)), Fitness.dedupe(listOf(cal(2026, 11, 9), cal(2026, 11, 9) + 20 * 60_000L)))   // orologio e telefono: una sola volta
    }

    @Test fun schede() {
        assertEquals(5, Fitness.PRESETS[0].exercises.size)
        assertTrue(Fitness.planMinutes(Fitness.PRESETS[0]) in 8..20)
        assertTrue(Fitness.planMinutes(Plan("x", "vuota", emptyList())) >= 1)
    }

    // ---------- farmaci, emergenza, visite, report ----------
    @Test fun farmaci() {
        assertEquals(listOf("08:00", "13:00", "20:30"), Meds.parseTimes("8, 20:30 , 13.00, 25:00, boh"))
        val m = Med("1", "Cardioaspirina", "100 mg", listOf("08:00", "20:00"), 14, 1)
        assertEquals(2, Meds.perDay(m)); assertEquals(7, Meds.daysLeft(m)); assertFalse(Meds.isLow(m))
        assertTrue(Meds.isLow(m.copy(stock = 8))); assertTrue(Meds.isLow(m.copy(stock = 0)))
        val c = Calendar.getInstance().apply { clear(); set(2026, 10, 9, 9, 30) }
        assertEquals("20:00", Meds.nextDose(m, c)); assertEquals("domani 08:00", Meds.nextDose(m, Calendar.getInstance().apply { clear(); set(2026, 10, 9, 22, 0) }))
        val now = Calendar.getInstance().apply { clear(); set(2026, 10, 9, 9, 0) }.timeInMillis
        assertTrue(Meds.nextAt("08:00", now) > now && Meds.nextAt("10:00", now) - now == 3_600_000L)
    }

    @Test fun schedaEmergenza() {
        assertTrue(Emergency.isEmpty(EmergencyCard()))
        val l = Emergency.lines(EmergencyCard(name = "Mario Rossi", blood = "A+", allergies = "Penicillina", contact1 = "Anna", phone1 = "333 1234567"))
        assertEquals(listOf("Mario Rossi", "Gruppo sanguigno: A+", "⚠️ Allergie: Penicillina", "📞 Anna: 333 1234567"), l)
    }

    @Test fun visite() {
        val visita = Calendar.getInstance().apply { clear(); set(2026, 10, 12, 10, 0) }.timeInMillis
        val prima = Calendar.getInstance().apply { clear(); set(2026, 10, 9, 9, 0) }.timeInMillis
        val r = Visits.reminderTimes(visita, prima)
        assertEquals(2, r.size)
        assertEquals(Calendar.getInstance().apply { clear(); set(2026, 10, 11, 18, 0) }.timeInMillis, r[0])
        assertEquals(visita - 2 * 3_600_000L, r[1])
        assertEquals(1, Visits.reminderTimes(visita, Calendar.getInstance().apply { clear(); set(2026, 10, 12, 7, 0) }.timeInMillis).size)   // la sera prima è già passata
    }

    @Test fun reportPerIlMedico() {
        val d = Report.Data("Mario Rossi", 0L, 30L * 86_400_000L, listOf(0L to 80.0, 10L to 78.5), listOf(Triple(0L, 130, 85), Triple(5L, 120, 80)),
            listOf(0L to 95.0), 8200, 430, 62, 3, listOf("Cardioaspirina 100 mg"), "Penicillina", "")
        val s = Report.sections(d).toMap()
        assertTrue(s.getValue("Pressione arteriosa (mmHg)")[0].contains("media 125/82"))
        assertTrue(s.getValue("Peso (kg)")[0].contains("-1,5"))
        assertTrue(s.getValue("Glicemia (mg/dL)")[0].contains("media 95"))
        assertTrue(s.getValue("Attività e riposo").any { it.contains("7 h 10 min") } && s.getValue("Attività e riposo").any { it.contains("8.200") })
        assertTrue(s.getValue("Dati clinici").any { it.contains("Penicillina") })
        assertTrue(Report.sections(Report.Data("", 0, 1, emptyList(), emptyList(), emptyList(), 0, 0, 0, 0, emptyList(), "", "")).any { it.first == "Dati" })
    }

    // ---------- Health Connect: riepiloghi ----------
    @Test fun healthConnectRiepiloghi() {
        val days = listOf(Hc.Day("2026-11-07", steps = 6000, sleepMin = 400), Hc.Day("2026-11-08", steps = 0), Hc.Day("2026-11-09", steps = 10000, sleepMin = 480))
        assertEquals(8000.0, Hc.avg(days) { it.steps.toDouble() }, 1e-9)           // il giorno senza dati non abbassa la media
        assertEquals("7 h 20 min", Hc.duration(440)); assertEquals("45 min", Hc.duration(45)); assertEquals("—", Hc.duration(0))
        val s = Hc.Snapshot(0, days, emptyList())
        assertEquals("2026-11-09", Hc.lastNight(s)!!.date)
        assertEquals("Mi Fitness", Hc.sourceName("com.xiaomi.wearable")); assertEquals("com.altra.app", Hc.sourceName("com.altra.app"))
        assertEquals("Corsa", Hc.exerciseLabel(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
    }

    @Test fun fotoCiboDallAi() {
        val e = Food.parseAi("Ecco:\n```json\n{\"piatto\":\"Pasta al pomodoro\",\"porzione_g\":320,\"kcal\":\"560\",\"proteine_g\":18,\"carboidrati_g\":96,\"grassi_g\":12,\"note\":\"sembra pasta\"}\n```")!!
        assertEquals("Pasta al pomodoro", e.name); assertEquals(560.0, e.kcal, 0.01); assertEquals(96.0, e.carbs, 0.01)
        assertNull(Food.parseAi("{\"kcal\": 0}")); assertNull(Food.parseAi("non vedo nulla")); assertNull(Food.parseAi("{\"kcal\": 99999}"))
    }

    @Test fun eserciziScritti() {
        val l = Fitness.parseExercises("Squat 3x12 riposo 45\nPlank 3x30s riposo 30\nPanca 4x8 riposo 90 40kg\nnon valida\nCrunch 2 x 15")
        assertEquals(4, l.size)
        assertEquals(Ex("Squat", 3, 12, 45), l[0]); assertEquals(30, l[1].seconds); assertEquals(0, l[1].reps)
        assertEquals(40.0, l[2].kg, 0.01); assertEquals(45, l[3].restSec)
        assertEquals(l, Fitness.parseExercises(l.joinToString("\n") { Fitness.exerciseText(it) }))
    }
}
