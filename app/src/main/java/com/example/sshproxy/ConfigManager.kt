package com.example.sshproxy

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gtunnel_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Config(
        var sshDetails: String = "",
        var remoteProxy: String = "",
        var payload: String = "",
        var splitDelay: Int = 500,
        var dnsPrimary: String = "1.1.1.1",
        var dnsSecondary: String = "1.0.0.1",
        var enableCompression: Boolean = true,
        var alwaysReconnect: Boolean = false,
        var followRedirects: Boolean = true,
        var usePayload: Boolean = true,
        var proxySsl: Boolean = false,
        var mtu: Int = 1500,
        var sendBuffer: Int = 16384,
        var receiveBuffer: Int = 32768,
        var pingUrl: String = "https://dns.google",
        var pingInterval: Int = 2000,
        var pingTimeout: Int = 5000,
        var enhanced: Boolean = false   // <-- NEW
    )

    fun saveConfig(
        sshDetails: String,
        remoteProxy: String,
        payload: String,
        splitDelay: Int,
        dnsPrimary: String,
        dnsSecondary: String,
        enableCompression: Boolean,
        alwaysReconnect: Boolean,
        followRedirects: Boolean,
        usePayload: Boolean,
        proxySsl: Boolean,
        mtu: Int,
        sendBuffer: Int,
        receiveBuffer: Int,
        pingUrl: String,
        pingInterval: Int,
        pingTimeout: Int,
        enhanced: Boolean   // <-- NEW
    ) {
        val config = Config(
            sshDetails = sshDetails,
            remoteProxy = remoteProxy,
            payload = payload,
            splitDelay = splitDelay,
            dnsPrimary = dnsPrimary,
            dnsSecondary = dnsSecondary,
            enableCompression = enableCompression,
            alwaysReconnect = alwaysReconnect,
            followRedirects = followRedirects,
            usePayload = usePayload,
            proxySsl = proxySsl,
            mtu = mtu,
            sendBuffer = sendBuffer,
            receiveBuffer = receiveBuffer,
            pingUrl = pingUrl,
            pingInterval = pingInterval,
            pingTimeout = pingTimeout,
            enhanced = enhanced
        )
        val json = gson.toJson(config)
        prefs.edit().putString("config", json).apply()
    }

    fun getConfig(): Config {
        val json = prefs.getString("config", null)
        return if (json != null) {
            gson.fromJson(json, Config::class.java)
        } else {
            Config()
        }
    }
}