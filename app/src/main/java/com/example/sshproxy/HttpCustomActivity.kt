package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File
import java.util.Date

class HttpCustomActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            val color = when (status) {
                "Connected" -> android.R.color.holo_green_dark
                "Disconnected" -> android.R.color.holo_red_dark
                "Connecting..." -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_red_dark
            }
            val configFragment = supportFragmentManager.findFragmentByTag("f0") as? ConfigFragment
            configFragment?.updateStatus(status, color)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_http_custom)

        // ========== CRASH HANDLER ==========
        // Writes any uncaught exception to a file in internal storage.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(filesDir, "crash.log")
                crashFile.appendText("${Date()}\n${throwable.stackTraceToString()}\n\n")
            } catch (_: Exception) {
                // If we can't write the file, at least let the system handle it
            }
            // Re-throw to the default system handler (which will show the crash dialog)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Config"
                else -> "Logs"
            }
        }.attach()

        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, IntentFilter("VPN_STATUS"))
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }
}