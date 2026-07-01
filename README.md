# WS REMCON – natív Android app (root NEM kell)

**v3.0**

WebSocket-vezérelt háttér-app Androidra, ami több feladatot lát el egyszerre,
kikapcsolt kijelzőnél is:

1. **USB (UVC) kamera → fotó + SFTP** – OTG-vel dugott külső webkameráról
   WebSocket-üzenetre fotót készít, elmenti a telefonra (DCIM), és feltölti SFTP-vel.
2. **Beépített kamera → SFTP** *(új, külön ki/be kapcsolható)* – figyeli a telefon
   beépített kamerájával készült új fotókat, és azonnal feltölti őket SFTP-vel,
   majd `uploaded` WebSocket-üzenetet küld.
3. **Média- és hangerővezérlés** – ugyanazon a WebSocket kapcsolaton
   rendszer-médiabillentyűket / hangerő-parancsokat ad ki.

> Root nélkül, Androidon ez az EGYETLEN stabil út a külső USB kamerához: a
> kamerát az Android USB Host API éri el (az AUSBC/UVC könyvtáron keresztül).
> Az egyetlen hardveres feltétel az **OTG / USB host** támogatás (a legtöbb
> telefon támogatja). A beépített kamera figyeléséhez nem kell OTG.

---

## Fő képernyő (füstüveg / sötét téma)

A felület sötét „füstüveg" stílusú, idővonalas állapot-nézettel:

- **Állapot & események** (idővonal): Szolgáltatás · WebSocket (+ URL) · Kamera.
- **Üzemmód**: az aktuális mód (Audio / Remote Controller) és a **Váltás** gomb.
- **Beépített kamera → SFTP**: a figyelés állapota, a mappa / feltöltve / utolsó
  fájl / utolsó esemény, és a **Bekapcsolás / Kikapcsolás** gomb.
- **Vezérlés**: mentés helye, SFTP, parancsok, készült képek, média események,
  utolsó esemény – alattuk a **Beállítások** és egy **Bekapcsolás / Kikapcsolás**
  gomb (a szolgáltatás indítása és leállítása egyetlen gombon).

A működés logikája megegyezik a korábbi verziókkal – ez a kiadás a kinézetet
frissítette (füstüveg + idővonal) és felemelte a verziót 3.0-ra.

---

## Fájlok

M�sold be őket, felülírva a generáltakat:

- `MainActivity.kt`, `CaptureService.kt`, `PhotoWatchService.kt`, `AppSettings.kt`
  → `app/src/main/java/com/example/usbcapture/`
- `AndroidManifest.xml` → `app/src/main/`
- `device_filter.xml` → `app/src/main/res/xml/`
- `app/build.gradle`, `settings.gradle` → cseréld a meglévőket

---

## Beállítás Android Studio-ban

1. **New Project → Empty Views Activity**, nyelv: **Kotlin**, csomagnév:
   `com.example.usbcapture`.
2. Másold be a fenti fájlokat.
3. **Sync Now**, majd **Run** ▶ a telefonodra. (A projekt GitHub Actions-szel is
   fordítható; a kész APK-t kézzel sideloadolod.)

Beépített könyvtárak (lásd `app/build.gradle`):
`okhttp` (WebSocket), `com.herohan:UVCAndroid` (USB/UVC kamera),
`com.github.mwiede:jsch` (SFTP/SSH).

---

## Első indítás – engedélyek

- **Kamera** + **Értesítés** engedély → *Engedélyezés*.
- **Akkumulátor-optimalizálás** → ne korlátozza az appot (az app maga kéri).
- USB kamera bedugásakor a rendszer rákérdez az eszköz-hozzáférésre → pipáld be
  a *„mindig ehhez az alkalmazáshoz"* opciót.
- A **beépített kamera figyeléséhez** külön kép-olvasási engedély kell
  (Android 13+: *fotók*, régebbin *tárhely*). Ezt az app csak akkor kéri el,
  amikor a funkciót bekapcsolod – így opcionális marad.

---

## WebSocket parancsok

A szerver szöveges üzenetei:

| Üzenet | Hatás |
| --- | --- |
| `takeapicture` | fotó a **külső USB (UVC)** kameráról → mentés + SFTP feltöltés → `uploaded` |
| `next` | következő szám (`KEYCODE_MEDIA_NEXT`) |
| `prev` | előző szám (`KEYCODE_MEDIA_PREVIOUS`) |
| `playpause` | lejátszás / szünet (`KEYCODE_MEDIA_PLAY_PAUSE`) |
| `hangerofel` | hangerő fel |
| `hangerole` | hangerő le |
| `setmode0` / `setmode1` | szerver mód váltása (Remote / Audio Controller) |

A parancsszövegek a **Beállítások**ban szabadon átírhatók (a fentiek az
alapértékek). A `mode0` = Remote Controller (kamera), `mode1` = Audio Controller
(zene). A **beépített kamera figyelő** ettől függetlenül, saját WebSocket
kapcsolaton küldi az `uploaded` üzenetet a saját feltöltései után.

---

## Beállítások

Egy ablakban, szekciókra bontva:

- **Általános**: WebSocket URL, fényképek mappa (a DCIM-en belül, külső kamera).
- **Média vezérlés**: következő / előző / lejátszás-szünet parancs.
- **Hangerő vezérlés**: hangerő fel / le parancs.
- **SFTP / SSH**: be/ki kapcsoló, host, port, felhasználó, jelszó, távoli mappa.
- **Beépített kamera → SFTP**: figyelt mappa (a DCIM-en belül, alapból `Camera`).

A „Visszaállítás alapértékekre" minden mezőt visszaállít.

---

## Beépített kamera → SFTP (részletek)

- Külön, önálló előtér-szolgáltatás (`PhotoWatchService`), a külső-kamerás
  résztől teljesen függetlenül, saját ki/be kapcsolóval és saját WebSocket
  kapcsolattal. Bármelyik futhat a másik nélkül.
- A MediaStore-t figyeli: amint a beépített kamera új képet ment a figyelt
  mappába (alapból `DCIM/Camera`), az eredeti fájlt SFTP-vel feltölti a beállított
  szerverre, majd sikeres feltöltéskor `uploaded` üzenetet küld.
- Csak kész (nem „pending") képeket tölt fel; sorozatfotókat egymás után.
- Az SFTP-beállításokat a közös beállításokból veszi (ugyanaz, mint a külső
  kameránál), de külön kapcsolatot kezel.

**Megjegyzések:** telefon-újraindítás után a figyelés nem indul el magától, csak
az app következő megnyitásakor (kérésre hozzáadható `BOOT_COMPLETED` figyelő).
Android 14+ alatt a `dataSync` típusú előtér-szolgáltatásnak van egy hosszú napi
össz-időkorlátja, ami nagyon hosszú, folyamatos futásnál számíthat; alkalmankénti
fotózásnál nem.

---

## Változások – v3.0

- Teljesen új, sötét **füstüveg** felület, **idővonalas** állapot-nézettel.
- A **beépített kamera → SFTP** figyelő funkció (külön kapcsolóval).
- Beállítások ablak az új stílusban, minden mezővel.
- Verzió: `versionName 3.0` (`versionCode 3`).
