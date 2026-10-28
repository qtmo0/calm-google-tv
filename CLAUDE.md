# Calm Google TV – Anleitung für Claude Code

Dieses Repo richtet einen Google TV ruhig und schnell ein: aufräumen, eigener Startbildschirm,
Glühwürmchen-Bildschirmschoner, Fernbedienungs-Tasten, Zeiten. Hintergrund und Details: `README.md`.
Antworte in der Sprache des Nutzers, in einfachen Worten – er ist kein Entwickler.

## Grundregeln (immer)

1. **Vor jeder Änderung am TV im Chat fragen** und auf ein klares Ja warten. Lesen (`connect`, `info`,
   `inventory`, `keys`) darf ohne Frage. Eine Zustimmung gilt nur für den genannten Schritt.
2. **Apps nur ausschalten, nie deinstallieren** (außer den drei eigenen Apps bei `undo`).
3. **Nie ausschalten:** Google Play-Dienste (`com.google.android.gms`), Play Store, Einstellungen, System UI,
   Einrichtungs-Apps, Tastatur, Bluetooth, HDMI/CEC, TV-Eingangs- und Tuner-Framework oder sonst etwas, das
   der TV zum Starten braucht. Im Zweifel anlassen. `tvctl.sh disable` blockt die wichtigsten davon.
   **Einzige Ausnahme:** Die Google-TV-Einrichtungs-App (`com.google.android.tungsten.setupwraith`) darf nur
   über `tvctl.sh home … --launcher --setup` aus – und nur, wenn sie nachweislich Home übernimmt **und der
   Nutzer dem ausdrücklich zugestimmt hat**.
4. **Immer `tvctl.sh` benutzen**, nicht eigene adb-Befehle für Änderungen – es sichert, prüft und protokolliert.
   `setup.sh` ist für Menschen im Terminal (fragt per Tastatur) und funktioniert in Claude Code nicht.
5. **Ergebnisse ehrlich berichten:** Was getestet ist, was nicht. Nie „fertig“ sagen, bevor der TV nach dem
   Neustart normal gestartet ist.

## Ablauf für einen neuen TV

```
sh tvctl.sh connect   <IP>      # bei UNAUTHORIZED: Nutzer am TV "Zulassen" drücken lassen
sh tvctl.sh info      <IP>
sh tvctl.sh inventory <IP>      # alle Apps mit Einschätzung
sh tvctl.sh keys      <IP>      # passt die Fernbedienung zu remote-keys?
```

Dann dem Nutzer **vor jeder Änderung** in einfachen Worten zeigen:
- alle Apps in drei Gruppen: *kann aus* · *braucht der TV* · *nutzt du* (Nutzer fragen, welche er nutzt)
- freien Speicher

Danach, jeweils nach Zustimmung:

| Schritt | Befehl |
|---|---|
| 0. Ausgangszustand sichern (Pflicht, zuerst) | `sh tvctl.sh backup <IP>` |
| 1. Werbung/Extras aus (einzeln fragen) | `sh tvctl.sh disable <IP> <paket>` – Kandidaten: `com.google.android.backdrop`, `com.google.android.videos`, `com.google.android.tvrecommendations`, `com.google.android.apps.education.cast2class`, `com.google.android.apps.tv.dreamx` (Google-Schoner/Ambient, auch Google-Home-Panel) |
| 2. Startbildschirm | `sh tvctl.sh home <IP> "<pakete,kommagetrennt>"` – meldet er, dass der TV-eigene Startbildschirm gewinnt: fragen, dann mit `--launcher`. **`--launcher` nur, wenn adb über USB oder Port 5555 geht** (nicht über kabelloses Debugging mit Pairing-Code, z. B. Chromecast) – sonst droht eine Neustart-Schleife ohne Rückweg. Meldet er danach die Einrichtungs-App: **gesondert** fragen, dann `--launcher --setup` |
| 3. Bildschirmschoner | `sh tvctl.sh screensaver <IP>` |
| 4. Zeiten | `sh tvctl.sh times <IP> 3 30` (Schoner 3 Min., Ruhe 30 Min.; Nutzer darf andere Werte wählen) |
| 5. Animationen aus | `sh tvctl.sh animations <IP> off` |
| 6. Fernbedienungs-Tasten | `sh tvctl.sh remote-keys <IP>` – nur wenn `keys` BUTTON_3/BUTTON_4 zeigt. Mit der **echten** Fernbedienung testen lassen |
| 7. LG-Fernseher koppeln (nur wenn der Streamer an einem LG mit webOS hängt; fragen!) | `sh tvctl.sh lg-pair <IP> [LG_IP]` – der Nutzer muss am LG mit der LG-Fernbedienung „Zulassen“ drücken. Danach erscheinen Konsolen/HDMI-Geräte in der Leiste |
| 8. Neustart + Kontrolle | `sh tvctl.sh reboot <IP>` (wartet selbst 30 s vorher) |

