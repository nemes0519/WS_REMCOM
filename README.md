# WS REMCON – natív Android app (root NEM kell)

Kikapcsolt kijelzőnél WebSocket üzenetre fotót készít a telefonba OTG-vel dugott
USB (UVC) webkameráról, és a telefonra menti.

> Root nélkül, Androidon ez az EGYETLEN stabil út a USB kamerához: a kamerát az
> Android USB Host API éri el (az AUSBC könyvtáron keresztül). Az egyetlen
> hardveres feltétel, hogy a telefonod támogassa az **OTG / USB host** módot
> (a legtöbb támogatja).

## Beállítás Android Studio-ban

1. **New Project → Empty Views Activity**, nyelv: **Kotlin**, csomagnév:
   `com.example.usbcapture`.
2. Másold be a fájlokat, felülírva a generáltakat:
   - `MainActivity.kt`, `CaptureService.kt` → `app/src/main/java/com/example/usbcapture/`
   - `AndroidManifest.xml` → `app/src/main/`
   - `device_filter.xml` → `app/src/main/res/xml/`
   - `app/build.gradle` és `settings.gradle` → cseréld a meglévőket
3. `CaptureService.kt` tetején írd be a `WS_URL`-t (a saját WebSocket címed).
4. **Sync Now**, majd **Run** ▶ a telefonodra.

## Első indítás – engedélyek

- Kamera + Értesítés engedély → *Engedélyezés*.
- Akkumulátor-optimalizálás → ne korlátozza az appot.
- USB kamera bedugásakor a rendszer rákérdez az eszköz-hozzáférésre → pipáld be
  a *„mindig ehhez az alkalmazáshoz"* opciót.

A fotók ide kerülnek: `Android/data/com.example.usbcapture/files/Pictures/`

## Media (zene) vezérlés – beépítve

Az app a kamera-trigger mellett **médialejátszót is vezérel** ugyanazon a
WebSocket kapcsolaton. Amikor a szerver az alábbi szöveges üzeneteket küldi,
az app rendszer-médiabillentyűt ad ki (VLC és bármely médialejátszó reagál rá,
ami kezeli a médiabillentyűket):

- `next` → következő szám (`KEYCODE_MEDIA_NEXT`)
- `prev` → előző szám (`KEYCODE_MEDIA_PREVIOUS`)
- `pause` → lejátszás / szünet (`KEYCODE_MEDIA_PLAY_PAUSE`)

A három parancsszöveg a **Beállítások → MEDIA VEZERLES** alatt szabadon
átírható (a fenti értékek az alapértelmezések). A kamera triggere (`takeapicture`)
és ezek a parancsok külön üzenetek, így nem ütköznek – egy app látja el mindkét
szerepet (`mode0` = Remote Controller / kamera, `mode1` = Audio Controller / zene).
Az alkalmazás bármelyik szerver-módban a beérkező üzenetnek megfelelően reagál,
a „Mod valtasa" gomb pedig a szerver módját kapcsolja (`setmode0` / `setmode1`).

> A médiabillentyűk küldéséhez nem kell külön engedély. A háttérben futáshoz itt
> is érdemes kikapcsolni az akku-optimalizálást (lásd lentebb).

## Tesztelés

Küldj bármilyen WebSocket üzenetet → minden üzenet egy fotót vált ki.
A `Logcat`-ben (`CaptureService` tag) követheted, mi történik.

---

## Hogyan működik a fotózás (a megbízható rész)

Nem a `captureImage` hívást használjuk (az preview felületre várhat), hanem a
`setRawPreviewData(true)` + `addPreviewDataCallBack` párost: a kamera nyers NV21
képkockáit kapjuk meg, és amikor üzenet jön, a következő képkockát JPEG-be mentjük
(`YuvImage`). Ehhez nem kell látható kamerakép, ezért működik háttérben,
kikapcsolt kijelzővel.

## Ha nem készül fotó – hibakeresés (Logcat)

- **„could not negotiate with camera" / „unsupported preview size"**: a kamerád
  nem tudja az 1280x720-at. A kód `onCameraState` → `OPENED` ágban már lekéri a
  támogatott méreteket (`getAllPreviewSizes`) és a legnagyobbra vált – de ha az
  induló kérés elbukik, állítsd a `DEFAULT_W`/`DEFAULT_H`-t pl. 640x480-ra.
- **Mentési hiba „nem NV21 a formátum"**: néhány kamera más formátumot ad. Ekkor a
  `CameraRequest.Builder()`-be vedd fel:
  `.setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)` és/vagy igazítsd a
  mentést a kapott `format`-hoz.
- **Fordítási hiba az `onPreviewData`-nál**: verziótól függően a szignatúra lehet
  `onPreviewData(data, width, height, format)` is. Ekkor használd a callbackből
  kapott `width`/`height`-ot a `frameW`/`frameH` helyett.
