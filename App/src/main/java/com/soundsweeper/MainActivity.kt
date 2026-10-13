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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Shows Android's actual connected AUDIO profiles (A2DP / headset).
 * Bluetooth pairing alone does not count as an active connection.
 *
 * This application does not send proprietary commands to the headphones.
 */
class MainActivity : Activity() {

    private val navy = Color.rgb(7, 19, 34)
    private val panel = Color.rgb(19, 38, 59)
    private val ice = Color.rgb(181, 229, 252)
    private val white = Color.rgb(244, 249, 255)
    private val muted = Color.rgb(158, 182, 202)
    private val coral = Color.rgb(255, 139, 122)
    private val mint = Color.rgb(115, 221, 185)

    private lateinit var connectionTitle: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var deviceName: TextView
    private lateinit var refreshButton: TextView

    private var a2dpProfile: BluetoothProfile? = null
    private var headsetProfile: BluetoothProfile? = null
    private var a2dpRequested = false
    private var headsetRequested = false
    private var active = false
    private var receiverRegistered = false
    private var permissionAskedAutomatically = false

    private val handler = Handler(Looper.getMainLooper())
    private val recheck = Runnable { if (active) refreshConnection() }

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
            showConnectedDevices()
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
            refreshConnection()
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        active = true
        registerBluetoothUpdates()
        refreshConnection()

        if (!hasConnectPermission() && !permissionAskedAutomatically) {
            permissionAskedAutomatically = true
            requestBluetoothPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (active) refreshConnection()
    }

    override fun onStop() {
        active = false
        handler.removeCallbacks(recheck)
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

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(32), dp(22), dp(30))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                content.setPadding(dp(22), dp(28) + bars.top, dp(22), dp(30) + bars.bottom)
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }

        content.addView(label("SOUNDSWEEPER  /  CONTROL", 13f, ice, true))
        content.addView(label("Your sound.\nYour connection.", 32f, white, true).apply {
            setPadding(0, dp(13), 0, dp(12))
        })
        content.addView(label("Volkano SoundSweeper companion • v1.1", 14f, muted))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = rounded(panel, 24, Color.rgb(38, 65, 89))
        }
        val cardParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(30)
            bottomMargin = dp(18)
        }
        content.addView(card, cardParams)

        card.addView(label("BLUETOOTH AUDIO", 12f, ice, true))
        connectionTitle = label("Checking connection…", 24f, white, true).apply {
            setPadding(0, dp(16), 0, dp(10))
        }
        card.addView(connectionTitle)
        connectionDetail = label("Reading your phone's audio connections.", 15f, muted)
        card.addView(connectionDetail)
        deviceName = label("Device: —", 14f, ice, true).apply {
            setPadding(0, dp(21), 0, 0)
        }
        card.addView(deviceName)

        refreshButton = actionButton("Refresh connection", ice, navy).apply {
            setOnClickListener {
                if (!hasConnectPermission()) requestBluetoothPermission()
                else refreshConnection()
            }
        }
        content.addView(refreshButton, spacedParams(12))

        content.addView(actionButton("Open Bluetooth settings", panel, white).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }, spacedParams(12))

        content.addView(label("WHAT THIS VERSION DOES", 12f, ice, true).apply {
            setPadding(0, dp(26), 0, dp(12))
        })
        content.addView(label(
            "Detects active Bluetooth audio connections. If your headphones connect or disconnect, the status updates automatically.",
            15f, muted
        ))
        content.addView(label(
            "Battery reporting, headphone equalizer and ANC controls are not included yet. Those features depend on what the headphones expose.",
            14f, muted
        ).apply { setPadding(0, dp(16), 0, 0) })

        scroll.addView(content)
        setContentView(scroll)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }

    private fun actionButton(value: String, backgroundColor: Int, textColor: Int) =
        TextView(this).apply {
            text = value
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(dp(12), dp(17), dp(12), dp(17))
            background = rounded(backgroundColor, 16)
            isClickable = true
            isFocusable = true
        }

    private fun rounded(color: Int, radius: Int, border: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            border?.let { setStroke(dp(1), it) }
        }

    private fun spacedParams(bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission()) {
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

    private fun registerBluetoothUpdates() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission") // Guarded by hasConnectPermission().
    private fun refreshConnection() {
        if (!hasConnectPermission()) {
            render("Permission required", "Allow Nearby devices to check headphone audio connections.", coral)
            return
        }

        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            render("Bluetooth unavailable", "This device doesn't provide a Bluetooth adapter.", coral)
            return
        }
        if (!adapter.isEnabled) {
            render("Bluetooth is off", "Turn on Bluetooth to connect SoundSweeper.", coral)
            return
        }

        if (!a2dpRequested) {
            a2dpRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        }
        if (!headsetRequested) {
            headsetRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        }
        showConnectedDevices()
    }

    @SuppressLint("MissingPermission") // Guarded by hasConnectPermission().
    private fun showConnectedDevices() {
        if (!hasConnectPermission()) return
        try {
            val devices = listOfNotNull(a2dpProfile, headsetProfile)
                .flatMap { it.connectedDevices }
                .distinctBy { it.address }

            val soundSweeper = devices.firstOrNull { device ->
                isSoundSweeper(device.name.orEmpty())
            }

            when {
                soundSweeper != null -> {
                    val name = soundSweeper.name.orEmpty().ifBlank { "SoundSweeper" }
                    render("Connected", "Your SoundSweeper is connected for Bluetooth audio.", mint, name)
                }
                devices.isNotEmpty() -> {
                    val other = devices.first().name.orEmpty().ifBlank { "Unnamed audio device" }
                    render(
                        "SoundSweeper not connected",
                        "Android currently reports a different connected audio device.",
                        coral,
                        other
                    )
                }
                a2dpProfile == null && headsetProfile == null -> {
                    render(
                        "Checking audio devices…",
                        "Waiting for Android's Bluetooth audio services.",
                        ice
                    )
                }
                else -> {
                    render(
                        "Not connected",
                        "Connect SoundSweeper in your phone's Bluetooth settings, then refresh.",
                        coral
                    )
                }
            }
        } catch (_: SecurityException) {
            render("Permission required", "Allow Nearby devices and try again.", coral)
        }
    }

    private fun isSoundSweeper(name: String): Boolean {
        val compact = name.lowercase(Locale.ROOT).replace(Regex("[\\s_-]"), "")
        return compact.contains("soundsweeper") ||
            (compact.contains("volkano") && compact.contains("sweeper"))
    }

    private fun render(title: String, detail: String, color: Int, connectedDevice: String? = null) {
        connectionTitle.text = title
        connectionTitle.setTextColor(color)
        connectionDetail.text = detail
        deviceName.text = if (connectedDevice == null) "Device: —" else "Audio device: $connectedDevice"
        refreshButton.text = if (hasConnectPermission()) "Refresh connection" else "Allow Bluetooth access"
    }

    companion object {
        private const val REQUEST_CONNECT = 1001
    }
}
