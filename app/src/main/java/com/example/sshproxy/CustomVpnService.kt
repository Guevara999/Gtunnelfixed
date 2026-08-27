package com.example.sshproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.sshproxy.payload.PayloadProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CustomVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "Gtunnel:WakeLock"

        const val ACTION_CONNECT = "com.example.sshproxy.CONNECT"
        const val ACTION_DISCONNECT = "com.example.sshproxy.DISCONNECT"
        const val ACTION_RECONNECT = "com.example.sshproxy.RECONNECT"
    }

    enum class VpnState {
        IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR, RECONNECTING
    }

    private val _state = MutableStateFlow(VpnState.IDLE)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private var process: Process? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isConnected = AtomicBoolean(false)

    // Config fields
    private var sshHost: String = ""
    private var sshPort: String = ""
    private var sshUser: String = ""
    private var sshPass: String = ""
    private var proxyHost: String = ""
    private var proxyPort: String = ""
    private var payload: String = ""
    private var customUserAgent: String = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
    private var mtu: Int = 1500
    private var pingUrl: String = "https://dns.google"
    private var pingInterval: Int = 2000
    private var pingTimeout: Int = 10000
    private var alwaysReconnect: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        createNotificationChannel()
        startStateMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> { extractConfig(intent); connect() }
            ACTION_DISCONNECT -> disconnect()
            ACTION_RECONNECT -> reconnect()
            else -> if (intent != null && intent.hasExtra("sshHost")) {
                extractConfig(intent); connect()
            }
        }
        return START_STICKY
    }

    private fun extractConfig(intent: Intent) {
        sshHost = intent.getStringExtra("sshHost") ?: ""
        sshPort = intent.getStringExtra("sshPort") ?: ""
        sshUser = intent.getStringExtra("sshUser") ?: ""
        sshPass = intent.getStringExtra("sshPass") ?: ""
        proxyHost = intent.getStringExtra("proxyHost") ?: ""
        proxyPort = intent.getStringExtra("proxyPort") ?: ""
        payload = intent.getStringExtra("payload") ?: ""
        customUserAgent = intent.getStringExtra("userAgent") ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
        mtu = intent.getIntExtra("mtu", 1500)
        pingUrl = intent.getStringExtra("pingUrl") ?: "https://dns.google"
        pingInterval = intent.getIntExtra("pingInterval", 2000)
        pingTimeout = intent.getIntExtra("pingTimeout", 10000)
        alwaysReconnect = intent.getBooleanExtra("alwaysReconnect", false)
        LogManager.addLog("[DEBUG] Payload: ${payload.take(100)}...")
    }

    private fun connect() {
        if (_state.value == VpnState.CONNECTING || _state.value == VpnState.CONNECTED) {
            LogManager.addLog("[WARN] Already connecting or connected")
            return
        }
        if (sshHost.isEmpty() || sshPort.isEmpty() || sshUser.isEmpty() || sshPass.isEmpty()) {
            LogManager.addLog("[ERROR] Missing SSH details")
            _state.value = VpnState.ERROR
            return
        }
        _state.value = VpnState.CONNECTING
        acquireWakeLock()
        showNotification("Connecting...")
        LogManager.addLog("Starting sing-box CLI...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                doConnect()
            } catch (e: Exception) {
                LogManager.addLog("[ERROR] Connection failed: ${e.message}")
                e.printStackTrace()
                _state.value = VpnState.ERROR
                showNotification("Connection failed")
                releaseWakeLock()
                if (alwaysReconnect) reconnect()
            }
        }
    }

    private suspend fun doConnect() {
        // 1. Build JSON config
        val configJson = buildSingBoxConfig()
        val configFile = File(cacheDir, "config.json")
        configFile.writeText(configJson)
        LogManager.addLog("Config written to ${configFile.absolutePath}")

        // 2. Extract binary from assets
        val binaryFile = File(cacheDir, "sing-box")
        if (!binaryFile.exists()) {
            assets.open("sing-box").use { input ->
                FileOutputStream(binaryFile).use { output ->
                    input.copyTo(output)
                }
            }
            binaryFile.setExecutable(true)
        }
        LogManager.addLog("Binary ready at ${binaryFile.absolutePath}")

        // 3. Create TUN interface
        vpnInterface = Builder()
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .setMtu(mtu)
            .establish() ?: throw Exception("VPN interface creation failed")
        LogManager.addLog("TUN interface created (fd=${vpnInterface?.fd})")

        // 4. Run sing-box
        val processBuilder = ProcessBuilder(
            binaryFile.absolutePath,
            "-c", configFile.absolutePath,
            "--tun",
            "--tun-fd", vpnInterface!!.fd.toString()
        )
        processBuilder.redirectErrorStream(true)
        process = processBuilder.start()

        // Read output
        CoroutineScope(Dispatchers.IO).launch {
            process?.inputStream?.bufferedReader()?.forEachLine {
                LogManager.addLog("[sing-box] $it")
            }
        }

        LogManager.addLog("sing-box process started with PID ${process?.pid()}")
        isConnected.set(true)
        _state.value = VpnState.CONNECTED
        showNotification("Connected ✓")
        startPing()
        if (alwaysReconnect) startReconnectMonitor()
    }

    private fun buildSingBoxConfig(): String {
        val processed = PayloadProcessor.processPayload(payload, sshHost, sshPort, proxyHost, customUserAgent)
        val (method, path, headers) = parseHttpRequest(processed)

        val realProxyHost = proxyHost.ifEmpty { sshHost }
        val realProxyPort = proxyPort.ifEmpty { sshPort }.toIntOrNull() ?: 80

        val outbounds = listOf(
            mapOf(
                "type" to "http",
                "tag" to "payload",
                "server" to realProxyHost,
                "server_port" to realProxyPort,
                "method" to method,
                "path" to path,
                "headers" to headers
            ),
            mapOf(
                "type" to "ssh",
                "tag" to "ssh-out",
                "server" to sshHost,
                "server_port" to sshPort.toInt(),
                "user" to sshUser,
                "password" to sshPass,
                "detour" to "payload"
            )
        )

        return JSONObject(
            mapOf(
                "inbounds" to listOf(
                    mapOf(
                        "type" to "tun",
                        "inet4_address" to "172.19.0.1/30",
                        "mtu" to mtu,
                        "auto_route" to true
                    )
                ),
                "outbounds" to outbounds
            )
        ).toString()
    }

    private fun parseHttpRequest(raw: String): Triple<String, String, Map<String, String>> {
        val lines = raw.split("\r\n")
        if (lines.isEmpty()) return Triple("GET", "/", emptyMap())
        val requestLine = lines[0].split(" ")
        val method = if (requestLine.size >= 1) requestLine[0] else "GET"
        val path = if (requestLine.size >= 2) requestLine[1] else "/"
        val headers = mutableMapOf<String, String>()
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
            i++
        }
        return Triple(method, path, headers)
    }

    private fun disconnect() {
        _state.value = VpnState.DISCONNECTING
        isConnected.set(false)
        reconnectJob?.cancel()
        pingJob?.cancel()
        stateJob?.cancel()

        try {
            process?.destroy()
            process?.waitFor()
            process = null
            LogManager.addLog("sing-box process stopped")
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] Failed to stop process: ${e.message}")
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] Failed to close TUN: ${e.message}")
        }

        stopForeground(true)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        LogManager.addLog("VPN stopped")
        releaseWakeLock()
        _state.value = VpnState.IDLE
        stopSelf()
    }

    private fun reconnect() {
        if (_state.value == VpnState.RECONNECTING) return
        _state.value = VpnState.RECONNECTING
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            while (attempts < 10 && !isConnected.get()) {
                val delay = 2000L * (1L shl attempts.coerceAtMost(8))
                LogManager.addLog("Reconnect attempt ${attempts + 1} in ${delay}ms")
                delay(delay)
                connect()
                if (isConnected.get()) { attempts = 0; return@launch }
                attempts++
            }
            if (!isConnected.get()) {
                _state.value = VpnState.ERROR
                showNotification("Reconnection failed")
            }
        }
    }

    private fun startPing() {
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) {
                delay(pingInterval.toLong())
                try {
                    val conn = java.net.URL(pingUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = pingTimeout
                    conn.readTimeout = pingTimeout
                    conn.requestMethod = "GET"
                    if (conn.responseCode in 200..299) LogManager.addLog("Ping OK")
                    else LogManager.addLog("Ping timeout (code ${conn.responseCode})")
                } catch (e: Exception) { LogManager.addLog("Ping timeout") }
            }
        }
    }

    private fun startReconnectMonitor() {
        stateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) delay(1000)
        }
    }

    private fun startStateMonitoring() {
        stateJob = CoroutineScope(Dispatchers.Main).launch {
            state.collect { sendStatus(it.name) }
        }
    }

    private fun sendStatus(status: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("VPN_STATUS").putExtra("status", status))
    }

    private fun showNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gtunnel").setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun acquireWakeLock() {
        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (e: Exception) { LogManager.addLog("[ERROR] ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { LogManager.addLog("[ERROR] ${e.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        releaseWakeLock()
        LogManager.addLog("onDestroy finished")
    }
}