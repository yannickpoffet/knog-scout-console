package com.knogscout.control

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Minimal Knog Scout controller.
 *
 * The three things a browser could not do, and why they are here:
 *  - refresh() clears Android's cached attribute table before every discovery,
 *    which is what stops the Scout answering Database Out Of Sync.
 *  - autoConnect = true uses the patient connection strategy.
 *  - discovery is retried on a held connection when it returns an empty table,
 *    instead of tearing the link down.
 */
class MainActivity : Activity() {

    companion object {
        private val KNOG_SVC: UUID = UUID.fromString("00000000-feed-0bac-5241-d8bda6932a2f")
        private val CP_CHR: UUID = UUID.fromString("00000001-feed-0bac-5241-d8bda6932a2f")
        private val ALARM_CHR: UUID = UUID.fromString("00000002-feed-0bac-5241-d8bda6932a2f")
        private val BATT_SVC: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATT_CHR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val ARM: Byte = 0x01
        private const val DISARM: Byte = 0x02
        private const val KEEPALIVE_MS = 4000L   // Scout drops a silent link at ~6s
        private const val MAX_DISCOVERY = 4
    }

    private val ui = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var cpChar: BluetoothGattCharacteristic? = null
    private var alarmChar: BluetoothGattCharacteristic? = null
    private var discoveryTries = 0
    private var wantConnection = false

    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false

    private lateinit var stateView: TextView
    private lateinit var subView: TextView
    private lateinit var battView: TextView
    private lateinit var logView: TextView
    private lateinit var connectBtn: Button
    private lateinit var armBtn: Button
    private lateinit var disarmBtn: Button

