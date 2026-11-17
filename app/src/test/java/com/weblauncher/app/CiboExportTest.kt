package com.weblauncher.app

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CiboExportTest {
    @Test fun waterDaysOnlyFromWaterKeys() {
        val all = mapOf("water_2026-11-01" to 1500, "water_2026-11-02" to 2000, "water_goal" to 2000, "glass" to 250, "water_rem" to true, "entries" to "[]")
        assertEquals(mapOf("2026-11-01" to 1500, "2026-11-02" to 2000), CiboExport.waterDays(all))
    }

    @Test fun jsonHasTheFieldsTheNewAppReads() {
        val d = CiboExport.Data(1, listOf(FoodEntry("e", 1, "pranzo", "Riso", 200.0, 4.0, 40.0, 1.0)), mapOf("2026-11-01" to 1500), 2000, 250, listOf("Glutine"),
            "f", 1985, 168.0, 1.375, "keep", 62.0, listOf(Recipe("r", "Zuppa", "u", null, "2", "30 min", listOf("a"), listOf("b"), 5)))
        val json = Gson().toJson(d)
        for (k in listOf("\"version\"", "\"entries\"", "\"water\"", "\"waterGoal\"", "\"glass\"", "\"avoid\"", "\"sex\"", "\"birthYear\"", "\"heightCm\"", "\"activity\"", "\"goal\"", "\"weightKg\"", "\"recipes\"", "\"ingredients\"", "\"steps\"", "\"kcal\""))
            assertTrue("manca $k", json.contains(k))
    }
}
