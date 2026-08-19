package com.example.sshproxy

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var configManager: ConfigManager
    private val VPN_REQUEST_CODE = 100

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
                startVpnWithPermissionCheck()
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

    // ---------- VPN Permission ----------
    private fun startVpnWithPermissionCheck() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            doStartVpnService()
        }
    }

    private fun doStartVpnService() {
        val serviceIntent = Intent(requireContext(), CustomVpnService::class.java)
        serviceIntent.action = CustomVpnService.ACTION_CONNECT
        serviceIntent.putExtra("sshHost", sshDetailsInput.text.toString())
        serviceIntent.putExtra("sshPort", "80")
        val sshParts = sshDetailsInput.text.toString().split("@")
        val credentials = sshParts.getOrNull(1)?.split(":") ?: listOf()
        serviceIntent.putExtra("sshUser", credentials.getOrNull(0) ?: "")
        serviceIntent.putExtra("sshPass", credentials.getOrNull(1) ?: "")
        val proxyParts = proxyInput.text.toString().split(":")
        serviceIntent.putExtra("proxyHost", proxyParts.getOrNull(0) ?: "")
        serviceIntent.putExtra("proxyPort", proxyParts.getOrNull(1) ?: "80")
        serviceIntent.putExtra("payload", payloadInput.text.toString())
        serviceIntent.putExtra("splitDelay", splitDelayInput.text.toString().toIntOrNull() ?: 500)
        serviceIntent.putExtra("dnsPrimary", dnsPrimaryInput.text.toString())
        serviceIntent.putExtra("dnsSecondary", dnsSecondaryInput.text.toString())
        serviceIntent.putExtra("enableCompression", enableCompressionCheck.isChecked)
        serviceIntent.putExtra("alwaysReconnect", alwaysReconnectCheck.isChecked)
        serviceIntent.putExtra("followRedirects", followRedirectsCheck.isChecked)
        serviceIntent.putExtra("usePayload", usePayloadCheck.isChecked)
        serviceIntent.putExtra("proxySsl", proxySslCheck.isChecked)
        serviceIntent.putExtra("mtu", mtuInput.text.toString().toIntOrNull() ?: 1500)
        serviceIntent.putExtra("sendBuffer", sendBufferInput.text.toString().toIntOrNull() ?: 16384)
        serviceIntent.putExtra("receiveBuffer", receiveBufferInput.text.toString().toIntOrNull() ?: 32768)
        serviceIntent.putExtra("pingUrl", pingUrlInput.text.toString())
        serviceIntent.putExtra("pingInterval", pingIntervalInput.text.toString().toIntOrNull() ?: 2000)
        serviceIntent.putExtra("pingTimeout", pingTimeoutInput.text.toString().toIntOrNull() ?: 10000)
        serviceIntent.putExtra("enhanced", enhancedToggle.isChecked)

        requireContext().startService(serviceIntent)
        toggleButton.text = "Disconnect"
        Toast.makeText(requireContext(), "Connecting...", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(requireContext(), "VPN permission granted", Toast.LENGTH_SHORT).show()
                doStartVpnService()
            } else {
                Toast.makeText(requireContext(), "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Config Save/Load ----------
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

    private fun stopVpnService() {
        val serviceIntent = Intent(requireContext(), CustomVpnService::class.java)
        serviceIntent.action = CustomVpnService.ACTION_DISCONNECT
        requireContext().startService(serviceIntent)
        // The button will be updated by the statusReceiver when "Disconnected" is broadcast
        Toast.makeText(requireContext(), "Disconnecting...", Toast.LENGTH_SHORT).show()
    }

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