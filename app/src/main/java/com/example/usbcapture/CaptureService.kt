package com.example.usbcapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureService : Service() {

    companion object {
        // a beallitasok az AppSettings-ben vannak (a felhasznalo szerkesztheti)

        // a MainActivity ezzel keri a WebSocket ujracsatlakozast uj URL utan
        const val ACTION_RECONNECT_WS = "com.example.usbcapture.RECONNECT_WS"

        // mod lekerdezes / allitas a szerveren ("mode?", "setmode0", "setmode1")
        const val ACTION_QUERY_MODE = "com.example.usbcapture.QUERY_MODE"
        const val ACTION_SET_MODE = "com.example.usbcapture.SET_MODE"
        const val EXTRA_MODE = "mode"

        private const val TRIGGER_MESSAGE = "takeapicture"

        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "usb_capture"
        private const val NOTIF_ID = 1

        // ---- elo allapot: ezt olvassa a MainActivity a kijelzeshez ----
        @Volatile var serviceRunning = false
        @Volatile var wsConnected = false
        @Volatile var cameraReady = false
        @Volatile var cameraInfo = "-"
        @Volatile var photoCount = 0
        @Volatile var mediaCount = 0
        @Volatile var lastEvent = "-"

        // a szervertol lekerdezett mod: -1 = ismeretlen, 0 = Remote, 1 = Audio
        @Volatile var deviceMode = -1
        // true, amig egy mode? / setmodeX valaszara varunk
        @Volatile var modePending = false
    }

    private var cameraHelper: ICameraHelper? = null
    private var dummyTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    @Volatile private var previewing = false

    private var webSocket: WebSocket? = null
    // minden uj kapcsolat noveli; a regi (stale) listener-hivasokat ez alapjan eldobjuk,
    // igy soha nem marad ket elo kapcsolat, ami duplan kezelne ugyanazt az uzenetet
    @Volatile private var wsGeneration = 0
    private val reconnectRunnable = Runnable { connectWebSocket() }

    // media parancs debounce: ugyanazt a billentyut <350 ms-en belul nem kuldjuk ujra,
    // hogy egy esetleges duplikalt uzenet ne szamitson "dupla koppintasnak" (= next)
    private var lastMediaKey = 0
    private var lastMediaAt = 0L

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        wsConnected = false
        cameraReady = false
        createNotificationChannel()
        startForegroundCompat()
        acquireWakeLock()
        toast("Szolgaltatas elindult")
        initCamera()
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECONNECT_WS -> {
                // a beallitasok valtoztak: zarjuk a jelenlegi kapcsolatot, az
                // onClosed -> reconnectLater() mar az uj URL-lel csatlakozik vissza
                toast("Beallitasok mentve, WebSocket ujracsatlakozas")
                try { webSocket?.close(1000, "settings changed") } catch (_: Exception) {}
                wsConnected = false
                deviceMode = -1
                modePending = false
            }
            ACTION_QUERY_MODE -> queryMode()
            ACTION_SET_MODE -> {
                val target = intent.getIntExtra(EXTRA_MODE, -1)
                if (target == 0 || target == 1) setMode(target)
            }
        }
        return START_STICKY
    }

    // ------------------------- Mod kezeles -------------------------

    private fun queryMode() {
        if (!wsConnected || webSocket == null) {
            toast("Mod lekerdezes nem megy: nincs WebSocket kapcsolat")
            return
        }
        modePending = true
        webSocket?.send("mode?")
    }

    private fun setMode(target: Int) {
        if (!wsConnected || webSocket == null) {
            toast("Mod valtas nem megy: nincs WebSocket kapcsolat")
            return
        }
        modePending = true
        webSocket?.send("setmode$target")
        toast("Mod valtas kuldve: setmode$target")
    }

    /** true, ha az uzenet mod-valasz volt ("mode0" / "mode1") es feldolgoztuk */
    private fun handleModeMessage(text: String): Boolean {
        return when (text.trim().lowercase(Locale.US)) {
            "mode0" -> {
                deviceMode = 0
                modePending = false
                toast("Mod: Remote Controller")
                true
            }
            "mode1" -> {
                deviceMode = 1
                modePending = false
                toast("Mod: Audio Controller")
                true
            }
            else -> false
        }
    }

    // ------------------------- Media vezerles -------------------------

    /**
     * true, ha az uzenet egy media-parancs volt (kovetkezo / elozo /
     * lejatszas-szunet) es kuldtunk ra rendszer-mediabillentyut.
     * A parancsszovegek az AppSettings-bol jonnek (szerkesztheto).
     * Ures parancsra nem matchel.
     */
    private fun handleMediaCommand(text: String): Boolean {
        val t = text.trim().lowercase(Locale.US)
        if (t.isEmpty()) return false

        val next = AppSettings.cmdNext(this).trim().lowercase(Locale.US)
        val prev = AppSettings.cmdPrev(this).trim().lowercase(Locale.US)
        val pp = AppSettings.cmdPlayPause(this).trim().lowercase(Locale.US)

        if (next.isNotEmpty() && t == next) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); toast("Media: kovetkezo szam"); return true
        }
        if (prev.isNotEmpty() && t == prev) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); toast("Media: elozo szam"); return true
        }
        if (pp.isNotEmpty() && t == pp) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); toast("Media: lejatszas / szunet"); return true
        }
        return false
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            // ugyanaz a billentyu 350 ms-en belul -> duplikatum, eldobjuk
            // (kulonben a lejatszo "dupla koppintasnak" venne a pause-t es kovetkezore lepne)
            if (keyCode == lastMediaKey && now - lastMediaAt < 350L) return
            lastMediaKey = keyCode
            lastMediaAt = now

            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // egyertelmu, rovid lenyomas: azonos downTime a DOWN/UP-on
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            mediaCount += 1
        } catch (e: Exception) {
            toast("Media hiba: ${e.message}")
        }
    }

    // esemeny rogzitese: az "Utolso esemeny" sorban es a logban jelenik meg,
    // felugro Toast NINCS (idegesito volt)
    private fun toast(msg: String) {
        lastEvent = msg
        Log.d(TAG, msg)
    }

    // az egyetlen kivetel: tenyleges felugro uzenet (kamera csatlakozas)
    private fun popup(msg: String) {
        lastEvent = msg
        Log.d(TAG, msg)
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun initCamera() {
        cameraHelper = CameraHelper().apply {
            setStateCallback(stateCallback)
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            toast("USB kamera csatlakozott")
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            toast("Eszkoz megnyitva")
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            var chosenFmt = ""
            try {
                val sizes = helper.supportedSizeList
                // a legnagyobb MJPEG (t=7) felbontas; ha nincs t=7, akkor a legnagyobb barmilyen
                val mjpeg = sizes.filter { it.type == 7 }.maxByOrNull { it.width * it.height }
                val best = mjpeg ?: sizes.maxByOrNull { it.width * it.height }
                if (best != null) {
                    helper.previewSize = best
                    chosenFmt = if (best.type == 7) "MJPEG" else "t=${best.type}"
                    toast("Valasztott: ${best.width}x${best.height} ($chosenFmt)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Felbontas allitas nem sikerult, marad az alap", e)
            }

            val sz = helper.previewSize
            dummyTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(sz.width, sz.height)
            }
            dummySurface = Surface(dummyTexture)
            helper.addSurface(dummySurface, false)
            helper.startPreview()
            previewing = true
            cameraInfo = if (chosenFmt.isNotEmpty())
                "${sz.width}x${sz.height} ($chosenFmt)" else "${sz.width}x${sz.height}"
            cameraReady = true
            popup("Kamera csatlakoztatva: $cameraInfo")
        }

        override fun onCameraClose(device: UsbDevice) {
            previewing = false
            cameraReady = false
            dummySurface?.let { cameraHelper?.removeSurface(it) }
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {
            cameraReady = false
            cameraInfo = "-"
            popup("USB kamera kihuzva")
        }
        override fun onCancel(device: UsbDevice) {
            toast("USB engedely megtagadva")
        }
    }

    private fun takePhoto() {
        val helper = cameraHelper
        if (helper == null || !previewing) {
            toast("Trigger jott, de a kamera NEM kesz")
            return
        }
        toast("Fotozas inditva")
        val baseName = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

        // 1) a kamera kepet egy ideiglenes JPEG-be mentjuk a cache-be
        val tmp = java.io.File(cacheDir, "tmp_capture.jpg")
        val options = IImageCapture.OutputFileOptions.Builder(tmp).build()

        helper.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(result: IImageCapture.OutputFileResults) {
                uploadExecutor.execute { convertAndSavePng(tmp, baseName) }
            }
            override fun onError(code: Int, message: String, cause: Throwable?) {
                toast("FOTO HIBA: $message")
            }
        })
    }

    // a JPEG-et dekodoljuk es vesztesegmentes PNG-kent mentjuk a galeriaba (+SFTP)
    private fun convertAndSavePng(tmpJpeg: java.io.File, baseName: String) {
        try {
            val bmp: Bitmap = BitmapFactory.decodeFile(tmpJpeg.absolutePath)
                ?: run { toast("PNG hiba: nem dekodolhato"); return }

            val pngBytes = ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
            bmp.recycle()
            tmpJpeg.delete()

            val album = AppSettings.album(this)
            val name = "$baseName.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + album)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(pngBytes) }
            } else {
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), album
                )
                if (!dir.exists()) dir.mkdirs()
                java.io.FileOutputStream(java.io.File(dir, name)).use { it.write(pngBytes) }
            }

            toast("PNG MENTVE: $name (${pngBytes.size / 1024} kB)")
            photoCount += 1

            if (AppSettings.sftpEnabled(this)) uploadSftp(name, pngBytes)
        } catch (e: OutOfMemoryError) {
            toast("PNG hiba: keves memoria")
        } catch (e: Exception) {
            toast("PNG hiba: ${e.message}")
        }
    }

    private fun uploadSftp(name: String, bytes: ByteArray) {
        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            val host = AppSettings.sftpHost(this)
            val port = AppSettings.sftpPort(this)
            val user = AppSettings.sftpUser(this)
            val pass = AppSettings.sftpPass(this)
            val dir = AppSettings.sftpDir(this)

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
            ByteArrayInputStream(bytes).use { channel.put(it, remotePath) }
            toast("SFTP feltoltve")
            webSocket?.send("uploaded")
        } catch (e: Exception) {
            toast("SFTP hiba: ${e.message}")
        } finally {
            channel?.disconnect()
            session?.disconnect()
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

    private fun shouldTrigger(text: String): Boolean {
        return TRIGGER_MESSAGE.isEmpty() || text.trim().equals(TRIGGER_MESSAGE, ignoreCase = true)
    }

    private fun connectWebSocket() {
        if (!serviceRunning) return
        handler.removeCallbacks(reconnectRunnable)   // ne stackelodjon az ujracsatlakozas
        val myGen = ++wsGeneration                   // ettol a regi listener-ek "stale"-lesznek
        try { webSocket?.cancel() } catch (_: Exception) {}  // regi kapcsolat eldobasa
        webSocket = null

        val url = AppSettings.wsUrl(this)
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myGen != wsGeneration) { ws.cancel(); return }
                wsConnected = true
                toast("WebSocket CSATLAKOZVA")
                // csatlakozas utan rogton lekerdezzuk a szerver modjat
                modePending = true
                ws.send("mode?")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (myGen != wsGeneration) return
                if (handleModeMessage(text)) return
                if (handleMediaCommand(text)) return
                toast("Uzenet jott: $text")
                if (shouldTrigger(text)) {
                    handler.post { takePhoto() }
                }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (myGen != wsGeneration) return
                val t = bytes.utf8()
                if (handleModeMessage(t)) return
                if (handleMediaCommand(t)) return
                toast("Binaris uzenet: $t")
                if (shouldTrigger(t)) {
                    handler.post { takePhoto() }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (myGen != wsGeneration) return
                wsConnected = false
                deviceMode = -1
                modePending = false
                toast("WebSocket HIBA: ${t.message}")
                reconnectLater()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (myGen != wsGeneration) return
                wsConnected = false
                deviceMode = -1
                modePending = false
                reconnectLater()
            }
        })
    }

    private fun reconnectLater() {
        handler.removeCallbacks(reconnectRunnable)   // csak egy ujracsatlakozas legyen fuggoben
        handler.postDelayed(reconnectRunnable, 5000)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbcapture:wakelock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "WS REMCON", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WS REMCON aktiv")
            .setContentText("WebSocket kapcsolat fenntartva")
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
        serviceRunning = false
        wsConnected = false
        cameraReady = false
        deviceMode = -1
        modePending = false
        wsGeneration++                                 // fuggoben levo listener-hivasok stale-lesznek
        handler.removeCallbacks(reconnectRunnable)      // ne legyen ujracsatlakozas leallitas utan
        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        try { cameraHelper?.release() } catch (_: Exception) {}
        uploadExecutor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
