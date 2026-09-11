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
import re, subprocess, time

def dump():
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/window.xml'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    return subprocess.check_output(['adb','shell','cat','/sdcard/window.xml'], text=True, errors='ignore')

def tap_text(texts, timeout=15, required=True):
    deadline=time.time()+timeout
    while time.time()<deadline:
        xml=dump()
        for text in texts:
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
        time.sleep(0.4)
    if required:
        print('UI XML at timeout:\n', dump())
        raise SystemExit('Could not find any UI text: '+repr(texts))
    return None

print('Tapped:', tap_text(['INICIAR PROCESSAMENTO']))
time.sleep(1)

# Android 14 QPR2+/15 defaults to sharing a single app. Open the spinner first,
# then deliberately select the entire virtual display for this automated CI test.
current = tap_text(['A single app','Um único app','Um app','Uma aplicação'], timeout=5, required=False)
if current:
    print('Opened share mode:', current)
    time.sleep(0.5)
    print('Selected:', tap_text(['Entire screen','Full screen','Tela inteira','Ecrã inteiro'], timeout=8))
    time.sleep(0.5)
else:
    # Some versions expose the full-screen option directly.
    full = tap_text(['Entire screen','Full screen','Tela inteira','Ecrã inteiro'], timeout=4, required=False)
    if full:
        print('Selected:', full)
        time.sleep(0.5)

print('Tapped:', tap_text(['Start now','Share screen','Start','Compartilhar tela','Iniciar agora','Começar agora'], timeout=15))
PY

# Generate visible motion after consent so MediaProjection continuously receives changed frames.
for i in $(seq 1 12); do
  adb shell input swipe 900 1200 180 1200 180 >/dev/null 2>&1 || true
  adb shell input swipe 180 1200 900 1200 180 >/dev/null 2>&1 || true
  sleep 0.25
done

sleep 5
adb logcat -d -s NeuralFrame:I '*:S' | tee neuralframe-render.log

grep -E 'render_fps=[0-9]+\.[0-9]+.*history=true' neuralframe-render.log
adb shell pidof "$PKG"
adb shell dumpsys activity services "$PKG" | grep -q 'ProjectionService'
