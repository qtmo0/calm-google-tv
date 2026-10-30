#!/bin/sh
# Einzelne Schritte für die Google-TV-Einrichtung, ohne Rückfragen im Terminal – gedacht für
# Claude Code (fragt vorher im Chat) und für setup.sh. Jede Änderung wird in tvs/<TV>/changes.md
# mit Rückgängig-Befehl protokolliert. Nur adb nötig.
#
#   sh tvctl.sh <befehl> <TV_IP> [argumente]
#
# Nur lesen:
#   connect     <IP>                      verbinden (startet adb neu, wenn es hängt)
#   info        <IP>                      Gerät, Speicher, Home, ausgeschaltete Apps, Einstellungen
#   inventory   <IP>                      alle Apps mit Einschätzung (never/keep/safe/review)
#   keys        <IP>                      prüft, ob die Fernbedienung YouTube/Netflix-Tasten hat
# Ändern (vorher immer fragen!):
#   backup      <IP>                      Ausgangszustand sichern (einmal pro TV, vor allem anderen)
#   disable     <IP> <paket>...           Apps ausschalten (Schutzliste wird geprüft)
#   enable      <IP> <paket>...           Apps wieder einschalten
#   home        <IP> "<apps>" [--launcher] [--setup]
#                                         Startbildschirm installieren. --launcher: Google-TV-Start-
#                                         bildschirm darf aus. --setup: Einrichtungs-App darf aus, falls
#                                         sie Home übernimmt (nur nach ausdrücklicher Zustimmung!)
#   screensaver <IP>                      Glühwürmchen installieren und setzen
#   times       <IP> [schoner_min] [ruhe_min]   Standard 3 und 30
#   animations  <IP> off|on
#   remote-keys <IP>                      YouTube->YouTube, Netflix->Wholphin, Stern->Zattoo-Live (Bedienungshilfe)
#   lg-pair     <IP> [LG_IP]              Startbildschirm mit einem LG-Fernseher (webOS) koppeln: HDMI-Geräte
#                                         in der Leiste, Umschalten per OK. Der LG fragt einmal "Zulassen?".
#                                         Bei mehreren LGs im Netz die IP des richtigen LG mitgeben.
#   reboot      <IP>                      30 s warten, neu starten, Start prüfen
#   undo        <IP>                      alles laut Sicherung zurück
set -e
cd "$(dirname "$0")"

CMD="$1"; TARGET="$2"
[ -n "$CMD" ] && [ -n "$TARGET" ] || { sed -n '2,30p' "$0"; exit 1; }
shift 2
command -v adb >/dev/null 2>&1 || { echo "adb fehlt. Mac: brew install android-platform-tools" >&2; exit 1; }
case "$TARGET" in *:*) TV=$TARGET ;; *) TV=$TARGET:5555 ;; esac

# Was nie ausgeschaltet werden darf (Startfähigkeit). Die Einrichtungs-App nur über "home --setup".
PROTECTED="com.google.android.gms com.google.android.gsf com.android.vending com.android.settings
  com.android.tv.settings com.android.systemui com.google.android.tungsten.setupwraith
  com.android.providers.tv com.android.providers.settings com.google.android.inputmethod.latin
  com.google.android.bluetooth com.android.bluetooth com.google.android.permissioncontroller
  com.android.shell com.google.android.packageinstaller android"

tv() { adb -s "$TV" shell "$@" | tr -d '\r'; }
mem() { tv cat /proc/meminfo | awk '/MemAvailable/ {printf "%d MB", $2/1024}'; }
home_now() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }
need_backup() { [ -f "$BACKUP" ] || { echo "Erst sichern: sh tvctl.sh backup $TARGET" >&2; exit 1; }; }
log() {  # log "<was>" "<rückgängig>"
  mkdir -p "$DIR"
  [ -f "$LOG" ] || printf '# Änderungen an %s\n\n| Zeit | Was | Rückgängig |\n|---|---|---|\n' "$TARGET" > "$LOG"
  printf '| %s | %s | `%s` |\n' "$(date '+%Y-%m-%d %H:%M')" "$1" "$2" >> "$LOG"
}

connect() {
  for i in 1 2 3; do
    adb connect "$TV" >/dev/null 2>&1 || true
    case "$(adb -s "$TV" get-state 2>&1)" in
      device) return 0 ;;
      *unauthorized*) echo "UNAUTHORIZED: Am TV \"USB-Debugging zulassen?\" -> Immer zulassen -> Zulassen, dann erneut versuchen." >&2; exit 2 ;;
      *) adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1; sleep 2 ;;
    esac
  done
  echo "Keine Verbindung zu $TV. TV an? Gleiches Netz? USB-Debugging an?" >&2; exit 1
}

connect

