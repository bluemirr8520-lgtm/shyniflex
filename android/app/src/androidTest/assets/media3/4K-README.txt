Original synthetic 4K streaming fixture

synthetic-4k-hevc-main10.mp4
SHA256: cc4ed6708e2c993cc95f67a76d28a2f7387b3eeb7c45cebf1b429dd7327dbb07
Bytes: 9,257,811 (8.83 MiB)
Input: FFmpeg lavfi testsrc2, 3840x2160, nominal 24 fps, no audio.
Verified output: HEVC, decoded pixel format yuv420p10, 3840x2160.
MP4 movie duration: 11.958333333333334 seconds. moov precedes mdat (fast start).
This is an original color/pattern animation, not a film or downloaded sample.
It is an androidTest asset and does not increase the release APK size.

Generation on 2026-10-23 used the existing Windows mpv encoder:
mpv v0.41.0-1224-g6a262d11f, FFmpeg N-127683-gc2bbf2b83.
The encoder process had no window; Python subprocess.run used CREATE_NO_WINDOW,
stdin=DEVNULL and redirected stdout/stderr into an ignored local artifact log.

Arguments (each line is a separate argument; the final line is the input):
--no-config
--terminal=yes
--no-audio
--force-window=no
--end=12
--o=synthetic-4k-hevc-main10.mp4
--of=mp4
--ofopts=movflags=+faststart
--ovc=libx265
--ovcopts=preset=ultrafast,crf=32,x265-params=pools=2:frame-threads=2:vbv-maxrate=6000:vbv-bufsize=6000:keyint=24:min-keyint=24:scenecut=0:log-level=error
av://lavfi:testsrc2=duration=12:size=3840x2160:rate=24,format=yuv420p10le

A separate headless software-decoding probe used --vo=null --hwdec=no
--frames=2 and verified hevc / 3840 / 2160 / yuv420p10. MP4 mvhd confirmed
the exact duration and top-level box order. Re-encoding with different tool
versions can change the bytes; the SHA256 above identifies the test input.

Native4kStreamingTest uses only the public MpvPlayer/Media3 interface, including
setAudioAttributes(AudioAttributes.DEFAULT, false) and volume=0. A dedicated
.spoilerqa package guard precedes assets, sockets and Activity launch. The empty
ComponentActivity supplied by ui-test-manifest hosts the real video Surface;
MainActivity, account sessions, PINs and Shyni API requests are not used.

The normal case records player creation, READY, real first-frame event,
PixelCopy colored pixels, 4K dimensions, actual decoder, HTTP requests/ranges
and bytes written. A conservative 15-second first-frame limit is a regression
guard, not a startup-speed promise. The stall case sends an approximately
two-source-second prefix, waits for the first rendered frame, withholds all
remaining range bodies for three seconds, and verifies buffering/recovery
without a playback error or source-clock reset. All responses share the gate.

The optional instrumentation argument benchmarkLabel is sanitized to at most
32 ASCII letters/digits/dots/underscores/hyphens; default is unlabeled.
Results are numeric JSON, also emitted under Logcat tag shyniflex4kBenchmark:
cache/4k-benchmark/<label>-first-frame.json
cache/4k-benchmark/<label>-three-second-stall.json
Use baseline/current labels to keep observations from separate app versions.

These synthetic loopback cases isolate decoding, rendering and read buffering.
They do not measure Shyni API preparation, real server/WAN throughput, all 4K
codecs/bitrates, HDR accuracy, DRM, or full-length movie behavior. No device
performance or pass result is asserted by this provenance document.
