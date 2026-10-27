#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
javac -encoding UTF-8 -d "$tmp" app/src/main/java/br/com/linkparamidia/ClipRules.java app/src/main/java/br/com/linkparamidia/DownloadOutcome.java tests/ClipRulesTest.java tests/DownloadOutcomeTest.java app/src/main/java/br/com/linkparamidia/CookieRules.java tests/CookieRulesTest.java
java -cp "$tmp" br.com.linkparamidia.ClipRulesTest
java -cp "$tmp" br.com.linkparamidia.DownloadOutcomeTest
java -cp "$tmp" br.com.linkparamidia.CookieRulesTest
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=128x72:rate=10 -f lavfi -i sine=frequency=420:sample_rate=16000 -t 9 -c:v libx264 -preset ultrafast -c:a aac -shortest "$tmp/full.mp4"
ffmpeg -hide_banner -loglevel error -ss 2 -i "$tmp/full.mp4" -t 3 -c:v libx264 -preset ultrafast -c:a aac "$tmp/clip.mp4"
ffmpeg -hide_banner -loglevel error -ss 2 -i "$tmp/full.mp4" -t 3 -vn -c:a libmp3lame -b:a 192k "$tmp/clip.mp3"
python3 - "$tmp" <<'PY'
import sys, subprocess, pathlib
folder=pathlib.Path(sys.argv[1])
def duration(name):
 return float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','default=noprint_wrappers=1:nokey=1',str(folder/name)]).decode())
assert abs(duration('full.mp4')-9)<.3
assert abs(duration('clip.mp4')-3)<.3
assert abs(duration('clip.mp3')-3)<.3
print('Real MP4/MP3 segment conversion tests: PASS')
PY