# Pro TV ein Ordner, benannt nach Modell und Seriennummer – nicht nach der IP, die sich ändern kann
# (z. B. LAN statt WLAN, anderer Router). Ein alter Ordner mit IP-Namen wird einmalig umbenannt.
SERIAL=$(tv getprop ro.serialno); [ -n "$SERIAL" ] || SERIAL=$(tv getprop ro.boot.serialno)
[ -n "$SERIAL" ] || SERIAL=$(tv settings get secure android_id)
MODEL=$(tv getprop ro.product.model | tr 'A-Z' 'a-z' | tr -cs 'a-z0-9' '-' | sed 's/^-//; s/-$//')
DIR="tvs/${MODEL}_$SERIAL"
OLD_DIR="tvs/$(echo "${TARGET%:*}" | tr '.' '-')"
if [ ! -d "$DIR" ] && [ -f "$OLD_DIR/backup.txt" ]; then
  mv "$OLD_DIR" "$DIR" && echo "Ordner umbenannt: $OLD_DIR -> $DIR"
fi
BACKUP="$DIR/backup.txt"; LOG="$DIR/changes.md"

case "$CMD" in

connect) echo "Verbunden: $(tv getprop ro.product.model) (Android $(tv getprop ro.build.version.release)) unter $TV" ;;

info)
  echo "Gerät:      $(tv getprop ro.product.model), Android $(tv getprop ro.build.version.release), Seriennr. $SERIAL"
  echo "Ordner:     $DIR"
  echo "Speicher:   $(mem) frei von $(tv cat /proc/meminfo | awk '/MemTotal/ {printf "%d MB", $2/1024}')"
  echo "Home:       $(home_now)"
  echo "Wachzustand: $(tv dumpsys power | sed -n 's/.*mWakefulness=//p' | head -1)"
  echo "Aus:        $(tv pm list packages -d | sed 's/package://' | tr '\n' ' ')"
  echo "Schoner:    $(tv settings get secure screensaver_components) (an=$(tv settings get secure screensaver_enabled))"
  echo "Zeiten:     Schoner $(tv settings get system screen_off_timeout) ms, Ruhe $(tv settings get secure sleep_timeout) ms"
  echo "Animation:  $(tv settings get global window_animation_scale)"
  echo "Bed.hilfe:  $(tv settings get secure enabled_accessibility_services)"
  echo "Eigene:     $(tv pm list packages | grep -E 'com.ansub.tvhome|io.github.qtmo0.calmtv' | sed 's/package://' | tr '\n' ' ')"
  [ -f "$BACKUP" ] && echo "Sicherung:  $BACKUP vorhanden" || echo "Sicherung:  noch keine"
  ;;

inventory) sh source/homescreen/candidates.sh "$TARGET" ;;

keys)
  kl=$(tv dumpsys input | sed -n 's/.*KeyLayoutFile: //p' | sort -u)
  found=0
  for f in $kl; do
    case "$f" in */Generic.kl) continue ;; esac   # Gamepad-Standardtabelle, sagt nichts über die Fernbedienung
    tv cat "$f" 2>/dev/null | grep -qE 'BUTTON_3|BUTTON_4' && { echo "$f:"; tv cat "$f" | grep -E 'BUTTON_3|BUTTON_4'; found=1; }
  done
  [ $found = 1 ] || echo "Keine BUTTON_3/BUTTON_4-Belegung gefunden – remote-keys passt für diese Fernbedienung vermutlich nicht."
  ;;

backup)
  if [ -f "$BACKUP" ]; then echo "Sicherung existiert schon: $BACKUP (wird nicht überschrieben)"; exit 0; fi
  mkdir -p "$DIR"
  {
    echo "# Ausgangszustand von $TARGET, $(date '+%Y-%m-%d %H:%M') – nicht löschen, undo braucht das"
    echo "model=$(tv getprop ro.product.model)"
    echo "home_before=$(home_now)"
    echo "disabled_before=$(tv pm list packages -d | sed 's/package://' | tr '\n' ' ')"
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do echo "global.$k=$(tv settings get global $k)"; done
    for k in screensaver_enabled screensaver_components sleep_timeout enabled_accessibility_services accessibility_enabled; do echo "secure.$k=$(tv settings get secure $k)"; done
    echo "system.screen_off_timeout=$(tv settings get system screen_off_timeout)"
    echo "mem_before=$(mem)"
  } > "$BACKUP"
  echo "Gesichert: $BACKUP"; cat "$BACKUP"
  ;;

