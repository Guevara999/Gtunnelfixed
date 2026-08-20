package com.example.sshproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogsFragment : Fragment() {

    private lateinit var logText: TextView
    private var copyFab: FloatingActionButton? = null
    private var shareFab: FloatingActionButton? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)

        logText = view.findViewById(R.id.logText)

        try {
            copyFab = view.findViewById(R.id.copyLogsButton)
            shareFab = view.findViewById(R.id.shareLogsButton)

            copyFab?.setOnClickListener { copyLogs() }
            shareFab?.setOnClickListener { shareLogs() }
        } catch (e: Exception) {
            // If FABs are missing, just ignore – the text view still works
            e.printStackTrace()
        }

        logText.setOnLongClickListener {
            copyLogs()
            true
        }

        // Start a coroutine that updates the log view every 500ms
        lifecycleScope.launch {
            while (true) {
                val combinedLogs = buildCombinedLogs()
                logText.text = combinedLogs
                delay(500)
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        // Update immediately on resume
        val combinedLogs = buildCombinedLogs()
        logText.text = combinedLogs
    }

    /**
     * Builds a combined log string from the app's in‑memory logs and the hev log file.
     */
    private fun buildCombinedLogs(): String {
        return runCatching {
            val appLogs = LogManager.getLogs().joinToString("\n")
            val hevLog = readHevLog()
            val separator = "\n\n--- HEV LOG (native tunnel) ---\n"
            appLogs + separator + hevLog
        }.getOrElse {
            "Error reading logs: ${it.message}"
        }
    }

    /**
     * Reads the hev log file from the app's internal storage.
     * Returns an empty string if the file doesn't exist or can't be read.
     */
    private suspend fun readHevLog(): String = withContext(Dispatchers.IO) {
        try {
            val hevLogFile = File(requireContext().filesDir, "hev.log")
            if (hevLogFile.exists()) {
                hevLogFile.readText().takeIf { it.isNotBlank() } ?: "(empty)"
            } else {
                "(hev.log not found)"
            }
        } catch (e: Exception) {
            "Error reading hev.log: ${e.message}"
        }
    }

    private fun copyLogs() {
        val text = logText.text.toString()
        if (text.isNotEmpty()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
            Toast.makeText(requireContext(), "Logs copied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "No logs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogs() {
        val text = logText.text.toString()
        if (text.isNotEmpty()) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share logs via"))
        } else {
            Toast.makeText(requireContext(), "No logs", Toast.LENGTH_SHORT).show()
        }
    }
}