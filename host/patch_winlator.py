from pathlib import Path
import re

root = Path('winlator')

# 1) Install our Android host activities.
javadir = root / 'app/src/main/java/com/winlator'
javadir.mkdir(parents=True, exist_ok=True)
for name in ('DlssHostActivity.java', 'DlssLogActivity.java'):
    src = Path('host') / name
    (javadir / name).write_text(src.read_text())

# 2) Let RootFSInstaller be used by our standalone AppCompatActivity.
p = root / 'app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java'
s = p.read_text()
s = s.replace('import com.winlator.MainActivity;\n', '')
s = s.replace('public static void install(final MainActivity activity)', 'public static void install(final AppCompatActivity activity)')
s = s.replace('public static void installIfNeeded(final MainActivity activity)', 'public static void installIfNeeded(final AppCompatActivity activity)')
p.write_text(s)

# 3) Make the host the single launcher and keep Winlator's original MainActivity internal.
p = root / 'app/src/main/AndroidManifest.xml'
s = p.read_text()
old = '''        <activity android:name="com.winlator.MainActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="true"\n            android:screenOrientation="sensor"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n        </activity>'''
new = '''        <activity android:name="com.winlator.DlssHostActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="true"\n            android:screenOrientation="sensor"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n        </activity>\n\n        <activity android:name="com.winlator.DlssLogActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="false"/>\n\n        <activity android:name="com.winlator.MainActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="false"\n            android:screenOrientation="sensor"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n        </activity>'''
if old not in s:
    raise SystemExit('MainActivity manifest block did not match upstream')
s = s.replace(old, new)
s = s.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="${applicationId}.FileProvider"')
p.write_text(s)

# 4) Give this build a separate application id and version name so it can coexist with Winlator.
p = root / 'app/build.gradle'
s = p.read_text()
s = s.replace("applicationId 'com.winlator'", "applicationId 'com.lm.androiddlsshost'")
s = s.replace('versionName "11.2"', 'versionName "0.1.0-host"')
p.write_text(s)

# 5) Rebrand the visible app name without renaming the upstream Java namespace.
strings = root / 'app/src/main/res/values/strings.xml'
ss = strings.read_text()
ss = re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">Android DLSS Host</string>', ss, count=1)
strings.write_text(ss)

print('Winlator patched for Android DLSS Host')