- Ground truth a pontos API-hoz a hivatalos demo:
  https://github.com/jiangdongguo/AndroidUSBCamera

## Gyártói korlátozások (fontos a stabil háttérfutáshoz)

Sok ROM (Xiaomi/MIUI, Samsung, Oppo, Huawei) agresszíven leállítja a háttér-appokat.
Telefon beállításaiban Termux… ill. itt az appnál kapcsold be az
**„Autostart" / korlátlan háttérfutás** engedélyt, és vedd ki az akku-optimalizálásból.

## Galériába mentés (opcionális)

Most az app-saját mappába ment (nem kell tárhely-engedély). Ha a Galériában is
látni akarod, a `saveNv21AsJpeg` után tedd be a fájlt a `MediaStore`-ba – szólj,
és megírom.

---

## Fordítás Android Studio NÉLKÜL (GitHub Actions, ingyen)

Nem kell semmit telepítened a gépedre. A felhőben fordul le, és letöltöd a kész APK-t.

1. Készíts egy ingyenes **GitHub** fiókot, majd egy **új repository**-t.
2. Töltsd fel ennek a projektnek a teljes tartalmát a repóba, a **mappaszerkezetet
   megtartva** (a legegyszerűbb a mellékelt ZIP kicsomagolása, majd a fájlok feltöltése,
   vagy `git push`). Fontos, hogy meglegyen a `.github/workflows/build.yml` is.
3. A feltöltés (push a `main` ágra) automatikusan elindítja a fordítást.
   A repó felső menüjében az **Actions** fülön látod a futást.
4. Ha kész (zöld pipa), kattints a futásra, és lent a **Artifacts** résznél töltsd le az
   **app-debug-apk** fájlt. Ez egy zip, benne az `app-debug.apk`.
5. Másold a telefonodra, és telepítsd (engedélyezd az „ismeretlen forrásból
   telepítést"). Ez egy debug APK, közvetlenül telepíthető.

Ha módosítasz valamit (pl. a `WS_URL`-t), elég újra feltölteni a fájlt – újrafordul.

### Egyéb felhős lehetőségek
- **GitHub Codespaces** vagy **Gitpod**: teljes felhős fejlesztőkörnyezet a böngészőben,
  ahol szintén futtathatod a `gradle assembleDebug`-ot.
- **Telefonos fordítók (pl. AIDE):** ezt NEM ajánlom ehhez, mert az AUSBC natív
  könyvtárakat (.so) tartalmaz, amit az on-device fordítók általában nem kezelnek jól.

---

## SFTP (SSH) feltöltés – a kész kép azonnali feltöltése

A `CaptureService.kt` config blokkjában kapcsolható és állítható:

```kotlin
private const val SFTP_ENABLED = true                      // kapcsold true-ra
private const val SFTP_HOST = "192.168.1.10"               // szerver címe
private const val SFTP_PORT = 22                           // port (alap: 22)
private const val SFTP_USER = "felhasznalo"
private const val SFTP_PASSWORD = "jelszo"
private const val SFTP_REMOTE_DIR = "/home/felhasznalo/kamera"  // célmappa
```

Hogyan működik: a fotó elkészülte után a kép helyben is mentődik (DCIM/DCIM2),
és ezzel egyidőben egy külön háttérszálon felmegy SFTP-n a megadott mappába
(amit létrehoz, ha még nincs). A feltöltések sorban futnak, így nem blokkolják
a kamerát. Ha a feltöltés hibázik (pl. nincs net), a Logcatben `SFTP hiba`
látszik, de a helyi mentés akkor is megvan.

### Biztonsági megjegyzések (őszintén)
- A jelszó a forráskódban szerepel – a saját, privát használatodhoz ez rendben
  lehet, de ha a repó publikus a GitHubon, **mindenki látja**. Tartsd a repót
  privátként, vagy használj jelszó helyett SSH-kulcsot.
- A kód `StrictHostKeyChecking = no`-val fut, ami egyszerű, de elvileg sebezhető
  „man-in-the-middle" támadásra. Megbízható hálózaton (pl. saját szerver) ez
  általában elfogadható; nagyobb biztonsághoz a szerver kulcsát known_hosts-ba
  kellene tenni.

### SSH-kulcsos belépés (jelszó helyett, opcionális)
Ha kulccsal szeretnél belépni, a `jsch.getSession(...)` előtt:
```kotlin
jsch.addIdentity("/data/data/com.example.usbcapture/files/id_rsa")
```
és hagyd ki a `session.setPassword(...)` sort. A privát kulcsot előbb az app
elérhető helyére kell másolni. Szólj, ha ezt is bekészítsem.
