#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Equivalent standard FFmpeg commands for the synthetic 320x180, 24 fps sources.
source=(-hide_banner -loglevel error -y -f lavfi -i 'testsrc2=duration=3:size=320x180:rate=24')
ffmpeg "${source[@]}" -pix_fmt yuv420p10le -c:v libx265 -preset ultrafast -crf 34 \
  -x265-params 'pools=1:frame-threads=1:log-level=error' modern-hevc-main10.mkv
ffmpeg "${source[@]}" -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -crf 40 -b:v 0 -threads 1 modern-vp9.webm
ffmpeg "${source[@]}" -c:v libsvtav1 -preset 12 -crf 40 -svtav1-params 'lp=2:loglevel=0' modern-av1.mkv
for file in modern-hevc-main10.mkv modern-vp9.webm modern-av1.mkv; do
  ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,profile,pix_fmt,width,height \
    -show_entries format=duration -of compact "$file"
  ffmpeg -hide_banner -loglevel error -i "$file" -f null -
done
sha256sum modern-hevc-main10.mkv modern-vp9.webm modern-av1.mkv > MODERN-SHA256SUMS
