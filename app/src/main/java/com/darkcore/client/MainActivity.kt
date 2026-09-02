package com.darkcore.client

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val defaultHost = "https://remote-test.shoujansapkota.com.np"
    private lateinit var hostInput: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectionService.ACTION_STATUS) {
                status.text = intent.getStringExtra(ConnectionService.EXTRA_STATUS) ?: "Unknown"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        status = findViewById(R.id.status)
        connectButton = findViewById(R.id.connectButton)

        hostInput.setText(defaultHost)
        requestNotificationPermission()

        connectButton.setOnClickListener {
            if (connectButton.text.toString() == "CONNECT") {
                startConnection()
            } else {
                stopConnection()
            }
        }
    }

    private fun startConnection() {
        val host = hostInput.text.toString().trim()
        if (!host.startsWith("https://")) {
            status.text = "Use an HTTPS Cloudflare hostname."
            return
        }

        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_CONNECT
            putExtra(ConnectionService.EXTRA_HOST, host)
        }
        ContextCompat.startForegroundService(this, intent)
        connectButton.text = "DISCONNECT"
        status.text = "Connecting…"
    }

    private fun stopConnection() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_DISCONNECT
        }
        startService(intent)
        connectButton.text = "CONNECT"
        status.text = "Disconnected"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ConnectionService.ACTION_STATUS)
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }
}