disable)
  need_backup
  for p in "$@"; do
    case " $(echo $PROTECTED) " in *" $p "*) echo "GESCHÜTZT, bleibt an: $p" >&2; continue ;; esac
    tv pm list packages "$p" | grep -qx "package:$p" || { echo "nicht installiert: $p"; continue; }
    tv pm disable-user --user 0 "$p" >/dev/null
    tv pm list packages -d "$p" | grep -qx "package:$p" && { echo "aus: $p"; log "ausgeschaltet: \`$p\`" "adb shell pm enable $p"; } || echo "ließ sich nicht ausschalten: $p" >&2
  done
  echo "Android speichert das erst nach ein paar Sekunden – nicht sofort neu starten."
  ;;

enable)
  for p in "$@"; do tv pm enable "$p" >/dev/null && echo "an: $p" && log "eingeschaltet: \`$p\`" "adb shell pm disable-user --user 0 $p"; done
  ;;

home)
  need_backup
  APPS="$1"; shift || true
  [ -n "$APPS" ] || { echo "App-Liste fehlt, z. B. \"com.google.android.youtube.tv,com.github.damontecres.wholphin\"" >&2; exit 1; }
  YES=; YES_SETUP=
  for a in "$@"; do case "$a" in --launcher) YES=1 ;; --setup) YES_SETUP=1 ;; esac; done
  mkdir -p source/homescreen/out
  cp apk/homescreen.apk source/homescreen/out/tvhome.apk
  before=$(tv pm list packages -d | sed 's/package://' | sort)
  YES=$YES YES_SETUP=$YES_SETUP sh source/homescreen/install.sh "$TARGET" "$APPS" </dev/null || true
  rm -f source/homescreen/out/tvhome.apk
  after=$(tv pm list packages -d | sed 's/package://' | sort)
  log "Startbildschirm installiert (\`com.ansub.tvhome\`), Apps: $APPS" "adb uninstall com.ansub.tvhome"
  for p in $(printf '%s\n' "$after" | grep -vxF "$before" || true); do log "ausgeschaltet von install.sh: \`$p\`" "adb shell pm enable $p"; done
  echo "Home öffnet jetzt: $(home_now)"
  case "$(home_now)" in
    com.ansub.tvhome/*) echo "OK" ;;
    *setupwraith*) echo "HINWEIS: Die Einrichtungs-App übernimmt Home. Nur mit ausdrücklicher Zustimmung des Nutzers erneut mit --launcher --setup ausführen." ;;
    *) echo "HINWEIS: Der TV-eigene Startbildschirm gewinnt noch. Mit Zustimmung erneut mit --launcher ausführen." ;;
  esac
  ;;

screensaver)
  need_backup
  adb -s "$TV" install -r apk/screensaver.apk >/dev/null
  tv settings put secure screensaver_enabled 1
  tv settings put secure screensaver_components io.github.qtmo0.calmtv.screensaver/.OledDream
  log "Glühwürmchen-Bildschirmschoner installiert und gesetzt" "adb shell settings put secure screensaver_components $(sed -n 's/^secure.screensaver_components=//p' "$BACKUP") && adb uninstall io.github.qtmo0.calmtv.screensaver"
  echo "Bildschirmschoner gesetzt: $(tv settings get secure screensaver_components)"
  ;;

times)
  need_backup
  s=${1:-3}; r=${2:-30}
  tv settings put system screen_off_timeout $((s * 60000))
  tv settings put secure sleep_timeout $((r * 60000))
  log "Bildschirmschoner nach $s Min., Ruhezustand nach $r Min." "adb shell settings put system screen_off_timeout $(sed -n 's/^system.screen_off_timeout=//p' "$BACKUP") && adb shell settings put secure sleep_timeout $(sed -n 's/^secure.sleep_timeout=//p' "$BACKUP")"
  echo "Schoner $(tv settings get system screen_off_timeout) ms, Ruhe $(tv settings get secure sleep_timeout) ms"
  ;;

animations)
  need_backup
  v=0; [ "$1" = on ] && v=1
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do tv settings put global $k $v; done
  log "Animationen $([ $v = 0 ] && echo aus || echo an)" "adb shell settings put global window_animation_scale 1 (ebenso transition_animation_scale, animator_duration_scale)"
  echo "Animationen: $v"
  ;;

remote-keys)
  need_backup
  adb -s "$TV" install -r apk/remote-keys.apk >/dev/null
  old=$(tv settings get secure enabled_accessibility_services)
  case "$old" in null|"") new=io.github.qtmo0.calmtv.remotekeys/.TastenService ;; *io.github.qtmo0.calmtv.remotekeys*) new=$old ;; *) new="$old:io.github.qtmo0.calmtv.remotekeys/.TastenService" ;; esac
  tv settings put secure enabled_accessibility_services "$new"
  tv settings put secure accessibility_enabled 1
  log "Fernbedienungs-Tasten (YouTube->YouTube, Netflix->Wholphin, Stern->Zattoo-Live) als Bedienungshilfe" "adb shell settings put secure enabled_accessibility_services $old && adb uninstall io.github.qtmo0.calmtv.remotekeys"
  echo "Aktiv: $(tv dumpsys accessibility | grep -c 'Fernbedienungs-Tasten') Dienst(e). Mit der echten Fernbedienung testen – simulierte Tasten (adb input) erreichen den Dienst nicht."
  ;;

lg-pair)
  need_backup
  tv pm list packages com.ansub.tvhome | grep -q com.ansub.tvhome || { echo "Erst den Startbildschirm installieren (home)." >&2; exit 1; }
  adb -s "$TV" logcat -c
  tv input keyevent KEYCODE_HOME
  sleep 6   # der Startbildschirm sucht den LG im Netz
  if [ -n "$1" ]; then tv am broadcast -a com.ansub.tvhome.LG_PAIR --es host "$1" >/dev/null
  else tv am broadcast -a com.ansub.tvhome.LG_PAIR >/dev/null; fi
  echo "Am LG-Fernseher erscheint gleich \"Calm Google TV möchte sich verbinden\" – mit der LG-Fernbedienung Zulassen drücken (90 s Zeit)."
  i=0; result=
  while [ $i -lt 50 ]; do
    out=$(adb -s "$TV" logcat -d -s tvhome-lg | tr -d '\r')
    case "$out" in
      *"pairing: registered"*) result=ok; break ;;
      *"several LG TVs"*) echo "Mehrere LG-Fernseher im Netz – bitte die IP des richtigen mitgeben: sh tvctl.sh lg-pair $TARGET <LG_IP>" >&2; exit 1 ;;
      *"pairing refused"*|*"no LG TV found"*) result=fail; break ;;
    esac
    i=$((i + 1)); sleep 2
  done
  if [ "$result" = ok ]; then
    echo "Gekoppelt. HDMI-Geräte erscheinen in der Leiste oben rechts."
    log "Startbildschirm mit LG-Fernseher gekoppelt (HDMI-Geräte in der Leiste)" "Am TV: OK auf dem Zahnrad halten -> Trennen (und im LG-Menü den Eintrag \"Calm Google TV\" löschen)"
  else
    echo "Nicht gekoppelt. Log:"; adb -s "$TV" logcat -d -s tvhome-lg | tail -5
    echo "Kein LG gefunden? TV und Streamer im selben Netz? Am LG \"LG Connect Apps\" bzw. \"Mobile TV On\" einschalten." >&2
    exit 1
  fi
  ;;

reboot)
  echo "Warte 30 s, damit Android alles speichert…"; sleep 30
  adb -s "$TV" reboot
  sleep 20
  i=0; until adb connect "$TV" 2>&1 | grep -q connected && [ "$(tv getprop sys.boot_completed 2>/dev/null)" = 1 ]; do i=$((i + 1)); [ $i -gt 60 ] && { echo "TV kommt nicht zurück – siehe CLAUDE.md, Notfall." >&2; exit 1; }; sleep 5; done
  sleep 45
  echo "Gestartet. Home: $(home_now). Freier Speicher: $(mem)"
  [ -f "$BACKUP" ] && echo "Vorher: $(sed -n 's/^mem_before=//p' "$BACKUP")"
  log "Neustart, startet normal" "-"
  ;;

undo)
  need_backup
  val() { sed -n "s/^$1=//p" "$BACKUP"; }
  put() { v=$(val "$1.$2"); if [ -z "$v" ] || [ "$v" = null ]; then tv settings delete "$1" "$2" >/dev/null; else tv settings put "$1" "$2" "$v"; fi; }
  before=" $(val disabled_before) "
  # Zuerst Einrichtungs-App und Google-TV-Startbildschirm, damit Home nie ins Leere zeigt.
  for p in com.google.android.tungsten.setupwraith com.google.android.apps.tv.launcherx $(tv pm list packages -d | sed 's/package://'); do
    case "$before" in *" $p "*) continue ;; esac
    tv pm enable "$p" >/dev/null 2>&1 && echo "an: $p" || true
  done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do put global $k; done
  for k in screensaver_enabled screensaver_components sleep_timeout enabled_accessibility_services accessibility_enabled; do put secure $k; done
  put system screen_off_timeout
  for p in io.github.qtmo0.calmtv.remotekeys io.github.qtmo0.calmtv.screensaver com.ansub.tvhome; do adb -s "$TV" uninstall "$p" >/dev/null 2>&1 && echo "entfernt: $p" || true; done
  tv rm -f /data/local/tmp/tvhome-disabled.txt
  log "ALLES rückgängig gemacht (undo)" "sh tvctl.sh … erneut einrichten"
  echo "Fertig. Jetzt neu starten: sh tvctl.sh reboot $TARGET"
  ;;

*) echo "Unbekannter Befehl: $CMD" >&2; sed -n '2,30p' "$0"; exit 1 ;;
esac
