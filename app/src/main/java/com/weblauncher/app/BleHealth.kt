package com.weblauncher.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import kotlin.math.pow

/** A reading from a standard Bluetooth health device, already in the units of the health diary. */
data class BleReading(val type: String, val v1: Double, val v2: Double? = null, val pulse: Int = 0)

/** Parsers for the standard Bluetooth SIG health profiles (pure functions, unit-tested). */
object BleParse {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** IEEE-11073 16-bit SFLOAT: 12-bit mantissa, 4-bit exponent. Null for the special "no value" codes. */
    fun sfloat(b: ByteArray, i: Int): Double? {
        val raw = u16(b, i)
        if (raw in listOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801)) return null
        var m = raw and 0x0FFF; if (m >= 0x0800) m -= 0x1000
        var e = raw shr 12; if (e >= 0x8) e -= 0x10
        return m * 10.0.pow(e)
    }
    /** IEEE-11073 32-bit FLOAT: 24-bit mantissa, 8-bit exponent. */
    fun float32(b: ByteArray, i: Int): Double? {
        var m = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
        if (m in listOf(0x7FFFFF, 0x800000, 0x7FFFFE, 0x800002, 0x800001)) return null
        if (m >= 0x800000) m -= 0x1000000
        val e = b[i + 3].toInt()
        return m * 10.0.pow(e)
    }

    /** Blood Pressure Measurement (0x2A35). kPa is converted to mmHg. */
    fun bloodPressure(b: ByteArray): BleReading? {
        if (b.size < 7) return null
        val flags = u8(b, 0)
        val k = if (flags and 1 != 0) 7.50062 else 1.0
        val sys = sfloat(b, 1) ?: return null; val dia = sfloat(b, 3) ?: return null
        var i = 7
        if (flags and 2 != 0) i += 7
        val pulse = if (flags and 4 != 0 && b.size >= i + 2) sfloat(b, i)?.toInt() ?: 0 else 0
        return BleReading("pressione", Math.round(sys * k).toDouble(), Math.round(dia * k).toDouble(), pulse)
    }

    /** Weight Measurement (0x2A9D): kg with 0.005 resolution, or pounds with 0.01. */
    fun weight(b: ByteArray): BleReading? {
        if (b.size < 3) return null
        val raw = u16(b, 1)
        if (raw == 0xFFFF || raw == 0) return null
        val kg = if (u8(b, 0) and 1 == 0) raw * 0.005 else raw * 0.01 * 0.45359237
        return BleReading("peso", Math.round(kg * 10) / 10.0)
    }

    /** Temperature Measurement (0x2A1C); Fahrenheit is converted to Celsius. */
    fun temperature(b: ByteArray): BleReading? {
        if (b.size < 5) return null
        var t = float32(b, 1) ?: return null
        if (u8(b, 0) and 1 != 0) t = (t - 32) * 5 / 9
        if (t !in 25.0..45.0) return null
        return BleReading("temperatura", Math.round(t * 10) / 10.0)
    }

    /** PLX Spot-check (0x2A5E) and Continuous (0x2A5F) both start with flags, SpO2, pulse rate. */
    fun oximeter(b: ByteArray): BleReading? {
        if (b.size < 5) return null
        val spo2 = sfloat(b, 1) ?: return null
        if (spo2 !in 50.0..100.0) return null
        val pr = sfloat(b, 3)?.toInt() ?: 0
        return BleReading("saturazione", Math.round(spo2).toDouble(), null, pr)
    }

    /** Glucose Measurement (0x2A18): kg/L or mol/L, converted to mg/dL. */
    fun glucose(b: ByteArray): BleReading? {
        val flags = if (b.isNotEmpty()) u8(b, 0) else return null
        var i = 1 + 2 + 7
        if (flags and 1 != 0) i += 2
        if (flags and 2 == 0 || b.size < i + 2) return null
        val v = sfloat(b, i) ?: return null
        val mg = if (flags and 4 == 0) v * 100_000 else v * 1000 * 18.016
        if (mg !in 10.0..1000.0) return null
        return BleReading("glicemia", Math.round(mg).toDouble())
    }
}

