package com.darkbit.bypass

import android.animation.ValueAnimator
import android.app.Activity
import android.content.*
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import android.text.InputType
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.darkbit.bypass.databinding.ActivityMainBinding
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var xrayService: XrayService? = null
    private var isConnected = false
    private var configFile: File? = null
    private var serviceBound = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private var logRefreshJob: Job? = null
    private var draggedProfileView: View? = null
    private lateinit var logsAdapter: LogsAdapter
    private var currentLogEntries: List<LogEntry> = emptyList()

    // Split Tunneling variables
    private var appsAdapter: AppsAdapter? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var appsLoadJob: Job? = null
    private var cachedAppsList: List<AppInfoItem> = emptyList()

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
            startConnection()
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

        setupConnectionMode()
        setupProxySettings()

        binding.layoutProxyInfo.setOnClickListener {
            copyProxyAddress()
        }

        binding.btnImportConfig.setOnClickListener {
            triggerConfigImport()
        }

        binding.btnDeployServer.setOnClickListener {
            val dialog = DeployDialogFragment().apply {
                onDeploymentSuccess = {
                    runOnUiThread {
                        populateProfiles()
                    }
                }
            }
            dialog.show(supportFragmentManager, "deploy_dialog")
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
                    binding.layoutProfiles.visibility = View.GONE
                    binding.layoutApps.visibility = View.GONE
                    binding.layoutLogs.visibility = View.GONE
                    binding.layoutAbout.visibility = View.GONE
                    stopLogAutoRefresh()
                    true
                }
                R.id.nav_profiles -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutProfiles.visibility = View.VISIBLE
                    binding.layoutApps.visibility = View.GONE
                    binding.layoutLogs.visibility = View.GONE
                    binding.layoutAbout.visibility = View.GONE
                    populateProfiles()
                    stopLogAutoRefresh()
                    true
                }
                R.id.nav_apps -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutProfiles.visibility = View.GONE
                    binding.layoutApps.visibility = View.VISIBLE
                    binding.layoutLogs.visibility = View.GONE
                    binding.layoutAbout.visibility = View.GONE
                    loadInstalledApps()
                    stopLogAutoRefresh()
                    true
                }
                R.id.nav_logs -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutProfiles.visibility = View.GONE
                    binding.layoutApps.visibility = View.GONE
                    binding.layoutLogs.visibility = View.VISIBLE
                    binding.layoutAbout.visibility = View.GONE
                    startLogAutoRefresh()
                    true
                }
                R.id.nav_about -> {
                    binding.layoutHome.visibility = View.GONE
                    binding.layoutProfiles.visibility = View.GONE
                    binding.layoutApps.visibility = View.GONE
                    binding.layoutLogs.visibility = View.GONE
                    binding.layoutAbout.visibility = View.VISIBLE
                    stopLogAutoRefresh()
                    true
                }
                else -> false
            }
        }

        // Setup Logs filters and actions
        logsAdapter = LogsAdapter()
        binding.rvLogsSimple.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvLogsSimple.adapter = logsAdapter

        binding.rgLogMode.setOnCheckedChangeListener { _, checkedId ->
            val isDetailed = checkedId == R.id.rbLogDetailed
            binding.rvLogsSimple.visibility = if (isDetailed) View.GONE else View.VISIBLE
            binding.scrollLogs.visibility = if (isDetailed) View.VISIBLE else View.GONE
            binding.tvLogsSubtitle.text = if (isDetailed) {
                "Техническая информация для диагностики"
            } else {
                "Понятные сообщения о работе VPN"
            }
            updateLogsView()
        }

        binding.rgLogFilters.setOnCheckedChangeListener { _, _ -> updateLogsView() }
        
        binding.btnLogClear.setOnClickListener {
            val logFile = File(File(filesDir, "logs"), "xray.log")
            if (logFile.exists()) {
                logFile.delete()
            }
            currentLogEntries = emptyList()
            logsAdapter.update(emptyList())
            binding.tvLogContent.text = "Логи пусты..."
            binding.tvLogsEmpty.visibility = View.VISIBLE
            Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
        }

        binding.btnLogCopy.setOnClickListener {
            val logText = if (binding.rbLogDetailed.isChecked) {
                binding.tvLogContent.text.toString()
            } else {
                if (currentLogEntries.isEmpty()) ""
                else LogFormatter.formatUserFriendlyForCopy(currentLogEntries)
            }
            if (logText.isEmpty() || logText == "Логи пусты..." || logText == "Событий пока нет") {
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
        handleExternalConfigIntent(intent)

        // Set app version dynamically
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "Версия ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = "Версия 1.0"
        }

        // Initialize Split Tunneling UI and settings
        setupSplitTunneling()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalConfigIntent(intent)
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
        try {
            mainScope.cancel()
        } catch (e: Exception) {}
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
        val isErrorFilter = binding.rbLogError.isChecked
        val isDetailed = binding.rbLogDetailed.isChecked

        if (!logFile.exists() || logFile.length() == 0L) {
            currentLogEntries = emptyList()
            logsAdapter.update(emptyList())
            binding.tvLogContent.text = "Логи пусты..."
            binding.tvLogsEmpty.visibility = View.VISIBLE
            binding.tvLogsEmpty.text = "Событий пока нет"
            return
        }

        try {
            val lines = logFile.readText().lines()
            if (isDetailed) {
                val filtered = LogFormatter.formatDetailed(lines, isErrorFilter)
                if (filtered.isBlank() && isErrorFilter) {
                    binding.tvLogContent.text = "Ошибок не найдено..."
                    binding.tvLogsEmpty.visibility = View.GONE
                } else {
                    val isAtBottom = binding.scrollLogs.scrollY >= (
                        binding.scrollLogs.getChildAt(0).measuredHeight -
                            binding.scrollLogs.measuredHeight - 50
                        )
                    binding.tvLogContent.text = filtered.ifBlank { "Логи пусты..." }
                    binding.tvLogsEmpty.visibility = View.GONE
                    if (isAtBottom) {
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            } else {
                val entries = LogFormatter.parseUserFriendly(lines, isErrorFilter)
                currentLogEntries = entries
                logsAdapter.update(entries)

                if (entries.isEmpty()) {
                    binding.tvLogsEmpty.visibility = View.VISIBLE
                    binding.tvLogsEmpty.text = if (isErrorFilter) {
                        "Ошибок не найдено"
                    } else {
                        "Событий пока нет"
                    }
                } else {
                    binding.tvLogsEmpty.visibility = View.GONE
                    val layoutManager = binding.rvLogsSimple.layoutManager
                        as androidx.recyclerview.widget.LinearLayoutManager
                    val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
                    val isAtBottom = lastVisible >= entries.size - 2
                    if (isAtBottom) {
                        binding.rvLogsSimple.post {
                            binding.rvLogsSimple.scrollToPosition(entries.size - 1)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            currentLogEntries = emptyList()
            logsAdapter.update(emptyList())
            binding.tvLogContent.text = "Ошибка чтения логов: ${e.message}"
            binding.tvLogsEmpty.visibility = View.GONE
        }
    }

    private val connectionModeChangeListener =
        android.widget.RadioGroup.OnCheckedChangeListener { _, checkedId ->
            if (isConnected) {
                Toast.makeText(this, "Сначала отключитесь, чтобы сменить режим", Toast.LENGTH_SHORT).show()
                applyConnectionModeSelection(readSavedConnectionMode())
                return@OnCheckedChangeListener
            }
            onConnectionModeChanged(checkedId)
        }

    private fun setupConnectionMode() {
        binding.rgConnectionMode.setOnCheckedChangeListener(connectionModeChangeListener)
        applyConnectionModeSelection(readSavedConnectionMode())
    }

    private fun readSavedConnectionMode(): String {
        return getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .getString("connection_mode", XrayService.MODE_VPN) ?: XrayService.MODE_VPN
    }

    private fun applyConnectionModeSelection(mode: String) {
        binding.rgConnectionMode.setOnCheckedChangeListener(null)
        if (mode == XrayService.MODE_PROXY) {
            binding.rbModeProxy.isChecked = true
        } else {
            binding.rbModeVpn.isChecked = true
        }
        binding.rgConnectionMode.setOnCheckedChangeListener(connectionModeChangeListener)
        updateProxySettingsVisibility()
    }

    private fun restoreConnectionModeSelection() {
        applyConnectionModeSelection(readSavedConnectionMode())
    }

    private fun setupProxySettings() {
        updateProxyPreview()
        binding.layoutProxySettings.setOnClickListener {
            if (isConnected) {
                Toast.makeText(this, "Сначала отключитесь, чтобы изменить порт", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showProxyPortDialog()
        }
        updateProxySettingsVisibility()
    }

    private fun readProxyPort(): Int {
        return getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .getInt(XrayService.PREF_PROXY_PORT, XrayService.DEFAULT_PROXY_PORT)
    }

    private fun saveProxyPort(port: Int): Boolean {
        if (port !in 1024..65535) {
            Toast.makeText(this, "Порт должен быть от 1024 до 65535", Toast.LENGTH_SHORT).show()
            return false
        }
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putInt(XrayService.PREF_PROXY_PORT, port)
            .apply()
        updateProxyPreview()
        return true
    }

    private fun updateProxyPreview() {
        binding.tvProxyPreview.text = formatProxyAddress()
    }

    private fun updateProxySettingsVisibility() {
        binding.layoutProxySettings.visibility =
            if (isProxyMode() && !isConnected) View.VISIBLE else View.GONE
        if (isProxyMode()) {
            updateProxyPreview()
        }
    }

    private fun showProxyPortDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_proxy_port, null)
        val errorView = dialogView.findViewById<TextView>(R.id.tvPortError)
        val input = dialogView.findViewById<android.widget.EditText>(R.id.etProxyPort).apply {
            setText(readProxyPort().toString())
            setSelection(text?.length ?: 0)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Порт прокси")
            .setView(dialogView)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val port = input.text?.toString()?.trim()?.toIntOrNull()
                if (port == null) {
                    errorView.text = "Введите число"
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (!saveProxyPort(port)) {
                    errorView.text = "Порт должен быть от 1024 до 65535"
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                errorView.visibility = View.GONE
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun formatProxyAddress(): String {
        val endpoint = xrayService?.proxyEndpoint
        if (isConnected && !endpoint.isNullOrBlank()) {
            return endpoint
        }
        return "127.0.0.1:${readProxyPort()}"
    }

    private fun onConnectionModeChanged(checkedId: Int) {
        val mode = if (checkedId == R.id.rbModeProxy) {
            XrayService.MODE_PROXY
        } else {
            XrayService.MODE_VPN
        }
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putString("connection_mode", mode)
            .apply()
        binding.layoutProxyInfo.visibility = View.GONE
        updateProxySettingsVisibility()
    }

    private fun isProxyMode(): Boolean = binding.rbModeProxy.isChecked

    private fun copyProxyAddress() {
        val address = formatProxyAddress()
        if (address.isBlank()) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SOCKS Proxy", address))
            Toast.makeText(this, "Адрес скопирован: $address", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка копирования: ${e.message}", Toast.LENGTH_SHORT).show()
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
        if (isProxyMode()) {
            startConnection()
        } else {
            checkVpnPermissionAndStart()
        }
    }

    private fun checkVpnPermissionAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startConnection()
        }
    }

    private fun startConnection() {
        // Clear logs instantly
        val logFile = File(File(filesDir, "logs"), "xray.log")
        if (logFile.exists()) logFile.delete()
        currentLogEntries = emptyList()
        logsAdapter.update(emptyList())
        binding.tvLogContent.text = "Подключение..."
        binding.tvLogsEmpty.visibility = View.VISIBLE
        binding.tvLogsEmpty.text = "Подключение…"
        
        updateUI(connecting = true)
        val intent = Intent(this, XrayService::class.java).apply {
            action = XrayService.ACTION_START
            putExtra("config_path", configFile!!.absolutePath)
            putExtra(
                XrayService.EXTRA_CONNECTION_MODE,
                if (isProxyMode()) XrayService.MODE_PROXY else XrayService.MODE_VPN
            )
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
                binding.tvStatus.text = if (isProxyMode()) "ЗАПУСК ПРОКСИ..." else "ПОДКЛЮЧЕНИЕ..."
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
                binding.tvTimer.visibility = View.GONE
                binding.speedSection.visibility = View.GONE
                binding.layoutProxyInfo.visibility = View.GONE
                binding.rgConnectionMode.isEnabled = false
                updateProxySettingsVisibility()
            }
            connected -> {
                binding.btnPower.state = PowerButton.State.CONNECTED
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = if (isProxyMode()) "ПРОКСИ АКТИВЕН" else "ПОДКЛЮЧЕНО"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                binding.rgConnectionMode.isEnabled = false
                updateProxySettingsVisibility()

                binding.tvTimer.visibility = View.VISIBLE
                val startTime = xrayService?.connectionStartTime ?: System.currentTimeMillis()
                startTimer(startTime)

                if (isProxyMode()) {
                    binding.speedSection.visibility = View.GONE
                    binding.tvProxyAddress.text = formatProxyAddress()
                    binding.layoutProxyInfo.visibility = View.VISIBLE
                } else {
                    binding.layoutProxyInfo.visibility = View.GONE
                    if (binding.speedSection.visibility != View.VISIBLE) {
                        binding.speedSection.apply {
                            alpha = 0f
                            visibility = View.VISIBLE
                            animate().alpha(1f).setDuration(400).start()
                        }
                    }
                }
            }
            else -> {
                binding.btnPower.state = PowerButton.State.DISCONNECTED
                binding.tvStatus.visibility = View.GONE
                binding.tvTimer.visibility = View.GONE
                binding.layoutProxyInfo.visibility = View.GONE
                binding.rgConnectionMode.isEnabled = true
                updateProxySettingsVisibility()
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
            val fileName = normalizeConfigFileName(getFileName(uri))
            
            // Ensure configs dir exists
            val configsDir = File(filesDir, "configs")
            if (!configsDir.exists()) {
                configsDir.mkdirs()
            }
            
            val destFile = nextAvailableConfigFile(configsDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("не удалось открыть файл")

            if (!isJsonConfigFile(destFile)) {
                destFile.delete()
                throw IllegalArgumentException("выбранный файл не похож на JSON")
            }

            // Set as active
            setActiveConfig(destFile.name)
            appendProfileOrder(destFile.name)
            Toast.makeText(this, "Конфиг '${destFile.name}' добавлен", Toast.LENGTH_SHORT).show()
            populateProfiles()

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleExternalConfigIntent(sourceIntent: Intent?) {
        val uri = when (sourceIntent?.action) {
            Intent.ACTION_VIEW -> sourceIntent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    sourceIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        } ?: return

        handleConfigImport(uri)
        binding.bottomNavigation.selectedItemId = R.id.nav_profiles
    }

    private fun normalizeConfigFileName(rawName: String?): String {
        val cleanName = rawName
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "config.json"

        val safeName = cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (safeName.endsWith(".json", ignoreCase = true)) {
            safeName
        } else {
            "$safeName.json"
        }
    }

    private fun nextAvailableConfigFile(configsDir: File, preferredName: String): File {
        val baseName = if (preferredName.endsWith(".json", ignoreCase = true)) {
            preferredName.dropLast(5)
        } else {
            preferredName
        }
        var candidate = File(configsDir, preferredName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(configsDir, "$baseName ($index).json")
            index++
        }
        return candidate
    }

    private fun isJsonConfigFile(file: File): Boolean {
        return try {
            val value = org.json.JSONTokener(file.readText()).nextValue()
            value is org.json.JSONObject || value is org.json.JSONArray
        } catch (e: Exception) {
            false
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
                binding.    tvConfigName.translationX = it.animatedValue as Float
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

    private fun populateProfiles() {
        val container = binding.layoutProfilesContainer
        container.removeAllViews()
        setupProfileDragTarget(container)
        val configsDir = File(filesDir, "configs")
        if (!configsDir.exists()) {
            configsDir.mkdirs()
        }

        val files = sortedConfigFiles(configsDir)

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
            val tvProfileName = itemView.findViewById<TextView>(R.id.tvProfileName)
            val tvProfileStatus = itemView.findViewById<TextView>(R.id.tvProfileStatus)
            val btnDrag = itemView.findViewById<View>(R.id.btnDragProfile)
            val btnRename = itemView.findViewById<View>(R.id.btnRenameProfile)
            val btnShare = itemView.findViewById<View>(R.id.btnShareProfile)
            val btnDelete = itemView.findViewById<View>(R.id.btnDeleteProfile)

            val isActive = (file.name == activeName)
            itemView.tag = file.name
            itemView.isSelected = isActive
            tvProfileName.text = file.name
            tvProfileStatus.visibility = if (isActive) View.VISIBLE else View.GONE

            val selectListener = View.OnClickListener {
                if (isConnected) {
                    Toast.makeText(this@MainActivity, "Сначала отключите VPN", Toast.LENGTH_SHORT).show()
                } else {
                    setActiveConfig(file.name)
                    populateProfiles()
                }
            }
            tvProfileName.setOnClickListener(selectListener)
            itemView.setOnClickListener(selectListener)

            btnDrag.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    startProfileDrag(itemView)
                    true
                } else {
                    false
                }
            }

            btnRename.setOnClickListener {
                showRenameProfileDialog(file)
            }

            btnShare.setOnClickListener {
                shareProfile(file)
            }

            btnDelete.setOnClickListener {
                if (isConnected && file.name == activeName) {
                    Toast.makeText(this@MainActivity, "Сначала отключите VPN", Toast.LENGTH_SHORT).show()
                } else if (file.name == activeName) {
                    Toast.makeText(this@MainActivity, "Нельзя удалить активный профиль", Toast.LENGTH_SHORT).show()
                } else {
                    file.delete()
                    removeProfileFromOrder(file.name)
                    Toast.makeText(this@MainActivity, "Профиль удален", Toast.LENGTH_SHORT).show()
                    populateProfiles()
                }
            }

            container.addView(itemView)
        }
    }

    private fun sortedConfigFiles(configsDir: File): List<File> {
        val files = configsDir.listFiles { _, name -> name.endsWith(".json") }?.toList() ?: emptyList()
        val order = getProfileOrder().filter { orderedName -> files.any { it.name == orderedName } }
        val knownNames = order.toSet()
        val newNames = files
            .map { it.name }
            .filterNot { it in knownNames }
            .sorted()

        val names = order + newNames
        if (names != getProfileOrder()) {
            saveProfileOrder(names)
        }
        return names.mapNotNull { name -> files.firstOrNull { it.name == name } }
    }

    private fun setupProfileDragTarget(container: LinearLayout) {
        container.setOnDragListener { _, event ->
            val dragged = draggedProfileView ?: return@setOnDragListener true
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    dragged.alpha = 0.45f
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    moveDraggedProfile(container, dragged, event.y)
                    true
                }
                DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                    dragged.alpha = 1f
                    saveCurrentProfileOrder()
                    draggedProfileView = null
                    true
                }
                else -> true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startProfileDrag(itemView: View) {
        draggedProfileView = itemView
        val shadow = View.DragShadowBuilder(itemView)
        val dragData = ClipData.newPlainText("profile", itemView.tag as? String ?: "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            itemView.startDragAndDrop(dragData, shadow, itemView, 0)
        } else {
            itemView.startDrag(dragData, shadow, itemView, 0)
        }
    }

    private fun moveDraggedProfile(container: LinearLayout, dragged: View, y: Float) {
        val currentIndex = container.indexOfChild(dragged)
        if (currentIndex == -1) return

        var targetIndex = container.childCount - 1
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child == dragged) continue
            val childMid = child.top + child.height / 2f
            if (y < childMid) {
                targetIndex = i
                break
            }
        }

        if (targetIndex != currentIndex) {
            container.removeView(dragged)
            val boundedIndex = targetIndex.coerceIn(0, container.childCount)
            container.addView(dragged, boundedIndex)
        }
    }

    private fun showRenameProfileDialog(file: File) {
        if (isConnected && file.name == getActiveConfigName()) {
            Toast.makeText(this, "Сначала отключите VPN", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_rename_profile, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.tilProfileName)
        val input = dialogView.findViewById<TextInputEditText>(R.id.etProfileName).apply {
            setText(file.name.removeSuffix(".json"))
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Переименовать профиль")
            .setView(dialogView)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .show()

        input.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            inputLayout.error = null
            val error = validateProfileName(file, input.text.toString())
            if (error != null) {
                inputLayout.error = error
                return@setOnClickListener
            }
            if (renameProfile(file, input.text.toString())) {
                dialog.dismiss()
            }
        }
    }

    private fun validateProfileName(file: File, rawName: String): String? {
        val cleanBaseName = sanitizeProfileBaseName(rawName)
        if (cleanBaseName.isBlank()) {
            return "Введите название профиля"
        }

        val newName = "$cleanBaseName.json"
        if (newName == file.name) {
            return null
        }

        val parentDir = file.parentFile ?: return "Не удалось открыть папку профилей"
        if (File(parentDir, newName).exists()) {
            return "Профиль с таким названием уже есть"
        }

        return null
    }

    private fun renameProfile(file: File, rawName: String): Boolean {
        val cleanBaseName = sanitizeProfileBaseName(rawName)
        if (cleanBaseName.isBlank()) return false

        val newName = "$cleanBaseName.json"
        if (newName == file.name) return true

        val parentDir = file.parentFile ?: return false
        val newFile = File(parentDir, newName)
        if (newFile.exists()) return false

        return if (file.renameTo(newFile)) {
            replaceProfileInOrder(file.name, newName)
            if (file.name == getActiveConfigName()) {
                setActiveConfig(newName)
            }
            Toast.makeText(this, "Профиль переименован", Toast.LENGTH_SHORT).show()
            populateProfiles()
            true
        } else {
            Toast.makeText(this, "Не удалось переименовать профиль", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun sanitizeProfileBaseName(rawName: String): String {
        return rawName
            .trim()
            .removeSuffix(".json")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun shareProfile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться профилем ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка при экспорте: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getProfileOrder(): List<String> {
        val order = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .getString("profile_order", "")
            .orEmpty()
        return order.split("|").filter { it.isNotBlank() }
    }

    private fun saveProfileOrder(names: List<String>) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putString("profile_order", names.joinToString("|"))
            .apply()
    }

    private fun saveCurrentProfileOrder() {
        val container = binding.layoutProfilesContainer
        val names = (0 until container.childCount).mapNotNull { index ->
            container.getChildAt(index).tag as? String
        }
        saveProfileOrder(names)
    }

    private fun appendProfileOrder(fileName: String) {
        val order = getProfileOrder()
        if (fileName !in order) {
            saveProfileOrder(order + fileName)
        }
    }

    private fun removeProfileFromOrder(fileName: String) {
        saveProfileOrder(getProfileOrder().filterNot { it == fileName })
    }

    private fun replaceProfileInOrder(oldName: String, newName: String) {
        val updated = getProfileOrder().map { if (it == oldName) newName else it }
        saveProfileOrder(updated)
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

    private fun setupSplitTunneling() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val splitEnabled = prefs.getBoolean("split_tunneling_enabled", false)
        val splitMode = prefs.getString("split_tunneling_mode", "disallow") ?: "disallow"

        binding.swSplitTunnelEnabled.isChecked = splitEnabled
        if (splitMode == "allow") {
            binding.rbSplitTunnelOnly.isChecked = true
        } else {
            binding.rbSplitTunnelBypass.isChecked = true
        }

        updateAppsControlsState(splitEnabled)

        binding.swSplitTunnelEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("split_tunneling_enabled", isChecked).apply()
            updateAppsControlsState(isChecked)
            appsAdapter?.isEnabled = isChecked
        }

        binding.rgSplitTunnelMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbSplitTunnelOnly) "allow" else "disallow"
            prefs.edit().putString("split_tunneling_mode", mode).apply()
        }

        binding.etAppSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAppsList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.rgAppsFilter.setOnCheckedChangeListener { _, _ ->
            filterAppsList()
        }
    }

    private fun updateAppsControlsState(enabled: Boolean) {
        binding.rgSplitTunnelMode.setEnabled(enabled)
        for (i in 0 until binding.rgSplitTunnelMode.childCount) {
            binding.rgSplitTunnelMode.getChildAt(i).isEnabled = enabled
        }
        binding.etAppSearch.isEnabled = enabled
        binding.rgAppsFilter.setEnabled(enabled)
        for (i in 0 until binding.rgAppsFilter.childCount) {
            binding.rgAppsFilter.getChildAt(i).isEnabled = enabled
        }
        val alpha = if (enabled) 1.0f else 0.5f
        binding.rgSplitTunnelMode.alpha = alpha
        binding.etAppSearch.alpha = alpha
        binding.rgAppsFilter.alpha = alpha
        binding.rvAppsList.alpha = alpha
    }

    private fun loadInstalledApps() {
        if (appsLoadJob?.isActive == true) return

        binding.pbAppsLoading.visibility = View.VISIBLE
        binding.tvNoAppsFound.visibility = View.GONE

        appsLoadJob = mainScope.launch {
            val appList = withContext(Dispatchers.IO) {
                val pm = packageManager
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                val selectedSet = prefs.getStringSet("split_tunneling_packages", null) ?: emptySet()

                val list = ArrayList<AppInfoItem>()
                for (app in installed) {
                    if (app.packageName == packageName) continue

                    val name = app.loadLabel(pm).toString()
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val isSelected = selectedSet.contains(app.packageName)
                    val icon = try { app.loadIcon(pm) } catch (e: Exception) { null }

                    list.add(AppInfoItem(name, app.packageName, icon, isSystem, isSelected))
                }
                list.sortBy { it.name.lowercase() }
                list
            }

            cachedAppsList = appList
            binding.pbAppsLoading.visibility = View.GONE

            val isSplitEnabled = binding.swSplitTunnelEnabled.isChecked

            if (appsAdapter == null) {
                appsAdapter = AppsAdapter(appList) { item, isSelected ->
                    saveAppSelection(item.packageName, isSelected)
                }.apply {
                    isEnabled = isSplitEnabled
                }
                binding.rvAppsList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
                binding.rvAppsList.adapter = appsAdapter
            } else {
                appsAdapter?.isEnabled = isSplitEnabled
                appsAdapter?.updateData(appList)
            }
            filterAppsList()
        }
    }

    private fun saveAppSelection(pkgName: String, isSelected: Boolean) {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val currentSet = prefs.getStringSet("split_tunneling_packages", null) ?: emptySet()
        val newSet = HashSet(currentSet)
        if (isSelected) {
            newSet.add(pkgName)
        } else {
            newSet.remove(pkgName)
        }
        prefs.edit().putStringSet("split_tunneling_packages", newSet).apply()

        if (binding.rgAppsFilter.checkedRadioButtonId == R.id.rbAppsSelected) {
            filterAppsList()
        }
    }

    private fun filterAppsList() {
        val adapter = appsAdapter ?: return
        val searchText = binding.etAppSearch.text.toString().trim().lowercase()
        val filterId = binding.rgAppsFilter.checkedRadioButtonId

        val filtered = cachedAppsList.filter { item ->
            val matchesSearch = item.name.lowercase().contains(searchText) ||
                                item.packageName.lowercase().contains(searchText)
            if (!matchesSearch) return@filter false

            when (filterId) {
                R.id.rbAppsUser -> !item.isSystem
                R.id.rbAppsSystem -> item.isSystem
                R.id.rbAppsSelected -> item.isSelected
                else -> true
            }
        }

        adapter.setFilteredItems(filtered)
        binding.tvNoAppsFound.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
}
