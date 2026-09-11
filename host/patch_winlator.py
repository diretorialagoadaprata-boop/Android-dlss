from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

root = Path('winlator')

# 1) Install our Android host activities/service.
javadir = root / 'app/src/main/java/com/winlator'
javadir.mkdir(parents=True, exist_ok=True)
for name in ('DlssHostActivity.java', 'DlssLogActivity.java', 'AndroidFrameBridgeService.java'):
    src = Path('host') / name
    if not src.is_file():
        raise SystemExit(f'missing host source: {src}')
    (javadir / name).write_text(src.read_text())

# 2) Embed only our MIT/open-source frame presenter. Proprietary DLSS/ReShade files stay user supplied.
presenter = Path('bridge/AndroidFramePresenter.exe')
if not presenter.is_file() or presenter.stat().st_size < 4096:
    raise SystemExit('bridge/AndroidFramePresenter.exe was not built')
assets = root / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
shutil.copy2(presenter, assets / 'android-frame-presenter.exe')

# 3) Let RootFSInstaller be used by our standalone AppCompatActivity.
p = root / 'app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java'
s = p.read_text()
s = s.replace('import com.winlator.MainActivity;\n', '')
s = s.replace('public static void install(final MainActivity activity)', 'public static void install(final AppCompatActivity activity)')
s = s.replace('public static void installIfNeeded(final MainActivity activity)', 'public static void installIfNeeded(final AppCompatActivity activity)')
p.write_text(s)

# 4) Make the host the single launcher and keep Winlator's original MainActivity internal.
p = root / 'app/src/main/AndroidManifest.xml'
s = p.read_text()
old = '''        <activity android:name="com.winlator.MainActivity"
            android:theme="@style/AppThemeDark"
            android:exported="true"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>'''
new = '''        <activity android:name="com.winlator.DlssHostActivity"
            android:theme="@style/AppThemeDark"
            android:exported="true"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <activity android:name="com.winlator.DlssLogActivity"
            android:theme="@style/AppThemeDark"
            android:exported="false"/>

        <service android:name="com.winlator.AndroidFrameBridgeService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection"/>

        <activity android:name="com.winlator.MainActivity"
            android:theme="@style/AppThemeDark"
            android:exported="false"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
        </activity>'''
if old not in s:
    raise SystemExit('MainActivity manifest block did not match upstream')
s = s.replace(old, new)
s = s.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="${applicationId}.FileProvider"')

# Android 14+ requires the mediaProjection-specific foreground-service permission.
# IMPORTANT: permissions must be children of <manifest>, never inserted before it.
if 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION' not in s:
    manifest_open = re.search(r'<manifest\b[^>]*>', s)
    if not manifest_open:
        raise SystemExit('Could not find <manifest> opening tag')
    permission = '\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>'
    s = s[:manifest_open.end()] + permission + s[manifest_open.end():]

p.write_text(s)

# Fail early with a clear message if a future upstream change produces invalid XML.
try:
    ET.parse(p)
except ET.ParseError as exc:
    raise SystemExit(f'Patched AndroidManifest.xml is invalid: {exc}')

# 5) Give this build a separate application id and version name so it can coexist with Winlator.
p = root / 'app/build.gradle'
s = p.read_text()
s = s.replace("applicationId 'com.winlator'", "applicationId 'com.lm.androiddlsshost'")
s = s.replace('versionName "11.2"', 'versionName "0.2.1-bridge"')
p.write_text(s)

# 6) Rebrand the visible app name without renaming the upstream Java namespace.
strings = root / 'app/src/main/res/values/strings.xml'
ss = strings.read_text()
ss = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">Android DLSS Host</string>', ss, count=1)
strings.write_text(ss)

print('Winlator patched for Android DLSS Host + MediaProjection bridge; manifest XML validated')
