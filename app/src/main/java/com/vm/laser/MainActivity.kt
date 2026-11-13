package com.vm.laser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var laser: Laser

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        laser = Laser(applicationContext)
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        // finestre prompt/confirm della pagina; gli errori JavaScript finiscono nel log
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.w("VMLASER", "${m.message()} @${m.lineNumber()}")
                return true
            }
        }
        web.addJavascriptInterface(Bridge(laser), "VM")
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    // Uscendo dall'app nessun movimento deve restare attivo.
    override fun onPause() {
        super.onPause()
        thread { laser.safeStop() }
    }

    override fun onDestroy() {
        thread { laser.close() }
        super.onDestroy()
    }
}
