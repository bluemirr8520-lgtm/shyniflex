#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
audio=(-hide_banner -loglevel error -y -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=3' -ac 2 -threads 1)
ffmpeg "${audio[@]}" -c:a aac -b:a 128k tone-aac.m4a
ffmpeg "${audio[@]}" -c:a ac3 -b:a 192k tone-ac3.ac3
ffmpeg "${audio[@]}" -c:a eac3 -b:a 192k tone-eac3.eac3
ffmpeg "${audio[@]}" -c:a dca -strict -2 -b:a 768k tone-dts.mka
ffmpeg "${audio[@]}" -c:a flac tone-flac.flac
ffmpeg "${audio[@]}" -c:a libopus -b:a 96k tone-opus.ogg
ffmpeg "${audio[@]}" -c:a truehd -strict -2 tone-truehd.mka
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=160x90:rate=24:duration=6' \
  -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=6' -t 6 -ac 2 \
  -c:v libx264 -threads 1 -preset ultrafast -tune zerolatency -crf 35 -g 24 \
  -pix_fmt yuv420p -c:a aac -b:a 64k -movflags +faststart gesture-av.mp4
for file in tone-* gesture-av.mp4; do
  ffmpeg -hide_banner -loglevel error -i "$file" -f null -
  printf '%s ' "$file"
  ffprobe -v error -show_entries stream=codec_name,codec_type,sample_rate,channels \
    -show_entries format=duration -of compact=p=0:nk=1 "$file"
done
sha256sum tone-* gesture-av.mp4 > SHA256SUMS
