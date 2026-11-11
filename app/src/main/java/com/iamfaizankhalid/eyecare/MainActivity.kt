package com.iamfaizankhalid.eyecare

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var timerText: TextView
    private lateinit var timerHint: TextView
    private lateinit var actionButton: TextView
    private lateinit var overlayValue: TextView
    private lateinit var notificationValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var skipSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastEnabled: Boolean? = null

    private val poll = object : Runnable {
        override fun run() {
            refreshState()
            if (polling) handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg
        setContentView(buildUi())
        refreshPermissions()
    }

    override fun onResume() {
        super.onResume()
        polling = true
        lastEnabled = null
        handler.post(poll)
        refreshPermissions()
        if (::skipSwitch.isInitialized) {
            skipSwitch.isChecked = BreakService.isSkipAllowed(this)
        }

        // If protection is enabled but the process was killed, bring the service back.
        if (BreakService.isEnabled(this) && !ServiceState.running) {
            runCatching {
                startForegroundService(
                    Intent(this, BreakService::class.java)
                        .setAction(BreakService.ACTION_RESUME),
                )
            }
        }
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    // ---------------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.bg)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(34), dp(24), dp(36))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            uiText(this, 11f, Palette.accent, Typeface.BOLD).apply {
                text = "EYECARE  ·  20–20–20"
                letterSpacing = 0.18f
            },
        )

        root.addView(
            uiText(this, 38f, Palette.textPrimary, Typeface.NORMAL, "sans-serif-light").apply {
                text = "Give your eyes\na break."
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            matchWrap(top = 14),
        )

        root.addView(
            uiText(this, 15f, Palette.textSecondary).apply {
                text = "Every 20 minutes of screen time, look 20 feet away for 20 seconds."
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            matchWrap(top = 12),
        )

        root.addView(buildStatusCard(), matchWrap(top = 24))

        root.addView(
            uiText(this, 10f, Palette.textTertiary, Typeface.BOLD).apply {
                text = "SETUP"
                letterSpacing = 0.18f
            },
            matchWrap(top = 32),
        )

        val overlayRow = permissionRow(
            "Display over other apps",
            "Shows the break screen above any app",
        ) { openOverlaySettings() }
        overlayValue = overlayRow.second
        root.addView(overlayRow.first, matchWrap(top = 10))

        val notificationRow = permissionRow(
            "Notifications",
            "Keeps the protection service visible",
        ) { openNotificationSettings() }
        notificationValue = notificationRow.second
        root.addView(notificationRow.first, matchWrap(top = 8))

        val batteryRow = permissionRow(
            "Unrestricted background",
            "Stops the system from pausing the timer",
        ) { openBatterySettings() }
        batteryValue = batteryRow.second
        root.addView(batteryRow.first, matchWrap(top = 8))

        root.addView(
            uiText(this, 10f, Palette.textTertiary, Typeface.BOLD).apply {
                text = "BREAK SCREEN"
                letterSpacing = 0.18f
            },
            matchWrap(top = 28),
        )

        root.addView(
            toggleRow(
                "Allow skipping",
                "Show a Skip button on the break screen",
                BreakService.isSkipAllowed(this),
            ) { checked -> BreakService.setAllowSkip(this, checked) },
            matchWrap(top = 10),
        )

        val preview = uiText(this, 14f, Palette.accent, Typeface.BOLD).apply {
            text = "Preview the break screen"
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = rippled(Palette.bg, 14)
            isClickable = true
            isFocusable = true
        }
        preview.setOnClickListener { previewBreak() }
        root.addView(preview, matchWrap(top = 4))

        root.addView(
            uiText(this, 12f, Palette.textTertiary).apply {
                text = "EyeCare runs as a foreground service and restarts after a reboot. " +
                    "On Xiaomi, Oppo, Vivo or Samsung devices, also allow autostart in the " +
                    "system settings so the timer is never interrupted."
                setLineSpacing(dp(4).toFloat(), 1f)
            },
            matchWrap(top = 20),
        )

        return scroll
    }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBg(Palette.card, 22, Palette.stroke)
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply { background = ovalBg(Palette.textTertiary) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))

        statusTitle = uiText(this, 14f, Palette.textSecondary, Typeface.BOLD).apply {
            text = "Protection is off"
            letterSpacing = 0.01f
        }
        statusRow.addView(
            statusTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
        card.addView(statusRow)

        card.addView(
            uiText(this, 10f, Palette.textTertiary, Typeface.BOLD).apply {
                text = "NEXT BREAK"
                letterSpacing = 0.18f
            },
            matchWrap(top = 18),
        )

        timerText = uiText(this, 42f, Palette.textPrimary, Typeface.NORMAL, "sans-serif-light").apply {
            text = formatTime(BreakService.SESSION_MS)
            letterSpacing = 0.02f
            fontFeatureSettings = "tnum"
        }
        card.addView(timerText, matchWrap(top = 2))

        timerHint = uiText(this, 13f, Palette.textTertiary).apply {
            text = "Start protection to begin the 20 minute cycle."
        }
        card.addView(timerHint, matchWrap(top = 6))

        actionButton = uiText(this, 16f, Palette.accentInk, Typeface.BOLD).apply {
            text = "Start protection"
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = rippled(Palette.accent, 16)
            isClickable = true
            isFocusable = true
        }
        actionButton.setOnClickListener { onActionClicked() }
        card.addView(
            actionButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )

        return card
    }

    private fun permissionRow(
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rippled(Palette.card, 16, Palette.stroke)
            isClickable = true
            isFocusable = true
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            uiText(this, 14f, Palette.textPrimary, Typeface.BOLD).apply { text = title },
        )
        texts.addView(
            uiText(this, 12f, Palette.textTertiary).apply {
                text = subtitle
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) },
        )
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val value = uiText(this, 12f, Palette.textSecondary, Typeface.BOLD).apply {
            text = "Needed"
            letterSpacing = 0.04f
        }
        row.addView(
            value,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12) },
        )

        row.setOnClickListener { onClick() }
        return row to value
    }

    private fun toggleRow(
        title: String,
        subtitle: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rippled(Palette.card, 16, Palette.stroke)
            isClickable = true
            isFocusable = true
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            uiText(this, 14f, Palette.textPrimary, Typeface.BOLD).apply { text = title },
        )
        texts.addView(
            uiText(this, 12f, Palette.textTertiary).apply {
                text = subtitle
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) },
        )
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val toggle = Switch(this).apply {
            isChecked = initial
            contentDescription = title
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        skipSwitch = toggle
        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )

        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    private fun refreshState() {
        val enabled = ServiceState.enabled
        val running = ServiceState.running
        val breakActive = ServiceState.breakActive

        statusTitle.text = when {
            breakActive -> "Break in progress"
            enabled -> "Protection is on"
            else -> "Protection is off"
        }
        (statusDot.background as? GradientDrawable)?.setColor(
            if (enabled) Palette.accent else Palette.textTertiary,
        )

        timerText.text = when {
            breakActive -> formatTime(ServiceState.breakRemainingMs)
            enabled && running -> formatTime(ServiceState.remainingMs)
            else -> formatTime(BreakService.SESSION_MS)
        }
        timerText.setTextColor(if (enabled) Palette.textPrimary else Palette.textTertiary)

        timerHint.text = when {
            breakActive -> "Look 20 feet away. The screen will clear itself."
            enabled && !running -> "Starting…"
            enabled -> "The timer runs while your phone is unlocked."
            else -> "Start protection to begin the 20 minute cycle."
        }

        if (lastEnabled != enabled) {
            lastEnabled = enabled
            if (enabled) {
                actionButton.text = "Stop protection"
                actionButton.setTextColor(Palette.textPrimary)
                actionButton.background = rippled(Palette.card, 16, Palette.stroke)
            } else {
                actionButton.text = "Start protection"
                actionButton.setTextColor(Palette.accentInk)
                actionButton.background = rippled(Palette.accent, 16)
            }
        }
    }

    private fun refreshPermissions() {
        setPermissionValue(overlayValue, Settings.canDrawOverlays(this))

        val notificationsOn =
            getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        setPermissionValue(notificationValue, notificationsOn)

        val powerManager = getSystemService(PowerManager::class.java)
        setPermissionValue(
            batteryValue,
            powerManager.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun setPermissionValue(view: TextView, granted: Boolean) {
        view.text = if (granted) "Granted" else "Needed"
        view.setTextColor(if (granted) Palette.accent else Palette.textSecondary)
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private fun onActionClicked() {
        if (BreakService.isEnabled(this) || ServiceState.enabled) {
            stopProtection()
        } else {
            startProtection()
        }
    }

    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow “Display over other apps” to show the break screen.")
            openOverlaySettings()
            return
        }

        maybeRequestNotificationPermission()
        BreakService.setEnabled(this, true)
        runCatching {
            startForegroundService(
                Intent(this, BreakService::class.java).setAction(BreakService.ACTION_START),
            )
        }.onFailure {
            toast("Could not start protection: ${it.message}")
        }
        handler.postDelayed({ refreshState() }, 300L)
    }

    private fun stopProtection() {
        BreakService.setEnabled(this, false)
        ServiceState.enabled = false
        runCatching {
            startService(
                Intent(this, BreakService::class.java).setAction(BreakService.ACTION_STOP),
            )
        }
        refreshState()
    }

    private fun previewBreak() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow “Display over other apps” to preview the break screen.")
            openOverlaySettings()
            return
        }
        maybeRequestNotificationPermission()
        runCatching {
            startForegroundService(
                Intent(this, BreakService::class.java).setAction(BreakService.ACTION_PREVIEW),
            )
        }.onFailure {
            toast("Could not open the preview: ${it.message}")
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        launchSettings(intent, "Open Settings → Apps → EyeCare → Display over other apps.")
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            return
        }
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        launchSettings(intent, "Open Settings → Apps → EyeCare → Notifications.")
    }

    private fun openBatterySettings() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            launchSettings(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                "Open Settings → Battery → Battery optimization.",
            )
            return
        }

        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        if (!launchSettings(request, null)) {
            launchSettings(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                "Open Settings → Battery → Battery optimization → EyeCare → Don't optimize.",
            )
        }
    }

    private fun launchSettings(intent: Intent, fallbackMessage: String?): Boolean =
        runCatching { startActivity(intent) }.isSuccess.also { launched ->
            if (!launched && fallbackMessage != null) toast(fallbackMessage)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ceil(ms / 1000.0).toInt().coerceAtLeast(0)
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
