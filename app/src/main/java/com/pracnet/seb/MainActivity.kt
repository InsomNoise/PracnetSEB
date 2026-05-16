package com.pracnet.seb

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private lateinit var btnFinishExam: MaterialButton

    // Secret exit: tap 5x di pojok kiri atas
    private var tapCount = 0
    private var lastTapTime = 0L

    // Password dari remote config (fallback ke default)
    private var exitPassword = DEFAULT_EXIT_PASSWORD

    // URL kuis dari remote config (akan di-load setelah login berhasil)
    private var quizUrl: String? = null

    // Simpan URL terakhir untuk auto-reconnect
    private var lastLoadedUrl: String? = null

    // Anti-minimize: track apakah app pernah kehilangan focus
    private var violationCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isPinned = false

    companion object {
        private const val BASE_URL = "https://prac.amn-lab.com"
        private const val CONFIG_URL = "$BASE_URL/seb-config.json"
        private const val FALLBACK_URL = "$BASE_URL/login/index.php"
        private const val ALLOWED_DOMAIN = "prac.amn-lab.com"
        private const val CUSTOM_UA_SUFFIX = "SEB/3.0 PracnetSEBClient/1.0"
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

        // --- Keep screen always on selama ujian ---
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        // --- Kiosk Mode (Lock Task) — hanya sekali ---
        startLockTask()

        // --- Start foreground service ---
        startLockService()

        // Init views
        webView = findViewById(R.id.webView)
        splashView = findViewById(R.id.splashView)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)
        btnFinishExam = findViewById(R.id.btnFinishExam)

        // Clear cookies agar tidak ada sesi tumpang tindih
        clearCookies()

        setupWebView()
        setupSecretExit()

        btnRetry.setOnClickListener {
            showSplash()
            fetchConfigAndLoad()
        }

        btnFinishExam.setOnClickListener {
            exitApp()
        }

        // Fetch remote config, lalu load URL
        fetchConfigAndLoad()
    }

    /**
     * Intercept semua hardware key — blokir HOME, RECENT APPS, BACK
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_MENU -> {
                    // Blokir tombol-tombol ini
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Dihandle di dispatchKeyEvent
    }

    /**
     * Detect saat app kehilangan focus (user berhasil minimize).
     * Catat sebagai pelanggaran dan bawa kembali.
     */
    override fun onPause() {
        super.onPause()
        // Jika app di-pause, segera schedule bawa kembali
        handler.postDelayed({
            if (!isFinishing) {
                bringToFront()
            }
        }, 100)
    }

    override fun onStop() {
        super.onStop()
        // App benar-benar kehilangan foreground — ini pelanggaran
        if (!isFinishing) {
            violationCount++
            handler.post { bringToFront() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Tampilkan warning jika ada pelanggaran
        if (violationCount > 0 && webView.visibility == View.VISIBLE) {
            Toast.makeText(
                this,
                "⚠️ Aktivitas keluar aplikasi terdeteksi ($violationCount kali). Pengawas akan diberitahu.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Sembunyikan system UI (immersive mode)
            hideSystemUI()
            // Cek apakah lock task aktif
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                isPinned = false
            } else {
                isPinned = true
            }
        }
    }

    /**
     * Immersive mode — sembunyikan navigation bar dan status bar
     */
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun bringToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
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
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // --- Disable file access ---
            allowFileAccess = false
            allowContentAccess = false

            // --- Custom User-Agent ---
            userAgentString = "$userAgentString $CUSTOM_UA_SUFFIX"
        }

        // --- Disable long-press (copy/paste/select text) ---
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false

        // --- Disable file download ---
        webView.setDownloadListener(DownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "Download tidak diizinkan selama ujian.", Toast.LENGTH_SHORT).show()
        })

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host ?: return false
                return !host.endsWith(ALLOWED_DOMAIN)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                splashView.visibility = View.GONE
                errorView.visibility = View.GONE
                progressBar.visibility = View.GONE

                lastLoadedUrl = url

                // Inject CSS untuk disable text selection
                view?.evaluateJavascript(
                    """
                    (function() {
                        var style = document.createElement('style');
                        style.innerHTML = '* { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }';
                        document.head.appendChild(style);
                    })();
                    """.trimIndent(),
                    null
                )

                val currentUrl = url ?: return

                // Redirect ke kuis setelah login
                if (quizUrl != null && (
                    currentUrl.contains("/my/") ||
                    currentUrl.contains("redirect=0") ||
                    currentUrl.endsWith("/") && !currentUrl.contains("/login/")
                )) {
                    val targetQuiz = quizUrl
                    quizUrl = null
                    view?.loadUrl(targetQuiz!!)
                }

                // Detect kuis selesai
                if (currentUrl.contains("/mod/quiz/review.php") ||
                    currentUrl.contains("/mod/quiz/summary.php") ||
                    currentUrl.contains("/mod/quiz/view.php") && quizUrl == null
                ) {
                    btnFinishExam.visibility = View.VISIBLE
                } else {
                    btnFinishExam.visibility = View.GONE
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val urlToRetry = lastLoadedUrl ?: FALLBACK_URL
                    view?.postDelayed({ view.loadUrl(urlToRetry) }, 3000)
                    view?.postDelayed({
                        if (progressBar.visibility == View.VISIBLE) {
                            showError(getString(R.string.error_connection))
                        }
                    }, 10000)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                Toast.makeText(this@MainActivity, "Upload file tidak diizinkan selama ujian.", Toast.LENGTH_SHORT).show()
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
    }

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

                    val remotePassword = json.optString("exit_password", "")
                    if (remotePassword.isNotBlank()) {
                        exitPassword = remotePassword
                    }

                    val quizUrl = json.optString("quiz_url", "")
                    if (quizUrl.isNotBlank()) {
                        this@MainActivity.quizUrl = quizUrl
                        targetUrl = FALLBACK_URL
                    } else {
                        noExam = true
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { showError(getString(R.string.error_connection)) }
                return@Thread
            }

            val finalUrl = targetUrl
            val finalNoExam = noExam

            runOnUiThread {
                if (finalNoExam) {
                    showError(getString(R.string.no_exam_active))
                } else {
                    splashView.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSecretExit() {
        val secretTapArea = findViewById<View>(R.id.secretTapArea)
        secretTapArea.setOnTouchListener { _, event ->
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
            true
        }
    }

    private fun showExitDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_exit, null)
        val inputPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputExitPassword)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirmExit = dialogView.findViewById<MaterialButton>(R.id.btnConfirmExit)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirmExit.setOnClickListener {
            val enteredPassword = inputPassword.text.toString().trim()
            if (enteredPassword == exitPassword) {
                dialog.dismiss()
                exitApp()
            } else {
                Toast.makeText(this, "Kode akses tidak valid. Silakan coba kembali.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun exitApp() {
        stopLockService()
        try { stopLockTask() } catch (_: Exception) {}
        finishAndRemoveTask()
    }

    private fun startLockService() {
        val serviceIntent = Intent(this, LockService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopLockService() {
        val serviceIntent = Intent(this, LockService::class.java)
        stopService(serviceIntent)
    }
}