/** Standard Bluetooth health devices (blood pressure monitor, scale, thermometer, pulse oximeter, glucometer). */
@SuppressLint("MissingPermission")
object BleHealth {
    private fun uuid(short: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))
    private val CCC = uuid(0x2902)
    private val RACP = uuid(0x2A52)

    data class Kind(val id: String, val label: String, val emoji: String, val service: UUID, val chars: List<UUID>, val how: String, val parse: (ByteArray) -> BleReading?)

    val KINDS = listOf(
        Kind("pressione", "Misuratore di pressione", "❤️", uuid(0x1810), listOf(uuid(0x2A35)),
            "Accendi il misuratore e fai una misurazione: alla fine invia il risultato.", BleParse::bloodPressure),
        Kind("peso", "Bilancia", "⚖️", uuid(0x181D), listOf(uuid(0x2A9D)),
            "Sali sulla bilancia: quando il peso si ferma viene inviato.", BleParse::weight),
        Kind("temperatura", "Termometro", "🌡", uuid(0x1809), listOf(uuid(0x2A1C)),
            "Accendi il termometro e misura la temperatura.", BleParse::temperature),
        Kind("saturazione", "Saturimetro", "🫁", uuid(0x1822), listOf(uuid(0x2A5E), uuid(0x2A5F)),
            "Metti il dito nel saturimetro e aspetta qualche secondo.", BleParse::oximeter),
        Kind("glicemia", "Glucometro", "🩸", uuid(0x1808), listOf(uuid(0x2A18)),
            "Accendi il glucometro: leggo le misurazioni salvate.", BleParse::glucose)
    )
    fun kind(id: String) = KINDS.firstOrNull { it.id == id }

    data class Dev(val name: String, val address: String, val rssi: Int)

    // ----- saved devices (one per kind) -----
    private fun prefs(c: Context) = c.getSharedPreferences("wl_ble_health", Context.MODE_PRIVATE)
    fun saved(c: Context, kind: String): Pair<String, String>? {
        val a = prefs(c).getString("addr_$kind", null) ?: return null
        return a to (prefs(c).getString("name_$kind", null) ?: "Dispositivo")
    }
    fun save(c: Context, kind: String, d: Dev) { prefs(c).edit().putString("addr_$kind", d.address).putString("name_$kind", d.name).apply() }
    fun forget(c: Context, kind: String) { prefs(c).edit().remove("addr_$kind").remove("name_$kind").apply() }
    fun savedSummary(c: Context): String? = KINDS.mapNotNull { k -> saved(c, k.id)?.let { k.label } }.takeIf { it.isNotEmpty() }?.let { "Collegati: " + it.joinToString() }

    /** Saves a reading in the health diary (and the pulse, if the device sent it); returns the text shown to the user. */
    fun store(c: Context, r: BleReading, deviceName: String): String {
        val list = Health.load(c)
        val now = System.currentTimeMillis()
        // a device often resends the same value: skip an identical one from the last 2 minutes
        if (list.none { it.type == r.type && it.v1 == r.v1 && it.v2 == r.v2 && now - it.time < 120_000 }) {
            list += Measure("ble$now", r.type, r.v1, r.v2, now, deviceName)
            if (r.pulse in 30..230) list += Measure("ble${now}p", "battito", r.pulse.toDouble(), null, now, deviceName)
            Health.save(c, list)
        }
        val m = Measure("", r.type, r.v1, r.v2, now)
        return Health.fmt(m) + if (r.pulse in 30..230) " · battito ${r.pulse} bpm" else ""
    }

    private val h = Handler(Looper.getMainLooper())

    fun scan(c: Context, kind: Kind, seconds: Int = 10, done: (List<Dev>) -> Unit, failed: (String) -> Unit) {
        val ad = HrBand.adapter(c)
        if (ad == null || !ad.isEnabled) { failed("Il Bluetooth è spento: accendilo e riprova."); return }
        val sc = ad.bluetoothLeScanner ?: run { failed("Scansione Bluetooth non disponibile."); return }
        val found = linkedMapOf<String, Dev>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                found[r.device.address] = Dev(runCatching { r.device.name }.getOrNull() ?: r.scanRecord?.deviceName ?: kind.label, r.device.address, r.rssi)
            }
            override fun onScanFailed(errorCode: Int) { failed("Scansione non riuscita (codice $errorCode).") }
        }
        runCatching {
            sc.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(kind.service)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }.onFailure { failed("Scansione non riuscita: ${it.message}"); return }
        h.postDelayed({ runCatching { sc.stopScan(cb) }; done(found.values.sortedByDescending { it.rssi }) }, seconds * 1000L)
    }

    /** A running "wait for a measurement" session; readings arrive on the main thread. */
    class Session(val gatt: BluetoothGatt?) { fun close() { runCatching { gatt?.disconnect(); gatt?.close() } } }

    /** Connects to [address] and listens; [onState] reports progress, [onReading] each new measurement. */
    fun listen(c: Context, kind: Kind, address: String, onState: (String) -> Unit, onReading: (BleReading) -> Unit): Session {
        val ad = HrBand.adapter(c) ?: run { onState("Bluetooth non disponibile"); return Session(null) }
        val queue = ArrayDeque<(BluetoothGatt) -> Unit>()
        fun next(g: BluetoothGatt) { queue.removeFirstOrNull()?.invoke(g) }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) { h.post { onState("Collegato, aspetto la misurazione…") }; g.discoverServices() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) h.post { onState("Il dispositivo si è scollegato (molti si spengono dopo aver inviato la misura).") }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(kind.service)
                val chars = kind.chars.mapNotNull { svc?.getCharacteristic(it) }
                if (chars.isEmpty()) { h.post { onState("Questo dispositivo non usa il profilo standard: non riesco a leggerlo.") }; return }
                chars.forEach { ch ->
                    queue += { gg ->
                        val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                        gg.setCharacteristicNotification(ch, true)
                        ch.getDescriptor(CCC)?.let { d ->
                            @Suppress("DEPRECATION") run {
                                d.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gg.writeDescriptor(d)
                            }
                        } ?: next(gg)
                    }
                }
                // glucometers keep the readings in memory: ask for all of them (Record Access Control Point)
                svc?.getCharacteristic(RACP)?.let { racp ->
                    queue += { gg ->
                        gg.setCharacteristicNotification(racp, true)
                        racp.getDescriptor(CCC)?.let { d -> @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE; gg.writeDescriptor(d) } } ?: next(gg)
                    }
                    queue += { gg -> @Suppress("DEPRECATION") run { racp.value = byteArrayOf(0x01, 0x01); gg.writeCharacteristic(racp) } }
                }
                next(g)
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { next(g) }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { next(g) }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                if (ch.uuid !in kind.chars) return
                val r = kind.parse(ch.value ?: return) ?: return
                h.post { onReading(r) }
            }
        }
        onState("Mi collego…")
        val g = runCatching { ad.getRemoteDevice(address).connectGatt(c.applicationContext, true, cb, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { onState("Non riesco a collegarmi: ${it.message}") }.getOrNull()
        return Session(g)
    }
}
