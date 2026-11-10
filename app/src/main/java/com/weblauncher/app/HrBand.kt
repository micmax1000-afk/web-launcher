package com.weblauncher.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
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

/** Bluetooth heart-rate strap (standard "Heart Rate" profile). One shared connection for the whole app. */
@SuppressLint("MissingPermission")
object HrBand {
    private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASURE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    data class Dev(val name: String, val address: String, val rssi: Int)

    @Volatile var bpm = 0
    @Volatile var lastAt = 0L
    @Volatile var state = "off"          // off | connecting | connected
    @Volatile var name = ""
    @Volatile var error = ""
    @Volatile var recording = false
    private val samples = mutableListOf<Pair<Long, Int>>()
    private var gatt: BluetoothGatt? = null
    private var want = false
    private var ctx: Context? = null
    private val h = Handler(Looper.getMainLooper())

    /** Current reading, or 0 if it is older than 10 seconds (strap lost / not worn). */
    fun current(): Int = if (state == "connected" && System.currentTimeMillis() - lastAt < 10_000) bpm else 0
    fun isOn() = state != "off"

    fun startRecording() { synchronized(samples) { samples.clear() }; recording = true }
    fun stopRecording(): List<Pair<Long, Int>> { recording = false; return synchronized(samples) { samples.toList() } }
    fun snapshot(): List<Pair<Long, Int>> = synchronized(samples) { samples.toList() }

    // ----- saved device -----
    private fun prefs(c: Context) = c.getSharedPreferences("wl_fitness", Context.MODE_PRIVATE)
    fun savedAddress(c: Context) = prefs(c).getString("hr_addr", null)
    fun savedName(c: Context) = prefs(c).getString("hr_name", null)
    fun forget(c: Context) { disconnect(); prefs(c).edit().remove("hr_addr").remove("hr_name").apply() }

    fun adapter(c: Context): BluetoothAdapter? = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ----- scan -----
    private var scanCb: ScanCallback? = null
    /** Scans for [seconds] and reports the straps found (once, at the end). */
    fun scan(c: Context, seconds: Int = 8, done: (List<Dev>) -> Unit, failed: (String) -> Unit) {
        val ad = adapter(c)
        if (ad == null || !ad.isEnabled) { failed("Il Bluetooth è spento: accendilo e riprova."); return }
        val sc = ad.bluetoothLeScanner ?: run { failed("Scansione Bluetooth non disponibile."); return }
        val found = linkedMapOf<String, Dev>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                found[r.device.address] = Dev(runCatching { r.device.name }.getOrNull() ?: r.scanRecord?.deviceName ?: "Fascia cardio", r.device.address, r.rssi)
            }
            override fun onScanFailed(errorCode: Int) { failed("Scansione non riuscita (codice $errorCode).") }
        }
        scanCb = cb
        runCatching {
            sc.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }.onFailure { failed("Scansione non riuscita: ${it.message}"); return }
        h.postDelayed({ runCatching { sc.stopScan(cb) }; scanCb = null; done(found.values.sortedByDescending { it.rssi }) }, seconds * 1000L)
    }

    // ----- connect -----
    fun connect(c: Context, address: String, name: String) {
        val app = c.applicationContext; ctx = app
        val ad = adapter(app) ?: return
        prefs(app).edit().putString("hr_addr", address).putString("hr_name", name).apply()
        disconnect(); want = true; this.name = name; error = ""; state = "connecting"
        val dev: BluetoothDevice = runCatching { ad.getRemoteDevice(address) }.getOrNull() ?: run { state = "off"; error = "Indirizzo non valido"; return }
        gatt = dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }
    /** Reconnects to the saved strap if there is one and it is not connected already. */
    fun autoConnect(c: Context) {
        val a = savedAddress(c) ?: return
        if (state == "off") runCatching { connect(c, a, savedName(c) ?: "Fascia cardio") }.onFailure { state = "off"; error = "Permesso Bluetooth mancante" }
    }
    fun disconnect() {
        want = false; state = "off"; bpm = 0
        runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bpm = 0
                runCatching { g.close() }
                if (want) {
                    state = "connecting"
                    h.postDelayed({ if (want && state != "connected") ctx?.let { c -> savedAddress(c)?.let { a -> connectQuiet(c, a) } } }, 3000)
                } else state = "off"
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASURE)
            if (ch == null) { error = "Questo dispositivo non invia il battito."; disconnect(); return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCC)
            @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            state = "connected"
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != HR_MEASURE) return
            val v = Hr.parse(ch.value ?: return)
            if (v in 30..230) {
                bpm = v; lastAt = System.currentTimeMillis(); state = "connected"
                if (recording) synchronized(samples) { samples += lastAt to v; if (samples.size > 20_000) samples.removeAt(0) }
            }
        }
    }
    private fun connectQuiet(c: Context, address: String) {
        val ad = adapter(c) ?: return
        runCatching { gatt = ad.getRemoteDevice(address).connectGatt(c, false, callback, BluetoothDevice.TRANSPORT_LE) }
    }
}
