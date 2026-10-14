package com.soundsweeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Independent SoundSweeper companion.
 * Button choices are explicitly LOCAL DRAFTS, not headset remapping.
 * ANC is a physical switch and isn't remotely controlled by this app.
 */
class MainActivity : Activity() {
    private val bg = Color.rgb(247, 250, 254)
    private val white = Color.WHITE
    private val navy = Color.rgb(11, 32, 55)
    private val secondary = Color.rgb(101, 123, 145)
    private val blue = Color.rgb(42, 122, 204)
    private val ice = Color.rgb(218, 241, 253)
    private val border = Color.rgb(223, 232, 241)
    private val coral = Color.rgb(242, 119, 98)
    private val mint = Color.rgb(23, 163, 117)

    private lateinit var status: TextView
    private lateinit var deviceTitle: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var batteryInfo: TextView
    private lateinit var pageContent: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabs = ArrayList<TextView>()
    private var selectedPage = 0

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var a2dpRequested = false
    private var headsetRequested = false
    private var active = false
    private var receiverRegistered = false
    private var permissionAsked = false
    private var batteryDevice: String? = null
    private var lastBattery: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private val delayedRefresh = Runnable { if (active) refreshBluetooth() }
    private val bluetooth: BluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private data class ButtonSpec(val id: String, val name: String, val factoryAction: String)

