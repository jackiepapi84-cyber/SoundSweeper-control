package com.soundsweeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.os.ParcelUuid
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Read-only hardware capability investigation.
 *
 * Discovers classic Bluetooth service UUIDs for the CONNECTED SoundSweeper,
 * and displays key events ONLY if Android delivers them to this foreground Activity.
 * It does not scan for nearby devices, use Bluetooth GATT, write characteristics,
 * send vendor commands, or claim to remap headset buttons.
 *
 * Reports omit hardware/MAC addresses and stay on the phone unless the user
 * explicitly copies them.
 */
class DiagnosticsActivity : Activity() {
    private val bg = Color.rgb(247, 250, 254)
    private val white = Color.WHITE
    private val navy = Color.rgb(11, 32, 55)
    private val secondary = Color.rgb(101, 123, 145)
    private val blue = Color.rgb(42, 122, 204)
    private val ice = Color.rgb(218, 241, 253)
    private val border = Color.rgb(223, 232, 241)
    private val coral = Color.rgb(242, 119, 98)
    private val mint = Color.rgb(23, 163, 117)

    private lateinit var deviceTitle: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var bluetoothType: TextView
    private lateinit var profileText: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var servicesText: TextView
    private lateinit var keyStatus: TextView

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var a2dpRequested = false
    private var headsetRequested = false
    private var started = false
    private var receiverRegistered = false
    private var headsetDevice: BluetoothDevice? = null
    private var discoveredServices: List<ParcelUuid>? = null
    private var lookupStatus = "Not requested"
    private var lastKey = "No button event received"
    private val manager: BluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!started) {
                manager.adapter?.closeProfileProxy(profile, proxy)
                return
            }
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy
                BluetoothProfile.HEADSET -> headset = proxy
            }
            refreshDevice()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    a2dp = null
                    a2dpRequested = false
                }
                BluetoothProfile.HEADSET -> {
                    headset = null
                    headsetRequested = false
                }
            }
            if (started) refreshDevice()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started || !hasPermission()) return
            if (intent.action == BluetoothDevice.ACTION_UUID) {
                handleUuidUpdate(intent)
            } else {
                refreshDevice()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerEvents()
        refreshDevice()
        if (!hasPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), PERMISSION_REQUEST)
        }
    }

    override fun onStop() {
        started = false
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        manager.adapter?.let { adapter ->
            a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        a2dp = null
        headset = null
        a2dpRequested = false
        headsetRequested = false
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) refreshDevice()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(24), dp(21), dp(36))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                root.setPadding(dp(21), bars.top + dp(18), dp(21), bars.bottom + dp(36))
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }

        root.addView(button("‹  Back to device", white, navy).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }, spaceBelow(19))
        root.addView(label("HARDWARE DIAGNOSTICS", 12f, blue, true))
        root.addView(label("Explore capabilities", 28f, navy, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        })
        root.addView(label("Read-only Bluetooth inspection • v1.5", 13f, secondary), spaceBelow(18))

        val summary = card()
        summary.addView(label("CONNECTED HEADPHONES", 12f, blue, true))
        deviceTitle = label("Checking…", 21f, navy, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        }
        connectionStatus = label("Waiting for Android", 14f, secondary)
        bluetoothType = label("Bluetooth type: —", 14f, secondary).apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        profileText = label("Audio profiles: —", 14f, secondary)
        summary.addView(deviceTitle)
        summary.addView(connectionStatus)
        summary.addView(bluetoothType)
        summary.addView(profileText)
        root.addView(summary, spaceBelow(15))

        val services = card()
        services.addView(label("ADVERTISED BLUETOOTH SERVICES", 12f, blue, true))
        serviceStatus = label("Not checked yet", 14f, secondary).apply {
            setPadding(0, dp(10), 0, dp(8))
        }
        servicesText = label("Connect SoundSweeper to inspect cached Bluetooth service UUIDs.",
            13f, secondary)
        services.addView(serviceStatus)
        services.addView(servicesText)
        services.addView(button("Read supported services", blue, white).apply {
            setOnClickListener { requestServiceDiscovery() }
        }, spaceAbove(18))
        services.addView(label(
            "This checks classic Bluetooth SDP records. A listed service does not prove ANC or button-remapping support; some headphone features may use a separate BLE interface.",
            12f, secondary
        ).apply { setPadding(0, dp(15), 0, 0) })
        root.addView(services, spaceBelow(15))

        val keyCard = card()
        keyCard.addView(label("BUTTON EVENT TEST", 12f, blue, true))
        keyCard.addView(label("Press a headset button", 20f, navy, true).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        keyStatus = label(lastKey, 14f, mint, true)
        keyCard.addView(keyStatus, spaceBelow(12))
        keyCard.addView(label(
            "Keep this screen open and press a media or volume button. Android may handle the event before it reaches this app. Events could also come from the phone's own buttons. No event does not mean a headset button is defective.",
            12f, secondary
        ))
        root.addView(keyCard, spaceBelow(15))

        root.addView(button("Copy diagnostic report", blue, white).apply {
            setOnClickListener { copyReport() }
        }, spaceBelow(14))
        root.addView(label(
            "Privacy: This report excludes the Bluetooth MAC address. It is not uploaded or sent anywhere. Copying places it on your phone's clipboard until you paste it.",
            12f, secondary
        ), spaceBelow(18))
        root.addView(label(
            "ANC is still controlled by the physical switch. No commands are sent to the headphones, and draft button mappings are unchanged.",
            13f, secondary
        ))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun registerEvents() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission") // Gated by hasPermission()
    private fun refreshDevice() {
        if (!hasPermission()) {
            showEmpty("Nearby devices permission required")
            return
        }
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            showEmpty("Bluetooth is turned off")
            return
        }
        try {
            if (!a2dpRequested) {
                a2dpRequested = adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)
            }
            if (!headsetRequested) {
                headsetRequested = adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET)
            }
            val a2dpDevices = a2dp?.connectedDevices.orEmpty()
            val headsetDevices = headset?.connectedDevices.orEmpty()
            val devices = (a2dpDevices + headsetDevices).distinctBy { it.address }
            val device = devices.firstOrNull { matchesSoundSweeper(it.name.orEmpty()) }

            if (device == null) {
                headsetDevice = null
                discoveredServices = null
                showEmpty(
                    if (a2dp == null && headset == null) "Waiting for Bluetooth audio profiles"
                    else "SoundSweeper isn't connected for audio"
                )
                return
            }

            if (headsetDevice?.address != device.address) {
                discoveredServices = null
                lookupStatus = "Showing locally cached Bluetooth services"
            }
            headsetDevice = device
            deviceTitle.text = device.name.orEmpty().ifBlank { "SoundSweeper" }
            connectionStatus.text = "Connected for Bluetooth audio"
            connectionStatus.setTextColor(mint)
            bluetoothType.text = "Bluetooth type: " + describeType(device.type)
            val profiles = ArrayList<String>()
            if (a2dpDevices.any { it.address == device.address }) profiles.add("A2DP music")
            if (headsetDevices.any { it.address == device.address }) profiles.add("Hands-free calls")
            profileText.text = "Audio profiles: " +
                if (profiles.isEmpty()) "Not available" else profiles.joinToString(" + ")
            renderServices()
        } catch (_: SecurityException) {
            showEmpty("Nearby devices permission required")
        }
    }

    private fun matchesSoundSweeper(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
            .replace(" ", "").replace("-", "").replace("_", "")
        return normalized.contains("soundsweeper") ||
            (normalized.contains("volkano") && normalized.contains("sweeper"))
    }

    private fun describeType(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic Bluetooth"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual-mode (Classic + BLE capable)"
        BluetoothDevice.DEVICE_TYPE_LE -> "Bluetooth Low Energy"
        else -> "Unknown"
    }

    private fun showEmpty(message: String) {
        deviceTitle.text = "SoundSweeper"
        connectionStatus.text = message
        connectionStatus.setTextColor(coral)
        bluetoothType.text = "Bluetooth type: —"
        profileText.text = "Audio profiles: —"
        serviceStatus.text = "Connect the headphones first"
        servicesText.text = "No service list available."
    }

    @SuppressLint("MissingPermission") // Gated by hasPermission()
    private fun renderServices() {
        if (!hasPermission()) return
        val device = headsetDevice ?: return
        val uuids = discoveredServices ?: device.uuids?.toList().orEmpty()
        serviceStatus.text = lookupStatus
        servicesText.text = if (uuids.isEmpty()) {
            "No cached services available. Tap 'Read supported services' while the headphones are connected."
        } else {
            uuids.distinctBy { it.uuid.toString() }.sortedBy { it.uuid.toString() }
                .joinToString("\n\n") { uuid -> formatService(uuid) }
        }
    }

    private fun formatService(service: ParcelUuid): String {
        val value = service.uuid.toString().lowercase(Locale.ROOT)
        val known = when (value.substring(0, 8)) {
            "0000110b" -> "A2DP audio sink"
            "0000110c" -> "AVRCP remote control target"
            "0000110e" -> "AVRCP remote control"
            "0000111e" -> "Hands-free"
            "0000111f" -> "Hands-free audio gateway"
            "00001108" -> "Headset"
            "00001101" -> "Serial Port (SPP)"
            "00001800" -> "Generic Access (BLE)"
            "00001801" -> "Generic Attribute (BLE)"
            "0000180f" -> "Battery Service (BLE)"
            else -> "Unidentified service"
        }
        return "$known\n$value"
    }

    @SuppressLint("MissingPermission") // Gated by hasPermission()
    private fun requestServiceDiscovery() {
        if (!hasPermission()) {
            Toast.makeText(this, "Allow Nearby devices permission first.", Toast.LENGTH_LONG).show()
            return
        }
        val device = headsetDevice
        if (device == null) {
            Toast.makeText(this, "Connect SoundSweeper first.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val requested = device.fetchUuidsWithSdp()
            lookupStatus = if (requested)
                "Discovering services… this may take a few seconds"
            else "Android could not start service discovery"
            renderServices()
        } catch (_: SecurityException) {
            lookupStatus = "Nearby devices permission required"
            renderServices()
        } catch (_: Exception) {
            lookupStatus = "Service discovery isn't available on this phone"
            renderServices()
        }
    }

    private fun readDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @SuppressLint("MissingPermission") // Gated by hasPermission()
    private fun handleUuidUpdate(intent: Intent) {
        val source = readDeviceExtra(intent) ?: return
        val selected = headsetDevice ?: return
        if (source.address != selected.address) return
        val payload: Array<ParcelUuid>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                ?.filterIsInstance<ParcelUuid>()?.toTypedArray()
        }
        discoveredServices = payload?.toList()
        lookupStatus = when {
            payload == null -> "Service discovery timed out; cached values may still be shown"
            payload.isEmpty() -> "Discovery finished: no services reported"
            else -> "Discovery completed: " + payload.size + " advertised services"
        }
        renderServices()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val name = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Media Play"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Media Pause"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Media Play/Pause"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Next Track"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous Track"
                KeyEvent.KEYCODE_HEADSETPHOOK -> "Headset Hook"
                KeyEvent.KEYCODE_VOLUME_UP -> "Volume Up"
                KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume Down"
                else -> null
            }
            if (name != null) {
                lastKey = "Received by this screen: $name"
                if (::keyStatus.isInitialized) keyStatus.text = lastKey
            }
        }
        // Leave Android's original button behaviour intact.
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("MissingPermission") // Gated by hasPermission()
    private fun buildReport(): String {
        val device = if (hasPermission()) headsetDevice else null
        val uuidList = if (device != null) {
            discoveredServices ?: device.uuids?.toList().orEmpty()
        } else emptyList()
        val currentName = device?.name.orEmpty().ifBlank { "Not connected" }
        return buildString {
            appendLine("SoundSweeper diagnostics — v1.5")
            appendLine("Headset name: $currentName")
            appendLine("Headset connected: ${device != null}")
            appendLine("Bluetooth type: ${device?.type?.let { describeType(it) } ?: "N/A"}")
            appendLine("Service discovery: $lookupStatus")
            appendLine("Advertised UUIDs:")
            if (uuidList.isEmpty()) appendLine("  None reported")
            else uuidList.distinctBy { it.uuid }.forEach {
                appendLine("  " + formatService(it).replace("\n", " — "))
            }
            appendLine("Button event: $lastKey")
            appendLine("Hardware address: excluded")
            appendLine("Note: SDP results do not establish proprietary ANC/remapping support.")
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SoundSweeper diagnostics", buildReport()))
        Toast.makeText(this, "Diagnostic report copied. Paste it into ChatGPT.", Toast.LENGTH_LONG).show()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1.05f)
        }

    private fun button(value: String, fill: Int, foreground: Int) =
        label(value, 14f, foreground, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(17), dp(14), dp(17))
            background = round(fill, 16, if (fill == white) border else null)
            isClickable = true
            isFocusable = true
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(19), dp(18), dp(20))
        background = round(white, 21, border)
    }

    private fun round(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun spaceBelow(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(bottom)
    }

    private fun spaceAbove(top: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PERMISSION_REQUEST = 1501
    }
}
