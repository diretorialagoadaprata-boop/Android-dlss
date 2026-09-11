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
import re, subprocess, time, xml.etree.ElementTree as ET

def dump():
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/window.xml'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    return subprocess.check_output(['adb','shell','cat','/sdcard/window.xml'], text=True, errors='ignore')

def center(bounds):
    m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds or '')
    if not m:
        return None
    x1,y1,x2,y2=map(int,m.groups())
    return (x1+x2)//2,(y1+y2)//2

def find_node(texts):
    xml=dump()
    try:
        root=ET.fromstring(xml)
    except ET.ParseError:
        return None, xml
    wanted={t.casefold():t for t in texts}
    for node in root.iter('node'):
        for key in ('text','content-desc'):
            value=(node.attrib.get(key) or '').strip()
            if value.casefold() in wanted:
                pt=center(node.attrib.get('bounds'))
                if pt:
                    return (wanted[value.casefold()], pt), xml
    return None, xml

def tap_text(texts, timeout=15, required=True):
    deadline=time.time()+timeout
    last_xml=''
    while time.time()<deadline:
        found,last_xml=find_node(texts)
        if found:
            label,(x,y)=found
            subprocess.run(['adb','shell','input','tap',str(x),str(y)], check=True)
            return label
        time.sleep(0.4)
    if required:
        print('UI XML at timeout:\n', last_xml or dump())
        raise SystemExit('Could not find any UI text: '+repr(texts))
    return None

print('Tapped:', tap_text(['INICIAR PROCESSAMENTO']))
time.sleep(1)

current = tap_text(['A single app','Um único app','Um app','Uma aplicação'], timeout=8, required=False)
if current:
    print('Opened share mode:', current)
    time.sleep(0.8)
    print('Selected:', tap_text(['Entire screen','Full screen','Tela inteira','Ecrã inteiro'], timeout=10))
    time.sleep(0.8)
else:
    full = tap_text(['Entire screen','Full screen','Tela inteira','Ecrã inteiro'], timeout=5, required=False)
    if full:
        print('Selected:', full)
        time.sleep(0.8)

print('Tapped:', tap_text(['Start now','Share screen','Start','Compartilhar tela','Iniciar agora','Começar agora'], timeout=15))
PY

# Give onActivityResult enough time to start the foreground service and create the overlay.
sleep 3

# Generate visible motion so MediaProjection delivers multiple changing frames.
for i in $(seq 1 16); do
  adb shell input swipe 900 1200 180 1200 160 >/dev/null 2>&1 || true
  adb shell input swipe 180 1200 900 1200 160 >/dev/null 2>&1 || true
  sleep 0.20
done

sleep 6
{
  echo '=== NeuralFrame log ==='
  adb logcat -d -s NeuralFrame:I '*:S' || true
  echo '=== Process ==='
  adb shell pidof "$PKG" || true
  echo '=== Service ==='
  adb shell dumpsys activity services "$PKG" | head -n 160 || true
} | tee neuralframe-render.log

grep -E 'render_fps=[0-9]+\.[0-9]+.*history=true' neuralframe-render.log
adb shell pidof "$PKG"
adb shell dumpsys activity services "$PKG" | grep -q 'ProjectionService'
