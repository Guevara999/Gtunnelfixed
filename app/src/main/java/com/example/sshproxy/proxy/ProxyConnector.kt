package com.example.sshproxy.proxy

import com.example.sshproxy.LogManager
import com.example.sshproxy.payload.PayloadProcessor
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ProxyConnector {

    @Throws(ProxyConnectionException::class)
    suspend fun connectViaProxy(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payload: String = "",
        userAgent: String = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
        auth: ProxyAuth? = null,
        connectTimeout: Int = 25000,
        readTimeout: Int = 5000,
        followRedirects: Boolean = false,
        splitDelayMs: Long = 500,
        sslForProxy: Boolean = false,
        sslForSSH: Boolean = false,
        directFallback: Boolean = false,
        usePayload: Boolean = true,
        useEnhanced: Boolean = false
    ): Socket {
        require(proxyHost.isNotEmpty() && proxyPort in 1..65535) { "Invalid proxy address" }
        require(sshHost.isNotEmpty() && sshPort in 1..65535) { "Invalid SSH target" }

        if (directFallback) {
            return connectDirect(
                sshHost, sshPort, proxyHost, proxyPort, payload, userAgent,
                connectTimeout, splitDelayMs, sslForSSH, usePayload
            )
        }

        val targetHost = proxyHost
        val targetPort = proxyPort

        LogManager.addLog("[ProxyConnector] Connecting to proxy $targetHost:$targetPort" +
                if (sslForProxy) " (SSL)" else "" +
                (if (useEnhanced) " (Enhanced mode)" else ""))

        val socket: Socket = if (sslForProxy) {
            try {
                val factory = SSLSocketFactory.getDefault()
                val sslSocket = factory.createSocket(targetHost, targetPort) as SSLSocket
                sslSocket.startHandshake()
                sslSocket
            } catch (e: Exception) {
                throw ProxyConnectionException("SSL handshake failed: ${e.message}", e)
            }
        } else {
            Socket()
        }

        try {
            if (!sslForProxy) {
                socket.connect(InetSocketAddress(targetHost, targetPort), connectTimeout)
            }
            socket.soTimeout = readTimeout
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: Exception) {
            socket.close()
            throw ProxyConnectionException("Failed to connect to proxy $targetHost:$targetPort", e)
        }

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        // --- Send payload ---
        if (usePayload && payload.isNotEmpty()) {
            val proxyString = "$targetHost:$targetPort"
            val processedPayload = PayloadProcessor.processPayload(
                payload, sshHost, sshPort.toString(), proxyString, userAgent
            )

            // ========== ALWAYS SPLIT BY [split] ==========
            val parts = PayloadProcessor.splitPayload(processedPayload)
            LogManager.addLog("[ProxyConnector] Split into ${parts.size} parts")
            for ((index, part) in parts.withIndex()) {
                output.write(part.toByteArray())
                output.flush()
                if (index < parts.size - 1 && splitDelayMs > 0) {
                    Thread.sleep(splitDelayMs)
                }
            }
            LogManager.addLog("[ProxyConnector] Payload sent (${parts.size} parts)")

            // If enhanced mode is on, we might add extra behaviour (e.g., WebSocket upgrade)
            // But splitting is always done.
            if (useEnhanced) {
                LogManager.addLog("[ProxyConnector] Enhanced mode: additional headers/upgrade may be applied")
                // For now, nothing extra needed; the payload already contains Upgrade: websocket
            }
        } else {
            LogManager.addLog("[ProxyConnector] No payload to send (usePayload=false)")
        }

        // ---- READ THE RESPONSE ----
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(input))

            var statusLine: String? = reader.readLine()
            LogManager.addLog("[ProxyConnector] Server status: $statusLine")

            if (statusLine == null || statusLine.isEmpty()) {
                Thread.sleep(200)
                statusLine = reader.readLine()
                LogManager.addLog("[ProxyConnector] Delayed server status: $statusLine")
            }

            var line: String?
            while (reader.ready().also { line = reader.readLine() } && line != null) {
                if (line!!.isEmpty()) {
                    LogManager.addLog("[ProxyConnector] End of HTTP headers")
                    break
                }
                LogManager.addLog("[ProxyConnector] Response header: $line")
                if (line!!.startsWith("SSH-2.0")) {
                    LogManager.addLog("[ProxyConnector] SSH banner detected – stopping read")
                    break
                }
                if (line!!.startsWith("HTTP/1.1 101")) {
                    LogManager.addLog("[ProxyConnector] ✅ WebSocket upgrade confirmed")
                }
            }

            if (statusLine != null) {
                val isAccepted = statusLine.startsWith("HTTP/1.1 2") ||
                        statusLine.startsWith("HTTP/1.1 3") ||
                        statusLine.contains("101") ||
                        statusLine.contains("SSH-2.0")
                if (!isAccepted) {
                    LogManager.addLog("[ProxyConnector] Invalid response: $statusLine")
                    socket.close()
                    throw ProxyConnectionException("Server returned $statusLine")
                }
                LogManager.addLog("[ProxyConnector] Server status accepted ($statusLine) – continuing to SSH")
            } else {
                LogManager.addLog("[ProxyConnector] No status line – assuming success")
            }

        } catch (e: SocketTimeoutException) {
            LogManager.addLog("[ProxyConnector] Response read timed out – assuming SSH handshake can start")
        } catch (e: Exception) {
            LogManager.addLog("[ProxyConnector] Error reading response: ${e.message} – continuing")
        }

        socket.soTimeout = 30000
        LogManager.addLog("[ProxyConnector] Tunnel established successfully")
        return socket
    }

    private fun connectDirect(
        sshHost: String,
        sshPort: Int,
        proxyHost: String,
        proxyPort: Int,
        payload: String,
        userAgent: String,
        connectTimeout: Int,
        splitDelayMs: Long,
        sslForSSH: Boolean,
        usePayload: Boolean
    ): Socket {
        LogManager.addLog("[ProxyConnector] Direct connection to $sshHost:$sshPort" +
                if (sslForSSH) " (SSL)" else "")

        val socket: Socket = if (sslForSSH) {
            try {
                val factory = SSLSocketFactory.getDefault()
                val sslSocket = factory.createSocket(sshHost, sshPort) as SSLSocket
                sslSocket.startHandshake()
                sslSocket
            } catch (e: Exception) {
                throw ProxyConnectionException("SSL handshake failed: ${e.message}", e)
            }
        } else {
            Socket()
        }

        try {
            if (!sslForSSH) {
                socket.connect(InetSocketAddress(sshHost, sshPort), connectTimeout)
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: Exception) {
            socket.close()
            throw ProxyConnectionException("Failed to connect to $sshHost:$sshPort", e)
        }

        val output = socket.getOutputStream()

        if (usePayload && payload.isNotEmpty()) {
            val proxyString = "$proxyHost:$proxyPort"
            val processedPayload = PayloadProcessor.processPayload(
                payload, sshHost, sshPort.toString(), proxyString, userAgent
            )
            LogManager.addLog("[ProxyConnector] Sending direct payload")
            val parts = PayloadProcessor.splitPayload(processedPayload)
            for ((index, part) in parts.withIndex()) {
                output.write(part.toByteArray())
                output.flush()
                if (index < parts.size - 1 && splitDelayMs > 0) {
                    Thread.sleep(splitDelayMs)
                }
            }
            LogManager.addLog("[ProxyConnector] Direct payload sent")
        }

        return socket
    }
}