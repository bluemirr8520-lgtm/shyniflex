exo-embedded-subrip-kor-audio.mkv is a metadata-only remux of the existing synthetic
exo-embedded-subrip.mkv test clip. Its audio remains a generated tone, not recorded
Korean speech. The AAC stream has language=kor to test the playback engines' selected
audio-language metadata; the embedded SubRip caption retains language=eng.

Generated with Ubuntu FFmpeg 6.1.1:
ffmpeg -hide_banner -loglevel error -y -i exo-embedded-subrip.mkv -map 0 -c copy \
  -metadata:s:a:0 language=kor exo-embedded-subrip-kor-audio.mkv

ffprobe verified stream 0=H.264 video, stream 1=AAC audio with language=kor,
and stream 2=SubRip subtitle with language=eng. No transcoding was performed.

SHA-256:
4aaefe521aedf71138cbc6d72bfe72ff0ebbf8b1497e4262f32842f02a9fe0f4  exo-embedded-subrip-kor-audio.mkv

This androidTest-only fixture is excluded from release APKs.

gesture-av-kor-audio.mp4 is the existing six-second generated gesture-av.mp4,
also copied without transcoding. It deliberately contains no subtitle stream so
tests can check a delayed external-caption list independently of native captions.
ffmpeg -hide_banner -loglevel error -y -i gesture-av.mp4 -map 0 -c copy \
  -metadata:s:a:0 language=kor -movflags +faststart gesture-av-kor-audio.mp4
ffprobe verified H.264 video and AAC audio with language=kor, with no text stream.
14d5e7430abdef5cde7763678c8102aa7f249dc5e5dc834b10839a08534bedb3  gesture-av-kor-audio.mp4