    private val buttonSpecs = listOf(
        ButtonSpec("power_tap", "Power button · short press", "Play / Pause"),
        ButtonSpec("plus_tap", "Volume + · short press", "Volume Up"),
        ButtonSpec("plus_hold", "Volume + · hold", "Next Track"),
        ButtonSpec("minus_tap", "Volume − · short press", "Volume Down"),
        ButtonSpec("minus_hold", "Volume − · hold", "Previous Track")
    )
    private val actionChoices = arrayOf(
        "Play / Pause", "Next Track", "Previous Track",
        "Volume Up", "Volume Down", "Voice Assistant", "No Action"
    )

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!active) {
                bluetooth.adapter?.closeProfileProxy(profile, proxy)
                return
            }
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy
                BluetoothProfile.HEADSET -> headset = proxy
            }
            updateBluetoothView()
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
            if (active) refreshBluetooth()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active) return
            if (hasPermission()) {
                try {
                    captureBattery(intent)
                } catch (_: SecurityException) {
                    // Some Android builds hide additional Bluetooth details.
                }
            }
            refreshBluetooth()
            handler.removeCallbacks(delayedRefresh)
            handler.postDelayed(delayedRefresh, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildInterface()
    }

    override fun onStart() {
        super.onStart()
        active = true
        registerBluetoothBroadcasts()
        refreshBluetooth()
        if (!hasPermission() && !permissionAsked) {
            permissionAsked = true
            requestBluetoothPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (active) refreshBluetooth()
    }

    override fun onStop() {
        active = false
        handler.removeCallbacks(delayedRefresh)
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        bluetooth.adapter?.let { adapter ->
            a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        a2dp = null
        headset = null
        a2dpRequested = false
        headsetRequested = false
        super.onStop()
    }

    private fun buildInterface() {
        scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(26), dp(21), dp(35))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                root.setPadding(dp(21), bars.top + dp(18), dp(21), bars.bottom + dp(35))
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }

        root.addView(line("SOUNDSWEEPER", 13f, blue, true))
        root.addView(line("Your headphones", 29f, navy, true).apply {
            setPadding(0, dp(8), 0, dp(3))
        })
        root.addView(line("Simple controls. Clear information.", 14f, secondary))
        root.addView(space(18))

        val hero = card()
        val heroHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val symbol = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = round(Color.rgb(10, 28, 48), 23)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = "SoundSweeper headphone icon"
        }
        heroHead.addView(symbol, LinearLayout.LayoutParams(dp(106), dp(106)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        info.addView(line("SOUND SWEEPER", 12f, secondary, true))
        deviceTitle = line("SoundSweeper", 20f, navy, true).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        info.addView(deviceTitle)
        status = line("Checking…", 14f, blue, true)
        info.addView(status)
        heroHead.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(heroHead)
        hero.addView(space(19))

        val batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batteryRow.addView(line("Battery", 15f, navy, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        batteryLabel = line("—", 21f, navy, true)
        batteryRow.addView(batteryLabel)
        hero.addView(batteryRow)
        batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(border)
        }
        hero.addView(batteryBar, LinearLayout.LayoutParams(-1, dp(7)).apply {
            topMargin = dp(10)
        })
        batteryInfo = line("Waiting for battery data", 12f, secondary).apply {
            setPadding(0, dp(9), 0, 0)
        }
        hero.addView(batteryInfo)
        root.addView(hero, gap(12))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("↻  Refresh", blue, white).apply {
            setOnClickListener {
                if (hasPermission()) refreshBluetooth() else requestBluetoothPermission()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9) })
        actions.addView(button("Bluetooth  ↗", white, navy).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions, gap(22))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(ice, 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("Overview", "Buttons", "ANC").forEachIndexed { page, name ->
            val tab = line(name, 14f, navy, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(13), dp(5), dp(13))
                isClickable = true
                isFocusable = true
                setOnClickListener { showPage(page) }
            }
            tabs.add(tab)
            nav.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav, gap(17))

        pageContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pageContent)
        scroll.addView(root)
        setContentView(scroll)
        showPage(0)
    }

    private fun showPage(page: Int) {
        selectedPage = page
        tabs.forEachIndexed { index, tab ->
            val selected = index == page
            tab.background = if (selected) round(white, 15) else null
            tab.setTextColor(if (selected) blue else secondary)
        }
        pageContent.removeAllViews()
        when (page) {
            1 -> showButtonPage()
            2 -> showNoisePage()
            else -> showOverviewPage()
        }
    }

    private fun showOverviewPage() {
        pageContent.addView(line("Device settings", 19f, navy, true), gap(12))
        pageContent.addView(detailRow("Button controls", "View factory controls and save draft preferences", "›").apply {
            setOnClickListener { showPage(1) }
        }, gap(10))
        pageContent.addView(detailRow("Noise cancellation", "ANC is controlled by the physical switch", "›").apply {
            setOnClickListener { showPage(2) }
        }, gap(10))
        pageContent.addView(detailRow("Hardware diagnostics", "Inspect Bluetooth services and test button events", "›").apply {
            setOnClickListener { openDiagnostics() }
        }, gap(23))

        pageContent.addView(line("Quick launch", 19f, navy, true), gap(12))
        pageContent.addView(detailRow("Spotify", "Open music and podcasts", "↗").apply {
            setOnClickListener { launchApp("com.spotify.music") }
        }, gap(9))
        pageContent.addView(detailRow("Apple Music", "Open Apple Music on Android", "↗").apply {
            setOnClickListener { launchApp("com.apple.android.music") }
        }, gap(9))
        pageContent.addView(detailRow("YouTube", "Open YouTube", "↗").apply {
            setOnClickListener { launchApp("com.google.android.youtube") }
        }, gap(16))
        pageContent.addView(line("Independent SoundSweeper prototype • v1.5", 12f, secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun showButtonPage() {
        pageContent.addView(line("Button preferences", 20f, navy, true), gap(8))
        pageContent.addView(note(
            "Draft only — Bluetooth audio does not provide a supported method to rewrite these physical button actions. Changes below are saved on your phone, but are NOT sent to the headset.",
            coral
        ), gap(16))

        val preferences = getSharedPreferences("button_drafts_v1", MODE_PRIVATE)
        buttonSpecs.forEach { spec ->
            val draft = preferences.getString(spec.id, null)
            val container = card()
            container.addView(line(spec.name, 15f, navy, true))
            container.addView(line("Factory: " + spec.factoryAction, 13f, secondary).apply {
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(line(
                if (draft == null) "Draft action: Not selected"
                else "Draft action: " + draft,
                13f, blue, true
            ).apply { setPadding(0, dp(8), 0, dp(10)) })
            container.addView(line("Choose draft action  ›", 13f, blue, true))
            container.isClickable = true
            container.isFocusable = true
            container.setOnClickListener { chooseDraft(spec) }
            pageContent.addView(container, gap(10))
        }

        pageContent.addView(button("Clear saved drafts", ice, navy).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear button drafts?")
                    .setMessage("This removes your locally saved preferences. It won't change the headphones.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        preferences.edit().clear().apply()
                        showPage(1)
                    }
                    .show()
            }
        }, gap(18))
        pageContent.addView(line(
            "The power button's long-press on/off function remains a hardware action and is not offered for reassignment.",
            13f, secondary
        ), gap(15))
        pageContent.addView(button("Test button events", blue, white).apply {
            setOnClickListener { openDiagnostics() }
        })
    }

    private fun chooseDraft(spec: ButtonSpec) {
        AlertDialog.Builder(this)
            .setTitle(spec.name)
            .setMessage("Select a preference to save locally. It will NOT remap your physical headphone button.")
            .setItems(actionChoices) { _, position ->
                getSharedPreferences("button_drafts_v1", MODE_PRIVATE)
                    .edit().putString(spec.id, actionChoices[position]).apply()
                showPage(1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoisePage() {
        pageContent.addView(line("Noise cancellation", 20f, navy, true), gap(8))
        pageContent.addView(note(
            "Hardware switch only. ANC on/off status is not exposed to this app, so this screen cannot detect or change it.",
            coral
        ), gap(14))
        val card = card()
        card.addView(line("ANC  •  PHYSICAL CONTROL", 12f, blue, true))
        card.addView(line("Use the switch on your headphones", 22f, navy, true).apply {
            setPadding(0, dp(13), 0, dp(13))
        })
        card.addView(line("1. Locate the ANC switch on an earcup.", 14f, secondary), gap(10))
        card.addView(line("2. Slide the switch to ON to enable noise cancellation.", 14f, secondary), gap(10))
        card.addView(line("3. Slide it to OFF to disable noise cancellation.", 14f, secondary), gap(15))
        card.addView(line("The headphone's ANC indicator light may show when the feature is active.", 13f, blue))
        pageContent.addView(card, gap(16))
        pageContent.addView(line("Why no ANC toggle?", 17f, navy, true), gap(8))
        pageContent.addView(line(
            "The documented SoundSweeper model has a physical ANC switch. No verified software command interface is available, so a digital toggle would be misleading.",
            14f, secondary
        ), gap(16))
        pageContent.addView(button("Inspect Bluetooth capabilities", blue, white).apply {
            setOnClickListener { openDiagnostics() }
        })
    }

    private fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }

    private fun detailRow(title: String, subtitle: String, trailing: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(16))
            background = round(white, 17, border)
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(line(title, 16f, navy, true))
        texts.addView(line(subtitle, 12f, secondary).apply {
            setPadding(0, dp(5), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(line(trailing, 20f, blue, true).apply {
            setPadding(dp(12), 0, 0, 0)
        })
        return row
    }

    private fun note(message: String, color: Int) =
        line(message, 13f, navy).apply {
            background = round(Color.rgb(255, 242, 238), 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setLineSpacing(dp(3).toFloat(), 1.1f)
        }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) startActivity(intent)
            else startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)))
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setMessage("Couldn't open that streaming app on this phone.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun line(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1.04f)
        }

    private fun button(value: String, fill: Int, foreground: Int) =
        line(value, 14f, foreground, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(16), dp(9), dp(16))
            background = round(fill, 16, if (fill == white) border else null)
            isClickable = true
            isFocusable = true
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(20), dp(18), dp(20))
        background = round(white, 22, border)
    }

    private fun round(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun gap(bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) refreshBluetooth()
    }

    private fun registerBluetoothBroadcasts() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BATTERY_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission") // Permission checked by receiver.
    private fun captureBattery(intent: Intent) {
        if (intent.action != BATTERY_ACTION &&
            intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            if (batteryDevice == device.address) {
                batteryDevice = null
                lastBattery = null
            }
            return
        }
        batteryDevice = device.address
        lastBattery = intent.getIntExtra(BATTERY_EXTRA, -1).takeIf { it in 0..100 }
    }

    @SuppressLint("MissingPermission") // Permission checked above.
    private fun refreshBluetooth() {
        if (!hasPermission()) {
            render("Permission needed", "SoundSweeper", coral, -1)
            return
        }
        val adapter = bluetooth.adapter
        if (adapter == null || !adapter.isEnabled) {
            render("Bluetooth off", "SoundSweeper", coral, -1)
            return
        }
        if (!a2dpRequested)
            a2dpRequested = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.A2DP)
        if (!headsetRequested)
            headsetRequested = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HEADSET)
        updateBluetoothView()
    }

    @SuppressLint("MissingPermission") // Permission checked above.
    private fun updateBluetoothView() {
        if (!hasPermission()) return
        try {
            val devices = listOfNotNull(a2dp, headset)
                .flatMap { it.connectedDevices }
                .distinctBy { it.address }
            val soundsweeper = devices.firstOrNull {
                val name = it.name.orEmpty().lowercase(Locale.ROOT)
                    .replace(" ", "").replace("-", "").replace("_", "")
                name.contains("soundsweeper") ||
                    (name.contains("volkano") && name.contains("sweeper"))
            }
            when {
                soundsweeper != null -> {
                    val level = if (soundsweeper.address == batteryDevice)
                        lastBattery ?: -1 else -1
                    render("●  Connected", soundsweeper.name ?: "SoundSweeper", mint, level)
                }
                devices.isNotEmpty() -> render("Other audio connected",
                    devices[0].name ?: "Another device", coral, -1)
                a2dp == null && headset == null ->
                    render("Checking audio…", "SoundSweeper", blue, -1)
                else -> render("Not connected", "SoundSweeper", secondary, -1)
            }
        } catch (_: SecurityException) {
            render("Permission needed", "SoundSweeper", coral, -1)
        }
    }

    private fun render(state: String, name: String, color: Int, batteryLevel: Int) {
        status.text = state
        status.setTextColor(color)
        deviceTitle.text = name
        if (batteryLevel in 0..100) {
            batteryLabel.text = "$batteryLevel%"
            batteryBar.visibility = View.VISIBLE
            batteryBar.progress = batteryLevel
            batteryBar.progressTintList =
                ColorStateList.valueOf(if (batteryLevel <= 20) coral else mint)
            batteryInfo.text = "Reported by your headphones"
        } else {
            batteryLabel.text = "—"
            batteryBar.visibility = View.GONE
            batteryInfo.text = "Battery level not available"
        }
    }

    companion object {
        private const val BATTERY_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
