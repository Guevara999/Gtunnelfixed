// ProxyConnector.kt – connectViaProxy() method (partial)

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
    useEnhanced: Boolean = false   // ← this flag controls WebSocket mode
): Socket {
    // ... (same validation and connection setup)

    val output = socket.getOutputStream()
    val input = socket.getInputStream()

    if (usePayload && payload.isNotEmpty()) {
        val proxyString = "$targetHost:$targetPort"
        val processedPayload = PayloadProcessor.processPayload(
            payload, sshHost, sshPort.toString(), proxyString, userAgent
        )

        if (useEnhanced) {
            // ========== WEBSOCKET TUNNELING (like HTTP Custom) ==========
            LogManager.addLog("[Enhanced] Sending full payload (no split)")

            // Send the whole processed payload at once
            output.write(processedPayload.toByteArray())
            output.flush()

            // Read the server response to verify the upgrade
            try {
                socket.soTimeout = 10000 // 10s for response
                val reader = BufferedReader(InputStreamReader(input))
                var line: String? = reader.readLine()
                var upgradeSuccess = false

                while (line != null) {
                    LogManager.addLog("[Enhanced] Response: $line")
                    if (line.startsWith("HTTP/1.1 101")) {
                        upgradeSuccess = true
                        LogManager.addLog("[Enhanced] ✅ WebSocket upgrade successful!")
                        break
                    }
                    if (line.isEmpty()) {
                        // End of headers
                        break
                    }
                    line = reader.readLine()
                }

                if (!upgradeSuccess) {
                    // Still continue – some servers may not return 101 but still work
                    LogManager.addLog("[Enhanced] ⚠️ No 101 response, but continuing...")
                }
            } catch (e: Exception) {
                LogManager.addLog("[Enhanced] ⚠️ Response read timed out, continuing...")
            }

            // Reset timeout for SSH
            socket.soTimeout = 30000

        } else {
            // ========== STANDARD SPLIT PAYLOAD MODE ==========
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
        }
    }

    // ... (rest of the method: read status, validate, return socket)
}