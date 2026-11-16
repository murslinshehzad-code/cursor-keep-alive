// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 murslinshehzad-code

package com.example.cursorkeepalive

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * One-time setup: sign in to Google, open your laptop on the Chrome Remote Desktop website,
 * then tap "Use this laptop". The background service reconnects to that session by itself.
 */
class SessionActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlText: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        keepInsideSystemBars(root)

        // Instructions at the top (below the camera / status bar).
        urlText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#1B2230"))
            setBackgroundColor(Color.parseColor("#E8ECF4"))
            setPadding(24, 16, 24, 16)
            text = "1. Sign in to Google\n2. Tap your laptop and wait until you see its screen\n" +
                "3. Tap the green button below"
        }
        root.addView(urlText)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Buttons at the bottom (above the navigation bar).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E8ECF4"))
            setPadding(8, 8, 8, 8)
        }
        bar.addView(button("Back", 1f) { if (web.canGoBack()) web.goBack() })
        bar.addView(button("Reload", 1f) { web.reload() })
        bar.addView(button("Use this laptop", 2.2f, Color.parseColor("#1F8A4C")) { useThisLaptop() })
        bar.addView(button("Close", 1f) { finish() })
        root.addView(bar)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page crashed. Without this Android would kill the whole app.
                Toast.makeText(this@SessionActivity, "The page crashed. Reopening it...", Toast.LENGTH_LONG).show()
                recreate()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }

        if (savedInstanceState == null) web.loadUrl(START_URL) else web.restoreState(savedInstanceState)
    }

    private fun button(label: String, weight: Float, fill: Int? = null, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        if (fill != null) {
            setTextColor(Color.WHITE)
            setBackgroundColor(fill)
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = 4
            marginEnd = 4
        }
        setOnClickListener { onClick() }
    }

    /** Android 15+ draws apps edge to edge; keep our content clear of the camera and system bars. */
    private fun keepInsideSystemBars(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
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
    }

    private fun useThisLaptop() {
        val url = web.url.orEmpty()
        if (!url.startsWith("https://remotedesktop.google.com/") || !url.contains("/access/session/")) {
            Toast.makeText(this, "Open your laptop first and wait until you see its screen", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.putString(this, Prefs.KEY_SESSION_URL, url)
        Toast.makeText(this, "Saved. You can now press Start.", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        // Close the visible session so only the background service holds a connection.
        web.loadUrl("about:blank")
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://remotedesktop.google.com/access"
    }
}
