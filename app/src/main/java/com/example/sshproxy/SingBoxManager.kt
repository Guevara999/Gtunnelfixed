package com.example.sshproxy

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LibboxCallback
import org.json.JSONObject

class SingBoxManager(private val context: Context) {

    companion object {
        private const val TAG = "SingBoxManager"
    }

    private var callback: LibboxCallback? = null

    fun start(configJson: String, callback: LibboxCallback): Boolean {
        this.callback = callback
        return try {
            Libbox.start(configJson, callback)
            Log.d(TAG, "Sing-box started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Sing-box: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            Libbox.stop()
            Log.d(TAG, "Sing-box stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Sing-box: ${e.message}")
        }
    }

    fun isRunning(): Boolean = Libbox.running()

    /**
     * Build the Sing-box JSON config from user settings.
     * Uses an HTTP outbound with custom payload, then chains an SSH outbound through it.
     */
    fun buildConfig(
        sshHost: String,
        sshPort: Int,
        sshUser: String,
        sshPass: String,
        proxyHost: String,
        proxyPort: Int,
        payload: String,
        userAgent: String = "Mozilla/5.0 (Linux; Android 12)",
        mtu: Int = 1500
    ): String {
        // Process the payload using your existing PayloadProcessor
        val processedPayload = PayloadProcessor.processPayload(
            payload,
            sshHost,
            sshPort.toString(),
            "$proxyHost:$proxyPort",
            userAgent
        )

        // Parse the HTTP request lines to extract method, path, and headers.
        val lines = processedPayload.split("\r\n")
        val firstLine = lines.firstOrNull() ?: "GET / HTTP/1.1"
        val method = firstLine.split(" ").getOrElse(0) { "GET" }
        val path = firstLine.split(" ").getOrElse(1) { "/" }

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim()] = parts[1].trim()
            }
        }

        // HTTP outbound (the proxy with payload)
        val httpOutbound = JSONObject().apply {
            put("type", "http")
            put("tag", "http-proxy")
            put("server", proxyHost)
            put("server_port", proxyPort)
            put("method", method)
            put("path", path)
            put("headers", JSONObject(headers))
            // If your payload requires raw data (e.g., for the huge Content-Length), you may need to use "tls" or "transport" outbound.
            // For now, we rely on the standard HTTP outbound.
        }

        // SSH outbound chained through the HTTP proxy
        val sshOutbound = JSONObject().apply {
            put("type", "ssh")
            put("tag", "ssh-out")
            put("server", sshHost)
            put("server_port", sshPort)
            put("user", sshUser)
            put("password", sshPass)
            put("client_version", "SSH-2.0-OpenSSH_8.2p1")
            put("multiplex", JSONObject().apply {
                put("enabled", true)
                put("protocol", "smux")
                put("max_connections", 1)
            })
            put("dialer", JSONObject().apply {
                put("outbound", "http-proxy")
            })
        }

        // Main configuration with TUN inbound and routing
        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("disabled", false)
                put("level", "info")
                put("output", "/dev/null") // or a file path for debugging
            })
            put("inbounds", listOf(
                JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("inet4_address", "10.0.0.1/30")
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("strict_route", true)
                }
            ))
            put("outbounds", listOf(httpOutbound, sshOutbound))
            put("route", JSONObject().apply {
                put("final", "ssh-out")
            })
        }

        return config.toString()
    }
}