package com.darkcore.client

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
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
        private const val FRAME_INTERVAL_MS = 200L // about 5 fps for a simple test
    }

    private var socket: WebSocket? = null
    private var host: String = ""
    private var retrySeconds = 2L
    private var reconnecting = false

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraRunning = false
    private var lastFrameAt = 0L

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var microphoneRunning = false

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
                if (hasCameraAndMic()) {
                    startAsMediaService()
                } else {
                    startAsDataService()
                }
                connect()
            }
            ACTION_DISCONNECT -> {
                reconnecting = false
                stopCamera()
                stopMicrophone()
                socket?.close(1000, "User disconnected")
                socket = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startAsDataService() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification("Connecting — camera/mic permissions not ready"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("Connecting"))
        }
    }

    private fun startAsMediaService() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification("Connected — camera/mic ready when requested"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("Connected — camera/mic ready when requested"))
        }
    }

    private fun connect() {
        if (host.isBlank() || socket != null) return
        val clean = host.trim().trimEnd('/')
        if (!clean.startsWith("https://")) {
            broadcast("ERROR: Use an HTTPS Cloudflare hostname")
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
                    sendStatus(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type")) {
                            "ping" -> webSocket.send(JSONObject().put("type", "pong").toString())
                            "request_camera" -> {
                                if (hasPermission(Manifest.permission.CAMERA)) startCamera()
                                else sendError("Camera permission is not granted")
                            }
                            "stop_camera" -> stopCamera()
                            "request_microphone" -> {
                                if (hasPermission(Manifest.permission.RECORD_AUDIO)) startMicrophone()
                                else sendError("Microphone permission is not granted")
                            }
                            "stop_microphone" -> stopMicrophone()
                        }
                    } catch (_: Exception) {
                    }
                    sendStatus(webSocket)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    stopCamera()
                    stopMicrophone()
                    broadcast("Disconnected")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    stopCamera()
                    stopMicrophone()
                    broadcast("Disconnected: ${t.message ?: "network error"}")
                    scheduleReconnect()
                }
            }
        )
    }

    private fun sendStatus(ws: WebSocket? = socket) {
        ws?.send(
            JSONObject()
                .put("type", "status")
                .put("camera", if (cameraRunning) "sharing" else if (hasPermission(Manifest.permission.CAMERA)) "permission_granted" else "permission_needed")
                .put("microphone", if (microphoneRunning) "sharing" else if (hasPermission(Manifest.permission.RECORD_AUDIO)) "permission_granted" else "permission_needed")
                .put("screen", "not_implemented")
                .toString()
        )
    }

    private fun sendError(message: String) {
        socket?.send(JSONObject().put("type", "error").put("message", message).toString())
        broadcast("ERROR: $message")
    }

    private fun scheduleReconnect() {
        if (reconnecting || host.isBlank()) return
        reconnecting = true
        updateNotification("Reconnecting…")
        Thread {
            try { Thread.sleep(retrySeconds * 1000) } catch (_: InterruptedException) { return@Thread }
            reconnecting = false
            retrySeconds = min(retrySeconds * 2, 60)
            connect()
        }.start()
    }

    private fun startCamera() {
        if (cameraRunning || !hasPermission(Manifest.permission.CAMERA)) return
        try {
            val manager = getSystemService(CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                sendError("No camera found")
                return
            }

            cameraThread = HandlerThread("DarkCoreCamera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastFrameAt = now
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val encoded = Base64.getEncoder().encodeToString(bytes)
                    socket?.send(
                        JSONObject()
                            .put("type", "camera_frame")
                            .put("mime", "image/jpeg")
                            .put("data", encoded)
                            .toString()
                    )
                } finally {
                    image.close()
                }
            }, cameraHandler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader!!.surface
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraSession = session
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                }.build()
                                session.setRepeatingRequest(request, null, cameraHandler)
                                cameraRunning = true
                                updateNotification("Camera sharing active")
                                broadcast("CAMERA_SHARING")
                                sendStatus()
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                sendError("Camera session configuration failed")
                                stopCamera()
                            }
                        }, cameraHandler)
                    } catch (e: Exception) {
                        sendError("Camera start failed: ${e.message}")
                        stopCamera()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { stopCamera() }
                override fun onError(camera: CameraDevice, error: Int) {
                    sendError("Camera error: $error")
                    stopCamera()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            sendError("Camera permission/security restriction: ${e.message}")
        } catch (e: Exception) {
            sendError("Camera start failed: ${e.message}")
        }
    }

    private fun stopCamera() {
        cameraRunning = false
        runCatching { cameraSession?.close() }
        cameraSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        updateNotification(if (microphoneRunning) "Microphone sharing active" else "Connected")
        broadcast("CAMERA_STOPPED")
        if (socket != null) sendStatus()
    }

    private fun startMicrophone() {
        if (microphoneRunning || !hasPermission(Manifest.permission.RECORD_AUDIO)) return
        try {
            val sampleRate = 16000
            val channel = android.media.AudioFormat.CHANNEL_IN_MONO
            val encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuffer <= 0) {
                sendError("Microphone buffer unavailable")
                return
            }
            val bufferSize = maxOf(minBuffer, sampleRate / 5 * 2)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channel,
                encoding,
                bufferSize
            )
            audioRecord?.startRecording()
            microphoneRunning = true
            updateNotification("Microphone sharing active")
            broadcast("MICROPHONE_SHARING")
            sendStatus()

            audioThread = Thread {
                val buffer = ByteArray(sampleRate / 5 * 2)
                while (microphoneRunning) {
                    val read = runCatching { audioRecord?.read(buffer, 0, buffer.size) ?: -1 }.getOrDefault(-1)
                    if (read > 0 && microphoneRunning) {
                        val chunk = buffer.copyOf(read)
                        val encoded = Base64.getEncoder().encodeToString(chunk)
                        socket?.send(
                            JSONObject()
                                .put("type", "audio_chunk")
                                .put("sample_rate", sampleRate)
                                .put("channels", 1)
                                .put("encoding", "pcm_s16le")
                                .put("data", encoded)
                                .toString()
                        )
                    }
                }
            }.also { it.start() }
        } catch (SecurityException) {
            sendError("Microphone permission/security restriction")
            stopMicrophone()
        } catch (e: Exception) {
            sendError("Microphone start failed: ${e.message}")
            stopMicrophone()
        }
    }

    private fun stopMicrophone() {
        microphoneRunning = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        audioThread = null
        updateNotification(if (cameraRunning) "Camera sharing active" else "Connected")
        broadcast("MICROPHONE_STOPPED")
        if (socket != null) sendStatus()
    }

    private fun hasCameraAndMic(): Boolean =
        hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.RECORD_AUDIO)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
            channel.description = "Shows when DarkCore connection or media sharing is active."
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconnecting = false
        stopCamera()
        stopMicrophone()
        socket?.close(1000, "Service stopped")
        socket = null
        // Do not shut down the shared OkHttp dispatcher here; Android may recreate the service.
        super.onDestroy()
    }
}
