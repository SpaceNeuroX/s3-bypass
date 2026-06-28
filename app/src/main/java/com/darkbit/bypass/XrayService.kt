package com.darkbit.bypass

import android.app.*
import android.content.Intent
import android.net.TrafficStats
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.FileDescriptor
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

class XrayService : VpnService() {

    private var xrayController: Any? = null


    companion object {
        const val TAG = "XrayService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "darkbit_channel"
        const val ACTION_START = "com.darkbit.bypass.START"
        const val ACTION_STOP = "com.darkbit.bypass.STOP"
        const val EXTRA_CONNECTION_MODE = "connection_mode"
        const val MODE_VPN = "vpn"
        const val MODE_PROXY = "proxy"
        const val PREF_PROXY_PORT = "proxy_port"
        const val DEFAULT_PROXY_PORT = 10808
    }

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onStatusChanged: ((Boolean) -> Unit)? = null
    var onSpeedUpdate: ((downBytesPerSec: Long, upBytesPerSec: Long) -> Unit)? = null
    var connectionStartTime: Long = 0L
        private set

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var speedJob: Job? = null
    private var logcatProcess: Process? = null
    private var isProxyMode = false
    var proxyEndpoint: String? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): XrayService = this@XrayService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onRevoke() {
        super.onRevoke()
        writeLog("VPN revoked by system (another VPN started). Shutting down...")
        stopXray()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configPath = intent.getStringExtra("config_path") ?: return START_NOT_STICKY
                isProxyMode = intent.getStringExtra(EXTRA_CONNECTION_MODE) == MODE_PROXY
                startForeground(NOTIFICATION_ID, buildNotification())
                startXray(configPath)
            }
            ACTION_STOP -> {
                cleanup()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startXray(configPath: String) {
        serviceScope.launch {
            try {
                // Clear old logs
                try {
                    val logFile = File(File(filesDir, "logs"), "xray.log")
                    if (logFile.exists()) logFile.delete()
                    Runtime.getRuntime().exec("logcat -c").waitFor() // Clear logcat buffer
                } catch (e: Exception) {}

                writeLog(
                    if (isProxyMode) "Starting proxy mode via AAR..."
                    else "Starting connection sequence via AAR..."
                )
                
                // Start logcat capturing for GoLog
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        logcatProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "-s", "GoLog:V", "nativeloader:V"))
                        logcatProcess?.inputStream?.bufferedReader()?.use { reader ->
                            var line: String? = null
                            while (isActive && reader.readLine().also { line = it } != null) {
                                writeLog("logcat: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Logcat capture failed", e)
                    }
                }

                var tunFd = 0

                if (isProxyMode) {
                    writeLog("Starting local SOCKS proxy (no VPN tunnel)...")
                } else {
                    writeLog("Establishing VPN Tunnel interface...")
                    val builder = Builder()
                        .addAddress("172.19.0.1", 30)
                        .addAddress("fd00::1", 126)
                        .addDnsServer("8.8.8.8")
                        .addDnsServer("2001:4860:4860::8888")
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)
                        .setSession("Darkbit Bypass")
                        .setMtu(1500)

                    val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                    val splitEnabled = prefs.getBoolean("split_tunneling_enabled", false)
                    val splitMode = prefs.getString("split_tunneling_mode", "disallow") ?: "disallow"
                    val splitPackages = prefs.getStringSet("split_tunneling_packages", null) ?: emptySet()

                    if (splitEnabled && splitPackages.isNotEmpty()) {
                        if (splitMode == "allow") {
                            for (pkg in splitPackages) {
                                try {
                                    if (pkg != packageName) {
                                        builder.addAllowedApplication(pkg)
                                    }
                                } catch (e: Exception) {
                                    writeLog("Warning: Failed to add allowed application $pkg: ${e.message}")
                                }
                            }
                        } else {
                            try {
                                builder.addDisallowedApplication(packageName)
                            } catch (e: Exception) {
                                writeLog("Warning: Failed to exclude app from VPN: ${e.message}")
                            }
                            for (pkg in splitPackages) {
                                try {
                                    if (pkg != packageName) {
                                        builder.addDisallowedApplication(pkg)
                                    }
                                } catch (e: Exception) {
                                    writeLog("Warning: Failed to exclude application $pkg: ${e.message}")
                                }
                            }
                        }
                    } else {
                        try {
                            builder.addDisallowedApplication(packageName)
                        } catch (e: Exception) {
                            writeLog("Warning: Failed to exclude app from VPN: ${e.message}")
                        }
                    }

                    vpnInterface?.close()
                    vpnInterface = builder.establish()

                    if (vpnInterface != null) {
                        try {
                            Os.dup2(vpnInterface!!.fileDescriptor, 0)
                            tunFd = vpnInterface!!.fd
                            writeLog("VPN Tunnel successfully configured and bound to system fd 0")
                        } catch (e: Exception) {
                            writeLog("Error: Failed to dup2 tun fd: ${e.message}")
                        }
                    } else {
                        writeLog("Error: Failed to establish VPN interface")
                        stopSelf()
                        return@launch
                    }
                }

                writeLog("Invoking Xray core from AAR library...")
                
                try {
                    val clazz = Class.forName("libv2ray.Libv2ray")
                    clazz.getMethod("initCoreEnv", String::class.java, String::class.java)
                         .invoke(null, filesDir.absolutePath, "")
                    
                    val callbackHandlerClass = Class.forName("libv2ray.CoreCallbackHandler")
                    val proxy = Proxy.newProxyInstance(
                        clazz.classLoader,
                        arrayOf(callbackHandlerClass)
                    ) { _, method, _ ->
                        when (method.name) {
                            "onEmitStatus" -> 0L
                            "shutdown" -> 0L
                            "startup" -> 0L
                            else -> 0L
                        }
                    }
                    
                    xrayController = clazz.getMethod("newCoreController", callbackHandlerClass)
                        .invoke(null, proxy)
                    
                    val startLoopMethod = xrayController!!.javaClass.getMethod("startLoop", String::class.java, Int::class.java)
                    
                    val configContent = File(configPath).readText()
                    val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                    val proxyPort = prefs.getInt(PREF_PROXY_PORT, DEFAULT_PROXY_PORT)
                    val (preparedConfig, socksEndpoint) = ConfigHelper.prepareForMode(
                        configContent, isProxyMode, proxyPort
                    )
                    val jsonConfig = org.json.JSONObject(preparedConfig)
                    
                    val logFile = File(File(filesDir, "logs"), "xray.log")
                    val logObj = jsonConfig.optJSONObject("log") ?: org.json.JSONObject()
                    logObj.put("access", logFile.absolutePath)
                    logObj.put("error", logFile.absolutePath)
                    logObj.put("loglevel", "debug")
                    jsonConfig.put("log", logObj)
                    
                    val finalConfigContent = jsonConfig.toString()
                    proxyEndpoint = socksEndpoint?.display()
                    
                    startLoopMethod.invoke(xrayController, finalConfigContent, tunFd)
                    
                    if (isProxyMode) {
                        writeLog("Proxy listening on ${proxyEndpoint ?: "127.0.0.1:10808"}")
                    }
                    writeLog("Successfully started Xray via libv2ray.Libv2ray!")
                } catch (e: Exception) {
                    writeLog("Error: Failed to invoke libv2ray classes in the AAR!")
                    writeLog(e.stackTraceToString())
                    throw e
                }

                connectionStartTime = System.currentTimeMillis()
                startSpeedMonitor()
                onStatusChanged?.invoke(true)
                writeLog(
                    if (isProxyMode) "Proxy core running inside AAR!"
                    else "Xray core running inside AAR!"
                )

            } catch (e: Exception) {
                writeLog("Error running xray from AAR: ${e.message}")
                onStatusChanged?.invoke(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
    }

    private fun cleanup() {
        writeLog("Stopping Xray AAR...")
        try {
            if (xrayController != null) {
                val stopLoopMethod = xrayController!!.javaClass.getMethod("stopLoop")
                stopLoopMethod.invoke(xrayController)
                xrayController = null
            }
        } catch (e: Exception) {
            writeLog("Error stopping libv2ray: ${e.message}")
        }

        try {
            logcatProcess?.destroy()
        } catch (e: Exception) {}
        logcatProcess = null

        // Redirect fd 0 back to /dev/null to release the TUN interface reference
        try {
            val devNull = File("/dev/null")
            val pfd = ParcelFileDescriptor.open(devNull, ParcelFileDescriptor.MODE_READ_ONLY)
            Os.dup2(pfd.fileDescriptor, 0)
            pfd.close()
            Log.i(TAG, "Redirected fd 0 to /dev/null")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redirect fd 0 to /dev/null", e)
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close VPN interface", e)
        }
        vpnInterface = null
        proxyEndpoint = null
        isProxyMode = false

        stopSpeedMonitor()
        connectionStartTime = 0L
        onStatusChanged?.invoke(false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startSpeedMonitor() {
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        speedJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()
                val rxSpeed = (currentRx - lastRxBytes).coerceAtLeast(0)
                val txSpeed = (currentTx - lastTxBytes).coerceAtLeast(0)
                lastRxBytes = currentRx
                lastTxBytes = currentTx
                withContext(Dispatchers.Main) {
                    onSpeedUpdate?.invoke(rxSpeed, txSpeed)
                    updateNotificationSpeed(rxSpeed, txSpeed)
                }
            }
        }
    }

    private fun stopSpeedMonitor() {
        speedJob?.cancel()
        speedJob = null
    }

    fun stopXray() {
        cleanup()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(downBytes: Long = 0, upBytes: Long = 0): Notification {
        val stopIntent = Intent(this, XrayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = when {
            isProxyMode && proxyEndpoint != null -> "SOCKS: $proxyEndpoint"
            downBytes > 0 || upBytes > 0 -> "DL: ${formatSpeed(downBytes)}   UL: ${formatSpeed(upBytes)}"
            else -> getString(R.string.notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(speedText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Отключить", stopPending)
            .setOngoing(true)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun updateNotificationSpeed(downBytes: Long, upBytes: Long) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(downBytes, upBytes))
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024L * 1024L -> String.format("%.1f KB/s", bytesPerSecond / 1024f)
            bytesPerSecond < 1024L * 1024L * 1024L -> String.format("%.1f MB/s", bytesPerSecond / (1024f * 1024f))
            else -> String.format("%.2f GB/s", bytesPerSecond / (1024f * 1024f * 1024f))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Darkbit Bypass VPN статус"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun writeLog(message: String) {
        Log.d(TAG, message)
        try {
            val logsDir = File(filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            val logFile = File(logsDir, "xray.log")
            if (logFile.exists() && logFile.length() > 1024 * 1024) {
                logFile.writeText("[LOGS CLEARED DUE TO SIZE LIMIT]\n")
            }
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }
}