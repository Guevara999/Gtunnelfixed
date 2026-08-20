package com.example.sshproxy

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class LocalSocks5Proxy(private val sshSession: Session) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var port = 0

    fun start(bindAddress: String = "0.0.0.0"): Int {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName(bindAddress))
        port = serverSocket!!.localPort
        isRunning = true
        LogManager.addLog("[SOCKS5] Proxy started on $bindAddress:$port")
        Thread { acceptLoop() }.start()
        return port
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val client = serverSocket!!.accept()
                Thread { handleClient(client) }.start()
            } catch (e: Exception) {
                if (isRunning) {
                    LogManager.addLog("[SOCKS5] Accept error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // ---- SOCKS5 handshake ----
            LogManager.addLog("[SOCKS5-DBG] Reading handshake...")
            val version = input.read()
            if (version != 0x05) {
                LogManager.addLog("[SOCKS5-DBG] Invalid SOCKS version: $version")
                client.close()
                return
            }
            val nmethods = input.read()
            LogManager.addLog("[SOCKS5-DBG] nmethods = $nmethods")
            repeat(nmethods) { input.read() }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
            LogManager.addLog("[SOCKS5-DBG] Handshake response sent")

            // ---- Parse SOCKS5 request (correctly) ----
            val ver = input.read()
            if (ver != 0x05) {
                LogManager.addLog("[SOCKS5-DBG] Invalid request VER: $ver")
                client.close()
                return
            }
            val cmd = input.read()
            if (cmd != 0x01) {
                LogManager.addLog("[SOCKS5-DBG] Unsupported command: $cmd (only CONNECT supported)")
                client.close()
                return
            }
            input.read() // RSV
            val addrType = input.read()
            LogManager.addLog("[SOCKS5-DBG] Address type: $addrType")
            val destHost = when (addrType) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4)
                    input.read(ip)
                    ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    String(domain)
                }
                0x04 -> { // IPv6 (simplified)
                    val ip = ByteArray(16)
                    input.read(ip)
                    "::1"
                }
                else -> {
                    LogManager.addLog("[SOCKS5-DBG] Unsupported address type: $addrType")
                    client.close()
                    return
                }
            }
            val destPort = (input.read() shl 8) or input.read()
            LogManager.addLog("[SOCKS5-DBG] CONNECT to $destHost:$destPort")

            // ---- Open SSH direct-tcpip channel with timeout ----
            try {
                LogManager.addLog("[SOCKS5-DBG] Opening direct-tcpip channel...")
                val channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(destHost)
                channel.setPort(destPort)
                LogManager.addLog("[SOCKS5-DBG] Channel configured, connecting with 15s timeout...")
                channel.connect(15000)   // ← timeout added
                LogManager.addLog("[SOCKS5-DBG] Channel connected successfully")

                // Send success response
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                output.flush()
                LogManager.addLog("[SOCKS5-DBG] Success response sent")

                // Relay data
                val clientInput = client.getInputStream()
                val clientOutput = client.getOutputStream()
                val channelInput = channel.getInputStream()
                val channelOutput = channel.getOutputStream()

                relay(clientInput, channelOutput, "client->ssh")
                relay(channelInput, clientOutput, "ssh->client")

                clientInput.close()
                channel.disconnect()
                client.close()
                LogManager.addLog("[SOCKS5-DBG] Relay complete for $destHost:$destPort")

            } catch (e: JSchException) {
                LogManager.addLog("[SOCKS5-DBG] ❌ JSchException: ${e.message}")
                LogManager.addLog("[SOCKS5-DBG] Stack trace: ${e.stackTrace.joinToString("\n")}")
                // Send error response (0x05, 0x01 = general failure)
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                output.flush()
                client.close()
                return
            } catch (e: Exception) {
                LogManager.addLog("[SOCKS5-DBG] ❌ Exception: ${e.message}")
                LogManager.addLog("[SOCKS5-DBG] Stack trace: ${e.stackTrace.joinToString("\n")}")
                client.close()
                return
            }

        } catch (e: Exception) {
            if (!(e is SocketException && e.message?.contains("Socket closed") == true)) {
                LogManager.addLog("[SOCKS5] Error in handleClient: ${e.message}")
                LogManager.addLog("[SOCKS5] Stack trace: ${e.stackTrace.joinToString("\n")}")
            }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun relay(input: InputStream, output: OutputStream, name: String) {
        Thread {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    output.write(buffer, 0, len)
                    output.flush()
                }
            } catch (_: Exception) {
                // closed
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        LogManager.addLog("[SOCKS5] Proxy stopped")
    }
}