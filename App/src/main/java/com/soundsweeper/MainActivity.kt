package com.soundsweeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * SoundSweeper Control 1.3
 *
 * Shows connected Android Bluetooth audio profiles and optional battery broadcasts.
 * This app does not claim to process other apps' audio sessions or to send
 * undocumented proprietary EQ / ANC commands to the headphones.
 */
class MainActivity : Activity() {

    private val navy = Color.rgb(7, 17, 31)
    private val surface = Color.rgb(18, 39, 58)
    private val inset = Color.rgb(12, 29, 46)
    private val outline = Color.rgb(41, 71, 92)
    private val ice = Color.rgb(183, 229, 251)
    private val white = Color.rgb(242, 248, 255)
    private val muted = Color.rgb(162, 183, 202)
    private val coral = Color.rgb(255, 148, 129)
    private val mint = Color.rgb(113, 219, 181)

    private lateinit var status: TextView
    private lateinit var statusInfo: TextView
    private lateinit var detectedName: TextView
    private lateinit var battery: TextView
    private lateinit var batteryInfo: TextView
    private lateinit var batteryBar: ProgressBar

    private var a2dpProfile: BluetoothProfile? = null
    private var headsetProfile: BluetoothProfile? = null
    private var a2dpRequested = false
    private var headsetRequested = false
    private var active = false
    private var receiverRegistered = false
    private var autoPermissionAsked = false

