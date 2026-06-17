package com.darkbit.bypass

import android.animation.ValueAnimator
import android.app.Activity
import android.content.*
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.darkbit.bypass.databinding.ActivityMainBinding
import androidx.appcompat.app.AlertDialog
import android.widget.RadioButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var xrayService: XrayService? = null
    private var isConnected = false
    private var configFile: File? = null
    private var serviceBound = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private var logRefreshJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            xrayService = (binder as XrayService.LocalBinder).getService()
            serviceBound = true
            xrayService?.onStatusChanged = { connected ->
                runOnUiThread { updateUI(connected) }
            }
            xrayService?.onSpeedUpdate = { down, up ->
                runOnUiThread { updateSpeed(down, up) }
            }
            // Restore state if service is already running
            val startTime = xrayService?.connectionStartTime ?: 0L
            if (startTime > 0L) {
                runOnUiThread {
                    updateUI(connected = true)
                    startTimer(startTime)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            xrayService = null
            serviceBound = false
        }
    }

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleConfigImport(uri) }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkVpnPermissionAndStart()
        } else {
            Toast.makeText(this, "Включите уведомления, чтобы видеть статус VPN", Toast.LENGTH_LONG).show()
            checkVpnPermissionAndStart()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "Необходимы права VPN", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Migrate old config if exists
        migrateOldConfig()

        // Load active configuration
        val activeName = getActiveConfigName()
        if (activeName != null) {
            val file = File(File(filesDir, "configs"), activeName)
            if (file.exists()) {
                configFile = file
                binding.tvConfigName.text = activeName
            } else {
                binding.tvConfigName.text = "Выбрать конфиг"
                configFile = null
            }
        } else {
            binding.tvConfigName.text = "Выбрать конфиг"
            configFile = null
        }

        binding.btnPower.onClickListener = { toggleConnection() }

        binding.configSection.setOnClickListener {
            showProfilesDialog()
        }

        binding.tvImportConfig.setOnClickListener {
            showProfilesDialog()
        }

        binding.btnTelegramChannel.setOnClickListener {
            openUrl("https://t.me/darkbitVPN")
        }

        binding.btnTelegramBot.setOnClickListener {
            openUrl("https://t.me/darkbitVPN_bot")
        }

        // Setup Bottom Navigation
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.layoutHome.visibility = View.VISIBLE
                    binding.layoutLogs.visibility = View.GONE
                    binding.layoutAbout.visibility = View.GONE
                    stopLogAutoRefresh()
                    true
                }
                R.id.nav_logs -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutLogs.visibility = View.VISIBLE
                    binding.layoutAbout.visibility = View.GONE
                    startLogAutoRefresh()
                    true
                }
                R.id.nav_about -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutLogs.visibility = View.GONE
                    binding.layoutAbout.visibility = View.VISIBLE
                    stopLogAutoRefresh()
                    true
                }
                else -> false
            }
        }

        // Setup Logs filters and actions
        binding.rgLogFilters.setOnCheckedChangeListener { _, _ -> updateLogsView() }
        
        binding.btnLogClear.setOnClickListener {
            val logFile = File(File(filesDir, "logs"), "xray.log")
            if (logFile.exists()) {
                logFile.delete()
            }
            binding.tvLogContent.text = "Логи пусты..."
            Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
        }

        binding.btnLogCopy.setOnClickListener {
            val logText = binding.tvLogContent.text.toString()
            if (logText.isEmpty() || logText == "Логи пусты...") {
                Toast.makeText(this, "Логи пусты", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Xray Logs", logText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка копирования: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        updateUI(connected = false)
        checkPrivacyDisclosure()
        checkFirstLaunchGreeting()
        checkForUpdates()

        // Set app version dynamically
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "Версия ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = "Версия 1.0"
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, XrayService::class.java)
        bindService(intent, serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        stopLogAutoRefresh()
    }

    private fun startLogAutoRefresh() {
        stopLogAutoRefresh()
        logRefreshJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateLogsView()
                delay(1000)
            }
        }
    }

    private fun stopLogAutoRefresh() {
        logRefreshJob?.cancel()
        logRefreshJob = null
    }

    private fun updateLogsView() {
        val logFile = File(File(filesDir, "logs"), "xray.log")
        if (logFile.exists() && logFile.length() > 0) {
            try {
                val logsText = logFile.readText()
                val lines = logsText.lines()
                val isErrorFilter = binding.rbLogError.isChecked

                val filtered = if (isErrorFilter) {
                    lines.filter { it.contains("Warning", ignoreCase = true) || 
                                   it.contains("Error", ignoreCase = true) || 
                                   it.contains("E/", ignoreCase = false) || 
                                   it.contains("W/", ignoreCase = false) }
                        .joinToString("\n")
                } else {
                    logsText
                }

                if (filtered.isBlank() && isErrorFilter) {
                    binding.tvLogContent.text = "Ошибок не найдено..."
                } else {
                    // Only auto-scroll if user is at the bottom
                    val isAtBottom = binding.scrollLogs.scrollY >= (binding.scrollLogs.getChildAt(0).measuredHeight - binding.scrollLogs.measuredHeight - 50)
                    binding.tvLogContent.text = filtered
                    
                    if (isAtBottom) {
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            } catch (e: Exception) {
                binding.tvLogContent.text = "Ошибка чтения логов: ${e.message}"
            }
        } else {
            binding.tvLogContent.text = "Логи пусты..."
        }
    }

    private fun toggleConnection() {
        if (configFile == null) {
            Toast.makeText(this, "Сначала импортируйте конфиг", Toast.LENGTH_SHORT).show()
            shakeConfigSection()
            return
        }

        if (isConnected) {
            stopVpn()
        } else {
            checkNotificationPermissionAndStart()
        }
    }

    private fun checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkVpnPermissionAndStart()
    }

    private fun checkVpnPermissionAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        updateUI(connecting = true)
        val intent = Intent(this, XrayService::class.java).apply {
            action = XrayService.ACTION_START
            putExtra("config_path", configFile!!.absolutePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(Intent(this, XrayService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    private fun stopVpn() {
        stopTimer()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            xrayService = null
        }
        val intent = Intent(this, XrayService::class.java).apply {
            action = XrayService.ACTION_STOP
        }
        startService(intent)
        updateUI(connected = false, connecting = false)
    }

    private fun updateUI(connected: Boolean = false, connecting: Boolean = false) {
        isConnected = connected
        when {
            connecting -> {
                binding.btnPower.state = PowerButton.State.CONNECTING
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "ПОДКЛЮЧЕНИЕ..."
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
                binding.tvTimer.visibility = View.GONE
                binding.speedSection.visibility = View.GONE
            }
            connected -> {
                binding.btnPower.state = PowerButton.State.CONNECTED
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "ПОДКЛЮЧЕНО"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))

                // Show timer
                binding.tvTimer.visibility = View.VISIBLE
                val startTime = xrayService?.connectionStartTime ?: System.currentTimeMillis()
                startTimer(startTime)

                // Show speed section with fade-in
                if (binding.speedSection.visibility != View.VISIBLE) {
                    binding.speedSection.apply {
                        alpha = 0f
                        visibility = View.VISIBLE
                        animate().alpha(1f).setDuration(400).start()
                    }
                }
            }
            else -> {
                binding.btnPower.state = PowerButton.State.DISCONNECTED
                binding.tvStatus.visibility = View.GONE
                binding.tvTimer.visibility = View.GONE
                stopTimer()

                // Hide speed section
                if (binding.speedSection.visibility == View.VISIBLE) {
                    binding.speedSection.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { binding.speedSection.visibility = View.GONE }
                        .start()
                }
                binding.tvDownloadSpeed.text = "0 B/s"
                binding.tvUploadSpeed.text = "0 B/s"
            }
        }
    }

    private fun updateSpeed(downBytesPerSec: Long, upBytesPerSec: Long) {
        binding.tvDownloadSpeed.text = formatSpeed(downBytesPerSec)
        binding.tvUploadSpeed.text = formatSpeed(upBytesPerSec)
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024L * 1024L -> String.format("%.1f KB/s", bytesPerSecond / 1024f)
            bytesPerSecond < 1024L * 1024L * 1024L -> String.format("%.1f MB/s", bytesPerSecond / (1024f * 1024f))
            else -> String.format("%.2f GB/s", bytesPerSecond / (1024f * 1024f * 1024f))
        }
    }

    private fun startTimer(startTimeMillis: Long) {
        stopTimer()
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
                val hours = elapsed / 3600
                val minutes = (elapsed % 3600) / 60
                val seconds = elapsed % 60
                binding.tvTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        binding.tvTimer.text = "00:00:00"
    }

    private fun handleConfigImport(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "config.json"
            
            // Ensure configs dir exists
            val configsDir = File(filesDir, "configs")
            if (!configsDir.exists()) {
                configsDir.mkdirs()
            }
            
            val destFile = File(configsDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Set as active
            setActiveConfig(fileName)
            Toast.makeText(this, "Конфиг '$fileName' добавлен", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun shakeConfigSection() {
        val animator = ValueAnimator.ofFloat(0f, 10f, -10f, 8f, -8f, 5f, -5f, 0f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                binding.configSection.translationX = it.animatedValue as Float
            }
        }
        animator.start()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPrivacyDisclosure() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val accepted = prefs.getBoolean("disclosure_accepted", false)
        if (!accepted) {
            showDisclosureDialog()
        }
    }

    private fun showDisclosureDialog() {
        val message = """
            Для работы приложения необходимо предоставить согласие на использование службы VPN.
            
            S3 Bypass использует службу Android VpnService для безопасного туннелирования сетевого трафика. Это шифрует ваши данные и защищает вашу конфиденциальность в сети.
            
            Важная информация:
            • Приложение не собирает, не анализирует и не передает третьим лицам ваш трафик или личные данные.
            • Вы можете разорвать соединение в любой момент внутри приложения.
            
            Полная политика конфиденциальности доступна по ссылке:
            https://telegra.ph/Darkbit-Bypass-Privacy-Policy-06-13
            
            Нажимая «Принять», вы подтверждаете свое согласие и разрешаете приложению запускать службу VPN.
        """.trimIndent()

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Соглашение о конфиденциальности")
            .setMessage(message)
            .setPositiveButton("Принять") { d, _ ->
                getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("disclosure_accepted", true)
                    .apply()
                d.dismiss()
            }
            .setNegativeButton("Выйти") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()

        // Make the link clickable
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    private fun checkFirstLaunchGreeting() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val greetingShown = prefs.getBoolean("greeting_shown", false)
        if (!greetingShown) {
            showGreetingDialog()
        }
    }

    private fun showGreetingDialog() {
        val message = """
            Добро пожаловать в S3 Bypass!
            
            Для работы приложения вам понадобятся конфигурационные профили. 
            
            Инструкции по настройке и получению конфигураций можно найти в нашем официальном Telegram-боте.
        """.trimIndent()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Настройка соединения")
            .setMessage(message)
            .setPositiveButton("Открыть Telegram") { dialog, _ ->
                getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("greeting_shown", true)
                    .apply()
                dialog.dismiss()
                openUrl("https://t.me/darkbitVPN_bot")
            }
            .setNegativeButton("Позже") { dialog, _ ->
                getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("greeting_shown", true)
                    .apply()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun triggerConfigImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        importConfigLauncher.launch(intent)
    }

    private fun showProfilesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_profiles, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutProfilesContainer)
        val btnImport = dialogView.findViewById<View>(R.id.btnDialogImport)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        // Make the system bottom sheet container transparent so our custom rounded corners show
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundResource(android.R.color.transparent)
        }

        populateProfiles(container, dialog)

        btnImport.setOnClickListener {
            dialog.dismiss()
            triggerConfigImport()
        }

        dialog.show()
    }

    private fun populateProfiles(container: LinearLayout, dialog: android.app.Dialog) {
        container.removeAllViews()
        val configsDir = File(filesDir, "configs")
        if (!configsDir.exists()) {
            configsDir.mkdirs()
        }

        val files = configsDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()

        if (files.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Нет импортированных профилей"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            container.addView(emptyText)
            return
        }

        val activeName = getActiveConfigName()

        files.forEach { file ->
            val itemView = layoutInflater.inflate(R.layout.item_profile, container, false)
            val rbSelected = itemView.findViewById<RadioButton>(R.id.rbSelected)
            val tvProfileName = itemView.findViewById<TextView>(R.id.tvProfileName)
            val btnDelete = itemView.findViewById<View>(R.id.btnDeleteProfile)

            val isActive = (file.name == activeName)
            itemView.isSelected = isActive
            rbSelected.isChecked = isActive
            tvProfileName.text = file.name

            val selectListener = View.OnClickListener {
                if (isConnected) {
                    Toast.makeText(this@MainActivity, "Сначала отключите VPN", Toast.LENGTH_SHORT).show()
                } else {
                    setActiveConfig(file.name)
                    dialog.dismiss()
                }
            }
            rbSelected.setOnClickListener(selectListener)
            tvProfileName.setOnClickListener(selectListener)
            itemView.setOnClickListener(selectListener)

            btnDelete.setOnClickListener {
                if (isConnected && file.name == activeName) {
                    Toast.makeText(this@MainActivity, "Сначала отключите VPN", Toast.LENGTH_SHORT).show()
                } else if (file.name == activeName) {
                    Toast.makeText(this@MainActivity, "Нельзя удалить активный профиль", Toast.LENGTH_SHORT).show()
                } else {
                    file.delete()
                    Toast.makeText(this@MainActivity, "Профиль удален", Toast.LENGTH_SHORT).show()
                    populateProfiles(container, dialog)
                }
            }

            container.addView(itemView)
        }
    }

    private fun getActiveConfigName(): String? {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        var name = prefs.getString("active_config_name", null)

        if (name == null) {
            val configsDir = File(filesDir, "configs")
            if (configsDir.exists()) {
                val files = configsDir.listFiles { _, fName -> fName.endsWith(".json") }
                if (!files.isNullOrEmpty()) {
                    name = files[0].name
                    prefs.edit().putString("active_config_name", name).apply()
                }
            }
        }
        return name
    }

    private fun setActiveConfig(fileName: String) {
        val file = File(File(filesDir, "configs"), fileName)
        if (file.exists()) {
            getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                .edit()
                .putString("active_config_name", fileName)
                .apply()
            configFile = file
            binding.tvConfigName.text = fileName
        }
    }

    private fun migrateOldConfig() {
        val oldConfig = File(filesDir, "config.json")
        val configsDir = File(filesDir, "configs")
        if (!configsDir.exists()) {
            configsDir.mkdirs()
        }
        if (oldConfig.exists()) {
            val newFile = File(configsDir, "config.json")
            if (!newFile.exists()) {
                oldConfig.renameTo(newFile)
            } else {
                oldConfig.delete()
            }
        }
    }

    private fun checkForUpdates() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("disclosure_accepted", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                val url = java.net.URL("https://api.github.com/repos/SpaceNeuroX/s3-bypass/releases/latest")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "S3-Bypass-Android-App")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val tagName = json.getString("tag_name")
                    val htmlUrl = json.getString("html_url")

                    if (isNewerVersion(currentVersion, tagName)) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(tagName, htmlUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to check for updates", e)
            }
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.replace(Regex("[^0-9.]"), "")
        val cleanLatest = latest.replace(Regex("[^0-9.]"), "")
        val currParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val lateParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val length = maxOf(currParts.size, lateParts.size)
        for (i in 0 until length) {
            val currVal = currParts.getOrElse(i) { 0 }
            val lateVal = lateParts.getOrElse(i) { 0 }
            if (lateVal > currVal) return true
            if (currVal > lateVal) return false
        }
        return false
    }

    private fun showUpdateDialog(newVersion: String, downloadUrl: String) {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Доступно обновление")
            .setMessage("Доступна новая версия приложения ($newVersion). Хотите перейти на страницу релиза и скачать обновление?")
            .setPositiveButton("Скачать") { dialog, _ ->
                openUrl(downloadUrl)
                dialog.dismiss()
            }
            .setNegativeButton("Позже") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
