package com.darkcore.client

import android.Manifest
import android.app.*
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ConnectionService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.darkcore.client.CONNECT"
        const val ACTION_DISCONNECT = "com.darkcore.client.DISCONNECT"
        const val ACTION_START_SCREEN = "com.darkcore.client.START_SCREEN"
        const val ACTION_STOP_SCREEN = "com.darkcore.client.STOP_SCREEN"
        const val EXTRA_HOST = "host"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STATUS = "com.darkcore.client.STATUS"
        const val EXTRA_STATUS = "status"
        const val ACTION_REQUEST_SCREEN_PERMISSION = "com.darkcore.client.REQUEST_SCREEN_PERMISSION"
        const val ACTION_OPEN_SCREEN_CONSENT = "com.darkcore.client.OPEN_SCREEN_CONSENT"

        private const val CHANNEL_ID = "darkcore_connection"
        private const val NOTIFICATION_ID = 1001
        private const val CAMERA_FRAME_INTERVAL_MS = 333L
        private const val SCREEN_FRAME_INTERVAL_MS = 250L
    }

    private var socket: WebSocket? = null
    private var host = ""
    private var retrySeconds = 2L
    private var reconnecting = false

    // Camera
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraRunning = false
    private var lastCameraFrameAt = 0L
    private var requestedCameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var requestedMicSource = MediaRecorder.AudioSource.MIC

    // Microphone
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var microphoneRunning = false

    // Screen projection
    private var mediaProjection: MediaProjection? = null
    private var screenReader: ImageReader? = null
    private var screenVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenThread: HandlerThread? = null
    private var screenHandler: Handler? = null
    private var screenRunning = false
    private var lastScreenFrameAt = 0L

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
                startForegroundForCurrentState()
                connect()
            }
            ACTION_START_SCREEN -> {
                host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { host }
                startForegroundForScreen()
                connect()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, ActivityResultCodes.CANCELLED)
                val data = intent.parcelableIntent(EXTRA_RESULT_DATA)
                if (resultCode == ActivityResultCodes.OK && data != null) {
                    startScreenProjection(resultCode, data)
                } else {
                    sendError("Invalid screen-sharing authorization")
                }
            }
            ACTION_STOP_SCREEN -> stopScreenProjection()
            ACTION_DISCONNECT -> {
                reconnecting = false
                stopCamera()
                stopMicrophone()
                stopScreenProjection()
                socket?.close(1000, "User disconnected")
                socket = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundForCurrentState() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification("Connecting — camera/mic ready when requested"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("Connecting"))
        }
    }

    private fun startForegroundForScreen() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification("Screen sharing active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("Screen sharing active"))
        }
    }

    private fun connect() {
        if (host.isBlank() || socket != null) return
        val clean = host.trim().trimEnd('/')
        val wsUrl = when {
            clean.startsWith("wss://", ignoreCase = true) -> {
                clean.removeSuffix("/") + if (clean.endsWith("/ws", ignoreCase = true)) "" else "/ws"
            }
            clean.startsWith("https://", ignoreCase = true) -> {
                "wss://${clean.removePrefix("https://")}/ws"
            }
            clean.startsWith("http://", ignoreCase = true) -> {
                "ws://${clean.removePrefix("http://")}/ws"
            }
            else -> {
                broadcast("ERROR: Invalid server URL. Use https:// or wss://")
                return
            }
        }
        broadcast("Connecting…")

        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    retrySeconds = 2
                    reconnecting = false
                    updateNotification(currentNotificationText())
                    broadcast("CONNECTED")
                    sendStatus(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type")) {
                            "ping" -> webSocket.send(JSONObject().put("type", "pong").toString())
                            "request_camera" -> {
                                requestedCameraFacing = if (msg.optString("facing", "back").equals("front", true)) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                                if (hasPermission(Manifest.permission.CAMERA)) startCamera() else sendError("Camera permission is not granted")
                            }
                            "stop_camera" -> stopCamera()
                            "request_microphone" -> {
                                requestedMicSource = when (msg.optString("source", "mic").lowercase()) {
                                    "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
                                    "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                                    else -> MediaRecorder.AudioSource.MIC
                                }
                                if (hasPermission(Manifest.permission.RECORD_AUDIO)) startMicrophone() else sendError("Microphone permission is not granted")
                            }
                            "stop_microphone" -> stopMicrophone()
                            "request_screen" -> {
                                // A running projection can be used immediately. A new projection requires
                                // Android's visible system consent dialog, which is requested by the Activity.
                                if (screenRunning) {
                                    sendStatus()
                                } else {
                                    broadcast(ACTION_REQUEST_SCREEN_PERMISSION)
                                    broadcast("SCREEN_PERMISSION_REQUIRED")
                                }
                            }
                            "stop_screen" -> stopScreenProjection()
                        }
                    } catch (_: Exception) {
                        // Ignore malformed control messages.
                    }
                    sendStatus(webSocket)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    stopCamera()
                    stopMicrophone()
                    // Keep an active screen projection alive; it can reconnect later.
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

    private fun currentNotificationText(): String = when {
        screenRunning -> "Screen sharing active"
        cameraRunning && microphoneRunning -> "Camera + microphone sharing active"
        cameraRunning -> "Camera sharing active"
        microphoneRunning -> "Microphone sharing active"
        else -> "Connected"
    }

    private fun sendStatus(ws: WebSocket? = socket) {
        ws?.send(
            JSONObject()
                .put("type", "status")
                .put("camera", if (cameraRunning) "sharing" else if (hasPermission(Manifest.permission.CAMERA)) "permission_granted" else "permission_needed")
                .put("microphone", if (microphoneRunning) "sharing" else if (hasPermission(Manifest.permission.RECORD_AUDIO)) "permission_granted" else "permission_needed")
                .put("screen", if (screenRunning) "sharing" else "permission_required")
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
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == requestedCameraFacing
            } ?: manager.cameraIdList.firstOrNull() ?: run { sendError("No camera found"); return }

            cameraThread = HandlerThread("DarkCoreCamera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val target = sizes.filter { it.width <= 640 && it.height <= 480 }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { abs(it.width * it.height - 640 * 480) }
                ?: android.util.Size(640, 480)

            imageReader = ImageReader.newInstance(target.width, target.height, ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastCameraFrameAt < CAMERA_FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastCameraFrameAt = now
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    sendBinaryMedia(
                        mediaType = 1,
                        metadata = JSONObject()
                            .put("type", "camera_frame")
                            .put("mime", "image/jpeg")
                            .put("width", target.width)
                            .put("height", target.height),
                        payload = bytes,
                        maxQueueBytes = 1024L * 1024L
                    )
                } finally { image.close() }
            }, cameraHandler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader!!.surface
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraSession = session
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    }.build()
                                    session.setRepeatingRequest(request, null, cameraHandler)
                                    cameraRunning = true
                                    lastCameraFrameAt = 0L
                                    updateNotification(currentNotificationText())
                                    broadcast("CAMERA_SHARING")
                                    sendStatus()
                                } catch (e: Exception) {
                                    sendError("Camera capture failed: ${e.message}")
                                    stopCamera()
                                }
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
                override fun onError(camera: CameraDevice, error: Int) { sendError("Camera error: $error"); stopCamera() }
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
        updateNotification(currentNotificationText())
        if (socket != null) sendStatus()
    }

    private fun startMicrophone() {
        if (microphoneRunning || !hasPermission(Manifest.permission.RECORD_AUDIO)) return
        try {
            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuffer <= 0) { sendError("Microphone buffer unavailable"); return }
            val chunkSize = sampleRate / 20 * 2
            val bufferSize = max(minBuffer, chunkSize * 2)
            audioRecord = AudioRecord(requestedMicSource, sampleRate, channel, encoding, bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { sendError("Microphone could not be initialized"); stopMicrophone(); return }
            audioRecord?.startRecording()
            microphoneRunning = true
            updateNotification(currentNotificationText())
            broadcast("MICROPHONE_SHARING")
            sendStatus()
            audioThread = Thread {
                val buffer = ByteArray(chunkSize)
                while (microphoneRunning) {
                    val read = runCatching { audioRecord?.read(buffer, 0, buffer.size) ?: -1 }.getOrDefault(-1)
                    if (read > 0 && microphoneRunning) {
                        sendBinaryMedia(
                            mediaType = 2,
                            metadata = JSONObject()
                                .put("type", "audio_chunk")
                                .put("sample_rate", sampleRate)
                                .put("channels", 1)
                                .put("encoding", "pcm_s16le"),
                            payload = buffer.copyOf(read),
                            maxQueueBytes = 256L * 1024L
                        )
                    }
                }
            }.also { it.start() }
        } catch (e: SecurityException) {
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
        updateNotification(currentNotificationText())
        if (socket != null) sendStatus()
    }

    /** Starts an Android-approved MediaProjection session supplied by the visible Activity consent flow. */
    private fun startScreenProjection(resultCode: Int, data: Intent) {
        if (screenRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            sendError("Screen sharing requires Android 5.0 or newer")
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection?.stop()
            mediaProjection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            val rawWidth = resources.displayMetrics.widthPixels
            val rawHeight = resources.displayMetrics.heightPixels
            val scale = min(1.0, 1280.0 / max(rawWidth, rawHeight).toDouble())
            val width = max(320, (rawWidth * scale).toInt() and -2)
            val height = max(320, (rawHeight * scale).toInt() and -2)

            screenThread = HandlerThread("DarkCoreScreen").also { it.start() }
            screenHandler = Handler(screenThread!!.looper)
            screenReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            screenReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenFrameAt < SCREEN_FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastScreenFrameAt = now
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val paddedWidth = image.width + rowPadding / pixelStride
                    val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    buffer.rewind()
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    bitmap.recycle()

                    val outBitmap = if (cropped.width > 1280 || cropped.height > 720) {
                        val s = min(1280.0 / cropped.width, 720.0 / cropped.height)
                        Bitmap.createScaledBitmap(cropped, max(2, (cropped.width * s).toInt() and -2), max(2, (cropped.height * s).toInt() and -2), true).also { cropped.recycle() }
                    } else cropped

                    val outputWidth = outBitmap.width
                    val outputHeight = outBitmap.height
                    val out = ByteArrayOutputStream()
                    outBitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)
                    outBitmap.recycle()
                    sendBinaryMedia(
                        mediaType = 3,
                        metadata = JSONObject()
                            .put("type", "screen_frame")
                            .put("mime", "image/jpeg")
                            .put("width", outputWidth)
                            .put("height", outputHeight),
                        payload = out.toByteArray(),
                        maxQueueBytes = 1024L * 1024L
                    )
                } catch (e: Exception) {
                    // Keep the projection alive; a single frame failure should not stop sharing.
                } finally { image.close() }
            }, screenHandler)

            val density = metrics.densityDpi
            screenVirtualDisplay = mediaProjection!!.createVirtualDisplay(
                "DarkCoreScreen",
                width,
                height,
                density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenReader!!.surface,
                null,
                screenHandler
            )

            screenRunning = true
            lastScreenFrameAt = 0L
            updateNotification(currentNotificationText())
            broadcast("SCREEN_SHARING")
            sendStatus()
        } catch (e: SecurityException) {
            sendError("Screen projection security restriction: ${e.message}")
            stopScreenProjection()
        } catch (e: Exception) {
            sendError("Screen sharing failed: ${e.message}")
            stopScreenProjection()
        }
    }

    private fun stopScreenProjection() {
        screenRunning = false
        runCatching { screenVirtualDisplay?.release() }
        screenVirtualDisplay = null
        runCatching { screenReader?.close() }
        screenReader = null
        screenThread?.quitSafely()
        screenThread = null
        screenHandler = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        updateNotification(currentNotificationText())
        broadcast("SCREEN_STOPPED")
        if (socket != null) sendStatus()
    }

    /**
     * Binary media frame format used by the Android client:
     *
     *   1 byte   media type (1=camera, 2=audio, 3=screen)
     *   4 bytes  big-endian metadata JSON length
     *   N bytes  UTF-8 metadata JSON
     *   remaining bytes = raw media payload
     *
     * This avoids Base64 overhead and lets the Host decode the payload
     * without parsing large JSON strings.
     */
    private fun sendBinaryMedia(
        mediaType: Int,
        metadata: JSONObject,
        payload: ByteArray,
        maxQueueBytes: Long
    ) {
        val ws = socket ?: return
        if (ws.queueSize() > maxQueueBytes) return

        val header = metadata.toString().toByteArray(Charsets.UTF_8)
        if (header.size > 16 * 1024) return

        val frame = ByteBuffer.allocate(1 + 4 + header.size + payload.size)
            .put(mediaType.toByte())
            .putInt(header.size)
            .put(header)
            .put(payload)
            .array()

        ws.send(frame.toByteString())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun broadcast(status: String) {
        if (status == ACTION_REQUEST_SCREEN_PERMISSION) {
            sendBroadcast(Intent(status).setPackage(packageName))
        } else {
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DarkCore connection", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when DarkCore connection or media sharing is active."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SCREEN_CONSENT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val screenPending = PendingIntent.getActivity(this, 2001, openIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DarkCore Client")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action.Builder(0, "Allow screen sharing", screenPending).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconnecting = false
        stopCamera()
        stopMicrophone()
        stopScreenProjection()
        socket?.close(1000, "Service stopped")
        socket = null
        super.onDestroy()
    }

    private object ActivityResultCodes {
        const val OK = Activity.RESULT_OK
        const val CANCELLED = Activity.RESULT_CANCELED
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntent(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)
}
