package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.Inet4Address
import java.net.NetworkInterface

class ConfigFragment : Fragment() {

    private lateinit var sshDetailsInput: EditText
    private lateinit var proxyInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var splitDelayInput: EditText
    private lateinit var dnsPrimaryInput: EditText
    private lateinit var dnsSecondaryInput: EditText
    private lateinit var enableCompressionCheck: CheckBox
    private lateinit var alwaysReconnectCheck: CheckBox
    private lateinit var followRedirectsCheck: CheckBox
    private lateinit var usePayloadCheck: CheckBox
    private lateinit var proxySslCheck: CheckBox
    private lateinit var mtuInput: EditText
    private lateinit var sendBufferInput: EditText
    private lateinit var receiveBufferInput: EditText
    private lateinit var pingUrlInput: EditText
    private lateinit var pingIntervalInput: EditText
    private lateinit var pingTimeoutInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var localIpText: TextView
    private lateinit var enhancedToggle: CheckBox

    private var currentSshHost: String = ""
    private var currentSshPort: String = ""
    private var currentSshUser: String = ""
    private var currentSshPass: String = ""
    private var currentProxyHost: String = ""
    private var currentProxyPort: String = ""
    private var currentPayload: String = ""
    private var currentSplitDelay: Int = 500
    private var currentDnsPrimary: String = "1.1.1.1"
    private var currentDnsSecondary: String = "1.0.0.1"
    private var currentEnableCompression: Boolean = true
    private var currentAlwaysReconnect: Boolean = false
    private var currentFollowRedirects: Boolean = true
    private var currentUsePayload: Boolean = true
    private var currentProxySsl: Boolean = false
    private var currentMtu: Int = 1500
    private var currentSendBuffer: Int = 16384
    private var currentReceiveBuffer: Int = 32768
    private var currentPingUrl: String = "https://dns.google"
    private var currentPingInterval: Int = 2000
    private var currentPingTimeout: Int = 5000
    private var currentEnhanced: Boolean = false

    private lateinit var configManager: ConfigManager

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            activity?.runOnUiThread {
                when (status) {
                    "Connected" -> {
                        toggleButton.text = "Disconnect"
                        toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        updateStatus("Connected", android.R.color.holo_green_dark)
                    }
                    "Disconnected" -> {
                        toggleButton.text = "Connect"
                        toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                        updateStatus("Disconnected", android.R.color.holo_red_dark)
                    }
                    else -> updateStatus(status, android.R.color.holo_orange_dark)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        sshDetailsInput = view.findViewById(R.id.sshDetailsInput)
        proxyInput = view.findViewById(R.id.proxyInput)
        payloadInput = view.findViewById(R.id.payloadInput)
        splitDelayInput = view.findViewById(R.id.splitDelayInput)
        dnsPrimaryInput = view.findViewById(R.id.dnsPrimaryInput)
        dnsSecondaryInput = view.findViewById(R.id.dnsSecondaryInput)
        enableCompressionCheck = view.findViewById(R.id.enableCompressionCheck)
        alwaysReconnectCheck = view.findViewById(R.id.alwaysReconnectCheck)
        followRedirectsCheck = view.findViewById(R.id.followRedirectsCheck)
        usePayloadCheck = view.findViewById(R.id.usePayloadCheck)
        proxySslCheck = view.findViewById(R.id.proxySslCheck)
        mtuInput = view.findViewById(R.id.mtuInput)
        sendBufferInput = view.findViewById(R.id.sendBufferInput)
        receiveBufferInput = view.findViewById(R.id.receiveBufferInput)
        pingUrlInput = view.findViewById(R.id.pingUrlInput)
        pingIntervalInput = view.findViewById(R.id.pingIntervalInput)
        pingTimeoutInput = view.findViewById(R.id.pingTimeoutInput)
        toggleButton = view.findViewById(R.id.toggleButton)
        statusText = view.findViewById(R.id.statusText)
        localIpText = view.findViewById(R.id.localIpText)
        enhancedToggle = view.findViewById(R.id.enhancedToggle)

        configManager = ConfigManager(requireContext())

        loadSavedConfig()

        toggleButton.setOnClickListener {
            if (toggleButton.text == "Connect") {
                saveConfig()
                startVpnService()
            } else {
                stopVpnService()
            }
        }

        updateLocalIp()

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            statusReceiver,
            IntentFilter("VPN_STATUS")
        )

        return view
    }

    private fun loadSavedConfig() {
        val config = configManager.getConfig()
        sshDetailsInput.setText(config.sshDetails)
        proxyInput.setText(config.remoteProxy)
        payloadInput.setText(config.payload)
        splitDelayInput.setText(config.splitDelay.toString())
        dnsPrimaryInput.setText(config.dnsPrimary)
        dnsSecondaryInput.setText(config.dnsSecondary)
        enableCompressionCheck.isChecked = config.enableCompression
        alwaysReconnectCheck.isChecked = config.alwaysReconnect
        followRedirectsCheck.isChecked = config.followRedirects
        usePayloadCheck.isChecked = config.usePayload
        proxySslCheck.isChecked = config.proxySsl
        mtuInput.setText(config.mtu.toString())
        sendBufferInput.setText(config.sendBuffer.toString())
        receiveBufferInput.setText(config.receiveBuffer.toString())
        pingUrlInput.setText(config.pingUrl)
        pingIntervalInput.setText(config.pingInterval.toString())
        pingTimeoutInput.setText(config.pingTimeout.toString())
        enhancedToggle.isChecked = config.enhanced
    }

