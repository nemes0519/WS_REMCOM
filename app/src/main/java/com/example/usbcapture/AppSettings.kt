package com.example.usbcapture

import android.content.Context

/**
 * Beallitasok tarolasa SharedPreferences-ben.
 *
 * Az alapertekek (DEF_*) a regi, beegetett ertekek - ezeket a felhasznalo
 * az appbol felulirhatja, es az ertekek a keszuleken, az app privat
 * tarhelyen tarolodnak (nem kerulnek a forraskodba / a repoba).
 *
 * A CaptureService mindig innen, futas kozben olvassa az aktualis ertekeket,
 * igy a mentett valtoztatasok kulon ujraepites nelkul ervenyesulnek.
 */
object AppSettings {

    private const val PREFS = "usbcapture_prefs"

    private const val K_WS_URL = "ws_url"
    private const val K_ALBUM = "album"
    private const val K_SFTP_ENABLED = "sftp_enabled"
    private const val K_SFTP_HOST = "sftp_host"
    private const val K_SFTP_PORT = "sftp_port"
    private const val K_SFTP_USER = "sftp_user"
    private const val K_SFTP_PASS = "sftp_pass"
    private const val K_SFTP_DIR = "sftp_dir"

    // media (zenelejatszo) vezerles parancsai
    private const val K_CMD_NEXT = "cmd_next"
    private const val K_CMD_PREV = "cmd_prev"
    private const val K_CMD_PLAYPAUSE = "cmd_playpause"

    // hangero vezerles parancsai
    private const val K_CMD_VOLUP = "cmd_volup"
    private const val K_CMD_VOLDOWN = "cmd_voldown"

    // beepitett kamera figyeles (kulon ki/be kapcsolhato szolgaltatas)
    private const val K_PHOTO_WATCH_ENABLED = "photo_watch_enabled"
    private const val K_PHOTO_WATCH_FOLDER = "photo_watch_folder"

    // ===================== ALAP BEALLITASOK =====================
    const val DEF_WS_URL = "wss://socket.levstack.hu/sound"
    const val DEF_ALBUM = "DCIM2"
    const val DEF_SFTP_ENABLED = true
    const val DEF_SFTP_HOST = "rtmp.levstack.hu"
    const val DEF_SFTP_PORT = 10132
    const val DEF_SFTP_USER = "eddy0519"
    const val DEF_SFTP_PASS = "whoistheking"
    const val DEF_SFTP_DIR = "/var/www/html/photos"

    // media vezerles alapertelmezett parancsai (a szerver ezeket az
    // uzeneteket kuldi a zene leptetesehez) - az appbol szerkesztheto
    const val DEF_CMD_NEXT = "next"
    const val DEF_CMD_PREV = "prev"
    const val DEF_CMD_PLAYPAUSE = "pause"

    // hangero vezerles alapertelmezett parancsai - az appbol szerkesztheto
    const val DEF_CMD_VOLUP = "hangerofel"
    const val DEF_CMD_VOLDOWN = "hangerole"

    // beepitett kamera figyeles alapertekei
    // alapbol KIKAPCSOLVA (a felhasznalo kapcsolja be a fo kepernyon);
    // a figyelt mappa a DCIM-en belul (a legtobb telefonon "Camera")
    const val DEF_PHOTO_WATCH_ENABLED = false
    const val DEF_PHOTO_WATCH_FOLDER = "Camera"
    // ============================================================

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wsUrl(ctx: Context): String =
        prefs(ctx).getString(K_WS_URL, DEF_WS_URL) ?: DEF_WS_URL

    fun album(ctx: Context): String =
        prefs(ctx).getString(K_ALBUM, DEF_ALBUM) ?: DEF_ALBUM

