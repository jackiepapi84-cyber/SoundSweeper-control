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
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Independent phone-side companion. Bluetooth monitoring reads Android's audio
 * connections and, when available, the connected device's reported battery.
 * EQ is attached ONLY to this app's own MediaPlayer audio session.
 */
class MainActivity : Activity() {

    private val background = Color.rgb(7, 17, 31)
    private val surface = Color.rgb(17, 38, 57)
    private val surfaceDeep = Color.rgb(12, 29, 46)
    private val border = Color.rgb(39, 72, 93)
    private val ice = Color.rgb(180, 231, 251)
    private val white = Color.rgb(242, 249, 252)
    private val muted = Color.rgb(162, 186, 204)
    private val coral = Color.rgb(255, 142, 123)
    private val mint = Color.rgb(116, 227, 189)

    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var deviceLabel: TextView
    private lateinit var batteryPercent: TextView
    private lateinit var batteryExplanation: TextView
    private lateinit var batteryProgress: ProgressBar
    private lateinit var refreshButton: TextView

    private lateinit var songTitle: TextView
    private lateinit var playbackDetail: TextView
    private lateinit var playButton: TextView
    private lateinit var effectsDetail: TextView
    private lateinit var bassSeek: SeekBar
    private lateinit var trebleSeek: SeekBar
    private lateinit var bassValue: TextView
    private lateinit var trebleValue: TextView
    private val presetButtons = ArrayList<TextView>()

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var a2dpRequested = false
    private var headsetRequested = false
    private var active = false
    private var receiverRegistered = false
    private var askedForPermission = false
    private val handler = Handler(Looper.getMainLooper())
    private val retryRead = Runnable { if (active) refreshBluetooth() }

    private var player: MediaPlayer? = null
    private var playerReady = false
    private var preparing = false
    private var equalizer: Equalizer? = null
    private var selectedUri: Uri? = null
    private var bassLevel = 0
    private var trebleLevel = 0
    private var currentPreset = 0

    // Battery updates are a non-public Android broadcast and are not guaranteed
    // to be delivered to third-party apps. No percentage is fabricated.
    private var batteryForDevice: String? = null
    private var lastReportedBattery: Int? = null

