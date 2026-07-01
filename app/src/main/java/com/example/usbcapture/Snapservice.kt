package com.example.usbcapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Onallo hatterszolgaltatas: WebSocketen a "photo" parancsra (beallithato) a
 * telefon HATSO kamerajaval keszit egy fenykepet - lezart kijelzonel is -,
 * autofokusz utan, VAKU NELKUL, majd feltolti SFTP-vel a beallitott szerverre,
 * es siker eseten kuld egy "uploaded" WebSocket uzenetet.
 *
 * Teljesen fuggetlen a CaptureService-tol es a PhotoWatchService-tol: kulon
 * ki/be kapcsolhato a fo kepernyorol, sajat WebSocket kapcsolatot tart fenn, es
 * ugyanazokat az SFTP beallitasokat olvassa az AppSettings-bol, mint a tobbi.
 *
 * A hatterbeli (elonezet nelkuli) fotozashoz "camera" tipusu eloterbeli
 * szolgaltatas kell (Android 14+ alatt kulon FGS-engedellyel), es a CAMERA
 * futasideju engedely. A kamerat mindig csak a keszites idejere nyitja meg,
 * utana azonnal elengedi.
 */
class SnapService : Service() {

    companion object {
        // a beallitasok valtozasakor (WS URL / parancs) ezzel kerjuk az ujratoltest
        const val ACTION_RELOAD = "com.example.usbcapture.SNAP_RELOAD"

        private const val TAG = "SnapService"
        private const val CHANNEL_ID = "snap_capture"
        private const val NOTIF_ID = 3
        private const val UPLOADED_MESSAGE = "uploaded"

        // ha az autofokusz nem all be ennyi ido alatt, akkor is elkeszul a kep
        private const val AF_TIMEOUT_MS = 2500L
        // a still JPEG felbontasat ennel nem engedjuk nagyobbra (memoria)
        private const val MAX_STILL_PX = 12_000_000L

        // ---- elo allapot: ezt olvassa a MainActivity a kijelzeshez ----
        @Volatile var running = false
        @Volatile var wsConnected = false
        @Volatile var captureCount = 0
        @Volatile var lastEvent = "-"
        @Volatile var lastFile = "-"
    }

