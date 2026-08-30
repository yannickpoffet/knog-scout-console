package com.knogscout.control

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
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
        private const val MAX_DISCOVERY = 3     // per connection
        private const val MAX_CYCLES = 15       // retries; ~50% land per try
    }

    private val ui = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var cpChar: BluetoothGattCharacteristic? = null
    private var alarmChar: BluetoothGattCharacteristic? = null
    private var discoveryTries = 0
    private var wantConnection = false
    private var useRefresh = true
    private var cycle = 0

    /** One point in the search space. Discovery on this device is intermittent
     *  and no single theory has held, so the app sweeps the combinations and
     *  reports which one actually worked. */
    private data class Combo(
        val auto: Boolean, val refresh: Boolean, val delay: Long, val prio: Int
    ) {
        fun label() = "auto=$auto refresh=$refresh delay=${delay}ms " +
            "prio=" + if (prio == BluetoothGatt.CONNECTION_PRIORITY_HIGH) "HIGH" else "BAL"
    }

    // The sweep showed the same combination both succeeding and failing, so the
    // parameters do not decide it. What decides it is whether Android serves its
    // cache (<1s, 8 services) or does a real discovery (~5s, 0 services) - and
    // the Scout refuses real discoveries. So: never refresh (that discards the
    // good cache), and just retry until a cache-backed discovery lands.
    private val combos: List<Combo> = listOf(
        Combo(auto = false, refresh = false, delay = 1600L,
              prio = BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
    )

    private fun currentCombo(): Combo = combos[(cycle - 1).coerceAtLeast(0) % combos.size]

    private val queue = ArrayDeque<Triple<String, () -> Boolean, Boolean>>()
    private var busy = false
    private var currentOp = ""

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

        val copyBtn = Button(this).apply {
            text = "Copy log"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Knog Scout log", logView.text))
                Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }

        val refreshBtn = Button(this).apply {
            text = "allow refresh() : YES"
            setOnClickListener {
                useRefresh = !useRefresh
                text = "allow refresh() : " + if (useRefresh) "YES" else "NO"
            }
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
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(copyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tools)
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
            log("Tap Connect - it retries until discovery lands (about half do).")
        }
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "BONDED"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        else -> "NOT BONDED"
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
        cycle = 1
        wantConnection = true
        ui.post { connectBtn.text = "Stop" }
        beginCycle()
    }

    /** One full connect + discover attempt. Discovery is intermittent on this
     *  device, so a fresh connection is retried until one of them works. */
    private fun beginCycle() {
        val d = device ?: run { log("no paired Scout"); return }
        discoveryTries = 0
        val c = currentCombo()
        log("--- attempt $cycle/$MAX_CYCLES : ${c.label()} ---")
        setState("TRYING", "attempt $cycle of $MAX_CYCLES", "#E0A63C")
        gatt = d.connectGatt(this, c.auto, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Discovery failed on this link: tear it down and try a brand new one. */
    private fun nextCycle() {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null
        cpChar = null; alarmChar = null
        ui.removeCallbacks(opWatchdog)
        queue.clear(); busy = false
        if (!wantConnection) return
        if (cycle >= MAX_CYCLES) {
            log("gave up after $cycle attempts")
            setState("FAILED", "tap Connect to try again", "#F0574A")
            wantConnection = false
            ui.post { connectBtn.text = "Connect" }
            return
        }
        cycle++
        ui.postDelayed({ if (wantConnection) beginCycle() }, 4000)
    }

    private fun stop() {
        wantConnection = false
        cycle = 0
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
                enqueue("keep-alive", quiet = true) { g.readCharacteristic(c) }
                ui.postDelayed(this, KEEPALIVE_MS)
            }
        }
    }

    // ---------- serialized GATT queue (Android runs one op at a time) ----------

    private fun enqueue(name: String, quiet: Boolean = false, op: () -> Boolean) {
        queue.add(Triple(name, op, quiet))
        if (!busy) drain()
    }

    private val opWatchdog = Runnable {
        log("!! op '$currentOp' never completed - skipping")
        busy = false
        ui.post { drain() }
    }

    private fun drain() {
        val item = queue.poll()
        if (item == null) { busy = false; return }
        busy = true
        currentOp = item.first
        val started = try { item.second() } catch (e: Exception) {
            log("op '${item.first}' threw: ${e.message}"); false
        }
        if (!started) {
            // Returning false means the stack never accepted the request, so no
            // callback is coming. Without this the queue stalls forever, the
            // keep-alive stops, and the Scout hangs up on an idle link.
            log("op '${item.first}' was rejected by the stack")
            busy = false
            ui.post { drain() }
            return
        }
        if (!item.third) log("op '${item.first}' sent")
        ui.postDelayed(opWatchdog, 3000)
    }

    private fun opDone() {
        ui.removeCallbacks(opWatchdog)
        busy = false
        ui.post { drain() }
    }

    // ---------- callbacks ----------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("connected (status $status)")
                setState("LINKED", "clearing cache…", "#4FBE7C")
                val c = currentCombo()
                g.requestConnectionPriority(c.prio)
                log("bond=${bondName(g.device.bondState)}")
                if (c.refresh && useRefresh) refresh(g)
                discoveryTries = 1
                ui.postDelayed({
                    log("discovering services…")
                    g.discoverServices()
                }, c.delay)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("link dropped (status $status)")
                val wasReady = alarmChar != null
                queue.clear(); busy = false
                ui.removeCallbacks(opWatchdog)
                cpChar = null; alarmChar = null
                ui.removeCallbacks(keepAlive)
                ui.post { armBtn.isEnabled = false; disarmBtn.isEnabled = false }
                if (wantConnection) {
                    setState("TRYING", "reconnecting", "#E0A63C")
                    // With autoConnect=false nothing retries on our behalf, so
                    // drive a fresh cycle ourselves. A reconnect after a real
                    // disconnect is the sequence that works by hand.
                    if (wasReady) { cycle = 1 }
                    nextCycle()
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
                    // Deliberately NOT refreshing here: repeating it changed
                    // nothing across four passes, so retry plainly instead.
                    log("empty table - retrying discovery ($discoveryTries), no refresh")
                    ui.postDelayed({ g.discoverServices() }, 1200)
                } else {
                    log("empty after $MAX_DISCOVERY tries - starting a fresh connection")
                    nextCycle()
                }
                return
            }

            val knog = g.getService(KNOG_SVC)
            if (knog == null) {
                log("Knog service missing from the table - retrying")
                nextCycle()
                return
            }
            cpChar = knog.getCharacteristic(CP_CHR)
            alarmChar = knog.getCharacteristic(ALARM_CHR)
            log("control point + alarm characteristic found")

            alarmChar?.let { ac ->
                enqueue("subscribe alarm") {
                    g.setCharacteristicNotification(ac, true)
                    val d = ac.getDescriptor(CCCD)
                    if (d == null) false else {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    }
                }
                enqueue("read alarm") { g.readCharacteristic(ac) }
            }
            g.getService(BATT_SVC)?.getCharacteristic(BATT_CHR)?.let { bc ->
                enqueue("subscribe battery") {
                    g.setCharacteristicNotification(bc, true)
                    val d = bc.getDescriptor(CCCD)
                    if (d == null) false else {
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    }
                }
                enqueue("read battery") { g.readCharacteristic(bc) }
            }

            ui.post {
                armBtn.isEnabled = true
                disarmBtn.isEnabled = true
            }
            ui.removeCallbacks(keepAlive)
            ui.postDelayed(keepAlive, KEEPALIVE_MS)
            ui.post { connectBtn.text = "Disconnect" }
            log("=========================================")
            log("READY on attempt $cycle - arm/disarm enabled")
            log("=========================================")
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
        enqueue("write $what") {
            c.value = byteArrayOf(op)
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }
    }
}
