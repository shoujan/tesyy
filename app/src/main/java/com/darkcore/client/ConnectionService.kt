package com.darkcore.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class ConnectionService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.darkcore.client.CONNECT"
        const val ACTION_DISCONNECT = "com.darkcore.client.DISCONNECT"
        const val EXTRA_HOST = "host"
        const val ACTION_STATUS = "com.darkcore.client.STATUS"
        const val EXTRA_STATUS = "status"

        private const val CHANNEL_ID = "darkcore_connection"
        private const val NOTIFICATION_ID = 1001
    }

    private var socket: WebSocket? = null
    private var host: String = ""
    private var retrySeconds = 2L
    private var reconnecting = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                startForeground(NOTIFICATION_ID, notification("Connecting…"))
                connect()
            }
            ACTION_DISCONNECT -> {
                reconnecting = false
                socket?.close(1000, "User disconnected")
                socket = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // START_STICKY asks Android to recreate the service after process death.
        // Android may still stop services for policy, battery, force-stop, reboot, etc.
        return START_STICKY
    }

    private fun connect() {
        if (host.isBlank() || socket != null) return

        val clean = host.trim().trimEnd('/')
        if (!clean.startsWith("https://")) {
            broadcast("ERROR: Use an HTTPS Cloudflare hostname")
            stopSelf()
            return
        }

        val wsUrl = "wss://${clean.removePrefix("https://")}/ws"
        broadcast("Connecting…")

        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    retrySeconds = 2
                    reconnecting = false
                    updateNotification("Connected")
                    broadcast("CONNECTED")
                    webSocket.send(JSONObject().put("type", "ping").toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    updateNotification("Connected")
                    broadcast("CONNECTED: $text")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    broadcast("Disconnected")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    broadcast("Disconnected: ${t.message ?: "network error"}")
                    scheduleReconnect()
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (reconnecting || host.isBlank()) return
        reconnecting = true
        updateNotification("Reconnecting…")

        Thread {
            try {
                Thread.sleep(retrySeconds * 1000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            reconnecting = false
            retrySeconds = min(retrySeconds * 2, 60)
            connect()
        }.start()
    }

    private fun broadcast(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DarkCore connection",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows when DarkCore is maintaining a connection."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DarkCore Client")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconnecting = false
        socket?.close(1000, "Service stopped")
        socket = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