Zum Schluss `sh tvctl.sh info <IP>` und dem Nutzer freien Speicher vorher/nachher zeigen
(`mem_before` steht in `tvs/<modell>_<seriennummer>/backup.txt`).

**App-Liste für den Startbildschirm:** Den Nutzer fragen, welche Apps er nutzt und in welcher Reihenfolge.
Installierte Apps: `adb -s <IP>:5555 shell pm list packages -3`; Namen per `inventory`. Bis zu 6 Apps passen in
eine Reihe, mehr werden zu weiteren Reihen. Tailscale (`com.tailscale.ipn`) nie in die Liste – es erscheint
automatisch als Schild oben rechts, wenn es installiert ist.
Beispiel (Liste des Autors): `com.github.damontecres.wholphin,com.google.android.youtube.tv,com.zattoo.player,de.swr.avp.ard.tv,com.zdf.android.mediathek,com.amazon.amazonvideo.livingroom`

**Fernbedienungs-Tasten:** Die fertige App legt YouTube-Taste → YouTube und Netflix-Taste → **Wholphin** (ein
Jellyfin-Client), Stern-Taste (`MACRO_1`) → Zattoo, direkt im Reiter Programm
(App-Link `https://zattoo.com/guide`; klappt der nicht, startet Zattoo normal). Ist der Programmführer Zattoos
**erste** Seite, zeigt er die alte Stelle (Stunden zurück); kommt man von einer anderen Seite, steht er bei „jetzt“.
Deshalb öffnet die Taste erst `https://zattoo.com/live` (Wunsch des Nutzers, nicht die Aufnahmen), wartet, bis Zattoo 5 Fenster-Änderungen gemeldet hat
(+0,6 s; Bedienungshilfe bekommt dafür nur Ereignisse von `com.zattoo.player`, keine Inhalte), dann den Programmführer.
Die Zattoo-Kachel im Startbildschirm öffnet `https://zattoo.com/live` (letzter live gesehener Sender im Vollbild;
`openZattooLive` in `MainActivity.java`). Links immer mit `CLEAR_TOP|SINGLE_TOP` übergeben, **nie**
`FLAG_ACTIVITY_CLEAR_TASK`: Mit zwei Zattoo-Fenstern stürzt Zattoo beim Schließen ab
(„Receiver not registered“ in `adb logcat -b crash`). Die Stern-Taste kommt nur an, wenn in den Einstellungen
der Fernbedienung die Eingangsumschaltung per Infrarot dafür aus ist; sonst geht sie direkt an den Fernseher. Das vorher sagen. Will der Nutzer ein anderes Ziel für die Netflix-Taste (oder hat er Netflix
und will die Taste behalten), Schritt 6 weglassen oder `targetFor()` in
`source/remote-keys/src/io/github/qtmo0/calmtv/remotekeys/TastenService.java` ändern und neu bauen (braucht Java + SDK, siehe
„Code ändern“).

## Pro TV festgehalten

