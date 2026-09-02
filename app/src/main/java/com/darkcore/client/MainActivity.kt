package com.darkcore.client

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val camera = result[Manifest.permission.CAMERA] == true || has(Manifest.permission.CAMERA)
            val mic = result[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)
            val notification = Build.VERSION.SDK_INT < 33 ||
                result[Manifest.permission.POST_NOTIFICATIONS] == true || has(Manifest.permission.POST_NOTIFICATIONS)

            status.text = "Camera: ${if (camera) "granted" else "denied"}\n" +
                    "Microphone: ${if (mic) "granted" else "denied"}\n" +
                    "Notifications: ${if (notification) "granted" else "denied"}"

            startService()
        }

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
        status = findViewById(R.id.status)
        requestAllMissingPermissions()
    }

    private fun requestAllMissingPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter { !has(it) }
        if (missing.isEmpty()) {
            startService()
        } else {
            status.text = "Requesting Camera, Microphone and Notification permissions…"
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startService() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_CONNECT
            putExtra(ConnectionService.EXTRA_HOST, "https://remote-test.shoujansapkota.com.np")
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ConnectionService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }
}
