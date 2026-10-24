package com.perplexus.game

import android.app.Activity
import android.os.Bundle

/** Activity única: delega todo en AppLogic (orquestación). */
class MainActivity : Activity() {
    private lateinit var app: AppLogic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = AppLogic(this)
        app.onCreate()
    }

    override fun onResume() { super.onResume(); app.onResume() }
    override fun onPause() { app.onPause(); super.onPause() }
    override fun onDestroy() { app.onDestroy(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); app.onWindowFocusChanged(hasFocus) }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { app.onBackPressed() }
}