    // ---------- UI ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E1416"))
        }

        stateView = TextView(this).apply {
            textSize = 40f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#7C8A8C"))
            gravity = Gravity.CENTER
            text = "OFFLINE"
        }
        subView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#8B9B9D"))
            gravity = Gravity.CENTER
            text = "not connected"
        }
        battView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#E6EDEC"))
            gravity = Gravity.CENTER
            text = "Battery --%"
        }

        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { if (wantConnection) stop() else start() }
        }
        armBtn = Button(this).apply {
            text = "ARM"
            isEnabled = false
            setOnClickListener { writeCp(ARM, "arm") }
        }
        disarmBtn = Button(this).apply {
            text = "DISARM"
            isEnabled = false
            setOnClickListener { writeCp(DISARM, "disarm") }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(armBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(disarmBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8B9B9D"))
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(stateView)
        root.addView(subView)
        root.addView(battView)
        root.addView(connectBtn)
        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is off - enable it and reopen the app")
            return
        }
        device = findScout(adapter)
        if (device == null) {
            log("No paired Knog Scout found.")
            log("Pair it first: Settings > Bluetooth > Pair new device.")
        } else {
            log("found paired device: ${device!!.name} (${device!!.address})")
            log("tap Connect")
        }
    }

    private fun findScout(adapter: BluetoothAdapter): BluetoothDevice? =
        adapter.bondedDevices?.firstOrNull {
            (it.name ?: "").contains("Knog", ignoreCase = true) ||
            (it.name ?: "").contains("Scout", ignoreCase = true)
        }

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ui.post {
            logView.append("$t  $msg\n")
            (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun setState(word: String, sub: String, color: String) {
        ui.post {
            stateView.text = word
            stateView.setTextColor(Color.parseColor(color))
            subView.text = sub
        }
    }

    // ---------- connection ----------

    private fun start() {
        val d = device ?: run { log("no paired Scout"); return }
        wantConnection = true
        discoveryTries = 0
        ui.post { connectBtn.text = "Disconnect" }
        log("connecting (autoConnect = true)…")
        setState("LINKING", "connecting", "#E0A63C")
        gatt = d.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun stop() {
        wantConnection = false
        ui.removeCallbacks(keepAlive)
        queue.clear(); busy = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        cpChar = null; alarmChar = null
        ui.post {
            connectBtn.text = "Connect"
            armBtn.isEnabled = false; disarmBtn.isEnabled = false
        }
        setState("OFFLINE", "not connected", "#7C8A8C")
        log("disconnected on request")
    }

    /** The hidden call that clears Android's cached attribute table. */
    private fun refresh(g: BluetoothGatt): Boolean = try {
        val m = g.javaClass.getMethod("refresh")
        val ok = m.invoke(g) as? Boolean ?: false
        log(if (ok) "gatt.refresh() ok - cache cleared" else "gatt.refresh() returned false")
        ok
    } catch (e: Exception) {
        log("gatt.refresh() unavailable (${e.javaClass.simpleName}) - continuing")
        false
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            val c = alarmChar
            val g = gatt
            if (wantConnection && g != null && c != null) {
                enqueue { g.readCharacteristic(c) }
                ui.postDelayed(this, KEEPALIVE_MS)
            }
        }
    }

    // ---------- serialized GATT queue (Android runs one op at a time) ----------

    private fun enqueue(op: () -> Unit) {
        queue.add(op)
        if (!busy) drain()
    }

    private fun drain() {
        val op = queue.poll()
        if (op == null) { busy = false; return }
        busy = true
        try { op() } catch (e: Exception) { log("op failed: ${e.message}"); busy = false; drain() }
    }

    private fun opDone() { busy = false; ui.post { drain() } }

    // ---------- callbacks ----------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("connected (status $status)")
                setState("LINKED", "clearing cache…", "#4FBE7C")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                refresh(g)
                discoveryTries = 1
                ui.postDelayed({
                    log("discovering services (try $discoveryTries)…")
                    g.discoverServices()
                }, 600)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("link dropped (status $status)")
                queue.clear(); busy = false
                cpChar = null; alarmChar = null
                ui.removeCallbacks(keepAlive)
                ui.post { armBtn.isEnabled = false; disarmBtn.isEnabled = false }
                if (wantConnection) {
                    setState("RELINKING", "autoConnect will retry", "#E0A63C")
                } else {
                    setState("OFFLINE", "not connected", "#7C8A8C")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val services = g.services
            log("services discovered: status=$status count=${services.size}")

            if (status != BluetoothGatt.GATT_SUCCESS || services.isEmpty()) {
                if (discoveryTries < MAX_DISCOVERY) {
                    discoveryTries++
                    log("empty table - refreshing cache and retrying ($discoveryTries)")
                    refresh(g)
                    ui.postDelayed({ g.discoverServices() }, 800)
                } else {
                    log("discovery kept coming back empty after $MAX_DISCOVERY tries")
                    setState("NO SERVICES", "try toggling Bluetooth", "#F0574A")
                }
                return
            }

            val knog = g.getService(KNOG_SVC)
            if (knog == null) {
                log("Knog service missing from the table")
                setState("NO SERVICE", "unexpected table", "#F0574A")
                return
            }
            cpChar = knog.getCharacteristic(CP_CHR)
            alarmChar = knog.getCharacteristic(ALARM_CHR)
            log("control point + alarm characteristic found")

            alarmChar?.let { ac ->
                enqueue { g.setCharacteristicNotification(ac, true)
                    val d = ac.getDescriptor(CCCD)
                    if (d != null) {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    } else { opDone() }
                }
                enqueue { g.readCharacteristic(ac) }
            }
            g.getService(BATT_SVC)?.getCharacteristic(BATT_CHR)?.let { bc ->
                enqueue { g.setCharacteristicNotification(bc, true)
                    val d = bc.getDescriptor(CCCD)
                    if (d != null) {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    } else { opDone() }
                }
                enqueue { g.readCharacteristic(bc) }
            }

            ui.post {
                armBtn.isEnabled = true
                disarmBtn.isEnabled = true
            }
            ui.removeCallbacks(keepAlive)
            ui.postDelayed(keepAlive, KEEPALIVE_MS)
            log("ready - arm/disarm enabled")
        }

        @Deprecated("targetSdk 28 uses this signature")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            handleValue(c, c.value, status)
            opDone()
        }

        @Deprecated("targetSdk 28 uses this signature")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleValue(c, c.value, BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            log("write ${if (status == 0) "ok" else "failed ($status)"}")
            opDone()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            opDone()
        }
    }

    private fun handleValue(c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) return
        when (c.uuid) {
            ALARM_CHR -> {
                val v = (value.getOrElse(0) { 0 }.toInt() and 0xFF) or
                        ((value.getOrElse(1) { 0 }.toInt() and 0xFF) shl 8)
                val (word, sub, col) = when (v) {
                    0 -> Triple("OFF", "disarmed", "#7C8A8C")
                    1 -> Triple("ARMING", "turning on", "#E0A63C")
                    2 -> Triple("ARMED", "on watch", "#4FBE7C")
                    3 -> Triple("RINGING", "siren active", "#F0574A")
                    else -> Triple("STATE $v", "unrecognised", "#8B9B9D")
                }
                setState(word, sub, col)
            }
            BATT_CHR -> {
                val pct = value.getOrElse(0) { 0 }.toInt() and 0xFF
                ui.post { battView.text = "Battery $pct%" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(keepAlive)
        gatt?.close()
    }

    private fun writeCp(op: Byte, what: String) {
        val g = gatt; val c = cpChar
        if (g == null || c == null) { log("not ready"); return }
        log("$what -> control point 0x%02x".format(op))
        enqueue {
            c.value = byteArrayOf(op)
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }
    }
}
