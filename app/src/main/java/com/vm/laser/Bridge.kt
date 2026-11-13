package com.vm.laser

import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Ponte pagina -> app: ogni chiamata è un comando "/api/..." con corpo JSON. */
class Bridge(private val laser: Laser) {
    @JavascriptInterface
    fun call(path: String, body: String): String = try {
        laser.handle(path, JSONObject(body.ifBlank { "{}" })).toString()
    } catch (e: Exception) {
        laser.safeStop()
        JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
    }
}
