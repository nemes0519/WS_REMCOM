package com.example.usbcapture

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Inditokepernyo + allapotkijelzo + beallitasok.
 *  - elkeri az engedelyeket es elinditja a hatterszolgaltatast
 *  - elo allapotot mutat (zold/piros pottyel a WebSocket es a Kamera)
 *  - "Beállítások": WebSocket URL, fenykep mappa, SFTP/SSH szerkesztese
 *  - "Szolgáltatás indítása" / "Szolgáltatás leállítása": kezi vezerles
 *
 * Ha kesobb ujra radugsz egy USB kamerat, az app az USB_DEVICE_ATTACHED
 * miatt ujraindul es a service megint elindul - mint eddig.
 */
class MainActivity : ComponentActivity() {

    // szinek
    private val cGreen = Color.parseColor("#16A34A")
    private val cRed = Color.parseColor("#DC2626")
    private val cGray = Color.parseColor("#9CA3AF")
    private val cHeader = Color.parseColor("#1F2937")
    private val cHeaderSub = Color.parseColor("#9CA3AF")
    private val cPageBg = Color.parseColor("#F3F4F6")
    private val cBorder = Color.parseColor("#E5E7EB")
    private val cTextMain = Color.parseColor("#111827")
    private val cTextSub = Color.parseColor("#6B7280")
    private val cBlue = Color.parseColor("#2563EB")
    private val cPurple = Color.parseColor("#7C3AED")
    private val cBlueSoft = Color.parseColor("#DBEAFE")
    private val cPurpleSoft = Color.parseColor("#EDE9FE")
    private val cGraySoft = Color.parseColor("#E5E7EB")

    private lateinit var svcDot: View
    private lateinit var svcValue: TextView
    private lateinit var wsDot: View
    private lateinit var wsValue: TextView
    private lateinit var wsUrlText: TextView
    private lateinit var camDot: View
    private lateinit var camValue: TextView
    private lateinit var infoText: TextView

