package com.thehhr.form.nativeapp

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun MuscleMapView(entries: List<MuscleMapMath.Entry>, gender: String, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val call = remember(entries, gender) { MuscleMapMath.renderCall(gender, entries) }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).also { view ->
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.allowFileAccess = true
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view2: WebView, url: String) {
                        ready = true
                        pending?.let { script -> view2.evaluateJavascript(script, null) }
                        pending = null
                    }
                }
                view.loadUrl("file:///android_asset/musclemap/map.html")
            }
        },
        update = { view ->
            if (ready) view.evaluateJavascript(call, null) else pending = call
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}
