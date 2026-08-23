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
import java.util.concurrent.Semaphore

class LocalSocks5Proxy(private val sshSession: Session) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var port = 0

    private val connectionSemaphore = Semaphore(1)

    fun start(bindAddress: String = "0.0.0.0"): Int {
        serverSocket = ServerSocket(0, 1, InetAddress.getByName(bindAddress))
        port = serverSocket!!.localPort
        isRunning = true
        LogManager.addLog("[SOCKS5] Proxy started on $bindAddress:$port (strict single‑connection)")
        Thread { acceptLoop() }.start()
        return port
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                connectionSemaphore.acquire()
                val client = serverSocket!!.accept()
                Thread {
                    try {
                        handleClient(client)
                    } finally {
                        connectionSemaphore.release()
                    }
                }.start()
            } catch (e: InterruptedException) {
                // ignore
            } catch (e: Exception) {
                if (isRunning) {
                    LogManager.addLog("[SOCKS5] Accept error: ${e.message}")
                }
                connectionSemaphore.release()
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 handshake
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

            // Parse request
            val ver = input.read()
            if (ver != 0x05) {
                LogManager.addLog("[SOCKS5-DBG] Invalid request VER: $ver")
                client.close()
                return
            }
            val cmd = input.read()
            if (cmd != 0x01) {
                LogManager.addLog("[SOCKS5-DBG] Unsupported command: $cmd (only CONNECT)")
                client.close()
                return
            }
            input.read() // RSV
            val addrType = input.read()
            LogManager.addLog("[SOCKS5-DBG] Address type: $addrType")
            val destHost = when (addrType) {
                0x01 -> {
                    val ip = ByteArray(4)
                    input.read(ip)
                    ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> {
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    String(domain)
                }
                0x04 -> {
                    val ip = ByteArray(16)
                    input.read(ip)
                    "::1"
                }
                else -> {
                    LogManager.addLog("[SOCKS5-DBG] Unsupported addr type: $addrType")
                    client.close()
                    return
                }
            }
            val destPort = (input.read() shl 8) or input.read()
            LogManager.addLog("[SOCKS5-DBG] CONNECT to $destHost:$destPort")

            // Open SSH channel
            try {
                val channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(destHost)
                channel.setPort(destPort)
                LogManager.addLog("[SOCKS5-DBG] Channel connecting (30s timeout)...")
                channel.connect(30000)
                LogManager.addLog("[SOCKS5-DBG] Channel connected")

                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                output.flush()
                LogManager.addLog("[SOCKS5-DBG] Success response sent")

                val clientIn = client.getInputStream()
                val clientOut = client.getOutputStream()
                val channelIn = channel.getInputStream()
                val channelOut = channel.getOutputStream()

                // Relay with idle timeout
                val idleTimeoutMs = 2000L
                relayWithTimeout(clientIn, channelOut, "client→ssh", idleTimeoutMs)
                relayWithTimeout(channelIn, clientOut, "ssh→client", idleTimeoutMs)

                clientIn.close()
                channelIn.close()
                channelOut.close()
                channel.disconnect()
                client.close()
                LogManager.addLog("[SOCKS5-DBG] Relay complete for $destHost:$destPort")

            } catch (e: JSchException) {
                LogManager.addLog("[SOCKS5-DBG] ❌ JSchException: ${e.message}")
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                output.flush()
                client.close()
            } catch (e: Exception) {
                LogManager.addLog("[SOCKS5-DBG] ❌ Exception: ${e.message}")
                client.close()
            }

        } catch (e: Exception) {
            if (!(e is SocketException && e.message?.contains("Socket closed") == true)) {
                LogManager.addLog("[SOCKS5] Error: ${e.message}")
            }
        }
    }

    private fun relayWithTimeout(input: InputStream, output: OutputStream, name: String, idleTimeoutMs: Long) {
        Thread {
            try {
                val buffer = ByteArray(8192)
                var lastActivity = System.currentTimeMillis()
                while (true) {
                    val available = input.available()
                    if (available > 0) {
                        val len = input.read(buffer, 0, minOf(buffer.size, available))
                        if (len <= 0) break
                        output.write(buffer, 0, len)
                        output.flush()
                        lastActivity = System.currentTimeMillis()
                    } else {
                        if (System.currentTimeMillis() - lastActivity > idleTimeoutMs) {
                            LogManager.addLog("[SOCKS5-DBG] $name idle timeout")
                            break
                        }
                        Thread.sleep(100)
                    }
                }
            } catch (_: Exception) {}
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
