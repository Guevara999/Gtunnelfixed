package com.example.sshproxy

import android.content.Context
import android.util.Log
import io.github.sing_box.SingBox
import org.json.JSONObject

class SingBoxManager(private val context: Context) {

    companion object {
        private const val TAG = "SingBoxManager"
    }

    private var config: String? = null
    private var callback: SingBox.Callback? = null

    fun start(configJson: String, callback: SingBox.Callback): Boolean {
        this.config = configJson
        this.callback = callback
        try {
            SingBox.start(configJson, callback)
            Log.d(TAG, "Sing-box started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Sing-box: ${e.message}")
            return false
        }
    }

    fun stop() {
        try {
            SingBox.stop()
            Log.d(TAG, "Sing-box stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Sing-box: ${e.message}")
        }
    }

    fun isRunning(): Boolean = SingBox.running()

    /**
     * Build the Sing-box config from UI settings.
     * This replicates the HTTP proxy + SSH chain with multiplexing.
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
        val payloadProcessed = PayloadProcessor.processPayload(
            payload,
            sshHost,
            sshPort.toString(),
            "$proxyHost:$proxyPort",
            userAgent
        )

        // Extract the first line of payload for the "path" and headers for the HTTP outbound.
        val lines = payloadProcessed.split("\r\n")
        val firstLine = lines.firstOrNull() ?: "GET / HTTP/1.1"
        val headers = mutableMapOf<String, String>()
        var inHeaders = true
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim()] = parts[1].trim()
            }
        }

        val httpOutbound = JSONObject().apply {
            put("type", "http")
            put("tag", "http-proxy")
            put("server", proxyHost)
            put("server_port", proxyPort)
            put("path", firstLine.split(" ")[1]) // path from request
            put("headers", JSONObject(headers))
            // If your payload contains a raw HTTP request, you can also use the "http" outbound with custom headers.
            // For more complex payloads (with multiple parts), you might need to use the "tls" or "transport" outbound.
            // But we'll keep it simple.
        }

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
            // Chain through the HTTP proxy
            put("dialer", JSONObject().apply {
                put("outbound", "http-proxy")
            })
        }

        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("disabled", false)
                put("level", "info")
                put("output", "/dev/null") // or file if needed
            })
            // TUN inbound to replace hev
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