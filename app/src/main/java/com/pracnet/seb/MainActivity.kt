package com.pracnet.seb

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val TARGET_URL = "https://prac.amn-lab.com/login/index.php"
        private const val CUSTOM_UA_SUFFIX = "PracnetSEBClient/1.0"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Aturan 3: Blokir screenshot & screen recording ---
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_main)

        // --- Aturan 4: Kiosk Mode (Lock Task) ---
        startLockTask()

        webView = findViewById(R.id.webView)
        setupWebView()

        // --- Aturan 1: Arahkan ke URL target ---
        webView.loadUrl(TARGET_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true

            // --- Aturan 2: Custom User-Agent ---
            userAgentString = "$userAgentString $CUSTOM_UA_SUFFIX"
        }

        // Navigasi tetap di dalam WebView
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    // Blokir tombol back agar tidak bisa keluar dari aplikasi
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
        // Jangan panggil super.onBackPressed() agar tidak bisa keluar
    }

    // Pastikan lock task tetap aktif saat app kembali ke foreground
    override fun onResume() {
        super.onResume()
        startLockTask()
    }
}
