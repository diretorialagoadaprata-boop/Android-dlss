#!/usr/bin/env bash
set -euo pipefail

PKG="com.lm.neuralframe"
ACTIVITY="$PKG/.MainActivity"
APK="work/project/app/build/outputs/apk/debug/app-debug.apk"

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 2

python3 - <<'PY'
import re, subprocess, time, sys

def dump():
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/window.xml'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    return subprocess.check_output(['adb','shell','cat','/sdcard/window.xml'], text=True, errors='ignore')

def tap_text(texts, timeout=15):
    deadline=time.time()+timeout
    while time.time()<deadline:
        xml=dump()
        for text in texts:
            # Exact text first, then case-insensitive contains.
            patterns=[
                rf'text="{re.escape(text)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                rf'content-desc="{re.escape(text)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            ]
            for pat in patterns:
                m=re.search(pat, xml, re.I)
                if m:
                    x=(int(m.group(1))+int(m.group(3)))//2
                    y=(int(m.group(2))+int(m.group(4)))//2
                    subprocess.run(['adb','shell','input','tap',str(x),str(y)], check=True)
                    return text
        time.sleep(0.5)
    print('UI XML at timeout:\n', dump())
    raise SystemExit('Could not find any UI text: '+repr(texts))

print('Tapped:', tap_text(['INICIAR PROCESSAMENTO']))
time.sleep(1)

# Android 14/15 may first ask whether to share one app or the whole screen.
# For CI we deliberately choose the whole virtual display so no second app is required.
try:
    print('Tapped:', tap_text(['Entire screen','Full screen','Tela inteira','Ecrã inteiro'], timeout=5))
    time.sleep(0.5)
except SystemExit:
    pass

# Consent CTA varies across Android releases/locales.
print('Tapped:', tap_text(['Start now','Share screen','Start','Compartilhar tela','Iniciar agora','Começar agora'], timeout=15))
PY

# Generate visible motion after consent so MediaProjection continuously receives changed frames.
for i in $(seq 1 12); do
  adb shell input swipe 900 1200 180 1200 180 >/dev/null 2>&1 || true
  adb shell input swipe 180 1200 900 1200 180 >/dev/null 2>&1 || true
  sleep 0.25
done

# The renderer emits a statistics line every ~2 seconds only after SurfaceTexture frames arrive.
sleep 5
adb logcat -d -s NeuralFrame:I '*:S' | tee neuralframe-render.log

grep -E 'render_fps=[0-9]+\.[0-9]+.*history=true' neuralframe-render.log
adb shell pidof "$PKG"
adb shell dumpsys activity services "$PKG" | grep -q 'ProjectionService'
