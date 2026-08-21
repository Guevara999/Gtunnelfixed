package com.example.sshproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.sing_box.SingBox
import java.util.concurrent.atomic.AtomicBoolean

class CustomVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CustomVpnService"
        private const val WAKELOCK_TAG = "HttpCustom:WakeLock"

        const val ACTION_CONNECT = "com.example.sshproxy.CONNECT"
        const val ACTION_DISCONNECT = "com.example.sshproxy.DISCONNECT"
        const val ACTION_RECONNECT = "com.example.sshproxy.RECONNECT"
    }

    enum class VpnState {
        IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR, RECONNECTING
    }

    private val _state = MutableStateFlow(VpnState.IDLE)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private var singBoxManager: SingBoxManager? = null
    private val isConnected = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Config (same as before) ---
    private var sshHost: String = ""
    private var sshPort: String = ""
    private var sshUser: String = ""
    private var sshPass: String = ""
    private var proxyHost: String = ""
    private var proxyPort: String = ""
    private var payload: String = ""
    private var splitDelayMs: Int = 500
    private var dnsPrimary: String = "1.1.1.1"
    private var dnsSecondary: String = "1.0.0.1"
    private var pingTarget: String = "1.1.1.1"
    private var enableCompression: Boolean = true
    private var alwaysReconnect: Boolean = false
    private var followRedirects: Boolean = true
    private var usePayload: Boolean = true
    private var useSsl: Boolean = false
    private var mtu: Int = 1500
    private var sendBuffer: Int = 16384
    private var receiveBuffer: Int = 32768
    private var pingUrl: String = "https://dns.google"
    private var pingInterval: Int = 2000
    private var pingTimeout: Int = 10000
    private var enhanced: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        createNotificationChannel()
        singBoxManager = SingBoxManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                extractConfig(intent)
                connect()
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_RECONNECT -> reconnect()
            else -> {
                if (intent != null && intent.hasExtra("sshHost")) {
                    extractConfig(intent)
                    connect()
                }
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
        splitDelayMs = intent.getIntExtra("splitDelay", 500)
        dnsPrimary = intent.getStringExtra("dnsPrimary") ?: "1.1.1.1"
        dnsSecondary = intent.getStringExtra("dnsSecondary") ?: "1.0.0.1"
        pingTarget = intent.getStringExtra("pingTarget") ?: "1.1.1.1"
        enableCompression = intent.getBooleanExtra("enableCompression", true)
        alwaysReconnect = intent.getBooleanExtra("alwaysReconnect", false)
        followRedirects = intent.getBooleanExtra("followRedirects", true)
        usePayload = intent.getBooleanExtra("usePayload", true)
        useSsl = intent.getBooleanExtra("proxySsl", false)
        mtu = intent.getIntExtra("mtu", 1500)
        sendBuffer = intent.getIntExtra("sendBuffer", 16384)
        receiveBuffer = intent.getIntExtra("receiveBuffer", 32768)
        pingUrl = intent.getStringExtra("pingUrl") ?: "https://dns.google"
        pingInterval = intent.getIntExtra("pingInterval", 2000)
        pingTimeout = intent.getIntExtra("pingTimeout", 10000)
        enhanced = intent.getBooleanExtra("enhanced", false)
        LogManager.addLog("[DEBUG] Payload received: ${payload.take(100)}...")
        LogManager.addLog("[DEBUG] Enhanced mode: $enhanced")
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                doConnect()
            } catch (e: Exception) {
                LogManager.addLog("[ERROR] Connection failed: ${e.message}")
                _state.value = VpnState.ERROR
                showNotification("Connection failed")
                releaseWakeLock()
                if (alwaysReconnect) reconnect()
            }
        }
    }

    private suspend fun doConnect() {
        // Build Sing-box config
        val config = singBoxManager!!.buildConfig(
            sshHost, sshPort.toInt(), sshUser, sshPass,
            proxyHost, proxyPort.toInt(), payload,
            mtu = mtu
        )

        LogManager.addLog("[Sing-box] Starting with config:\n$config")

        // Start Sing-box
        val success = singBoxManager!!.start(config, object : SingBox.Callback {
            override fun onLog(level: Int, message: String) {
                LogManager.addLog("[Sing-box] $message")
                if (message.contains("SSH handshake") || message.contains("connected")) {
                    // We can update state here
                }
            }
            override fun onExit(code: Int) {
                LogManager.addLog("[Sing-box] Exited with code $code")
                if (isConnected.get()) {
                    // reconnect if needed
                    if (alwaysReconnect) reconnect()
                }
            }
        })

        if (!success) {
            throw Exception("Sing-box failed to start")
        }

        isConnected.set(true)
        _state.value = VpnState.CONNECTED
        sendStatus("Connected")
        showNotification("Connected ✓")

        // Start ping (optional)
        startPing()
    }

    private fun disconnect() {
        _state.value = VpnState.DISCONNECTING
        isConnected.set(false)

        singBoxManager?.stop()

        stopForeground(true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        sendStatus("Disconnected")
        LogManager.addLog("VPN stopped")
        releaseWakeLock()
        _state.value = VpnState.IDLE
        stopSelf()
    }

    private fun reconnect() {
        if (_state.value == VpnState.RECONNECTING) return
        _state.value = VpnState.RECONNECTING
        CoroutineScope(Dispatchers.IO).launch {
            delay(2000)
            connect()
        }
    }

    private fun startPing() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) {
                delay(pingInterval.toLong())
                try {
                    val url = java.net.URL(pingUrl)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = pingTimeout
                    connection.readTimeout = pingTimeout
                    connection.requestMethod = "GET"
                    val responseCode = connection.responseCode
                    if (responseCode == 200 || responseCode == 204) {
                        LogManager.addLog("Ping success")
                    } else {
                        LogManager.addLog("Ping timeout (code $responseCode)")
                    }
                } catch (e: Exception) {
                    LogManager.addLog("Ping timeout")
                }
            }
        }
    }

    private fun sendStatus(status: String) {
        val intent = Intent("VPN_STATUS")
        intent.putExtra("status", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun showNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gtunnel")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] WakeLock release failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        releaseWakeLock()
        Log.d(TAG, "onDestroy finished")
    }
}