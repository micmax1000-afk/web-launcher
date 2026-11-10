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

# pagine degli strumenti: si devono aprire senza crash
n=10
for page in instruments tabata altimeter ruler metal split waste finance stocks; do
  adb shell am start -W -n $PKG/.MainActivity --es widget_action $page >/dev/null
  shot "$n-$page" 3
  alive || { echo "::error::L'app si è chiusa aprendo $page"; adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -60; exit 1; }
  adb shell input keyevent KEYCODE_BACK
  n=$((n+1))
done

# riconoscitori di testo sul motore regex di Android (diverso da quello dei test JVM)
adb shell am start -W -n $PKG/.MainActivity --ez wl_selftest true >/dev/null
sleep 5
adb logcat -d -s WL_SELFTEST:* > "$OUT/selftest.txt"
cat "$OUT/selftest.txt"
if ! grep -q "SELFTEST OK" "$OUT/selftest.txt"; then
  echo "::error::Autotest fallito sul telefono virtuale"; adb logcat -d | grep -A15 "WL_SELFTEST" | head -60; exit 1
fi

# tema: sistema scuro + app chiara; dopo aver aperto una pagina web le finestre devono restare chiare
adb shell cmd uimode night yes
adb shell am start -W -n $PKG/.MainActivity --es wl_theme_check light >/dev/null
sleep 4
adb shell am start -W -a android.intent.action.VIEW -d "https://example.com" -n $PKG/.MainActivity >/dev/null
sleep 6
adb shell am start -W -n $PKG/.MainActivity --ez wl_dialog_check true >/dev/null
shot 07-tema-chiaro-finestra 4
python3 - "$OUT/07-tema-chiaro-finestra.png" <<'PY' || exit 1
import sys, zlib, struct
def png_pixels(path):
    data = open(path, 'rb').read(); pos = 8; w = h = 0; idat = b''
    while pos < len(data):
        ln, = struct.unpack('>I', data[pos:pos+4]); typ = data[pos+4:pos+8]; chunk = data[pos+8:pos+8+ln]; pos += 12 + ln
        if typ == b'IHDR': w, h, depth, ctype = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT': idat += chunk
    raw = zlib.decompress(idat); bpp = 4 if ctype == 6 else 3; stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i+1:i+1+stride]); i += 1 + stride
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, bpp, rows
w, h, bpp, rows = png_pixels(sys.argv[1])
vals = []
for y in range(int(h*0.40), int(h*0.60), 6):
    for x in range(int(w*0.25), int(w*0.75), 6):
        r, g, b = rows[y][x*bpp:x*bpp+3]; vals.append(0.299*r + 0.587*g + 0.114*b)
lum = sum(vals) / len(vals)
print("luminosità media della finestra: %.0f" % lum)
if lum < 150:
    print("::error::Con il tema chiaro la finestra è scura (luminosità %.0f)" % lum); sys.exit(1)
PY
# i suggerimenti nei campi devono leggersi sul fondo chiaro (non quasi bianchi, non trasparenti)
HINT=$(adb logcat -d -s WL_SELFTEST:* | grep -o "HINT_LUM=[0-9]* HINT_ALPHA=[0-9]* TEXT_LUM=[0-9]*" | tail -1)
echo "campo di prova: $HINT"
HL=$(echo "$HINT" | sed -n 's/.*HINT_LUM=\([0-9]*\).*/\1/p'); HA=$(echo "$HINT" | sed -n 's/.*HINT_ALPHA=\([0-9]*\).*/\1/p'); TL=$(echo "$HINT" | sed -n 's/.*TEXT_LUM=\([0-9]*\).*/\1/p')
if [ -z "$HL" ] || [ "$HL" -gt 170 ] || [ "$HA" -lt 150 ] || [ "$TL" -gt 120 ]; then
  echo "::error::Con il tema chiaro i suggerimenti o il testo dei campi non si leggono ($HINT)"; exit 1
fi
adb shell input keyevent KEYCODE_BACK
adb shell am start -W -n $PKG/.MainActivity --es wl_theme_check dark >/dev/null
adb shell cmd uimode night no

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
