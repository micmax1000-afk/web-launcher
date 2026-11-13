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
why() { why; echo "--- perché il processo è finito ---"; adb logcat -d | grep -iE "Fatal signal|ANR in|lowmemorykiller|Killing .*weblauncher|weblauncher.*(died|crash)|Force stopping|am_kill|am_proc_died" | tail -25; adb shell dumpsys meminfo | head -12; }
pdfprobe() {   # diagnosi: la lettura del testo dei PDF funziona in questo momento? (non fa fallire la prova)
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity --es widget_action pdftext >/dev/null
  for i in $(seq 1 24); do sleep 2; adb logcat -d -s WL_SELFTEST:* | grep -q 'PDF_TEXT=' && break; done
  echo "PROVA PDF ($1): $(adb logcat -d -s WL_SELFTEST:* | grep -o 'PDF_TEXT=.\{0,40\}' | tail -1)"
}

# le schermate si fanno con il tema chiaro, come lo usa chi guarda
adb shell am start -W -n $PKG/.MainActivity --ez wl_noprompts true >/dev/null
sleep 4
adb shell am start -W -n $PKG/.MainActivity --es wl_theme_check light >/dev/null
sleep 4
adb shell am start -W -n $PKG/.MainActivity >/dev/null
shot 01-home 6
pdfprobe "dopo la home"
# Strumenti (scorrendo) e ogni pagina delle Impostazioni
adb shell am start -W -n $PKG/.MainActivity --es widget_action tools >/dev/null
shot 02a-strumenti 3
adb shell input swipe 540 1800 540 500 300; shot 02b-strumenti 2
adb shell input swipe 540 1800 540 500 300; shot 02c-strumenti 2
adb shell am start -W -n $PKG/.MainActivity --es widget_action settings >/dev/null
shot 03a-impostazioni 3
n=1
for key in ASPETTO HOME BROWSER PRIVACY NOTIZIE BACKUP AI AGGIORNAMENTI; do
  adb shell am start -W -n $PKG/.MainActivity --es widget_action settings_$key >/dev/null
  shot "03b-imp-$n-$key" 3
  alive || { echo "::error::L'app si è chiusa aprendo le impostazioni $key"; why; exit 1; }
  n=$((n+1))
done
adb shell am start -W -n $PKG/.MainActivity --es widget_action settings >/dev/null
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
for page in instruments tabata altimeter ruler metal split waste finance stocks birthdays wellness hc food water calc workout plans history sleep meds visits emergency report hr body symptoms music nowplaying recents privacytools backuptools passwords recipes todo quickadd appcheck foryou player sources bledevices queue podcasttools radioalarm portfolio audiosettings radiotab ytmusic playerdemo lyricsdemo; do
  adb shell am start -W -n $PKG/.MainActivity --es widget_action $page >/dev/null
  shot "$n-$page" 3
  alive || { echo "::error::L'app si è chiusa aprendo $page"; why; exit 1; }
  adb shell input keyevent KEYCODE_BACK
  n=$((n+1))
done

# voce Lira (Piper) nel WebView: scarica la voce piccola e prova a parlare (informativa, non blocca)
adb shell am start -W -n $PKG/.MainActivity --es widget_action piperdemo >/dev/null
for i in $(seq 1 60); do sleep 3; adb logcat -d -s WL_SELFTEST:* | grep -qE 'PIPER_(OK|ERR)' && break; done
echo "PROVA VOCE LIRA: $(adb logcat -d -s WL_SELFTEST:* | grep -oE 'PIPER_(OK|ERR).*' | tail -1)"
echo "PROVA VOCE LIRA (avanzamento): $(adb logcat -d -s WL_SELFTEST:* | grep -oE 'PIPER_PROGRESS.*' | tail -1)"
adb logcat -d -s WL_SELFTEST:* | grep -E 'PIPER_PROGRESS' | sed 's/^.*PIPER_PROGRESS/  avanzamento:/' | head -12
adb logcat -d -s WL_SELFTEST:* | grep PIPER_START | sed 's/^/  /'
adb logcat -d -s WL_AI_JS:* | tail -20 | sed 's/^/  console: /'

