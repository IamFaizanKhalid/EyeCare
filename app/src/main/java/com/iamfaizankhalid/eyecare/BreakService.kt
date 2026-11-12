package com.iamfaizankhalid.eyecare

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Foreground service that owns the 20-20-20 cycle.
 *
 * It tracks time only while the device is interactive and unlocked, then draws a full-screen
 * overlay above whatever app is open for the 20 second break. The overlay is used instead of a
 * background activity launch, which Android blocks for apps that are not in the foreground.
 */
class BreakService : Service() {

    companion object {
        const val ACTION_START = "com.iamfaizankhalid.eyecare.action.START"
        const val ACTION_STOP = "com.iamfaizankhalid.eyecare.action.STOP"
        const val ACTION_RESUME = "com.iamfaizankhalid.eyecare.action.RESUME"
        const val ACTION_PREVIEW = "com.iamfaizankhalid.eyecare.action.PREVIEW"

        const val SESSION_MS = 20L * 60L * 1000L
        const val BREAK_MS = 20L * 1000L

        /** A lock lasting at least this long starts a fresh 20 minute cycle. */
        private const val IDLE_RESET_MS = 5L * 60L * 1000L

        /** Heads-up reminders shown while the timer is running. */
        private const val WARNING_ONE_MINUTE_MS = 60L * 1000L
        private const val WARNING_FIFTEEN_SECONDS_MS = 15L * 1000L

        private const val PREFS = "eyecare"
        private const val KEY_ENABLED = "protection_enabled"
        private const val KEY_USAGE = "usage_ms"
        private const val KEY_ALLOW_SKIP = "allow_skip"

        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            ServiceState.enabled = enabled
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setAllowSkip(context: Context, allow: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ALLOW_SKIP, allow)
                .apply()
        }

        fun isSkipAllowed(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALLOW_SKIP, false)
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var notificationManager: NotificationManager

    private var enabled = false
    private var inForeground = false

    private var overlayView: View? = null
    private var overlayRing: CountdownRingView? = null
    private var breakShowing = false
    private var breakIsPreview = false
    private var breakDeadline = 0L

    private var warningView: View? = null
    private var warningHideToken = 0
    private var warnedOneMinute = false
    private var warnedFifteenSeconds = false