    // uzemmod kartya elemei
    private lateinit var modeBadge: TextView
    private lateinit var modeSubText: TextView
    private lateinit var modeSwitchBtn: Button

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
        val scroll = ScrollView(this).apply { setBackgroundColor(cPageBg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // fejlec
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cHeader); cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        header.addView(TextView(this).apply {
            text = "WS REMCON"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Háttérben fut: WebSocket → fotó + SFTP / média vezérlés"
            setTextColor(cHeaderSub)
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(header, lp(bottom = dp(14)))

        // allapot kartya
        val statusCard = cardView()
        statusCard.addView(sectionTitle("ÁLLAPOT"))

        // Szolgaltatas sor (ugyanolyan stilus, mint a WebSocket es Kamera)
        val svcRow = rowView()
        svcDot = dotView()
        svcRow.addView(svcDot, dotLp())
        val svcCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        svcCol.addView(TextView(this).apply {
            text = "Szolgáltatás"; setTextColor(cTextMain); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        svcValue = TextView(this).apply { textSize = 14f; setTextColor(cTextSub) }
        svcCol.addView(svcValue)
        svcRow.addView(svcCol)
        statusCard.addView(svcRow, lp(top = dp(6)))

        // WebSocket sor
        val wsRow = rowView()
        wsDot = dotView()
        wsRow.addView(wsDot, dotLp())
        val wsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wsCol.addView(TextView(this).apply {
            text = "WebSocket"; setTextColor(cTextMain); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        wsValue = TextView(this).apply { textSize = 14f; setTextColor(cTextSub) }
        wsCol.addView(wsValue)
        wsUrlText = TextView(this).apply { textSize = 11f; setTextColor(cTextSub) }
        wsCol.addView(wsUrlText)
        wsRow.addView(wsCol)
        statusCard.addView(wsRow, lp(top = dp(12)))

        // Kamera sor
        val camRow = rowView()
        camDot = dotView()
        camRow.addView(camDot, dotLp())
        val camCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        camCol.addView(TextView(this).apply {
            text = "Kamera"; setTextColor(cTextMain); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        camValue = TextView(this).apply { textSize = 14f; setTextColor(cTextSub) }
        camCol.addView(camValue)
        camRow.addView(camCol)
        statusCard.addView(camRow, lp(top = dp(12)))

        // elvalaszto
        val divider = View(this).apply { setBackgroundColor(cBorder) }
        statusCard.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(14); bottomMargin = dp(12) })

        // info blokk
        infoText = TextView(this).apply {
            textSize = 13f; setTextColor(cTextMain)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        statusCard.addView(infoText)

        root.addView(statusCard, lp(bottom = dp(14)))

        // uzemmod kartya
        val modeCard = cardView()
        modeCard.addView(sectionTitle("ÜZEMMÓD"))

        modeBadge = TextView(this).apply {
            text = "Ismeretlen"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cTextSub)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(cGraySoft); cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        modeCard.addView(modeBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL })

        modeSubText = TextView(this).apply {
            text = "A mód a WebSocket csatlakozása után kérdezhető le."
            textSize = 12f
            setTextColor(cTextSub)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        modeCard.addView(modeSubText)

        modeSwitchBtn = filledButton("Mód váltása", cPurple)
        modeSwitchBtn.setOnClickListener { switchMode() }
        modeCard.addView(modeSwitchBtn, lp(top = dp(8)))

        root.addView(modeCard, lp(bottom = dp(14)))

        // gombok
        // vezerles kartya: a harom gomb egy helyen
        val controlCard = cardView()
        controlCard.addView(sectionTitle("VEZÉRLÉS"))

        val settingsBtn = filledButton("Beállítások", cBlue)
        settingsBtn.setOnClickListener { showSettingsDialog() }
        controlCard.addView(settingsBtn, lp(top = dp(10)))

        val startBtn = filledButton("Szolgáltatás indítása", cGreen)
        startBtn.setOnClickListener { startServiceManually() }
        controlCard.addView(startBtn, lp(top = dp(10)))

        val stopBtn = filledButton("Szolgáltatás leállítása", cRed)
        stopBtn.setOnClickListener { confirmStop() }
        controlCard.addView(stopBtn, lp(top = dp(10)))

        root.addView(controlCard)

        root.addView(TextView(this).apply {
            text = "Ha újra csatlakoztatsz egy USB kamerát, a szolgáltatás " +
                    "automatikusan újraindul."
            textSize = 12f; setTextColor(cTextSub)
            setPadding(dp(4), dp(16), dp(4), 0)
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
        wsUrlText.text = AppSettings.wsUrl(this)

        camDot.background = dotDrawable(if (cam) cGreen else cRed)
        camValue.text = if (cam) "Kesz - ${CaptureService.cameraInfo}" else "Nincs csatlakoztatva"

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

        refreshModeUi(ws)
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
            setColor(bgColor); cornerRadius = dp(24).toFloat()
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

    // -------------------------- Beallitasok --------------------------

    private fun showSettingsDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val wsField = field(form, "WebSocket URL", AppSettings.wsUrl(this),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val albumField = field(form, "Fényképek mappa (a DCIM-en belül)",
            AppSettings.album(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionTitle("MÉDIA VEZÉRLÉS (zenelejátszó)")
            .apply { setPadding(0, dp(20), 0, dp(2)) })

        val nextField = field(form, "Következő szám parancs",
            AppSettings.cmdNext(this), InputType.TYPE_CLASS_TEXT)
        val prevField = field(form, "Előző szám parancs",
            AppSettings.cmdPrev(this), InputType.TYPE_CLASS_TEXT)
        val playPauseField = field(form, "Lejátszás / szünet parancs",
            AppSettings.cmdPlayPause(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionTitle("HANGERŐ VEZÉRLÉS")
            .apply { setPadding(0, dp(20), 0, dp(2)) })

        val volUpField = field(form, "Hangerő fel parancs",
            AppSettings.cmdVolUp(this), InputType.TYPE_CLASS_TEXT)
        val volDownField = field(form, "Hangerő le parancs",
            AppSettings.cmdVolDown(this), InputType.TYPE_CLASS_TEXT)

        form.addView(sectionTitle("SFTP / SSH").apply { setPadding(0, dp(20), 0, dp(2)) })

        val sftpCheck = CheckBox(this).apply {
            text = "SFTP feltöltés bekapcsolva"
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

        val resetBtn = Button(this).apply {
            text = "Visszaallitas alapertekekre"
            isAllCaps = false
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
            }
        }
        form.addView(resetBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        val scroll = ScrollView(this).apply { addView(form) }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Beállítások")
            .setView(scroll)
            .setPositiveButton("Mentés") { _, _ ->
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
                    cmdVolDown = volDownField.text.toString()
                )
                Toast.makeText(this, "Beállítások mentve", Toast.LENGTH_SHORT).show()
                notifyServiceSettingsChanged()
                refreshStatus()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun notifyServiceSettingsChanged() {
        // a WebSocket URL valtozas ervenyesitese: csak ha fut a service
        if (!CaptureService.serviceRunning) return
        try {
            val i = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_RECONNECT_WS
            }
            startService(i)
        } catch (_: Exception) {
        }
    }

    // -------------------------- Service vezerles --------------------------

    private fun confirmStop() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
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
            setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), cBorder)
        }
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(cTextSub)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f
    }

    private fun rowView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dotView(): View = View(this).apply { background = dotDrawable(cGray) }

    private fun dotLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) }

    private fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    // lekerekitett hatter + kattintaskor "villano" ripple visszajelzes
    private fun rippleBg(content: GradientDrawable, rippleColor: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun filledButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        val content = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(12).toFloat()
        }
        background = rippleBg(content, Color.parseColor("#66FFFFFF"))
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun field(parent: LinearLayout, label: String, value: String, type: Int): EditText {
        parent.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(cTextSub)
            setPadding(0, dp(12), 0, dp(2))
        })
        val et = EditText(this).apply {
            setText(value); inputType = type; textSize = 15f
        }
        parent.addView(et)
        return et
    }
}
