package com.example.usbcapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Onallo hatterszolgaltatas: figyeli a telefon BEEPITETT kameraja altal
 * keszitett uj fenykepeket (a DCIM-en beluli, beallithato mappaban, alap:
 * "Camera"), es minden uj fotot feltolt SFTP-vel a beallitott szerverre,
 * majd kuld egy "uploaded" WebSocket uzenetet, ha kesz.
 *
 * Teljesen fuggetlen a CaptureService-tol: kulon ki/be kapcsolhato a fo
 * kepernyo "Beepitett kamera" kartyajarol, sajat WebSocket kapcsolatot tart
 * fenn (csak kuldesre hasznalja, a bejovo uzeneteket figyelmen kivul hagyja),
 * es ugyanazokat az SFTP beallitasokat olvassa az AppSettings-bol, mint a
 * CaptureService.
 *
 * Eszleles: MediaStore ContentObserver. Indulaskor megjegyezzuk a jelenlegi
 * legnagyobb kep-azonositot (baseline). Ezutan csak az ennel ujabb, a figyelt
 * mappaba kerult, mar nem "pending" kepeket toltjuk fel - minden kepet
 * pontosan egyszer (a feldolgozott azonositokat egy korlatozott halmazban
 * tartjuk nyilvan).
 *
 * Megjegyzes: a CaptureService a sajat (USB) kepeit a DCIM/<album> (alap:
 * DCIM2) mappaba menti, ez a szolgaltatas viszont a DCIM/Camera mappat
 * figyeli - igy a ket folyamat nem keveredik.
 */
class PhotoWatchService : Service() {

    companion object {
        // a beallitasok valtozasakor (WS URL / mappa) ezzel kerjuk az ujratoltest
        const val ACTION_RELOAD = "com.example.usbcapture.PHOTO_WATCH_RELOAD"

        private const val TAG = "PhotoWatchService"
        private const val CHANNEL_ID = "photo_watch"
        private const val NOTIF_ID = 2
        private const val UPLOADED_MESSAGE = "uploaded"

        // a ContentObserver tobbszor is jelez egy fotora; ennyit varunk, hogy
        // az egy fotohoz tartozo jelzeseket egyetlen beolvasasba vonjuk ossze
        private const val SCAN_DEBOUNCE_MS = 800L
        // egy beolvasasban maximum ennyi uj kepet dolgozunk fel (vedelem)
        private const val MAX_PER_SCAN = 50
        // ennyi legutobbi kep-azonositot tartunk nyilvan a duplikaciok ellen
        private const val PROCESSED_CAP = 500

        // ---- elo allapot: ezt olvassa a MainActivity a kijelzeshez ----
        @Volatile var running = false
        @Volatile var wsConnected = false
        @Volatile var uploadCount = 0
        @Volatile var lastEvent = "-"
        @Volatile var lastFile = "-"
    }

    private var webSocket: WebSocket? = null
    // minden uj kapcsolat noveli; a regi (stale) listener-hivasokat ez alapjan eldobjuk
    @Volatile private var wsGeneration = 0

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // a MediaStore figyelese / beolvasasa sajat hatterszalon fut (nem a fo szalon)
    private var observerThread: HandlerThread? = null
    private var observerHandler: Handler? = null
    private var contentObserver: ContentObserver? = null

