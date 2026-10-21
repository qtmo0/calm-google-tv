#!/bin/sh
# Geführte Einrichtung im Terminal: fragt vor jedem Schritt und ruft dafür tvctl.sh auf.
# (Claude Code nutzt tvctl.sh direkt und fragt im Chat – siehe CLAUDE.md.)
#
#   sh setup.sh <TV_IP> ["app1,app2,..."]
set -e
cd "$(dirname "$0")"

TARGET="$1"
APPS="${2:-com.github.damontecres.wholphin,com.google.android.youtube.tv,com.zattoo.player,de.swr.avp.ard.tv,com.zdf.android.mediathek,com.amazon.amazonvideo.livingroom}"
[ -n "$TARGET" ] || { echo "Aufruf: sh setup.sh <TV_IP> [\"app1,app2,...\"]" >&2; exit 1; }
T() { sh tvctl.sh "$@"; }
ask() { printf "\n%s [j/N] " "$1"; read a </dev/tty || a=n; case "$a" in j|J|ja|y|Y|yes) return 0 ;; *) return 1 ;; esac; }

# Google-Extras, die auf Google-TV-Geräten gefahrlos aus können (Werbung, Empfehlungen, Google-Schoner).
EXTRAS="com.google.android.backdrop com.google.android.videos com.google.android.tvrecommendations
  com.google.android.apps.education.cast2class com.google.android.apps.tv.dreamx"

T info "$TARGET"
T backup "$TARGET"

echo; echo "== 1. Werbung und Google-Extras ausschalten (nur ausschalten, nicht löschen)"
for p in $EXTRAS; do ask "  $p ausschalten?" && T disable "$TARGET" "$p"; done

if ask "== 2. Eigenen Startbildschirm installieren? Apps: $APPS"; then
  flags=
  ask "   Darf der Google-TV-Startbildschirm dafür ausgeschaltet werden (nötig, damit Home den neuen öffnet)?" && flags="--launcher"
  if [ -n "$flags" ] && ask "   Falls danach die Google-Einrichtungs-App Home übernimmt: darf sie auch aus? (Vor Werksreset wieder einschalten!)"; then
    flags="$flags --setup"
  fi
  T home "$TARGET" "$APPS" $flags
fi

ask "== 3. Glühwürmchen-Bildschirmschoner installieren und einschalten?" && T screensaver "$TARGET"
ask "== 4. Bildschirmschoner nach 3 Min., Ruhezustand nach 30 Min.?" && T times "$TARGET" 3 30
ask "== 5. Animationen ausschalten?" && T animations "$TARGET" off
if ask "== 6. YouTube-Taste -> YouTube, Netflix-Taste -> Wholphin?"; then T keys "$TARGET"; T remote-keys "$TARGET"; fi
ask "== 7. 30 s warten, neu starten und prüfen?" && T reboot "$TARGET"

echo; T info "$TARGET"
echo "Protokoll: tvs/*/changes.md  ·  Rückgängig: sh undo.sh $TARGET"
