#!/bin/sh
# Macht die Einrichtung rückgängig (laut tvs/<TV>/backup.txt) und startet den TV neu.
#   sh undo.sh <TV_IP>
set -e
cd "$(dirname "$0")"
[ -n "$1" ] || { echo "Aufruf: sh undo.sh <TV_IP>" >&2; exit 1; }
sh tvctl.sh undo "$1"
sh tvctl.sh reboot "$1"