    // a WebSocket ujracsatlakozas utemezese a fo szalon tortenik
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connectWebSocket() }

    // a tenyleges SFTP feltoltes kulon szalon (halozat)
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    // csak az observerHandler szalon irjuk/olvassuk -> nincs verseny
    private var baselineId = 0L
    private val processedIds = LinkedHashSet<Long>()

    private var wakeLock: PowerManager.WakeLock? = null

    private val scanRunnable = Runnable { scanForNewPhotos() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wsConnected = false
        uploadCount = 0
        lastFile = "-"

        createNotificationChannel()
        startForegroundCompat()
        acquireWakeLock()

        // hatterszal a ContentObserver visszahivasaihoz es a MediaStore lekerdezesekhez
        val thread = HandlerThread("photo-watch-observer").also { it.start() }
        observerThread = thread
        val oh = Handler(thread.looper)
        observerHandler = oh

        // baseline: a jelenlegi legnagyobb kep-azonosito; csak az ennel ujabbak erdekelnek.
        // (Az observer regisztracioja utan erkezo onChange a scanRunnable-t teszi a sorba,
        //  ami ezen post UTAN fut le, igy a baseline mindig kesz, mire elso beolvasas indul.)
        oh.post {
            baselineId = currentMaxImageId()
            event("Figyeles elindult")
        }

        contentObserver = object : ContentObserver(oh) {
            override fun onChange(selfChange: Boolean) = scheduleScan()
            override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleScan()
        }
        try {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, contentObserver!!
            )
        } catch (e: Exception) {
            event("Figyelo regisztracio hiba: ${e.message}")
        }

        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            // a beallitasok valtoztak: azonnali WebSocket ujracsatlakozas az uj URL-lel
            // (a figyelt mappat a kovetkezo beolvasas mar az uj ertekkel hasznalja)
            event("Beallitasok ujratoltve")
            connectWebSocket()
        }
        return START_STICKY
    }

    // ------------------------- MediaStore figyeles -------------------------

    private fun scheduleScan() {
        val h = observerHandler ?: return
        h.removeCallbacks(scanRunnable)
        h.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS)
    }

    /** Az observerHandler szalon fut. Megkeresi a baseline ota erkezett uj
     *  fotokat a figyelt mappaban, es mindegyiket egyszer feltoltesre teszi. */
    private fun scanForNewPhotos() {
        val folder = AppSettings.photoWatchFolder(this).trim().trim('/')
        if (folder.isEmpty()) return

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val idCol = MediaStore.Images.Media._ID
        val nameCol = MediaStore.Images.Media.DISPLAY_NAME

        val projection: Array<String>
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = arrayOf(
                idCol, nameCol,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.IS_PENDING
            )
            // csak a figyelt mappa (pl. "DCIM/Camera/") es csak a mar kesz (nem pending) kepek
            selection = "$idCol > ? AND " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Images.Media.IS_PENDING} = 0"
            args = arrayOf(baselineId.toString(), "DCIM/$folder/%")
        } else {
            @Suppress("DEPRECATION")
            projection = arrayOf(idCol, nameCol, MediaStore.Images.Media.DATA)
            @Suppress("DEPRECATION")
            selection = "$idCol > ? AND ${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf(baselineId.toString(), "%/DCIM/$folder/%")
        }

        try {
            contentResolver.query(collection, projection, selection, args, "$idCol ASC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(idCol)
                val iName = c.getColumnIndexOrThrow(nameCol)
                var count = 0
                while (c.moveToNext() && count < MAX_PER_SCAN) {
                    val id = c.getLong(iId)
                    if (id <= baselineId || processedIds.contains(id)) continue
                    val name = if (!c.isNull(iName)) c.getString(iName) else "IMG_$id.jpg"
                    markProcessed(id)
                    val uri = ContentUris.withAppendedId(collection, id)
                    uploadExecutor.execute { uploadPhoto(uri, name) }
                    count++
                }
                if (count > 0) event("Uj foto eszlelve: $count")
            }
        } catch (e: Exception) {
            event("Beolvasas hiba: ${e.message}")
        }
    }

    private fun markProcessed(id: Long) {
        processedIds.add(id)
        if (processedIds.size > PROCESSED_CAP) {
            val it = processedIds.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }

    private fun currentMaxImageId(): Long {
        var maxId = 0L
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                if (c.moveToFirst()) maxId = c.getLong(0)
            }
        } catch (_: Exception) {
        }
        return maxId
    }

    // ------------------------- SFTP feltoltes -------------------------

    /** Az uploadExecutor szalon fut: a kep tartalmat kozvetlenul streameli az
     *  SFTP szerverre (nem tolti a teljes fajlt a memoriaba), majd siker eseten
     *  kuld egy "uploaded" WebSocket uzenetet. */
    private fun uploadPhoto(uri: Uri, name: String) {
        if (!AppSettings.sftpEnabled(this)) {
            event("SFTP kikapcsolva, kihagyva: $name")
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
                ?: run { event("Megnyitas hiba: $name"); return }

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

            uploadCount += 1
            lastFile = name
            event("SFTP feltoltve: $name")

            // siker -> "uploaded" uzenet (a send szalbiztos; a referenciat lokalisba olvassuk)
            try { webSocket?.send(UPLOADED_MESSAGE) } catch (_: Exception) {}
        } catch (e: Exception) {
            event("SFTP hiba ($name): ${e.message}")
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

    // ------------------------- WebSocket (csak kuldes) -------------------------

    private fun connectWebSocket() {
        if (!running) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val myGen = ++wsGeneration                       // ettol a regi listener-ek "stale"-lesznek
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
            // a bejovo uzenetekre ennek a szolgaltatasnak nincs dolga (csak kuld)
            override fun onMessage(ws: WebSocket, text: String) {}
            override fun onMessage(ws: WebSocket, bytes: ByteString) {}
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

    private fun reconnectLater() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, 5000)
    }

    // ------------------------- Eloterbeli szolgaltatas / segedek -------------------------

    private fun event(msg: String) {
        lastEvent = msg
        Log.d(TAG, msg)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbcapture:photowatch")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Beépített kamera figyelés", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beépített kamera figyelés")
            .setContentText("Új fotók feltöltése SFTP-re")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        wsConnected = false
        wsGeneration++                                   // fuggoben levo listener-hivasok stale-lesznek
        reconnectHandler.removeCallbacks(reconnectRunnable)

        contentObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        contentObserver = null
        observerHandler?.removeCallbacks(scanRunnable)

        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        // OkHttp eroforrasok azonnali felszabaditasa (kulonben ~60 mp-ig elnek meg)
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}

        uploadExecutor.shutdown()
        observerThread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