    fun sftpEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_SFTP_ENABLED, DEF_SFTP_ENABLED)

    fun sftpHost(ctx: Context): String =
        prefs(ctx).getString(K_SFTP_HOST, DEF_SFTP_HOST) ?: DEF_SFTP_HOST

    fun sftpPort(ctx: Context): Int =
        prefs(ctx).getInt(K_SFTP_PORT, DEF_SFTP_PORT)

    fun sftpUser(ctx: Context): String =
        prefs(ctx).getString(K_SFTP_USER, DEF_SFTP_USER) ?: DEF_SFTP_USER

    fun sftpPass(ctx: Context): String =
        prefs(ctx).getString(K_SFTP_PASS, DEF_SFTP_PASS) ?: DEF_SFTP_PASS

    fun sftpDir(ctx: Context): String =
        prefs(ctx).getString(K_SFTP_DIR, DEF_SFTP_DIR) ?: DEF_SFTP_DIR

    fun cmdNext(ctx: Context): String =
        prefs(ctx).getString(K_CMD_NEXT, DEF_CMD_NEXT) ?: DEF_CMD_NEXT

    fun cmdPrev(ctx: Context): String =
        prefs(ctx).getString(K_CMD_PREV, DEF_CMD_PREV) ?: DEF_CMD_PREV

    fun cmdPlayPause(ctx: Context): String =
        prefs(ctx).getString(K_CMD_PLAYPAUSE, DEF_CMD_PLAYPAUSE) ?: DEF_CMD_PLAYPAUSE

    fun cmdVolUp(ctx: Context): String =
        prefs(ctx).getString(K_CMD_VOLUP, DEF_CMD_VOLUP) ?: DEF_CMD_VOLUP

    fun cmdVolDown(ctx: Context): String =
        prefs(ctx).getString(K_CMD_VOLDOWN, DEF_CMD_VOLDOWN) ?: DEF_CMD_VOLDOWN

    fun photoWatchEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(K_PHOTO_WATCH_ENABLED, DEF_PHOTO_WATCH_ENABLED)

    fun photoWatchFolder(ctx: Context): String =
        prefs(ctx).getString(K_PHOTO_WATCH_FOLDER, DEF_PHOTO_WATCH_FOLDER) ?: DEF_PHOTO_WATCH_FOLDER

    /**
     * A figyeles ki/be kapcsolasa kulon, sajat metoduson keresztul tarolodik
     * (a fo kepernyo kapcsoloja allitja). Igy a Beallitasok parbeszedablak
     * mentese (save) NEM irja felul ezt az allapotot.
     */
    fun setPhotoWatchEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(K_PHOTO_WATCH_ENABLED, enabled).apply()
    }

    fun save(
        ctx: Context,
        wsUrl: String,
        album: String,
        sftpEnabled: Boolean,
        sftpHost: String,
        sftpPort: Int,
        sftpUser: String,
        sftpPass: String,
        sftpDir: String,
        cmdNext: String,
        cmdPrev: String,
        cmdPlayPause: String,
        cmdVolUp: String,
        cmdVolDown: String,
        photoWatchFolder: String
    ) {
        prefs(ctx).edit()
            .putString(K_WS_URL, wsUrl.trim())
            .putString(K_ALBUM, album.trim())
            .putBoolean(K_SFTP_ENABLED, sftpEnabled)
            .putString(K_SFTP_HOST, sftpHost.trim())
            .putInt(K_SFTP_PORT, sftpPort)
            .putString(K_SFTP_USER, sftpUser.trim())
            .putString(K_SFTP_PASS, sftpPass)
            .putString(K_SFTP_DIR, sftpDir.trim())
            .putString(K_CMD_NEXT, cmdNext.trim())
            .putString(K_CMD_PREV, cmdPrev.trim())
            .putString(K_CMD_PLAYPAUSE, cmdPlayPause.trim())
            .putString(K_CMD_VOLUP, cmdVolUp.trim())
            .putString(K_CMD_VOLDOWN, cmdVolDown.trim())
            .putString(K_PHOTO_WATCH_FOLDER, photoWatchFolder.trim())
            .apply()
    }
}