# PDF dentro l'app (pagina con il visualizzatore, senza passare dal browser)
adb shell am start -W -n $PKG/.MainActivity --es widget_action pdfpage >/dev/null
shot 19-pdf-nell-app 7
alive || { echo "::error::L'app si e' chiusa aprendo il PDF"; why; exit 1; }
adb shell input keyevent KEYCODE_BACK

# estrazione del testo da un PDF (e' quello che l'AI legge per il calendario dei rifiuti)
adb shell am start -W -n $PKG/.MainActivity --es widget_action pdftext >/dev/null
for i in $(seq 1 24); do sleep 2; adb logcat -d -s WL_SELFTEST:* | grep -q 'PDF_TEXT=' && break; done
PT=$(adb logcat -d -s WL_SELFTEST:* | grep -o 'PDF_TEXT=.*' | tail -1)
[ -n "$PT" ] || { echo "Registro PDF:"; adb logcat -d | grep -E "WL_PDF|chromium|Console|extract" | tail -40; }
echo "Testo del PDF: $PT"
case "$PT" in
  *Umido*Plastica*Carta*Vetro*) ;;
  *) echo "::error::L'estrazione del testo dal PDF non funziona ($PT)"; adb logcat -d -s WL_PDF:* | tail -5; exit 1;;
esac

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
# Mia AI con sistema scuro e app chiara: deve essere chiara (non scura)
adb shell am start -W -n $PKG/.MainActivity --ez wl_mia_check true >/dev/null
sleep 12
shot 08-mia-ai-tema-chiaro 2
MIA=$(adb logcat -d -s WL_SELFTEST:* | grep -o 'MIA_CHECK=.*' | tail -1)
echo "Mia AI: $MIA"
case "$MIA" in
  *'"light|rgb('*'|function|function"'*) ;;
  *) echo "::error::Mia AI non segue il tema chiaro dell'app oppure mancano le funzioni vocali ($MIA)"; exit 1;;
esac
adb shell am start -W -n $PKG/.MainActivity --es wl_theme_check dark >/dev/null
adb shell cmd uimode night no

# finestra dell'AI: il testo lungo deve scorrere e la finestra NON deve chiudersi (prima si chiudeva trascinando)
adb shell am start -W -n $PKG/.MainActivity --ez wl_ai_sheet true >/dev/null
sleep 3
shot 09a-finestra-ai 1
adb shell input swipe 540 1700 540 800 250; adb shell input swipe 540 1700 540 800 250
adb shell input swipe 540 900 540 2100 150; adb shell input swipe 540 900 540 2100 150; adb shell input swipe 540 900 540 2100 150
sleep 12
shot 09b-finestra-ai 1
AIS=$(adb logcat -d -s WL_SELFTEST:* | grep -o 'AI_SHEET .*' | tail -1)
echo "Finestra AI: $AIS"
case "$AIS" in
  *'shown=true'*) ;;
  *) echo "::error::La finestra dell'AI si e' chiusa mentre si scorreva ($AIS)"; exit 1;;
esac
MAXY=$(echo "$AIS" | sed -n 's/.*maxScrollY=\([0-9]*\).*/\1/p')
if [ -z "$MAXY" ] || [ "$MAXY" -lt 100 ]; then
  # telefono virtuale lento: i primi trascinamenti possono arrivare prima che la finestra sia pronta. Riprovo una volta.
  echo "Finestra AI: riprovo lo scorrimento"
  adb shell input swipe 540 1700 540 700 300; sleep 1; adb shell input swipe 540 1700 540 700 300
  sleep 16
  AIS=$(adb logcat -d -s WL_SELFTEST:* | grep -o 'AI_SHEET .*' | tail -1)
  echo "Finestra AI (seconda prova): $AIS"
  case "$AIS" in *'shown=true'*) ;; *) echo "::error::La finestra dell'AI si e' chiusa mentre si scorreva ($AIS)"; exit 1;; esac
  MAXY=$(echo "$AIS" | sed -n 's/.*maxScrollY=\([0-9]*\).*/\1/p')
fi
if [ -z "$MAXY" ] || [ "$MAXY" -lt 100 ]; then echo "::error::Il testo della finestra AI non scorre ($AIS)"; exit 1; fi
adb shell input keyevent KEYCODE_BACK

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
