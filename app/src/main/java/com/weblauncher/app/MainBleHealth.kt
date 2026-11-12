package com.weblauncher.app

import android.view.Gravity
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Standard Bluetooth health devices: pair once, then each measurement goes into the health diary. */
internal fun MainActivity.showBleDevicesPage() {
    openPage("Misuratori Bluetooth") { _, body, _ ->
        fun render() {
            body.removeAllViews()
            hintLabel(body, "Funzionano i dispositivi che usano i profili Bluetooth standard (molti Omron, Beurer, Medisana, A&D, Sanitas, iHealth, Contour… ma non tutti). " +
                "Se un modello usa solo la sua app, qui non si vede: in quel caso controlla se quell'app scrive su Health Connect.")
            sectionLabel(body, "I TUOI DISPOSITIVI")
            BleHealth.KINDS.forEach { k ->
                val s = BleHealth.saved(this, k.id)
                listRow(body, "${k.emoji} ${k.label}", if (s != null) "${s.second} · tocca per misurare" else "Non collegato · tocca per cercarlo",
                    onClick = { if (s != null) measureWith(k, s.first, s.second) else pairBle(k) { render() } },
                    onLong = if (s != null) ({
                        MaterialAlertDialogBuilder(this).setTitle(k.label).setItems(arrayOf("Cerca un altro dispositivo", "Dimentica")) { _, w ->
                            if (w == 0) pairBle(k) { render() } else { BleHealth.forget(this, k.id); render() }
                        }.show(); Unit
                    }) else null)
            }
            sectionLabel(body, "DOVE FINISCONO LE MISURE")
            listRow(body, "📒 Diario della salute", "Pressione, peso, temperatura, saturazione e glicemia con i grafici", onClick = { healthType = "pressione"; showHealthPage() })
            hintLabel(body, "Tieni premuto su un dispositivo per cambiarlo o dimenticarlo. I valori sono quelli inviati dal dispositivo: per dubbi sulla salute chiedi al medico.")
        }
        render()
    }
}

private fun MainActivity.pairBle(k: BleHealth.Kind, done: () -> Unit): Unit = withBlePermission {
    toast("Cerco ${k.label.lowercase()}… (10 secondi). Accendilo ora.")
    BleHealth.scan(this, k, 10, { list ->
        if (isFinishing) return@scan
        if (list.isEmpty()) MaterialAlertDialogBuilder(this).setTitle("Non trovato")
            .setMessage("Controlla che:\n• il dispositivo sia acceso (molti si attivano solo durante la misura o premendo il tasto Bluetooth);\n• sia vicino al telefono;\n• il Bluetooth sia acceso.\n\nSe ancora non compare, il modello probabilmente non usa il profilo standard.")
            .setPositiveButton("Riprova") { _, _ -> pairBle(k, done) }.setNegativeButton("Chiudi", null).show()
        else MaterialAlertDialogBuilder(this).setTitle("Scegli il dispositivo").setItems(list.map { it.name + " (" + it.address.takeLast(5) + ")" }.toTypedArray()) { _, i ->
            BleHealth.save(this, k.id, list[i]); done(); measureWith(k, list[i].address, list[i].name)
        }.show()
    }, { msg -> toast(msg) })
}

private fun MainActivity.measureWith(k: BleHealth.Kind, address: String, name: String): Unit = withBlePermission {
    val d = resources.displayMetrics.density
    val state = TextView(this).apply { textSize = 15f; setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0) }
    val value = TextView(this).apply { textSize = 30f; gravity = Gravity.CENTER; setPadding(0, (20 * d).toInt(), 0, (20 * d).toInt()); text = "—" }
    val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; addView(state); addView(value) }
    var count = 0
    val session = BleHealth.listen(this, k, address, { s -> state.text = s }) { r ->
        val txt = BleHealth.store(this, r, name); count++
        value.text = txt; state.text = "✅ Salvato nel diario" + if (count > 1) " ($count misure)" else ""
        wellnessRefresh?.invoke()
    }
    MaterialAlertDialogBuilder(this).setTitle("${k.emoji} ${k.label}").setMessage(k.how).setView(box)
        .setPositiveButton("Fatto", null).setOnDismissListener { session.close() }.show()
}
