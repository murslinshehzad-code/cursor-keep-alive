// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 murslinshehzad-code

package com.example.cursorkeepalive

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Control screen: status, start/stop, pause/resume, go dark, setup checklist and settings. */
class MainActivity : Activity() {

    private class Palette(night: Boolean) {
        val bg = Color.parseColor(if (night) "#0E1218" else "#F3F5F9")
        val card = Color.parseColor(if (night) "#1A202B" else "#FFFFFF")
        val divider = Color.parseColor(if (night) "#2A3342" else "#E1E5EC")
        val text = Color.parseColor(if (night) "#EAEFF7" else "#1B2230")
        val muted = Color.parseColor(if (night) "#93A0B5" else "#68758A")
        val accent = Color.parseColor(if (night) "#7B97FF" else "#3558D6")
        val green = Color.parseColor(if (night) "#3FBF76" else "#1F8A4C")
        val amber = Color.parseColor(if (night) "#F0A24A" else "#C77718")
        val red = Color.parseColor(if (night) "#F06A6A" else "#C93B3B")
        val dark = Color.parseColor(if (night) "#3A4560" else "#2B3345")
        val night = night
    }

    private lateinit var p: Palette
    private val handler = Handler(Looper.getMainLooper())
    private var startRequestedAt = 0L

    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var nextText: TextView
    private lateinit var lastText: TextView
    private lateinit var sessionText: TextView
    private lateinit var notifWarning: TextView
    private lateinit var startStopButton: Button
    private lateinit var pauseButton: Button
    private lateinit var darkButton: Button

    private lateinit var laptopLabel: TextView
    private lateinit var laptopButton: Button
    private lateinit var overlayLabel: TextView
    private lateinit var overlayButton: Button
    private lateinit var batteryLabel: TextView
    private lateinit var batteryButton: Button

    private val fields = mutableMapOf<String, EditText>()

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        p = Palette(night)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        window.decorView.systemUiVisibility = if (night) 0 else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Clear a status notification left behind when nothing is actually running.
        if (!KeepAliveForegroundService.running) {
            getSystemService(NotificationManager::class.java).cancel(KeepAliveForegroundService.NOTIF_ID)
        }
        refresh.run()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // ---- Small UI helpers --------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun lp(top: Int = 0, weight: Float = 0f, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { topMargin = dp(top) }

