package com.darkbit.bypass

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class LocalTlsProxy(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    val assignedPort: Int
        get() = serverSocket?.localPort ?: port

    fun start() {
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            val boundPort = serverSocket?.localPort ?: port
            isRunning = true
            thread {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: continue
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isRunning) Log.e("LocalTlsProxy", "Accept error", e)
                    }
                }
            }
            Log.i("LocalTlsProxy", "HTTP CONNECT Proxy started on 127.0.0.1:$boundPort")
        } catch (e: Exception) {
            Log.e("LocalTlsProxy", "Failed to start", e)
        }
    }

    private fun readLineSafe(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n")) {
                return sb.substring(0, sb.length - 2)
            }
        }
        return sb.toString()
    }

    private fun handleClient(client: Socket) {
        thread {
            var target: Socket? = null
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val requestLine = readLineSafe(input)
                if (!requestLine.uppercase().startsWith("CONNECT")) {
                    client.close()
                    return@thread
                }

                // Read all headers
                while (true) {
                    val line = readLineSafe(input)
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) return@thread
                
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val targetPort = if (hostPort.size > 1) hostPort[1].toInt() else 443

                Log.d("LocalTlsProxy", "CONNECT to $host:$targetPort")
                target = Socket(host, targetPort)

                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()

                val targetInput = target.getInputStream()
                val targetOutput = target.getOutputStream()

                val t1 = thread {
                    try { input.copyTo(targetOutput) } catch (e: Exception) {}
                    try { target.close() } catch (e: Exception) {}
                }
                val t2 = thread {
                    try { targetInput.copyTo(output) } catch (e: Exception) {}
                    try { client.close() } catch (e: Exception) {}
                }
                t1.join()
                t2.join()
            } catch (e: Exception) {
                Log.e("LocalTlsProxy", "Handler error", e)
            } finally {
                try { target?.close() } catch (e: Exception) {}
                try { client.close() } catch (e: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