`tvs/<modell>_<seriennummer>/backup.txt` (Ausgangszustand, nie löschen; Ordnername kommt von `tvctl.sh`, die IP darf sich ändern) und `changes.md` (jede Änderung mit
Rückgängig-Befehl, schreibt `tvctl.sh` selbst). Änderungen, die du ausnahmsweise per Hand machst, dort
nachtragen. `tvs/` ist per `.gitignore` vom öffentlichen Repo ausgeschlossen und bleibt beim Nutzer lokal –
nicht löschen, `undo` braucht es.

## Bekannte Eigenheiten

- **„No route to host“**, obwohl `ping`/`nc -z <IP> 5555` gehen: adb auf dem Mac hängt.
  `tvctl.sh` startet adb dann selbst neu. Sonst: `adb kill-server && adb start-server`.
- **Bildschirmschoner springt zum Testen nicht an:** `adb shell dumpsys power | grep Undim` – Android 14 hält
  den Bildschirm ~10 Min. wach (`UndimDetectorWakeLock`), v. a. nachdem man eine App aktualisiert hat,
  während der Schoner lief. Lösung: Nutzer den Streamer kurz aus- und einschalten lassen, oder warten.
  Zum Testen kurz `adb shell settings put system screen_off_timeout 20000` und danach den alten Wert
  zurücksetzen. Apps möglichst aktualisieren, wenn der Schoner **nicht** läuft.
- **Simulierte Tasten** (`adb shell input keyevent`) erreichen den Fernbedienungs-Dienst nicht. Echte
  Tastendrücke mitschneiden: `adb shell 'timeout 40 getevent -lt'` (macOS hat kein `timeout`).
- **Kein Root:** `cmd dreams` geht nicht; Bildschirmschoner nur über die Wartezeit auslösen.
- **zsh:** `S="adb -s …"; $S shell` funktioniert nicht – Funktionen nutzen.
- **Bildschirmfotos** (`adb exec-out screencap -p > x.png`) sind immer 1080p, auch wenn der Schoner in 4K rendert.

## Code ändern

- Bauen: `sh build-all.sh` (braucht Java 17 + Android SDK 34, kein Gradle). Danach einzeln aufspielen:
  `adb -s <IP>:5555 install -r apk/<name>.apk` – Einstellungen bleiben erhalten, wenn mit demselben
  Schlüssel signiert (`source/*/key.jks`). Fehlt der Schlüssel, muss die App erst deinstalliert werden –
  das vorher mit dem Nutzer klären (Kachel-Reihenfolge geht verloren).
- **Leistung messen**, bevor du etwas als fertig meldest:
  Startbildschirm/Canvas: `adb shell dumpsys gfxinfo <paket>` (Janky frames, 50th percentile).
  Bildschirmschoner (OpenGL): schreibt `fps … at 3840x2160` ins Log: `adb logcat -d 'OledDream:*' '*:S'`.
  CPU: `adb shell top -b -n 1 -o %CPU,NAME | grep <paket>`.
- **Grafikchip ist schwach:** Ein Vollbild-Shader mit Schleife über 7 Lichter lief mit 4 fps in 4K. Nur
  kleine Flächen zeichnen (z. B. ein Quadrat pro Objekt, additives Blending). Ziel: 30 fps, < 15 % eines Kerns.
- **Dunkle Verläufe auf OLED:** brauchen Dithering. `Paint.setDither` / `GradientDrawable.setDither` wirken beim
  GPU-Zeichnen nicht. Startbildschirm-Hintergrund wird deshalb einmal pro Tageszeit als Bild mit Rauschen
  berechnet; der Schoner dithert im Shader (±1 Stufe, pro Bild wechselnd, zu Schwarz hin ausgeblendet).
