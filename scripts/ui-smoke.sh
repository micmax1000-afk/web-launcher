#!/bin/bash
# Prova dell'app su un telefono virtuale: installa, apre le schermate principali,
# fa 600 tocchi casuali e fallisce se l'app va in crash. Salva le schermate in ui-shots/.
set -u
APK=${1:-app-debug.apk}
PKG=com.weblauncher.app
OUT=ui-shots
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APK" || exit 1
adb logcat -c

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "schermata: $1"; }
alive() { adb shell pidof $PKG >/dev/null; }

adb shell am start -W -n $PKG/.MainActivity >/dev/null
shot 01-home 6
adb shell am start -W -n $PKG/.MainActivity --es widget_action news >/dev/null
shot 02-notizie 8
adb shell am start -W -n $PKG/.MainActivity --es widget_action reminders >/dev/null
shot 03-promemoria
adb shell input keyevent KEYCODE_BACK
adb shell am start -W -n $PKG/.MainActivity --es widget_action new_note >/dev/null
shot 04-nota
adb shell input keyevent KEYCODE_BACK
adb shell am start -W -a android.intent.action.VIEW -d "https://example.com" -n $PKG/.MainActivity >/dev/null
shot 05-browser 8

# tocchi casuali solo dentro l'app (niente barra di sistema / altre app)
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 150 -s 4242 --ignore-security-exceptions 600 > "$OUT/monkey.txt" 2>&1 || true
shot 06-dopo-tocchi 3

adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "::error::L'app è andata in crash: vedi logcat.txt"
  grep -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
if grep -q "// CRASH: $PKG" "$OUT/monkey.txt"; then
  echo "::error::Crash durante i tocchi casuali"; grep -A20 "// CRASH" "$OUT/monkey.txt" | head -40; exit 1
fi
echo "Nessun crash"