    // Battery broadcast is a vendor/platform-dependent extra, not a guarantee.
    private var batteryForDevice: String? = null
    private var lastReportedBattery: Int? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val delayedCheck = Runnable { if (active) refreshConnection() }
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!active) {
                bluetoothManager.adapter?.closeProfileProxy(profile, proxy)
                return
            }
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProfile = proxy
                BluetoothProfile.HEADSET -> headsetProfile = proxy
            }
            updateConnectedDevices()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    a2dpProfile = null
                    a2dpRequested = false
                }
                BluetoothProfile.HEADSET -> {
                    headsetProfile = null
                    headsetRequested = false
                }
            }
            if (active) refreshConnection()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active) return
            if (canReadBluetooth()) {
                try {
                    onDeviceBroadcast(intent)
                } catch (_: SecurityException) {
                    // Some OEMs restrict extra Bluetooth details.
                }
            }
            refreshConnection()
            mainHandler.removeCallbacks(delayedCheck)
            mainHandler.postDelayed(delayedCheck, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        createInterface()
    }

    override fun onStart() {
        super.onStart()
        active = true
        registerBluetoothEvents()
        refreshConnection()
        if (!canReadBluetooth() && !autoPermissionAsked) {
            autoPermissionAsked = true
            requestBluetoothPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (active) refreshConnection()
    }

    override fun onStop() {
        active = false
        mainHandler.removeCallbacks(delayedCheck)
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        bluetoothManager.adapter?.let { adapter ->
            a2dpProfile?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProfile?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        a2dpProfile = null
        headsetProfile = null
        a2dpRequested = false
        headsetRequested = false
        super.onStop()
    }

    private fun createInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(40))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                column.setPadding(dp(22), bars.top + dp(24), dp(22), bars.bottom + dp(40))
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }

        column.addView(label("SOUNDSWEEPER  /  CONTROL", 12f, ice, bold = true))
        column.addView(label("Your headphones.\nOne dashboard.", 32f, white, bold = true).apply {
            setPadding(0, dp(12), 0, dp(12))
        })
        column.addView(label("STREAMING EDITION  •  V1.3", 12f, muted, bold = true))
        column.addView(space(26))

        val deviceCard = card()
        deviceCard.addView(label("DEVICE CONNECTION", 12f, ice, bold = true))
        status = label("Checking Bluetooth…", 28f, white, bold = true).apply {
            setPadding(0, dp(13), 0, dp(5))
        }
        statusInfo = label("Reading Android's connected audio devices.", 14f, muted)
        detectedName = label("SoundSweeper", 17f, white, bold = true).apply {
            setPadding(0, dp(19), 0, dp(3))
        }
        deviceCard.addView(status)
        deviceCard.addView(statusInfo)
        deviceCard.addView(detectedName)
        deviceCard.addView(label("BLUETOOTH AUDIO", 11f, muted, bold = true))

        val batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(21), 0, dp(8))
        }
        batteryRow.addView(label("HEADPHONE BATTERY", 12f, ice, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        battery = label("—", 19f, white, bold = true)
        batteryRow.addView(battery)
        deviceCard.addView(batteryRow)
        batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressBackgroundTintList = ColorStateList.valueOf(outline)
            progressTintList = ColorStateList.valueOf(mint)
        }
        deviceCard.addView(batteryBar, LinearLayout.LayoutParams(-1, dp(8)))
        batteryInfo = label("Waiting for a battery report.", 12f, muted).apply {
            setPadding(0, dp(10), 0, 0)
        }
        deviceCard.addView(batteryInfo)
        column.addView(deviceCard, gap(12))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Refresh", ice, navy).apply {
            setOnClickListener {
                if (!canReadBluetooth()) requestBluetoothPermission()
                else refreshConnection()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(9) })
        controls.addView(button("Bluetooth settings", inset, white).apply {
            setOnClickListener { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(controls, gap(30))

        column.addView(section("LISTEN", "Open the apps you actually stream with"))
        column.addView(streamApp("S", "Spotify", "Music and podcasts", "com.spotify.music", mint), gap(10))
        column.addView(streamApp("A", "Apple Music", "Streaming on Android", "com.apple.android.music", coral), gap(10))
        column.addView(streamApp("Y", "YouTube", "Videos and music", "com.google.android.youtube", ice), gap(28))

        column.addView(section("SOUND TOOLS", "Phone settings, not headphone firmware"))
        val soundCard = card()
        soundCard.addView(label("Find your phone's equalizer", 19f, white, bold = true))
        soundCard.addView(label(
            "If your phone offers system-wide sound effects, those may improve audio from several streaming apps.",
            14f, muted
        ).apply { setPadding(0, dp(8), 0, dp(17)) })
        soundCard.addView(button("Open phone sound settings", ice, navy).apply {
            setOnClickListener { openSettings(Settings.ACTION_SOUND_SETTINGS) }
        })
        soundCard.addView(button("Show media volume", inset, white).apply {
            setOnClickListener { showMediaVolume() }
        }, gap(2))
        column.addView(soundCard, gap(22))

        val guidance = card()
        guidance.addView(label("WHERE TO ADJUST EQ", 12f, ice, bold = true))
        guidance.addView(label("Spotify", 16f, white, bold = true).apply {
            setPadding(0, dp(14), 0, dp(4))
        })
        guidance.addView(label(
            "Open Spotify → Settings and privacy → Playback / Audio Quality → Equalizer, if available on your phone.",
            13f, muted
        ))
        guidance.addView(label("Apple Music + YouTube", 16f, white, bold = true).apply {
            setPadding(0, dp(16), 0, dp(4))
        })
        guidance.addView(label(
            "Try your phone's Sound settings for device-level effects. Availability depends on your Android phone.",
            13f, muted
        ))
        guidance.addView(label("HEADPHONE HARDWARE EQ", 11f, coral, bold = true).apply {
            setPadding(0, dp(21), 0, dp(6))
        })
        guidance.addView(label(
            "Direct bass, treble and ANC commands are not supported by standard Bluetooth audio profiles. We need a verified SoundSweeper control protocol before adding those buttons.",
            13f, muted
        ))
        column.addView(guidance)

        column.addView(label("Independent prototype • not affiliated with Volkano", 11f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(23), 0, 0)
        })
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun streamApp(letter: String, name: String, detail: String,
                          packageName: String, accent: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(13), dp(15), dp(13))
            background = round(surface, 18, outline)
            isClickable = true
            isFocusable = true
            setOnClickListener { launchApp(packageName) }
        }

        val initial = label(letter, 20f, navy, bold = true).apply {
            gravity = Gravity.CENTER
            background = round(accent, 14)
        }
        row.addView(initial, LinearLayout.LayoutParams(dp(49), dp(49)).apply { marginEnd = dp(14) })

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(name, 17f, white, bold = true))
        titles.addView(label(detail, 12f, muted).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("Open  ›", 13f, ice, bold = true))
        return row
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this app.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            Toast.makeText(this, "These settings are unavailable on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMediaVolume() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_SAME,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open media volume controls.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun section(heading: String, subheading: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(heading, 12f, ice, bold = true))
            addView(label(subheading, 14f, muted).apply {
                setPadding(0, dp(5), 0, dp(14))
            })
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(
                if (bold) "sans-serif-medium" else "sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
            setLineSpacing(dp(2).toFloat(), 1.06f)
        }

    private fun button(value: String, bg: Int, fg: Int) =
        label(value, 14f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(17), dp(9), dp(17))
            background = round(bg, 16, if (bg == ice) null else outline)
            isClickable = true
            isFocusable = true
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(21), dp(20), dp(22))
        background = round(surface, 23, outline)
    }

    private fun round(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun gap(bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun canReadBluetooth(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canReadBluetooth()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONNECT) refreshConnection()
    }

    private fun registerBluetoothEvents() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // OEM-specific battery broadcast: may not reach third-party apps.
            addAction(BATTERY_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission") // Caller checks BLUETOOTH_CONNECT.
    private fun onDeviceBroadcast(intent: Intent) {
        val action = intent.action ?: return
        if (action != BATTERY_CHANGED_ACTION &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            if (batteryForDevice == device.address) {
                batteryForDevice = null
                lastReportedBattery = null
            }
            return
        }

        val level = intent.getIntExtra(BATTERY_LEVEL_EXTRA, -1)
        batteryForDevice = device.address
        lastReportedBattery = level.takeIf { it in 0..100 }
    }

    @SuppressLint("MissingPermission") // Caller checks BLUETOOTH_CONNECT.
    private fun refreshConnection() {
        if (!canReadBluetooth()) {
            renderConnection("Permission needed", "Allow Nearby devices to check your headphones.", coral)
            return
        }
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            renderConnection("Unavailable", "This phone has no Bluetooth adapter.", coral)
            return
        }
        if (!adapter.isEnabled) {
            renderConnection("Bluetooth off", "Turn Bluetooth on to connect your headphones.", coral)
            return
        }
        if (!a2dpRequested) {
            a2dpRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        }
        if (!headsetRequested) {
            headsetRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        }
        updateConnectedDevices()
    }

    @SuppressLint("MissingPermission") // Caller checks BLUETOOTH_CONNECT.
    private fun updateConnectedDevices() {
        if (!canReadBluetooth()) return
        try {
            val connected = listOfNotNull(a2dpProfile, headsetProfile)
                .flatMap { it.connectedDevices }
                .distinctBy { it.address }
            val headphones = connected.firstOrNull { device ->
                val name = device.name.orEmpty().lowercase(Locale.ROOT)
                    .replace(Regex("[\\s_-]"), "")
                name.contains("soundsweeper") ||
                    (name.contains("volkano") && name.contains("sweeper"))
            }
            when {
                headphones != null -> {
                    renderConnection("Connected", "Ready for Bluetooth audio.", mint,
                        headphones.name.orEmpty().ifBlank { "SoundSweeper" })
                    val level = if (batteryForDevice == headphones.address)
                        lastReportedBattery ?: -1 else -1
                    renderBattery(level)
                }
                connected.isNotEmpty() -> {
                    val other = connected.first().name.orEmpty().ifBlank { "Other audio device" }
                    renderConnection("Other device", "SoundSweeper is not connected for audio.", coral, other)
                }
                a2dpProfile == null && headsetProfile == null ->
                    renderConnection("Checking…", "Waiting for Bluetooth audio services.", ice)
                else -> renderConnection("Not connected",
                    "Connect SoundSweeper in your Bluetooth settings.", coral)
            }
        } catch (_: SecurityException) {
            renderConnection("Permission needed", "Allow Nearby devices to detect headphones.", coral)
        }
    }

    private fun renderConnection(title: String, detail: String, color: Int, name: String? = null) {
        status.text = title
        status.setTextColor(color)
        statusInfo.text = detail
        detectedName.text = name ?: "SoundSweeper"
        renderBattery(-1)
        if (name == null) batteryInfo.text = "Connect SoundSweeper to check battery reporting."
    }

    private fun renderBattery(level: Int) {
        if (level in 0..100) {
            battery.text = "$level%"
            batteryBar.progress = level
            batteryBar.visibility = View.VISIBLE
            batteryBar.progressTintList = ColorStateList.valueOf(if (level <= 20) coral else mint)
            batteryInfo.text = "Reported by Android's Bluetooth stack."
        } else {
            battery.text = "Unavailable"
            batteryBar.visibility = View.GONE
            batteryInfo.text = "Battery reporting depends on your phone and headphones."
        }
    }

    companion object {
        private const val REQUEST_CONNECT = 1001
        private const val BATTERY_CHANGED_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BATTERY_LEVEL_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
