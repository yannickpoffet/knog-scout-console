package com.knogscout.control

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        private const val KEEPALIVE_MS = 4000L
        private const val MAX_DISCOVERY = 3
        private const val MAX_CYCLES = 40

        // Palette lifted from the web console so the two feel like one tool.
        private const val BG = "#0E1416"
        private const val SURFACE = "#161E21"
        private const val SURFACE2 = "#1D2629"
        private const val LINE = "#2A3639"
        private const val TEXT = "#E6EDEC"
        private const val DIM = "#8B9B9D"
        private const val ACCENT = "#4FC4C4"
        private const val ON_ACCENT = "#06191B"
        private const val S_OFF = "#7C8A8C"
        private const val S_ARMING = "#E0A63C"
        private const val S_ON = "#4FBE7C"
        private const val S_RING = "#F0574A"
    }

    private val ui = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var cpChar: BluetoothGattCharacteristic? = null
    private var alarmChar: BluetoothGattCharacteristic? = null
    private var discoveryTries = 0
    private var wantConnection = false
    private var cycle = 0
    private var discoverStarted = 0L

    private val autoConnect = false
    private val discoveryDelay = 1600L
    private val priority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED

    private val queue = ArrayDeque<Triple<String, () -> Boolean, Boolean>>()
    private var busy = false
    private var currentOp = ""

    private lateinit var pill: TextView
    private lateinit var eyebrow: TextView
    private lateinit var stateWord: TextView
    private lateinit var stateSub: TextView
    private lateinit var stateCardBg: GradientDrawable
    private lateinit var battValue: TextView
    private lateinit var battFill: View
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectBtn: Button
    private lateinit var armBtn: Button
    private lateinit var disarmBtn: Button

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
    private fun dpi(v: Float) = dp(v).toInt()
    private fun col(hex: String) = Color.parseColor(hex)

    // ---------------------------------------------------------------- UI

    private fun cardBg(stroke: String = LINE, fill: String = SURFACE) =
        GradientDrawable().apply {
            setColor(col(fill))
            cornerRadius = dp(14f)
            setStroke(dpi(1f), col(stroke))
        }

    private fun styledButton(label: String, primary: Boolean): Button {
        val bg = GradientDrawable().apply {
            setColor(col(if (primary) ACCENT else SURFACE))
            cornerRadius = dp(12f)
            setStroke(dpi(1f), col(if (primary) ACCENT else LINE))
        }
        return Button(this).apply {
            text = label
            isAllCaps = true
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(col(if (primary) ON_ACCENT else TEXT))
            background = bg
            minHeight = dpi(56f)
            stateListAnimator = null
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        letterSpacing = 0.12f
        typeface = Typeface.MONOSPACE
        setTextColor(col(DIM))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(BG))
            setPadding(dpi(16f), dpi(20f), dpi(16f), dpi(16f))
        }

        // header ------------------------------------------------------
        val title = TextView(this).apply {
            text = "SCOUT ALARM"
            textSize = 22f
            letterSpacing = 0.04f
            setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
            setTextColor(col(TEXT))
        }
        pill = TextView(this).apply {
            text = "OFFLINE"
            textSize = 10f
            letterSpacing = 0.09f
            typeface = Typeface.MONOSPACE
            setTextColor(col(DIM))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(999f)
                setStroke(dpi(1f), col(LINE))
            }
            setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill)
        }

        // state hero --------------------------------------------------
        stateCardBg = cardBg()
        eyebrow = label("ALARM STATE").apply { gravity = Gravity.CENTER }
        stateWord = TextView(this).apply {
            text = "—"
            textSize = 44f
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
            setTextColor(col(S_OFF))
        }
        stateSub = TextView(this).apply {
            text = "not connected"
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(col(DIM))
        }
        val stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = stateCardBg
            setPadding(dpi(20f), dpi(22f), dpi(20f), dpi(20f))
            addView(eyebrow); addView(stateWord); addView(stateSub)
        }

        // actions -----------------------------------------------------
        connectBtn = styledButton("Connect", true).apply {
            setOnClickListener { onConnectPressed() }
        }
        armBtn = styledButton("Arm", false).apply {
            isEnabled = false
            setOnClickListener { writeCp(ARM, "arm") }
        }
        disarmBtn = styledButton("Disarm", false).apply {
            isEnabled = false
            setOnClickListener { writeCp(DISARM, "disarm") }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(armBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dpi(6f) })
            addView(disarmBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dpi(6f) })
        }

        // battery -----------------------------------------------------
        battValue = TextView(this).apply {
            text = "––%"
            textSize = 19f
            typeface = Typeface.MONOSPACE
            setTextColor(col(TEXT))
        }
        val battRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("BATTERY"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(battValue)
        }
        battFill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(col(S_ON)); cornerRadius = dp(3f)
            }
        }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(col(SURFACE2)); cornerRadius = dp(3f)
            }
            addView(battFill, FrameLayout.LayoutParams(0, dpi(6f)))
        }
        val battCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            addView(battRow)
            addView(track, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpi(6f))
                .apply { topMargin = dpi(10f) })
        }

        // log ---------------------------------------------------------
        val copyBtn = Button(this).apply {
            text = "COPY"
            textSize = 11f
            isAllCaps = true
            setTextColor(col(TEXT))
            background = GradientDrawable().apply {
                setColor(col(SURFACE2)); cornerRadius = dp(8f)
                setStroke(dpi(1f), col(LINE))
            }
            minHeight = dpi(34f)
            minWidth = dpi(72f)
            stateListAnimator = null
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Knog Scout log", logView.text))
                Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("EVENT LOG"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(copyBtn)
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(col(DIM))
            movementMethod = ScrollingMovementMethod()
        }
        logScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(col(SURFACE2)); cornerRadius = dp(14f)
                setStroke(dpi(1f), col(LINE))
            }
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
            addView(logView)
        }

        fun gap(h: Float) = View(this).also {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(h)))
        }

        root.addView(header); gap(16f)
        root.addView(stateCard); gap(14f)
        root.addView(connectBtn); gap(10f)
        root.addView(actions); gap(14f)
        root.addView(battCard); gap(14f)
        root.addView(logHeader); gap(6f)
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }

        refreshAdapterState()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(btReceiver) } catch (e: Exception) { /* ignore */ }
        ui.removeCallbacks(keepAlive)
        ui.removeCallbacks(opWatchdog)
        gatt?.close()
    }

    // ------------------------------------------------- bluetooth state

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    log("Bluetooth turned off")
                    hardStop()
                    refreshAdapterState()
                }
                BluetoothAdapter.STATE_ON -> {
                    log("Bluetooth turned on")
                    refreshAdapterState()
                }
                BluetoothAdapter.STATE_TURNING_OFF ->
                    setState("BLUETOOTH", "turning off…", S_ARMING)
            }
        }
    }

    /** Single place that decides what the screen says when idle. */
    private fun refreshAdapterState() {
        val a = adapter()
        when {
            a == null -> {
                setState("NO BLUETOOTH", "this device has no adapter", S_RING)
                setPill("NO BT", S_RING)
                connectBtn.isEnabled = false
                connectBtn.text = "Unavailable"
            }
            !a.isEnabled -> {
                setState("BLUETOOTH OFF", "turn Bluetooth on to continue", S_RING)
                setPill("BT OFF", S_RING)
                connectBtn.isEnabled = true
                connectBtn.text = "Turn on Bluetooth"
                armBtn.isEnabled = false; disarmBtn.isEnabled = false
                log("Bluetooth is off - tap the button to open settings")
            }
            else -> {
                device = findScout(a)
                if (device == null) {
                    setState("NOT PAIRED", "pair the Scout in Settings", S_ARMING)
                    setPill("NO DEVICE", S_ARMING)
                    connectBtn.isEnabled = true
                    connectBtn.text = "Open Bluetooth settings"
                    log("No paired Knog Scout found.")
                } else {
                    setState("READY", "tap Connect", S_OFF)
                    setPill("OFFLINE", DIM)
                    connectBtn.isEnabled = true
                    connectBtn.text = "Connect"
                    log("paired device: ${device!!.name} (${device!!.address})")
                }
            }
        }
    }

    private fun onConnectPressed() {
        val a = adapter()
        if (a == null) return
        if (!a.isEnabled || device == null) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        if (wantConnection) hardStop() else start()
    }

    private fun findScout(a: BluetoothAdapter): BluetoothDevice? = try {
        a.bondedDevices?.firstOrNull {
            (it.name ?: "").contains("Knog", true) || (it.name ?: "").contains("Scout", true)
        }
    } catch (e: SecurityException) { null }

    // ------------------------------------------------------- rendering

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ui.post {
            logView.append("$t  $msg\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setPill(text: String, color: String) = ui.post {
        pill.text = text
        pill.setTextColor(col(color))
        (pill.background as? GradientDrawable)?.setStroke(dpi(1f), col(color))
    }

    private fun setState(word: String, sub: String, color: String) = ui.post {
        stateWord.text = word
        stateWord.setTextColor(col(color))
        stateSub.text = sub
        stateCardBg.setStroke(dpi(1f), col(color))
    }

    private fun renderBattery(pct: Int) = ui.post {
        battValue.text = "$pct%"
        val track = battFill.parent as? FrameLayout ?: return@post
        battFill.layoutParams = FrameLayout.LayoutParams(
            (track.width * pct / 100).coerceAtLeast(0), dpi(6f))
        battFill.requestLayout()
    }

    // ------------------------------------------------------ connection

    private fun start() {
        cycle = 1
        wantConnection = true
        ui.post { connectBtn.text = "Stop" }
        beginCycle()
    }

    private fun beginCycle() {
        val d = device ?: return
        discoveryTries = 0
        log("--- attempt $cycle of $MAX_CYCLES ---")
        setState("CONNECTING", "attempt $cycle of $MAX_CYCLES", S_ARMING)
        setPill("TRYING", S_ARMING)
        gatt = d.connectGatt(this, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun nextCycle() {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null; cpChar = null; alarmChar = null
        ui.removeCallbacks(opWatchdog)
        queue.clear(); busy = false
        if (!wantConnection) return
        if (cycle >= MAX_CYCLES) {
            log("gave up after $cycle attempts.")
            log("Every attempt rediscovered over the air, which this Scout")
            log("refuses. Toggle Bluetooth off/on, or re-pair, then retry.")
            setState("FAILED", "tap Connect to try again", S_RING)
            setPill("FAILED", S_RING)
            wantConnection = false
            ui.post { connectBtn.text = "Connect" }
            return
        }
        cycle++
        ui.postDelayed({ if (wantConnection) beginCycle() },
            if (cycle < 8) 4000L else 10000L)
    }

    private fun hardStop() {
        wantConnection = false
        cycle = 0
        ui.removeCallbacks(keepAlive)
        ui.removeCallbacks(opWatchdog)
        queue.clear(); busy = false
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null; cpChar = null; alarmChar = null
        ui.post {
            connectBtn.text = "Connect"
            armBtn.isEnabled = false; disarmBtn.isEnabled = false
        }
        setPill("OFFLINE", DIM)
        setState("OFFLINE", "not connected", S_OFF)
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            val c = alarmChar; val g = gatt
            if (wantConnection && g != null && c != null) {
                enqueue("keep-alive", quiet = true) { g.readCharacteristic(c) }
                ui.postDelayed(this, KEEPALIVE_MS)
            }
        }
    }

    // ----------------------------------------------------- op queue

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
        val item = queue.poll() ?: run { busy = false; return }
        busy = true
        currentOp = item.first
        val started = try { item.second() } catch (e: Exception) { false }
        if (!started) {
            log("op '${item.first}' rejected by the stack")
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

    // ------------------------------------------------------ callbacks

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("connected (status $status)")
                setState("CONNECTED", "discovering services…", S_ARMING)
                setPill("LINKED", S_ON)
                g.requestConnectionPriority(priority)
                discoveryTries = 1
                ui.postDelayed({
                    log("discovering services…")
                    discoverStarted = System.currentTimeMillis()
                    g.discoverServices()
                }, discoveryDelay)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("link dropped (status $status)")
                val wasReady = alarmChar != null
                queue.clear(); busy = false
                ui.removeCallbacks(opWatchdog)
                ui.removeCallbacks(keepAlive)
                cpChar = null; alarmChar = null
                ui.post { armBtn.isEnabled = false; disarmBtn.isEnabled = false }
                if (wantConnection) {
                    setPill("RETRYING", S_ARMING)
                    if (wasReady) cycle = 1
                    nextCycle()
                } else {
                    setPill("OFFLINE", DIM)
                    setState("OFFLINE", "not connected", S_OFF)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val services = g.services
            val ms = System.currentTimeMillis() - discoverStarted
            val how = if (ms < 2000) "CACHE HIT" else "over the air"
            log("services: status=$status count=${services.size} in ${ms}ms ($how)")

            if (status != BluetoothGatt.GATT_SUCCESS || services.isEmpty()) {
                if (discoveryTries < MAX_DISCOVERY) {
                    discoveryTries++
                    log("empty table - retrying discovery ($discoveryTries)")
                    ui.postDelayed({ g.discoverServices() }, 1200)
                } else {
                    log("empty after $MAX_DISCOVERY tries - fresh connection")
                    nextCycle()
                }
                return
            }

            val knog = g.getService(KNOG_SVC)
            if (knog == null) { log("Knog service missing - retrying"); nextCycle(); return }
            cpChar = knog.getCharacteristic(CP_CHR)
            alarmChar = knog.getCharacteristic(ALARM_CHR)

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

            ui.post { armBtn.isEnabled = true; disarmBtn.isEnabled = true }
            ui.post { connectBtn.text = "Disconnect" }
            setPill("READY", S_ON)
            ui.removeCallbacks(keepAlive)
            ui.postDelayed(keepAlive, KEEPALIVE_MS)
            log("READY on attempt $cycle")
        }

        @Deprecated("targetSdk uses the legacy signature")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) { handleValue(c, c.value, status); opDone() }

        @Deprecated("targetSdk uses the legacy signature")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleValue(c, c.value, BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) { log(if (status == 0) "write ok" else "write failed ($status)"); opDone() }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) { opDone() }
    }

    private fun handleValue(c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) return
        when (c.uuid) {
            ALARM_CHR -> {
                val v = (value.getOrElse(0) { 0 }.toInt() and 0xFF) or
                        ((value.getOrElse(1) { 0 }.toInt() and 0xFF) shl 8)
                when (v) {
                    0 -> setState("OFF", "disarmed", S_OFF)
                    1 -> setState("ARMING", "turning on", S_ARMING)
                    2 -> setState("ARMED", "on watch", S_ON)
                    3 -> setState("RINGING", "siren active", S_RING)
                    else -> setState("STATE $v", "unrecognised", DIM)
                }
            }
            BATT_CHR -> renderBattery(value.getOrElse(0) { 0 }.toInt() and 0xFF)
        }
    }

    private fun writeCp(op: Byte, what: String) {
        val g = gatt; val c = cpChar
        if (g == null || c == null) { log("not ready"); return }
        log("$what → control point 0x%02x".format(op))
        enqueue("write $what") {
            c.value = byteArrayOf(op)
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }
    }
}