    private val manager: BluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!active) {
                manager.adapter?.closeProfileProxy(profile, proxy)
                return
            }
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy
                BluetoothProfile.HEADSET -> headset = proxy
            }
            updateBluetoothDisplay()
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
            if (intent.action == BATTERY_CHANGED_ACTION && canReadBluetooth()) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                val level = intent.getIntExtra(BATTERY_LEVEL_EXTRA, -1)
                if (device != null) {
                    batteryForDevice = device.address
                    lastReportedBattery = level.takeIf { it in 0..100 }
                }
            }
            refreshBluetooth()
            handler.removeCallbacks(retryRead)
            handler.postDelayed(retryRead, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        buildInterface()
    }

    override fun onStart() {
        super.onStart()
        active = true
        registerBluetoothEvents()
        refreshBluetooth()
        if (!canReadBluetooth() && !askedForPermission) {
            askedForPermission = true
            requestBluetooth()
        }
    }

    override fun onResume() {
        super.onResume()
        if (active) refreshBluetooth()
    }

    override fun onStop() {
        active = false
        handler.removeCallbacks(retryRead)
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

        // This is a foreground-only local player; don't play invisibly.
        if (playerReady && player?.isPlaying == true) {
            player?.pause()
            playButton.text = "▶  Play"
            playbackDetail.text = "Paused when the app went to the background."
        }
        super.onStop()
    }

    override fun onDestroy() {
        disposePlayer()
        super.onDestroy()
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(32), dp(22), dp(36))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bar = insets.getInsets(WindowInsets.Type.systemBars())
                root.setPadding(dp(22), bar.top + dp(24), dp(22), bar.bottom + dp(36))
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }

        root.addView(text("SOUNDSWEEPER   /   STUDIO", 12f, ice, bold = true))
        root.addView(text("Hear more.\nControl more.", 34f, white, bold = true).apply {
            setPadding(0, dp(13), 0, dp(10))
        })
        root.addView(text("VOLKANO  •  COMPANION APP  •  V1.2", 12f, muted))
        root.addView(spacer(24))

        val hero = card()
        hero.addView(text("●  DEVICE STATUS", 12f, ice, bold = true))
        statusText = text("Checking Bluetooth…", 27f, white, bold = true).apply {
            setPadding(0, dp(14), 0, dp(6))
        }
        statusDetail = text("Checking audio devices on this phone.", 14f, muted)
        deviceLabel = text("SoundSweeper", 18f, white, bold = true).apply {
            setPadding(0, dp(17), 0, dp(3))
        }
        hero.addView(statusText)
        hero.addView(statusDetail)
        hero.addView(deviceLabel)
        hero.addView(text("BLUETOOTH AUDIO", 11f, muted, bold = true))

        val batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(8))
        }
        batteryRow.addView(text("HEADPHONE BATTERY", 12f, ice, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        batteryPercent = text("—", 19f, white, bold = true)
        batteryRow.addView(batteryPercent)
        hero.addView(batteryRow)
        batteryProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(border)
            visibility = View.GONE
        }
        hero.addView(batteryProgress, LinearLayout.LayoutParams(-1, dp(9)))
        batteryExplanation = text("Waiting for a battery report.", 12f, muted).apply {
            setPadding(0, dp(10), 0, 0)
        }
        hero.addView(batteryExplanation)
        root.addView(hero)

        refreshButton = button("↻   Refresh connection", ice, background).apply {
            setOnClickListener {
                if (!canReadBluetooth()) requestBluetooth() else refreshBluetooth()
            }
        }
        root.addView(refreshButton, gap(12))
        root.addView(button("Bluetooth settings  ↗", surfaceDeep, white).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }, gap(24))

        root.addView(sectionTitle("LOCAL MUSIC PLAYER", "Play a file from your phone"))
        val playerCard = card()
        songTitle = text("No track selected", 20f, white, bold = true)
        playbackDetail = text("Choose an audio file to try your sound presets.", 13f, muted).apply {
            setPadding(0, dp(7), 0, dp(18))
        }
        playerCard.addView(songTitle)
        playerCard.addView(playbackDetail)

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(button("Choose track", ice, background).apply {
            setOnClickListener { chooseTrack() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(9) })
        playButton = button("▶  Play", surfaceDeep, white).apply {
            setOnClickListener { togglePlayback() }
        }
        buttonRow.addView(playButton, LinearLayout.LayoutParams(0, -2, 1f))
        playerCard.addView(buttonRow)
        root.addView(playerCard, gap(22))

        root.addView(sectionTitle("SOUND SHAPING", "Effects for audio played in this app"))
        val effectsCard = card()
        effectsCard.addView(text("QUICK PRESETS", 11f, ice, bold = true))
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(13), 0, dp(18))
        }
        arrayOf("Balanced", "Bass Boost", "Clear Voice").forEachIndexed { index, name ->
            val chip = text(name, 12f, white, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(13), dp(4), dp(13))
                isClickable = true
                isFocusable = true
                setOnClickListener { selectPreset(index) }
            }
            presets.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(7)
            })
            presetButtons.add(chip)
        }
        effectsCard.addView(presets)

        bassValue = text("0", 13f, ice, bold = true)
        effectsCard.addView(sliderHeading("BASS", bassValue))
        bassSeek = slider().apply {
            setOnSeekBarChangeListener(sliderListener { newValue ->
                bassLevel = newValue
                bassValue.text = formatDb(newValue)
                setPresetCustom()
                applySoundControls()
            })
        }
        effectsCard.addView(bassSeek)

        trebleValue = text("0", 13f, ice, bold = true)
        effectsCard.addView(sliderHeading("TREBLE", trebleValue).apply {
            setPadding(0, dp(15), 0, dp(5))
        })
        trebleSeek = slider().apply {
            setOnSeekBarChangeListener(sliderListener { newValue ->
                trebleLevel = newValue
                trebleValue.text = formatDb(newValue)
                setPresetCustom()
                applySoundControls()
            })
        }
        effectsCard.addView(trebleSeek)
        effectsDetail = text("Choose a track to activate audio effects.", 12f, muted).apply {
            setPadding(0, dp(16), 0, 0)
        }
        effectsCard.addView(effectsDetail)
        root.addView(effectsCard)

        root.addView(text("HOW IT WORKS", 12f, ice, bold = true).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        root.addView(text(
            "Bluetooth status and battery readings come from Android. A battery percentage appears only when your headphones report one.\n\n" +
                "Sound presets work only with tracks played inside this app. They cannot alter Spotify, YouTube, the headphones' built-in EQ, or ANC.",
            13f, muted
        ))

        scroll.addView(root)
        setContentView(scroll)
        selectPreset(0)
    }

    private fun sectionTitle(heading: String, subheading: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(heading, 12f, ice, bold = true))
            addView(text(subheading, 14f, muted).apply {
                setPadding(0, dp(5), 0, dp(12))
            })
        }

    private fun sliderHeading(label: String, value: TextView) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 12f, ice, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
        }

    private fun slider() = SeekBar(this).apply {
        max = 12
        progress = 6
        progressTintList = ColorStateList.valueOf(ice)
        thumbTintList = ColorStateList.valueOf(coral)
        progressBackgroundTintList = ColorStateList.valueOf(border)
    }

    private fun sliderListener(changed: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) changed(progress - 6)
        }
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    }

    private fun selectPreset(index: Int) {
        currentPreset = index
        val levels = when (index) {
            1 -> 5 to 1      // Bass Boost
            2 -> -2 to 4     // Clear Voice
            else -> 0 to 0   // Balanced
        }
        bassLevel = levels.first
        trebleLevel = levels.second
        bassSeek.progress = bassLevel + 6
        trebleSeek.progress = trebleLevel + 6
        bassValue.text = formatDb(bassLevel)
        trebleValue.text = formatDb(trebleLevel)
        updatePresetButtons()
        applySoundControls()
    }

    private fun setPresetCustom() {
        currentPreset = -1
        updatePresetButtons()
    }

    private fun updatePresetButtons() {
        presetButtons.forEachIndexed { index, chip ->
            val selected = currentPreset == index
            chip.background = rounded(if (selected) coral else surfaceDeep, 14,
                if (selected) null else border)
            chip.setTextColor(if (selected) background else white)
        }
    }

    private fun formatDb(value: Int) = if (value > 0) "+$value" else "$value"

    private fun chooseTrack() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQUEST_AUDIO)
    }

    @Deprecated("Uses the platform file picker without requiring storage permissions.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        songTitle.text = resolveAudioName(uri)
        startTrack(uri)
    }

    private fun resolveAudioName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "Selected audio")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected audio"
        }
    }

    private fun startTrack(uri: Uri) {
        disposePlayer()
        preparing = true
        playerReady = false
        playbackDetail.text = "Loading selected audio…"
        playButton.text = "Loading…"
        try {
            val newPlayer = MediaPlayer()
            player = newPlayer
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            newPlayer.setDataSource(this, uri)
            newPlayer.setOnPreparedListener { readyPlayer ->
                if (player !== readyPlayer) return@setOnPreparedListener
                playerReady = true
                preparing = false
                createEqualizer(readyPlayer.audioSessionId)
                readyPlayer.start()
                playButton.text = "Ⅱ  Pause"
                playbackDetail.text = "Playing locally through Android's current audio output."
            }
            newPlayer.setOnCompletionListener {
                playButton.text = "▶  Replay"
                playbackDetail.text = "Playback finished."
            }
            newPlayer.setOnErrorListener { _, _, _ ->
                playbackDetail.text = "Cannot play this audio file."
                playButton.text = "▶  Play"
                playerReady = false
                preparing = false
                true
            }
            newPlayer.prepareAsync()
        } catch (_: Exception) {
            disposePlayer()
            playbackDetail.text = "Unable to open that file. Try another audio file."
            playButton.text = "▶  Play"
        }
    }

    private fun togglePlayback() {
        val current = player
        when {
            preparing -> playbackDetail.text = "Please wait for the file to load."
            current == null -> chooseTrack()
            !playerReady -> selectedUri?.let { startTrack(it) }
            current.isPlaying -> {
                current.pause()
                playButton.text = "▶  Play"
                playbackDetail.text = "Paused."
            }
            else -> {
                current.start()
                playButton.text = "Ⅱ  Pause"
                playbackDetail.text = "Playing locally through Android's current audio output."
            }
        }
    }

    private fun createEqualizer(audioSession: Int) {
        equalizer?.release()
        equalizer = null
        try {
            equalizer = Equalizer(0, audioSession).apply { enabled = true }
            effectsDetail.text = "EQ enabled for the selected local track."
            applySoundControls()
        } catch (_: Exception) {
            equalizer = null
            effectsDetail.text = "Audio EQ isn't supported by this phone's audio engine."
        }
    }

    private fun applySoundControls() {
        val effect = equalizer ?: return
        try {
            val limits = effect.bandLevelRange
            val min = limits[0].toInt()
            val max = limits[1].toInt()
            for (i in 0 until effect.numberOfBands.toInt()) {
                val band = i.toShort()
                val hz = effect.getCenterFreq(band) / 1000
                val amount = when {
                    hz < 400 -> bassLevel
                    hz >= 2500 -> trebleLevel
                    else -> 0
                }
                val target = (amount / 6.0 * 850).roundToInt().coerceIn(min, max)
                effect.setBandLevel(band, target.toShort())
            }
            effectsDetail.text = "Equalizer active on in-app playback only."
        } catch (_: Exception) {
            effectsDetail.text = "This device could not apply the selected EQ settings."
        }
    }

    private fun disposePlayer() {
        equalizer?.release()
        equalizer = null
        player?.let { old ->
            try {
                old.reset()
                old.release()
            } catch (_: Exception) {
                // Allow a later track to load even if the old decoder failed.
            }
        }
        player = null
        playerReady = false
        preparing = false
    }

    private fun registerBluetoothEvents() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // System-only on many Android builds; listening does not ensure delivery.
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

    private fun canReadBluetooth(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canReadBluetooth()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) refreshBluetooth()
    }

    @SuppressLint("MissingPermission") // Each call is permission-gated.
    private fun refreshBluetooth() {
        if (!canReadBluetooth()) {
            renderConnection("Permission needed", "Allow Nearby devices to read audio connections.", coral)
            return
        }
        val adapter = manager.adapter
        if (adapter == null) {
            renderConnection("Unavailable", "This phone has no Bluetooth adapter.", coral)
            return
        }
        if (!adapter.isEnabled) {
            renderConnection("Bluetooth off", "Turn Bluetooth on to connect your headphones.", coral)
            return
        }
        if (!a2dpRequested) {
            a2dpRequested = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.A2DP)
        }
        if (!headsetRequested) {
            headsetRequested = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HEADSET)
        }
        updateBluetoothDisplay()
    }

    @SuppressLint("MissingPermission") // Each call is permission-gated.
    private fun updateBluetoothDisplay() {
        if (!canReadBluetooth()) return
        try {
            val connected = listOfNotNull(a2dp, headset)
                .flatMap { it.connectedDevices }
                .distinctBy { it.address }
            val soundSweeper = connected.firstOrNull {
                val name = it.name.orEmpty().lowercase(Locale.ROOT)
                    .replace(Regex("[\\s_-]"), "")
                name.contains("soundsweeper") ||
                    (name.contains("volkano") && name.contains("sweeper"))
            }

            when {
                soundSweeper != null -> {
                    val name = soundSweeper.name.orEmpty().ifBlank { "SoundSweeper" }
                    renderConnection("Connected", "Ready for Bluetooth audio.", mint, name)
                    val level = if (batteryForDevice == soundSweeper.address) {
                        lastReportedBattery ?: -1
                    } else -1
                    showBattery(level)
                }
                connected.isNotEmpty() -> {
                    val other = connected.first().name.orEmpty().ifBlank { "Another audio device" }
                    renderConnection("Other audio device", "SoundSweeper is not the connected audio device.", coral, other)
                }
                a2dp == null && headset == null ->
                    renderConnection("Checking…", "Waiting for Android audio services.", ice)
                else ->
                    renderConnection("Not connected", "Connect your headphones in Bluetooth settings.", coral)
            }
        } catch (_: SecurityException) {
            renderConnection("Permission needed", "Allow Nearby devices to check headphones.", coral)
        }
    }

    private fun showBattery(level: Int) {
        if (level in 0..100) {
            batteryPercent.text = "$level%"
            batteryProgress.visibility = View.VISIBLE
            batteryProgress.progress = level
            batteryProgress.progressTintList = ColorStateList.valueOf(
                if (level <= 20) coral else mint
            )
            batteryExplanation.text = "Battery level reported by your headphones."
        } else {
            batteryPercent.text = "Unavailable"
            batteryProgress.visibility = View.GONE
            batteryExplanation.text = "This headphone connection isn't reporting battery data to Android."
        }
    }

    private fun renderConnection(title: String, description: String, color: Int, name: String? = null) {
        statusText.text = title
        statusText.setTextColor(color)
        statusDetail.text = description
        deviceLabel.text = name ?: "SoundSweeper"
        refreshButton.text = if (canReadBluetooth()) "↻   Refresh connection"
            else "Allow Bluetooth access"
        showBattery(-1)
        if (name == null) batteryExplanation.text = "Connect SoundSweeper to check battery reporting."
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(
                if (bold) "sans-serif-medium" else "sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
            setLineSpacing(dp(2).toFloat(), 1.08f)
        }

    private fun button(value: String, bg: Int, fg: Int) = text(value, 15f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(16), dp(10), dp(16))
        background = rounded(bg, 17, if (bg == ice) null else border)
        isClickable = true
        isFocusable = true
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(21), dp(22), dp(21), dp(23))
        background = rounded(surface, 24, border)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun gap(bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_BLUETOOTH = 1001
        private const val REQUEST_AUDIO = 1002
        private const val BATTERY_CHANGED_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BATTERY_LEVEL_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
