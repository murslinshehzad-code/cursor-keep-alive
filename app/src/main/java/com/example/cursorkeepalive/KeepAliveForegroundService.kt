// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 murslinshehzad-code

package com.example.cursorkeepalive

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Keeps your Chrome Remote Desktop web session alive.
 *
 * It hosts the CRD web page in an almost transparent overlay window (so Android treats it as
 * visible and the connection stays up), and sends a small real touch swipe into the remote
 * screen at random intervals. "Dark mode" covers the screen with black at the lowest
 * brightness while keeping the display on, because swipes only work while the screen is on.
 */
class KeepAliveForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val swipeHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var web: WebView? = null
    private var webParams: WindowManager.LayoutParams? = null
    private var darkView: View? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionUrl = ""

    // Session health
    private var disconnectedSince = 0L
    private var reloadFailures = 0
    private var needsSignIn = false
    private var attentionShown = false
    private var noCanvasCount = 0

    // Swipe in flight (so swipes never overlap and can be cancelled cleanly)
    private var swipeActive = false
    private var swipeBusyUntil = 0L
    private var swipeLastX = 0f
    private var swipeLastY = 0f
    private var swipeDownTime = 0L

    // What the notification currently says
    private var warning: String? = null
    private var lastNotifText = ""

    private val tick = Runnable { onTick() }
    private val watchdog = object : Runnable {
        override fun run() {
            checkSession()
            handler.postDelayed(this, 5000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Keep-alive status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Needs attention", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action != null && !running) {
            // A stale notification button after the loop already ended.
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_DARK -> showDark()
            ACTION_LIGHT -> hideDark()
            // Normal start, or Android restarting a killed service (null intent).
            else -> return startLoop()
        }
        return START_STICKY
    }

    // ---- Start / stop ------------------------------------------------------------------------

    private fun startLoop(): Int {
        try {
            startForegroundCompat() // must be called promptly after startForegroundService()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            return failStart("Android would not let the service start (${e.javaClass.simpleName}). Open the app and press Start.")
        }
        cancelAttention()
        val url = Prefs.getString(this, Prefs.KEY_SESSION_URL)
        if (url.isBlank()) return failStart("No laptop saved yet. Open the app and tap Connect to laptop.")
        if (!Settings.canDrawOverlays(this)) return failStart("\"Display over other apps\" is off. Turn it on in the app.")

        sessionUrl = url
        paused = false
        running = true
        dark = false
        sessionState = "starting"
        lastResult = "Connecting to your laptop..."
        lastTickAt = 0L
        startedAt = SystemClock.elapsedRealtime()
        swipeCount = 0
        disconnectedSince = SystemClock.elapsedRealtime()
        reloadFailures = 0
        needsSignIn = false
        attentionShown = false
        noCanvasCount = 0
        warning = null
        try {
            acquireLocks()
            createOverlay(url)
        } catch (e: Exception) {
            Log.e(TAG, "could not open the session overlay", e)
            return failStart("Could not open the laptop session (${e.javaClass.simpleName}).")
        }
        handler.removeCallbacks(tick)
        scheduleNext(initialMs = 10_000L) // give the session time to connect
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 5000L)
        refreshNotification()
        Log.i(TAG, "Loop started")
        return START_STICKY
    }

    private fun failStart(message: String): Int {
        lastResult = message
        running = false
        sessionState = "stopped"
        postAttention("Keep-alive could not start", message)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            handler.removeCallbacks(tick)
            cancelSwipe()
            nextTickAt = 0L
        } else {
            scheduleNext(initialMs = 2000L)
        }
        refreshNotification()
        Log.i(TAG, if (value) "Paused" else "Resumed")
    }

    // ---- Scheduling and swipes ---------------------------------------------------------------

    private fun scheduleNext(initialMs: Long? = null) {
        handler.removeCallbacks(tick)
        if (paused) {
            nextTickAt = 0L
            return
        }
        val delayMs = initialMs ?: run {
            val cfg = Prefs.load(this)
            // Log-uniform between min and max: short gaps are common, long ones still happen.
            // Never longer than the max (hard cap 30 s).
            val lo = ln(cfg.minIntervalSec.toDouble())
            val hi = ln(cfg.maxIntervalSec.toDouble())
            val sec = exp(lo + Random.nextDouble() * (hi - lo))
            (sec * 1000).toLong().coerceIn(cfg.minIntervalSec * 1000L, cfg.maxIntervalSec * 1000L)
        }
        nextTickAt = SystemClock.elapsedRealtime() + delayMs
        handler.postDelayed(tick, delayMs)
    }

    private fun onTick() {
        if (!paused) {
            updateKeepScreenOn()
            val now = SystemClock.elapsedRealtime()
            val screenOn = getSystemService(PowerManager::class.java).isInteractive
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            warning = null
            when {
                !screenOn -> {
                    warning = "Screen is off, swipes paused"
                    lastResult = "Screen is off, so swipes can't work. Use Go dark instead of the power button."
                }
                locked -> {
                    warning = "Phone is locked, swipes paused"
                    lastResult = "The phone is locked, so swipes can't reach the session. Unlock it or use Go dark."
                }
                needsSignIn -> {
                    // lastResult already explains this
                }
                sessionState != "connected" -> {
                    lastResult = "Waiting for the laptop session (state: $sessionState)"
                }
                now < swipeBusyUntil -> {
                    // Previous swipe is still running; skip this tick rather than overlap.
                }
                else -> performSwipe()
            }
            maybeRefreshNotification()
        }
        scheduleNext()
    }

    /** Finds the remote-screen canvas, then swipes inside it. */
    private fun performSwipe() {
        val v = web ?: return
        val cfg = Prefs.load(this)
        v.evaluateJavascript(RECT_JS) { raw -> doSwipe(cfg, raw) }
    }

    private fun doSwipe(cfg: Prefs.Config, raw: String?) {
        val v = web ?: return
        if (paused || v.width == 0 || v.height == 0) return

        var left = 0f
        var top = 0f
        var rw = 0f
        var rh = 0f
        var found = false
        try {
            val json = JSONObject(JSONTokener(raw).nextValue() as String)
            val innerW = json.getDouble("w")
            val r = json.optJSONArray("r")
            if (r != null && innerW > 0) {
                val scale = (v.width / innerW).toFloat()
                val l = max(0f, (r.getDouble(0) * scale).toFloat())
                val t = max(0f, (r.getDouble(1) * scale).toFloat())
                val rr = min(v.width.toFloat(), ((r.getDouble(0) + r.getDouble(2)) * scale).toFloat())
                val bb = min(v.height.toFloat(), ((r.getDouble(1) + r.getDouble(3)) * scale).toFloat())
                if (rr - l > 40f && bb - t > 40f) {
                    left = l; top = t; rw = rr - l; rh = bb - t
                    found = true
                }
            }
        } catch (_: Exception) {
            // treated as "no canvas" below
        }

        if (!found) {
            // The page is "connected" but shows no remote screen. If that persists, reload.
            noCanvasCount++
            lastResult = "Laptop screen not visible yet"
            if (noCanvasCount >= 3) {
                Log.w(TAG, "No remote screen found $noCanvasCount times; reloading the session")
                noCanvasCount = 0
                reloadSession("No remote screen found")
            }
            return
        }
        noCanvasCount = 0

        val cx = left + rw / 2f
        val cy = top + rh / 2f
        // Random start near the middle, random direction, and a path that stays inside the target.
        val sx = cx + (Random.nextFloat() - 0.5f) * rw * 0.3f
        val sy = cy + (Random.nextFloat() - 0.5f) * rh * 0.3f
        val cap = max(10f, 0.3f * min(rw, rh))
        val hi = min(cfg.maxLenPx.toFloat(), cap)
        val lo = min(cfg.minLenPx.toFloat(), hi)
        val len = lo + Random.nextFloat() * (hi - lo)
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val dx = (cos(angle) * len).toFloat()
        val dy = (sin(angle) * len).toFloat()
        val dur = Random.nextInt(cfg.minDurMs, cfg.maxDurMs + 1)

        dispatchSwipe(v, sx, sy, dx, dy, dur)
        swipeCount++
        lastResult = "Swiped ${len.toInt()} px in $dur ms"
        lastTickAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Swipe len=${len.toInt()} dur=$dur at (${sx.toInt()}, ${sy.toInt()})")
    }

    /** Real Android touch events, out and back, sent straight into the WebView. */
    private fun dispatchSwipe(view: WebView, sx: Float, sy: Float, dx: Float, dy: Float, durMs: Int) {
        val stepMs = 16L
        val half = max(4, durMs / 2 / stepMs.toInt())
        swipeDownTime = SystemClock.uptimeMillis()
        swipeBusyUntil = SystemClock.elapsedRealtime() + (2 * half + 3) * stepMs

        sendTouch(view, MotionEvent.ACTION_DOWN, sx, sy, 0)
        for (i in 1..(2 * half)) {
            val f = if (i <= half) i / half.toFloat() else (2 * half - i) / half.toFloat()
            val x = sx + dx * f
            val y = sy + dy * f
            swipeHandler.postDelayed({ sendTouch(view, MotionEvent.ACTION_MOVE, x, y, i * stepMs) }, i * stepMs)
        }
        swipeHandler.postDelayed(
            { sendTouch(view, MotionEvent.ACTION_UP, sx, sy, (2 * half + 1) * stepMs) },
            (2 * half + 1) * stepMs
        )
    }

    private fun sendTouch(view: WebView, action: Int, x: Float, y: Float, t: Long) {
        if (web !== view) return // the WebView was replaced; drop the rest of this swipe
        val e = MotionEvent.obtain(swipeDownTime, swipeDownTime + t, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(e)
        e.recycle()
        swipeLastX = x
        swipeLastY = y
        swipeActive = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
    }

    /** Drops any queued swipe events and, if a swipe was mid-way, ends it with ACTION_CANCEL. */
    private fun cancelSwipe() {
        swipeHandler.removeCallbacksAndMessages(null)
        val v = web
        if (swipeActive && v != null) {
            sendTouch(v, MotionEvent.ACTION_CANCEL, swipeLastX, swipeLastY, 0)
        }
        swipeActive = false
        swipeBusyUntil = 0L
    }

    // ---- Session overlay ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createOverlay(url: String) {
        removeOverlay()
        val manager = getSystemService(WindowManager::class.java)
        wm = manager
        val view = WebView(applicationContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String) {
                Log.i(TAG, "Session page loaded: $u")
                onPageLoaded(u)
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page's renderer died (e.g. low memory). Without this Android kills the app.
                Log.w(TAG, "Renderer gone (crashed=${detail.didCrash()}); rebuilding the session view")
                handler.post { rebuildOverlay("The page crashed") }
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val m = msg.message()
                if (m.startsWith("state =")) {
                    // e.g. "state = connected; transport = direct"
                    val s = m.removePrefix("state =").substringBefore(';').trim()
                    if (s != sessionState) {
                        Log.i(TAG, "Session state: $s")
                        sessionState = s
                        maybeRefreshNotification()
                    }
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }

        // 540 x 900 px, almost transparent, never touchable or focusable.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (Prefs.load(this).keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
        val params = WindowManager.LayoutParams(
            540, 900,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.004f
        }
        manager.addView(view, params)
        web = view
        webParams = params
        view.loadUrl(url)
    }

    private fun removeOverlay() {
        cancelSwipe()
        web?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        web = null
        webParams = null
    }

    /** Throws away the session view and opens a fresh one (after a crash or a long dead spell). */
    private fun rebuildOverlay(reason: String) {
        if (!running) return
        Log.w(TAG, "Rebuilding session view: $reason")
        sessionState = "reloading"
        lastResult = "Reconnecting to your laptop... ($reason)"
        disconnectedSince = SystemClock.elapsedRealtime()
        try {
            createOverlay(sessionUrl)
        } catch (e: Exception) {
            Log.e(TAG, "rebuild failed", e)
            lastResult = "Could not reopen the laptop session (${e.javaClass.simpleName})"
            postAttention("Keep-alive stopped", lastResult)
            stopSelf()
        }
    }

    /** Applies the "keep the screen on" setting to the running overlay. */
    private fun updateKeepScreenOn() {
        val p = webParams ?: return
        val v = web ?: return
        val want = Prefs.load(this).keepScreenOn
        val has = (p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (want != has) {
            p.flags = if (want) {
                p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
            try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    // ---- Session health ----------------------------------------------------------------------

    /** Spots a lost Google sign-in (the page ends up on the login or device-list page). */
    private fun onPageLoaded(url: String) {
        val signedOut = url.startsWith("https://accounts.google.com") ||
            (url.startsWith("https://remotedesktop.google.com/access") && !url.contains("/session/"))
        if (signedOut) {
            needsSignIn = true
            sessionState = "sign-in needed"
            lastResult = "Sign-in needed. Open the app and tap Connect to laptop."
            postAttention("Sign-in needed", "Cursor Keep-Alive lost its Google session. Open the app and tap \"Connect to laptop\".")
            maybeRefreshNotification()
        } else if (url.contains("/access/session/")) {
            needsSignIn = false
        }
    }

    private fun reloadSession(reason: String) {
        Log.w(TAG, "Reloading the session: $reason")
        lastResult = "Reconnecting to your laptop..."
        sessionState = "reloading"
        disconnectedSince = SystemClock.elapsedRealtime()
        web?.loadUrl(sessionUrl)
    }

    /** Reloads the session if it has been disconnected for a while; backs off if it keeps failing. */
    private fun checkSession() {
        if (needsSignIn) return // reloading would not help; the user must sign in again
        val now = SystemClock.elapsedRealtime()
        if (sessionState == "connected") {
            disconnectedSince = 0L
            reloadFailures = 0
            attentionShown = false
            return
        }
        if (disconnectedSince == 0L) disconnectedSince = now
        val wait = min(30_000L * (1 + reloadFailures), 300_000L)
        if (now - disconnectedSince > wait) {
            reloadFailures++
            if (reloadFailures >= 3 && !attentionShown) {
                attentionShown = true
                postAttention(
                    "Laptop not reachable",
                    "Could not reconnect after several tries. Is the laptop on and online?"
                )
            }
            // Every third failed reload, throw the whole page away and start clean.
            if (reloadFailures % 3 == 0) rebuildOverlay("Still not connected") else reloadSession("Not connected for ${wait / 1000} s")
        }
        maybeRefreshNotification()
    }

    // ---- Dark mode ---------------------------------------------------------------------------

    /**
     * A full-screen black window at the lowest brightness that keeps the display on (swipes need
     * it on), swallows touches, and closes on a double tap or a long press.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showDark() {
        if (darkView != null) return
        if (!Settings.canDrawOverlays(this)) {
            lastResult = "\"Display over other apps\" was turned off, so Go dark is unavailable"
            return
        }
        val manager = wm ?: getSystemService(WindowManager::class.java).also { wm = it }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                hideDark()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                hideDark()
            }
        })
        val v = View(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = 0f
        }
        try {
            manager.addView(v, params)
        } catch (e: Exception) {
            Log.e(TAG, "dark mode failed", e)
            lastResult = "Could not go dark (${e.javaClass.simpleName})"
            return
        }
        darkView = v
        dark = true
        refreshNotification()
        Toast.makeText(this, "Double-tap or long-press the screen to exit", Toast.LENGTH_LONG).show()
        Log.i(TAG, "Dark mode on")
    }

    private fun hideDark() {
        darkView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        darkView = null
        dark = false
        if (running) refreshNotification()
        Log.i(TAG, "Dark mode off")
    }

    // ---- Locks and notifications -------------------------------------------------------------

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        if (cpuLock?.isHeld != true) {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CursorKeepAlive:cpu").also { it.acquire() }
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock?.isHeld != true) {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "CursorKeepAlive:wifi").also { it.acquire() }
        }
    }

    private fun releaseLocks() {
        cpuLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        wifiLock = null
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** One short line describing what the loop is doing right now. */
    private fun statusLine(): String {
        if (paused) return "Paused"
        warning?.let { return it }
        if (needsSignIn) return "Sign-in needed. Open the app."
        val session = if (sessionState == "connected") "Laptop connected" else "Laptop: $sessionState"
        val extra = if (dark) " · screen dark" else ""
        return "$session · $swipeCount swipes$extra"
    }

    private fun maybeRefreshNotification() {
        if (statusLine() != lastNotifText) refreshNotification()
    }

    private fun refreshNotification() {
        // After the loop has stopped, a late message from the closing page must not bring the
        // notification back.
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        fun action(title: String, act: String, icon: Int): Notification.Action {
            val pi = PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, KeepAliveForegroundService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
        }
        val toggle = if (paused) {
            action("Resume", ACTION_RESUME, android.R.drawable.ic_media_play)
        } else {
            action("Pause", ACTION_PAUSE, android.R.drawable.ic_media_pause)
        }
        val screenAction = if (dark) {
            action("Screen on", ACTION_LIGHT, android.R.drawable.ic_menu_view)
        } else {
            action("Go dark", ACTION_DARK, android.R.drawable.ic_menu_view)
        }
        val text = statusLine()
        lastNotifText = text
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Cursor Keep-Alive")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(toggle)
            .addAction(screenAction)
            .addAction(action("Stop", ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel))
            .build()
    }

    /** A separate, higher-priority notification for things that need the user's attention. */
    private fun postAttention(title: String, text: String) {
        val n = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ATTENTION_ID, n)
    }

    private fun cancelAttention() {
        getSystemService(NotificationManager::class.java).cancel(ATTENTION_ID)
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    /** Called when the user swipes the app away from recent apps. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.load(this).stopOnClose) {
            Log.i(TAG, "App closed from recents; stopping because the setting is on")
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false // first, so nothing below can re-post the notification
        handler.removeCallbacksAndMessages(null)
        hideDark()
        removeOverlay()
        releaseLocks()
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        paused = false
        dark = false
        nextTickAt = 0L
        sessionState = "stopped"
        if (lastResult.startsWith("Swiped") || lastResult.startsWith("Waiting") ||
            lastResult.startsWith("Connecting") || lastResult.startsWith("Reconnecting") ||
            lastResult.startsWith("Screen is off") || lastResult.startsWith("The phone is locked") ||
            lastResult.startsWith("Laptop screen")
        ) lastResult = "Stopped"
        Log.i(TAG, "Loop stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "keepalive"
        private const val ALERT_CHANNEL_ID = "keepalive_alerts"
        const val NOTIF_ID = 1
        private const val ATTENTION_ID = 2
        const val ACTION_PAUSE = "com.example.cursorkeepalive.PAUSE"
        const val ACTION_RESUME = "com.example.cursorkeepalive.RESUME"
        const val ACTION_STOP = "com.example.cursorkeepalive.STOP"
        const val ACTION_DARK = "com.example.cursorkeepalive.DARK"
        const val ACTION_LIGHT = "com.example.cursorkeepalive.LIGHT"

        // Finds the largest canvas (the remote screen) and the page width, in CSS pixels.
        private const val RECT_JS = """
            (function() {
              var cs = document.querySelectorAll('canvas'), best = null, area = 0;
              for (var i = 0; i < cs.length; i++) {
                var b = cs[i].getBoundingClientRect();
                if (b.width * b.height > area) { area = b.width * b.height; best = b; }
              }
              return JSON.stringify({ w: window.innerWidth,
                r: best ? [best.left, best.top, best.width, best.height] : null });
            })();
        """

        // State shared with the UI (same process).
        @Volatile var running = false
        @Volatile var paused = false
        @Volatile var dark = false
        @Volatile var sessionState = "stopped"
        @Volatile var lastResult = "Stopped"
        @Volatile var lastTickAt = 0L
        @Volatile var nextTickAt = 0L
        @Volatile var startedAt = 0L
        @Volatile var swipeCount = 0
    }
}
