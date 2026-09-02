package com.darkcore.client

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val camera = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val mic = result[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val notification = if (android.os.Build.VERSION.SDK_INT >= 33)
                result[Manifest.permission.POST_NOTIFICATIONS] == true ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true

            status.text = "Permissions: camera=$camera, mic=$mic, notification=$notification"
        }

    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra("status") ?: "Connected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32,32,32,32)
        }

        val title = TextView(this).apply {
            text = "DarkCore Client"
            textSize = 24f
        }
        status = TextView(this).apply {
            text = "Starting..."
            textSize = 16f
            setPadding(0,24,0,24)
        }

        layout.addView(title)
        layout.addView(status)
        setContentView(layout)

        requestAllPermissionsOnce()
        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
    }

    private fun requestAllPermissionsOnce() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter("com.darkcore.client.STATUS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }
}
