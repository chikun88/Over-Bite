package com.chihaya.mu3androidtx

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.hardware.input.InputManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Button
import android.widget.TextView
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MU3_TX_FRAME_LEN = 21
private const val TX_MIN_GUARD_MS = 6L
private const val TX_UI_UPDATE_INTERVAL_MS = 120L
private const val TX_RX_DRAIN_MS = 1
private const val TX_STOP_JOIN_MIN_MS = 300L
private const val TX_STOP_JOIN_MAX_MS = 800L
// 単発のUSB送受信エラーで操縦リンク全体を落とさないための耐性設定。
// しきい値に達するまでは短いバックオフを挟んでリンクを維持し、
// 連続で失敗した場合のみポートを閉じて再接続を促す。
private const val TX_MAX_CONSECUTIVE_ERRORS = 6
private const val TX_ERROR_BACKOFF_MS = 12L

/**
 * MU3_20260501_Stable の send 側を Android に移植した版。
 *
 * 横画面専用の操縦ダッシュボードUI。
 * Androidスマホ + PS5/DualSenseコントローラー + USB OTG + MU-3 USBシリアル基板で使う想定。
 *
 * Ver. Mapping UI:
 * - アプリ内の「割当」から、物理コントローラーの各ボタンの送信先bitを変更できる。
 * - 画面上ボタンは、表示名・HOLD/TOGGLE・送信先bitを変更できる。
 * - 設定は SharedPreferences に保存され、次回起動時も残る。
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val actionUsbPermission = "com.chihaya.overbite2.USB_PERMISSION"
    private val configPrefsName = "control_mapping_v2"
    private val commPrefsName = "mu3_comm_settings_v1"
    private val savedNavigation = SavedNavigationCommand()
    private val anglePresetCommand = AnglePresetCommand()
    private var savedNavigationStatus: TextView? = null
    private var savedNavigationTabIndex = 0
    private var savedNavigationMap: SavedNavigationMapView? = null
    private var savedNavigationSideLabel: TextView? = null
    // The field is remembered across launches: it is set once before a match
    // and getting it wrong sends the robot into the other team's half.
    private var savedNavigationRightField = false
    private val maxScreenButtons = 12

    private lateinit var usbManager: UsbManager
    @Volatile private var usbConnection: UsbDeviceConnection? = null
    @Volatile private var serialPort: UsbSerialPort? = null

    private var isDarkMode = false
    private lateinit var colors: AppColors

    private val mainHandler = Handler(Looper.getMainLooper())
    private val diagExecutor = Executors.newSingleThreadExecutor()
    private val serialWriteLock = Any()
    private val serialOpenBusy = AtomicBoolean(false)
    private val diagBusy = AtomicBoolean(false)
    private val neutralBurstBusy = AtomicBoolean(false)
    private val senderExecutor = Executors.newSingleThreadScheduledExecutor()
    private var sendTask: ScheduledFuture<*>? = null
    private var senderThread: Thread? = null
    private val senderGeneration = AtomicInteger(0)
    
    @Volatile private var isSending = false

    @Volatile private var currentBaudRate = 19200
    @Volatile private var txIntervalMs = 50L
    @Volatile private var connectedDeviceId = -1
    @Volatile private var pendingOpenDeviceId = -1
    private var mu3LinePreset = "SAFE"
    @Volatile private var txErrorCount = 0
    @Volatile private var lastTxError = ""

    private val pad = PadState(maxScreenButtons)
    private val configLock = Any()
    private lateinit var controlConfig: ControlConfig

    // ===== Telemetry / Black Box =====
    // 実際にMU-3へ送った7バイトpayload・送信間隔・ACK(応答)有無・リンクイベントを
    // 無確保リングバッファに記録する「ブラックボックス」。試合後の再生・分析に使う。
    private val blackBox = BlackBox(36000)
    @Volatile private var lastKnownRssi = -1
    private var telemetryTabIndex = -1
    private var telemetryGraph: LiveGraphView? = null
    private var telemetryStatsText: TextView? = null
    private var blackBoxStateText: TextView? = null
    private var blackBoxRecButton: TextView? = null
    private var replaySeek: SeekBar? = null
    private var replayStateText: TextView? = null
    private var replayPadView: ReplayPadView? = null
    private var replayPlaying = false
    private var replayLogical = 0
    private val replayFrameHolder = BbFrame()
    private val telemetryStatsBuf = DoubleArray(4)
    private val telemetryRefresh = object : Runnable {
        override fun run() {
            refreshTelemetryUi()
            mainHandler.postDelayed(this, 110L)
        }
    }
    private val replayTick = object : Runnable {
        override fun run() {
            stepReplay()
            if (replayPlaying) mainHandler.postDelayed(this, 60L)
        }
    }

    // ===== AI COPILOT =====
    // 端末内（オンデバイス／クラウド不要）で動く知能レイヤー。
    // 送信スレッドが吐く確保ゼロのライブ信号から、適応オートチューン・異常検知・
    // 走行AI解析・マクロ自動操縦を提供する。AIタブの心臓部。
    private var copilotTabIndex = -1

    // --- ライブ信号（送信スレッドが確保ゼロで更新、読み出しはメイン） ---
    @Volatile private var aiAckEwma = -1.0     // ACK率(リンク健全度) 指数移動平均 0..1, -1=未測定
    @Volatile private var aiGapEwma = 0.0      // 実測送信間隔ms EWMA
    @Volatile private var aiGapVarEwma = 0.0   // 送信間隔の分散EWMA(=ジッタ^2)
    @Volatile private var aiErrEwma = 0.0      // 送信エラー率 EWMA 0..1
    @Volatile private var aiLastTxMs = 0L      // 最後に送信したelapsedRealtime
    @Volatile private var aiTxTotal = 0L       // ライブ信号に取り込んだ送信回数

    // --- 適応オートチューン ---
    private var autoTuneOn = false
    private var autoTuneStableStreak = 0
    private var autoTuneSafeFloorMs = 10L      // これまで安定が確認できた最速間隔
    private var autoTuneLastActionMs = 0L
    @Volatile private var aiRecommendedIntervalMs = 0L
    @Volatile private var aiSuppressStallUntilMs = 0L  // 送信再起動直後の誤検知を抑制
    private var autoTuneButton: TextView? = null
    private var autoTuneText: TextView? = null

    // --- 異常検知センチネル ---
    private var aiSentinelText: TextView? = null
    private var aiSentinelLogText: TextView? = null
    private var aiGaugeView: AiGaugeView? = null
    private val aiSentinelLog = ArrayDeque<String>()
    private var aiLastSeverity = -1            // 直近通知した深刻度。悪化時だけログする
    private var aiHealthGrade = "--"
    @Volatile private var aiHealthScore = -1.0 // 0..100 総合リンクスコア

    // --- マクロ自動操縦 ---
    private val macroRec = MacroRecorder(8000)
    private val autopilotLock = Any()
    @Volatile private var autopilotActive = false
    private val autopilotPayload = PadState.neutralPayload()
    private var autopilotThread: Thread? = null
    @Volatile private var autopilotLoop = false
    @Volatile private var autopilotProgress = 0
    private var macroStateText: TextView? = null
    private var autopilotButton: TextView? = null
    private var learnButton: TextView? = null
    private var loopButton: TextView? = null

    // --- AI解析レポート ---
    private var aiReportText: TextView? = null
    private val aiReportFrame = BbFrame()

    // AIコパイロットの心拍はタブを開いている時かオートチューン稼働中だけ回す（普段は完全停止して軽量化）
    private var aiTickScheduled = false

    private val aiTick = object : Runnable {
        override fun run() {
            if (!aiShouldRun()) { aiTickScheduled = false; return }
            aiEvaluate()
            mainHandler.postDelayed(this, 500L)
        }
    }

    /** AI解析を動かすべき条件：コパイロットタブ表示中、または適応オートチューン稼働中。 */
    private fun aiShouldRun(): Boolean =
        selectedTabIndex == copilotTabIndex || autoTuneOn

    /** 条件が揃っている時だけ心拍を起動し、不要なら停止する（多重起動を防ぐ）。 */
    private fun ensureAiHeartbeat() {
        if (aiShouldRun()) {
            if (!aiTickScheduled) {
                aiTickScheduled = true
                mainHandler.post(aiTick)
            }
        } else if (aiTickScheduled) {
            aiTickScheduled = false
            mainHandler.removeCallbacks(aiTick)
        }
    }

    private lateinit var statusText: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var sendBadge: TextView
    private lateinit var payloadText: TextView
    private lateinit var physicalInputText: TextView
    private lateinit var logText: TextView
    private lateinit var txIntervalInput: EditText
    private lateinit var txIntervalSeekBar: SeekBar
    private lateinit var baudRateInput: EditText
    private lateinit var commStatusText: TextView
    private lateinit var featureGrid: GridLayout
    private lateinit var mu3CommandInput: EditText
    private lateinit var linkUiInput: EditText
    private lateinit var linkGiInput: EditText
    private lateinit var linkChInput: EditText
    private lateinit var linkEiInput: EditText
    private lateinit var linkDiInput: EditText
    private lateinit var scanStartInput: EditText
    private lateinit var scanEndInput: EditText
    private lateinit var mu3DiagLogText: TextView
    private lateinit var mu3RssiText: TextView
    private lateinit var mu3PacketText: TextView
    private lateinit var mu3ScanText: TextView
    private lateinit var mu3CommandResultText: TextView
    private val mu3DiagLogBuffer = StringBuilder()
    private val statusHistory = mutableListOf<String>()
    private lateinit var chromeContainer: LinearLayout
    private lateinit var tabScrollView: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var contentFrame: LinearLayout
    private lateinit var compactChromeBar: LinearLayout
    private lateinit var compactStatusBadge: TextView
    private lateinit var compactConnectionBadge: TextView
    private lateinit var compactTxBadge: TextView
    private lateinit var compactStopButton: TextView
    private var dashboardUsbChip: TextView? = null
    private var dashboardTxChip: TextView? = null
    private var isChromeCollapsed = false
    private var selectedTabIndex = 0
    private val tabLabels = mutableListOf<String>()
    private val tabButtons = mutableListOf<TextView>()
    private val tabViews = mutableListOf<View>()

    private val featureViews = mutableMapOf<Int, FeatureTileViews>()
    private var mappingDialog: AlertDialog? = null
    private var mappingEditorScrollView: ScrollView? = null
    private val controllerInputViews = mutableMapOf<Int, View>()
    private val screenButtonViews = mutableMapOf<Int, View>()

    private val outputTargets = listOf(
        PayloadTarget("NONE", -1, -1, "未割当 / OFF"),
        PayloadTarget("P4_B7_DOWN", 4, 7, "十字↓ / payload[4] bit7"),
        PayloadTarget("P4_B6_UP", 4, 6, "十字↑ / payload[4] bit6"),
        PayloadTarget("P4_B5_CROSS", 4, 5, "× / payload[4] bit5"),
        PayloadTarget("P4_B4_TRIANGLE", 4, 4, "△ / payload[4] bit4"),
        PayloadTarget("P4_B3_L1", 4, 3, "L1 / payload[4] bit3"),
        PayloadTarget("P4_B2_R1", 4, 2, "R1 / payload[4] bit2"),
        PayloadTarget("P4_B1_L2", 4, 1, "L2 / payload[4] bit1"),
        PayloadTarget("P4_B0_R2", 4, 0, "R2 / payload[4] bit0"),
        PayloadTarget("P5_B7_R3", 5, 7, "R3 / payload[5] bit7"),
        PayloadTarget("P5_B6_L3", 5, 6, "L3 / payload[5] bit6"),
        PayloadTarget("P5_B5_OPTIONS", 5, 5, "OPTIONS / payload[5] bit5"),
        PayloadTarget("P5_B4_SHARE", 5, 4, "SHARE / payload[5] bit4"),
        PayloadTarget("P5_B3_SQUARE", 5, 3, "□ / payload[5] bit3"),
        PayloadTarget("P5_B2_CIRCLE", 5, 2, "○ / payload[5] bit2"),
        PayloadTarget("P5_B1_LEFT", 5, 1, "十字← / payload[5] bit1"),
        PayloadTarget("P5_B0_RIGHT", 5, 0, "十字→ / payload[5] bit0"),
        PayloadTarget("P6_B0_PS", 6, 0, "PS / payload[6] bit0"),
        PayloadTarget("P6_B1_EXTRA1", 6, 1, "追加1 / payload[6] bit1"),
        PayloadTarget("P6_B2_EXTRA2", 6, 2, "追加2 / payload[6] bit2"),
        PayloadTarget("P6_B3_EXTRA3", 6, 3, "追加3 / payload[6] bit3"),
        PayloadTarget("P6_B4_EXTRA4", 6, 4, "追加4 / payload[6] bit4"),
        PayloadTarget("P6_B5_EXTRA5", 6, 5, "追加5 / payload[6] bit5"),
        PayloadTarget("P6_B6_EXTRA6", 6, 6, "追加6 / payload[6] bit6"),
        PayloadTarget("P6_B7_EXTRA7", 6, 7, "追加7 / payload[6] bit7")
    )
    private val targetsById = outputTargets.associateBy { it.id }

    private val controllerInputs = listOf(
        ControllerInput(KeyEvent.KEYCODE_BUTTON_A, "× / CROSS"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_B, "○ / CIRCLE"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_Y, "△ / TRIANGLE"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_X, "□ / SQUARE"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_L1, "L1 / BUMPER"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_R1, "R1 / BUMPER"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_L2, "L2 / TRIGGER"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_R2, "R2 / TRIGGER"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_SELECT, "CREATE / SHARE"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_START, "OPTIONS"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_MODE, "PS BUTTON"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_1, "TOUCHPAD CLICK"),
        ControllerInput(KeyEvent.KEYCODE_MUTE, "MUTE BUTTON"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3 / STICK"),
        ControllerInput(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3 / STICK"),
        ControllerInput(KeyEvent.KEYCODE_DPAD_UP, "UP"),
        ControllerInput(KeyEvent.KEYCODE_DPAD_DOWN, "DOWN"),
        ControllerInput(KeyEvent.KEYCODE_DPAD_LEFT, "LEFT"),
        ControllerInput(KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT")
    )
    private val controllerInputByKeyCode = controllerInputs.associateBy { it.id }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                if (device != null) handleUsbAttached(device)
                return
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (device == null || connectedDeviceId == device.deviceId || pendingOpenDeviceId == device.deviceId) {
                    handleUsbDetached(device)
                }
                return
            }
            if (intent.action != actionUsbPermission) return

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                (device != null && usbManager.hasPermission(device))
            if (granted && device != null) {
                openSerialForDeviceAsync(device)
            } else {
                setStatus("USB権限が許可されませんでした")
            }
        }
    }

    // A Bluetooth controller that drops out sends no "release" events, so the
    // last stick deflection would keep being transmitted. Neutralize on removal.
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            releasePhysicalInputs("コントローラー切断：スティック・ボタンをニュートラルに戻しました")
        }
    }

    private fun releasePhysicalInputs(status: String?) {
        synchronized(pad) { pad.clearPhysical() }
        updateUiState()
        if (status != null) setStatus(status)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        loadCommSettings()
        colors = if (isDarkMode) AppColors.dark(this) else AppColors.light(this)

        applyModernSystemChrome()
        enableImmersiveLandscape()

        controlConfig = loadControlConfig()
        registerUsbReceiver()
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputDeviceListener, mainHandler)
        buildUi()
        updateConnectionBadges()
        updateUiState()
        handleInitialUsbIntent(intent)
    }

    override fun onDestroy() {
        stopSending()
        stopAutopilotInternal(neutralize = false)
        closeSerial()
        mainHandler.removeCallbacks(telemetryRefresh)
        mainHandler.removeCallbacks(replayTick)
        mainHandler.removeCallbacks(aiTick)
        senderExecutor.shutdownNow()
        diagExecutor.shutdownNow()
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputDeviceListener)
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        stopAutopilotInternal(neutralize = false)
        stopSending()
        sendNeutralBurstAsync(5)
        mainHandler.removeCallbacks(telemetryRefresh)
        mainHandler.removeCallbacks(aiTick)
        aiTickScheduled = false
        if (replayPlaying) {
            replayPlaying = false
            mainHandler.removeCallbacks(replayTick)
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionBadges()
        updateUiState()
        updateCommUi()
        updateRecButton()
        mainHandler.removeCallbacks(telemetryRefresh)
        mainHandler.post(telemetryRefresh)
        mainHandler.removeCallbacks(aiTick)
        aiTickScheduled = false
        ensureAiHeartbeat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInitialUsbIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveLandscape()
        } else {
            // Key-up events go to whichever window has focus (dialog, shade);
            // without this a button held at that moment stays latched.
            releasePhysicalInputs(null)
        }
    }

    private fun enableImmersiveLandscape() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.decorView.windowInsetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    @Suppress("DEPRECATION")
    private fun applyModernSystemChrome() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = colors.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.decorView.windowInsetsController
            if (controller != null) {
                if (!isDarkMode) {
                    controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                } else {
                    controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (!isDarkMode) {
                var flags = window.decorView.systemUiVisibility
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun handleInitialUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = usbDeviceFromIntent(intent) ?: return
        handleUsbAttached(device)
    }

    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun handleUsbAttached(device: UsbDevice) {
        if (connectedDeviceId == device.deviceId && serialPort != null) return
        // USBハブ経由のゲームパッド等、シリアルでない機器の接続で
        // 操縦中のMU-3リンクを閉じてはいけない。
        if (serialProber().probeDevice(device) == null) return
        if (serialPort != null) {
            setStatus("別のUSBシリアル機器を検出（接続中のMU-3を維持します）")
            return
        }
        if (usbManager.hasPermission(device)) {
            setStatus("USB attached: opening serial")
            openSerialForDeviceAsync(device)
        } else {
            requestUsbPermission(device)
        }
    }

    private fun handleUsbDetached(device: UsbDevice?) {
        val label = device?.deviceName?.takeLast(12) ?: "USB"
        setStatus("USB detached: closing $label")
        diagExecutor.execute {
            closeSerial()
            mainHandler.post {
                setStatus("USB detached: serial closed")
                updateCommUi()
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        // The USB service fills EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED into this
        // intent, which an immutable PendingIntent silently drops (always "denied").
        // The intent is explicit (setPackage), so a mutable one is safe.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(actionUsbPermission).setPackage(packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
        setStatus("USB permission requested: VID:${String.format(Locale.US, "%04X", device.vendorId)}")
    }

    private fun topBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(10), dp(6))
            background = tonalSurfaceDrawable(colors.surface, colors.accentPrimary, 18f, 0.88f)
            elevation = 0f
            addView(headerBlock(), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(statusCardSmall())
            addSpaceHorizontal(6)
            addView(chromeButton("FOCUS", colors.accentNeutral) { setChromeCollapsed(true) })
        }
    }

    private fun tabButtons(): View {
        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = tonalSurfaceDrawable(colors.surface, colors.accentNeutral, 18f, 0.52f)
            elevation = 0f
        }
        tabScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(tabContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return tabScrollView
    }

    private fun buildUi() {
        tabButtons.clear()
        tabLabels.clear()
        tabViews.clear()
        val root = FrameLayout(this).apply {
            background = OverbiteBackgroundDrawable()
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
        }
        root.addView(mainColumn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        chromeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
        }

        chromeContainer.addView(topBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6))
        })

        chromeContainer.addView(tabButtons(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6))
        })
        mainColumn.addView(chromeContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Content Area
        contentFrame = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            clipToPadding = false
        }
        mainColumn.addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        compactChromeBar = createCompactChromeBar()
        root.addView(compactChromeBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        compactChromeBar.visibility = View.GONE

        // Tabs
        addTab("DASHBOARD", createDashboardTab())
        addTab("VIRTUAL BTNS", createVirtualBtnsTab())
        addTab("SYSTEM", createSystemTab())
        addTab("MU-3 TEST", createMu3TestTab())
        telemetryTabIndex = tabViews.size
        addTab("TELEMETRY", createTelemetryTab())
        copilotTabIndex = tabViews.size
        addTab("AI COPILOT", createCopilotTab())
        savedNavigationTabIndex = tabViews.size
        addTab("保存地点", createSavedNavigationTab())

        // 基本状態はタブが最小化された状態にする（初回はアニメーションなしで最小化）
        selectTab(0, collapseChrome = false)
        setChromeCollapsed(true, animate = false)
        setContentView(root)
    }

    private fun statusCardSmall(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            connectionBadge = badge("USB: --", false)
            sendBadge = badge("TX: --", false)
            addView(connectionBadge)
            addSpaceHorizontal(8)
            addView(sendBadge)
        }
    }

    private fun createCompactChromeBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = tonalSurfaceDrawable(colors.surface, colors.accentPrimary, 24f, 0.62f)
            elevation = 0f

            addView(chromeButton("MENU", colors.accentPrimary) { setChromeCollapsed(false) })
            addSpaceHorizontal(6)
            compactStatusBadge = badge("DASHBOARD", true).apply {
                minHeight = dp(32)
                minWidth = dp(124)
                textSize = 10f
                setPadding(dp(12), dp(5), dp(12), dp(5))
                isClickable = true
                addPressAnimation(0.96f)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    setChromeCollapsed(false)
                }
            }
            addView(compactStatusBadge)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            compactConnectionBadge = badge("MU3 --", false).apply {
                minHeight = dp(32)
                minWidth = dp(84)
                textSize = 10f
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            addView(compactConnectionBadge)
            addSpaceHorizontal(6)
            compactTxBadge = badge("TX --", false).apply {
                minHeight = dp(32)
                minWidth = dp(92)
                textSize = 10f
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            addView(compactTxBadge)
            addSpaceHorizontal(6)
            compactStopButton = chromeButton("STOP", color("#EA4335")) {
                stopSending()
                setChromeCollapsed(false)
            }.apply {
                visibility = View.GONE
            }
            addView(compactStopButton)
        }
    }

    private fun chromeButton(title: String, accent: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.textPrimary)
            minHeight = dp(32)
            minWidth = dp(56)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, accent, 999f, 0.86f), accent, 999f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.95f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    private fun setChromeCollapsed(collapsed: Boolean, animate: Boolean = true) {
        if (!::chromeContainer.isInitialized || !::compactChromeBar.isInitialized) return
        if (isChromeCollapsed == collapsed && chromeContainer.visibility == (if (collapsed) View.GONE else View.VISIBLE)) return

        val action = Runnable {
            isChromeCollapsed = collapsed
            contentFrame.setPadding(0, if (collapsed) dp(54) else 0, 0, 0)
            updateCompactChromeBar()
            if (collapsed) {
                compactChromeBar.visibility = View.VISIBLE
                compactChromeBar.alpha = if (animate) 0f else 1f
                compactChromeBar.translationY = if (animate) -dp(12).toFloat() else 0f

                if (animate) {
                    chromeContainer.animate()
                        .alpha(0f)
                        .translationY(-chromeContainer.height.coerceAtLeast(dp(80)).toFloat())
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            chromeContainer.visibility = View.GONE
                            chromeContainer.alpha = 1f
                            chromeContainer.translationY = 0f
                            contentFrame.requestLayout()
                        }
                        .start()
                    compactChromeBar.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                } else {
                    chromeContainer.visibility = View.GONE
                    contentFrame.requestLayout()
                }
            } else {
                chromeContainer.visibility = View.VISIBLE
                chromeContainer.alpha = if (animate) 0f else 1f
                chromeContainer.translationY = if (animate) -dp(40).toFloat() else 0f

                if (animate) {
                    chromeContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(OvershootInterpolator(1.05f))
                        .start()
                    compactChromeBar.animate()
                        .alpha(0f)
                        .translationY(-dp(10).toFloat())
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            compactChromeBar.visibility = View.GONE
                            compactChromeBar.alpha = 1f
                            compactChromeBar.translationY = 0f
                        }
                        .start()
                } else {
                    compactChromeBar.visibility = View.GONE
                    contentFrame.requestLayout()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    private fun updateCompactChromeBar() {
        if (!::compactStatusBadge.isInitialized || !::compactConnectionBadge.isInitialized || !::compactTxBadge.isInitialized || !::compactStopButton.isInitialized) return
        val usbActive = serialPort != null
        val txActive = isSending
        val effectiveIntervalMs = stableTxIntervalMs()
        val txIntervalLabel = if (effectiveIntervalMs == txIntervalMs) {
            "${txIntervalMs}ms"
        } else {
            "${txIntervalMs}->${effectiveIntervalMs}ms"
        }
        compactStatusBadge.text = "${selectedTabIndex + 1} · ${tabTitle(selectedTabIndex)}"
        compactStatusBadge.background = tonalSurfaceDrawable(
            colors.surface,
            tabAccent(selectedTabIndex),
            999f,
            0.86f
        )
        compactConnectionBadge.text = if (usbActive) "MU3 LIVE" else "MU3 OFF"
        compactConnectionBadge.background = tonalSurfaceDrawable(
            colors.surface,
            if (usbActive) color("#0B8043") else colors.accentPrimary,
            999f,
            if (usbActive) 0.9f else 0.58f
        )
        compactTxBadge.text = if (txActive) "TX $txIntervalLabel" else "TX OFF"
        compactTxBadge.background = tonalSurfaceDrawable(
            colors.surface,
            if (txActive) color("#4285F4") else colors.accentPrimary,
            999f,
            if (txActive) 0.92f else 0.58f
        )
        compactStopButton.visibility = if (txActive) View.VISIBLE else View.GONE
    }

    private fun updateDashboardChip(view: TextView?, textValue: String, fill: Int, accent: Int) {
        view ?: return
        view.text = textValue
            view.background = tonalSurfaceDrawable(fill, accent, 999f, 0.9f)
    }

    private fun headerBlock(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(BrandOrbView(context), LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(9)
            })
            val titleStack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(titleStack)
            titleStack.addView(TextView(context).apply {
                text = "OVERBITE 2.0"
                textSize = 25f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setTextColor(colors.textPrimary)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            titleStack.addView(TextView(context).apply {
                text = "MU-3 LIVE CONTROL / LOW-LATENCY TX"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                setTextColor(colors.accentPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    private fun tabTitle(index: Int): String {
        return tabLabels.getOrNull(index) ?: "DASHBOARD"
    }

    private fun addTab(label: String, view: View) {
        val index = tabButtons.size
        tabLabels.add(label)
        val tabBtn = TextView(this).apply {
            text = "${index + 1}. $label"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(color("#94A3B8"))
            isClickable = true
            minHeight = dp(36)
            minWidth = dp(120)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = "Tab $label"
            setOnClickListener { selectTab(index) }
            addPressAnimation(0.98f)
        }
        tabContainer.addView(tabBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        tabButtons.add(tabBtn)
        tabViews.add(view)
    }

    private fun selectTab(index: Int, collapseChrome: Boolean = true) {
        if (!::contentFrame.isInitialized) return
        if (index !in tabButtons.indices) return
        val previousIndex = selectedTabIndex
        selectedTabIndex = index
        ensureAiHeartbeat()
        for (i in tabButtons.indices) {
            val isActive = i == index
            val accent = tabAccent(i)
            tabButtons[i].apply {
                setTextColor(if (isActive) contentColorFor(accent) else colors.textSecondary)
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = if (isActive) {
                    tonalSurfaceDrawable(accent, accent, 18f, 1f)
                } else {
                    rippleDrawable(rounded(Color.TRANSPARENT, 18f), accent, 18f)
                }
                elevation = 0f
                animate()
                    .scaleX(if (isActive) 1.04f else 1f)
                    .scaleY(if (isActive) 1.04f else 1f)
                    .translationY(if (isActive) -dp(1).toFloat() else 0f)
                    .setDuration(if (isActive) 210L else 150L)
                    .setInterpolator(if (isActive) OvershootInterpolator(1.08f) else DecelerateInterpolator())
                    .start()
            }
            
            if (isActive) {
                val view = tabViews[i]
                if (contentFrame.childCount > 0 && contentFrame.getChildAt(0) == view) {
                    // Already showing
                } else {
                    contentFrame.removeAllViews()
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.animate().cancel()
                    view.alpha = 0f
                    view.translationX = dp(if (index >= previousIndex) 18 else -18).toFloat()
                    contentFrame.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
                    view.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }
        updateCompactChromeBar()
        if (::tabScrollView.isInitialized) {
            tabScrollView.post {
                val target = (tabButtons[index].left - dp(12)).coerceAtLeast(0)
                tabScrollView.smoothScrollTo(target, 0)
            }
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // タブを選択したら自動的にタブ（chrome）を最小化する
        if (collapseChrome) setChromeCollapsed(true)
    }

    private var leftJoystickView: JoystickView? = null
    private var rightJoystickView: JoystickView? = null

    private val dashboardButtonViews = mutableMapOf<Int, TextView>()

    private data class DashboardMetrics(
        val compact: Boolean,
        val ultraCompact: Boolean,
        val leftWeight: Float,
        val rightWeight: Float,
        val paneGapDp: Int,
        val cardPaddingDp: Int,
        val sectionGapDp: Int,
        val buttonSizeDp: Int,
        val triggerWidthDp: Int,
        val joystickSizeDp: Int,
        val deckButtonWidthDp: Int,
        val deckButtonHeightDp: Int,
        val deckButtonTitleSp: Float,
        val deckButtonSubSp: Float,
        val touchpadWidthDp: Int,
        val touchpadHeightDp: Int
    )

    private fun dashboardMetrics(): DashboardMetrics {
        val configuration = resources.configuration
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val fallbackWidthDp = (resources.displayMetrics.widthPixels / density).roundToInt()
        val fallbackHeightDp = (resources.displayMetrics.heightPixels / density).roundToInt()
        val widthDp = if (configuration.screenWidthDp > 0) configuration.screenWidthDp else fallbackWidthDp
        val heightDp = if (configuration.screenHeightDp > 0) configuration.screenHeightDp else fallbackHeightDp
        val compact = heightDp <= 430 || widthDp <= 820
        val ultraCompact = heightDp <= 380 || widthDp <= 700

        return DashboardMetrics(
            compact = compact,
            ultraCompact = ultraCompact,
            leftWeight = if (compact) 1.12f else 1.35f,
            rightWeight = if (compact) 0.98f else 1f,
            paneGapDp = if (compact) 5 else 10,
            cardPaddingDp = if (ultraCompact) 8 else if (compact) 10 else 14,
            sectionGapDp = if (compact) 6 else 10,
            buttonSizeDp = if (ultraCompact) 24 else if (compact) 28 else 32,
            triggerWidthDp = if (compact) 36 else 44,
            joystickSizeDp = if (ultraCompact) 58 else if (compact) 68 else 84,
            deckButtonWidthDp = if (ultraCompact) 82 else if (compact) 90 else 120,
            deckButtonHeightDp = if (compact) 46 else 58,
            deckButtonTitleSp = if (compact) 13f else 15f,
            deckButtonSubSp = if (compact) 8.5f else 9.5f,
            touchpadWidthDp = if (ultraCompact) 96 else if (compact) 112 else 136,
            touchpadHeightDp = if (compact) 26 else 34
        )
    }

    private inner class OverbiteBackgroundDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val shapeRect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat().coerceAtLeast(1f)
            val h = b.height().toFloat().coerceAtLeast(1f)

            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = colors.bg
            canvas.drawRect(b, paint)

            canvas.save()
            canvas.rotate(-12f, w * 0.5f, h * 0.5f)
            paint.color = alphaColor(colors.accentPrimary, if (isDarkMode) 0.11f else 0.07f)
            shapeRect.set(-w * 0.12f, h * 0.10f, w * 1.12f, h * 0.24f)
            canvas.drawRoundRect(shapeRect, dp(20).toFloat(), dp(20).toFloat(), paint)
            paint.color = alphaColor(colors.accentTeal, if (isDarkMode) 0.10f else 0.06f)
            shapeRect.set(-w * 0.18f, h * 0.74f, w * 1.04f, h * 0.88f)
            canvas.drawRoundRect(shapeRect, dp(20).toFloat(), dp(20).toFloat(), paint)
            canvas.restore()

            linePaint.color = alphaColor(colors.gridLine, if (isDarkMode) 0.72f else 0.58f)
            linePaint.strokeWidth = dp(1).toFloat()
            val step = dp(36).coerceAtLeast(1)
            var x = b.left.toFloat()
            while (x <= b.right) {
                canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), linePaint)
                x += step
            }
            var y = b.top.toFloat()
            while (y <= b.bottom) {
                canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, linePaint)
                y += step
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            linePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            linePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private inner class BrandOrbView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arcRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = (min(width, height) / 2f) - dp(5)
            val cx = width / 2f
            val cy = height / 2f
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = colors.orb1
            canvas.drawCircle(cx, cy, r, paint)

            paint.color = colors.orb2
            canvas.drawCircle(cx + r * 0.32f, cy - r * 0.28f, r * 0.44f, paint)

            paint.color = colors.orb3
            canvas.drawCircle(cx - r * 0.24f, cy + r * 0.32f, r * 0.36f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = colors.accentPrimary
            canvas.drawCircle(cx, cy, r, paint)
            arcRect.set(cx - r + dp(4), cy - r + dp(4), cx + r - dp(4), cy + r - dp(4))
            paint.strokeWidth = dp(2).toFloat()
            paint.color = colors.accentPrimary
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(arcRect, -30f, 84f, false, paint)
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
        }
    }

    inner class JoystickView(context: Context) : View(context) {
        var xValue = 0f
        var yValue = 0f
        var isJoystickPressed = false

        private val baseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colors.bg
        }
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = colors.accentPrimary
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = colors.orb1
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colors.accentPrimary
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            
            // Reserve space for the stick itself to avoid clipping
            // stick radius plus a small margin keeps the knob from clipping.
            val stickVisualRadius = dp(20).toFloat()
            val maxBaseRadius = min(cx, cy) - stickVisualRadius - dp(2).toFloat()
            val baseRadius = maxBaseRadius.coerceAtLeast(dp(10).toFloat())

            // Flat Material-style base ring.
            canvas.drawCircle(cx, cy, baseRadius, baseFillPaint)
            canvas.drawCircle(cx, cy, baseRadius, outerPaint)
            canvas.drawCircle(cx, cy, baseRadius, rimPaint)

            // Calculate stick position
            val stickX = cx + (xValue * baseRadius)
            val stickY = cy + (yValue * baseRadius)

            if (isJoystickPressed) {
                innerPaint.alpha = 255
                innerPaint.color = colors.accentCoral
                canvas.drawCircle(stickX, stickY, dp(17).toFloat(), innerPaint)
                innerPaint.color = contentColorFor(colors.accentCoral)
                canvas.drawCircle(stickX, stickY, dp(8).toFloat(), innerPaint)
            } else {
                innerPaint.alpha = 255
                innerPaint.color = colors.accentTeal
                canvas.drawCircle(stickX, stickY, dp(14).toFloat(), innerPaint)
                innerPaint.color = contentColorFor(colors.accentTeal)
                canvas.drawCircle(stickX - dp(4), stickY - dp(4), dp(3).toFloat(), innerPaint)
            }
        }
    }

    /**
     * ブラックボックスの直近サンプルをオシロスコープ風にスクロール表示するライブグラフ。
     * 上段: スティック活動量(面)＋送信間隔ms(線)＋目標周期(破線)。
     * 下段: 1サンプルごとのリンク健全度(ACK=緑 / 欠落=赤)とエラー点(明赤の縦線)。
     */
    inner class LiveGraphView(context: Context) : View(context) {
        private val maxPoints = 480
        private val gapArr = FloatArray(maxPoints)
        private val ackArr = FloatArray(maxPoints)
        private val magArr = FloatArray(maxPoints)
        private val errArr = BooleanArray(maxPoints)
        private val gapPath = Path()
        private val magPath = Path()

        // activityから設定: 目標送信周期(ms)と縦軸スケール上限(ms)。
        var targetIntervalMs = 50f
        var maxGapMs = 160f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = blendColors(colors.surface, colors.accentPrimary, 0.06f)
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = alphaColor(colors.outline, 0.6f)
        }
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = alphaColor(colors.accentSuccess, 0.85f)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(4).toFloat()), 0f)
        }
        private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colors.accentTeal
        }
        private val magPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = alphaColor(colors.accentViolet, 0.22f)
        }
        private val ackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val errPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colors.accentDanger
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary
            textSize = dp(10).toFloat()
            typeface = Typeface.MONOSPACE
        }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary
            textSize = dp(13).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = dp(16).toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)

            val padL = dp(6).toFloat()
            val padR = dp(6).toFloat()
            val padT = dp(8).toFloat()
            val padB = dp(6).toFloat()
            val plotW = (w - padL - padR).coerceAtLeast(1f)
            val healthH = dp(14).toFloat()
            val topBandTop = padT
            val topBandBottom = h - padB - healthH - dp(4).toFloat()
            val topBandH = (topBandBottom - topBandTop).coerceAtLeast(1f)

            // 横グリッド(0 / 1/2 / 1 of maxGapMs) と目標周期の破線。
            for (g in 0..2) {
                val y = topBandBottom - topBandH * (g / 2f)
                canvas.drawLine(padL, y, w - padR, y, gridPaint)
            }
            val msToY = { ms: Float -> topBandBottom - topBandH * (ms / maxGapMs).coerceIn(0f, 1f) }
            val targetY = msToY(targetIntervalMs)
            canvas.drawLine(padL, targetY, w - padR, targetY, targetPaint)

            val n = blackBox.copyRecent(maxPoints, gapArr, ackArr, magArr, errArr)
            if (n < 2) {
                canvas.drawText(
                    if (blackBox.recording) "REC ● 記録待機中..." else "RECを押すと記録開始",
                    w / 2f, h / 2f, emptyPaint
                )
                return
            }

            val stepX = plotW / (n - 1)
            val xOf = { k: Int -> padL + stepX * k }

            // スティック活動量(0..1)を上段の塗り面で。
            magPath.reset()
            magPath.moveTo(xOf(0), topBandBottom)
            for (k in 0 until n) {
                magPath.lineTo(xOf(k), topBandBottom - topBandH * magArr[k].coerceIn(0f, 1f))
            }
            magPath.lineTo(xOf(n - 1), topBandBottom)
            magPath.close()
            canvas.drawPath(magPath, magPaint)

            // 送信間隔(ms)を上段の線で。
            gapPath.reset()
            for (k in 0 until n) {
                val x = xOf(k)
                val y = msToY(gapArr[k])
                if (k == 0) gapPath.moveTo(x, y) else gapPath.lineTo(x, y)
            }
            canvas.drawPath(gapPath, gapPaint)

            // 下段: リンク健全度バー(ACK=緑 / 欠落=赤)とエラーの縦線。
            val healthTop = h - padB - healthH
            val barW = (plotW / n).coerceAtLeast(1f)
            for (k in 0 until n) {
                val x = padL + (plotW / n) * k
                ackPaint.color = if (ackArr[k] > 0.5f) {
                    alphaColor(colors.accentSuccess, 0.85f)
                } else {
                    alphaColor(colors.accentDanger, 0.85f)
                }
                canvas.drawRect(x, healthTop, x + barW + 0.5f, h - padB, ackPaint)
                if (errArr[k]) {
                    canvas.drawRect(x, topBandTop, x + barW.coerceAtLeast(dp(2).toFloat()), h - padB, errPaint)
                }
            }

            // 目盛ラベル。
            canvas.drawText("${maxGapMs.roundToInt()}ms", padL + dp(2), topBandTop + dp(10), labelPaint)
            canvas.drawText("LINK", padL + dp(2), h - padB - dp(2), labelPaint)
        }
    }

    /**
     * リプレイ時に、ブラックボックスの1フレーム分のpayload状態を再現表示するビュー。
     * 左右スティック位置と payload[4][5][6] の24ビットLED、ニュートラルからの差分を描く。
     */
    inner class ReplayPadView(context: Context) : View(context) {
        private var frame: BbFrame? = null

        fun showFrame(value: BbFrame?) {
            frame = value
            invalidate()
        }

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = colors.accentPrimary
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ledOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colors.accentSuccess }
        private val ledOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = alphaColor(colors.outline, 0.5f) }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary
            textSize = dp(9).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }

        private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, nx: Float, ny: Float, label: String) {
            fillPaint.color = blendColors(colors.surface, colors.accentPrimary, 0.10f)
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, basePaint)
            // payloadは 0..255、128がニュートラル。-1..+1へ正規化。
            val knobX = cx + nx.coerceIn(-1f, 1f) * r
            val knobY = cy + ny.coerceIn(-1f, 1f) * r
            fillPaint.color = colors.accentCoral
            canvas.drawCircle(knobX, knobY, dp(7).toFloat(), fillPaint)
            canvas.drawText(label, cx, cy + r + dp(11), labelPaint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val f = frame ?: return
            val h = height.toFloat()
            val r = (h * 0.30f).coerceAtLeast(dp(18).toFloat())
            val stickY = h * 0.36f
            val leftCx = width * 0.16f
            val rightCx = width * 0.40f
            val lx = (f.p0 - 128) / 128f
            val ly = (f.p1 - 128) / 128f
            val rx = (f.p2 - 128) / 128f
            val ry = (f.p3 - 128) / 128f
            drawStick(canvas, leftCx, stickY, r, lx, ly, "L")
            drawStick(canvas, rightCx, stickY, r, rx, ry, "R")

            // 24ビットLED (payload[4],[5],[6]) を右側に3行×8列で。
            val gridLeft = width * 0.56f
            val gridRight = width.toFloat() - dp(8)
            val cols = 8
            val rows = 3
            val cellW = (gridRight - gridLeft) / cols
            val cellH = (h - dp(16)) / rows
            val ledR = (min(cellW, cellH) * 0.30f).coerceAtLeast(dp(3).toFloat())
            val bytes = intArrayOf(f.p4, f.p5, f.p6)
            val rowNames = arrayOf("P4", "P5", "P6")
            for (row in 0 until rows) {
                val v = bytes[row]
                val cyRow = dp(10) + cellH * row + cellH / 2f
                canvas.drawText(rowNames[row], gridLeft - dp(12), cyRow + dp(3), labelPaint)
                for (bit in 0 until cols) {
                    // bit7が左、bit0が右になるよう表示。
                    val shown = 7 - bit
                    val on = (v ushr shown) and 1 != 0
                    val cxBit = gridLeft + cellW * bit + cellW / 2f
                    canvas.drawCircle(cxBit, cyRow, ledR, if (on) ledOnPaint else ledOffPaint)
                }
            }
        }
    }

    private fun createDashboardTab(): View {
        val metrics = dashboardMetrics()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            clipToPadding = false

            // Left side: Controller Visualization
            val left = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setPadding(0, 0, dp(metrics.paneGapDp / 2), 0)
                addView(createControllerMonitor(metrics), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            // Right side: Status & Data
            val right = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                val rightContent = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(metrics.paneGapDp / 2), 0, 0, dp(6))

                    addView(createDashboardCommandDeck(metrics), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    addSpace(metrics.sectionGapDp)

                    if (metrics.compact) {
                        addView(createDashboardTelemetryPanel(metrics), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    } else {
                        // 1. Live Data Card
                        val dataCard = glassBox().apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(metrics.cardPaddingDp), dp(metrics.cardPaddingDp), dp(metrics.cardPaddingDp), dp(metrics.cardPaddingDp))

                            addView(sectionTitle("LIVE STREAM", "送信データ"))
                            addSpace(metrics.sectionGapDp)

                            addView(miniLabel("FRAME PACKET (RAW)"))
                            payloadText = monoText("WAITING...").apply {
                                textSize = 12f
                                setTextColor(if (isDarkMode) color("#90CAF9") else color("#0B57D0"))
                            }
                            addView(payloadText)
                        }
                        addView(dataCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                        addSpace(metrics.sectionGapDp)

                        // 2. Device Input Card
                        val deviceCard = glassBox().apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(metrics.cardPaddingDp), dp(12), dp(metrics.cardPaddingDp), dp(12))
                            addView(miniLabel("PHYSICAL INPUTS"))
                            physicalInputText = monoText("--").apply {
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(color("#FF6F61"))
                                typeface = Typeface.DEFAULT_BOLD
                            }
                            addView(physicalInputText)
                        }
                        addView(deviceCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                        addSpace(metrics.sectionGapDp)

                        // 3. System Log Card
                        val logCard = glassBox().apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(metrics.cardPaddingDp), dp(12), dp(metrics.cardPaddingDp), dp(12))
                            addView(miniLabel("ACTIVITY LOG"))
                            logText = monoText("SYSTEM READY").apply {
                                textSize = 10f
                                maxLines = 5
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(colors.textSecondary)
                            }
                            addView(logText)
                        }
                        addView(logCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    }
                    addSpace(metrics.sectionGapDp)
                }
                addView(rightContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, metrics.leftWeight))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, metrics.rightWeight))
        }
    }

    private fun createDashboardTelemetryPanel(metrics: DashboardMetrics): View {
        return glassBox().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(metrics.cardPaddingDp), dp(metrics.cardPaddingDp), dp(metrics.cardPaddingDp), dp(metrics.cardPaddingDp))

            addView(sectionTitle("LIVE STREAM", "DATA"))
            addSpace(6)

            addView(miniLabel("FRAME PACKET (RAW)"))
            payloadText = monoText("WAITING...").apply {
                textSize = if (metrics.ultraCompact) 10f else 10.5f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(if (isDarkMode) color("#90CAF9") else color("#0B57D0"))
            }
            addView(payloadText)

            addSpace(8)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(miniLabel("PHYSICAL INPUTS"))
                    physicalInputText = monoText("--").apply {
                        textSize = if (metrics.ultraCompact) 9.5f else 10f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        setTextColor(color("#FF6F61"))
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    addView(physicalInputText)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f))

                addSpaceHorizontal(8)

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(miniLabel("ACTIVITY LOG"))
                    logText = monoText("SYSTEM READY").apply {
                        textSize = if (metrics.ultraCompact) 8.5f else 9f
                        maxLines = if (metrics.ultraCompact) 3 else 4
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        setTextColor(colors.textSecondary)
                    }
                    addView(logText)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f))
            })
        }
    }

    private fun miniLabel(label: String): View {
        return TextView(this).apply {
            text = label
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setTextColor(primaryAccent())
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun createDashboardCommandDeck(metrics: DashboardMetrics = dashboardMetrics()): View {
        return glassBox().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(metrics.cardPaddingDp), dp(if (metrics.compact) 10 else 14), dp(metrics.cardPaddingDp), dp(if (metrics.compact) 10 else 14))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(sectionTitle("CONTROL DECK", "EXPRESSIVE"))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(statusPill("M3", color("#BEEAE6")))
            })
            addSpace(if (metrics.compact) 6 else 8)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val usbChip = statusPill("USB WAIT", color("#BEEAE6"))
                    val txChip = statusPill("TX IDLE", color("#FFD2CC"))
                    dashboardUsbChip = usbChip
                    dashboardTxChip = txChip
                    addView(usbChip)
                    addSpaceHorizontal(6)
                    addView(txChip)
                    addSpaceHorizontal(6)
                    addView(statusPill("${txIntervalMs}ms", color("#A8DBA8")))
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            addSpace(metrics.sectionGapDp)

            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(expressiveDeckButton("Connect", "USB", color("#BEEAE6"), metrics) { connectUsbSerial() })
                    addSpaceHorizontal(if (metrics.compact) 6 else 8)
                    addView(expressiveDeckButton("保存地点", "自動走行", color("#A8DBA8"), metrics) { selectTab(savedNavigationTabIndex) })
                    addSpaceHorizontal(if (metrics.compact) 6 else 8)
                    addView(expressiveDeckButton("Start", "Focus TX", color("#A8DBA8"), metrics) { startSending() })
                    addSpaceHorizontal(if (metrics.compact) 6 else 8)
                    addView(expressiveDeckButton("Stop", "TX off", color("#FFD8A8"), metrics) { stopSending() })
                    addSpaceHorizontal(if (metrics.compact) 6 else 8)
                    addView(expressiveDeckButton("Neutral", "Reset pad", color("#FFD2CC"), metrics) {
                        stopSavedNavigation()
                        synchronized(pad) { pad.clearAll() }
                        updateUiState()
                        sendNeutralBurstAsync(10, "RESET: AXIS NEUTRAL SENT")
                    })
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun expressiveDeckButton(
        title: String,
        sub: String,
        accent: Int,
        metrics: DashboardMetrics = dashboardMetrics(),
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (metrics.compact) 12 else 14), dp(if (metrics.compact) 8 else 10), dp(if (metrics.compact) 12 else 14), dp(if (metrics.compact) 8 else 10))
            minimumWidth = dp(metrics.deckButtonWidthDp)
            minimumHeight = dp(metrics.deckButtonHeightDp)
            background = rippleDrawable(expressiveSurfaceDrawable(accent, 24f), accent, 24f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.96f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            addView(TextView(context).apply {
                text = title
                textSize = metrics.deckButtonTitleSp
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setTextColor(colors.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = sub
                textSize = metrics.deckButtonSubSp
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(alphaColor(colors.textPrimary, 0.72f))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            })
        }
    }

    private fun statusPill(label: String, accent: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.textPrimary)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = tonalSurfaceDrawable(colors.surface, accent, 999f, 0.82f)
            elevation = 0f
        }
    }

    private fun createControllerMonitor(metrics: DashboardMetrics = dashboardMetrics()): View {
        dashboardButtonViews.clear()
        return glassBox().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(metrics.cardPaddingDp), dp(if (metrics.compact) 10 else 16), dp(metrics.cardPaddingDp), dp(if (metrics.compact) 10 else 16))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(sectionTitle("DUALSENSE SURFACE", "LIVE INPUT"))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(statusPill("READY", color("#22C55E")))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(metrics.sectionGapDp)

            // 1. Triggers Row (L2, L1, [Spacer], R1, R2)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                addView(createButtonView(KeyEvent.KEYCODE_BUTTON_L2, "L2", "#64748B", metrics).apply { minimumWidth = dp(metrics.triggerWidthDp) })
                addSpaceHorizontal(if (metrics.compact) 6 else 8)
                addView(createButtonView(KeyEvent.KEYCODE_BUTTON_L1, "L1", "#64748B", metrics).apply { minimumWidth = dp(metrics.triggerWidthDp) })
                
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                
                addView(createButtonView(KeyEvent.KEYCODE_BUTTON_R1, "R1", "#64748B", metrics).apply { minimumWidth = dp(metrics.triggerWidthDp) })
                addSpaceHorizontal(if (metrics.compact) 6 else 8)
                addView(createButtonView(KeyEvent.KEYCODE_BUTTON_R2, "R2", "#64748B", metrics).apply { minimumWidth = dp(metrics.triggerWidthDp) })
            })

            addSpace(metrics.sectionGapDp)

            // 2. Main Controls Row (D-Pad, [Spacer], Center, [Spacer], Face Buttons)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                // D-Pad
                val dpad = GridLayout(context).apply {
                    columnCount = 3
                    rowCount = 3
                    addView(createButtonView(KeyEvent.KEYCODE_DPAD_UP, "↑", "#38BDF8", metrics), gridLp(0, 1, metrics))
                    addView(createButtonView(KeyEvent.KEYCODE_DPAD_LEFT, "←", "#38BDF8", metrics), gridLp(1, 0, metrics))
                    addView(createButtonView(KeyEvent.KEYCODE_DPAD_RIGHT, "→", "#38BDF8", metrics), gridLp(1, 2, metrics))
                    addView(createButtonView(KeyEvent.KEYCODE_DPAD_DOWN, "↓", "#38BDF8", metrics), gridLp(2, 1, metrics))
                }
                addView(dpad)

                addView(View(context), LinearLayout.LayoutParams(0, 1, if (metrics.compact) 0.22f else 0.4f))

                // Center Area (DualSense inspired)
                val center = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    
                    // Touchpad
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_1, "TOUCHPAD CLICK", "#94A3B8", metrics).apply {
                        minimumWidth = dp(metrics.touchpadWidthDp)
                        minimumHeight = dp(metrics.touchpadHeightDp)
                        textSize = if (metrics.compact) 7.5f else 8.5f
                    })
                    
                    addSpace(if (metrics.compact) 6 else 8)
                    
                    // Share / PS / Options
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(createButtonView(KeyEvent.KEYCODE_BUTTON_SELECT, "CREATE", "#94A3B8", metrics).apply { textSize = if (metrics.compact) 7f else 7.5f })
                        addSpaceHorizontal(if (metrics.compact) 6 else 10)
                        addView(createButtonView(KeyEvent.KEYCODE_BUTTON_MODE, "PS", "#FF6F61", metrics).apply {
                            minimumWidth = dp(if (metrics.compact) 34 else 40)
                            minimumHeight = dp(if (metrics.compact) 34 else 40)
                            textSize = if (metrics.compact) 10f else 11f
                        })
                        addSpaceHorizontal(if (metrics.compact) 6 else 10)
                        addView(createButtonView(KeyEvent.KEYCODE_BUTTON_START, "OPTIONS", "#94A3B8", metrics).apply { textSize = if (metrics.compact) 7f else 7.5f })
                    })
                    
                    addSpace(if (metrics.compact) 6 else 8)
                    
                    // Mute
                    addView(createButtonView(KeyEvent.KEYCODE_MUTE, "MUTE", "#64748B", metrics).apply {
                        minimumWidth = dp(40)
                        minimumHeight = dp(if (metrics.compact) 20 else 22)
                        textSize = 7f
                    })
                }
                addView(center)

                addView(View(context), LinearLayout.LayoutParams(0, 1, if (metrics.compact) 0.22f else 0.4f))

                // Face Buttons
                val face = GridLayout(context).apply {
                    columnCount = 3
                    rowCount = 3
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_Y, "△", "#4ADE80", metrics), gridLp(0, 1, metrics))
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_X, "□", "#7C3AED", metrics), gridLp(1, 0, metrics))
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_B, "○", "#F87171", metrics), gridLp(1, 2, metrics))
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_A, "×", "#60A5FA", metrics), gridLp(2, 1, metrics))
                }
                addView(face)
            })

            addSpace(if (metrics.compact) 10 else 16)

            // 3. Sticks Row (L3, Sticks, R3)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                
                // Left Stick
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    leftJoystickView = JoystickView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(metrics.joystickSizeDp), dp(metrics.joystickSizeDp))
                    }
                    addView(leftJoystickView)
                    addSpace(if (metrics.compact) 3 else 4)
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3", "#38BDF8", metrics))
                })
                
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                
                // Right Stick
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    rightJoystickView = JoystickView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(metrics.joystickSizeDp), dp(metrics.joystickSizeDp))
                    }
                    addView(rightJoystickView)
                    addSpace(if (metrics.compact) 3 else 4)
                    addView(createButtonView(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3", "#38BDF8", metrics))
                })
            })
        }
    }

    private fun createButtonView(
        keyCode: Int,
        label: String,
        activeColor: String,
        metrics: DashboardMetrics = dashboardMetrics()
    ): TextView {
        return TextView(this).apply {
            text = label
            tag = activeColor
            textSize = if (metrics.compact) 9f else 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.textSecondary)
            setPadding(dp(4), dp(3), dp(4), dp(3))
            minimumWidth = dp(metrics.buttonSizeDp)
            minimumHeight = dp(metrics.buttonSizeDp)
            val accent = color(activeColor)
            background = tonalSurfaceDrawable(colors.surface, accent, 14f, 0.54f)
            elevation = 0f
            dashboardButtonViews[keyCode] = this
        }
    }

    private fun gridLp(row: Int, col: Int, metrics: DashboardMetrics = dashboardMetrics()): GridLayout.LayoutParams {
        return GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col)).apply {
            val margin = if (metrics.compact) 1 else 2
            setMargins(dp(margin), dp(margin), dp(margin), dp(margin))
        }
    }

    private fun createSavedNavigationTab(): View {
        val map = SavedNavigationMapView(
            this,
            SAVED_NAVIGATION_POINTS,
            SavedNavigationMapView.Palette(
                surface = colors.surface,
                fieldFill = colors.surfaceVariant,
                outline = colors.outline,
                gridLine = colors.gridLine,
                divider = colors.accentWarning,
                marker = colors.accentInfo,
                markerSelected = colors.accentSuccess,
                textPrimary = colors.textPrimary,
                textOnMarker = Color.WHITE,
                textSecondary = colors.textSecondary,
            ),
        ) { point -> requestSavedNavigation(point) }
        map.rightField = savedNavigationRightField
        savedNavigationMap = map

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = tonalSurfaceDrawable(colors.surface, colors.accentInfo, 16f, 0.6f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    savedNavigationSideLabel = smallButton("", colors.accentInfo) {
                        setSavedNavigationField(!savedNavigationRightField)
                    } as TextView
                    savedNavigationSideLabel?.textSize = 12f
                    addView(savedNavigationSideLabel, LinearLayout.LayoutParams(0, dp(44), 0.75f))
                    addSpaceHorizontal(6)
                    addView(smallButton("自己位置リセット", colors.accentWarning) {
                        requestStartPoseReset()
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addSpaceHorizontal(8)
                    addView(smallButton("■ 移動を中止", colors.accentDanger) {
                        stopSavedNavigation()
                        synchronized(pad) { pad.clearAll() }
                        updateUiState()
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                })
                addSpace(8)
                addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                savedNavigationStatus = descriptionText("地点をタップして移動 / 新地点はJetsonで初回保存") as TextView
                savedNavigationStatus?.apply {
                    textSize = 11f
                    maxLines = 2
                    setPadding(dp(4), dp(6), dp(4), 0)
                }
                addView(savedNavigationStatus)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 7f))
            addSpaceHorizontal(10)
            addView(createNavigationAnglePanel(),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.6f))
        }.also { updateSavedNavigationSideLabel() }
    }

    /** Five fixed receiver presets; the order is the transmitted preset number. */
    private fun createNavigationAnglePanel(): View {
        val labels = listOf("手前バケツ・スーパー", "奥バケツ", "クリリン移動", "スーパー装填", "ギュッ")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = tonalSurfaceDrawable(colors.surface, colors.accentViolet, 16f, 0.65f)
            addView(TextView(context).apply {
                text = "仰角変更"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.textPrimary)
            })
            addView(TextView(context).apply {
                text = "プリセットを選択"
                textSize = 11f
                setTextColor(colors.textSecondary)
            })
            addSpace(6)
            labels.forEachIndexed { index, label ->
                val button = TextView(context).apply {
                    text = label
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextColor(colors.textPrimary)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    background = rippleDrawable(
                        tonalSurfaceDrawable(colors.surfaceVariant, colors.accentViolet, 12f, 0.8f),
                        colors.accentViolet, 12f
                    )
                    isClickable = true
                    contentDescription = "仰角変更：$label"
                    addPressAnimation()
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        stopAutopilotInternal(neutralize = false)
                        anglePresetCommand.request(index + 1, SystemClock.elapsedRealtime())
                        setStatus("仰角を選択：$label（受信側で角度設定が必要）")
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle("$label の仰角")
                            .setMessage("bacon6の_include/value.hppで、gm_preset_angle_degとgm_preset_enabledを設定してください。未設定では動作しません。")
                            .setPositiveButton("OK", null).show()
                        true
                    }
                }
                addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(0, 0, 0, dp(6))
                })
            }
            addView(descriptionText("長押しで設定案内").apply {
                (this as TextView).textSize = 10f
                gravity = Gravity.CENTER
            })
        }
    }

    private fun createFieldSideSelector(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("◀ 左フィールド", color("#0B57D0")) {
                setSavedNavigationField(rightField = false)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addSpaceHorizontal(8)
            addView(smallButton("右フィールド ▶", color("#0B57D0")) {
                setSavedNavigationField(rightField = true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * Switch fields. Any running instruction is released first: the slot the
     * automation side is executing belongs to the previous field.
     */
    private fun setSavedNavigationField(rightField: Boolean) {
        if (savedNavigationRightField == rightField) return
        savedNavigationRightField = rightField
        savedNavigationMap?.rightField = rightField
        savedNavigationMap?.clearSelection()
        saveCommSettings()
        if (savedNavigation.slot != 0) stopSavedNavigation()
        updateSavedNavigationSideLabel()
        setStatus("フィールドを${fieldSideName()}に切り替えました")
    }

    private fun fieldSideName() = if (savedNavigationRightField) "右フィールド" else "左フィールド"

    private fun updateSavedNavigationSideLabel() {
        savedNavigationSideLabel?.text = "${fieldSideName()} ↔"
    }

    private fun requestStartPoseReset() {
        if (serialPort == null || !isSending) {
            setStatus("MU-3を接続してSTARTで送信を開始してください")
            savedNavigationStatus?.text = "MU-3を接続してSTARTで送信を開始してください"
            return
        }
        if (stableTxIntervalMs(20L) > 40L) {
            setStatus("自己位置リセットには送信周期を40ms以下に設定してください")
            savedNavigationStatus?.text = "自己位置リセットには送信周期を40ms以下に設定してください"
            return
        }
        stopAutopilotInternal(neutralize = false)
        macroRec.stop()
        anglePresetCommand.stop()
        synchronized(pad) { pad.clearAll() }
        savedNavigationMap?.clearSelection()
        savedNavigation.request(
            if (savedNavigationRightField) SavedNavigationCommand.RESET_START_RIGHT
            else SavedNavigationCommand.RESET_START_LEFT, SystemClock.elapsedRealtime()
        )
        senderThread?.let { LockSupport.unpark(it) }
        val text = "自己位置リセット送信: ${fieldSideName()}の保存済みスタート位置（結果は自動制御側で確認）"
        savedNavigationStatus?.text = text
        setStatus(text)
    }

    private fun requestSavedNavigation(point: SavedNavigationPoint) {
        val map = savedNavigationMap
        if (serialPort == null || !isSending) {
            map?.clearSelection()
            savedNavigationStatus?.text = "MU-3を接続してSTARTで送信を開始してください"
            setStatus("MU-3を接続してSTARTで送信を開始してください")
            return
        }
        if (stableTxIntervalMs(20L) > 40L) {
            map?.clearSelection()
            savedNavigationStatus?.text = "自動走行には送信周期を40ms以下に設定してください"
            setStatus("自動走行には送信周期を40ms以下に設定してください")
            return
        }
        stopAutopilotInternal(neutralize = false)
        macroRec.stop()
        synchronized(pad) { pad.clearAll() }
        savedNavigation.request(
            map?.slotFor(point) ?: point.slot, SystemClock.elapsedRealtime()
        )
        senderThread?.let { LockSupport.unpark(it) }
        val text = "指示送信中: ${point.label} / ${fieldSideName()}（到達結果は自動制御側で確認）"
        savedNavigationStatus?.text = text
        setStatus(text)
    }

    private fun stopSavedNavigation() {
        savedNavigation.stop()
        senderThread?.let { LockSupport.unpark(it) }
        anglePresetCommand.stop()
        savedNavigationMap?.clearSelection()
        savedNavigationStatus?.text = "停止指示 / 待機中"
        setStatus("自動走行の指示を停止しました")
    }

    private fun createVirtualBtnsTab(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
            addView(createVirtualButtonTopBar())
            addSpace(8)
            addView(featureButtonGrid(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addSpace(8)
            addView(emergencyButton())
        }
    }

    private fun createVirtualButtonTopBar(): View {
        return glassBox().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(sectionTitle(getString(R.string.screen_buttons_label), "タップで送信 / ⚙で設定"))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = true
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    addView(chromeButton("AUTO DECK", color("#4285F4")) { applySmartVirtualDeck() })
                    addSpaceHorizontal(6)
                    addView(chromeButton("ADD", color("#34A853")) { addVirtualButtonAndEdit() })
                    addSpaceHorizontal(6)
                    addView(chromeButton("CLEAR", color("#F9AB00")) { clearVirtualButtonStates("VIRTUAL: CLEARED") })
                    addSpaceHorizontal(6)
                    addView(chromeButton("EDIT", color("#0B57D0")) { showMappingEditor() })
                    addSpaceHorizontal(6)
                    addView(chromeButton("FOCUS", colors.accentNeutral) { setChromeCollapsed(true) })
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }
    }

    private fun createSystemTab(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(16))

                    // 1. Connection Card
                    addView(sectionTitle("\uD83D\uDCBB CONTROL", "システム操作"))
                    addSpace(8)
                    val controlGrid = GridLayout(context).apply { columnCount = 2 }
                    controlGrid.addView(actionButton("CONNECT", "SERIAL", color("#00A8A8")) { connectUsbSerial() }, gridParams())
                    controlGrid.addView(actionButton("START", "TX ON", color("#00A86B")) { startSending() }, gridParams())
                    controlGrid.addView(actionButton("STOP", "TX OFF", color("#FFB300")) { stopSending() }, gridParams())
                    controlGrid.addView(actionButton("NEUTRAL", "RESET", color("#0B57D0")) {
                        synchronized(pad) { pad.clearAll() }
                        updateUiState()
                        sendNeutralBurstAsync(10, "RESET: AXIS NEUTRAL SENT")
                    }, gridParams())
                    controlGrid.addView(actionButton("MAPPING", "CONFIG", color("#7C3AED")) { showMappingEditor() }, gridParams())
                    controlGrid.addView(actionButton("RELOAD", "SYNC", color("#4285F4")) {
                        synchronized(configLock) { controlConfig = loadControlConfig() }
                        rebuildFeatureButtons()
                        updateUiState()
                        setStatus("SYNC: CONFIG RELOADED")
                    }, gridParams())
                    addView(glassCard(controlGrid))
                    addSpace(20)

                    // 2. TX Period Card
                    addView(sectionTitle("\u23F1 TX PERIOD", "送信周期設定"))
                    addSpace(8)
                    val txPeriodPanel = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        
                        txIntervalInput = formInput(txIntervalMs.toString()).apply { 
                            inputType = InputType.TYPE_CLASS_NUMBER
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    applyTxIntervalFromUi()
                                    true
                                } else false
                            }
                        }
                        
                        addView(descriptionText("周期スライダー (10ms 〜 200ms)"))
                        addSpace(4)
                        txIntervalSeekBar = SeekBar(context).apply {
                            max = 190
                            progress = (txIntervalMs.toInt() - 10).coerceIn(0, 190)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                                    if (fromUser) txIntervalInput.setText((p + 10).toString())
                                }
                                override fun onStartTrackingTouch(s: SeekBar?) {}
                                override fun onStopTrackingTouch(s: SeekBar?) { applyTxIntervalFromUi() }
                            })
                        }
                        addView(txIntervalSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
                        
                        val cyclePresets = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(smallButton("20ms", color("#00A8A8")) { setTxInterval(20) })
                            addView(smallButton("33ms", color("#00A8A8")) { setTxInterval(33) })
                            addView(smallButton("50ms", color("#00A8A8")) { setTxInterval(50) })
                            addView(smallButton("100ms", color("#00A8A8")) { setTxInterval(100) })
                        }
                        addView(cyclePresets)
                        addSpace(12)

                        val periodInputRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = rounded(color("#F3FAF8"), 18f, strokeColor = color("#D6E5E1"), strokeWidth = 1)
                            setPadding(dp(12), dp(4), dp(8), dp(4))
                            
                            addView(txIntervalInput, LinearLayout.LayoutParams(dp(80), dp(40)))
                            addView(TextView(context).apply {
                                text = "ms"
                                setTextColor(colors.accentPrimary)
                                textSize = 14f
                                setPadding(dp(8), 0, dp(12), 0)
                            })
                            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                            addView(smallButton("APPLY", color("#00A8A8")) { applyTxIntervalFromUi() })
                        }
                        addView(periodInputRow)
                    }
                    addView(glassCard(txPeriodPanel))
                    addSpace(20)

                    // 3. Baud Rate Card
                    addView(sectionTitle("\u26A1 BAUD RATE", "通信速度設定"))
                    addSpace(8)
                    val baudRatePanel = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL

                        baudRateInput = formInput(currentBaudRate.toString()).apply { 
                            inputType = InputType.TYPE_CLASS_NUMBER
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    applyBaudRateFromUi(false)
                                    true
                                } else false
                            }
                        }
                        
                        addView(descriptionText("UART速度プリセット (MU-3 + Android)"))
                        addSpace(4)
                        val baudPresets = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(smallButton("9600", color("#00A86B")) { setBaudRate(9600) })
                            addView(smallButton("19200", color("#00A86B")) { setBaudRate(19200) })
                            addView(smallButton("38400", color("#00A86B")) { setBaudRate(38400) })
                            addView(smallButton("57600", color("#FF6F61")) { setBaudRate(57600) })
                        }
                        addView(baudPresets)
                        addSpace(12)

                        val baudInputRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = rounded(colors.surfaceVariant, 18f, strokeColor = colors.outline, strokeWidth = 1)
                            setPadding(dp(12), dp(4), dp(8), dp(4))

                            addView(baudRateInput, LinearLayout.LayoutParams(dp(100), dp(40)))
                            addView(TextView(context).apply {
                                text = "bps"
                                setTextColor(colors.accentPrimary)
                                textSize = 14f
                                setPadding(dp(8), 0, dp(12), 0)
                            })
                            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                            addView(smallButton("APPLY", color("#00A86B")) { applyBaudRateFromUi(false) })
                        }
                        addView(baudInputRow)
                        addSpace(12)

                        val baudActionGrid = GridLayout(context).apply { columnCount = 2 }
                        baudActionGrid.addView(actionButton("SAVE BAUD", "MU-3保存", color("#FFB300")) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("通信速度をMU-3に保存しますか？")
                                .setMessage("@BRxx/W を送信してEEPROMに保存します。")
                                .setPositiveButton("保存") { _, _ -> applyBaudRateFromUi(true) }
                                .setNegativeButton("キャンセル", null)
                                .show()
                        }, gridParams())
                        baudActionGrid.addView(actionButton("AUTO BAUD", "自動検出", color("#0B57D0")) { autoRecoverMu3Response() }, gridParams())
                        baudActionGrid.addView(actionButton("FAST PRESET", "57600/30ms", color("#FF6F61")) { applyFastPreset() }, gridParams())
                        addView(baudActionGrid)
                    }
                    addView(glassCard(baudRatePanel))
                    addSpace(20)

                    // 4. Theme Selection
                    addView(sectionTitle("\uD83C\uDF17 THEME", "テーマ設定"))
                    addSpace(8)
                    val themeRowView = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(actionButton("LIGHT", "明るい", if (!isDarkMode) color("#4285F4") else colors.outline) { toggleTheme(false) }, rowButtonParams())
                        addView(actionButton("DARK", "暗い", if (isDarkMode) color("#4285F4") else colors.outline) { toggleTheme(true) }, rowButtonParams())
                    }
                    addView(glassCard(themeRowView))
                    addSpace(20)

                    // 5. Status Info Card
                    addView(sectionTitle("\u2139 STATUS INFO", "詳細ステータス"))
                    addSpace(8)
                    statusText = TextView(context).apply {
                        text = "USB-OTGでMU-3を接続してください。"
                        textSize = 12f
                        setTextColor(colors.textSecondary)
                        setLineSpacing(4f, 1.0f)
                    }
                    commStatusText = monoText("TX: @DT0E / $txIntervalMs ms / $currentBaudRate bps")
                    
                    val statusContent = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(infoBox(commStatusText))
                        addSpace(8)
                        addView(statusText)
                    }
                    addView(glassCard(statusContent))
                }
                addView(content)
            }
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun glassCard(content: View): View {
        return glassBox().apply {
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ===================== TELEMETRY / BLACK BOX タブ =====================

    private fun createTelemetryTab(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(16))

                    // --- ライブ統計 ---
                    addView(sectionTitle("📡 LIVE TELEMETRY", "ライブ通信状態"))
                    addSpace(8)
                    val statsText = monoText("接続して送信を開始すると、ここに実測Hz・ジッタ・リンク健全度が表示されます。").apply {
                        textSize = 12f
                    }
                    telemetryStatsText = statsText
                    addView(glassCard(statsText))
                    addSpace(12)

                    // --- ライブグラフ ---
                    val graph = LiveGraphView(context).apply {
                        targetIntervalMs = txIntervalMs.toFloat()
                    }
                    telemetryGraph = graph
                    addView(glassBox().apply {
                        addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
                    })
                    addSpace(6)
                    addView(descriptionText("線=送信間隔(ms) / 破線=目標周期 / 紫面=スティック活動量 / 下帯=リンク健全度(緑=ACK,赤=欠落) / 縦赤=送信エラー"))
                    addSpace(18)

                    // --- ブラックボックス記録 ---
                    addView(sectionTitle("⏺ BLACK BOX", "操縦ログ記録"))
                    addSpace(8)
                    val bbState = monoText("記録停止中 / サンプル 0").apply { textSize = 12f }
                    blackBoxStateText = bbState
                    addView(glassCard(bbState))
                    addSpace(8)
                    val bbGrid = GridLayout(context).apply { columnCount = 2 }
                    val recBtn = actionButton("REC ●", "記録開始", color("#EA4335")) { toggleRecording() } as TextView
                    blackBoxRecButton = recBtn
                    bbGrid.addView(recBtn, gridParams())
                    bbGrid.addView(actionButton("CLEAR", "消去", color("#607D8B")) { clearBlackBox() }, gridParams())
                    bbGrid.addView(actionButton("MARK", "目印", color("#7C3AED")) { markBlackBox() }, gridParams())
                    bbGrid.addView(actionButton("EXPORT", "CSV保存", color("#0B57D0")) { exportBlackBoxCsv() }, gridParams())
                    addView(glassCard(bbGrid))
                    addSpace(18)

                    // --- リプレイ ---
                    addView(sectionTitle("⏪ REPLAY", "リプレイ再生"))
                    addSpace(4)
                    addView(descriptionText("記録を巻き戻して各時点の操縦状態を再現します。再生中もロボットには送信しません（安全）。"))
                    addSpace(8)
                    val replay = ReplayPadView(context)
                    replayPadView = replay
                    addView(glassBox().apply {
                        addView(replay, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)))
                    })
                    addSpace(8)
                    val replayState = monoText("記録がありません").apply { textSize = 12f }
                    replayStateText = replayState
                    addView(glassCard(replayState))
                    addSpace(8)
                    val seek = SeekBar(context).apply {
                        max = 0
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                                if (fromUser) {
                                    if (replayPlaying) toggleReplayPlay()
                                    showReplayFrame(p)
                                }
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }
                    replaySeek = seek
                    addView(seek)
                    addSpace(8)
                    val replayGrid = GridLayout(context).apply { columnCount = 4 }
                    replayGrid.addView(actionButton("▶ PLAY", "再生", color("#00A86B")) { toggleReplayPlay() }, gridParams())
                    replayGrid.addView(actionButton("⏮", "先頭", color("#607D8B")) { jumpReplay(0) }, gridParams())
                    replayGrid.addView(actionButton("⏭", "末尾", color("#607D8B")) { jumpReplay(blackBox.count - 1) }, gridParams())
                    replayGrid.addView(actionButton("LIVE", "最新へ", color("#0B57D0")) {
                        if (replayPlaying) toggleReplayPlay()
                        jumpReplay(blackBox.count - 1)
                    }, gridParams())
                    addView(glassCard(replayGrid))
                }
                addView(content)
            }
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun toggleRecording() {
        if (blackBox.recording) {
            blackBox.recording = false
            setStatus("BLACK BOX: 記録停止 / ${blackBox.count} サンプル")
        } else {
            blackBox.recording = true
            blackBox.sessionWallMs = System.currentTimeMillis()
            setStatus("BLACK BOX: 記録開始 ●")
        }
        updateRecButton()
        refreshTelemetryUi()
    }

    private fun updateRecButton() {
        val btn = blackBoxRecButton ?: return
        val recording = blackBox.recording
        val accent = if (recording) color("#00A86B") else color("#EA4335")
        btn.text = if (recording) "STOP ■\n記録停止" else "REC ●\n記録開始"
        btn.background = rippleDrawable(tonalSurfaceDrawable(colors.surface, accent, 20f, 0.82f), accent, 20f)
    }

    private fun clearBlackBox() {
        if (replayPlaying) toggleReplayPlay()
        blackBox.clear()
        replayLogical = 0
        replaySeek?.max = 0
        replaySeek?.progress = 0
        replayPadView?.showFrame(null)
        replayStateText?.text = "記録がありません"
        setStatus("BLACK BOX: 記録を消去しました")
        refreshTelemetryUi()
    }

    private fun markBlackBox() {
        if (!blackBox.recording) {
            setStatus("BLACK BOX: 先にRECで記録を開始してください")
            return
        }
        blackBox.recordEvent(SystemClock.elapsedRealtime(), error = false, marker = true)
        setStatus("BLACK BOX: 目印を記録しました")
    }

    private fun exportBlackBoxCsv() {
        if (blackBox.count <= 0) {
            setStatus("BLACK BOX: 記録がありません")
            return
        }
        diagExecutor.execute {
            try {
                val csv = blackBox.toCsv()
                val dir = getExternalFilesDir(null) ?: filesDir
                val name = "blackbox_${blackBox.sessionWallMs.takeIf { it > 0 } ?: System.currentTimeMillis()}.csv"
                val file = File(dir, name)
                file.writeText(csv, StandardCharsets.UTF_8)
                val path = file.absolutePath
                val lines = blackBox.count
                mainHandler.post {
                    try {
                        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("blackbox csv path", path))
                    } catch (_: Exception) {
                    }
                    setStatus("EXPORT: ${lines}行を保存 (パスをコピー)")
                    appendMu3Log("[BLACK BOX] CSV ${lines}行を保存:\n$path\n")
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                mainHandler.post { setStatus("EXPORT 失敗: $msg") }
            }
        }
    }

    private fun toggleReplayPlay() {
        if (blackBox.count < 2) {
            setStatus("REPLAY: 再生できる記録がありません")
            return
        }
        replayPlaying = !replayPlaying
        if (replayPlaying) {
            if (replayLogical >= blackBox.count - 1) jumpReplay(0)
            mainHandler.removeCallbacks(replayTick)
            mainHandler.post(replayTick)
        } else {
            mainHandler.removeCallbacks(replayTick)
        }
        updateReplayPlayButtonStatus()
    }

    private fun updateReplayPlayButtonStatus() {
        setStatus(if (replayPlaying) "REPLAY: 再生中 ▶" else "REPLAY: 一時停止")
    }

    private fun jumpReplay(logical: Int) {
        val target = logical.coerceIn(0, (blackBox.count - 1).coerceAtLeast(0))
        replaySeek?.progress = target
        showReplayFrame(target)
    }

    private fun stepReplay() {
        if (blackBox.count < 2) {
            replayPlaying = false
            return
        }
        replayLogical++
        if (replayLogical >= blackBox.count) {
            replayLogical = blackBox.count - 1
            replayPlaying = false
            mainHandler.removeCallbacks(replayTick)
            updateReplayPlayButtonStatus()
            return
        }
        replaySeek?.progress = replayLogical
        showReplayFrame(replayLogical)
    }

    private fun showReplayFrame(logical: Int) {
        replayLogical = logical
        if (!blackBox.frameAt(logical, replayFrameHolder)) {
            replayPadView?.showFrame(null)
            replayStateText?.text = "記録がありません"
            return
        }
        val f = replayFrameHolder
        replayPadView?.showFrame(f)
        val flags = buildString {
            if (f.marker) append(" 🚩MARK")
            if (f.error) append(" ⚠ERROR")
            if (!f.ack) append(" (応答なし)")
        }
        val lx = f.p0 - 128
        val ly = f.p1 - 128
        val rx = f.p2 - 128
        val ry = f.p3 - 128
        replayStateText?.text = "t=${"%.2f".format(Locale.US, f.tMs / 1000.0)}s  #${logical + 1}/${blackBox.count}  間隔${f.gapMs}ms$flags\n" +
            "L(${lx},${ly}) R(${rx},${ry})  " +
            "P4=${"%02X".format(f.p4)} P5=${"%02X".format(f.p5)} P6=${"%02X".format(f.p6)}" +
            (if (f.rssi >= 0) "  RSSI=${f.rssi}" else "")
    }

    private fun refreshTelemetryUi() {
        val statsText = telemetryStatsText ?: return
        // 重い更新はTELEMETRYタブ表示中のみ。
        if (selectedTabIndex != telemetryTabIndex) return

        blackBox.recentStats(3000L, telemetryStatsBuf)
        val hz = telemetryStatsBuf[0]
        val ackPct = telemetryStatsBuf[1]
        val errs = telemetryStatsBuf[2].toInt()
        val recState = if (blackBox.recording) "REC ● 記録中" else "停止中"
        val ackStr = if (ackPct < 0) "--" else "${ackPct.roundToInt()}%"
        val txState = if (isSending) "送信中 ${stableTxIntervalMs()}ms" else "停止"
        statsText.text = "状態: $txState   実測: ${"%.1f".format(Locale.US, hz)} Hz\n" +
            "リンク健全度(ACK): $ackStr   直近エラー: $errs\n" +
            "ブラックボックス: $recState   サンプル: ${blackBox.count} (累計 ${blackBox.totalRecorded})"

        telemetryGraph?.let { g ->
            g.targetIntervalMs = stableTxIntervalMs().toFloat()
            g.invalidate()
        }

        blackBoxStateText?.text = if (blackBox.recording) {
            "記録中 ● / サンプル ${blackBox.count} / 累計 ${blackBox.totalRecorded}"
        } else {
            "記録停止中 / サンプル ${blackBox.count}"
        }

        // リプレイのシーク範囲を記録数に追従させる（再生・操作中は触らない）。
        val seek = replaySeek
        if (seek != null && !replayPlaying) {
            val newMax = (blackBox.count - 1).coerceAtLeast(0)
            if (seek.max != newMax) seek.max = newMax
        }
    }

    // ============================================================
    // ===== AI COPILOT : オンデバイス知能レイヤー =====
    //  クラウド不要。送信スレッドが吐く確保ゼロのライブ信号だけを使い、
    //  適応オートチューン / 異常検知 / 走行AI解析 / マクロ自動操縦 を実現する。
    // ============================================================

    private val aiEwmaAlpha = 0.10

    /** 送信スレッドから確保ゼロで呼ばれるライブ信号取り込み。記録ON/OFFに依らず常時動く。 */
    private fun aiOnTx(ack: Boolean, gapMs: Int, error: Boolean) {
        val a = aiEwmaAlpha
        val ackV = if (ack) 1.0 else 0.0
        aiAckEwma = if (aiAckEwma < 0) ackV else aiAckEwma + a * (ackV - aiAckEwma)
        val errV = if (error) 1.0 else 0.0
        aiErrEwma += a * (errV - aiErrEwma)
        if (gapMs > 0) {
            val g = gapMs.toDouble()
            val mean = if (aiGapEwma <= 0.0) g else aiGapEwma
            aiGapEwma = mean + a * (g - mean)
            val d = g - aiGapEwma
            aiGapVarEwma += a * (d * d - aiGapVarEwma)
        }
        aiLastTxMs = SystemClock.elapsedRealtime()
        aiTxTotal++
    }

    /** 500msごとにメインスレッドで回るAIの心拍。診断・検知・自動調整を一括で行う。 */
    private fun aiEvaluate() {
        val now = SystemClock.elapsedRealtime()
        val jitter = sqrt(aiGapVarEwma.coerceAtLeast(0.0))
        val ackPct = if (aiAckEwma < 0) -1.0 else aiAckEwma * 100.0
        val targetMs = stableTxIntervalMs().toDouble()

        val score = aiComputeHealthScore(ackPct, aiErrEwma, jitter, targetMs)
        aiHealthScore = score
        aiHealthGrade = aiGradeForScore(score)

        val severity = aiRunSentinel(now, ackPct, aiErrEwma, jitter, targetMs)
        aiRunAutoTune(now, ackPct, aiErrEwma, jitter)

        if (selectedTabIndex == copilotTabIndex) {
            aiUpdateUi(ackPct, jitter, score, severity)
            aiGaugeView?.invalidate()
        }
    }

    private fun aiComputeHealthScore(ackPct: Double, errEwma: Double, jitter: Double, targetMs: Double): Double {
        if (!isSending || aiTxTotal < 3) return -1.0
        val ack = if (ackPct < 0) 0.0 else ackPct
        val errPenalty = (errEwma * 100.0 * 1.5).coerceIn(0.0, 60.0)
        val jitterRatio = if (targetMs > 0) jitter / targetMs else 0.0
        val jitterPenalty = (jitterRatio * 100.0).coerceIn(0.0, 40.0)
        return (ack - errPenalty - jitterPenalty).coerceIn(0.0, 100.0)
    }

    private fun aiGradeForScore(s: Double): String = when {
        s < 0 -> "--"
        s >= 92 -> "S"
        s >= 82 -> "A"
        s >= 70 -> "B"
        s >= 55 -> "C"
        s >= 35 -> "D"
        else -> "F"
    }

    private fun aiScoreColor(s: Double): Int = when {
        s >= 82 -> color("#00C853")
        s >= 70 -> color("#64DD17")
        s >= 55 -> color("#FFC400")
        s >= 35 -> color("#FF6D00")
        s >= 0 -> color("#D50000")
        else -> colors.textSecondary
    }

    /** 異常検知。深刻度 0=正常/待機 1=注意 2=警告 3=危険 を返し、悪化時のみログする。 */
    private fun aiRunSentinel(now: Long, ackPct: Double, errEwma: Double, jitter: Double, targetMs: Double): Int {
        if (!isSending) { aiLastSeverity = -1; return 0 }
        var sev = 0
        var msg = "リンク正常"
        val stallMs = max(400.0, targetMs * 4).toLong()
        if (now > aiSuppressStallUntilMs && aiLastTxMs > 0 && now - aiLastTxMs > stallMs) {
            sev = 3; msg = "送信が停止しています（フリーズ/USB断の疑い）"
        } else if (ackPct in 0.0..55.0) {
            sev = 3; msg = "リンク断の危険：ACK ${ackPct.roundToInt()}%"
        } else if (errEwma > 0.08) {
            sev = 2; msg = "送信エラー多発：再送が連続しています"
        } else if (ackPct in 0.0..85.0) {
            sev = 2; msg = "リンク低下：ACK ${ackPct.roundToInt()}%"
        } else if (targetMs > 0 && jitter > targetMs * 0.45) {
            sev = 1; msg = "ジッタ増大：±${jitter.roundToInt()}ms"
        }
        if (sev > aiLastSeverity && sev >= 1) {
            val tag = when (sev) { 3 -> "■危険"; 2 -> "●警告"; else -> "▲注意" }
            aiPushLog("[$tag] $msg")
        }
        aiLastSeverity = sev
        return sev
    }

    private fun aiPushLog(text: String) {
        aiSentinelLog.addFirst(text)
        while (aiSentinelLog.size > 8) aiSentinelLog.removeLast()
    }

    /** 適応オートチューン：リンク健全度を見ながら、壊れない範囲で送信周期を最速へ寄せる。 */
    private fun aiRunAutoTune(now: Long, ackPct: Double, errEwma: Double, jitter: Double) {
        val cur = txIntervalMs
        if (!isSending || autopilotActive || aiTxTotal < 25) {
            aiRecommendedIntervalMs = cur
            return
        }
        val good = ackPct >= 90.0 && errEwma < 0.03 && (cur <= 10L || jitter < cur * 0.4)
        if (good) {
            autoTuneStableStreak++
            if (cur < autoTuneSafeFloorMs) autoTuneSafeFloorMs = cur
            aiRecommendedIntervalMs = if (autoTuneStableStreak >= 3) {
                max(10.0, (cur - aiTuneStep(cur)).toDouble()).toLong()
            } else cur
        } else {
            autoTuneStableStreak = 0
            val backoff = (cur + aiTuneStep(cur) * 2).coerceAtMost(1000L)
            if (backoff > autoTuneSafeFloorMs) autoTuneSafeFloorMs = backoff
            aiRecommendedIntervalMs = backoff
        }

        if (!autoTuneOn) return
        if (now - autoTuneLastActionMs < 1600L) return
        val target = aiRecommendedIntervalMs.coerceIn(10L, 1000L)
        if (target != cur && ((good && target < cur) || (!good && target > cur))) {
            autoTuneLastActionMs = now
            aiSuppressStallUntilMs = now + 2200L
            autoTuneStableStreak = 0
            aiPushLog("⚙ AUTO-TUNE: ${cur}ms → ${target}ms")
            setTxInterval(target.toInt())
        }
    }

    private fun aiTuneStep(cur: Long): Long = when {
        cur > 200 -> 20L
        cur > 80 -> 10L
        cur > 30 -> 5L
        else -> 2L
    }

    private fun aiUpdateUi(ackPct: Double, jitter: Double, score: Double, severity: Int) {
        val ackStr = if (ackPct < 0) "--" else "${ackPct.roundToInt()}%"
        val hz = if (aiGapEwma > 0) 1000.0 / aiGapEwma else 0.0
        val txState = if (isSending) "送信中 ${stableTxIntervalMs()}ms" else "停止"
        val sevLabel = when (severity) {
            3 -> "■ 危険"
            2 -> "● 警告"
            1 -> "▲ 注意"
            else -> if (isSending) "✔ 正常" else "待機"
        }
        aiSentinelText?.text = buildString {
            append("総合グレード: $aiHealthGrade  (${if (score < 0) "--" else score.roundToInt().toString()}/100)\n")
            append("状態: $txState\n")
            append("リンク健全度(ACK): $ackStr\n")
            append("実測: ${"%.1f".format(Locale.US, hz)}Hz  ジッタ±${jitter.roundToInt()}ms\n")
            append("センチネル: $sevLabel")
        }
        autoTuneText?.text = buildString {
            append(if (autoTuneOn) "AUTO-TUNE: ON ● 自動最適化中\n" else "AUTO-TUNE: OFF（手動）\n")
            append("現在周期: ${stableTxIntervalMs()}ms\n")
            append("AI推奨周期: ${if (aiRecommendedIntervalMs > 0) "${aiRecommendedIntervalMs}ms" else "--"}\n")
            append("安定ストリーク: $autoTuneStableStreak")
        }
        aiSentinelLogText?.text = if (aiSentinelLog.isEmpty()) "異常イベントなし" else aiSentinelLog.joinToString("\n")
    }

    // ---------- 適応オートチューン UI操作 ----------
    private fun toggleAutoTune() {
        autoTuneOn = !autoTuneOn
        autoTuneStableStreak = 0
        autoTuneLastActionMs = SystemClock.elapsedRealtime()
        autoTuneButton?.text = if (autoTuneOn) "AUTO-TUNE: ON ●\n自動最適化中" else "AUTO-TUNE: OFF\n自動最適化"
        setStatus(if (autoTuneOn) "適応オートチューンON：リンク健全度を見ながら最速周期を自動探索します" else "適応オートチューンOFF")
        ensureAiHeartbeat()
    }

    private fun applyAiRecommendation() {
        if (!isSending) { setStatus("送信中のみAI推奨を反映できます"); return }
        val rec = aiRecommendedIntervalMs.coerceIn(10L, 1000L)
        if (rec <= 0L) { setStatus("まだ推奨値を算出していません（数秒お待ちください）"); return }
        aiSuppressStallUntilMs = SystemClock.elapsedRealtime() + 2200L
        setTxInterval(rec.toInt())
        setStatus("AI推奨の送信周期 ${rec}ms を反映しました")
    }

    // ---------- マクロ自動操縦 ----------
    private fun toggleLearn() {
        if (autopilotActive) { setStatus("自動操縦中は学習できません"); return }
        if (macroRec.learning) {
            macroRec.stop()
            setStatus("学習停止：${macroRec.count}フレーム（${macroRec.durationMs()}ms）を記録しました")
        } else {
            if (!isSending) { setStatus("先に送信を開始してから学習してください（送信した操作を学習します）"); return }
            macroRec.start()
            setStatus("学習開始：今の操縦を記録中…もう一度押すと停止します")
        }
        updateLearnButton()
        updateMacroState()
    }

    private fun updateLearnButton() {
        learnButton?.text = if (macroRec.learning) "LEARN ●\n学習停止" else "LEARN\nマクロ学習"
    }

    private fun updateMacroState() {
        macroStateText?.text = when {
            macroRec.learning -> "学習中 ● / ${macroRec.count}フレーム"
            macroRec.count > 0 -> "マクロ保有: ${macroRec.count}フレーム / ${macroRec.durationMs()}ms"
            else -> "マクロ未学習。送信中にLEARNで現在の操縦を記録します。"
        }
    }

    private fun toggleLoop() {
        autopilotLoop = !autopilotLoop
        loopButton?.text = if (autopilotLoop) "LOOP: ON ●\n繰返し再生" else "LOOP: OFF\n繰返し再生"
    }

    private fun confirmAndRunAutopilot() {
        if (autopilotActive) { stopAutopilot(); return }
        if (macroRec.count <= 0) { setStatus("先にマクロを学習してください"); return }
        if (macroRec.learning) { setStatus("学習を停止してから実行してください"); return }
        if (!isSending) { setStatus("先にMU-3へ送信を開始してください"); return }
        AlertDialog.Builder(this)
            .setTitle("⚠ 自動操縦を開始します")
            .setMessage(
                "学習した${macroRec.count}フレーム（約${macroRec.durationMs()}ms）の操縦をロボットへ自動再生します。" +
                    "${if (autopilotLoop) "（ループON）" else ""}\n\n" +
                    "安全確認：必ず車輪を浮かせ、周囲に人がいないことを確認してください。" +
                    "EMERGENCY STOPまたはSTOPでいつでも停止できます。"
            )
            .setPositiveButton("実行") { _, _ -> startAutopilot(autopilotLoop) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startAutopilot(loop: Boolean) {
        stopSavedNavigation()
        if (autopilotActive) return
        if (macroRec.count <= 0 || !isSending) return
        autopilotActive = true
        autopilotProgress = 0
        val t = Thread { autopilotRun(loop) }
        t.name = "Mu3Autopilot"
        t.priority = Thread.NORM_PRIORITY
        autopilotThread = t
        t.start()
        updateAutopilotButton()
        updateMacroState()
        setStatus("自動操縦 開始")
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 自動操縦ワーカー。マクロのタイミングを守りつつ「次に送るpayload」を更新するだけ。
     * 実際のシリアル送信と周期は送信ループが従来どおり担うので、安全に分離できる。
     */
    private fun autopilotRun(loop: Boolean) {
        val out = ByteArray(7)
        try {
            do {
                val n = macroRec.count
                var prevT = 0L
                var i = 0
                while (i < n && autopilotActive) {
                    val tRel = macroRec.frameInto(i, out)
                    val wait = tRel - prevT
                    prevT = tRel
                    if (wait in 1..5000) {
                        try {
                            Thread.sleep(wait)
                        } catch (_: InterruptedException) {
                            autopilotActive = false
                            break
                        }
                    }
                    synchronized(autopilotLock) { System.arraycopy(out, 0, autopilotPayload, 0, 7) }
                    autopilotProgress = i + 1
                    i++
                }
            } while (loop && autopilotActive)
        } finally {
            synchronized(autopilotLock) {
                val nu = PadState.neutralPayload()
                System.arraycopy(nu, 0, autopilotPayload, 0, 7)
            }
            autopilotActive = false
            mainHandler.post {
                updateAutopilotButton()
                updateMacroState()
                if (::statusText.isInitialized) setStatus("自動操縦 終了：ニュートラルへ復帰")
            }
        }
    }

    private fun stopAutopilot() {
        stopAutopilotInternal(neutralize = true)
        setStatus("自動操縦 停止")
    }

    /** 自動操縦を確実に止め、送るpayloadをニュートラルへ戻す。停止系すべてから呼ばれる安全弁。 */
    private fun stopAutopilotInternal(neutralize: Boolean) {
        val wasActive = autopilotActive
        autopilotActive = false
        autopilotThread?.interrupt()
        autopilotThread = null
        if (neutralize || wasActive) {
            synchronized(autopilotLock) {
                val nu = PadState.neutralPayload()
                System.arraycopy(nu, 0, autopilotPayload, 0, 7)
            }
        }
        if (wasActive && ::statusText.isInitialized) {
            mainHandler.post {
                updateAutopilotButton()
                updateMacroState()
            }
        }
    }

    private fun updateAutopilotButton() {
        autopilotButton?.text = if (autopilotActive) "■ STOP\n自動操縦停止" else "▶ AUTOPILOT\n自動操縦実行"
    }

    // ---------- 走行AI解析レポート ----------
    private fun generateAiReport() {
        val n = blackBox.count
        if (n < 2) {
            aiReportText?.text = "記録がありません。TELEMETRYタブでブラックボックス記録(REC)を行ってから解析してください。"
            return
        }
        val f = aiReportFrame
        var active = 0; var ackCount = 0; var ackSamples = 0; var errCount = 0
        var sumGap = 0.0; var sumGap2 = 0.0; var gapN = 0
        var maxMag = 0.0
        var fwd = 0; var back = 0; var left = 0; var right = 0
        var btnFrames = 0
        var firstT = 0L; var lastT = 0L
        for (k in 0 until n) {
            if (!blackBox.frameAt(k, f)) continue
            if (k == 0) firstT = f.tMs
            lastT = f.tMs
            val dxL = f.p0 - 128; val dyL = f.p1 - 128
            val dxR = f.p2 - 128; val dyR = f.p3 - 128
            val mag = (abs(dxL) + abs(dyL) + abs(dxR) + abs(dyR)) / 4.0 / 128.0
            if (mag > maxMag) maxMag = mag
            val moving = abs(dxL) > 12 || abs(dyL) > 12 || abs(dxR) > 12 || abs(dyR) > 12
            val btn = f.p4 != 0 || f.p5 != 0 || f.p6 != 0
            if (moving || btn) active++
            if (btn) btnFrames++
            if (dyL < -16) fwd++ else if (dyL > 16) back++
            if (dxL < -16) left++ else if (dxL > 16) right++
            if (f.gapMs > 0) { sumGap += f.gapMs; sumGap2 += f.gapMs.toDouble() * f.gapMs; gapN++ }
            ackSamples++
            if (f.ack) ackCount++
            if (f.error) errCount++
        }
        val durMs = (lastT - firstT).coerceAtLeast(1L)
        val meanGap = if (gapN > 0) sumGap / gapN else 0.0
        val jitter = if (gapN > 1) sqrt((sumGap2 / gapN - meanGap * meanGap).coerceAtLeast(0.0)) else 0.0
        val hz = if (meanGap > 0) 1000.0 / meanGap else 0.0
        val ackPct = if (ackSamples > 0) ackCount * 100.0 / ackSamples else -1.0
        val activePct = active * 100.0 / n
        val errRate = errCount.toDouble() / n
        val jr = if (meanGap > 0) jitter / meanGap else 0.0
        val scoreR = ((if (ackPct < 0) 0.0 else ackPct) -
            (errRate * 100 * 1.5).coerceIn(0.0, 60.0) -
            (jr * 100).coerceIn(0.0, 40.0)).coerceIn(0.0, 100.0)
        val grade = aiGradeForScore(scoreR)
        val recommend = aiRecommendIntervalFromReport(ackPct, meanGap, jitter)

        aiReportText?.text = buildString {
            append("◤ 走行AI解析レポート ◥\n")
            append("─────────────────\n")
            append("総合グレード: $grade（${scoreR.roundToInt()}/100）\n")
            append("記録時間: ${"%.1f".format(Locale.US, durMs / 1000.0)}秒 / ${n}フレーム\n")
            append("実測レート: ${"%.1f".format(Locale.US, hz)} Hz（平均${meanGap.roundToInt()}ms / ジッタ±${jitter.roundToInt()}ms）\n")
            append("リンク健全度: ${if (ackPct < 0) "--" else "${ackPct.roundToInt()}%"}  エラー: $errCount\n")
            append("稼働率: ${activePct.roundToInt()}%（操作していた割合）\n")
            append("最大スティック強度: ${(maxMag * 100).roundToInt()}%\n")
            append("移動傾向(左ST): 前${fwd} / 後${back} / 左${left} / 右${right}\n")
            append("ボタン操作フレーム: $btnFrames\n")
            append("─────────────────\n")
            append("AI提案:\n")
            append(aiReportAdvice(ackPct, jitter, meanGap, errCount, recommend))
        }
        setStatus("AI解析を更新しました（グレード $grade）")
    }

    private fun aiRecommendIntervalFromReport(ackPct: Double, meanGap: Double, jitter: Double): Long {
        if (meanGap <= 0) return txIntervalMs
        return when {
            ackPct >= 95 && jitter < meanGap * 0.25 -> max(10.0, meanGap - 10).toLong()
            ackPct in 0.0..85.0 -> (meanGap * 1.3).toLong().coerceAtMost(1000L)
            else -> meanGap.toLong()
        }
    }

    private fun aiReportAdvice(ackPct: Double, jitter: Double, meanGap: Double, errCount: Int, recommend: Long): String {
        val parts = ArrayList<String>()
        if (ackPct in 0.0..85.0) parts.add("リンク健全度が低めです。baudを下げるか送信周期を${(meanGap * 1.3).roundToInt()}ms程度に緩めると安定します。")
        if (meanGap > 0 && jitter > meanGap * 0.4) parts.add("ジッタが大きいです。端末の負荷軽減や周期の引き上げで改善します。")
        if (errCount > 0) parts.add("送信エラーが${errCount}回。USB接触・電源・距離を確認してください。")
        if (ackPct >= 95 && meanGap > 0 && jitter < meanGap * 0.25 && errCount == 0) {
            parts.add("非常に良好です。送信周期を${recommend}msまで詰めて応答性を上げられる余地があります。")
        }
        if (parts.isEmpty()) parts.add("特筆すべき問題は検出されませんでした。")
        return parts.joinToString("\n", prefix = "・", postfix = "")
            .replace("\n", "\n・")
    }

    private fun createCopilotTab(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(16))

                    // --- 総合知能診断 + ゲージ ---
                    addView(sectionTitle("🧠 AI HEALTH", "総合リンク知能診断"))
                    addSpace(8)
                    val gauge = AiGaugeView(context)
                    aiGaugeView = gauge
                    val sentinel = monoText("接続して送信を開始すると、AIがリンク状態をリアルタイム診断します。").apply { textSize = 12f }
                    aiSentinelText = sentinel
                    addView(glassBox().apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(gauge, LinearLayout.LayoutParams(dp(116), dp(116)))
                        addSpaceHorizontal(10)
                        addView(sentinel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    })
                    addSpace(16)

                    // --- 異常検知センチネル ---
                    addView(sectionTitle("🛰 ANOMALY SENTINEL", "異常検知"))
                    addSpace(6)
                    addView(descriptionText("リンク低下・ジッタ急増・送信ストール・エラー多発をAIが常時監視し、悪化した瞬間を記録します。"))
                    addSpace(8)
                    val slog = monoText("異常イベントなし").apply { textSize = 12f }
                    aiSentinelLogText = slog
                    addView(glassCard(slog))
                    addSpace(16)

                    // --- 適応オートチューン ---
                    addView(sectionTitle("⚙ ADAPTIVE AUTO-TUNE", "適応オートチューン"))
                    addSpace(6)
                    addView(descriptionText("リンク健全度を見ながら、壊れない範囲で送信周期を自動的に最速へ最適化します。"))
                    addSpace(8)
                    val tuneInfo = monoText("AUTO-TUNE OFF").apply { textSize = 12f }
                    autoTuneText = tuneInfo
                    addView(glassCard(tuneInfo))
                    addSpace(8)
                    val tuneGrid = GridLayout(context).apply { columnCount = 2 }
                    val tuneBtn = actionButton("AUTO-TUNE: OFF", "自動最適化", color("#7C3AED")) { toggleAutoTune() } as TextView
                    autoTuneButton = tuneBtn
                    tuneGrid.addView(tuneBtn, gridParams())
                    tuneGrid.addView(actionButton("APPLY", "推奨値を反映", color("#0B57D0")) { applyAiRecommendation() }, gridParams())
                    addView(glassCard(tuneGrid))
                    addSpace(16)

                    // --- マクロ自動操縦 ---
                    addView(sectionTitle("🤖 MACRO AUTOPILOT", "マクロ自動操縦"))
                    addSpace(6)
                    addView(descriptionText("操縦を学習(LEARN)し、ワンタップで同じ動きをロボットに自動再生(AUTOPILOT)。必ず車輪を浮かせて検証してください。"))
                    addSpace(8)
                    val macroState = monoText("マクロ未学習。送信中にLEARNで現在の操縦を記録します。").apply { textSize = 12f }
                    macroStateText = macroState
                    addView(glassCard(macroState))
                    addSpace(8)
                    val macroGrid = GridLayout(context).apply { columnCount = 3 }
                    val lBtn = actionButton("LEARN", "マクロ学習", color("#EA4335")) { toggleLearn() } as TextView
                    learnButton = lBtn
                    macroGrid.addView(lBtn, gridParams())
                    val apBtn = actionButton("▶ AUTOPILOT", "自動操縦実行", color("#00A86B")) { confirmAndRunAutopilot() } as TextView
                    autopilotButton = apBtn
                    macroGrid.addView(apBtn, gridParams())
                    macroGrid.addView(actionButton("CLEAR", "マクロ消去", color("#607D8B")) {
                        if (autopilotActive) {
                            setStatus("自動操縦中は消去できません")
                        } else {
                            macroRec.clear()
                            updateMacroState()
                            updateLearnButton()
                            setStatus("マクロを消去しました")
                        }
                    }, gridParams())
                    addView(glassCard(macroGrid))
                    addSpace(8)
                    val loopBtn = actionButton("LOOP: OFF", "繰返し再生", color("#FF8F00")) { toggleLoop() } as TextView
                    loopButton = loopBtn
                    addView(glassCard(loopBtn))
                    addSpace(16)

                    // --- 走行AI解析レポート ---
                    addView(sectionTitle("📊 AI SESSION REPORT", "走行AI解析"))
                    addSpace(6)
                    addView(descriptionText("ブラックボックス記録をAIが解析し、リンク品質・稼働率・移動傾向・改善提案をまとめます。"))
                    addSpace(8)
                    addView(actionButton("ANALYZE", "記録を解析", color("#0B57D0")) { generateAiReport() })
                    addSpace(8)
                    val report = monoText("「ANALYZE」を押すと、直近のブラックボックス記録を解析します。").apply { textSize = 12f }
                    aiReportText = report
                    addView(glassCard(report))
                }
                addView(content)
            }
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** AI総合スコアを円弧ゲージとグレードで描く。 */
    private inner class AiGaugeView(context: Context) : View(context) {
        private val arcRect = RectF()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(10).toFloat(); strokeCap = Paint.Cap.ROUND
            color = alphaColor(colors.outline, 0.5f)
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(10).toFloat(); strokeCap = Paint.Cap.ROUND
        }
        private val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = colors.textSecondary
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val padPx = dp(14).toFloat()
            val size = min(w, h) - padPx * 2
            val cx = w / 2f; val cy = h / 2f
            arcRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            val start = 135f; val sweepMax = 270f
            canvas.drawArc(arcRect, start, sweepMax, false, bgPaint)
            val score = aiHealthScore
            if (score >= 0) {
                val frac = (score / 100.0).toFloat().coerceIn(0f, 1f)
                fgPaint.color = aiScoreColor(score)
                canvas.drawArc(arcRect, start, sweepMax * frac, false, fgPaint)
            }
            gradePaint.color = aiScoreColor(score)
            gradePaint.textSize = size * 0.34f
            val fm = gradePaint.fontMetrics
            canvas.drawText(aiHealthGrade, cx, cy - (fm.ascent + fm.descent) / 2f, gradePaint)
            labelPaint.textSize = dp(11).toFloat()
            canvas.drawText(if (score < 0) "待機中" else "SCORE ${score.roundToInt()}", cx, cy + size * 0.32f, labelPaint)
        }
    }

    /** 操縦マクロ：送信した7バイトpayloadを時系列で確保ゼロ記録し、自動操縦で再生する。 */
    private class MacroRecorder(val capacity: Int) {
        private val tMs = LongArray(capacity)
        private val b = Array(7) { ByteArray(capacity) }
        var count = 0
            private set
        @Volatile var learning = false
            private set
        private var baseMs = 0L

        @Synchronized fun start() { count = 0; baseMs = 0L; learning = true }
        @Synchronized fun stop() { learning = false }
        @Synchronized fun clear() { count = 0; baseMs = 0L; learning = false }

        /** 送信スレッドから確保ゼロで呼ばれる。容量到達で自動的に学習停止。 */
        @Synchronized fun add(nowMs: Long, payload: ByteArray) {
            if (!learning) return
            if (count >= capacity) { learning = false; return }
            if (count == 0) baseMs = nowMs
            val i = count
            tMs[i] = nowMs - baseMs
            for (j in 0 until 7) b[j][i] = payload[j]
            count++
        }

        /** i番目フレームをoutへ展開し、開始からの相対msを返す。 */
        @Synchronized fun frameInto(i: Int, out: ByteArray): Long {
            if (i < 0 || i >= count) return 0L
            for (j in 0 until 7) out[j] = b[j][i]
            return tMs[i]
        }

        @Synchronized fun durationMs(): Long = if (count > 0) tMs[count - 1] else 0L
    }

    private fun addVirtualButtonAndEdit() {
        val latest = currentConfig()
        if (latest.screenButtons.size >= maxScreenButtons) {
            setStatus("画面ボタンは最大 $maxScreenButtons 個までです")
            return
        }
        val slot = latest.screenButtons.size + 1
        val newButton = ScreenButtonSpec(
            slot = slot,
            label = "BTN$slot",
            targetId = firstUnusedExtraTarget(latest),
            mode = FeatureMode.MOMENTARY
        )
        replaceConfig(latest.copy(screenButtons = latest.screenButtons + newButton), "画面ボタン BTN$slot を追加しました")
        showScreenButtonEditor(newButton)
    }

    private fun applySmartVirtualDeck() {
        val smartButtons = listOf(
            ScreenButtonSpec(1, "PULSE", "P6_B1_EXTRA1", FeatureMode.MOMENTARY),
            ScreenButtonSpec(2, "BURST", "P6_B2_EXTRA2", FeatureMode.MOMENTARY),
            ScreenButtonSpec(3, "MARK", "P6_B3_EXTRA3", FeatureMode.MOMENTARY),
            ScreenButtonSpec(4, "ARM", "P6_B4_EXTRA4", FeatureMode.TOGGLE),
            ScreenButtonSpec(5, "HOLD", "P6_B5_EXTRA5", FeatureMode.TOGGLE),
            ScreenButtonSpec(6, "LOCK", "P6_B6_EXTRA6", FeatureMode.TOGGLE),
            ScreenButtonSpec(7, "BEACON", "P6_B7_EXTRA7", FeatureMode.TOGGLE)
        )
        clearVirtualButtonStates()
        replaceConfig(currentConfig().copy(screenButtons = smartButtons), "SMART DECKを生成しました")
    }

    private fun clearVirtualButtonStates(status: String? = null) {
        synchronized(pad) { pad.clearScreenButtons() }
        updateUiState()
        if (status != null) setStatus(status)
    }

    private fun createMu3TestTab(): View {
        val screenWidthDp = resources.configuration.screenWidthDp
        val isWide = screenWidthDp >= 840
        val quickColumns = if (screenWidthDp >= 600) 3 else 2
        val sidePanelColumns = if (screenWidthDp >= 960) 3 else 2

        linkUiInput = formInput("0000")
        linkGiInput = formInput("00")
        linkChInput = formInput("07")
        linkEiInput = formInput("01")
        linkDiInput = formInput("02")
        scanStartInput = formInput("02")
        scanEndInput = formInput("19")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), 0)
            setBackgroundColor(Color.TRANSPARENT)

            addView(mu3HeroPanel())
            addSpace(6)
        }

        val overviewPage = mu3PageScroll {
            val radioQualityPanel = mu3QualityPanel()
            val packetStatusPanel = mu3Panel("TX TEST STATUS", "送信テスト", color("#00A86B")) {
                addView(miniLabel("PACKET RESULT"))
                mu3PacketText = monoText("READY").apply {
                    textSize = 12f
                    setTextColor(if (isDarkMode) color("#A7F3D0") else color("#0B8043"))
                }
                addView(infoBox(mu3PacketText).apply { minimumHeight = dp(78) })
                addSpace(8)
                this.addMu3ActionGrid(2) {
                    this.addMu3Button("PACKET", "100回テスト", color("#00A86B")) { runPacketTest(100) }
                    this.addMu3Button("WAKE", "@VR 確認", color("#F59E0B")) { runWakeTest() }
                }
            }
            addMu3ResponsivePair(radioQualityPanel, packetStatusPanel, isWide, 1f, 1.1f)
        }

        val linkPage = mu3PageScroll {
            val linkPanel = mu3Panel("LINK SETTINGS", "リンク設定", color("#00A8A8")) {
                addView(mu3FormRow("User ID", "@UI", linkUiInput, 86))
                addView(mu3FormRow("Group", "@GI", linkGiInput))
                addView(mu3FormRow("Channel", "@CH", linkChInput))
                addView(mu3FormRow("Own ID", "@EI", linkEiInput))
                addView(mu3FormRow("Target ID", "@DI", linkDiInput))
                addSpace(10)

                this.addMu3ActionGrid(2) {
                    this.addMu3Button("GET LINK", "現在値", color("#00A8A8")) { readLinkParams() }
                    this.addMu3Button("APPLY", "一時反映", color("#00A86B")) { applyLinkParams(false) }
                    this.addMu3Button("SAVE ROM", "/W保存", color("#FFB300")) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("/Wで保存しますか？")
                            .setMessage("MU-3のEEPROMにリンク設定を保存します。")
                            .setPositiveButton("保存") { _, _ -> applyLinkParams(true) }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    }
                    this.addMu3Button("FAST SET", "57600/30", color("#34A853")) { applyFastPreset() }
                }
            }

            val linkActionPanel = mu3Panel("LINK TOOLS", "接続・復旧", color("#64748B")) {
                this.addMu3ActionGrid(sidePanelColumns) {
                    this.addMu3Button("CONNECT", "USB接続", color("#BEEAE6")) { connectUsbSerial() }
                    this.addMu3Button("READ ALL", "@UI/@GI...", color("#00A8A8")) { readLinkParams() }
                    this.addMu3Button("QUALITY", "RSSI/SN", color("#0B57D0")) { runQualityCheck() }
                    this.addMu3Button("AUTO BAUD", "自動復旧", color("#FF6F61")) { autoRecoverMu3Response() }
                    this.addMu3Button("FAST SET", "57600/30", color("#34A853")) { applyFastPreset() }
                    this.addMu3Button("NEUTRAL", "安全停止", color("#F59E0B")) {
                        stopSending()
                        synchronized(pad) { pad.clearAll() }
                        updateUiState()
                        sendNeutralBurstAsync(10, "DIAG: NEUTRAL x10")
                    }
                }
            }
            addMu3ResponsivePair(linkPanel, linkActionPanel, isWide, 1.1f, 0.9f)
        }

        val commandPage = mu3PageScroll {
            val commandPanel = mu3Panel("MU-3 COMMANDS", "コマンド", color("#7C3AED")) {
                addView(miniLabel("CUSTOM COMMAND"))
                val customInputRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    mu3CommandInput = formInput("@GI").apply { textSize = 14f }
                    addView(mu3CommandInput, LinearLayout.LayoutParams(0, dp(42), 1f))
                    addSpaceHorizontal(8)
                    addView(smallButton("SEND", color("#00A86B")) { sendDiagCommand(mu3CommandInput.text.toString()) })
                }
                addView(customInputRow)
                addSpace(10)

                val commandResultHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(miniLabel("LAST RESPONSE"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(smallButton("CLEAR", color("#64748B")) {
                        if (::mu3CommandResultText.isInitialized) mu3CommandResultText.text = "READY"
                    })
                }
                addView(commandResultHeader)
                mu3CommandResultText = monoText("READY").apply {
                    textSize = 10.5f
                    setTextColor(colors.textSecondary)
                }
                addView(infoBox(mu3CommandResultText).apply { minimumHeight = dp(if (isWide) 112 else 104) })
                addSpace(10)

                val quickCommands = listOf(
                    "@GI" to "GROUP", "@VR" to "VERSION", "@UI" to "USER",
                    "@CH" to "CHANNEL", "@RA" to "RSSI", "@CR" to "S/N",
                    "@IZ" to "RESET", "@DT" to "PACKET", "WAKE" to "WAKE"
                )
                this.addMu3ActionGrid(quickColumns) {
                    for ((cmd, label) in quickCommands) {
                        val accent = when (cmd) {
                            "@RA", "@CR" -> color("#0B57D0")
                            "@DT" -> color("#00A86B")
                            "WAKE" -> color("#F59E0B")
                            "@IZ" -> color("#FF6F61")
                            else -> color("#00A8A8")
                        }
                        this.addMu3Button(label, cmd, accent) {
                            when (cmd) {
                                "@IZ" -> AlertDialog.Builder(this@MainActivity)
                                    .setTitle("MU-3を初期化しますか？")
                                    .setMessage("@IZを送信します。")
                                    .setPositiveButton("送信") { _, _ -> sendDiagCommand(cmd) }
                                    .setNegativeButton("キャンセル", null)
                                    .show()
                                "@DT" -> runPacketTest(100)
                                "WAKE" -> runWakeTest()
                                "@RA" -> runRssiCheck()
                                "@CR" -> runSnCheck()
                                else -> sendDiagCommand(cmd)
                            }
                        }
                    }
                }
                addSpace(10)
                addMu3ActionGrid(1) {
                    addMu3Button("AUTO BAUD", "自動復旧", color("#FF6F61")) { autoRecoverMu3Response() }
                }
            }
            addView(commandPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val scanLogPage = mu3PageScroll {
            val scanPanel = mu3Panel("AIR SCAN", "チャンネルスキャン", color("#F59E0B")) {
                addView(mu3RangeRow())
                addSpace(8)
                this.addMu3ActionGrid(2) {
                    this.addMu3Button("FULL", "02-19", color("#64748B")) {
                        scanStartInput.setText("02")
                        scanEndInput.setText("19")
                    }
                    this.addMu3Button("LOW", "02-0D", color("#64748B")) {
                        scanStartInput.setText("02")
                        scanEndInput.setText("0D")
                    }
                    this.addMu3Button("HIGH", "0E-19", color("#64748B")) {
                        scanStartInput.setText("0E")
                        scanEndInput.setText("19")
                    }
                }
                addSpace(10)
                addMu3ActionGrid(2) {
                    addMu3Button("START SCAN", "@CH + @RA", color("#FF6F61")) { runAirScan() }
                    addMu3Button("NEUTRAL x10", "安全停止", color("#F59E0B")) {
                        stopSending()
                        synchronized(pad) { pad.clearAll() }
                        updateUiState()
                        sendNeutralBurstAsync(10, "DIAG: NEUTRAL x10")
                    }
                }
                addSpace(10)
                addView(miniLabel("SCAN RESULT"))
                mu3ScanText = monoText("READY").apply {
                    textSize = 10.5f
                    setTextColor(colors.textSecondary)
                }
                addView(infoBox(mu3ScanText).apply { minimumHeight = dp(if (isWide) 128 else 112) })
            }

            val logPanel = mu3Panel("DIAG LOG", "通信ログ", color("#64748B")) {
                val logHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(miniLabel("SERIAL RESPONSE"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(smallButton("CLEAR", color("#64748B")) {
                        mu3DiagLogBuffer.clear()
                        if (::mu3DiagLogText.isInitialized) mu3DiagLogText.text = ""
                    })
                }
                addView(logHeader)
                mu3DiagLogText = monoText("MU-3 TEST READY").apply {
                    textSize = 10.5f
                    setTextColor(colors.textSecondary)
                }
                addView(infoBox(mu3DiagLogText).apply { minimumHeight = dp(if (isWide) 214 else 240) })
            }
            addMu3ResponsivePair(scanPanel, logPanel, isWide, 0.78f, 1.55f)
        }

        val pages = listOf(overviewPage, linkPage, commandPage, scanLogPage)
        val pageFrame = FrameLayout(this).apply {
            clipToPadding = false
        }
        pages.forEach { page ->
            page.visibility = View.GONE
            pageFrame.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val sectionButtons = mutableListOf<TextView>()
        val tabSpecs = listOf(
            Triple("OVERVIEW", "品質・送信", color("#0B57D0")),
            Triple("LINK", "ID / CH", color("#00A8A8")),
            Triple("COMMAND", "直接送信", color("#7C3AED")),
            Triple("SCAN / LOG", "空きCH・ログ", color("#F59E0B"))
        )
        val selectMu3Section: (Int) -> Unit = { index ->
            pages.forEachIndexed { pageIndex, page ->
                page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE
            }
            sectionButtons.forEachIndexed { buttonIndex, button ->
                updateMu3InternalTabButton(button, buttonIndex == index, tabSpecs[buttonIndex].third)
            }
        }

        val tabRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(colors.surfaceVariant, 24f, strokeColor = colors.outline, strokeWidth = 1)
            tabSpecs.forEachIndexed { index, spec ->
                val button = mu3InternalTabButton(spec.first, spec.second, spec.third).apply {
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        selectMu3Section(index)
                    }
                }
                sectionButtons.add(button)
                addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(2), 0, dp(2))
                })
            }
        }
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToPadding = false
            val railWidth = if (screenWidthDp < 600) 112 else 132
            addView(tabRail, LinearLayout.LayoutParams(dp(railWidth), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, 0, dp(10), 0)
            })
            addView(pageFrame, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(contentRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        selectMu3Section(0)
        return root
    }

    private fun mu3PageScroll(content: LinearLayout.() -> Unit): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.TRANSPARENT)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            content()
        }
        scroll.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun mu3QualityPanel(): View {
        return mu3Panel("RADIO QUALITY", "電波品質", color("#0B57D0")) {
            addView(miniLabel("RSSI / S/N"))
            mu3RssiText = monoText("WAITING...").apply {
                textSize = 12f
                setTextColor(if (isDarkMode) color("#90CAF9") else color("#0B57D0"))
            }
            addView(infoBox(mu3RssiText).apply { minimumHeight = dp(78) })
            addSpace(8)
            this.addMu3ActionGrid(2) {
                this.addMu3Button("RSSI", "@RA 更新", color("#0B57D0")) { runRssiCheck() }
                this.addMu3Button("S/N", "@CR 確認", color("#00A8A8")) { runSnCheck() }
            }
        }
    }

    private fun mu3InternalTabButton(title: String, sub: String, accent: Int): TextView {
        return TextView(this).apply {
            text = "$title\n$sub"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val compact = resources.configuration.screenWidthDp < 600
            setPadding(dp(if (compact) 8 else 10), dp(6), dp(if (compact) 8 else 10), dp(6))
            minHeight = dp(if (compact) 56 else 62)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 0.96f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(8, 10, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            isClickable = true
            addPressAnimation(0.97f)
            updateMu3InternalTabButton(this, false, accent)
        }
    }

    private fun updateMu3InternalTabButton(button: TextView, active: Boolean, accent: Int) {
        button.setTextColor(if (active) contentColorFor(accent) else colors.textSecondary)
        button.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.background = if (active) {
            tonalSurfaceDrawable(accent, accent, 18f, 1f)
        } else {
            rippleDrawable(rounded(Color.TRANSPARENT, 18f), accent, 18f)
        }
        button.elevation = 0f
    }

    private fun mu3HeroPanel(): View {
        return glassBox().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "MU-3 DIAGNOSTIC"
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    setTextColor(colors.textPrimary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = "接続・リンク設定・電波品質"
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colors.textSecondary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            addSpaceHorizontal(8)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(statusPill("MU-3", color("#BEEAE6")))
                    addSpaceHorizontal(6)
                    addView(statusPill("BAUD ${currentBaudRate}", color("#BEEAE6")))
                    addSpaceHorizontal(6)
                    addView(statusPill("TX ${txIntervalMs}ms", color("#A8DBA8")))
                    addSpaceHorizontal(6)
                    addView(statusPill("LINE $mu3LinePreset", color("#FFD8A8")))
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
        }
    }

    private fun mu3Panel(title: String, subtitle: String, accent: Int, content: LinearLayout.() -> Unit): LinearLayout {
        return glassBox().apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = if (resources.configuration.screenWidthDp < 600) 12 else 16
            setPadding(dp(horizontalPadding), dp(14), dp(horizontalPadding), dp(14))
            addView(mu3PanelHeader(title, subtitle, accent))
            addSpace(10)
            content()
        }
    }

    private fun mu3PanelHeader(title: String, subtitle: String, accent: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = rounded(accent, 999f)
            }, LinearLayout.LayoutParams(dp(4), dp(32)).apply {
                marginEnd = dp(10)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    letterSpacing = 0.04f
                    setTextColor(colors.textPrimary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colors.textSecondary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun LinearLayout.addMu3ResponsivePair(
        first: View,
        second: View,
        isWide: Boolean,
        firstWeight: Float = 1f,
        secondWeight: Float = 1f
    ) {
        if (isWide) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, firstWeight))
                addSpaceHorizontal(12)
                addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, secondWeight))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            addView(first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(12)
            addView(second, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private inner class Mu3ActionGrid(context: Context, private val columns: Int) : LinearLayout(context) {
        private var currentRow: LinearLayout? = null
        private var currentColumn = 0

        init {
            orientation = VERTICAL
        }

        fun addCell(view: View) {
            if (currentRow == null || currentColumn >= columns) {
                currentRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                }.also { row ->
                    addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                currentColumn = 0
            }

            currentRow?.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
            currentColumn += 1
        }
    }

    private fun LinearLayout.addMu3ActionGrid(columns: Int, build: Mu3ActionGrid.() -> Unit) {
        val grid = Mu3ActionGrid(context, columns.coerceAtLeast(1))
        grid.build()
        addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun Mu3ActionGrid.addMu3Button(title: String, sub: String, accent: Int, onClick: () -> Unit) {
        addCell(actionButton(title, sub, accent, onClick))
    }

    private fun mu3FormRow(title: String, command: String, input: EditText, inputWidthDp: Int = 68): View {
        input.setSelectAllOnFocus(true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(10), dp(7))
            background = tonalSurfaceDrawable(colors.surface, colors.outline, 18f, 0.45f)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colors.textPrimary)
                    includeFontPadding = false
                })
                addView(TextView(context).apply {
                    text = command
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTextColor(colors.textSecondary)
                    includeFontPadding = false
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(input, LinearLayout.LayoutParams(dp(inputWidthDp), dp(38)))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(6))
            }
        }
    }

    private fun mu3RangeRow(): View {
        scanStartInput.setSelectAllOnFocus(true)
        scanEndInput.setSelectAllOnFocus(true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = tonalSurfaceDrawable(colors.surface, colors.outline, 18f, 0.45f)
            addView(TextView(context).apply {
                text = "RANGE"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.textPrimary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(scanStartInput, LinearLayout.LayoutParams(dp(54), dp(38)))
            addView(TextView(context).apply {
                text = "-"
                gravity = Gravity.CENTER
                setTextColor(colors.textSecondary)
                setPadding(dp(8), 0, dp(8), 0)
            })
            addView(scanEndInput, LinearLayout.LayoutParams(dp(54), dp(38)))
        }
    }

    private fun createMu3TestTabLegacy(): View {
        val rootScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.TRANSPARENT)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))

            // --- Top Row: Status Displays ---
            val statusRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                
                // RSSI / SN Display
                val rssiCard = glassBox().apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(sectionTitle("RADIO QUALITY", "電波強度・品質"))
                    addSpace(8)
                    mu3RssiText = monoText("WAITING...").apply { textSize = 11f }
                    addView(infoBox(mu3RssiText).apply { minimumHeight = dp(60) })
                }
                
                addSpaceHorizontal(12)

                // Test Packet Result Display
                val packetCard = glassBox().apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(sectionTitle("TX TEST STATUS", "パケットテスト状況"))
                    addSpace(8)
                    mu3PacketText = monoText("READY").apply { textSize = 11f }
                    addView(infoBox(mu3PacketText).apply { minimumHeight = dp(60) })
                }
                
                addView(rssiCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(packetCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            }
            addView(statusRow)
            addSpace(16)

            // --- Middle Row: Link Settings and Commands ---
            val mainControls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                
                // 1. Link Settings
                val linkPane = glassBox().apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(sectionTitle("LINK SETTINGS", "リンク・ID設定"))
                    addSpace(8)
                    
                    linkUiInput = formInput("0000")
                    linkGiInput = formInput("00")
                    linkChInput = formInput("07")
                    linkEiInput = formInput("01")
                    linkDiInput = formInput("02")

                    val form = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(mappingRow("UserID (UI)", "0000") { linkUiInput.requestFocus() }.apply { (this as LinearLayout).addView(linkUiInput, LinearLayout.LayoutParams(dp(70), dp(32))) })
                        addView(mappingRow("Group (GI)", "00") { linkGiInput.requestFocus() }.apply { (this as LinearLayout).addView(linkGiInput, LinearLayout.LayoutParams(dp(70), dp(32))) })
                        addView(mappingRow("Channel (CH)", "07") { linkChInput.requestFocus() }.apply { (this as LinearLayout).addView(linkChInput, LinearLayout.LayoutParams(dp(70), dp(32))) })
                        addView(mappingRow("Own ID (EI)", "01") { linkEiInput.requestFocus() }.apply { (this as LinearLayout).addView(linkEiInput, LinearLayout.LayoutParams(dp(70), dp(32))) })
                        addView(mappingRow("Target (DI)", "02") { linkDiInput.requestFocus() }.apply { (this as LinearLayout).addView(linkDiInput, LinearLayout.LayoutParams(dp(70), dp(32))) })
                    }
                    addView(form)
                    addSpace(12)
                    
                    val linkGrid = GridLayout(context).apply { columnCount = 2 }
                    linkGrid.addView(actionButton("READ", "一括取得", color("#00A8A8")) { readLinkParams() }, gridParams())
                    linkGrid.addView(actionButton("APPLY", "一時適用", color("#00A86B")) { applyLinkParams(false) }, gridParams())
                    linkGrid.addView(actionButton("SAVE/W", "ROM保存", color("#FFB300")) {
                         AlertDialog.Builder(this@MainActivity)
                            .setTitle("/Wで保存しますか？")
                            .setMessage("MU-3のEEPROMにリンク設定を保存します。")
                            .setPositiveButton("保存") { _, _ -> applyLinkParams(true) }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    }, gridParams())
                    linkGrid.addView(actionButton("FAST", "FIXED", color("#34A853")) { applyFastPreset() }, gridParams())
                    addView(linkGrid)
                }

                addSpaceHorizontal(12)

                // 2. Command Palette
                val commandPane = glassBox().apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(sectionTitle("MU-3 COMMANDS", "コマンド操作"))
                    addSpace(8)

                    val customInputRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        mu3CommandInput = formInput("@GI")
                        addView(mu3CommandInput, LinearLayout.LayoutParams(0, dp(40), 1f))
                        addSpaceHorizontal(8)
                        addView(smallButton("SEND", color("#00A86B")) { sendDiagCommand(mu3CommandInput.text.toString()) })
                    }
                    addView(customInputRow)
                    addSpace(12)

                    val quickGrid = GridLayout(context).apply { columnCount = 3 }
                    val quickCommands = listOf(
                        "@GI" to "INFO", "@VR" to "VER", "@UI" to "UserID",
                        "@CH" to "CH", "@RA" to "RSSI", "@CR" to "S/N",
                        "@IZ" to "RESET", "@DT" to "TEST", "WAKE" to "WAKE"
                    )
                    for ((cmd, label) in quickCommands) {
                        quickGrid.addView(actionButton(label, cmd, color("#00A8A8")) {
                            when(cmd) {
                                "@IZ" -> AlertDialog.Builder(this@MainActivity)
                                    .setTitle("MU-3を初期化しますか？")
                                    .setMessage("@IZを送信します。")
                                    .setPositiveButton("送信") { _, _ -> sendDiagCommand(cmd) }
                                    .setNegativeButton("キャンセル", null).show()
                                "@DT" -> runPacketTest(100)
                                "WAKE" -> runWakeTest()
                                else -> sendDiagCommand(cmd)
                            }
                        }, gridParams())
                    }
                    addView(quickGrid)
                    
                    addSpace(12)
                    addView(actionButton("AUTO RECOVER", "通信ボーレート自動復旧", color("#FF6F61")) { autoRecoverMu3Response() })
                }
                
                addView(linkPane, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
                addView(commandPane, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            }
            addView(mainControls)
            addSpace(16)

            // --- Bottom Section: Log and Scan ---
            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                
                // 1. Scan Panel
                val scanPane = glassBox().apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(sectionTitle("AIR SCAN", "簡易チャネルスキャン"))
                    addSpace(8)
                    val scanSetting = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        scanStartInput = formInput("02")
                        scanEndInput = formInput("19")
                        addView(TextView(context).apply { text = "RANGE"; textSize = 11f; setTextColor(colors.textSecondary) })
                        addSpaceHorizontal(8)
                        addView(scanStartInput, LinearLayout.LayoutParams(dp(44), dp(32)))
                        addView(TextView(context).apply { text = "-"; setPadding(dp(4), 0, dp(4), 0); setTextColor(colors.textSecondary) })
                        addView(scanEndInput, LinearLayout.LayoutParams(dp(44), dp(32)))
                    }
                    addView(scanSetting)
                    addSpace(8)
                    addView(actionButton("START SCAN", "@CH+@RA sequential", color("#FF6F61")) { runAirScan() })
                    addSpace(12)
                    addView(actionButton("NEUTRAL x10", "安全停止バースト", color("#F59E0B")) {
                        stopSending()
                        synchronized(pad) { pad.clearAll() }
                        updateUiState()
                        sendNeutralBurstAsync(10, "DIAG: NEUTRAL x10")
                    })
                }

                addSpaceHorizontal(12)

                // 2. Diag Log
                val logPane = glassBox().apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    val logHeader = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(sectionTitle("DIAG LOG", "通信ログ"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(smallButton("CLEAR", color("#64748B")) {
                            mu3DiagLogBuffer.clear()
                            if (::mu3DiagLogText.isInitialized) mu3DiagLogText.text = ""
                        })
                    }
                    addView(logHeader)
                    addSpace(8)
                    mu3DiagLogText = monoText("MU-3 TEST READY").apply { textSize = 10f; setTextColor(colors.textSecondary) }
                    addView(infoBox(mu3DiagLogText).apply { minimumHeight = dp(160) })
                }
                
                addView(scanPane, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
                addView(logPane, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
            }
            addView(bottomRow)
        }

        rootScroll.addView(android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        return rootScroll
    }

    private fun featureButtonGrid(): View {
        featureGrid = GridLayout(this).apply {
            columnCount = virtualButtonColumns()
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        rebuildFeatureButtons()
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // Use explicit fully qualified names for everything to avoid ambiguity
            val lp = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(featureGrid, lp)
        }
    }

    private fun rebuildFeatureButtons() {
        if (!::featureGrid.isInitialized) return
        featureViews.clear()
        featureGrid.removeAllViews()
        featureGrid.columnCount = virtualButtonColumns()
        val cfg = currentConfig()
        for (spec in cfg.screenButtons) {
            val tile = featureButton(spec)
            featureViews[spec.slot] = tile
            featureGrid.addView(tile.container, virtualButtonGridParams())
        }
        
        // Add "Add New" button if space allows
        if (cfg.screenButtons.size < maxScreenButtons) {
            val addBtn = addNewButtonTile()
            featureGrid.addView(addBtn, virtualButtonGridParams())
        }
    }

    private fun virtualButtonColumns(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return when {
            widthDp >= 1180f -> 4
            widthDp >= 820f -> 3
            else -> 2
        }
    }

    private fun virtualButtonGridParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = 0
            height = dp(88)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
    }

    private fun addNewButtonTile(): View {
        return TextView(this).apply {
            text = "＋ ボタン追加\nタップで作成"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(color("#34A853"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
            includeFontPadding = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, color("#34A853"), 22f, 0.72f), color("#34A853"), 22f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.97f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                addVirtualButtonAndEdit()
            }
        }
    }

    private fun emergencyButton(): View {
        return TextView(this).apply {
            text = "EMERGENCY STOP  /  ALL INPUTS OFF + NEUTRAL"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.textPrimary)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = dp(44)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, color("#BA1A1A"), 18f, 0.88f), color("#BA1A1A"), 18f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.97f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                stopSending()
                synchronized(pad) { pad.clearAll() }
                updateUiState()
                sendNeutralBurstAsync(16, "EMERGENCY: ALL INPUTS KILLED")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun featureButton(spec: ScreenButtonSpec): FeatureTileViews {
        // ボタン名（大きく・最優先で読ませる）
        val nameText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        // 送信先（payload[?] のどのbitに入るか）を明示
        val targetText = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        // 動作モード（切替 / ホールド）のチップ
        val modeChip = TextView(this).apply {
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        // 現在のON/OFF状態を一目で示すチップ
        val stateChip = TextView(this).apply {
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(modeChip)
            addSpaceHorizontal(5)
            addView(stateChip)
        }
        val action = object : LinearLayout(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            addView(nameText)
            addSpace(2)
            addView(targetText)
            addSpace(5)
            addView(bottomRow)
        }
        // 右上の設定ボタン。本体タップ＝送信、⚙タップ＝設定 と役割を分離し、
        // モーメンタリ長押しの送信を邪魔せず、設定への導線も分かりやすくする。
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(colors.textSecondary)
            minWidth = dp(30)
            minHeight = dp(30)
            isClickable = true
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, colors.accentNeutral, 999f, 0.5f), colors.accentNeutral, 999f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showScreenButtonEditor(spec)
            }
        }
        val container = FrameLayout(this).apply {
            addView(action, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                gear,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(3), dp(3), 0)
                }
            )
        }

        action.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    container.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (spec.mode == FeatureMode.MOMENTARY) {
                        synchronized(pad) { pad.setScreenButton(spec.slot, true) }
                    } else {
                        synchronized(pad) { pad.toggleScreenButton(spec.slot) }
                    }
                    updateUiState()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    container.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(1.6f)).start()
                    if (spec.mode == FeatureMode.MOMENTARY) {
                        synchronized(pad) { pad.setScreenButton(spec.slot, false) }
                        updateUiState()
                    }
                    if (event.action == MotionEvent.ACTION_UP) action.performClick()
                }
            }
            true
        }

        val tile = FeatureTileViews(container, action, nameText, targetText, modeChip, stateChip)
        updateFeatureButtonView(tile, spec, false)
        return tile
    }

    private fun updateFeatureButtonView(tile: FeatureTileViews, spec: ScreenButtonSpec, active: Boolean) {
        val isToggle = spec.mode == FeatureMode.TOGGLE
        val accent = if (isToggle) color("#0B57D0") else color("#00A86B")
        val target = targetsById[spec.targetId] ?: targetsById.getValue("NONE")
        val disabled = target.id == "NONE"

        // 1. ボタン名
        tile.nameText.text = spec.label
        tile.nameText.setTextColor(if (disabled) colors.textSecondary else colors.textPrimary)

        // 2. 送信先（未割当か、payload[?]のどのbitか）
        tile.targetText.text = if (disabled) "未割当 / OFF" else "→ ${destinationShort(target)}"
        tile.targetText.setTextColor(if (disabled) colors.textSecondary else blendColors(colors.textSecondary, accent, 0.25f))

        // 3. モードチップ（切替 / ホールド）
        tile.modeChip.text = if (isToggle) "切替" else "ホールド"
        tile.modeChip.setTextColor(if (disabled) colors.textSecondary else accent)
        tile.modeChip.background = rounded(
            blendColors(colors.surface, accent, 0.14f),
            999f,
            strokeColor = blendColors(colors.surface, accent, 0.38f),
            strokeWidth = 1
        )

        // 4. 状態チップ（ON時は塗りつぶし＋白文字で強調）
        tile.stateChip.text = when {
            active && isToggle -> "● ON"
            active -> "● 送信中"
            isToggle -> "○ OFF"
            else -> "待機"
        }
        if (active && !disabled) {
            tile.stateChip.setTextColor(Color.WHITE)
            tile.stateChip.background = rounded(accent, 999f)
        } else {
            tile.stateChip.setTextColor(if (disabled) colors.textSecondary else blendColors(colors.textSecondary, accent, 0.2f))
            tile.stateChip.background = rounded(
                blendColors(colors.surface, accent, 0.08f),
                999f,
                strokeColor = blendColors(colors.surface, accent, 0.3f),
                strokeWidth = 1
            )
        }

        // 5. タイル全体：ON時ははっきり色を乗せ、未割当は淡くする
        val baseColor = when {
            active -> blendColors(colors.surface, accent, 0.5f)
            disabled -> colors.surfaceVariant
            else -> colors.surface
        }
        val rim = if (disabled) colors.outline else accent
        tile.action.background = tonalSurfaceDrawable(baseColor, rim, 18f, if (active) 1f else 0.72f)
        tile.action.alpha = if (disabled) 0.55f else 1f
        tile.container.alpha = 1f
    }

    /** 送信先を「P6·b1」のように簡潔に表す。READMEのbit確認指示に沿って一目で送信bitが分かるようにする。 */
    private fun destinationShort(target: PayloadTarget): String {
        if (target.payloadIndex < 0 || target.bit < 0) return "OFF"
        return "P${target.payloadIndex}·b${target.bit}"
    }

    private fun showMappingEditor() {
        val dialog = AlertDialog.Builder(this).create()
        mappingDialog = dialog
        controllerInputViews.clear()
        screenButtonViews.clear()
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(colors.bg, 28f, strokeColor = colors.outline, strokeWidth = 1)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "割り当て設定"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.textPrimary)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("初期化", color("#F59E0B")) {
            replaceConfig(defaultControlConfig(), "割り当てを初期状態に戻しました")
            dialog.dismiss()
            showMappingEditor()
        })
        header.addView(smallButton("閉じる", colors.textSecondary) { dialog.dismiss() })
        root.addView(header)
        root.addSpace(6)
        root.addView((descriptionText("物理ボタンを押すとその項目へジャンプします") as TextView).apply {
            textSize = 11f
            setTextColor(colors.accentPrimary)
        })
        root.addSpace(6)

        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cfg = currentConfig()

        scrollContent.addView(sectionTitle("CONTROLLER", "物理コントローラーボタンの割り当て"))
        scrollContent.addSpace(6)
        for (input in controllerInputs) {
            val targetId = cfg.controllerTargets[input.id] ?: "NONE"
            val row = mappingRow(input.label, targetLabel(targetId)) {
                showTargetPicker("${input.label} の送信先", targetId) { newTargetId ->
                    val newMap = cfg.controllerTargets.toMutableMap()
                    newMap[input.id] = newTargetId
                    replaceConfig(cfg.copy(controllerTargets = newMap), "${input.label} を ${targetLabel(newTargetId)} に変更しました")
                    dialog.dismiss()
                    showMappingEditor()
                }
            }
            controllerInputViews[input.id] = row
            scrollContent.addView(row)
        }

        scrollContent.addSpace(14)
        scrollContent.addView(sectionTitle("SCREEN BUTTONS", "画面上ボタンの割り当て"))
        scrollContent.addSpace(6)
        for (button in cfg.screenButtons) {
            val sub = "${targetLabel(button.targetId)} / ${if (button.mode == FeatureMode.TOGGLE) "TOGGLE" else "HOLD"}"
            val row = mappingRow(button.label, sub) {
                showScreenButtonEditor(button)
                dialog.dismiss()
            }
            screenButtonViews[button.slot] = row
            scrollContent.addView(row)
        }

        scrollContent.addSpace(8)
        scrollContent.addView(smallButton("＋ 画面ボタンを追加", color("#22C55E")) {
            val latest = currentConfig()
            if (latest.screenButtons.size >= maxScreenButtons) {
                setStatus("画面ボタンは最大 $maxScreenButtons 個までです")
                return@smallButton
            }
            val slot = latest.screenButtons.size + 1
            val candidateTarget = firstUnusedExtraTarget(latest)
            val newButton = ScreenButtonSpec(
                slot = slot,
                label = "BTN$slot",
                targetId = candidateTarget,
                mode = FeatureMode.MOMENTARY
            )
            replaceConfig(latest.copy(screenButtons = latest.screenButtons + newButton), "画面ボタン BTN$slot を追加しました")
            dialog.dismiss()
            showMappingEditor()
        })

        mappingEditorScrollView = ScrollView(this).apply { addView(scrollContent) }
        root.addView(mappingEditorScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.setView(root)
        dialog.setOnDismissListener {
            mappingDialog = null
            mappingEditorScrollView = null
            controllerInputViews.clear()
            screenButtonViews.clear()
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).roundToInt(), (resources.displayMetrics.heightPixels * 0.88).roundToInt())
        }
        dialog.show()
    }

    private fun showScreenButtonEditor(button: ScreenButtonSpec) {
        val items = arrayOf("表示名を変更", "送信先bitを変更", "HOLD / TOGGLE を切替", "このボタンを削除")
        AlertDialog.Builder(this)
            .setTitle("${button.label} の設定")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameScreenButtonDialog(button)
                    1 -> showTargetPicker("${button.label} の送信先", button.targetId) { newTargetId ->
                        val latest = currentConfig()
                        val updated = latest.screenButtons.map {
                            if (it.slot == button.slot) it.copy(targetId = newTargetId) else it
                        }
                        replaceConfig(latest.copy(screenButtons = updated), "${button.label} の送信先を ${targetLabel(newTargetId)} に変更しました")
                        showMappingEditor()
                    }
                    2 -> {
                        val latest = currentConfig()
                        val updated = latest.screenButtons.map {
                            if (it.slot == button.slot) it.copy(mode = if (it.mode == FeatureMode.TOGGLE) FeatureMode.MOMENTARY else FeatureMode.TOGGLE) else it
                        }
                        replaceConfig(latest.copy(screenButtons = updated), "${button.label} の動作を切り替えました")
                        showMappingEditor()
                    }
                    3 -> {
                        val latest = currentConfig()
                        val updated = latest.screenButtons
                            .filter { it.slot != button.slot }
                            .mapIndexed { index, spec -> spec.copy(slot = index + 1) }
                        synchronized(pad) { pad.clearScreenButtons() }
                        replaceConfig(latest.copy(screenButtons = updated), "${button.label} を削除しました")
                        showMappingEditor()
                    }
                }
            }
            .setNegativeButton("戻る") { _, _ -> showMappingEditor() }
            .show()
    }

    private fun showRenameScreenButtonDialog(button: ScreenButtonSpec) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(button.label)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("表示名を変更")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim().ifBlank { button.label }.take(12)
                val latest = currentConfig()
                val updated = latest.screenButtons.map {
                    if (it.slot == button.slot) it.copy(label = newName) else it
                }
                replaceConfig(latest.copy(screenButtons = updated), "画面ボタン名を $newName に変更しました")
                showMappingEditor()
            }
            .setNegativeButton("キャンセル") { _, _ -> showMappingEditor() }
            .show()
    }

    private fun showTargetPicker(title: String, currentTargetId: String, onSelected: (String) -> Unit) {
        val categories = listOf(
            "DIRECTIONAL (十字キー)" to listOf("P4_B7_DOWN", "P4_B6_UP", "P5_B1_LEFT", "P5_B0_RIGHT"),
            "MAIN ACTIONS (ボタン)" to listOf("P4_B5_CROSS", "P5_B2_CIRCLE", "P4_B4_TRIANGLE", "P5_B3_SQUARE"),
            "TRIGGERS (L/R)" to listOf("P4_B3_L1", "P4_B2_R1", "P4_B1_L2", "P4_B0_R2"),
            "SYSTEM (各種機能)" to listOf("P5_B4_SHARE", "P5_B5_OPTIONS", "P6_B0_PS", "P5_B6_L3", "P5_B7_R3"),
            "EXTRA (拡張ビット)" to listOf("P6_B1_EXTRA1", "P6_B2_EXTRA2", "P6_B3_EXTRA3", "P6_B4_EXTRA4", "P6_B5_EXTRA5", "P6_B6_EXTRA6", "P6_B7_EXTRA7"),
            "NONE (無効)" to listOf("NONE")
        )

        val catLabels = categories.map { it.first }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("$title - カテゴリ選択")
            .setItems(catLabels) { _, which ->
                val targetsInCategory = categories[which].second
                showTargetSubPicker(title, targetsInCategory, currentTargetId, onSelected)
            }
            .setNegativeButton("戻る", null)
            .show()
    }

    private fun showTargetSubPicker(title: String, targetIds: List<String>, currentTargetId: String, onSelected: (String) -> Unit) {
        val targets = targetIds.map { targetsById[it] ?: PayloadTarget(it, -1, -1, "Unknown") }
        val labels = targets.map { it.label }.toTypedArray()
        val checked = targetIds.indexOf(currentTargetId)
        
        AlertDialog.Builder(this)
            .setTitle("$title - ビット選択")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                onSelected(targetIds[which])
            }
            .setNegativeButton("戻る") { _, _ -> showTargetPicker(title, currentTargetId, onSelected) }
            .show()
    }

    private fun mappingRow(title: String, value: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, colors.accentPrimary, 18f, 0.35f), colors.accentPrimary, 18f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.985f)
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.textPrimary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.72f))

            addView(TextView(context).apply {
                text = value
                textSize = 13f
                gravity = Gravity.END
                setTextColor(colors.accentPrimary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.28f))
        }.apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(3), 0, dp(3))
            layoutParams = lp
        }
    }

    private fun descriptionText(value: String): View {
        return TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(colors.textSecondary)
        }
    }

    private fun smallButton(title: String, accent: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.textPrimary)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, accent, 999f, 0.75f), accent, 999f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.96f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(6), 0, 0, 0)
            layoutParams = lp
        }
    }

    private fun actionButton(title: String, sub: String, accent: Int, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = "$title\n$sub"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.textPrimary)
            setPadding(dp(6), dp(7), dp(6), dp(7))
            minHeight = dp(54)
            includeFontPadding = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            background = rippleDrawable(tonalSurfaceDrawable(colors.surface, accent, 20f, 0.82f), accent, 20f)
            elevation = 0f
            isClickable = true
            addPressAnimation(0.965f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    private fun sectionTitle(en: String, ja: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(TextView(context).apply {
                text = en
                textSize = 14f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = 0.04f
                setTextColor(primaryAccent())
                includeFontPadding = false
            })
            addSpaceHorizontal(6)
            addView(TextView(context).apply {
                text = ja
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color("#607D8B"))
                includeFontPadding = false
            })
        }
    }

    private fun infoBox(content: TextView): View {
        return glassBox().apply {
            orientation = LinearLayout.VERTICAL
            addView(content)
        }
    }

    private fun monoText(initial: String): TextView {
        return TextView(this).apply {
            text = initial
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(colors.textPrimary)
            setLineSpacing(2f, 1.0f)
        }
    }


    private fun formInput(initial: String): EditText {
        return EditText(this).apply {
            setText(initial)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colors.textPrimary)
            setHintTextColor(colors.textSecondary)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(colors.surface, 16f, strokeColor = colors.outline, strokeWidth = 1)
        }
    }

    private fun badge(textValue: String, active: Boolean = false): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.textPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val stroke = if (active) color("#00A86B") else colors.outline
            background = tonalSurfaceDrawable(colors.surface, stroke, 999f, 0.7f)
            elevation = 0f
        }
    }

    private fun glassBox(): LinearLayout {
        return LinearLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = tonalSurfaceDrawable(colors.surface, colors.accentPrimary, 18f, 0.58f)
            elevation = 0f
        }
    }

    private fun LinearLayout.addSpaceHorizontal(widthDp: Int) {
        addView(View(context), LinearLayout.LayoutParams(dp(widthDp), 1))
    }

    private fun LinearLayout.addSpace(heightDp: Int) {
        addView(View(context), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun LinearLayout.addView(view: View, matchHeight: Boolean) {
        val height = if (matchHeight) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        val weight = if (matchHeight) 1f else 0f
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height, weight))
    }

    private fun gridParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT // Allow height to grow
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun rowButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun primaryAccent(): Int {
        return colors.accentPrimary
    }

    private fun tabAccent(index: Int): Int {
        return when (index % 5) {
            0 -> primaryAccent()
            1 -> colors.accentSuccess
            2 -> colors.accentTeal
            3 -> colors.accentViolet
            else -> colors.accentInfo
        }
    }

    private fun tonalSurfaceDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float, tone: Float): Drawable {
        val surface = blendColors(fillColor, strokeColor, 0.08f + 0.10f * tone.coerceIn(0f, 1f))
        val outline = blendColors(surface, strokeColor, 0.34f)
        return rounded(surface, radiusDp, strokeColor = outline, strokeWidth = 1)
    }

    private fun expressiveSurfaceDrawable(accent: Int, radiusDp: Float): Drawable {
        val surface = blendColors(colors.surface, accent, 0.24f)
        val outline = blendColors(surface, accent, 0.28f)
        return rounded(surface, radiusDp, strokeColor = outline, strokeWidth = 1)
    }

    private fun rippleDrawable(content: Drawable, accent: Int, radiusDp: Float): Drawable {
        return RippleDrawable(
            ColorStateList.valueOf(alphaColor(accent, 0.28f)),
            content,
            rounded(colors.surface, radiusDp)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.addPressAnimation(pressedScale: Float = 0.97f) {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(90)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            }
            false
        }
    }

    private fun rounded(
        fillColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.RECTANGLE
        gd.cornerRadius = dp(radiusDp).toFloat()
        gd.setColor(fillColor)
        if (strokeColor != null && strokeWidth > 0) gd.setStroke(dp(strokeWidth), strokeColor)
        return gd
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inverse = 1f - t
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * t).roundToInt().coerceIn(0, 255),
            (Color.green(from) * inverse + Color.green(to) * t).roundToInt().coerceIn(0, 255),
            (Color.blue(from) * inverse + Color.blue(to) * t).roundToInt().coerceIn(0, 255)
        )
    }

    private fun alphaColor(value: Int, alpha: Float): Int {
        return Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).roundToInt(),
            Color.red(value),
            Color.green(value),
            Color.blue(value)
        )
    }

    private fun contentColorFor(background: Int): Int {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel(Color.red(background)) +
            0.7152 * channel(Color.green(background)) +
            0.0722 * channel(Color.blue(background))
        return if (luminance > 0.48) Color.parseColor("#1B1D23") else Color.WHITE
    }

    private fun color(hex: String): Int {
        return when (hex.uppercase(Locale.US)) {
            "#0B57D0", "#4285F4", "#60A5FA", "#90CAF9" -> colors.accentInfo
            "#00A8A8", "#38BDF8", "#BEEAE6" -> colors.accentTeal
            "#00A86B", "#0B8043", "#22C55E", "#34A853", "#4ADE80", "#A8DBA8", "#A7F3D0" -> colors.accentSuccess
            "#F59E0B", "#F9AB00", "#FB923C", "#FFB300", "#FFD8A8" -> colors.accentWarning
            "#FF6F61", "#FFD2CC" -> colors.accentCoral
            "#EA4335", "#BA1A1A", "#B3261E", "#F87171" -> colors.accentDanger
            "#7C3AED" -> colors.accentViolet
            "#607D8B", "#64748B", "#94A3B8" -> colors.accentNeutral
            "#F3FAF8" -> colors.surfaceVariant
            "#D6E5E1" -> colors.outline
            else -> Color.parseColor(hex)
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun performHapticFeedback(constant: Int) {
        findViewById<View>(android.R.id.content).performHapticFeedback(constant)
    }

    private fun serialProber(): UsbSerialProber {
        val customTable = UsbSerialProber.getDefaultProbeTable().apply {
            addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
        }
        return UsbSerialProber(customTable)
    }

    private fun connectUsbSerial() {
        val drivers = serialProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            val deviceList = usbManager.deviceList
            if (deviceList.isEmpty()) {
                setStatus("USBデバイスがOSに認識されていません。OTG接続を確認してください。")
            } else {
                val sb = StringBuilder("ドライバ不一致。認識中のUSB:\n")
                for (dev in deviceList.values) {
                    sb.append(String.format(Locale.US, " - VID:%04X PID:%04X %s\n", 
                        dev.vendorId, dev.productId, dev.deviceName.takeLast(8)))
                }
                sb.append("device_filter.xmlにVID/PIDの追加が必要かもしれません。")
                setStatus(sb.toString())
            }
            updateConnectionBadges()
            return
        }

        val driver = drivers.first()
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return
        }

        setStatus("接続準備中...")
        openSerialForDeviceAsync(device)
    }

    private fun openSerialForDeviceAsync(device: UsbDevice) {
        if (!serialOpenBusy.compareAndSet(false, true)) {
            setStatus("USB open is already running")
            return
        }
        pendingOpenDeviceId = device.deviceId
        diagExecutor.execute {
            try {
                openSerialForDevice(device)
            } catch (e: Exception) {
                mainHandler.post {
                    setStatus("接続失敗: ${e.message}")
                    updateConnectionBadges()
                    updateCommUi()
                }
            } finally {
                pendingOpenDeviceId = -1
                serialOpenBusy.set(false)
            }
        }
    }

    private fun openSerialForDevice(device: UsbDevice) {
        // Look up the driver before tearing down the current link, so a failed
        // lookup does not drop a working connection.
        val driver = serialProber().probeDevice(device)
        if (driver == null) {
            setStatus("対応するUSBシリアルドライバが見つかりません")
            return
        }

        stopSendingInternal(showStatus = false, joinThread = true)

        // Need to ensure we don't have multiple connections
        synchronized(serialWriteLock) {
            closeSerialInternal()
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            setStatus("USBデバイスを開けません。権限またはOTG接続を確認してください")
            return
        }

        var openedPort: UsbSerialPort? = null
        try {
            val port = driver.ports.first()
            openedPort = port
            port.open(connection)
            port.setParameters(
                currentBaudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // MU3-USB は仮想COMポートとして扱う。
            // 以前の版では RTS=false / DTR=true を固定していたが、基板やUSBシリアルICによっては
            // RESET/MODE を意図せず動かして "NO response" になるため、まずは安全側の初期化にする。
            applyMu3LinePresetLocked(port, mu3LinePreset)

            // 電源投入直後はMU-3内部が安定するまでコマンドを送らない。
            Thread.sleep(250)
            synchronized(serialWriteLock) { drainSerialAvailableLocked(port, 120) }

            usbConnection = connection
            serialPort = port
            connectedDeviceId = driver.device.deviceId
            setStatus("MU-1/3接続OK: ${driver.device.deviceName} / ${currentBaudRate}bps / @DT0E HEX")
            updateConnectionBadges()
        } catch (e: Exception) {
            try {
                openedPort?.close()
            } catch (_: Exception) {
            }
            connection.close()
            connectedDeviceId = -1
            setStatus("USBシリアル初期化失敗: ${e.message}")
            updateConnectionBadges()
        }
    }

    private fun closeSerial() {
        stopSendingInternal(showStatus = true, joinThread = true)
        synchronized(serialWriteLock) {
            closeSerialInternal()
        }
        updateConnectionBadges()
    }

    private fun closeSerialInternal() {
        val port = serialPort
        val connection = usbConnection
        serialPort = null
        usbConnection = null
        connectedDeviceId = -1
        pendingOpenDeviceId = -1
        try {
            port?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
    }

    private fun startSending() {
        if (serialOpenBusy.get()) {
            setStatus("USB open is still running")
            return
        }
        if (serialPort == null) {
            setStatus("先にMU-3を接続してください")
            return
        }
        if (isSending || senderThread?.isAlive == true) {
            setStatus("すでに送信中です")
            return
        }

        val intervalMs = txIntervalMs.coerceIn(10L, 1000L)
        txErrorCount = 0
        lastTxError = ""
        // aiLastTxMs still holds the previous session's last send; without this
        // the sentinel reports a "送信停止" stall right after every START.
        aiSuppressStallUntilMs = SystemClock.elapsedRealtime() + 2200L
        isSending = true

        // 送信は固定デッドラインの transmissionLoop に一本化する。
        // 旧 scheduleWithFixedDelay は「前回の処理が終わってから intervalMs 空ける」方式のため、
        // 実周期 = intervalMs + 毎サイクルばらつく送信処理時間（シリアル書き込み＋RXドレイン）
        // となり、MU-3 モジュールの送信LEDが不規則に点滅していた。
        // transmissionLoop は絶対時刻のデッドラインで待機し、遅れてもcatch-up連送をしないため、
        // 処理時間に依らず送信周期が一定になり、TX LEDが安定して点滅する
        // （最優先スレッドで動かしGCジッタも避ける）。
        sendTask?.cancel(true)
        sendTask = null
        val generation = senderGeneration.incrementAndGet()
        val thread = Thread { transmissionLoop(generation) }.apply {
            name = "mu3-tx"
            isDaemon = true
        }
        senderThread = thread
        thread.start()

        setStatus("送信開始: ${intervalMs}ms周期 / ${currentBaudRate}bps / fixed-deadline scheduler")
        updateConnectionBadges()
        setChromeCollapsed(true)
    }

    private fun stopSending() {
        stopSendingInternal(showStatus = true, joinThread = true)
    }

    private fun stopSendingInternal(showStatus: Boolean, joinThread: Boolean) {
        savedNavigation.stop()
        anglePresetCommand.stop()
        mainHandler.post {
            savedNavigationMap?.clearSelection()
            savedNavigationStatus?.text = "送信停止 / 自動走行停止"
        }
        // 送信が止まる＝自動操縦も必ず止める（緊急停止・一時停止・リンク障害すべてで安全側に倒す）。
        if (autopilotActive) stopAutopilotInternal(neutralize = false)
        isSending = false
        sendTask?.cancel(true)
        sendTask = null
        senderGeneration.incrementAndGet()
        val thread = senderThread
        thread?.interrupt()
        if (joinThread && thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(senderStopJoinTimeoutMs())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (senderThread === thread) senderThread = null
        if (showStatus && ::statusText.isInitialized) setStatus("送信停止")
        updateConnectionBadges()
    }

    private fun transmissionLoop(generation: Int) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }

        val hexChars = "0123456789ABCDEF".toCharArray()

        // Pre-initialize buffer and static parts to minimize allocation in loop
        val sendBuffer = ByteArray(MU3_TX_FRAME_LEN)
        val payloadBuffer = ByteArray(7)
        sendBuffer[0] = '@'.code.toByte()
        sendBuffer[1] = 'D'.code.toByte()
        sendBuffer[2] = 'T'.code.toByte()
        sendBuffer[3] = '0'.code.toByte()
        sendBuffer[4] = 'E'.code.toByte()
        sendBuffer[19] = '\r'.code.toByte()
        sendBuffer[20] = '\n'.code.toByte()

        // Reusable RX drain scratch. Allocating inside the loop would add GC jitter
        // to this timing-critical thread, so keep a single buffer for the whole run.
        val rxScratch = ByteArray(256)

        // Jitter measurement uses send-start to send-start interval.
        var nextTickNs = SystemClock.elapsedRealtimeNanos()
        var lastTxStartNs = 0L
        var maxJitterNs = 0L
        var txCount = 0
        var lastPayloadUiUpdateMs = 0L
        var consecutiveTxErrors = 0
        val radioCommands = NavigationRadioMux()

        while (isSending && generation == senderGeneration.get() && !Thread.currentThread().isInterrupted) {
            try {
                val requestedIntervalMs = if (savedNavigation.transmitting) 20L else txIntervalMs.coerceIn(10L, 1000L)
                val intervalMs = stableTxIntervalMs(requestedIntervalMs)
                val intervalNs = intervalMs * 1_000_000L
                val txStartNs = SystemClock.elapsedRealtimeNanos()
                val cfg = currentConfig()

                // 1. Prepare payload in a local buffer to keep the TX thread allocation-free.
                // 自動操縦中はマクロが生成したpayloadで上書きし、手動入力に優先して送る。
                if (autopilotActive) {
                    synchronized(autopilotLock) {
                        System.arraycopy(autopilotPayload, 0, payloadBuffer, 0, 7)
                    }
                } else {
                    synchronized(pad) {
                        pad.toPayloadOptimized(cfg, targetsById, payloadBuffer)
                    }
                }
                // Reserved navigation marker cannot be generated by virtual button combinations.
                if ((payloadBuffer[6].toInt() and 0x81) == 0x80) {
                    payloadBuffer[6] = (payloadBuffer[6].toInt() and 0x7F).toByte()
                }
                if ((payloadBuffer[6].toInt() and 0xC7) == 0x40) {
                    payloadBuffer[6] = (payloadBuffer[6].toInt() and 0xBF).toByte()
                }
                // マクロ学習中なら、操縦者の7バイトを確保ゼロで時系列記録する。
                // 保存地点/仰角の専用フレームを混ぜる前に記録すること。混ぜた後を記録すると、
                // 再生時に上の予約マーカー除去で専用フレームが payload[6] の機構bitへ化ける。
                if (macroRec.learning) {
                    macroRec.add(SystemClock.elapsedRealtime(), payloadBuffer)
                }
                // Always interleave ordinary controls with dedicated commands.
                // The receiver retains held controls across dedicated frames.
                radioCommands.fill(payloadBuffer, SystemClock.elapsedRealtime(),
                    anglePresetCommand, savedNavigation)

                // 2. Format Frame (@DT0E + 14HEX + \r\n) directly into sendBuffer
                for (i in 0 until 7) {
                    val b = payloadBuffer[i].toInt() and 0xFF
                    sendBuffer[5 + i * 2] = hexChars[b ushr 4].code.toByte()
                    sendBuffer[6 + i * 2] = hexChars[b and 0x0F].code.toByte()
                }

                // 3. Write to Serial. Diagnostics stop the stream first, so this lock should not stall periodic TX.
                val rxBytes = synchronized(serialWriteLock) {
                    val port = serialPort ?: throw IllegalStateException("MU-3 disconnected")
                    port.write(sendBuffer, timeoutForFrame(MU3_TX_FRAME_LEN))
                    savedNavigation.onTransmitted(payloadBuffer, SystemClock.elapsedRealtime())
                    // MU-3 sends *DT response. Drain it to prevent RX buffer overflow.
                    // Allocation-free drain to keep the TX thread free of GC pauses.
                    // 受信バイト数も数えておき、ブラックボックスのACK(リンク健全度)指標に使う。
                    drainSerialQuietCountLocked(port, rxScratch, TX_RX_DRAIN_MS)
                }
                // Successful cycle clears the transient-error streak.
                consecutiveTxErrors = 0

                // 4. Jitter check by actual TX start timing
                val gapMsForRecord = if (lastTxStartNs != 0L) {
                    val actualIntervalNs = txStartNs - lastTxStartNs
                    val jitterNs = abs(actualIntervalNs - intervalNs)
                    if (jitterNs > maxJitterNs) maxJitterNs = jitterNs
                    (actualIntervalNs / 1_000_000L).toInt()
                } else 0
                lastTxStartNs = txStartNs
                txCount++

                // 4a-AI. オンデバイスAIのライブ信号へ反映（確保ゼロ・記録ON/OFFに依らず常時）。
                aiOnTx(ack = rxBytes > 0, gapMs = gapMsForRecord, error = false)

                // 4b. Black box: 実際に送った7バイトpayloadと送信間隔・ACK有無を記録する。
                // 確保ゼロのプリミティブ配列書き込みなので、最優先送信スレッドでも安全。
                if (blackBox.recording) {
                    blackBox.record(
                        SystemClock.elapsedRealtime(),
                        payloadBuffer,
                        gapMsForRecord,
                        ack = rxBytes > 0,
                        error = false,
                        marker = false,
                        rssi = lastKnownRssi
                    )
                }

                // 5. UI Update Throttling. Do not touch UI every packet.
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastPayloadUiUpdateMs >= TX_UI_UPDATE_INTERVAL_MS) {
                    lastPayloadUiUpdateMs = nowMs
                    val payloadSnapshot = payloadBuffer.copyOf()
                    val jitterSnapshotMs = maxJitterNs / 1_000_000.0
                    val txLabel = if (intervalMs == requestedIntervalMs) {
                        "${intervalMs}ms"
                    } else {
                        "${requestedIntervalMs}->${intervalMs}ms"
                    }
                    mainHandler.post {
                        if (isSending && generation == senderGeneration.get()) {
                            updatePayloadOnly(payloadSnapshot, "LIVE TX #$txCount")
                        }
                        if (::sendBadge.isInitialized && isSending && generation == senderGeneration.get()) {
                            sendBadge.text = "TX: 送信中 ${txLabel} / J±${String.format(Locale.US, "%.1f", jitterSnapshotMs)}ms"
                        }
                    }
                    if (txCount % 100 == 0) maxJitterNs = 0L
                }

                // 6. Fixed-deadline wait. If late, skip catch-up bursts to keep the TX LED regular.
                nextTickNs = txStartNs + intervalNs
                val afterWriteNs = SystemClock.elapsedRealtimeNanos()
                if (afterWriteNs >= nextTickNs) {
                    nextTickNs = afterWriteNs + intervalNs
                }
                waitUntilNs(nextTickNs, generation, txStartNs)

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                txErrorCount++
                consecutiveTxErrors++
                lastTxError = e.message ?: e.javaClass.simpleName

                // AIライブ信号にも送信失敗を反映（リンク健全度・エラー率に効く）。
                aiOnTx(ack = false, gapMs = 0, error = true)

                // ブラックボックスに送信エラーをマーキング。通信が切れた瞬間を後で可視化できる。
                if (blackBox.recording) {
                    blackBox.recordEvent(SystemClock.elapsedRealtime(), error = true, marker = false)
                }

                // 単発のUSBジッタやタイムアウトでリンク全体を落とさない。
                // ポートがまだ生きていて、連続エラーがしきい値未満の間は、
                // 短いバックオフを挟んで送信を継続し再同期する。
                if (consecutiveTxErrors < TX_MAX_CONSECUTIVE_ERRORS &&
                    serialPort != null &&
                    isSending &&
                    generation == senderGeneration.get()
                ) {
                    val retrySnapshot = consecutiveTxErrors
                    val errSnapshot = lastTxError
                    mainHandler.post {
                        if (::sendBadge.isInitialized && isSending && generation == senderGeneration.get()) {
                            sendBadge.text = "TX: 再送中 ($retrySnapshot) / $errSnapshot"
                        }
                    }
                    try {
                        Thread.sleep(TX_ERROR_BACKOFF_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    // 遅延ぶんのcatch-up連送を避けるためデッドラインとジッタ計測をリセット。
                    nextTickNs = SystemClock.elapsedRealtimeNanos()
                    lastTxStartNs = 0L
                    continue
                }

                // ここに到達する理由は2通り:
                //  (a) 連続エラーがしきい値に到達 → 本物のリンク障害。安全側でポートを閉じる。
                //  (b) 停止要求(isSending=false / generation変更)中にエラー → 後始末は停止側が行う。
                //      意図的な停止のためにポートを閉じてはいけないので、静かに抜ける。
                val linkFailure = consecutiveTxErrors >= TX_MAX_CONSECUTIVE_ERRORS
                isSending = false
                savedNavigation.stop()
        anglePresetCommand.stop()
                mainHandler.post {
                    savedNavigationMap?.clearSelection()
                    savedNavigationStatus?.text = "通信停止 / 再操作が必要"
                }
                senderGeneration.incrementAndGet()
                if (linkFailure) {
                    val countSnapshot = txErrorCount
                    synchronized(serialWriteLock) {
                        closeSerialInternal()
                    }
                    mainHandler.post {
                        // 送信が落ちたら自動操縦も必ず止める（旧パスの安全挙動を踏襲）。
                        // ポートは閉じる途中なのでニュートラル送信はしない。
                        if (autopilotActive) stopAutopilotInternal(neutralize = false)
                        setStatus("送信ループ停止: 連続エラー${consecutiveTxErrors}回 / $lastTxError (累計$countSnapshot)")
                        updateConnectionBadges()
                        updateCommUi()
                    }
                }
                break
            }
        }
        mainHandler.post { updateConnectionBadges() }
    }

    private fun waitUntilNs(deadlineNs: Long, generation: Int, lastTxStartNs: Long) {
        while (isSending && generation == senderGeneration.get()) {
            val effectiveDeadline = RadioTxTiming.deadline(deadlineNs, lastTxStartNs,
                stableTxIntervalMs(20L) * 1_000_000L, savedNavigation.transmitting)
            val waitNs = effectiveDeadline - SystemClock.elapsedRealtimeNanos()
            if (waitNs <= 0L) return
            // unpark wakes a long manual-period wait on a tap/cancel. Unlike
            // interrupt it does not terminate the serial transmission loop.
            LockSupport.parkNanos(waitNs)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        }
        throw InterruptedException()
    }

    private fun sendNeutralOnce() {
        try {
            sendPayload(PadState.neutralPayload())
        } catch (e: Exception) {
            lastTxError = e.message ?: e.javaClass.simpleName
            if (serialPort != null || connectedDeviceId != -1) {
                synchronized(serialWriteLock) {
                    closeSerialInternal()
                }
            }
            mainHandler.post {
                updateConnectionBadges()
                updateCommUi()
            }
        }
    }

    private fun sendNeutralBurstAsync(count: Int, doneStatus: String? = null) {
        if (isSending) {
            if (doneStatus != null) mainHandler.post { setStatus(doneStatus) }
            return
        }
        if (serialPort == null) {
            if (doneStatus != null) mainHandler.post { setStatus(doneStatus) }
            return
        }
        if (!neutralBurstBusy.compareAndSet(false, true)) {
            if (doneStatus != null) mainHandler.post { setStatus(doneStatus) }
            return
        }
        Thread {
            try {
                val safeCount = count.coerceAtLeast(1)
                repeat(safeCount) { index ->
                    sendNeutralOnce()
                    if (index != safeCount - 1) Thread.sleep(6)
                }
            } catch (_: Exception) {
            } finally {
                neutralBurstBusy.set(false)
                if (doneStatus != null) mainHandler.post { setStatus(doneStatus) }
            }
        }.apply {
            name = "Mu3NeutralBurst"
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun sendPayload(payload: ByteArray) {
        val port = serialPort ?: return
        val frame = formatMu3Frame(payload)
        synchronized(serialWriteLock) {
            if (serialPort !== port) throw IllegalStateException("MU-3 disconnected")
            port.write(frame.toByteArray(StandardCharsets.US_ASCII), 50)
        }
    }

    private fun formatMu3Frame(payload: ByteArray): String {
        val hex = payload.joinToString(separator = "") { b ->
            String.format(Locale.US, "%02X", b.toInt() and 0xFF)
        }
        return String.format(Locale.US, "@DT%02X%s\r\n", payload.size * 2, hex)
    }

    private fun formatMu3FramePreview(payload: ByteArray): String {
        val payloadHex = payload.joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        return "@DT${String.format(Locale.US, "%02X", payloadHex.length)}$payloadHex \\r\\n"
    }

    private fun stableTxIntervalMs(requestedMs: Long = txIntervalMs): Long {
        return requestedMs.coerceIn(10L, 1000L)
    }

    private fun minStableTxIntervalMs(): Long {
        return 10L
    }

    private fun enforceStableTxIntervalForBaud(): Boolean {
        txIntervalMs = stableTxIntervalMs(txIntervalMs)
        return false
    }

    private fun senderStopJoinTimeoutMs(): Long {
        return (timeoutForFrame(MU3_TX_FRAME_LEN).toLong() + 140L).coerceIn(TX_STOP_JOIN_MIN_MS, TX_STOP_JOIN_MAX_MS)
    }

    private fun timeoutForFrame(byteCount: Int): Int {
        val bits = byteCount * 10L
        val baud = currentBaudRate
        val wireMs = if (baud > 0) (((bits * 1000L) + baud - 1L) / baud).toInt() else 20
        return (wireMs + 20).coerceIn(25, 220)
    }

    private fun writeSerial(bytes: ByteArray, timeoutMs: Int = 300) {
        synchronized(serialWriteLock) {
            val port = serialPort ?: throw IllegalStateException("MU-3 disconnected")
            port.write(bytes, timeoutMs)
        }
    }

    private fun sendAsciiCommand(command: String, timeoutMs: Int = 900, retryOnNoResponse: Boolean = true): String {
        val normalized = normalizeMu3Command(command)
        val bytes = normalized.toByteArray(StandardCharsets.US_ASCII)
        val response = synchronized(serialWriteLock) {
            val port = serialPort ?: throw IllegalStateException("MU-3が未接続です")
            drainSerialAvailableLocked(port, 80)
            wakeMu3CommandModeLocked(port)
            val first = sendAsciiCommandLocked(port, bytes, timeoutMs)
            if (first.isEmpty() && retryOnNoResponse && !normalized.trim().equals("@IZ", ignoreCase = true)) {
                Thread.sleep(120)
                wakeMu3CommandModeLocked(port)
                sendAsciiCommandLocked(port, bytes, timeoutMs + 300)
            } else {
                first
            }
        }
        val text = String(response, StandardCharsets.ISO_8859_1)
        appendMu3Log("> ${normalized.trim()}\n< ${if (text.isBlank()) "(NO RESPONSE)" else text.trim()}\n")
        updateMu3CommandResult(normalized, text)
        if (text.isBlank()) {
            appendMu3Log("  hint: AUTO RECOVERを押すと、baud/RTS/DTRを順番に試します。\n")
        }
        return text
    }

    private fun sendAsciiCommandLocked(port: UsbSerialPort, bytes: ByteArray, timeoutMs: Int): ByteArray {
        port.write(bytes, timeoutForCommand(bytes.size).coerceAtLeast(120))
        return readSerialAvailableLocked(port, timeoutMs)
    }

    private fun timeoutForCommand(byteCount: Int): Int {
        val bits = byteCount * 10L
        val baud = currentBaudRate
        val wireMs = if (baud > 0) (((bits * 1000L) + baud - 1L) / baud).toInt() else 20
        return (wireMs + 120).coerceIn(150, 600)
    }

    private fun wakeMu3CommandModeLocked(port: UsbSerialPort) {
        // CRLFだけを送り、途中で残った入力や半端なコマンドを終了させる。
        // MODE/RESET線はむやみに動かさない。必要な場合はAUTO RECOVERで全組み合わせを試す。
        port.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()), 120)
        Thread.sleep(80)
        drainSerialAvailableLocked(port, 80)
    }

    private fun normalizeMu3Command(command: String): String {
        val c = command.trim()
        if (c.isEmpty()) return "\r\n"
        return if (c.endsWith("\r") || c.endsWith("\n")) c else "$c\r\n"
    }

    private fun readSerialAvailableLocked(port: UsbSerialPort, timeoutMs: Int): ByteArray {
        val out = ArrayList<Byte>()
        val buf = ByteArray(256)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        var lastRx = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val remainingMs = deadline - now
            if (remainingMs <= 0L) break
            val readTimeoutMs = remainingMs.coerceAtMost(50L).coerceAtLeast(1L).toInt()
            val n = try { port.read(buf, readTimeoutMs) } catch (e: Exception) { throw e }
            val afterReadMs = SystemClock.elapsedRealtime()
            if (n > 0) {
                for (i in 0 until n) out.add(buf[i])
                lastRx = afterReadMs
            } else if (out.isNotEmpty() && afterReadMs - lastRx >= 90L) {
                break
            }
        }
        return out.toByteArray()
    }

    /**
     * TXループ専用の、確保ゼロのRXドレイン。
     * 戻り値を作らず、呼び出し側が用意した [scratch] を使い回して読み捨てる。
     * バッファが空になった時点（read<=0）で即抜けるので、ウィンドウいっぱい空回りしない。
     * 読み捨てたバイト数を返し、ブラックボックスのACK(リンク健全度)指標に使う。
     */
    private fun drainSerialQuietCountLocked(port: UsbSerialPort, scratch: ByteArray, durationMs: Int): Int {
        if (durationMs <= 0) return 0
        var total = 0
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            val readTimeoutMs = remainingMs.coerceAtMost(20L).coerceAtLeast(1L).toInt()
            val n = port.read(scratch, readTimeoutMs)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun drainSerialAvailableLocked(port: UsbSerialPort, durationMs: Int = 80): ByteArray {
        val out = ArrayList<Byte>()
        val buf = ByteArray(256)
        if (durationMs <= 0) return out.toByteArray()
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            val readTimeoutMs = remainingMs.coerceAtMost(20L).coerceAtLeast(1L).toInt()
            val n = try { port.read(buf, readTimeoutMs) } catch (e: Exception) { throw e }
            if (n > 0) {
                for (i in 0 until n) out.add(buf[i])
            }
        }
        return out.toByteArray()
    }

    private fun applyMu3LinePresetLocked(port: UsbSerialPort, preset: String) {
        // SAFE: MU3のMODE端子はOpen=Command mode。固定でDTR/RTSを強く触らず、
        // 旧版で疑わしかった DTR=true / RTS=false を避ける。
        when (preset) {
            "DTR0_RTS0" -> { try { port.setDTR(false) } catch (_: Exception) {}; try { port.setRTS(false) } catch (_: Exception) {} }
            "DTR0_RTS1" -> { try { port.setDTR(false) } catch (_: Exception) {}; try { port.setRTS(true) } catch (_: Exception) {} }
            "DTR1_RTS0" -> { try { port.setDTR(true) } catch (_: Exception) {}; try { port.setRTS(false) } catch (_: Exception) {} }
            "DTR1_RTS1" -> { try { port.setDTR(true) } catch (_: Exception) {}; try { port.setRTS(true) } catch (_: Exception) {} }
            else -> { try { port.setDTR(false) } catch (_: Exception) {}; try { port.setRTS(true) } catch (_: Exception) {} }
        }
    }

    private fun runWakeTest() {
        runDiagnostic("WAKE TEST") {
            stopSendingFromWorker()
            val res = synchronized(serialWriteLock) {
                val port = serialPort ?: throw IllegalStateException("MU-3が未接続です")
                drainSerialAvailableLocked(port, 100)
                wakeMu3CommandModeLocked(port)
                sendAsciiCommandLocked(port, "@VR\r\n".toByteArray(StandardCharsets.US_ASCII), 1200)
            }
            val text = String(res, StandardCharsets.ISO_8859_1)
            appendMu3Log("[WAKE TEST] @VR -> ${text.trim().ifBlank { "NO RESPONSE" }}\n")
        }
    }

    private fun autoRecoverMu3Response() {
        if (serialPort == null) {
            setStatus("先にMU-3を接続してください")
            return
        }
        runDiagnostic("AUTO RECOVER") {
            stopSendingFromWorker()
            val port = serialPort ?: throw IllegalStateException("MU-3が未接続です")
            val baudCandidates = listOf(currentBaudRate, 19200, 57600, 38400, 9600, 4800, 2400, 1200).distinct()
            val linePresets = listOf("SAFE", "DTR0_RTS1", "DTR0_RTS0", "DTR1_RTS1", "DTR1_RTS0")
            var found: Pair<Int, String>? = null
            var foundText = ""
            synchronized(serialWriteLock) {
                for (baud in baudCandidates) {
                    if (!isSupportedBaud(baud)) continue
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    Thread.sleep(120)
                    for (preset in linePresets) {
                        applyMu3LinePresetLocked(port, preset)
                        Thread.sleep(180)
                        drainSerialAvailableLocked(port, 100)
                        port.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()), 150)
                        Thread.sleep(80)
                        drainSerialAvailableLocked(port, 100)
                        port.write("@VR\r\n".toByteArray(StandardCharsets.US_ASCII), 200)
                        val res = readSerialAvailableLocked(port, 1200)
                        val text = String(res, StandardCharsets.ISO_8859_1)
                        appendMu3Log("[AUTO] ${baud}bps / $preset -> ${text.trim().ifBlank { "NO RESPONSE" }}\n")
                        if (text.contains("*") || text.contains("MU", ignoreCase = true) || text.contains("VR", ignoreCase = true)) {
                            found = baud to preset
                            foundText = text
                            break
                        }
                    }
                    if (found != null) break
                }
                // 見つからなければ、最後に試した速度・線状態のままにせず元の設定へ戻す。
                if (found == null) {
                    port.setParameters(currentBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    applyMu3LinePresetLocked(port, mu3LinePreset)
                }
            }
            val ok = found
            if (ok != null) {
                currentBaudRate = ok.first
                mu3LinePreset = ok.second
                saveCommSettings()
                mainHandler.post {
                    updateCommUi()
                    updateConnectionBadges()
                    setStatus("AUTO RECOVER成功: ${ok.first}bps / ${ok.second}")
                }
                appendMu3Log("[AUTO RECOVER OK] baud=${ok.first}, line=${ok.second}\n${foundText.trim()}\n")
            } else {
                mainHandler.post { setStatus("AUTO RECOVER失敗: ケーブル・電源LED・初期化ボタンを確認") }
                appendMu3Log("[AUTO RECOVER FAILED] すべてNO RESPONSE。MU-3の電源LED、USB権限、ボーレート保存状態、初期化を確認。\n")
            }
        }
    }

    private fun sendDiagCommand(command: String) {
        if (serialPort == null) {
            updateMu3CommandResult(command, "MU-3が未接続です", isError = true)
            return
        } else if (diagBusy.get()) {
            updateMu3CommandResult(command, "別の診断・設定変更が実行中です。完了後に再実行してください", isError = true)
            return
        }
        runDiagnostic("CMD") {
            stopSendingFromWorker()
            try {
                sendAsciiCommand(command)
            } catch (e: Exception) {
                updateMu3CommandResult(command, e.message ?: "ERROR", isError = true)
                throw e
            }
        }
    }

    private fun runDiagnostic(name: String, block: () -> Unit) {
        if (serialPort == null) {
            setStatus("先にMU-3を接続してください")
            return
        }
        if (!diagBusy.compareAndSet(false, true)) {
            setStatus("別の診断・設定変更が実行中です。完了後に再実行してください")
            return
        }
        setStatus("DIAG $name 実行中...")
        diagExecutor.execute {
            try {
                block()
                mainHandler.post { setStatus("DIAG $name 完了") }
            } catch (e: Exception) {
                appendMu3Log("!! $name ERROR: ${e.message}\n")
                synchronized(serialWriteLock) {
                    closeSerialInternal()
                }
                mainHandler.post {
                    setStatus("DIAG $name エラー: ${e.message}")
                    updateConnectionBadges()
                }
            } finally {
                diagBusy.set(false)
                mainHandler.post { updateCommUi() }
            }
        }
    }

    private fun stopSendingFromWorker() {
        stopSendingInternal(showStatus = false, joinThread = true)
    }

    private fun appendMu3Log(message: String) {
        mainHandler.post {
            mu3DiagLogBuffer.append(message)
            val max = 6000
            if (mu3DiagLogBuffer.length > max) {
                mu3DiagLogBuffer.delete(0, mu3DiagLogBuffer.length - max)
            }
            if (::mu3DiagLogText.isInitialized) mu3DiagLogText.text = mu3DiagLogBuffer.toString()
        }
    }

    private fun updateMu3CommandResult(command: String, response: String, isError: Boolean = false) {
        val displayCommand = command.trim().ifBlank { "(blank)" }
        val displayResponse = response.trim().ifBlank { "(NO RESPONSE)" }
        val prefix = if (isError) "!!" else "<"
        val text = "> $displayCommand\n$prefix $displayResponse".let {
            if (it.length <= 1800) it else it.take(1800) + "\n..."
        }
        mainHandler.post {
            if (::mu3CommandResultText.isInitialized) {
                mu3CommandResultText.text = text
            }
        }
    }

    private fun applyTxIntervalFromUi() {
        val next = txIntervalInput.text.toString().trim().toLongOrNull()
        if (next == null || next !in 10L..1000L) {
            setStatus("送信周期は10〜1000msで入力してください")
            return
        }
        // 送信ループは毎周期 txIntervalMs を読むので、停止せずにそのまま反映する。
        // 停止→再開すると保存地点・仰角の指示が取り消されてしまう（AUTO-TUNEは1.6秒ごとに呼ぶ）。
        txIntervalMs = stableTxIntervalMs(next)

        // Sync SeekBar
        if (::txIntervalSeekBar.isInitialized) {
            txIntervalSeekBar.progress = (txIntervalMs.toInt() - 10).coerceIn(0, 190)
        }

        saveCommSettings()
        val corrected = if (txIntervalMs != next) " (現在の${currentBaudRate}bpsで安定する下限に補正)" else ""
        setStatus("送信周期を ${txIntervalMs}ms に変更$corrected")
        updateConnectionBadges()
        updateCommUi()
    }

    private fun setTxInterval(ms: Int) {
        if (::txIntervalInput.isInitialized) txIntervalInput.setText(ms.toString())
        applyTxIntervalFromUi()
    }

    private fun setBaudRate(baud: Int) {
        if (::baudRateInput.isInitialized) baudRateInput.setText(baud.toString())
        applyBaudRateFromUi(false)
    }

    private fun applyFastPreset() {
        // applyTxIntervalFromUi() が内部で updateCommUi() を呼び baudRateInput を
        // 現在値に上書きするため、ボーレート入力は周期適用の「後」に設定して読ませる。
        if (::txIntervalInput.isInitialized) txIntervalInput.setText("30")
        applyTxIntervalFromUi()
        if (::baudRateInput.isInitialized) baudRateInput.setText("57600")
        applyBaudRateFromUi(false)
    }

    private fun applyBaudRateFromUi(saveToModule: Boolean) {
        val next = baudRateInput.text.toString().trim().toIntOrNull()
        if (next == null || !isSupportedBaud(next)) {
            setStatus("対応速度は 1200/2400/4800/9600/19200/38400/57600 bps です")
            return
        }

        val wasSending = isSending
        if (wasSending) stopSending()

        val port = serialPort
        if (port == null) {
            currentBaudRate = next
            val corrected = enforceStableTxIntervalForBaud()
            saveCommSettings()
            setStatus("接続前設定: 次回 ${currentBaudRate}bps で開きます${if (corrected) " / TX ${txIntervalMs}msに補正" else ""}")
            updateCommUi()
            return
        }

        if (!diagBusy.compareAndSet(false, true)) {
            setStatus("別の診断・設定変更が実行中です。完了後に再実行してください")
            if (wasSending) startSending()
            return
        }

        diagExecutor.execute {
            try {
                val code = baudCommandCode(next)
                val cmd = if (saveToModule) "@BR$code/W\r\n" else "@BR$code\r\n"
                val response = synchronized(serialWriteLock) {
                    if (serialPort !== port) throw IllegalStateException("USB connection changed")
                    drainSerialAvailableLocked(port, 80)
                    wakeMu3CommandModeLocked(port)
                    port.write(cmd.toByteArray(StandardCharsets.US_ASCII), 400)
                    val res = readSerialAvailableLocked(port, 900)
                    Thread.sleep(160)
                    port.setParameters(next, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    applyMu3LinePresetLocked(port, mu3LinePreset)
                    res
                }
                currentBaudRate = next
                val corrected = enforceStableTxIntervalForBaud()
                saveCommSettings()
                appendMu3Log("> ${cmd.trim()}\n< ${String(response, StandardCharsets.ISO_8859_1).trim().ifBlank { "(no response)" }}\n[BAUD] Android/MU-3 UART = ${next}bps${if (saveToModule) " / saved" else ""}\n")
                mainHandler.post {
                    setStatus("通信速度を ${next}bps に変更${if (saveToModule) "・保存" else ""}${if (corrected) " / TX ${txIntervalMs}msに補正" else ""}")
                    updateConnectionBadges()
                    updateCommUi()
                    if (wasSending) startSending()
                }
            } catch (e: Exception) {
                synchronized(serialWriteLock) {
                    closeSerialInternal()
                }
                mainHandler.post {
                    setStatus("通信速度変更失敗: ${e.message}")
                    updateConnectionBadges()
                    if (wasSending) startSending()
                }
            } finally {
                diagBusy.set(false)
                mainHandler.post { updateCommUi() }
            }
        }
    }

    private fun isSupportedBaud(baud: Int): Boolean {
        return baud in listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600)
    }

    private fun baudCommandCode(baud: Int): String {
        return when (baud) {
            1200 -> "12"
            2400 -> "24"
            4800 -> "48"
            9600 -> "96"
            19200 -> "19"
            38400 -> "38"
            57600 -> "57"
            else -> throw IllegalArgumentException("Unsupported baud: $baud")
        }
    }

    private fun loadCommSettings() {
        val prefs = getSharedPreferences(commPrefsName, Context.MODE_PRIVATE)
        currentBaudRate = prefs.getInt("baud", 19200).let { if (isSupportedBaud(it)) it else 19200 }
        txIntervalMs = prefs.getLong("interval_ms", 50L).coerceIn(10L, 1000L)
        enforceStableTxIntervalForBaud()
        mu3LinePreset = prefs.getString("line_preset", "SAFE") ?: "SAFE"
        isDarkMode = prefs.getBoolean("dark_mode", false)
        savedNavigationRightField = prefs.getBoolean("saved_nav_right_field", false)
    }

    private fun saveCommSettings() {
        getSharedPreferences(commPrefsName, Context.MODE_PRIVATE).edit()
            .putInt("baud", currentBaudRate)
            .putLong("interval_ms", txIntervalMs)
            .putString("line_preset", mu3LinePreset)
            .putBoolean("dark_mode", isDarkMode)
            .putBoolean("saved_nav_right_field", savedNavigationRightField)
            .apply()
    }

    private fun updateCommUi() {
        if (::txIntervalInput.isInitialized) txIntervalInput.setText(txIntervalMs.toString())
        if (::txIntervalSeekBar.isInitialized) {
            txIntervalSeekBar.progress = (txIntervalMs.toInt() - 10).coerceIn(0, 190)
        }
        if (::baudRateInput.isInitialized) baudRateInput.setText(currentBaudRate.toString())
        if (::commStatusText.isInitialized) {
            val errorLine = if (txErrorCount > 0) "\nTX errors: $txErrorCount / last=$lastTxError" else ""
            val diagLine = if (diagBusy.get()) "\nDIAG: running" else "\nDIAG: idle"
            commStatusText.text = "TX FORMAT: @DT0E + HEX ASCII / ${txIntervalMs} ms / ${currentBaudRate} bps\n" +
                "Scheduler: fixed-deadline sender / write timeout ${timeoutForFrame(MU3_TX_FRAME_LEN)}ms / diagnostics guarded" +
                diagLine + errorLine
        }
    }

    private fun readLinkParams() {
        runDiagnostic("READ LINK") {
            stopSendingFromWorker()
            val cmds = listOf("@UI", "@GI", "@CH", "@EI", "@DI", "@VR")
            for (cmd in cmds) {
                sendAsciiCommand(cmd)
                Thread.sleep(60)
            }
        }
    }

    private fun applyLinkParams(save: Boolean) {
        val suffix = if (save) "/W" else ""
        val ui: String
        val gi: String
        val ch: String
        val ei: String
        val di: String
        try {
            ui = normalizeHexField(linkUiInput.text.toString(), 4, "UI")
            gi = normalizeHexField(linkGiInput.text.toString(), 2, "GI")
            ch = normalizeHexField(linkChInput.text.toString(), 2, "CH")
            ei = normalizeHexField(linkEiInput.text.toString(), 2, "EI")
            di = normalizeHexField(linkDiInput.text.toString(), 2, "DI")
            val chValue = ch.toInt(16)
            if (chValue !in 0x02..0x19) {
                setStatus("CHは02〜19の範囲で入力してください")
                return
            }
        } catch (e: IllegalArgumentException) {
            setStatus(e.message ?: "リンク設定の入力値を確認してください")
            return
        }
        runDiagnostic(if (save) "SAVE LINK" else "APPLY LINK") {
            stopSendingFromWorker()
            listOf("@UI$ui$suffix", "@GI$gi$suffix", "@CH$ch$suffix", "@EI$ei$suffix", "@DI$di$suffix").forEach { cmd ->
                sendAsciiCommand(cmd)
                Thread.sleep(100)
            }
        }
    }

    private fun normalizeHexField(raw: String, digits: Int, label: String): String {
        val value = raw.trim().uppercase(Locale.US)
        if (value.isEmpty() || value.length > digits || !value.all { it in '0'..'9' || it in 'A'..'F' }) {
            throw IllegalArgumentException("$label は${digits}桁以内の16進数で入力してください")
        }
        return value.padStart(digits, '0')
    }

    private fun runQualityCheck() {
        runDiagnostic("QUALITY") {
            stopSendingFromWorker()
            val rssi = sendAsciiCommand("@RA", 450)
            Thread.sleep(80)
            val sn = sendAsciiCommand("@CR", 600)
            val nums = Regex("[0-9A-Fa-f]{2}").findAll(sn).mapNotNull { runCatching { it.value.toInt(16) }.getOrNull() }.toList()
            val estimate = if (nums.size >= 2) {
                val s = nums[nums.size - 2]
                val n = nums[nums.size - 1]
                "\nParsed: S=$s / N=$n / diff=${s - n}"
            } else ""
            val text = "RSSI @RA\n${rssi.ifBlank { "(no response)" }}\n\nS/N @CR\n${sn.ifBlank { "(no response)" }}$estimate"
            mainHandler.post { if (::mu3RssiText.isInitialized) mu3RssiText.text = text }
        }
    }

    private fun runRssiCheck() {
        runDiagnostic("RSSI") {
            stopSendingFromWorker()
            val res = sendAsciiCommand("@RA", 450)
            mainHandler.post { if (::mu3RssiText.isInitialized) mu3RssiText.text = "@RA RSSI RAW\n${res.ifBlank { "(no response)" }}" }
        }
    }

    private fun runSnCheck() {
        runDiagnostic("S/N") {
            stopSendingFromWorker()
            val res = sendAsciiCommand("@CR", 600)
            val nums = Regex("[0-9A-Fa-f]{2}").findAll(res).mapNotNull { runCatching { it.value.toInt(16) }.getOrNull() }.toList()
            val estimate = if (nums.size >= 2) {
                val s = nums[nums.size - 2]
                val n = nums[nums.size - 1]
                "\nParsed HEX候補: S=$s / N=$n / 差=${s - n}"
            } else ""
            mainHandler.post { if (::mu3RssiText.isInitialized) mu3RssiText.text = "@CR S/N RAW\n${res.ifBlank { "(no response)" }}$estimate" }
        }
    }

    private fun runAirScan() {
        val start = scanStartInput.text.toString().trim().toIntOrNull(16)?.coerceIn(0x02, 0x19) ?: 0x02
        val end = scanEndInput.text.toString().trim().toIntOrNull(16)?.coerceIn(start, 0x19) ?: 0x19
        runDiagnostic("AIR SCAN") {
            stopSendingFromWorker()
            val sb = StringBuilder("CH  @RA response\n")
            for (ch in start..end) {
                val chHex = String.format(Locale.US, "%02X", ch)
                sendAsciiCommand("@CH$chHex", 300)
                Thread.sleep(120)
                val res = sendAsciiCommand("@RA", 450).trim().replace("\r", "").replace("\n", " ")
                sb.append(chHex).append("  ").append(if (res.isEmpty()) "(no response)" else res).append('\n')
                val snapshot = sb.toString()
                mainHandler.post {
                    if (::mu3RssiText.isInitialized) mu3RssiText.text = snapshot
                    if (::mu3ScanText.isInitialized) mu3ScanText.text = snapshot
                }
            }
        }
    }

    private fun runPacketTest(count: Int) {
        runDiagnostic("PACKET TEST") {
            stopSendingFromWorker()
            var accepted = 0
            var irError = 0
            // payload[6] は機構ボタン・非常停止・仰角プリセットに使われるため、
            // 試験フレームは必ずニュートラルにする（受信側を動かさない）。
            val frame = formatMu3Frame(PadState.neutralPayload())
            for (i in 1..count) {
                val response = synchronized(serialWriteLock) {
                    val port = serialPort ?: throw IllegalStateException("MU-3が未接続です")
                    port.write(frame.toByteArray(StandardCharsets.US_ASCII), 50)
                    String(readSerialAvailableLocked(port, 180), StandardCharsets.ISO_8859_1)
                }
                if (response.contains("*DT")) accepted++
                if (response.contains("*IR")) irError++
                if (i % 10 == 0 || i == count) {
                    val text = "PACKET TEST @DT0E HEX\nSent: $i / $count\nAccepted(*DT): $accepted\nIR/Error: $irError\nLocal accept rate: ${accepted * 100 / i}%"
                    mainHandler.post { if (::mu3PacketText.isInitialized) mu3PacketText.text = text }
                }
                Thread.sleep(stableTxIntervalMs())
            }
            appendMu3Log("PACKET TEST done: accepted=$accepted / $count, IR=$irError\n")
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isGameControllerEvent(event)) return super.dispatchGenericMotionEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (abs(hatX) > 0.5f) highlightInputRow(if (hatX < -0.5f) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            if (abs(hatY) > 0.5f) highlightInputRow(if (hatY < -0.5f) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            
            val l2 = chooseNonZero(getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER), getCenteredAxis(event, MotionEvent.AXIS_BRAKE)) > 0.5f
            val r2 = chooseNonZero(getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER), getCenteredAxis(event, MotionEvent.AXIS_GAS)) > 0.5f
            if (l2) highlightInputRow(KeyEvent.KEYCODE_BUTTON_L2)
            if (r2) highlightInputRow(KeyEvent.KEYCODE_BUTTON_R2)
        }

        synchronized(pad) {
            pad.lx = getCenteredAxis(event, MotionEvent.AXIS_X)
            pad.ly = getCenteredAxis(event, MotionEvent.AXIS_Y)

            val rightX1 = getCenteredAxis(event, MotionEvent.AXIS_Z)
            val rightY1 = getCenteredAxis(event, MotionEvent.AXIS_RZ)
            val rightX2 = getCenteredAxis(event, MotionEvent.AXIS_RX)
            val rightY2 = getCenteredAxis(event, MotionEvent.AXIS_RY)
            pad.rx = chooseNonZero(rightX1, rightX2)
            pad.ry = chooseNonZero(rightY1, rightY2)

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            pad.setAxisInput(KeyEvent.KEYCODE_DPAD_LEFT, hatX < -0.5f)
            pad.setAxisInput(KeyEvent.KEYCODE_DPAD_RIGHT, hatX > 0.5f)
            pad.setAxisInput(KeyEvent.KEYCODE_DPAD_UP, hatY < -0.5f)
            pad.setAxisInput(KeyEvent.KEYCODE_DPAD_DOWN, hatY > 0.5f)

            val l2 = chooseNonZero(
                getCenteredAxis(event, MotionEvent.AXIS_LTRIGGER),
                getCenteredAxis(event, MotionEvent.AXIS_BRAKE)
            ) > 0.5f
            val r2 = chooseNonZero(
                getCenteredAxis(event, MotionEvent.AXIS_RTRIGGER),
                getCenteredAxis(event, MotionEvent.AXIS_GAS)
            ) > 0.5f
            pad.setAxisInput(KeyEvent.KEYCODE_BUTTON_L2, l2)
            pad.setAxisInput(KeyEvent.KEYCODE_BUTTON_R2, r2)
        }
        updateUiState()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isGameControllerEvent(event)) return super.dispatchKeyEvent(event)
        val input = controllerInputByKeyCode[event.keyCode] ?: return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            highlightInputRow(event.keyCode)
        }

        val pressed = event.action != KeyEvent.ACTION_UP
        synchronized(pad) {
            pad.setControllerInput(input.id, pressed)
        }
        updateUiState()
        return true
    }

    private fun isGameControllerEvent(event: InputEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val device = event.device ?: return 0f
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        return if (abs(value) > range.flat) value.coerceIn(-1f, 1f) else 0f
    }

    private fun chooseNonZero(primary: Float, fallback: Float): Float {
        return if (abs(primary) >= abs(fallback)) primary else fallback
    }

    private fun highlightInputRow(keyCode: Int) {
        val dialog = mappingDialog ?: return
        if (!dialog.isShowing) return
        val view = controllerInputViews[keyCode] ?: return
        
        mainHandler.post {
            mappingEditorScrollView?.smoothScrollTo(0, view.top - dp(40))
            
            // Temporary highlight
            val originalBackground = view.background
            view.background = rounded(colors.surfaceVariant, 14f, strokeColor = colors.accentPrimary, strokeWidth = 2)
            mainHandler.postDelayed({
                view.background = originalBackground
            }, 600)
        }
    }

    private fun setStatus(message: String) {
        if (!::statusText.isInitialized) return
        val action = Runnable {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val entry = "[$timestamp] $message"
            statusText.text = message

            if (::logText.isInitialized) {
                statusHistory.add(0, entry)
                if (statusHistory.size > 8) statusHistory.removeAt(statusHistory.size - 1)
                logText.text = statusHistory.joinToString("\n")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    private fun toggleTheme(dark: Boolean) {
        if (isDarkMode == dark) return
        isDarkMode = dark
        saveCommSettings()
        recreate()
    }

    private fun updateConnectionBadges() {
        if (!::connectionBadge.isInitialized || !::sendBadge.isInitialized) return
        val usbActive = serialPort != null
        val txActive = isSending
        val effectiveIntervalMs = stableTxIntervalMs()
        val txIntervalLabel = if (effectiveIntervalMs == txIntervalMs) {
            "${txIntervalMs}ms"
        } else {
            "${txIntervalMs}->${effectiveIntervalMs}ms"
        }
        runOnUiThread {
            connectionBadge.text = if (usbActive) "USB: MU-3接続中" else "USB: 未接続"
            connectionBadge.background = tonalSurfaceDrawable(colors.surface, if (usbActive) color("#0B8043") else colors.accentPrimary, 999f, 0.75f)
            sendBadge.text = if (txActive) "TX: 送信中 $txIntervalLabel" else "TX: 停止中"
            sendBadge.background = tonalSurfaceDrawable(colors.surface, if (txActive) color("#4285F4") else colors.accentPrimary, 999f, 0.75f)
            updateDashboardChip(
                dashboardUsbChip,
                if (usbActive) "USB LIVE" else "USB WAIT",
                colors.surface,
                if (usbActive) color("#0B8043") else colors.accentPrimary
            )
            updateDashboardChip(
                dashboardTxChip,
                if (txActive) "TX $txIntervalLabel" else "TX IDLE",
                colors.surface,
                if (txActive) color("#4285F4") else colors.accentPrimary
            )
            updateCompactChromeBar()
        }
    }

    private fun updateUiState() {
        if (!::payloadText.isInitialized) return
        val cfg = currentConfig()
        val snapshot = synchronized(pad) { pad.snapshot(cfg, targetsById) }
        val payload = snapshot.payload

        runOnUiThread {
            leftJoystickView?.apply {
                xValue = snapshot.lx
                yValue = snapshot.ly
                isJoystickPressed = snapshot.controllerStates[KeyEvent.KEYCODE_BUTTON_THUMBL] == true
                invalidate()
            }
            rightJoystickView?.apply {
                xValue = snapshot.rx
                yValue = snapshot.ry
                isJoystickPressed = snapshot.controllerStates[KeyEvent.KEYCODE_BUTTON_THUMBR] == true
                invalidate()
            }

            for ((keyCode, view) in dashboardButtonViews) {
                val pressed = snapshot.controllerStates[keyCode] == true
                if (pressed) {
                    val neon = color(view.tag as String)
                    view.background = tonalSurfaceDrawable(colors.surface, neon, 14f, 1f)
                    view.setTextColor(colors.textPrimary)
                    view.elevation = 0f
                    view.scaleX = 1.06f
                    view.scaleY = 1.06f
                } else {
                    val neon = color(view.tag as String)
                    view.background = tonalSurfaceDrawable(colors.surface, neon, 14f, 0.48f)
                    view.setTextColor(blendColors(colors.textSecondary, neon, 0.36f))
                    view.elevation = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }

            val pressedNames = snapshot.controllerStates
                .filter { it.value }
                .map { (keyCode, _) -> controllerInputByKeyCode[keyCode]?.label ?: "Key:$keyCode" }
            physicalInputText.text = if (pressedNames.isEmpty()) "--" else pressedNames.joinToString(", ")

            if (!isSending) {
                payloadText.text = payloadDisplayText(payload, "INPUT PREVIEW")
            }

            for (spec in cfg.screenButtons) {
                val tile = featureViews[spec.slot] ?: continue
                updateFeatureButtonView(tile, spec, snapshot.screenStates.getOrNull(spec.slot) == true)
            }
        }
    }

    private fun updatePayloadOnly(payload: ByteArray, label: String) {
        if (!::payloadText.isInitialized) return
        payloadText.text = payloadDisplayText(payload, label)
    }

    private fun payloadDisplayText(payload: ByteArray, label: String): String {
        val payloadHex = payload.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        val mu3Frame = formatMu3FramePreview(payload)
        return """
            Source          : $label
            Payload 7 bytes : $payloadHex
            MU-3 Frame      : $mu3Frame
        """.trimIndent()
    }

    private fun targetLabel(targetId: String): String = targetsById[targetId]?.label ?: "未割当 / OFF"

    private fun currentConfig(): ControlConfig {
        return synchronized(configLock) { controlConfig }
    }

    private fun replaceConfig(newConfig: ControlConfig, status: String) {
        val normalized = newConfig.copy(
            screenButtons = newConfig.screenButtons
                .take(maxScreenButtons)
                .mapIndexed { index, spec -> spec.copy(slot = index + 1) }
        )
        synchronized(configLock) { controlConfig = normalized }
        saveControlConfig(normalized)
        rebuildFeatureButtons()
        updateUiState()
        setStatus(status)
    }

    private fun firstUnusedExtraTarget(cfg: ControlConfig): String {
        val used = cfg.screenButtons.map { it.targetId }.toSet()
        for (bit in 1..7) {
            val id = "P6_B${bit}_EXTRA$bit"
            if (id !in used) return id
        }
        return "NONE"
    }

    private fun defaultControlConfig(): ControlConfig {
        val map = mutableMapOf<Int, String>()
        map[KeyEvent.KEYCODE_BUTTON_A] = "P4_B5_CROSS"
        map[KeyEvent.KEYCODE_BUTTON_B] = "P5_B2_CIRCLE"
        map[KeyEvent.KEYCODE_BUTTON_Y] = "P4_B4_TRIANGLE"
        map[KeyEvent.KEYCODE_BUTTON_X] = "P5_B3_SQUARE"
        map[KeyEvent.KEYCODE_BUTTON_L1] = "P4_B3_L1"
        map[KeyEvent.KEYCODE_BUTTON_R1] = "P4_B2_R1"
        map[KeyEvent.KEYCODE_BUTTON_L2] = "P4_B1_L2"
        map[KeyEvent.KEYCODE_BUTTON_R2] = "P4_B0_R2"
        map[KeyEvent.KEYCODE_BUTTON_SELECT] = "P5_B4_SHARE"
        map[KeyEvent.KEYCODE_BUTTON_START] = "P5_B5_OPTIONS"
        map[KeyEvent.KEYCODE_BUTTON_MODE] = "P6_B0_PS"
        map[KeyEvent.KEYCODE_BUTTON_THUMBL] = "P5_B6_L3"
        map[KeyEvent.KEYCODE_BUTTON_THUMBR] = "P5_B7_R3"
        map[KeyEvent.KEYCODE_DPAD_UP] = "P4_B6_UP"
        map[KeyEvent.KEYCODE_DPAD_DOWN] = "P4_B7_DOWN"
        map[KeyEvent.KEYCODE_DPAD_LEFT] = "P5_B1_LEFT"
        map[KeyEvent.KEYCODE_DPAD_RIGHT] = "P5_B0_RIGHT"

        val screenButtons = listOf(
            ScreenButtonSpec(1, "F1", "P6_B1_EXTRA1", FeatureMode.MOMENTARY),
            ScreenButtonSpec(2, "F2", "P6_B2_EXTRA2", FeatureMode.MOMENTARY),
            ScreenButtonSpec(3, "F3", "P6_B3_EXTRA3", FeatureMode.MOMENTARY),
            ScreenButtonSpec(4, "F4", "P6_B4_EXTRA4", FeatureMode.MOMENTARY),
            ScreenButtonSpec(5, "F5", "P6_B5_EXTRA5", FeatureMode.TOGGLE),
            ScreenButtonSpec(6, "F6", "P6_B6_EXTRA6", FeatureMode.TOGGLE),
            ScreenButtonSpec(7, "F7", "P6_B7_EXTRA7", FeatureMode.TOGGLE)
        )
        return ControlConfig(map, screenButtons)
    }

    private fun loadControlConfig(): ControlConfig {
        val prefs = getSharedPreferences(configPrefsName, Context.MODE_PRIVATE)
        val defaults = defaultControlConfig()
        val map = defaults.controllerTargets.toMutableMap()
        for (input in controllerInputs) {
            val saved = prefs.getString("ctrl_${input.id}", null)
            if (saved != null && targetsById.containsKey(saved)) map[input.id] = saved
        }

        val savedCount = prefs.getInt("screen_count", defaults.screenButtons.size).coerceIn(0, maxScreenButtons)
        val buttons = mutableListOf<ScreenButtonSpec>()
        for (slot in 1..savedCount) {
            val default = defaults.screenButtons.getOrNull(slot - 1) ?: ScreenButtonSpec(slot, "BTN$slot", "NONE", FeatureMode.MOMENTARY)
            val label = prefs.getString("screen_${slot}_label", default.label) ?: default.label
            val targetId = prefs.getString("screen_${slot}_target", default.targetId) ?: default.targetId
            val modeName = prefs.getString("screen_${slot}_mode", default.mode.name) ?: default.mode.name
            val mode = runCatching { FeatureMode.valueOf(modeName) }.getOrDefault(default.mode)
            buttons.add(
                ScreenButtonSpec(
                    slot = slot,
                    label = label.take(12),
                    targetId = if (targetsById.containsKey(targetId)) targetId else "NONE",
                    mode = mode
                )
            )
        }
        return ControlConfig(map, buttons)
    }

    private fun saveControlConfig(cfg: ControlConfig) {
        val prefs = getSharedPreferences(configPrefsName, Context.MODE_PRIVATE)
        prefs.edit().apply {
            clear()
            for ((inputId, targetId) in cfg.controllerTargets) {
                putString("ctrl_$inputId", targetId)
            }
            putInt("screen_count", cfg.screenButtons.size)
            for (button in cfg.screenButtons) {
                putString("screen_${button.slot}_label", button.label)
                putString("screen_${button.slot}_target", button.targetId)
                putString("screen_${button.slot}_mode", button.mode.name)
            }
        }.apply()
    }

    private class AppColors(
        val bg: Int,
        val surface: Int,
        val surfaceVariant: Int,
        val outline: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accentPrimary: Int,
        val accentTeal: Int,
        val accentSuccess: Int,
        val accentInfo: Int,
        val accentWarning: Int,
        val accentCoral: Int,
        val accentDanger: Int,
        val accentViolet: Int,
        val accentNeutral: Int,
        val orb1: Int,
        val orb2: Int,
        val orb3: Int,
        val orb4: Int,
        val gridLine: Int
    ) {
        companion object {
            private fun parse(hex: String) = Color.parseColor(hex)

            @SuppressLint("DiscouragedApi")
            private fun dynamicColor(context: Context, name: String, fallback: String): Int {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return parse(fallback)
                val id = context.resources.getIdentifier(name, "color", "android")
                return if (id != 0) context.resources.getColor(id, context.theme) else parse(fallback)
            }

            @Suppress("UNUSED_PARAMETER")
            fun light(context: Context) = AppColors(
                bg = parse("#F6F8FC"),
                surface = parse("#FFFFFF"),
                surfaceVariant = parse("#E9EEF7"),
                outline = parse("#C8D2E1"),
                textPrimary = parse("#172033"),
                textSecondary = parse("#536176"),
                accentPrimary = parse("#3559D6"),
                accentTeal = parse("#007A78"),
                accentSuccess = parse("#0B8043"),
                accentInfo = parse("#2875E3"),
                accentWarning = parse("#E37400"),
                accentCoral = parse("#C33D2E"),
                accentDanger = parse("#B3261E"),
                accentViolet = parse("#6D4AFF"),
                accentNeutral = parse("#64748B"),
                orb1 = parse("#DCE7FF"),
                orb2 = parse("#FBE0DA"),
                orb3 = parse("#DEF3E7"),
                orb4 = parse("#D8F0F3"),
                gridLine = parse("#D8E0EC")
            )

            @Suppress("UNUSED_PARAMETER")
            fun dark(context: Context) = AppColors(
                bg = parse("#111318"),
                surface = parse("#1E212B"),
                surfaceVariant = parse("#303442"),
                outline = parse("#687086"),
                textPrimary = parse("#EEF2FA"),
                textSecondary = parse("#BDC5D6"),
                accentPrimary = parse("#A9B7FF"),
                accentTeal = parse("#64D8D0"),
                accentSuccess = parse("#81C995"),
                accentInfo = parse("#8AB4F8"),
                accentWarning = parse("#FDD663"),
                accentCoral = parse("#FFB4A8"),
                accentDanger = parse("#F2B8B5"),
                accentViolet = parse("#D0BCFF"),
                accentNeutral = parse("#AEB6C7"),
                orb1 = parse("#1B2945"),
                orb2 = parse("#3A2825"),
                orb3 = parse("#23362B"),
                orb4 = parse("#1F3338"),
                gridLine = parse("#29303B")
            )
        }
    }

    /**
     * 1フレーム分のブラックボックス記録を読み出すための可変ホルダー。
     * リプレイ/グラフ描画で毎フレーム確保しないよう使い回す。
     */
    class BbFrame {
        var tMs = 0L
        var p0 = 128; var p1 = 128; var p2 = 128; var p3 = 128
        var p4 = 0; var p5 = 0; var p6 = 0
        var gapMs = 0
        var ack = false
        var error = false
        var marker = false
        var rssi = -1
    }

    /**
     * 操縦中の送信内容を時系列で記録する固定長リングバッファ。
     *
     * - 書き込みは送信スレッド(最優先)から呼ばれるため、確保ゼロのプリミティブ配列に格納する。
     * - 読み出し(UI/リプレイ/CSV)はメインスレッド。書き込みと競合しないよう synchronized で保護する。
     * - 記録するのは「実際にMU-3へ送った7バイトpayload」なので、これがそのまま操縦のブラックボックスになる。
     */
    private class BlackBox(val capacity: Int) {
        private val tMs = LongArray(capacity)
        private val b0 = ByteArray(capacity)
        private val b1 = ByteArray(capacity)
        private val b2 = ByteArray(capacity)
        private val b3 = ByteArray(capacity)
        private val b4 = ByteArray(capacity)
        private val b5 = ByteArray(capacity)
        private val b6 = ByteArray(capacity)
        private val gap = ShortArray(capacity)
        // flags: bit0=ack, bit1=error, bit2=marker
        private val flags = ByteArray(capacity)
        private val rssiArr = ShortArray(capacity)

        private var head = 0
        var count = 0
            private set
        @Volatile var recording = false
        var totalRecorded = 0L
            private set
        // 最初に記録したサンプルの elapsedRealtime。相対時刻の基準。
        var baseMs = 0L
            private set
        // セッション開始時のウォールクロック(エクスポートのファイル名用に外から設定)。
        @Volatile var sessionWallMs = 0L

        @Synchronized fun clear() {
            head = 0
            count = 0
            totalRecorded = 0L
            baseMs = 0L
        }

        @Synchronized fun record(
            nowMs: Long,
            payload: ByteArray,
            gapMs: Int,
            ack: Boolean,
            error: Boolean,
            marker: Boolean,
            rssi: Int
        ) {
            if (!recording) return
            if (count == 0) baseMs = nowMs
            val i = head
            tMs[i] = nowMs
            b0[i] = payload[0]; b1[i] = payload[1]; b2[i] = payload[2]; b3[i] = payload[3]
            b4[i] = payload[4]; b5[i] = payload[5]; b6[i] = payload[6]
            gap[i] = gapMs.coerceIn(0, 32767).toShort()
            var f = 0
            if (ack) f = f or 1
            if (error) f = f or 2
            if (marker) f = f or 4
            flags[i] = f.toByte()
            rssiArr[i] = rssi.coerceIn(-1, 32767).toShort()
            head = (i + 1) % capacity
            if (count < capacity) count++
            totalRecorded++
        }

        /** 操縦エラーやマーカーなど、payloadを伴わないイベントもニュートラル値で1点記録する。 */
        @Synchronized fun recordEvent(nowMs: Long, error: Boolean, marker: Boolean) {
            if (!recording) return
            neutralScratch[0] = 128.toByte(); neutralScratch[1] = 128.toByte()
            neutralScratch[2] = 128.toByte(); neutralScratch[3] = 128.toByte()
            neutralScratch[4] = 0; neutralScratch[5] = 0; neutralScratch[6] = 0
            record(nowMs, neutralScratch, 0, ack = false, error = error, marker = marker, rssi = -1)
        }

        private val neutralScratch = ByteArray(7)

        private fun physical(logical: Int): Int {
            val oldest = if (count < capacity) 0 else head
            return (oldest + logical) % capacity
        }

        /** logical番目(0=最古)のサンプルを [dest] に読み出す。 */
        @Synchronized fun frameAt(logical: Int, dest: BbFrame): Boolean {
            if (logical < 0 || logical >= count) return false
            val p = physical(logical)
            dest.tMs = tMs[p] - baseMs
            dest.p0 = b0[p].toInt() and 0xFF
            dest.p1 = b1[p].toInt() and 0xFF
            dest.p2 = b2[p].toInt() and 0xFF
            dest.p3 = b3[p].toInt() and 0xFF
            dest.p4 = b4[p].toInt() and 0xFF
            dest.p5 = b5[p].toInt() and 0xFF
            dest.p6 = b6[p].toInt() and 0xFF
            dest.gapMs = gap[p].toInt()
            val f = flags[p].toInt()
            dest.ack = f and 1 != 0
            dest.error = f and 2 != 0
            dest.marker = f and 4 != 0
            dest.rssi = rssiArr[p].toInt()
            return true
        }

        /**
         * 直近 maxPoints 個のサンプルから、グラフ用の系列を一括コピーする。
         * gapMsOut: 送信間隔(ms) / ackOut: ACK(0/1) / magOut: スティック活動量(0..1) / errOut: エラー点
         * 戻り値は実際に書き込んだ点数。
         */
        @Synchronized fun copyRecent(
            maxPoints: Int,
            gapMsOut: FloatArray,
            ackOut: FloatArray,
            magOut: FloatArray,
            errOut: BooleanArray
        ): Int {
            val n = if (count < maxPoints) count else maxPoints
            val startLogical = count - n
            for (k in 0 until n) {
                val p = physical(startLogical + k)
                gapMsOut[k] = gap[p].toInt().toFloat()
                ackOut[k] = if (flags[p].toInt() and 1 != 0) 1f else 0f
                val dx = ((b0[p].toInt() and 0xFF) - 128) / 128f
                val dy = ((b1[p].toInt() and 0xFF) - 128) / 128f
                val dx2 = ((b2[p].toInt() and 0xFF) - 128) / 128f
                val dy2 = ((b3[p].toInt() and 0xFF) - 128) / 128f
                val mag = (abs(dx) + abs(dy) + abs(dx2) + abs(dy2)) / 4f
                magOut[k] = mag.coerceIn(0f, 1f)
                errOut[k] = flags[p].toInt() and 2 != 0
            }
            return n
        }

        /** 直近 windowMs ミリ秒分の集計を求める。 out には順に 実測Hz / ACK率% / エラー数 / 区間ms を返す。 */
        @Synchronized fun recentStats(windowMs: Long, out: DoubleArray) {
            out[0] = 0.0; out[1] = -1.0; out[2] = 0.0; out[3] = 0.0
            if (count == 0) return
            val newest = physical(count - 1)
            val tNewest = tMs[newest]
            var samples = 0
            var ackCount = 0
            var errCount = 0
            var oldestT = tNewest
            var k = count - 1
            while (k >= 0) {
                val p = physical(k)
                if (tNewest - tMs[p] > windowMs) break
                samples++
                val f = flags[p].toInt()
                if (f and 1 != 0) ackCount++
                if (f and 2 != 0) errCount++
                oldestT = tMs[p]
                k--
            }
            val spanMs = (tNewest - oldestT).coerceAtLeast(1L)
            out[0] = if (samples > 1) (samples - 1) * 1000.0 / spanMs else 0.0
            out[1] = if (samples > 0) ackCount * 100.0 / samples else -1.0
            out[2] = errCount.toDouble()
            out[3] = spanMs.toDouble()
        }

        /** CSVへ全サンプルを書き出す。先頭行はヘッダー。 */
        @Synchronized fun toCsv(): String {
            val sb = StringBuilder(count * 48 + 64)
            sb.append("t_ms,lx,ly,rx,ry,p4_hex,p5_hex,p6_hex,gap_ms,ack,error,marker,rssi\n")
            for (k in 0 until count) {
                val p = physical(k)
                sb.append(tMs[p] - baseMs).append(',')
                sb.append(b0[p].toInt() and 0xFF).append(',')
                sb.append(b1[p].toInt() and 0xFF).append(',')
                sb.append(b2[p].toInt() and 0xFF).append(',')
                sb.append(b3[p].toInt() and 0xFF).append(',')
                sb.append(String.format(Locale.US, "%02X", b4[p].toInt() and 0xFF)).append(',')
                sb.append(String.format(Locale.US, "%02X", b5[p].toInt() and 0xFF)).append(',')
                sb.append(String.format(Locale.US, "%02X", b6[p].toInt() and 0xFF)).append(',')
                sb.append(gap[p].toInt()).append(',')
                val f = flags[p].toInt()
                sb.append(if (f and 1 != 0) 1 else 0).append(',')
                sb.append(if (f and 2 != 0) 1 else 0).append(',')
                sb.append(if (f and 4 != 0) 1 else 0).append(',')
                sb.append(rssiArr[p].toInt()).append('\n')
            }
            return sb.toString()
        }
    }

    private enum class FeatureMode { MOMENTARY, TOGGLE }

    private data class PayloadTarget(
        val id: String,
        val payloadIndex: Int,
        val bit: Int,
        val label: String
    )

    private data class ControllerInput(
        val id: Int,
        val label: String
    )

    private data class ScreenButtonSpec(
        val slot: Int,
        val label: String,
        val targetId: String,
        val mode: FeatureMode
    )

    /** 1つの画面ボタンタイルを構成するView群。状態更新で各パーツを個別に書き換える。 */
    private class FeatureTileViews(
        val container: View,
        val action: View,
        val nameText: TextView,
        val targetText: TextView,
        val modeChip: TextView,
        val stateChip: TextView
    )

    private data class ControlConfig(
        val controllerTargets: Map<Int, String>,
        val screenButtons: List<ScreenButtonSpec>
    )

    private data class PadSnapshot(
        val lx: Float,
        val ly: Float,
        val rx: Float,
        val ry: Float,
        val controllerStates: Map<Int, Boolean>,
        val screenStates: BooleanArray,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PadSnapshot) return false
            if (lx != other.lx || ly != other.ly || rx != other.rx || ry != other.ry) return false
            if (controllerStates != other.controllerStates) return false
            if (!screenStates.contentEquals(other.screenStates)) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = lx.hashCode()
            result = 31 * result + ly.hashCode()
            result = 31 * result + rx.hashCode()
            result = 31 * result + ry.hashCode()
            result = 31 * result + controllerStates.hashCode()
            result = 31 * result + screenStates.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    private class PadState(maxScreenButtons: Int) {
        var lx = 0f
        var ly = 0f
        var rx = 0f
        var ry = 0f

        private val controllerStates = mutableMapOf<Int, Boolean>()
        // D-pad / L2 / R2 can arrive both as key events and as analog axes from
        // the same controller. Keep the sources apart and OR them, otherwise a
        // stick movement clears a held key (and vice versa).
        private val axisStates = mutableMapOf<Int, Boolean>()
        private val directTargets = mutableMapOf<String, PayloadTarget>()
        private val screenStates = BooleanArray(maxScreenButtons + 1)

        fun setControllerInput(inputId: Int, value: Boolean) {
            controllerStates[inputId] = value
        }

        fun setAxisInput(inputId: Int, value: Boolean) {
            axisStates[inputId] = value
        }

        private fun isPressed(inputId: Int): Boolean =
            controllerStates[inputId] == true || axisStates[inputId] == true

        /** Physical controller lost (disconnect / focus loss): release sticks and buttons. */
        fun clearPhysical() {
            lx = 0f; ly = 0f; rx = 0f; ry = 0f
            controllerStates.clear()
            axisStates.clear()
        }

        fun setScreenButton(slot: Int, value: Boolean) {
            if (slot in screenStates.indices) screenStates[slot] = value
        }

        fun toggleScreenButton(slot: Int) {
            if (slot in screenStates.indices) screenStates[slot] = !screenStates[slot]
        }

        fun clearScreenButtons() {
            for (i in screenStates.indices) screenStates[i] = false
        }

        fun clearAll() {
            clearPhysical()
            directTargets.clear()
            clearScreenButtons()
        }

        fun snapshot(config: ControlConfig, targetsById: Map<String, PayloadTarget>): PadSnapshot {
            val buf = ByteArray(7)
            toPayloadOptimized(config, targetsById, buf)
            return PadSnapshot(
                lx = lx,
                ly = ly,
                rx = rx,
                ry = ry,
                controllerStates = (controllerStates.keys + axisStates.keys).associateWith { isPressed(it) },
                screenStates = screenStates.copyOf(),
                payload = buf
            )
        }

        fun toPayloadOptimized(config: ControlConfig, targetsById: Map<String, PayloadTarget>, out: ByteArray) {
            // Fill with neutral first
            out[0] = axisToByte(lx)
            out[1] = axisToByte(ly)
            out[2] = axisToByte(rx)
            out[3] = axisToByte(ry)
            out[4] = 0
            out[5] = 0
            out[6] = 0

            for (target in directTargets.values) setTargetOptimized(out, target)
            for ((inputId, targetId) in config.controllerTargets) {
                if (!isPressed(inputId)) continue
                val target = targetsById[targetId] ?: continue
                setTargetOptimized(out, target)
            }

            for (button in config.screenButtons) {
                if (button.slot !in screenStates.indices) continue
                if (!screenStates[button.slot]) continue
                val target = targetsById[button.targetId] ?: continue
                setTargetOptimized(out, target)
            }
        }

        companion object {
            fun neutralPayload(): ByteArray = byteArrayOf(
                128.toByte(), 128.toByte(), 128.toByte(), 128.toByte(), 0, 0, 0
            )

            private fun axisToByte(value: Float): Byte {
                val deadZone = 0.06f
                val v = if (abs(value) < deadZone) 0f else value.coerceIn(-1f, 1f)
                val raw = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
                val packed = ((raw + 32768) ushr 8) and 0xFF
                return packed.toByte()
            }

            private fun setTargetOptimized(payload: ByteArray, target: PayloadTarget) {
                if (target.payloadIndex !in payload.indices || target.bit !in 0..7) return
                payload[target.payloadIndex] = (payload[target.payloadIndex].toInt() or (1 shl target.bit)).toByte()
            }
        }
    }
}
