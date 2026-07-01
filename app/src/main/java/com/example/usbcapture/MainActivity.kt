package com.example.usbcapture

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Inditokepernyo + allapotkijelzo + beallitasok - "fustuveg" (smoked glass)
 * sotet temaval es idovonalas allapot-nezettel.
 *
 *  - elkeri az engedelyeket es elinditja a hatterszolgaltatast
 *  - elo allapotot mutat (idovonal: szolgaltatas / WebSocket / kamera)
 *  - kulon "uzemmod" kartya a mod valtasahoz
 *  - kulon "beepitett kamera -> SFTP" kartya a foto-figyeleshez
 *  - "Beállítások": WebSocket URL, fenykep mappa, media/hangero parancsok,
 *    SFTP/SSH es a beepitett kamera figyelt mappaja
 *
 * Csak a kinezet uj; a mukodes (logika) valtozatlan.
 */
class MainActivity : ComponentActivity() {

    // ----- szinek (fustuveg / sotet tema) -----
    private val cBg = Color.parseColor("#0C0D10")
    private val cCard = Color.parseColor("#14FFFFFF")
    private val cBorder = Color.parseColor("#24FFFFFF")
    private val cLine = Color.parseColor("#1FFFFFFF")
    private val cText = Color.parseColor("#E8EAED")
    private val cTextSub = Color.parseColor("#8B93A1")
    private val cBox = Color.parseColor("#4D000000")
    private val cChip = Color.parseColor("#14FFFFFF")

    private val cAccent = Color.parseColor("#A5B4FC")
    private val cOnAccent = Color.parseColor("#1E1B2E")

    // a refreshModeUi / styleModeBadge / status hasznalja (a neveket megtartjuk)
    private val cGreen = Color.parseColor("#6EE7B7")
    private val cRed = Color.parseColor("#FCA5A5")
    private val cGray = Color.parseColor("#9CA3AF")
    private val cBlue = Color.parseColor("#93C5FD")
    private val cBlueSoft = Color.parseColor("#2693C5FD")
    private val cPurple = Color.parseColor("#A5B4FC")
    private val cPurpleSoft = Color.parseColor("#29A5B4FC")
    private val cGraySoft = Color.parseColor("#14FFFFFF")

    private lateinit var svcDot: View
    private lateinit var svcValue: TextView
    private lateinit var wsDot: View
    private lateinit var wsValue: TextView
    private lateinit var wsUrlText: TextView
    private lateinit var camDot: View
    private lateinit var camValue: TextView
    private lateinit var infoText: TextView
    private lateinit var svcToggleBtn: Button

    // uzemmod kartya elemei
    private lateinit var modeBadge: TextView
    private lateinit var modeSubText: TextView
    private lateinit var modeSwitchBtn: Button

    // beepitett kamera figyeles kartya elemei
    private lateinit var pwDot: View
    private lateinit var pwValue: TextView
    private lateinit var pwInfo: TextView
    private lateinit var pwToggleBtn: Button

    // parancsra foto (hatso kamera) kartya elemei
    private lateinit var snapDot: View
    private lateinit var snapValue: TextView
    private lateinit var snapInfo: TextView
    private lateinit var snapToggleBtn: Button

