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

class XrayService : VpnService() {

    companion object {
        const val TAG = "XrayService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "darkbit_channel"
        const val ACTION_START = "com.darkbit.bypass.START"
        const val ACTION_STOP = "com.darkbit.bypass.STOP"
    }

    private val binder = LocalBinder()
    private var xrayProcess: Process? = null
    private var localTlsProxy: LocalTlsProxy? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onStatusChanged: ((Boolean) -> Unit)? = null
    var onSpeedUpdate: ((downBytesPerSec: Long, upBytesPerSec: Long) -> Unit)? = null
    var connectionStartTime: Long = 0L
        private set

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var speedJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): XrayService = this@XrayService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configPath = intent.getStringExtra("config_path") ?: return START_NOT_STICKY
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

    private fun startXray(configPath: String) {
        serviceScope.launch {
            try {
                // Clear old logs
                try {
                    val logFile = File(File(filesDir, "logs"), "xray.log")
                    if (logFile.exists()) logFile.delete()
                } catch (e: Exception) {}

                writeLog("Starting connection sequence...")

                // Copy xray binary from assets if needed
                val xrayBin = prepareXrayBinary() ?: run {
                    writeLog("Error: Failed to prepare xray binary")
                    stopSelf()
                    return@launch
                }

                writeLog("Parsing configuration files...")
                val finalConfigPath = configPath

                // Build VPN tunnel
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
                
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    writeLog("Warning: Failed to exclude app from VPN: ${e.message}")
                }

                vpnInterface?.close()
                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    try {
                        Os.dup2(vpnInterface!!.fileDescriptor, 0)
                        writeLog("VPN Tunnel successfully configured and bound to system fd 0")
                    } catch (e: Exception) {
                        writeLog("Error: Failed to dup2 tun fd: ${e.message}")
                    }
                } else {
                    writeLog("Error: Failed to establish VPN interface")
                    stopSelf()
                    return@launch
                }

                writeLog("Starting Xray core process...")
                val pb = ProcessBuilder(xrayBin.absolutePath, "run", "-c", finalConfigPath)
                pb.redirectErrorStream(true)
                pb.environment()["HOME"] = filesDir.absolutePath
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
                } else {
                    writeLog("Warning: Full VPN feature requires Android 8.0+")
                }

                xrayProcess = pb.start()
                connectionStartTime = System.currentTimeMillis()
                startSpeedMonitor()
                onStatusChanged?.invoke(true)
                writeLog("Xray core running. Streaming stdout/stderr output:")

                // Read logs
                xrayProcess?.inputStream?.bufferedReader()?.use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        writeLog("xray: $line")
                    }
                }

                val exitCode = xrayProcess?.waitFor() ?: -1
                writeLog("Xray core exited with code: $exitCode")
                onStatusChanged?.invoke(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()

            } catch (e: Exception) {
                writeLog("Error running xray: ${e.message}")
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
        try {
            localTlsProxy?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TLS proxy", e)
        }
        localTlsProxy = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                xrayProcess?.destroyForcibly()
            } else {
                xrayProcess?.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill xray process", e)
        }
        xrayProcess = null

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

        stopSpeedMonitor()
        connectionStartTime = 0L
        onStatusChanged?.invoke(false)
    }

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

    private fun prepareXrayBinary(): File? {
        val xrayFile = File(applicationInfo.nativeLibraryDir, "libxray.so")
        Log.i(TAG, "Looking for xray at: ${xrayFile.absolutePath}, exists: ${xrayFile.exists()}")
        return if (xrayFile.exists()) xrayFile else null
    }

    /**
     * Parses the config, finds the fedarisha S3 endpoint, extracts the host,
     * and rewrites the endpoint to http://127.0.0.1:port.
     * Returns Pair(targetHost, patchedConfigPath)
     */
    private fun prepareTlsProxyConfig(configPath: String, proxyPort: Int): Pair<String?, String> {
        return try {
            val configFile = File(configPath)
            val config = org.json.JSONObject(configFile.readText())
            val outbounds = config.optJSONArray("outbounds") ?: return Pair(null, configPath)

            var endpointHost: String? = null
            var modified = false

            // Track existing tags to prevent 'default outbound handler not exist'
            val existingTags = mutableSetOf<String>()

            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                val tag = outbound.optString("tag")
                if (tag.isNotEmpty()) {
                    existingTags.add(tag)
                }

                if (outbound.optString("protocol") != "fedarisha") continue
                val storage = outbound.optJSONObject("settings")?.optJSONObject("storage") ?: continue
                val endpoint = storage.optString("endpoint", "")
                if (endpoint.isEmpty()) continue
                
                val url = java.net.URL(endpoint)
                endpointHost = url.host
                if (endpointHost.matches(Regex("[\\d.]+"))) continue
                
                storage.put("endpoint", "http://127.0.0.1:$proxyPort")
                modified = true
            }

            // Inject fallback outbounds if missing
            if (!existingTags.contains("direct")) {
                val directOutbound = org.json.JSONObject()
                directOutbound.put("tag", "direct")
                directOutbound.put("protocol", "freedom")
                outbounds.put(directOutbound)
                modified = true
                Log.i(TAG, "Injected missing 'direct' outbound")
            }

            if (!existingTags.contains("block")) {
                val blockOutbound = org.json.JSONObject()
                blockOutbound.put("tag", "block")
                blockOutbound.put("protocol", "blackhole")
                outbounds.put(blockOutbound)
                modified = true
                Log.i(TAG, "Injected missing 'block' outbound")
            }

            if (modified) {
                val resolved = File(filesDir, "config_proxy.json")
                resolved.writeText(config.toString(2))
                Log.i(TAG, "Wrote proxy config to: ${resolved.absolutePath}")
                return Pair(endpointHost, resolved.absolutePath)
            }
            Pair(null, configPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare TLS proxy config", e)
            Pair(null, configPath)
        }
    }

    private fun getS3Host(configPath: String): String? {
        return try {
            val configFile = File(configPath)
            val config = org.json.JSONObject(configFile.readText())
            val outbounds = config.optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                if (outbound.optString("protocol") != "fedarisha") continue
                val storage = outbound.optJSONObject("settings")?.optJSONObject("storage") ?: continue
                val endpoint = storage.optString("endpoint", "")
                if (endpoint.isNotEmpty()) {
                    val url = java.net.URL(endpoint)
                    val host = url.host
                    if (!host.matches(Regex("[\\d.]+"))) {
                        return host
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
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

        val speedText = if (downBytes > 0 || upBytes > 0) {
            "DL: ${formatSpeed(downBytes)}   UL: ${formatSpeed(upBytes)}"
        } else {
            getString(R.string.notification_text)
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
                logFile.delete()
            }
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }
}