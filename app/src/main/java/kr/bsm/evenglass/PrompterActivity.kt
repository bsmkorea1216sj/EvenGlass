package kr.bsm.evenglass

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/** Presentation prompter: the existing web page, shown in a WebView. */
class PrompterActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The page's "← 메인" button navigates to index.html: go back to the camera screen.
                if (request.url.toString().endsWith("index.html")) {
                    finish()
                    return true
                }
                return false
            }
        }
        web.loadUrl("file:///android_asset/prompter.html")
        setContentView(web)
    }
}
