package com.example.sshproxy.payload;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class PayloadProcessor {

    public static int rotateIndex = 0;

    public static String processPayload(String template, String host, String port, String proxy, String userAgent) {
        String payload = template;

        // --- Extract clean host (remove @username:password if present) ---
        String cleanHost = host;
        if (cleanHost.contains("@")) {
            cleanHost = cleanHost.substring(0, cleanHost.indexOf("@"));
        }
        // If host already contains a port, use it; otherwise append port
        String hostWithPort = cleanHost;
        if (port != null && !port.isEmpty() && !cleanHost.contains(":")) {
            hostWithPort = cleanHost + ":" + port;
        }

        // Basic replacements
        payload = payload.replace("[crlf]", "\r\n");
        payload = payload.replace("[host]", hostWithPort);   // <-- FIXED
        payload = payload.replace("[rlb]", hostWithPort);
        payload = payload.replace("[port]", port);

        if (proxy != null && !proxy.isEmpty()) {
            payload = payload.replace("[proxy]", proxy);
        }

        if (userAgent == null || userAgent.isEmpty()) {
            userAgent = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36";
        }
        payload = payload.replace("[ua]", userAgent);

        payload = payload.replace("[https/host]", "https://" + hostWithPort);

        // Normalize raw newlines
        payload = payload.replaceAll("(?<!\\r)\\n", "\r\n");

        // Rotation
        Pattern rotatePattern = Pattern.compile("\\[rotate=([^\\]]+)\\]");
        Matcher rotateMatcher = rotatePattern.matcher(payload);
        if (rotateMatcher.find()) {
            String[] hosts = rotateMatcher.group(1).split(";");
            String selectedHost = hosts[rotateIndex % hosts.length];
            payload = payload.replace(rotateMatcher.group(0), selectedHost);
        }

        return payload;
    }

    public static String[] splitPayload(String payload) {
        return payload.split("\\[split\\]");
    }

    public static void resetRotateIndex() {
        rotateIndex = 0;
    }
}