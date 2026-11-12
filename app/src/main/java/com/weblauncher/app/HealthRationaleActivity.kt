package com.weblauncher.app

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shown by Health Connect («Perché questa app legge i miei dati?»). */
class HealthRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val p = AppRepository.palette(this)
        val t = TextView(this).apply {
            text = "Web Launcher legge da Health Connect passi, battito, sonno, peso, allenamenti, ossigeno nel sangue, pressione e glicemia " +
                "condivisi dalla tua app di fitness (Samsung Health, Fitbit, Garmin, Zepp, Withings… o l'app del tuo orologio).\n\n" +
                "Li usa solo per mostrarti il riepilogo della giornata e i grafici nella sezione Benessere.\n\n" +
                "I dati restano sul telefono: non vengono inviati a nessun server, non vengono scritti in Health Connect e puoi revocare " +
                "l'accesso quando vuoi dalle impostazioni di Health Connect."
            textSize = 17f; setTextColor(p.text); setLineSpacing(0f, 1.15f)
            setPadding((24 * d).toInt(), (32 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(t) })
        title = "Dati di salute"
    }
}