    private var webSocket: WebSocket? = null
    @Volatile private var wsGeneration = 0

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connectWebSocket() }

    // az SFTP feltoltes kulon szalon (halozat)
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    // a kamera-muveletek sajat hatterszalon futnak
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // egyszerre csak egy keszites fusson
    private val capturing = AtomicBoolean(false)

    // kamera allapot
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewReader: ImageReader? = null   // YUV, csak a 3A (fokusz) beallasahoz
    private var stillReader: ImageReader? = null      // JPEG, a tenyleges kep
    private var sensorOrientation = 0
    private var afTriggered = false
    private var stillRequested = false

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wsConnected = false

        createNotificationChannel()
        startForegroundCompat()
        acquireWakeLock()

        val thread = HandlerThread("snap-camera").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        event("Vár a parancsra")
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            event("Beallitasok ujratoltve")
            connectWebSocket()
        }
        return START_STICKY
    }

    // ------------------------- WebSocket -------------------------

    private fun connectWebSocket() {
        if (!running) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val myGen = ++wsGeneration
        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null

        val url = AppSettings.wsUrl(this)
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myGen != wsGeneration) { ws.cancel(); return }
                wsConnected = true
                event("WebSocket csatlakozva")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (myGen != wsGeneration) return
                handleMessage(text)
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (myGen != wsGeneration) return
                handleMessage(bytes.utf8())
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (myGen != wsGeneration) return
                wsConnected = false
                event("WebSocket hiba: ${t.message}")
                reconnectLater()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (myGen != wsGeneration) return
                wsConnected = false
                reconnectLater()
            }
        })
    }

    private fun handleMessage(raw: String) {
        val msg = raw.trim()
        val cmd = AppSettings.snapCommand(this).trim()
        if (cmd.isNotEmpty() && msg.equals(cmd, ignoreCase = true)) {
            triggerCapture()
        }
    }

    private fun reconnectLater() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, 5000)
    }

    // ------------------------- Camera2 (hatso kamera, vaku nelkul) -------------------------

    private fun triggerCapture() {
        if (!capturing.compareAndSet(false, true)) {
            event("Fotózás már folyamatban, kihagyva")
            return
        }
        cameraHandler?.post { openCameraAndCapture() } ?: run {
            capturing.set(false)
        }
    }

    private fun openCameraAndCapture() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            event("Nincs kamera engedély")
            capturing.set(false)
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val camId = findBackCameraId(manager) ?: run {
                event("Nincs hátsó kamera")
                capturing.set(false)
                return
            }
            val ch = manager.getCameraCharacteristics(camId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val stillSize = chooseStillSize(map?.getOutputSizes(ImageFormat.JPEG))
            val previewSize = choosePreviewSize(map?.getOutputSizes(ImageFormat.YUV_420_888))

            stillReader = ImageReader.newInstance(
                stillSize.width, stillSize.height, ImageFormat.JPEG, 1
            ).apply { setOnImageAvailableListener({ r -> onStillAvailable(r) }, cameraHandler) }

            previewReader = ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2
            ).apply {
                // az elonezeti kepeket csak "elhasznaljuk", hogy a 3A (fokusz/expo) fusson
                setOnImageAvailableListener({ r ->
                    try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
                }, cameraHandler)
            }

            afTriggered = false
            stillRequested = false
            manager.openCamera(camId, stateCallback, cameraHandler)
        } catch (e: Exception) {
            event("Kamera megnyitás hiba: ${e.message}")
            closeCamera()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            try {
                val surfaces = listOf(previewReader!!.surface, stillReader!!.surface)
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, sessionCallback, cameraHandler)
            } catch (e: Exception) {
                event("Session hiba: ${e.message}")
                closeCamera()
            }
        }
        override fun onDisconnected(device: CameraDevice) { closeCamera() }
        override fun onError(device: CameraDevice, error: Int) {
            event("Kamera hiba: $error")
            closeCamera()
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            startPreviewAndFocus()
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            event("Session konfiguracio sikertelen")
            closeCamera()
        }
    }

    /** Folyamatos elonezet a fokusz beallasahoz, majd egyszeri AF-inditas. */
    private fun startPreviewAndFocus() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewReader!!.surface)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // vaku tiltasa: nincs auto-flash, a flash mod explicit OFF
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            session.setRepeatingRequest(builder.build(), afListener, cameraHandler)

            // egyszeri autofokusz-inditas
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), afListener, cameraHandler)
            afTriggered = true

            // ha az AF nem all be idoben, akkor is keszuljon el a kep
            cameraHandler?.postDelayed({
                if (!stillRequested) captureStill()
            }, AF_TIMEOUT_MS)
        } catch (e: Exception) {
            event("Elonezet/fokusz hiba: ${e.message}")
            closeCamera()
        }
    }

    private val afListener = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            if (!afTriggered || stillRequested) return
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            // ha nincs AF-allapot (fix fokusz), vagy a fokusz beallt -> keszul a kep
            if (afState == null ||
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
            ) {
                captureStill()
            }
        }
    }

    private fun captureStill() {
        if (stillRequested) return
        stillRequested = true
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(stillReader!!.surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            try { session.stopRepeating() } catch (_: Exception) {}
            session.capture(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            event("Kép készítés hiba: ${e.message}")
            closeCamera()
        }
    }

    private fun onStillAvailable(reader: ImageReader) {
        var bytes: ByteArray? = null
        try {
            reader.acquireNextImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                bytes = data
            }
        } catch (e: Exception) {
            event("Kép beolvasás hiba: ${e.message}")
        }

        // a kamerat mar el is engedhetjuk (a byte-okat kimasoltuk)
        closeCamera()

        val jpeg = bytes
        if (jpeg == null) {
            event("Üres kép")
            return
        }
        val name = "SNAP_${System.currentTimeMillis()}.jpg"
        uploadExecutor.execute { saveAndUpload(jpeg, name) }
    }

    private fun findBackCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return null
    }

    private fun chooseStillSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) return Size(1920, 1080)
        // a legnagyobb, de a megadott pixel-korlat alatti
        val capped = sizes.filter { it.width.toLong() * it.height <= MAX_STILL_PX }
        val pool = if (capped.isNotEmpty()) capped else sizes.toList()
        return pool.maxByOrNull { it.width.toLong() * it.height } ?: sizes[0]
    }

    private fun choosePreviewSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) return Size(1280, 720)
        // a legnagyobb, de 1280x720 alatti (a fokusz beallasahoz boven eleg)
        val under = sizes.filter { it.width <= 1280 && it.height <= 720 }
        return under.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: sizes[0]
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { previewReader?.close() } catch (_: Exception) {}
        previewReader = null
        try { stillReader?.close() } catch (_: Exception) {}
        stillReader = null
        afTriggered = false
        stillRequested = false
        capturing.set(false)
    }

    // ------------------------- Mentes + SFTP feltoltes -------------------------

    /** Az uploadExecutor szalon fut: elmenti a JPEG-et a galeriaba (DCIM/<album>),
     *  majd feltolti SFTP-vel, es siker eseten kuld egy "uploaded" uzenetet. */
    private fun saveAndUpload(jpeg: ByteArray, name: String) {
        val uri = saveToGallery(jpeg, name)
        if (uri == null) {
            event("Mentés hiba: $name")
            return
        }
        lastFile = name
        event("Kép elkészült: $name")

        if (!AppSettings.sftpEnabled(this)) {
            event("SFTP kikapcsolva, nem töltöm fel: $name")
            return
        }
        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            val host = AppSettings.sftpHost(this)
            val port = AppSettings.sftpPort(this)
            val user = AppSettings.sftpUser(this)
            val pass = AppSettings.sftpPass(this)
            val dir = AppSettings.sftpDir(this)

            val input = contentResolver.openInputStream(uri)
                ?: run { event("Megnyitás hiba: $name"); return }

            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session.setPassword(pass)
            val cfg = Properties()
            cfg["StrictHostKeyChecking"] = "no"
            session.setConfig(cfg)
            session.connect(15000)

            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15000)

            ensureRemoteDir(channel, dir)
            val remotePath = dir.trimEnd('/') + "/" + name
            input.use { channel.put(it, remotePath) }

            captureCount += 1
            event("SFTP feltöltve: $name")
            try { webSocket?.send(UPLOADED_MESSAGE) } catch (_: Exception) {}
        } catch (e: Exception) {
            event("SFTP hiba ($name): ${e.message}")
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }

    private fun saveToGallery(jpeg: ByteArray, name: String): Uri? {
        val album = AppSettings.album(this).trim().trim('/').ifEmpty { "DCIM2" }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$album")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    album
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(jpeg) }
                @Suppress("DEPRECATION")
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: Uri.fromFile(file)
            }
        } catch (e: Exception) {
            event("Mentés kivétel: ${e.message}")
            null
        }
    }

    private fun ensureRemoteDir(channel: ChannelSftp, dir: String) {
        val parts = dir.split("/").filter { it.isNotEmpty() }
        var path = ""
        for (p in parts) {
            path += "/$p"
            try {
                channel.stat(path)
            } catch (e: Exception) {
                try { channel.mkdir(path) } catch (_: Exception) {}
            }
        }
    }

    // ------------------------- Eloterbeli szolgaltatas / segedek -------------------------

    private fun event(msg: String) {
        lastEvent = msg
        Log.d(TAG, msg)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbcapture:snap")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Parancsra fotó (hátsó kamera)", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parancsra fotó (hátsó kamera)")
            .setContentText("Vár a WebSocket parancsra")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        wsConnected = false
        wsGeneration++
        reconnectHandler.removeCallbacks(reconnectRunnable)

        closeCamera()

        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}

        uploadExecutor.shutdown()
        cameraThread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