    private fun saveConfig() {
        configManager.saveConfig(
            sshDetails = sshDetailsInput.text.toString(),
            remoteProxy = proxyInput.text.toString(),
            payload = payloadInput.text.toString(),
            splitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500,
            dnsPrimary = dnsPrimaryInput.text.toString(),
            dnsSecondary = dnsSecondaryInput.text.toString(),
            enableCompression = enableCompressionCheck.isChecked,
            alwaysReconnect = alwaysReconnectCheck.isChecked,
            followRedirects = followRedirectsCheck.isChecked,
            usePayload = usePayloadCheck.isChecked,
            proxySsl = proxySslCheck.isChecked,
            mtu = mtuInput.text.toString().toIntOrNull() ?: 1500,
            sendBuffer = sendBufferInput.text.toString().toIntOrNull() ?: 16384,
            receiveBuffer = receiveBufferInput.text.toString().toIntOrNull() ?: 32768,
            pingUrl = pingUrlInput.text.toString(),
            pingInterval = pingIntervalInput.text.toString().toIntOrNull() ?: 2000,
            pingTimeout = pingTimeoutInput.text.toString().toIntOrNull() ?: 5000,
            enhanced = enhancedToggle.isChecked
        )
    }

    private fun startVpnService() {
        currentSshHost = sshDetailsInput.text.toString()
        currentSshPort = "80"
        val sshParts = currentSshHost.split("@")
        val credentials = sshParts.getOrNull(1)?.split(":") ?: listOf()
        currentSshUser = credentials.getOrNull(0) ?: ""
        currentSshPass = credentials.getOrNull(1) ?: ""
        val proxyParts = proxyInput.text.toString().split(":")
        currentProxyHost = proxyParts.getOrNull(0) ?: ""
        currentProxyPort = proxyParts.getOrNull(1) ?: "80"
        currentPayload = payloadInput.text.toString()
        currentSplitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500
        currentDnsPrimary = dnsPrimaryInput.text.toString()
        currentDnsSecondary = dnsSecondaryInput.text.toString()
        currentEnableCompression = enableCompressionCheck.isChecked
        currentAlwaysReconnect = alwaysReconnectCheck.isChecked
        currentFollowRedirects = followRedirectsCheck.isChecked
        currentUsePayload = usePayloadCheck.isChecked
        currentProxySsl = proxySslCheck.isChecked
        currentMtu = mtuInput.text.toString().toIntOrNull() ?: 1500
        currentSendBuffer = sendBufferInput.text.toString().toIntOrNull() ?: 16384
        currentReceiveBuffer = receiveBufferInput.text.toString().toIntOrNull() ?: 32768
        currentPingUrl = pingUrlInput.text.toString()
        currentPingInterval = pingIntervalInput.text.toString().toIntOrNull() ?: 2000
        currentPingTimeout = pingTimeoutInput.text.toString().toIntOrNull() ?: 5000
        currentEnhanced = enhancedToggle.isChecked

        val serviceIntent = Intent(requireContext(), CustomVpnService::class.java)
        serviceIntent.action = CustomVpnService.ACTION_CONNECT
        serviceIntent.putExtra("sshHost", currentSshHost)
        serviceIntent.putExtra("sshPort", currentSshPort)
        serviceIntent.putExtra("sshUser", currentSshUser)
        serviceIntent.putExtra("sshPass", currentSshPass)
        serviceIntent.putExtra("proxyHost", currentProxyHost)
        serviceIntent.putExtra("proxyPort", currentProxyPort)
        serviceIntent.putExtra("payload", currentPayload)
        serviceIntent.putExtra("splitDelay", currentSplitDelay)
        serviceIntent.putExtra("dnsPrimary", currentDnsPrimary)
        serviceIntent.putExtra("dnsSecondary", currentDnsSecondary)
        serviceIntent.putExtra("enableCompression", currentEnableCompression)
        serviceIntent.putExtra("alwaysReconnect", currentAlwaysReconnect)
        serviceIntent.putExtra("followRedirects", currentFollowRedirects)
        serviceIntent.putExtra("usePayload", currentUsePayload)
        serviceIntent.putExtra("proxySsl", currentProxySsl)
        serviceIntent.putExtra("mtu", currentMtu)
        serviceIntent.putExtra("sendBuffer", currentSendBuffer)
        serviceIntent.putExtra("receiveBuffer", currentReceiveBuffer)
        serviceIntent.putExtra("pingUrl", currentPingUrl)
        serviceIntent.putExtra("pingInterval", currentPingInterval)
        serviceIntent.putExtra("pingTimeout", currentPingTimeout)
        serviceIntent.putExtra("enhanced", currentEnhanced)

        requireContext().startService(serviceIntent)
    }

    private fun stopVpnService() {
        val serviceIntent = Intent(requireContext(), CustomVpnService::class.java)
        serviceIntent.action = CustomVpnService.ACTION_DISCONNECT
        requireContext().startService(serviceIntent)
    }

    // PUBLIC method – accessible from HttpCustomActivity
    fun updateStatus(status: String, colorId: Int) {
        activity?.runOnUiThread {
            statusText.text = "Status: $status"
            statusText.setTextColor(ContextCompat.getColor(requireContext(), colorId))
        }
    }

    private fun updateLocalIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        localIpText.text = "Local IP: ${address.hostAddress}"
                        return
                    }
                }
            }
        } catch (e: Exception) {
            localIpText.text = "Local IP: unable to detect"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statusReceiver)
    }
}