    private fun card(parent: LinearLayout, title: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 16, p.divider)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        parent.addView(c, lp(top = 12))
        if (title != null) {
            c.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(p.muted)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            })
        }
        return c
    }

    private fun filledButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(color, 14)
        stateListAnimator = null
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

    private fun outlineButton(label: String, color: Int, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = if (small) 14f else 15f
        setTextColor(color)
        background = rounded(Color.TRANSPARENT, 12, color)
        stateListAnimator = null
        minHeight = dp(if (small) 40 else 48)
        minimumHeight = dp(if (small) 40 else 48)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun row(parent: LinearLayout, top: Int = 8): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        parent.addView(r, lp(top = top))
        return r
    }

    private fun intField(parent: LinearLayout, label: String, key: String, def: Int) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(p.muted)
        })
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(4))
            setText(Prefs.getInt(this@MainActivity, key, def).toString())
            setTextColor(p.text)
            textSize = 16f
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.let { Prefs.putInt(this@MainActivity, key, it) }
                }
            })
            // When the user leaves the field, show the value the app will really use.
            setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    (v as EditText).setText(Prefs.effective(this@MainActivity, key).toString())
                    refreshFields()
                }
            }
        }
        fields[key] = edit
        col.addView(edit)
        parent.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
    }

    /** Re-reads the effective value of every field that is not being edited. */
    private fun refreshFields() {
        for ((key, edit) in fields) {
            if (!edit.hasFocus()) {
                val v = Prefs.effective(this, key).toString()
                if (edit.text.toString() != v) edit.setText(v)
            }
        }
    }

    private fun check(parent: LinearLayout, label: String, key: String, def: Boolean) {
        parent.addView(CheckBox(this).apply {
            text = label
            textSize = 15f
            setTextColor(p.text)
            isChecked = Prefs.getBool(this@MainActivity, key, def)
            setOnCheckedChangeListener { _, checked -> Prefs.putBool(this@MainActivity, key, checked) }
        }, lp(top = 6))
    }

    private fun applyPreset(minSec: Int, maxSec: Int) {
        Prefs.putInt(this, Prefs.KEY_MIN_INT, minSec)
        Prefs.putInt(this, Prefs.KEY_MAX_INT, maxSec)
        fields.values.forEach { it.clearFocus() }
        refreshFields()
        Toast.makeText(this, "Swipes every $minSec–$maxSec s", Toast.LENGTH_SHORT).show()
    }

    // ---- UI construction ---------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Android 15+ draws apps edge to edge; keep content clear of the camera, bars and keyboard.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        setContentView(scroll)

        // Title
        root.addView(TextView(this).apply {
            text = "Cursor Keep-Alive"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
        })
        root.addView(TextView(this).apply {
            text = "Keeps your laptop awake through Chrome Remote Desktop"
            textSize = 14f
            setTextColor(p.muted)
        })

        // Status card
        val status = card(root)
        stateText = TextView(this).apply { textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
        detailText = TextView(this).apply { textSize = 14f; setTextColor(p.muted) }
        nextText = TextView(this).apply { textSize = 16f; setTextColor(p.text) }
        lastText = TextView(this).apply { textSize = 14f; setTextColor(p.muted) }
        sessionText = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
        status.addView(stateText)
        status.addView(detailText)
        status.addView(nextText, lp(top = 8))
        status.addView(lastText, lp(top = 2))
        status.addView(sessionText, lp(top = 12))

        notifWarning = TextView(this).apply {
            text = "Notifications are off, so the Pause, Go dark and Stop buttons won't show in the notification. Tap here to allow."
            textSize = 13f
            setTextColor(p.amber)
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS_ONLY)
                }
            }
        }
        status.addView(notifWarning)

        // Main controls
        val controls = row(root, top = 12)
        startStopButton = filledButton("Start", p.green) { onStartStop() }
        pauseButton = filledButton("Pause", p.amber) { onPauseResume() }
        controls.addView(startStopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        darkButton = filledButton("Go dark", p.dark) { onDark() }
        root.addView(darkButton, lp(top = 8))
        root.addView(TextView(this).apply {
            text = "Blacks out the screen but keeps it on. Double-tap or long-press the black screen to exit."
            textSize = 12f
            setTextColor(p.muted)
            gravity = Gravity.CENTER
        }, lp(top = 6))

        // Setup checklist
        val setup = card(root, "SETUP")
        val laptopRow = row(setup, top = 10)
        laptopLabel = TextView(this).apply { textSize = 15f; setTextColor(p.text) }
        laptopButton = outlineButton("Connect", p.accent, small = true) { openConnect() }
        laptopRow.addView(laptopLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        laptopRow.addView(laptopButton)

        val overlayRow = row(setup, top = 10)
        overlayLabel = TextView(this).apply { textSize = 15f; setTextColor(p.text) }
        overlayButton = outlineButton("Allow", p.accent, small = true) { openOverlaySettings() }
        overlayRow.addView(overlayLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        overlayRow.addView(overlayButton)

        val batteryRow = row(setup, top = 10)
        batteryLabel = TextView(this).apply { textSize = 15f; setTextColor(p.text) }
        batteryButton = outlineButton("Allow", p.accent, small = true) { requestBatteryExemption() }
        batteryRow.addView(batteryLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        batteryRow.addView(batteryButton)

        setup.addView(TextView(this).apply {
            text = "Tip: when Chrome Remote Desktop asks for your PIN, tick \"remember on this device\" so it can reconnect by itself."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(0, dp(12), 0, 0)
        })

        // Timing
        val timing = card(root, "TIMING")
        timing.addView(TextView(this).apply {
            text = "Swipes happen at random gaps between two values. There is always activity at least every 30 s."
            textSize = 13f
            setTextColor(p.muted)
        }, lp(top = 4))
        val presets = row(timing, top = 10)
        presets.addView(outlineButton("Frequent", p.accent, small = true) { applyPreset(1, 10) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        presets.addView(outlineButton("Normal", p.accent, small = true) { applyPreset(1, 20) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        presets.addView(outlineButton("Relaxed", p.accent, small = true) { applyPreset(5, 30) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val timingRow = row(timing)
        intField(timingRow, "Shortest gap (1–30 s)", Prefs.KEY_MIN_INT, Prefs.DEF_MIN_INT)
        intField(timingRow, "Longest gap (1–30 s)", Prefs.KEY_MAX_INT, Prefs.DEF_MAX_INT)

        // Options
        val options = card(root, "OPTIONS")
        check(options, "Keep the phone screen on while running", Prefs.KEY_SCREEN_ON, true)
        check(options, "Stop everything when I close the app from recent apps", Prefs.KEY_STOP_ON_CLOSE, false)

        // Advanced (collapsed)
        val advanced = card(root)
        val advBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val advHeader = TextView(this).apply {
            text = "Advanced swipe settings  ▸"
            textSize = 15f
            setTextColor(p.accent)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                val open = advBody.visibility != View.VISIBLE
                advBody.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "Advanced swipe settings  ▾" else "Advanced swipe settings  ▸"
            }
        }
        advanced.addView(advHeader)
        advanced.addView(advBody)
        val lenRow = row(advBody, top = 10)
        intField(lenRow, "Length min (10–400 px)", Prefs.KEY_MIN_LEN, Prefs.DEF_MIN_LEN)
        intField(lenRow, "Length max (10–600 px)", Prefs.KEY_MAX_LEN, Prefs.DEF_MAX_LEN)
        val durRow = row(advBody, top = 8)
        intField(durRow, "Duration min (50–2000 ms)", Prefs.KEY_MIN_DUR, Prefs.DEF_MIN_DUR)
        intField(durRow, "Duration max (50–3000 ms)", Prefs.KEY_MAX_DUR, Prefs.DEF_MAX_DUR)

        // Kill switch
        root.addView(filledButton("Stop everything", p.red) { stopEverything() }, lp(top = 16))
        root.addView(TextView(this).apply {
            text = "Stops the loop, closes the laptop session, removes the notification and releases all locks."
            textSize = 12f
            setTextColor(p.muted)
            gravity = Gravity.CENTER
        }, lp(top = 6))
        root.addView(TextView(this).apply {
            text = "Don't press the power button while running: swipes need the screen on. Use Go dark instead."
            textSize = 12f
            setTextColor(p.muted)
            gravity = Gravity.CENTER
        }, lp(top = 10))
    }

    // ---- Actions -----------------------------------------------------------------------------

    private fun hasSession() = Prefs.getString(this, Prefs.KEY_SESSION_URL).isNotBlank()

    private fun batteryOk() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openConnect() {
        if (KeepAliveForegroundService.running) {
            Toast.makeText(this, "Press Stop first, then change the laptop", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, SessionActivity::class.java))
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun onStartStop() {
        if (KeepAliveForegroundService.running) {
            stopService(Intent(this, KeepAliveForegroundService::class.java))
            return
        }
        if (!hasSession()) {
            Toast.makeText(this, "Connect to your laptop first", Toast.LENGTH_LONG).show()
            openConnect()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        startLoop()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIFICATIONS) {
            // Start either way; without the permission only the notification is hidden.
            startLoop()
        }
    }

    private fun startLoop() {
        startRequestedAt = SystemClock.elapsedRealtime()
        startForegroundService(Intent(this, KeepAliveForegroundService::class.java))
    }

    private fun onPauseResume() {
        if (!KeepAliveForegroundService.running) return
        val action = if (KeepAliveForegroundService.paused) {
            KeepAliveForegroundService.ACTION_RESUME
        } else {
            KeepAliveForegroundService.ACTION_PAUSE
        }
        startService(Intent(this, KeepAliveForegroundService::class.java).setAction(action))
    }

    private fun onDark() {
        if (!KeepAliveForegroundService.running) {
            Toast.makeText(this, "Press Start first", Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, KeepAliveForegroundService::class.java)
                .setAction(KeepAliveForegroundService.ACTION_DARK)
        )
    }

    private fun stopEverything() {
        stopService(Intent(this, KeepAliveForegroundService::class.java))
        Toast.makeText(this, "Everything stopped", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    // ---- Status ------------------------------------------------------------------------------

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h} h ${m} min"
            m > 0 -> "${m} min"
            else -> "${s} s"
        }
    }

    private fun updateStatus() {
        val running = KeepAliveForegroundService.running
        val paused = KeepAliveForegroundService.paused
        val now = SystemClock.elapsedRealtime()
        val starting = !running && startRequestedAt > 0 && now - startRequestedAt < 3000

        stateText.text = when {
            starting -> "● Starting…"
            !running -> "● Stopped"
            paused -> "● Paused"
            KeepAliveForegroundService.dark -> "● Running (dark)"
            else -> "● Running"
        }
        stateText.setTextColor(when {
            starting -> p.amber
            !running -> p.muted
            paused -> p.amber
            else -> p.green
        })

        detailText.text = if (running) {
            "Up for ${formatDuration(now - KeepAliveForegroundService.startedAt)} · " +
                "${KeepAliveForegroundService.swipeCount} swipes sent"
        } else {
            "Press Start to begin"
        }

        nextText.text = if (running && !paused && KeepAliveForegroundService.nextTickAt > 0) {
            val s = ((KeepAliveForegroundService.nextTickAt - now) / 1000).coerceAtLeast(0)
            "Next swipe in $s s"
        } else {
            "No swipe scheduled"
        }

        val last = KeepAliveForegroundService.lastResult
        lastText.text = if (KeepAliveForegroundService.lastTickAt > 0 && last.startsWith("Swiped")) {
            val ago = (now - KeepAliveForegroundService.lastTickAt) / 1000
            "Last: $last ($ago s ago)"
        } else {
            "Last: $last"
        }

        // Laptop session
        val state = KeepAliveForegroundService.sessionState
        when {
            !hasSession() -> {
                sessionText.text = "✗ No laptop saved yet. Tap Connect below."
                sessionText.setTextColor(p.red)
            }
            running && state == "connected" -> {
                sessionText.text = "✓ Laptop connected"
                sessionText.setTextColor(p.green)
            }
            running && state == "sign-in needed" -> {
                sessionText.text = "✗ Google sign-in lost. Tap Stop, then Connect, and sign in again."
                sessionText.setTextColor(p.red)
            }
            running -> {
                sessionText.text = "… Laptop session: $state"
                sessionText.setTextColor(p.amber)
            }
            else -> {
                sessionText.text = "Laptop saved. It connects when you press Start."
                sessionText.setTextColor(p.muted)
            }
        }

        // Setup checklist
        val laptopOk = hasSession()
        laptopLabel.text = (if (laptopOk) "✓ " else "✗ ") + "Laptop saved"
        laptopLabel.setTextColor(if (laptopOk) p.green else p.red)
        laptopButton.text = if (laptopOk) "Change" else "Connect"

        val overlayOk = Settings.canDrawOverlays(this)
        overlayLabel.text = (if (overlayOk) "✓ " else "✗ ") + "Display over other apps"
        overlayLabel.setTextColor(if (overlayOk) p.green else p.red)
        overlayButton.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val batteryOk = batteryOk()
        batteryLabel.text = (if (batteryOk) "✓ " else "• ") + "Runs in the background"
        batteryLabel.setTextColor(if (batteryOk) p.green else p.amber)
        batteryButton.visibility = if (batteryOk) View.GONE else View.VISIBLE

        notifWarning.visibility = if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) View.VISIBLE else View.GONE

        startStopButton.text = if (running) "Stop" else "Start"
        startStopButton.background = rounded(if (running) p.red else p.green, 14)
        pauseButton.text = if (paused) "Resume" else "Pause"
        pauseButton.isEnabled = running
        pauseButton.alpha = if (running) 1f else 0.4f
        darkButton.isEnabled = running
        darkButton.alpha = if (running) 1f else 0.4f
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private const val REQ_NOTIFICATIONS_ONLY = 2
    }
}
