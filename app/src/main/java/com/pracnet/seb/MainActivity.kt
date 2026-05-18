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

    // Simpan URL terakhir untuk auto-reconnect
    private var lastLoadedUrl: String? = null

    // Anti-minimize: track apakah app pernah kehilangan focus
    private var violationCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isPinned = false
    private var studentId = ""  // Akan diambil dari halaman login
    private var isBanned = false
    private var isExiting = false  // Flag untuk mencegah false positive saat exit

    companion object {
        private const val BASE_URL = "https://prac.amn-lab.com"
        private const val CONFIG_URL = "$BASE_URL/seb-config.json"
        private const val VIOLATION_URL = "$BASE_URL/seb-violation.php"
        private const val FALLBACK_URL = "$BASE_URL/login/index.php"
        private const val ALLOWED_DOMAIN = "prac.amn-lab.com"
        private const val CUSTOM_UA_SUFFIX = "SEB/3.0 PracnetSEBClient/1.0"
        private const val DEFAULT_EXIT_PASSWORD = "dosen2024"
        private const val TAP_THRESHOLD = 5
        private const val TAP_TIMEOUT_MS = 3000L
        private const val MAX_VIOLATIONS = 3
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

        // Load student ID dari session sebelumnya (jika ada)
        studentId = getSharedPreferences("seb_prefs", MODE_PRIVATE)
            .getString("student_id", "") ?: ""

        setupWebView()
        setupSecretExit()

        btnRetry.setOnClickListener {
            // Jangan reset isBanned di sini — biarkan fetchConfigAndLoad yang tentukan
            // berdasarkan response server
            violationCount = 0
            btnRetry.text = getString(R.string.retry)
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
        // Tidak perlu bringToFront di sini — LockService sudah handle
        // onPause bisa terpanggil saat dialog muncul, keyboard muncul, dll
    }

    override fun onStop() {
        super.onStop()
        // App benar-benar kehilangan foreground — ini pelanggaran
        // Guard: hanya hitung jika:
        // 1. App tidak sedang exit
        // 2. App tidak banned
        // 3. WebView sudah visible (bukan saat splash/dialog)
        // 4. Activity tidak finishing
        if (!isExiting && !isFinishing && !isBanned && webView.visibility == View.VISIBLE) {
            violationCount++
            reportViolation()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isBanned) {
            // Jangan panggil showBannedScreen() berulang — cukup pastikan state benar
            if (errorView.visibility != View.VISIBLE) {
                showBannedScreen()
            }
            return
        }
        // Tampilkan warning jika ada pelanggaran
        if (violationCount > 0 && webView.visibility == View.VISIBLE) {
            val remaining = MAX_VIOLATIONS - violationCount
            val msg = if (remaining > 0) {
                "⚠️ Pelanggaran terdeteksi ($violationCount/$MAX_VIOLATIONS). Sisa kesempatan: $remaining"
            } else {
                "🚫 Batas pelanggaran tercapai. Akses ujian diblokir."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
            /**
             * URL Filtering ketat untuk mencegah curang.
             * 
             * DIIZINKAN:
             * - /login/          → Halaman login
             * - /mod/quiz/       → Semua halaman kuis (attempt, view, summary, review)
             * - /my/             → Dashboard (untuk navigasi ke kuis)
             * - /course/view.php → Halaman course (untuk klik kuis)
             * - /theme/          → Asset tema (CSS, JS, gambar)
             * - /lib/            → Library Moodle (JS, CSS)
             * - /pluginfile.php  → File soal (gambar dalam soal)
             * - /question/       → Engine soal
             * 
             * DIBLOKIR:
             * - /message/        → Messaging (bisa kirim jawaban ke teman)
             * - /user/           → Profil user (lihat peserta)
             * - /mod/forum/      → Forum (diskusi jawaban)
             * - /mod/chat/       → Chat (komunikasi real-time)
             * - /mod/resource/   → Resource/file (contekan)
             * - /mod/page/       → Halaman materi (contekan)
             * - /mod/book/       → Book (contekan)
             * - /mod/url/        → Link eksternal
             * - /mod/assign/     → Assignment
             * - /mod/wiki/       → Wiki
             * - /calendar/       → Kalender
             * - /badges/         → Badges
             * - /grade/          → Gradebook
             * - /blog/           → Blog
             * - /notes/          → Notes
             * - /report/         → Reports
             * - /admin/          → Admin pages
             * - /contentbank/    → Content bank
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = request.url?.host ?: return false

                // Blokir domain luar
                if (!host.endsWith(ALLOWED_DOMAIN)) return true

                // Cek path — hanya izinkan yang diperlukan untuk ujian
                return !isAllowedPath(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                splashView.visibility = View.GONE
                errorView.visibility = View.GONE
                progressBar.visibility = View.GONE

                lastLoadedUrl = url

                // Coba ambil student ID dari halaman
                extractStudentId(view)

                // Inject CSS untuk disable text selection + sembunyikan elemen navigasi berbahaya
                view?.evaluateJavascript(
                    """
                    (function() {
                        var style = document.createElement('style');
                        style.innerHTML = '* { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; } ' +
                            '.usermenu { display: none !important; } ' +
                            '#user-menu-toggle { display: none !important; } ' +
                            'a[href*="/message/"] { display: none !important; } ' +
                            'a[href*="/user/"] { display: none !important; } ' +
                            'a[href*="/calendar/"] { display: none !important; } ' +
                            'a[href*="/grade/"] { display: none !important; } ' +
                            '.popover-region-notifications { display: none !important; } ' +
                            '.popover-region-messages { display: none !important; } ' +
                            '#nav-drawer a:not([href*="/my/"]):not([href*="/course/"]):not([href*="/mod/quiz/"]) { display: none !important; }';
                        document.head.appendChild(style);
                    })();
                    """.trimIndent(),
                    null
                )

                val currentUrl = url ?: return

                // Detect kuis selesai — hanya di halaman review (setelah submit)
                // BUKAN di view.php (halaman sebelum attempt)
                if (currentUrl.contains("/mod/quiz/review.php") ||
                    currentUrl.contains("/mod/quiz/summary.php")
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
                    // Auto-retry setelah 3 detik
                    view?.postDelayed({ 
                        if (errorView.visibility != View.VISIBLE) {
                            view.loadUrl(urlToRetry) 
                        }
                    }, 3000)
                    // Tampilkan error kalau setelah 12 detik masih belum load
                    view?.postDelayed({
                        if (webView.visibility == View.VISIBLE && progressBar.visibility == View.VISIBLE) {
                            showError(getString(R.string.error_connection))
                        }
                    }, 12000)
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

    /**
     * Cek apakah URL diizinkan untuk diakses selama ujian.
     * Whitelist approach — hanya path yang eksplisit diizinkan yang bisa diakses.
     */
    private fun isAllowedPath(url: String): Boolean {
        val path = android.net.Uri.parse(url).path ?: return true

        // Whitelist: path yang diizinkan
        val allowedPaths = listOf(
            "/login/",           // Login
            "/mod/quiz/",        // Semua halaman kuis
            "/my/",              // Dashboard
            "/course/view.php",  // Halaman course
            "/theme/",           // Asset tema
            "/lib/",             // Library JS/CSS
            "/pluginfile.php",   // File dalam soal (gambar, dll)
            "/question/",        // Question engine
            "/draftfile.php",    // Draft files
            "/tokenpluginfile.php", // Token-based files
        )

        // Root URL (homepage)
        if (path == "/" || path.isEmpty()) return true

        // Cek apakah path cocok dengan whitelist
        for (allowed in allowedPaths) {
            if (path.startsWith(allowed)) return true
        }

        // Izinkan juga static assets (CSS, JS, images, fonts)
        val extension = path.substringAfterLast(".", "")
        val allowedExtensions = listOf("css", "js", "png", "jpg", "jpeg", "gif", "svg", "woff", "woff2", "ttf", "ico")
        if (extension in allowedExtensions) return true

        // Semua path lain diblokir
        return false
    }

    private fun fetchConfigAndLoad() {
        Thread {
            try {
                // --- Cek status ban dari server terlebih dahulu ---
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                )
                val checkId = if (studentId.isNotBlank()) studentId else deviceId
                val banCheckUrl = "$VIOLATION_URL?check=1&student_id=$checkId"
                val banConn = URL(banCheckUrl).openConnection() as HttpURLConnection
                banConn.connectTimeout = 5000
                banConn.readTimeout = 5000
                if (banConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val banResponse = banConn.inputStream.bufferedReader().readText()
                    val banJson = JSONObject(banResponse)
                    if (banJson.optBoolean("banned", false)) {
                        isBanned = true
                        violationCount = banJson.optInt("total_violations", MAX_VIOLATIONS)
                        runOnUiThread { showBannedScreen() }
                        banConn.disconnect()
                        return@Thread
                    } else {
                        // Server sudah di-reset atau belum banned
                        val wasBanned = isBanned
                        isBanned = false
                        violationCount = banJson.optInt("total_violations", 0)
                        
                        // Kalau sebelumnya banned dan sekarang unban, trigger lock task lagi
                        if (wasBanned) {
                            runOnUiThread { startLockTask() }
                        }
                    }
                }
                banConn.disconnect()

                // --- Fetch config (password) ---
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
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { showError(getString(R.string.error_connection)) }
                return@Thread
            }

            runOnUiThread {
                splashView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                webView.loadUrl(FALLBACK_URL)
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
        isExiting = true
        stopLockService()
        try { stopLockTask() } catch (_: Exception) {}
        finishAndRemoveTask()
    }

    /**
     * Kirim pelanggaran ke server secara async
     */
    private fun reportViolation() {
        Thread {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                )
                val json = JSONObject().apply {
                    put("student_id", if (studentId.isNotBlank()) studentId else deviceId)
                    put("device_id", deviceId)
                    put("violation_type", "app_exit")
                    put("current_url", lastLoadedUrl ?: "unknown")
                    put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                }

                val connection = URL(VIOLATION_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val result = JSONObject(response)
                    val banned = result.optBoolean("banned", false)

                    if (banned) {
                        isBanned = true
                        runOnUiThread { showBannedScreen() }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Tampilkan layar banned — mahasiswa tidak bisa lanjut ujian
     * Tapi ada tombol retry yang cek ulang ke server (kalau dosen sudah reset)
     */
    private fun showBannedScreen() {
        webView.visibility = View.GONE
        splashView.visibility = View.GONE
        btnFinishExam.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorText.text = "🚫 Akses Ujian Diblokir\n\nAnda telah melanggar aturan ujian sebanyak $MAX_VIOLATIONS kali dengan keluar dari aplikasi.\n\nHubungi pengawas untuk informasi lebih lanjut.\n\nDevice ID: ${android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)}"
        // Tampilkan tombol retry agar bisa cek ulang setelah dosen reset
        btnRetry.visibility = View.VISIBLE
        btnRetry.text = "Periksa Ulang Status"
    }

    /**
     * Ambil student ID dari halaman Moodle setelah login.
     * Coba beberapa metode untuk mendapatkan username.
     */
    private fun extractStudentId(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function() {
                // Method 1: dari user menu (Moodle 4.x)
                var el = document.querySelector('[data-userid]');
                if (el) return el.getAttribute('data-userid');
                
                // Method 2: dari class usertext
                var ut = document.querySelector('.usertext');
                if (ut) return ut.textContent.trim();
                
                // Method 3: dari login info
                var li = document.querySelector('.logininfo a[href*="user/profile"]');
                if (li) return li.textContent.trim();
                
                // Method 4: dari body class (Moodle adds user ID)
                var body = document.body.className;
                var match = body.match(/user-(\d+)/);
                if (match) return 'uid_' + match[1];
                
                // Method 5: dari sesskey hidden input (indicates logged in)
                var sesskey = document.querySelector('input[name="sesskey"]');
                if (sesskey) {
                    // User is logged in, try to get from page header
                    var header = document.querySelector('.usermenu .userbutton, .userbutton span, #user-menu-toggle');
                    if (header) return header.textContent.trim();
                }
                
                return '';
            })();
            """.trimIndent()
        ) { value ->
            val cleaned = value.replace("\"", "").replace("\\n", "").trim()
            if (cleaned.isNotBlank() && cleaned != "null" && cleaned.length > 1) {
                studentId = cleaned
                // Simpan ke SharedPreferences agar persist
                getSharedPreferences("seb_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("student_id", studentId)
                    .apply()
            }
        }
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
