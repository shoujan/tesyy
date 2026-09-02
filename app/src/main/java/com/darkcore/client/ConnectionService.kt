package com.darkcore.client

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {
    private val channelId = "darkcore_connection"
    private val notificationId = 1001
    private var webSocket: WebSocket? = null
    private var reconnectDelay = 2000L
    private val handler = Handler(Looper.getMainLooper())
    private val url = "wss://remote-test.shoujansapkota.com.np/ws"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, notification("DarkCore: connecting"))
        connect()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "DarkCore Connection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DarkCore Client")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification(text))
        sendStatus(text)
    }

    private fun sendStatus(text: String) {
        sendBroadcast(Intent("com.darkcore.client.STATUS").putExtra("status", text))
    }

    private fun connect() {
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelay = 2000L
                update("Connected")
                ws.send(JSONObject().put("type","status")
                    .put("camera", cameraState())
                    .put("microphone", micState())
                    .put("screen","not implemented").toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "ping" -> ws.send("""{"type":"pong"}""")
                        "request_camera" -> {
                            // Android camera permission is requested by the Activity.
                            sendStatus("Host requested camera — open the app to authorize")
                        }
                        "request_microphone" -> {
                            sendStatus("Host requested microphone — open the app to authorize")
                        }
                        "stop_camera" -> sendStatus("Camera stopped")
                        "stop_microphone" -> sendStatus("Microphone stopped")
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                update("Disconnected — reconnecting")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                update("Disconnected — reconnecting")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ connect() }, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(60000L)
    }

    private fun cameraState() =
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) "permission_granted" else "permission_needed"

    private fun micState() =
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "permission_granted" else "permission_needed"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        webSocket?.close(1000, "service stopped")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
