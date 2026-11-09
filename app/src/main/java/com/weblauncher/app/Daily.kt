package com.weblauncher.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ======================= Morning brief =======================

object Brief {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_brief", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun hour(c: Context) = prefs(c).getInt("hour", 7)
    fun minute(c: Context) = prefs(c).getInt("minute", 30)
    fun lastText(c: Context) = prefs(c).getString("text", null)

    fun set(c: Context, on: Boolean, h: Int = hour(c), m: Int = minute(c)) {
        prefs(c).edit().putBoolean("enabled", on).putInt("hour", h).putInt("minute", m).apply()
        schedule(c)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 7700, Intent(c, BriefReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c))
        if (!enabled(c)) return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(c)); set(Calendar.MINUTE, minute(c)); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
    }

    private fun weatherText(c: Context): String? = runCatching {
        val w = c.getSharedPreferences("wl_weather", Context.MODE_PRIVATE)
        val lat = w.getFloat("lat", Float.NaN); val lon = w.getFloat("lon", Float.NaN)
        if (lat.isNaN()) return null
        val json = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1")
            .openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val o = org.json.JSONObject(json)
        val code = o.getJSONObject("current").optInt("weather_code")
        val label = when (code) { 0 -> "sereno"; 1, 2 -> "poco nuvoloso"; 3 -> "coperto"; 45, 48 -> "nebbia"; in 51..67, in 80..82 -> "pioggia"; in 71..77, 85, 86 -> "neve"; in 95..99 -> "temporali"; else -> "variabile" }
        val d = o.getJSONObject("daily")
        val max = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)); val min = Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))
        val rain = d.getJSONArray("precipitation_probability_max").optInt(0)
        "${w.getString("city", "")}: $label, $min°/$max°" + if (rain >= 40) ", pioggia al $rain%: porta l'ombrello ☂️" else ""
    }.getOrNull()

    fun compose(c: Context): String {
        val lines = mutableListOf<String>()
        val day = java.text.SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(java.util.Date()).replaceFirstChar { it.uppercase() }
        lines += "Buongiorno! Oggi è $day."
        weatherText(c)?.let { lines += "🌦 $it" }
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val end = start + 86_400_000L
        val tf = java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN)
        if (androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) runCatching {
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
            c.contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.ALL_DAY), null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                while (cur.moveToNext()) lines += "📅 " + (if (cur.getInt(2) == 1) "" else tf.format(java.util.Date(cur.getLong(1))) + " ") + (cur.getString(0) ?: "")
            }
        }
        Reminders.load(c).filter { !it.done && it.at in System.currentTimeMillis()..end }.sortedBy { it.at }
            .forEach { lines += "⏰ ${tf.format(java.util.Date(it.at))} ${it.text}" }
        val now = System.currentTimeMillis()
        Deadlines.load(c).filter { (it.kind == Deadlines.DEADLINE && it.date - now in 0..7 * 86_400_000L) || (it.kind == Deadlines.SUBSCRIPTION && it.date - now in 0..3 * 86_400_000L) }
            .forEach { lines += "⚠️ ${it.title} " + (if (it.kind == Deadlines.SUBSCRIPTION) "si rinnova" else "scade") + " il " + java.text.SimpleDateFormat("d MMMM", Locale.ITALIAN).format(java.util.Date(it.date)) }
        val news = runCatching { NewsRepository(c).cached().first }.getOrNull().orEmpty()
            .sortedWith(compareByDescending<NewsItem> { (it.also ?: emptyList()).size }.thenByDescending { it.time }).take(3)
        if (news.isNotEmpty()) { lines += "📰 Notizie:"; news.forEach { lines += "• ${it.title}" } }
        Steps.yesterday(c).takeIf { it > 0 }?.let { lines += "👟 Ieri hai fatto ${String.format(Locale.ITALIAN, "%,d", it)} passi." }
        return lines.joinToString("\n")
    }

    fun notify(c: Context, text: String) {
        prefs(c).edit().putString("text", text).putLong("time", System.currentTimeMillis()).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("buongiorno", "Buongiorno", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 7701, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET, "brief")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, "buongiorno")
            .setSmallIcon(R.drawable.ic_nav_home).setContentTitle("☀️ Buongiorno")
            .setContentText(text.lines().drop(1).firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.lines().drop(1).joinToString("\n")))
            .setContentIntent(open).addAction(0, "🔊 Ascolta", open).setAutoCancel(true).build()
        runCatching { nm.notify(7701, n) }
    }
}

class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        Thread {
            runCatching { Brief.notify(context, Brief.compose(context)) }
            Brief.schedule(context)
            pr.finish()
        }.start()
    }
}

// ======================= Steps (phone step counter, no account) =======================

object Steps {
    private fun prefs(c: Context) = c.getSharedPreferences("wl_steps", Context.MODE_PRIVATE)
    private fun key(t: Long = System.currentTimeMillis()) = "d_" + java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(java.util.Date(t))