    private var running = false
    private var accumulatedMs = 0L
    private var segmentStart = 0L
    private var screenOffAt = 0L
    private var lastPersistAt = 0L
    private var nextOverlayAttemptAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!enabled) return

            val now = SystemClock.elapsedRealtime()
            if (!breakShowing && running) {
                val remaining = SESSION_MS - accumulated()
                if (remaining <= 0L) {
                    if (now >= nextOverlayAttemptAt) {
                        nextOverlayAttemptAt = now + 10_000L
                        showBreak(preview = false)
                    }
                } else if (remaining <= WARNING_FIFTEEN_SECONDS_MS && !warnedFifteenSeconds) {
                    warnedFifteenSeconds = true
                    warnedOneMinute = true
                    showWarning("Break in 15 seconds", 5_000L)
                } else if (remaining <= WARNING_ONE_MINUTE_MS && !warnedOneMinute) {
                    warnedOneMinute = true
                    val seconds = (remaining / 1000L).toInt()
                    showWarning(
                        if (seconds >= 50) "Break in 1 minute" else "Break in $seconds seconds",
                        6_000L,
                    )
                }
            }

            publish()

            if (running && now - lastPersistAt >= 15_000L) {
                lastPersistAt = now
                saveUsage()
            }

            handler.postDelayed(this, 500L)
        }
    }

    private val breakTicker = object : Runnable {
        override fun run() {
            if (!breakShowing) return
            val remaining = (breakDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            overlayRing?.setRemaining(remaining, BREAK_MS)
            ServiceState.breakRemainingMs = remaining
            if (remaining <= 0L) {
                finishBreak()
            } else {
                handler.postDelayed(this, 50L)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_USER_PRESENT -> handleUnlocked()
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    handler.removeCallbacks(rebuildBreakOverlay)
                    handler.postDelayed(rebuildBreakOverlay, 120L)
                }
            }
        }
    }

    private val rebuildBreakOverlay = Runnable {
        if (breakShowing && overlayView != null) rebuildOverlayForConfiguration()
    }

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        notificationManager = getSystemService(NotificationManager::class.java)

        // Restore recent progress if the process was restarted.
        accumulatedMs = prefs.getLong(KEY_USAGE, 0L).coerceIn(0L, SESSION_MS)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        ServiceState.running = true
        ServiceState.enabled = enabled

        createChannel()
        registerScreenReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                enabled = false
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                ServiceState.enabled = false
                pauseUsage()
                accumulatedMs = 0L
                warnedOneMinute = false
                warnedFifteenSeconds = false
                dismissWarning()
                saveUsage()
                dismissBreak()
                breakShowing = false
                breakIsPreview = false
                handler.removeCallbacks(ticker)
                handler.removeCallbacks(breakTicker)
                publish()
                ServiceState.breakActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PREVIEW -> {
                ensureForeground()
                if (!showBreak(preview = true) && !enabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    inForeground = false
                    stopSelf()
                }
                return if (enabled) START_STICKY else START_NOT_STICKY
            }

            ACTION_START, ACTION_RESUME -> {
                enabled = true
                prefs.edit().putBoolean(KEY_ENABLED, true).apply()
                ServiceState.enabled = true
                ensureForeground()
                beginProtection()
                return START_STICKY
            }

            else -> {
                // Restarted by the system with a null intent.
                enabled = prefs.getBoolean(KEY_ENABLED, false)
                ServiceState.enabled = enabled
                if (!enabled) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureForeground()
                beginProtection()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dismissBreak()
        dismissWarning()
        breakShowing = false
        breakIsPreview = false
        pauseUsage()
        runCatching { unregisterReceiver(screenReceiver) }
        publish()
        ServiceState.running = false
        ServiceState.breakActive = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Timing
    // ---------------------------------------------------------------------------------------------

    private fun beginProtection() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)

        if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked) {
            if (screenOffAt > 0L &&
                SystemClock.elapsedRealtime() - screenOffAt >= IDLE_RESET_MS
            ) {
                resetUsage()
            }
            screenOffAt = 0L
            startUsage()
        }

        nextOverlayAttemptAt = 0L
        publish()
        updateNotification()
    }

    private fun startUsage() {
        if (running) return
        segmentStart = SystemClock.elapsedRealtime()
        running = true
        lastPersistAt = segmentStart
    }

    private fun pauseUsage() {
        if (!running) return
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
        running = false
        saveUsage()
    }

    private fun resetUsage() {
        running = false
        accumulatedMs = 0L
        warnedOneMinute = false
        warnedFifteenSeconds = false
        dismissWarning()
        saveUsage()
    }

    private fun accumulated(): Long =
        accumulatedMs + if (running) SystemClock.elapsedRealtime() - segmentStart else 0L

    private fun saveUsage() {
        prefs.edit().putLong(KEY_USAGE, accumulated()).apply()
    }

    private fun handleScreenOn() {
        if (!enabled) return
        if (!keyguardManager.isKeyguardLocked) handleUnlocked()
    }

    private fun handleUnlocked() {
        if (!enabled) return

        val now = SystemClock.elapsedRealtime()
        val idleFor = if (screenOffAt > 0L) now - screenOffAt else 0L
        if (idleFor >= IDLE_RESET_MS) resetUsage()
        screenOffAt = 0L

        if (!breakShowing) {
            startUsage()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        publish()
    }

    private fun handleScreenOff() {
        screenOffAt = SystemClock.elapsedRealtime()
        pauseUsage()
        dismissWarning()
        if (breakShowing) finishBreak()
        publish()
    }

    // ---------------------------------------------------------------------------------------------
    // Break overlay
    // ---------------------------------------------------------------------------------------------

    private fun showBreak(preview: Boolean): Boolean {
        if (overlayView != null) return false
        if (!Settings.canDrawOverlays(this)) return false

        dismissWarning()
        if (!preview) pauseUsage()

        breakShowing = true
        breakIsPreview = preview
        breakDeadline = SystemClock.elapsedRealtime() + BREAK_MS
        ServiceState.breakRemainingMs = BREAK_MS

        val view = buildOverlay()
        overlayView = view

        return try {
            windowManager.addView(view, overlayParams())
            handler.removeCallbacks(breakTicker)
            handler.post(breakTicker)
            publish()
            updateNotification()
            true
        } catch (t: Throwable) {
            overlayView = null
            overlayRing = null
            breakShowing = false
            breakIsPreview = false
            false
        }
    }

    private fun finishBreak() {
        handler.removeCallbacks(breakTicker)
        dismissBreak()
        dismissWarning()

        val wasPreview = breakIsPreview
        breakShowing = false
        breakIsPreview = false
        ServiceState.breakActive = false
        ServiceState.breakRemainingMs = 0L

        if (!wasPreview || enabled) {
            resetUsage()
            if (enabled && powerManager.isInteractive && !keyguardManager.isKeyguardLocked) {
                startUsage()
            }
        }

        publish()
        updateNotification()

        if (wasPreview && !enabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
        }
    }

    private fun dismissBreak() {
        val view = overlayView ?: return
        overlayView = null
        overlayRing = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun publish() {
        ServiceState.running = true
        ServiceState.enabled = enabled
        ServiceState.breakActive = breakShowing
        ServiceState.remainingMs = (SESSION_MS - accumulated()).coerceAtLeast(0L)
    }

    private fun overlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "EyeCare break"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun buildOverlay(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Palette.bg)
            isClickable = true
            setOnClickListener { /* Swallow touches while the break is shown. */ }
        }

        val landscape =
            resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val animatedContent =
            if (landscape) buildLandscapeContent(root) else buildPortraitContent(root)

        root.alpha = 0f
        animatedContent.translationY = dp(18).toFloat()
        root.post {
            root.animate().alpha(1f).setDuration(400L).start()
            animatedContent.animate().translationY(0f).setDuration(520L).start()
        }

        @Suppress("DEPRECATION")
        root.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        return root
    }

    private fun buildPortraitContent(root: FrameLayout): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        addHeading(content, compact = false)

        val ring = CountdownRingView(this).apply {
            setRemaining(BREAK_MS, BREAK_MS)
        }
        overlayRing = ring
        content.addView(
            ring,
            LinearLayout.LayoutParams(dp(226), dp(226)).apply { topMargin = dp(42) },
        )

        addHint(content, compact = false)
        if (isSkipAllowed(this)) addSkipButton(content, compact = false)

        return content
    }

    private fun buildLandscapeContent(root: FrameLayout): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(20), dp(48), dp(20))
        }
        root.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        addHeading(text, compact = true)
        addHint(text, compact = true)
        if (isSkipAllowed(this)) addSkipButton(text, compact = true)

        val ringSize = min(
            dp(210),
            resources.displayMetrics.heightPixels - dp(120),
        ).coerceAtLeast(dp(140))
        val ring = CountdownRingView(this).apply {
            setRemaining(BREAK_MS, BREAK_MS)
        }
        overlayRing = ring
        row.addView(
            ring,
            LinearLayout.LayoutParams(ringSize, ringSize).apply { leftMargin = dp(36) },
        )

        return row
    }

    private fun addHeading(column: LinearLayout, compact: Boolean) {
        val textAlignment = if (compact) Gravity.START else Gravity.CENTER

        column.addView(
            TextView(this).apply {
                text = "20  ·  20  ·  20"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Palette.accent)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                letterSpacing = 0.22f
                gravity = textAlignment
            },
        )

        column.addView(
            TextView(this).apply {
                text = "Take a break"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 34f else 38f)
                setTextColor(Palette.textPrimary)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                gravity = textAlignment
                letterSpacing = 0.01f
            },
            linearParams(top = if (compact) 10 else 18),
        )

        column.addView(
            TextView(this).apply {
                text = "Look 20 feet away.\nGive your eyes 20 seconds to reset."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 15f)
                setTextColor(Palette.textSecondary)
                gravity = textAlignment
                setLineSpacing(dp(if (compact) 4 else 5).toFloat(), 1f)
            },
            linearParams(top = if (compact) 10 else 12),
        )
    }

    private fun addHint(column: LinearLayout, compact: Boolean) {
        column.addView(
            TextView(this).apply {
                text = "Eyes off the screen. This will clear on its own."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Palette.textTertiary)
                gravity = if (compact) Gravity.START else Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            linearParams(top = if (compact) 18 else 24),
        )
    }

    private fun addSkipButton(column: LinearLayout, compact: Boolean) {
        val skip = TextView(this).apply {
            text = "Skip break"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Palette.textSecondary)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rippled(0x00000000, 12)
            isClickable = true
            isFocusable = true
            contentDescription = "End this break early"
            setOnClickListener { finishBreak() }
        }
        column.addView(
            skip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (compact) dp(16) else dp(22)
                gravity = if (compact) Gravity.START else Gravity.CENTER_HORIZONTAL
            },
        )
    }

    private fun linearParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    private fun rebuildOverlayForConfiguration() {
        if (!breakShowing || overlayView == null) return
        val remaining = (breakDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

        dismissBreak()
        val view = buildOverlay()
        overlayView = view
        try {
            windowManager.addView(view, overlayParams())
            overlayRing?.setRemaining(remaining, BREAK_MS)
        } catch (t: Throwable) {
            overlayView = null
            overlayRing = null
            breakShowing = false
            breakIsPreview = false
            if (!enabled) stopSelf()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pre-break heads-up
    // ---------------------------------------------------------------------------------------------

    private fun showWarning(message: String, durationMs: Long) {
        if (!enabled || breakShowing) return
        if (!Settings.canDrawOverlays(this)) return

        dismissWarning()
        val view = buildWarning(message)
        warningView = view
        try {
            windowManager.addView(view, warningParams())
        } catch (t: Throwable) {
            warningView = null
            return
        }

        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180L).start()

        val token = ++warningHideToken
        handler.postDelayed({
            if (token == warningHideToken && warningView === view) {
                dismissWarning(animate = true)
            }
        }, durationMs)
    }

    private fun dismissWarning(animate: Boolean = false) {
        warningHideToken++
        val view = warningView ?: return
        warningView = null

        if (animate) {
            view.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction { runCatching { windowManager.removeViewImmediate(view) } }
                .start()
        } else {
            runCatching { windowManager.removeViewImmediate(view) }
        }
    }

    private fun buildWarning(message: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(18), dp(11))
            background = roundedBg(Palette.card, 24, Palette.stroke)
            elevation = dp(10).toFloat()
        }

        card.addView(
            View(this).apply { background = ovalBg(Palette.accent) },
            LinearLayout.LayoutParams(dp(8), dp(8)),
        )

        card.addView(
            TextView(this).apply {
                text = message
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Palette.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.01f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(10) },
        )

        return card
    }

    private fun warningParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(10)
            title = "EyeCare reminder"
        }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    // ---------------------------------------------------------------------------------------------
    // Notification and system plumbing
    // ---------------------------------------------------------------------------------------------

    private fun ensureForeground() {
        if (inForeground) return
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        inForeground = true
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Break reminders",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps EyeCare running and shows when the next break is due."
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BreakService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when {
            !enabled -> "Previewing the break screen"
            breakShowing -> "Time to look 20 feet away"
            else -> "20-20-20 reminders are active"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(if (enabled) "EyeCare is on" else "EyeCare")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stat_eye, if (enabled) "Stop" else "Close", stop)
            .build()
    }

    private fun updateNotification() {
        if (!inForeground) return
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }
}
