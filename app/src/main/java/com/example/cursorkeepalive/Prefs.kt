// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 murslinshehzad-code

package com.example.cursorkeepalive

import android.content.Context
import kotlin.math.max

/** All user settings, stored in SharedPreferences. The service re-reads them on every tick. */
object Prefs {
    const val KEY_MIN_INT = "min_interval_sec"
    const val KEY_MAX_INT = "max_interval_sec"
    const val KEY_MIN_LEN = "min_len_px"
    const val KEY_MAX_LEN = "max_len_px"
    const val KEY_MIN_DUR = "min_dur_ms"
    const val KEY_MAX_DUR = "max_dur_ms"
    const val KEY_SCREEN_ON = "keep_screen_on"
    const val KEY_STOP_ON_CLOSE = "stop_on_close"
    const val KEY_SESSION_URL = "session_url"

    const val DEF_MIN_INT = 1
    const val DEF_MAX_INT = 30
    const val DEF_MIN_LEN = 60
    const val DEF_MAX_LEN = 115
    const val DEF_MIN_DUR = 250
    const val DEF_MAX_DUR = 450

    /** Hard limit: there is always activity at least this often. */
    const val MAX_INTERVAL_CAP_SEC = 30

    /** The value the app actually uses for a numeric setting (after clamping), for showing in the UI. */
    fun effective(c: Context, key: String): Int {
        val cfg = load(c)
        return when (key) {
            KEY_MIN_INT -> cfg.minIntervalSec
            KEY_MAX_INT -> cfg.maxIntervalSec
            KEY_MIN_LEN -> cfg.minLenPx
            KEY_MAX_LEN -> cfg.maxLenPx
            KEY_MIN_DUR -> cfg.minDurMs
            KEY_MAX_DUR -> cfg.maxDurMs
            else -> getInt(c, key, 0)
        }
    }

    data class Config(
        val minIntervalSec: Int,
        val maxIntervalSec: Int,
        val minLenPx: Int,
        val maxLenPx: Int,
        val minDurMs: Int,
        val maxDurMs: Int,
        val keepScreenOn: Boolean,
        val stopOnClose: Boolean,
    )

    private fun sp(c: Context) = c.getSharedPreferences("keepalive", Context.MODE_PRIVATE)

    fun getInt(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)

    fun putInt(c: Context, key: String, value: Int) {
        sp(c).edit().putInt(key, value).apply()
    }

    fun getString(c: Context, key: String): String = sp(c).getString(key, "") ?: ""

    fun putString(c: Context, key: String, value: String) {
        sp(c).edit().putString(key, value).apply()
    }

    fun getBool(c: Context, key: String, def: Boolean): Boolean = sp(c).getBoolean(key, def)

    fun putBool(c: Context, key: String, value: Boolean) {
        sp(c).edit().putBoolean(key, value).apply()
    }

    /** Loads settings and forces them into safe ranges (e.g. max is never below min). */
    fun load(c: Context): Config {
        val minInt = getInt(c, KEY_MIN_INT, DEF_MIN_INT).coerceIn(1, MAX_INTERVAL_CAP_SEC)
        val minLen = getInt(c, KEY_MIN_LEN, DEF_MIN_LEN).coerceIn(10, 400)
        val minDur = getInt(c, KEY_MIN_DUR, DEF_MIN_DUR).coerceIn(50, 2000)
        return Config(
            minIntervalSec = minInt,
            maxIntervalSec = max(minInt, getInt(c, KEY_MAX_INT, DEF_MAX_INT).coerceIn(1, MAX_INTERVAL_CAP_SEC)),
            minLenPx = minLen,
            maxLenPx = max(minLen, getInt(c, KEY_MAX_LEN, DEF_MAX_LEN).coerceIn(10, 600)),
            minDurMs = minDur,
            maxDurMs = max(minDur, getInt(c, KEY_MAX_DUR, DEF_MAX_DUR).coerceIn(50, 3000)),
            keepScreenOn = getBool(c, KEY_SCREEN_ON, true),
            stopOnClose = getBool(c, KEY_STOP_ON_CLOSE, false),
        )
    }
}
