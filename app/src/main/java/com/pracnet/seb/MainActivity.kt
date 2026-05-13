package com.pracnet.seb

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: MaterialButton

    // Secret exit: tap 5x di pojok kiri atas
    private var tapCount = 0
    private var lastTapTime = 0L

    // Password dari remote config (fallback ke default)
    private var exitPassword = DEFAULT_EXIT_PASSWORD

    companion object {
        private const val BASE_URL = "https://prac.amn-lab.com"
        private const val CONFIG_URL = "$BASE_URL/seb-config.json"
        private const val FALLBACK_URL = "$BASE_URL/login/index.php"
        private const val ALLOWED_DOMAIN = "prac.amn-lab.com"
        private const val CUSTOM_UA_SUFFIX = "PracnetSEBClient/1.0"
        private const val DEFAULT_EXIT_PASSWORD = "dosen2024"
        private const val TAP_THRESHOLD = 5
        private const val TAP_TIMEOUT_MS = 3000L
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Blokir screenshot & screen recording ---
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_main)

        // --- Kiosk Mode (Lock Task) ---
        startLockTask()

        // Init views
        webView = findViewById(R.id.webView)
        splashView = findViewById(R.id.splashView)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)

        // Clear cookies agar tidak ada sesi tumpang tindih
        clearCookies()

        setupWebView()
        setupSecretExit()

        btnRetry.setOnClickListener {
            showSplash()
            fetchConfigAndLoad()
        }

        // Fetch remote config, lalu load URL
        fetchConfigAndLoad()
    }

    private fun clearCookies() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true

            // --- Custom User-Agent ---
            userAgentString = "$userAgentString $CUSTOM_UA_SUFFIX"
        }

        webView.webViewClient = object : WebViewClient() {
            // Blokir navigasi ke luar domain yang diizinkan
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host ?: return false
                return !host.endsWith(ALLOWED_DOMAIN)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Sembunyikan splash, tampilkan WebView
                splashView.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Hanya tampilkan error untuk main frame
                if (request?.isForMainFrame == true) {
                    showError(getString(R.string.error_connection))
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (webView.visibility == View.VISIBLE) {
                    progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    progressBar.progress = newProgress
                }
            }
        }
    }

    /**
     * Fetch seb-config.json dari server.
     * Format:
     * {
     *   "quiz_url": "https://prac.amn-lab.com/mod/quiz/view.php?id=123",
     *   "exit_password": "passwordSesi1"
     * }
     */
    private fun fetchConfigAndLoad() {
        Thread {
            var targetUrl = FALLBACK_URL
            var noExam = false

            try {
                val connection = URL(CONFIG_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)

                    // Ambil password dari config
                    val remotePassword = json.optString("exit_password", "")
                    if (remotePassword.isNotBlank()) {
                        exitPassword = remotePassword
                    }

                    // Ambil quiz URL
                    val quizUrl = json.optString("quiz_url", "")
                    if (quizUrl.isNotBlank()) {
                        val path = quizUrl.removePrefix(BASE_URL)
                        targetUrl = "$BASE_URL/login/index.php?wantsurl=$path"
                    } else {
                        noExam = true
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    showError(getString(R.string.error_connection))
                }
                return@Thread
            }

            val finalUrl = targetUrl
            val finalNoExam = noExam

            runOnUiThread {
                if (finalNoExam) {
                    showError(getString(R.string.no_exam_active))
                } else {
                    webView.loadUrl(finalUrl)
                }
            }
        }.start()
    }

    private fun showSplash() {
        splashView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        webView.visibility = View.GONE
    }

    private fun showError(message: String) {
        splashView.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorText.text = message
    }

    /**
     * Secret Exit Mechanism:
     * Tap 5x cepat di pojok kiri atas layar (area 100dp x 100dp)
     * → muncul dialog password → masukkan password → app keluar dari Kiosk Mode
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSecretExit() {
        val rootView = findViewById<FrameLayout>(android.R.id.content)
        rootView.setOnTouchListener { _, event ->
            val threshold = (100 * resources.displayMetrics.density).toInt()
            if (event.x < threshold && event.y < threshold) {
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime > TAP_TIMEOUT_MS) {
                        tapCount = 0
                    }
                    tapCount++
                    lastTapTime = now

                    if (tapCount >= TAP_THRESHOLD) {
                        tapCount = 0
                        showExitDialog()
                    }
                }
            }
            false
        }
    }

    private fun showExitDialog() {
        val input = EditText(this).apply {
            hint = "Masukkan password pengawas"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Exit Mode Ujian")
            .setMessage("Masukkan password pengawas untuk keluar.")
            .setView(input)
            .setPositiveButton("Keluar") { _, _ ->
                if (input.text.toString() == exitPassword) {
                    stopLockTask()
                    finish()
                } else {
                    Toast.makeText(this, "Password salah", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    override fun onResume() {
        super.onResume()
        startLockTask()
    }
}
