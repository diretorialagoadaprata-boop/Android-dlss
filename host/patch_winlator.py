from pathlib import Path
import re
import shutil

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
old = '''        <activity android:name="com.winlator.MainActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="true"\n            android:screenOrientation="sensor"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n        </activity>'''
new = '''        <activity android:name="com.winlator.DlssHostActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="true"\n            android:screenOrientation="sensor"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n        </activity>\n\n        <activity android:name="com.winlator.DlssLogActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="false"/>\n\n        <service android:name="com.winlator.AndroidFrameBridgeService"\n            android:exported="false"\n            android:foregroundServiceType="mediaProjection"/>\n\n        <activity android:name="com.winlator.MainActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="false"\n            android:screenOrientation="sensor"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n        </activity>'''
if old not in s:
    raise SystemExit('MainActivity manifest block did not match upstream')
s = s.replace(old, new)
s = s.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="${applicationId}.FileProvider"')

# Android 10+ needs foreground-service declaration, and Android 14+ requires the
# mediaProjection-specific permission/type for this service.
permissions = '''\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>\n'''
if 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION' not in s:
    idx = s.find('>') + 1
    s = s[:idx] + permissions + s[idx:]
p.write_text(s)

# 5) Give this build a separate application id and version name so it can coexist with Winlator.
p = root / 'app/build.gradle'
s = p.read_text()
s = s.replace("applicationId 'com.winlator'", "applicationId 'com.lm.androiddlsshost'")
s = s.replace('versionName "11.2"', 'versionName "0.2.0-bridge"')
p.write_text(s)

# 6) Rebrand the visible app name without renaming the upstream Java namespace.
strings = root / 'app/src/main/res/values/strings.xml'
ss = strings.read_text()
ss = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">Android DLSS Host</string>', ss, count=1)
strings.write_text(ss)

print('Winlator patched for Android DLSS Host + MediaProjection bridge')
