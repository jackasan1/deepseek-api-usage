package com.rikka.dsusage.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private const val EXTRACT_TOKEN_JS =
    "(function(){try{var r=localStorage.getItem('userToken');if(!r)return '';" +
        "var p=JSON.parse(r);return (p&&p.value)?p.value:'';}catch(e){return '';}})()"

internal fun unquoteJs(raw: String?): String {
    if (raw == null) return ""
    val t = raw.trim()
    if (t.isEmpty() || t == "null") return ""
    return t.removeSurrounding("\"")
}

/** 内置登录页：登录完成后轮询同源 localStorage 自动抓取 userToken。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(onToken: (String) -> Unit) {
    val holder = remember { arrayOfNulls<WebView>(1) }
    val done = remember { booleanArrayOf(false) }

    fun harvest(raw: String?) {
        if (done[0]) return
        val tok = unquoteJs(raw)
        if (tok.isNotEmpty()) {
            done[0] = true
            onToken(tok)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(EXTRACT_TOKEN_JS) { r -> harvest(r) }
                    }
                }
                loadUrl("https://platform.deepseek.com/sign_in")
                holder[0] = this
            }
        },
    )

    LaunchedEffect(Unit) {
        while (!done[0]) {
            delay(1500)
            holder[0]?.evaluateJavascript(EXTRACT_TOKEN_JS) { r -> harvest(r) }
        }
    }
}