- Design-Entwürfe vorher als HTML-Mockup zeigen (`mockups/`), erst nach Zustimmung einbauen.
- **Tageszeit-Wechsel testen**, ohne die Uhr des TVs zu verstellen (nur per adb möglich, Apps dürfen das nicht):
  `adb shell am broadcast -a com.ansub.tvhome.TEST_PHASE --ei phase 2` (0 Morgen, 1 Tag, 2 Abend, 3 Nacht;
  `-1` = wieder echte Uhrzeit – danach **immer** zurückstellen). Wirkt wie die volle Stunde: Ist Home offen,
  wird 2 s übergeblendet; liegt eine andere App vorne, wird der Hintergrund still vorbereitet. Log:
  `adb logcat -d -s tvhome` („background ready, phase N (fading|swapped quietly)“).

## LG-Fernseher (HDMI-Geräte in der Leiste)

- Suche: SSDP; antwortet kein LG (manche hören nach einer Weile auf, Ports bleiben offen), Scan des eigenen /24-
  Heimnetzes auf Port 3001, bestätigt über die Gerätebeschreibung (Port 1954, „LG Electronics“). Mehrere LGs →
  ein Menüeintrag pro TV mit Name/Modell. Gemerkt werden Host und UUID; bei neuer IP wird per UUID neu gesucht.
  Achtung: mit aktivem VPN (Tailscale) ist das VPN die „aktive“ Verbindung – für Heimnetz-Adressen immer WLAN/
  Ethernet nehmen. `tvctl.sh lg-pair <IP> [LG_IP]` (bei mehreren LGs LG_IP angeben).
- Code: `source/homescreen/src/com/ansub/tvhome/LgTv.java` (Suche per SSDP, eigener WebSocket-Client über
  TLS auf Port 3001, Zertifikat wird beim Koppeln gemerkt und danach geprüft) und in `MainActivity.java`
  `lgListener` (Filter), `showTvMenu`/`menu` (Menü am Zahnrad), `buildTiles` (Symbole, Punkte, Trennstriche).
- Regeln: eigener Eingang = der, dessen CEC-Name dem Gerätenamen entspricht (`settings get global device_name`);
  Soundbars (ARC/`audio`) weg; gezeigt wird, was der LG kennt oder was an ist. Oranger Punkt = LG meldet
  „verbunden“ und CEC nicht „Off“. Verschlechterungen (kein Konsolen-Typ mehr, anderer Name, verschwunden)
  erst nach zwei Meldungen in Folge – direkt nach dem Umschalten meldet der LG kurz Unvollständiges.
- Symbole je Geräteart (`LgTv.kind`, aus `serviceType`/Icon-Name des LG): game → Controller, Set-Top/Streaming
  → Box mit Play, Blu-ray/DVD → Scheibe, PC → Laptop, sonst HDMI-Stecker (alle in `LineIcon.java`).
  Bestätigt sind nur „game“ und „audio“; die übrigen Schlüsselwörter sind geraten.
- Bekannt: Nach dem Wechsel in den Ruhemodus meldet der LG ein Gerät noch Minuten als „an“; dann weckt
  Umschalten es nicht. Komplett aus erkennt er nach ~10 s. Mehrere gleichzeitige Verbindungen zum LG
  (z. B. ein Test vom Mac und der Startbildschirm) lehnt der LG gelegentlich ab – kurz warten.
- Logs: `adb logcat -d -s tvhome-lg` (Kopplung, „switching TV to …“, Antwort des LG).
- Testen ohne Fernbedienung: Menü am Zahnrad mit `adb shell input keyevent KEYCODE_MENU` (Fokus aufs Zahnrad
  vorher mit den Pfeiltasten). Halten von OK lässt sich per adb nicht zuverlässig nachstellen.

## Notfall: TV startet nicht mehr richtig

Meist kann man sich in den ersten Sekunden nach dem Start verbinden:
```
adb connect <IP>:5555 && adb wait-for-device
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell pm enable com.google.android.apps.tv.launcherx
for p in $(adb shell pm list packages -d | tr -d '\r' | sed 's/package://'); do adb shell pm enable "$p"; done
adb reboot
```
Danach `sh tvctl.sh undo <IP>`.