    // az app aktualis verzioneve (a build.gradle versionName-mel osszhangban)
    private val appVersionName = "3.0"

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startCaptureService()
    }

    // a beepitett kamera figyelesehez a kepek olvasasa kell - ezt a kapcsolo
    // bekapcsolasakor kerjuk el (nem az app inditasakor), igy opcionalis marad
    private val photoPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            enablePhotoWatch()
        } else {
            Toast.makeText(
                this,
                "Engedély nélkül nem indítható a beépített kamera figyelése",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // a parancsra-fotohoz a CAMERA engedely kell - ezt is a kapcsolo
    // bekapcsolasakor kerjuk el, ha meg nincs meg
    private val snapPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            enableSnap()
        } else {
            Toast.makeText(
                this,
                "Engedély nélkül nem indítható a parancsra fotó",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        requestBatteryExemption()

        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 9 es regebbi: a DCIM mappaba irashoz tarhely-engedely kell
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms.toTypedArray())

        // ha a beepitett kamera figyeles korabban be volt kapcsolva es megvan
        // a kepek olvasasi engedelye, akkor inditsuk ujra automatikusan
        if (AppSettings.photoWatchEnabled(this) && hasPhotoReadPermission()) {
            startPhotoWatchService()
        }

        // ugyanez a parancsra-fotora (ha be volt kapcsolva es van kamera engedely)
        if (AppSettings.snapEnabled(this) && hasCameraPermission()) {
            startSnapService()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    // ----------------------------- UI -----------------------------

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(cBg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }

        // --- fejlec (atlatszo) + ELO chip ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val hCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hCol.addView(TextView(this).apply {
            text = "WS REMCON"; setTextColor(cText); textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        })
        hCol.addView(TextView(this).apply {
            text = "WebSocket · SFTP · média"; setTextColor(cTextSub); textSize = 11f
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(hCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { weight = 1f })
        root.addView(header, lp(bottom = dp(16)))

        // --- ALLAPOT & ESEMENYEK (idovonal) ---
        val statusCard = cardView()
        statusCard.addView(sectionTitle("ÁLLAPOT & ESEMÉNYEK"))

        val timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        svcDot = dotView()
        svcValue = valueText()
        timeline.addView(timelineNode(svcDot, "Szolgáltatás", svcValue, null, false))

        wsDot = dotView()
        wsValue = valueText()
        wsUrlText = TextView(this).apply {
            textSize = 11f; setTextColor(cTextSub)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        timeline.addView(timelineNode(wsDot, "WebSocket", wsValue, wsUrlText, false))

        camDot = dotView()
        camValue = valueText()
        timeline.addView(timelineNode(camDot, "Kamera", camValue, null, true))

        statusCard.addView(timeline, lp(top = dp(8)))
        root.addView(statusCard, lp(bottom = dp(13)))

        // --- UZEMMOD ---
        val modeCard = cardView()
        modeCard.addView(sectionTitle("ÜZEMMÓD"))

        modeBadge = TextView(this).apply {
            text = "Ismeretlen"; textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cTextSub)
            background = GradientDrawable().apply {
                setColor(cGraySoft); cornerRadius = dp(999).toFloat()
                setStroke(dp(1), cBorder)
            }
            setPadding(dp(15), dp(8), dp(15), dp(8))
        }
        modeCard.addView(modeBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        modeSubText = TextView(this).apply {
            text = "A mód a WebSocket csatlakozása után kérdezhető le."
            textSize = 12f; setTextColor(cTextSub)
            setPadding(0, dp(8), 0, 0)
        }
        modeCard.addView(modeSubText)

        modeSwitchBtn = ghostButton("Mód váltása")
        modeSwitchBtn.setOnClickListener { switchMode() }
        modeCard.addView(modeSwitchBtn, lp(top = dp(12)))

        root.addView(modeCard, lp(bottom = dp(13)))

        // --- BEEPITETT KAMERA -> SFTP ---
        val pwCard = cardView()
        pwCard.addView(sectionTitle("BEÉPÍTETT KAMERA → SFTP"))

        val pwRow = rowView()
        pwDot = dotView()
        pwRow.addView(pwDot, dotLp())
        val pwCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pwCol.addView(TextView(this).apply {
            text = "Figyelés"; setTextColor(cText); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        pwValue = valueText()
        pwCol.addView(pwValue)
        pwRow.addView(pwCol)
        pwCard.addView(pwRow, lp(top = dp(6)))

        pwInfo = TextView(this).apply {
            textSize = 12f; setTextColor(cText)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        pwCard.addView(boxWrap(pwInfo), lp(top = dp(12)))

        pwToggleBtn = primaryButton("Bekapcsolás")
        pwToggleBtn.setOnClickListener { togglePhotoWatch() }
        pwCard.addView(pwToggleBtn, lp(top = dp(12)))

        root.addView(pwCard, lp(bottom = dp(13)))

        // --- PARANCSRA FOTO (hatso kamera) -> SFTP ---
        val snapCard = cardView()
        snapCard.addView(sectionTitle("PARANCSRA FOTÓ → SFTP"))

        val snapRow = rowView()
        snapDot = dotView()
        snapRow.addView(snapDot, dotLp())
        val snapCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        snapCol.addView(TextView(this).apply {
            text = "Hátsó kamera"; setTextColor(cText); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        snapValue = valueText()
        snapCol.addView(snapValue)
        snapRow.addView(snapCol)
        snapCard.addView(snapRow, lp(top = dp(6)))

        snapInfo = TextView(this).apply {
            textSize = 12f; setTextColor(cText)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        snapCard.addView(boxWrap(snapInfo), lp(top = dp(12)))

        snapToggleBtn = primaryButton("Bekapcsolás")
        snapToggleBtn.setOnClickListener { toggleSnap() }
        snapCard.addView(snapToggleBtn, lp(top = dp(12)))

        root.addView(snapCard, lp(bottom = dp(13)))

        // --- VEZERLES ---
        val controlCard = cardView()
        controlCard.addView(sectionTitle("VEZÉRLÉS"))

        infoText = TextView(this).apply {
            textSize = 12f; setTextColor(cText)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        controlCard.addView(boxWrap(infoText), lp(top = dp(10)))

        val settingsBtn = ghostButton("Beállítások")
        settingsBtn.setOnClickListener { showSettingsDialog() }
        controlCard.addView(settingsBtn, lp(top = dp(12)))

        svcToggleBtn = primaryButton("Bekapcsolás")
        svcToggleBtn.setOnClickListener { toggleService() }
        controlCard.addView(svcToggleBtn, lp(top = dp(10)))

        root.addView(controlCard)

        root.addView(TextView(this).apply {
            text = "Ha újra csatlakoztatsz egy USB kamerát, a szolgáltatás " +
                    "automatikusan újraindul."
            textSize = 12f; setTextColor(cTextSub)
            setPadding(dp(4), dp(16), dp(4), 0)
        })

        root.addView(TextView(this).apply {
            text = "WS REMCON · v$appVersionName"
            textSize = 11f; setTextColor(cTextSub)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun refreshStatus() {
        val running = CaptureService.serviceRunning
        val ws = CaptureService.wsConnected
        val cam = CaptureService.cameraReady

        svcDot.background = dotDrawable(if (running) cGreen else cRed)
        svcValue.text = if (running) "FUT" else "LEÁLLÍTVA"
        svcValue.setTextColor(if (running) cGreen else cRed)

        wsDot.background = dotDrawable(if (ws) cGreen else cRed)
        wsValue.text = if (ws) "Csatlakozva" else "Nincs kapcsolat"
        wsValue.setTextColor(if (ws) cGreen else cRed)
        wsUrlText.text = AppSettings.wsUrl(this)

        camDot.background = dotDrawable(if (cam) cGreen else cRed)
        camValue.text = if (cam) "Kesz - ${CaptureService.cameraInfo}" else "Nincs csatlakoztatva"
        camValue.setTextColor(if (cam) cGreen else cRed)

        val sb = StringBuilder()
        sb.append("Mentés helye:  DCIM/").append(AppSettings.album(this)).append("\n")
        if (AppSettings.sftpEnabled(this)) {
            sb.append("SFTP:  ").append(AppSettings.sftpHost(this))
                .append(":").append(AppSettings.sftpPort(this)).append("\n")
            sb.append("Mappa:  ").append(AppSettings.sftpDir(this)).append("\n")
        } else {
            sb.append("SFTP:  kikapcsolva\n")
        }
        sb.append("Média parancsok:  ")
            .append(AppSettings.cmdNext(this)).append(" / ")
            .append(AppSettings.cmdPrev(this)).append(" / ")
            .append(AppSettings.cmdPlayPause(this)).append("\n")
        sb.append("Hangerő parancsok:  ")
            .append(AppSettings.cmdVolUp(this)).append(" / ")
            .append(AppSettings.cmdVolDown(this)).append("\n")
        sb.append("Készült képek:  ").append(CaptureService.photoCount).append("\n")
        sb.append("Média események:  ").append(CaptureService.mediaCount).append("\n")
        sb.append("Utolsó esemény:  ").append(CaptureService.lastEvent)
        infoText.text = sb.toString()

        if (running) {
            svcToggleBtn.text = "Kikapcsolás"
            styleDanger(svcToggleBtn)
        } else {
            svcToggleBtn.text = "Bekapcsolás"
            stylePrimary(svcToggleBtn)
        }

        refreshModeUi(ws)
        refreshPhotoWatchUi()
        refreshSnapUi()
    }

    // -------------------------- Uzemmod --------------------------

    private fun refreshModeUi(wsConnected: Boolean) {
        val mode = CaptureService.deviceMode
        val pending = CaptureService.modePending

        when {
            !wsConnected -> {
                styleModeBadge("Nincs kapcsolat", cTextSub, cGraySoft)
                modeSubText.text = "A mód a WebSocket csatlakozása után kérdezhető le."
                modeSwitchBtn.isEnabled = false
                modeSwitchBtn.alpha = 0.5f
                modeSwitchBtn.text = "Mód váltása"
            }
            pending -> {
                styleModeBadge("Lekerdezes...", cTextSub, cGraySoft)
                modeSubText.text = "Válaszra várunk a szervertől."
                modeSwitchBtn.isEnabled = false
                modeSwitchBtn.alpha = 0.5f
                modeSwitchBtn.text = "Mód váltása"
            }
            mode == 0 -> {
                styleModeBadge("Remote Controller", cBlue, cBlueSoft)
                modeSubText.text = "Aktív mód: mode0"
                modeSwitchBtn.isEnabled = true
                modeSwitchBtn.alpha = 1f
                modeSwitchBtn.text = "Váltás: Audio Controller"
            }
            mode == 1 -> {
                styleModeBadge("Audio Controller", cPurple, cPurpleSoft)
                modeSubText.text = "Aktív mód: mode1"
                modeSwitchBtn.isEnabled = true
                modeSwitchBtn.alpha = 1f
                modeSwitchBtn.text = "Váltás: Remote Controller"
            }
            else -> {
                styleModeBadge("Ismeretlen", cTextSub, cGraySoft)
                modeSubText.text = "Nem jott meg valasz, probald ujra."
                modeSwitchBtn.isEnabled = true
                modeSwitchBtn.alpha = 1f
                modeSwitchBtn.text = "Mód lekérdezése"
            }
        }
    }

    private fun styleModeBadge(label: String, textColor: Int, bgColor: Int) {
        modeBadge.text = label
        modeBadge.setTextColor(textColor)
        modeBadge.background = GradientDrawable().apply {
            setColor(bgColor); cornerRadius = dp(999).toFloat()
            setStroke(dp(1), cBorder)
        }
    }

    private fun switchMode() {
        if (!CaptureService.serviceRunning || !CaptureService.wsConnected) {
            Toast.makeText(this, "Nincs WebSocket kapcsolat", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = CaptureService.deviceMode
        val i = Intent(this, CaptureService::class.java)
        if (mode == 0 || mode == 1) {
            i.action = CaptureService.ACTION_SET_MODE
            i.putExtra(CaptureService.EXTRA_MODE, 1 - mode)
        } else {
            // ha meg nem tudjuk a modot, eloszor lekerdezzuk
            i.action = CaptureService.ACTION_QUERY_MODE
        }
        try { startService(i) } catch (_: Exception) {}
        refreshStatus()
    }

    // -------------------------- Beepitett kamera figyeles --------------------------

    private fun refreshPhotoWatchUi() {
        val enabled = AppSettings.photoWatchEnabled(this)
        val running = PhotoWatchService.running
        val wsOk = PhotoWatchService.wsConnected

        val dotColor = when {
            running -> cGreen
            enabled -> cRed       // be van kapcsolva, de valamiert nem fut
            else -> cGray
        }
        pwDot.background = dotDrawable(dotColor)

        pwValue.text = when {
            running && wsOk -> "FUT · WS kapcsolódva"
            running -> "FUT · WS nincs"
            enabled -> "BE (nem fut)"
            else -> "KIKAPCSOLVA"
        }
        pwValue.setTextColor(if (running) cGreen else if (enabled) cRed else cTextSub)

        val sb = StringBuilder()
        sb.append("Figyelt mappa:  DCIM/").append(AppSettings.photoWatchFolder(this)).append("\n")
        sb.append("Feltöltve:  ").append(PhotoWatchService.uploadCount).append("\n")
        sb.append("Utolsó fájl:  ").append(PhotoWatchService.lastFile).append("\n")
        sb.append("Utolsó esemény:  ").append(PhotoWatchService.lastEvent)
        pwInfo.text = sb.toString()

        if (running) {
            pwToggleBtn.text = "Kikapcsolás"
            styleDanger(pwToggleBtn)
        } else {
            pwToggleBtn.text = "Bekapcsolás"
            stylePrimary(pwToggleBtn)
        }
    }

    private fun togglePhotoWatch() {
        if (PhotoWatchService.running) {
            disablePhotoWatch()
        } else {
            // ha mar megvan a kepek olvasasi engedelye, egybol indithatunk;
            // kulonben eloszor elkerjuk (a valasz a photoPermLauncher-be jon)
            if (hasPhotoReadPermission()) {
                enablePhotoWatch()
            } else {
                photoPermLauncher.launch(photoReadPermissions())
            }
        }
    }

    private fun enablePhotoWatch() {
        AppSettings.setPhotoWatchEnabled(this, true)
        startPhotoWatchService()
        Toast.makeText(this, "Beépített kamera figyelés bekapcsolva", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refreshStatus() }, 400)
    }

    private fun disablePhotoWatch() {
        AppSettings.setPhotoWatchEnabled(this, false)
        stopPhotoWatchService()
        // azonnali visszajelzes; a service onDestroy ugyanezt beallitja
        PhotoWatchService.running = false
        PhotoWatchService.wsConnected = false
        Toast.makeText(this, "Beépített kamera figyelés kikapcsolva", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun startPhotoWatchService() {
        val i = Intent(this, PhotoWatchService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopPhotoWatchService() {
        stopService(Intent(this, PhotoWatchService::class.java))
    }

    private fun photoReadPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasPhotoReadPermission(): Boolean =
        photoReadPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    // -------------------------- Parancsra foto (hatso kamera) --------------------------

    private fun refreshSnapUi() {
        val enabled = AppSettings.snapEnabled(this)
        val running = SnapService.running
        val wsOk = SnapService.wsConnected

        val dotColor = when {
            running -> cGreen
            enabled -> cRed       // be van kapcsolva, de valamiert nem fut
            else -> cGray
        }
        snapDot.background = dotDrawable(dotColor)

        snapValue.text = when {
            running && wsOk -> "FUT · WS kapcsolódva"
            running -> "FUT · WS nincs"
            enabled -> "BE (nem fut)"
            else -> "KIKAPCSOLVA"
        }
        snapValue.setTextColor(if (running) cGreen else if (enabled) cRed else cTextSub)

        val sb = StringBuilder()
        sb.append("Parancs:  ").append(AppSettings.snapCommand(this)).append("\n")
        sb.append("Kamera:  hátsó · vaku ki · autofókusz\n")
        sb.append("Felbontás:  ").append(SnapService.lastResolution).append(" (max)\n")
        sb.append("Készített:  ").append(SnapService.captureCount).append("\n")
        sb.append("Utolsó fájl:  ").append(SnapService.lastFile).append("\n")
        sb.append("Utolsó esemény:  ").append(SnapService.lastEvent)
        snapInfo.text = sb.toString()

        if (running) {
            snapToggleBtn.text = "Kikapcsolás"
            styleDanger(snapToggleBtn)
        } else {
            snapToggleBtn.text = "Bekapcsolás"
            stylePrimary(snapToggleBtn)
        }
    }

    private fun toggleSnap() {
        if (SnapService.running) {
            disableSnap()
        } else {
            if (hasCameraPermission()) {
                enableSnap()
            } else {
                snapPermLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        }
    }

    private fun enableSnap() {
        AppSettings.setSnapEnabled(this, true)
        startSnapService()
        Toast.makeText(this, "Parancsra fotó bekapcsolva", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refreshStatus() }, 400)
    }

    private fun disableSnap() {
        AppSettings.setSnapEnabled(this, false)
        stopSnapService()
        // azonnali visszajelzes; a service onDestroy ugyanezt beallitja
        SnapService.running = false
        SnapService.wsConnected = false
        Toast.makeText(this, "Parancsra fotó kikapcsolva", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun startSnapService() {
        val i = Intent(this, SnapService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopSnapService() {
        stopService(Intent(this, SnapService::class.java))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    // -------------------------- Beallitasok --------------------------

    private fun showSettingsDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setBackgroundColor(cBg)
        }

        // sajat fejlec (cim + bezaro X) a default szurke cimsav helyett
        val xBtn = TextView(this).apply {
            text = "✕"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(cTextSub)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cBox); setStroke(dp(1), cBorder)
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        headerRow.addView(TextView(this).apply {
            text = "Beállítások"; textSize = 20f; setTextColor(cText)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        headerRow.addView(xBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        form.addView(headerRow)

        form.addView(sectionAccent("ÁLTALÁNOS"))

        val wsField = field(form, "WebSocket URL", AppSettings.wsUrl(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val albumField = field(form, "Fényképek mappa (a DCIM-en belül)",
            AppSettings.album(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionAccent("MÉDIA VEZÉRLÉS (zenelejátszó)"))

        val nextField = field(form, "Következő szám parancs",
            AppSettings.cmdNext(this), InputType.TYPE_CLASS_TEXT)
        val prevField = field(form, "Előző szám parancs",
            AppSettings.cmdPrev(this), InputType.TYPE_CLASS_TEXT)
        val playPauseField = field(form, "Lejátszás / szünet parancs",
            AppSettings.cmdPlayPause(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionAccent("HANGERŐ VEZÉRLÉS"))

        val volUpField = field(form, "Hangerő fel parancs",
            AppSettings.cmdVolUp(this), InputType.TYPE_CLASS_TEXT)
        val volDownField = field(form, "Hangerő le parancs",
            AppSettings.cmdVolDown(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionAccent("SFTP / SSH"))

        val sftpCheck = CheckBox(this).apply {
            text = "SFTP feltöltés bekapcsolva"
            setTextColor(cText)
            isChecked = AppSettings.sftpEnabled(this@MainActivity)
        }
        form.addView(sftpCheck)

        val hostField = field(form, "SFTP host", AppSettings.sftpHost(this),
            InputType.TYPE_CLASS_TEXT)
        val portField = field(form, "SFTP port", AppSettings.sftpPort(this).toString(),
            InputType.TYPE_CLASS_NUMBER)
        val userField = field(form, "SFTP felhasználó", AppSettings.sftpUser(this),
            InputType.TYPE_CLASS_TEXT)
        val passField = field(form, "SFTP jelszó", AppSettings.sftpPass(this),
            InputType.TYPE_CLASS_TEXT)
        val dirField = field(form, "SFTP távoli mappa", AppSettings.sftpDir(this),
            InputType.TYPE_CLASS_TEXT)

        form.addView(sectionAccent("BEÉPÍTETT KAMERA → SFTP"))

        val watchFolderField = field(form, "Figyelt mappa (a DCIM-en belül)",
            AppSettings.photoWatchFolder(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionAccent("PARANCSRA FOTÓ (hátsó kamera)"))

        val snapCommandField = field(form, "Parancs (WebSocket üzenet)",
            AppSettings.snapCommand(this), InputType.TYPE_CLASS_TEXT)

        val resetBtn = ghostButton("Visszaállítás alapértékekre").apply {
            setOnClickListener {
                wsField.setText(AppSettings.DEF_WS_URL)
                albumField.setText(AppSettings.DEF_ALBUM)
                nextField.setText(AppSettings.DEF_CMD_NEXT)
                prevField.setText(AppSettings.DEF_CMD_PREV)
                playPauseField.setText(AppSettings.DEF_CMD_PLAYPAUSE)
                volUpField.setText(AppSettings.DEF_CMD_VOLUP)
                volDownField.setText(AppSettings.DEF_CMD_VOLDOWN)
                sftpCheck.isChecked = AppSettings.DEF_SFTP_ENABLED
                hostField.setText(AppSettings.DEF_SFTP_HOST)
                portField.setText(AppSettings.DEF_SFTP_PORT.toString())
                userField.setText(AppSettings.DEF_SFTP_USER)
                passField.setText(AppSettings.DEF_SFTP_PASS)
                dirField.setText(AppSettings.DEF_SFTP_DIR)
                watchFolderField.setText(AppSettings.DEF_PHOTO_WATCH_FOLDER)
                snapCommandField.setText(AppSettings.DEF_SNAP_COMMAND)
            }
        }
        form.addView(resetBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // dizajnos also gombsor a default dialog-gombok helyett
        val cancelBtn = ghostButton("Mégse")
        val saveBtn = primaryButton("Mentés")
        val actionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionBar.addView(cancelBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { weight = 1f })
        actionBar.addView(saveBtn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { weight = 1.5f; leftMargin = dp(10) })
        form.addView(actionBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        val scroll = ScrollView(this).apply {
            addView(form); setBackgroundColor(cBg)
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(scroll)
            .create()

        xBtn.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            val port = portField.text.toString().trim().toIntOrNull()
                ?: AppSettings.DEF_SFTP_PORT
            AppSettings.save(
                this,
                wsUrl = wsField.text.toString(),
                album = albumField.text.toString(),
                sftpEnabled = sftpCheck.isChecked,
                sftpHost = hostField.text.toString(),
                sftpPort = port,
                sftpUser = userField.text.toString(),
                sftpPass = passField.text.toString(),
                sftpDir = dirField.text.toString(),
                cmdNext = nextField.text.toString(),
                cmdPrev = prevField.text.toString(),
                cmdPlayPause = playPauseField.text.toString(),
                cmdVolUp = volUpField.text.toString(),
                cmdVolDown = volDownField.text.toString(),
                photoWatchFolder = watchFolderField.text.toString(),
                snapCommand = snapCommandField.text.toString()
            )
            Toast.makeText(this, "Beállítások mentve", Toast.LENGTH_SHORT).show()
            notifyServiceSettingsChanged()
            refreshStatus()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun notifyServiceSettingsChanged() {
        // a WebSocket URL valtozas ervenyesitese a CaptureService-ben: csak ha fut
        if (CaptureService.serviceRunning) {
            try {
                val i = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_RECONNECT_WS
                }
                startService(i)
            } catch (_: Exception) {
            }
        }
        // ugyanez a beepitett kamera figyelo szolgaltatasnak (uj WS URL / mappa)
        if (PhotoWatchService.running) {
            try {
                val i = Intent(this, PhotoWatchService::class.java).apply {
                    action = PhotoWatchService.ACTION_RELOAD
                }
                startService(i)
            } catch (_: Exception) {
            }
        }
        // es a parancsra-foto szolgaltatasnak (uj WS URL / parancs)
        if (SnapService.running) {
            try {
                val i = Intent(this, SnapService::class.java).apply {
                    action = SnapService.ACTION_RELOAD
                }
                startService(i)
            } catch (_: Exception) {
            }
        }
    }

    // -------------------------- Service vezerles --------------------------

    private fun confirmStop() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Szolgáltatás leállítása")
            .setMessage(
                "Biztosan leállítod a háttérszolgáltatást? " +
                        "A kamera figyelése és a WebSocket kapcsolat is megszakad."
            )
            .setPositiveButton("Leállítás") { _, _ -> stopCaptureService() }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun stopCaptureService() {
        stopService(Intent(this, CaptureService::class.java))
        // azonnali visszajelzes; a service onDestroy ugyanezt beallitja
        CaptureService.serviceRunning = false
        CaptureService.wsConnected = false
        CaptureService.cameraReady = false
        CaptureService.cameraInfo = "-"
        refreshStatus()
    }

    private fun startCaptureService() {
        val i = Intent(this, CaptureService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun startServiceManually() {
        if (CaptureService.serviceRunning) {
            Toast.makeText(this, "A szolgáltatás már fut", Toast.LENGTH_SHORT).show()
            return
        }
        startCaptureService()
        Toast.makeText(this, "Szolgáltatás indítása...", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refreshStatus() }, 500)
    }

    // egy gomb inditasra/leallitasra: a meglevo logikat hivja
    private fun toggleService() {
        if (CaptureService.serviceRunning) {
            confirmStop()
        } else {
            startServiceManually()
        }
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    // -------------------------- UI segedek --------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top; bottomMargin = bottom }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cCard); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), cBorder)
        }
        setPadding(dp(16), dp(15), dp(16), dp(15))
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(cTextSub)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    // szekciofejlec a beallitasokban: akcent szin + kis akcent sav, hogy
    // latvanyosan elkuloniiljon, mi mihez tartozik
    private fun sectionAccent(t: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(4))
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(cAccent); cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(3), dp(14)).apply { rightMargin = dp(9) })
        row.addView(TextView(this).apply {
            text = t; textSize = 12f; setTextColor(cAccent)
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f
        })
        return row
    }

    private fun rowView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // egy allapot-ertek (a cim alatti masodlagos sor); szinet a refresh allitja
    private fun valueText(): TextView = TextView(this).apply {
        textSize = 13f; setTextColor(cTextSub)
    }

    private fun dotView(): View = View(this).apply { background = dotDrawable(cGray) }

    private fun dotLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) }

    private fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    // sotet "uveg" oval gyuru egy idovonal-csomoponthoz
    private fun ringDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), cBorder)
    }

    // egy idovonal-csomopont: bal oldalt gyuru + osszekoto vonal, jobb oldalt szoveg
    private fun timelineNode(
        dot: View, title: String, value: TextView, sub: TextView?, isLast: Boolean
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val circle = FrameLayout(this).apply { background = ringDrawable() }
        circle.addView(dot, FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER))
        left.addView(circle, LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(1) })
        if (!isLast) {
            left.addView(View(this).apply { setBackgroundColor(cLine) },
                LinearLayout.LayoutParams(dp(2), 0).apply { weight = 1f; topMargin = dp(2) })
        }
        row.addView(left, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, if (isLast) 0 else dp(16))
        }
        right.addView(TextView(this).apply {
            text = title; setTextColor(cText); textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        right.addView(value)
        if (sub != null) right.addView(sub)
        row.addView(right, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { weight = 1f })

        return row
    }

    // mono info-doboz koré sotet, lekerekitett hatter
    private fun boxWrap(inner: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cBox); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        box.addView(inner)
        return box
    }

    // lekerekitett hatter + kattintaskor "villano" ripple visszajelzes
    private fun rippleBg(content: GradientDrawable, rippleColor: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(13).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun btnBase(label: String): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(13), dp(16), dp(13))
    }

    // toltott (akcent) gomb sotet szoveggel - elsodleges muvelet
    private fun stylePrimary(btn: Button) {
        btn.setTextColor(cOnAccent)
        val c = GradientDrawable().apply {
            setColor(cAccent); cornerRadius = dp(13).toFloat()
        }
        btn.background = rippleBg(c, Color.parseColor("#552B2540"))
    }

    // atlatszo, kerettel - masodlagos muvelet
    private fun styleGhost(btn: Button) {
        btn.setTextColor(cText)
        val c = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        btn.background = rippleBg(c, Color.parseColor("#22FFFFFF"))
    }

    // piros keret/szoveg - veszelyes muvelet (leallitas/kikapcsolas)
    private fun styleDanger(btn: Button) {
        btn.setTextColor(cRed)
        val c = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.parseColor("#5AFCA5A5"))
        }
        btn.background = rippleBg(c, Color.parseColor("#22FCA5A5"))
    }

    private fun primaryButton(label: String): Button = btnBase(label).also { stylePrimary(it) }
    private fun ghostButton(label: String): Button = btnBase(label).also { styleGhost(it) }
    private fun dangerButton(label: String): Button = btnBase(label).also { styleDanger(it) }

    private fun field(parent: LinearLayout, label: String, value: String, type: Int): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(cTextSub)
            setPadding(0, dp(12), 0, dp(6))
        })
        val et = EditText(this).apply {
            setText(value); inputType = type; textSize = 14f
            setTextColor(cText); setHintTextColor(cTextSub)
            background = GradientDrawable().apply {
                setColor(cBox); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), cBorder)
            }
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        parent.addView(et)
        return et
    }
}
