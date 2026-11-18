#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Entirely synthetic fixtures: no server media, credentials, or copyrighted clips.
if [[ "${1:-}" != --verify-only ]]; then
source=(-hide_banner -loglevel error -y
  -f lavfi -i 'testsrc2=duration=4:size=320x180:rate=24'
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=4')
if [[ "${1:-}" != --exo-only ]]; then
ffmpeg "${source[@]}" -map 0:v -map 1:a -c:v wmv1 -b:v 450k -g 12 -threads 1 \
  -c:a wmav2 -b:a 96k -ar 48000 -ac 2 -t 4 legacy-wmv1.wmv
ffmpeg "${source[@]}" -map 0:v -map 1:a -c:v wmv2 -b:v 450k -g 12 -threads 1 \
  -c:a wmav2 -b:a 96k -ar 48000 -ac 2 -t 4 legacy-wmv2.wmv
ffmpeg "${source[@]}" -map 0:v -map 1:a -c:v mpeg4 -b:v 450k -g 12 -threads 1 \
  -c:a libmp3lame -b:a 96k -ar 48000 -ac 2 -t 4 legacy-mpeg4.avi
ffmpeg "${source[@]}" -map 0:v -map 1:a -c:v libx264 -profile:v high -level:v 3.0 \
  -pix_fmt yuv420p -preset veryfast -b:v 450k -g 12 -threads 1 \
  -c:a aac -profile:a aac_low -b:a 96k -ar 48000 -ac 2 -t 4 legacy-h264.ts
fi
text_source=(-hide_banner -loglevel error -y
  -f lavfi -i 'testsrc2=duration=20:size=320x180:rate=24'
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=20')
cat > exo-embedded-subrip.srt <<'SUBRIP'
1
00:00:00,250 --> 00:00:18,000
Embedded SubRip cue
SUBRIP
cat > exo-embedded-webvtt.vtt <<'VTT_CUE'
WEBVTT

00:00.250 --> 00:18.000
Embedded WebVTT cue
VTT_CUE
for kind in subrip webvtt; do
  ext=srt
  if [[ "$kind" == webvtt ]]; then ext=vtt; fi
  ffmpeg "${text_source[@]}" -i "exo-embedded-$kind.$ext" -map 0:v -map 1:a -map 2:s \
    -c:v libx264 -profile:v high -level:v 3.0 -pix_fmt yuv420p -preset veryfast \
    -b:v 450k -g 12 -threads 1 -color_primaries bt709 -color_trc bt709 -colorspace bt709 \
    -c:a aac -profile:a aac_low -b:a 96k -ar 48000 -ac 2 -c:s "$kind" \
    -metadata:s:s:0 language=eng -metadata:s:s:0 title='Synthetic embedded cue' \
    -t 20 "exo-embedded-$kind.mkv"
done
fi
files=(legacy-wmv1.wmv legacy-wmv2.wmv legacy-mpeg4.avi legacy-h264.ts
  exo-embedded-subrip.mkv exo-embedded-webvtt.mkv)
for file in "${files[@]}"; do
  ffprobe -v error -show_entries stream=index,codec_name,profile,width,height,sample_rate,channels \
    -show_entries format=duration -of compact "$file"
  # Decode to raw sinks so the validation does not re-quantize Matroska timestamps.
  ffmpeg -hide_banner -loglevel error -xerror -i "$file" -map 0:v -pix_fmt yuv420p \
    -fps_mode passthrough -enc_time_base:v 1/1000 -f rawvideo -y /dev/null
  ffmpeg -hide_banner -loglevel error -xerror -i "$file" -map 0:a -f s16le -y /dev/null
done
sha256sum "${files[@]}" > LEGACY-SHA256SUMS