    fun available(c: Context) = c.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** value = steps since boot reported by the sensor. */
    fun record(c: Context, value: Float) {
        val p = prefs(c)
        val last = p.getFloat("last", -1f)
        val delta = when { last < 0f -> 0f; value >= last -> value - last; else -> value } // reboot: counter restarted
        val k = key()
        p.edit().putFloat("last", value).putInt(k, p.getInt(k, 0) + delta.toInt().coerceAtMost(60_000)).apply()
    }

    fun day(c: Context, daysAgo: Int): Int = prefs(c).getInt(key(System.currentTimeMillis() - daysAgo * 86_400_000L), 0)
    fun today(c: Context) = day(c, 0)
    fun yesterday(c: Context) = day(c, 1)
    fun goal(c: Context) = prefs(c).getInt("goal", 6000)
    fun setGoal(c: Context, g: Int) = prefs(c).edit().putInt("goal", g).apply()

    /** Reads the sensor once (blocking, up to 5 s). */
    fun sampleNow(c: Context) {
        val sm = c.getSystemService(SensorManager::class.java)
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val latch = CountDownLatch(1)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { record(c, e.values[0]); latch.countDown() }
            override fun onAccuracyChanged(sensor: Sensor?, a: Int) {}
        }
        val thread = android.os.HandlerThread("steps").apply { start() }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, android.os.Handler(thread.looper))
        latch.await(5, TimeUnit.SECONDS)
        sm.unregisterListener(l)
        thread.quitSafely()
    }

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("wl_steps", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StepsWorker>(1, TimeUnit.HOURS).build())
    }
}

class StepsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 29) runCatching { Steps.sampleNow(applicationContext) }
        return Result.success()
    }
}

// ======================= Health diary =======================

data class Measure(val id: String, val type: String, val v1: Double, val v2: Double?, val time: Long, val note: String = "")

object Health {
    /** type -> (label, unit, two values?) */
    val TYPES = linkedMapOf(
        "pressione" to Triple("❤️ Pressione", "mmHg", true),
        "peso" to Triple("⚖️ Peso", "kg", false),
        "glicemia" to Triple("🩸 Glicemia", "mg/dL", false),
        "battito" to Triple("💓 Battito", "bpm", false),
        "temperatura" to Triple("🌡 Temperatura", "°C", false),
        "saturazione" to Triple("🫁 Saturazione", "%", false)
    )
    private fun file(c: Context) = File(c.filesDir, "health.json")
    fun load(c: Context): MutableList<Measure> = runCatching {
        Gson().fromJson<MutableList<Measure>>(file(c).readText(), object : TypeToken<MutableList<Measure>>() {}.type)
    }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
    fun save(c: Context, list: List<Measure>) { runCatching { file(c).writeText(Gson().toJson(list)) } }
    fun fmt(m: Measure): String {
        val n = { v: Double -> if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.ITALIAN, "%.1f", v) }
        return n(m.v1) + (m.v2?.let { "/" + n(it) } ?: "") + " " + (TYPES[m.type]?.second ?: "")
    }
}

/** Minimal line chart for the health diary (one or two series). */
class LineChartView(context: Context) : android.view.View(context) {
    var series1: List<Double> = emptyList()
    var series2: List<Double>? = null
    var color1 = 0xFF5B8CFF.toInt()
    var color2 = 0xFFE53935.toInt()
    var gridColor = 0x33888888
    var textColor = 0xFF888888.toInt()
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val all = series1 + (series2 ?: emptyList())
        if (series1.size < 2) return
        val d = resources.displayMetrics.density
        val pad = 28 * d
        var min = all.minOrNull()!!; var max = all.maxOrNull()!!
        if (max - min < 1e-6) { min -= 1; max += 1 }
        val range = max - min
        min -= range * 0.1; max += range * 0.1
        val w = width - pad - 8 * d; val h = height - 16 * d
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = d; p.color = gridColor
        p.textSize = 10 * d
        for (i in 0..3) {
            val y = 8 * d + h * i / 3
            canvas.drawLine(pad, y, pad + w, y, p)
            val v = max - (max - min) * i / 3
            p.style = android.graphics.Paint.Style.FILL; p.color = textColor
            canvas.drawText(if (v >= 100) v.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", v), 0f, y + 4 * d, p)
            p.style = android.graphics.Paint.Style.STROKE; p.color = gridColor
        }
        fun draw(s: List<Double>, col: Int) {
            p.color = col; p.strokeWidth = 2.5f * d; p.style = android.graphics.Paint.Style.STROKE
            val path = android.graphics.Path()
            s.forEachIndexed { i, v ->
                val x = pad + w * i / (s.size - 1); val y = (8 * d + h * (1 - (v - min) / (max - min))).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, p)
            p.style = android.graphics.Paint.Style.FILL
            s.forEachIndexed { i, v ->
                canvas.drawCircle(pad + w * i / (s.size - 1), (8 * d + h * (1 - (v - min) / (max - min))).toFloat(), 3 * d, p)
            }
        }
        draw(series1, color1)
        series2?.takeIf { it.size >= 2 }?.let { draw(it, color2) }
    }
}
