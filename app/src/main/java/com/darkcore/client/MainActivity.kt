package com.darkcore.client

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var screenButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var autoResumeCheck: CheckBox

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startConnectionService()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, ConnectionService::class.java).apply {
                    action = ConnectionService.ACTION_START_SCREEN
                    putExtra(ConnectionService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ConnectionService.EXTRA_RESULT_DATA, result.data)
                    putExtra(ConnectionService.EXTRA_HOST, HOST_URL)
                }
                ContextCompat.startForegroundService(this, intent)
                status.text = "Screen sharing authorized — starting…"
            } else {
                status.text = "Screen sharing was cancelled"
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConnectionService.ACTION_STATUS -> {
                    status.text = intent.getStringExtra(ConnectionService.EXTRA_STATUS) ?: "Unknown"
                }
                ConnectionService.ACTION_REQUEST_SCREEN_PERMISSION -> {
                    requestScreenCapture()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        screenButton = findViewById(R.id.screenButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        autoResumeCheck = findViewById(R.id.autoResumeCheck)

        val prefs = getSharedPreferences("darkcore_prefs", MODE_PRIVATE)
        autoResumeCheck.isChecked = prefs.getBoolean("auto_resume_media", true)
        autoResumeCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_resume_media", checked).apply()
        }

        screenButton.setOnClickListener { requestScreenCapture() }
        accessibilityButton.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (intent?.action == ConnectionService.ACTION_OPEN_SCREEN_CONSENT) {
            window.decorView.post { requestScreenCapture() }
        }

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
            startConnectionService()
        } else {
            status.text = "Requesting Camera, Microphone and Notification permissions…"
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startConnectionService() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_CONNECT
            putExtra(ConnectionService.EXTRA_HOST, HOST_URL)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        status.text = "Requesting Android screen-sharing permission…"
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(ConnectionService.ACTION_STATUS)
                addAction(ConnectionService.ACTION_REQUEST_SCREEN_PERMISSION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    companion object {
        private const val HOST_URL = "wss://remote-test.shoujansapkota.com.np/ws"
    }
}
