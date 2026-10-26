# Third-party components and provenance

This repository publishes the Android shyniflex source at
<https://github.com/bluemirr8520-lgtm/shyniflex>. User-owned application code is
licensed under GPL-3.0-or-later; see [LICENSE](LICENSE). Generated native
libraries, the CA bundle, downloaded runtime archives, APKs, private signing material, server
captures and local QA output are excluded from Git. The project license does not
replace third-party component licenses or grant rights that their owners have
not provided.

The v1.0.11 GitHub Release is prepared to distribute the APK together with
[`shyniflex-1.0.11-corresponding-source.zip`](https://github.com/bluemirr8520-lgtm/shyniflex/releases/download/v1.0.11/shyniflex-1.0.11-corresponding-source.zip).
This is the planned stable asset URL, rather than a claim that publication has
already completed. The bundle includes the exact release's `app-source.zip`,
the matching upstream wrapper/build recipes, all 14 primary native dependency
sources and seven pinned recursive submodule sources, manifests, license texts,
and a hash-verifying restoration script. The application source is also
identified by [tag v1.0.11](https://github.com/bluemirr8520-lgtm/shyniflex/tree/v1.0.11).

## Original decorative artwork

The theater backdrop at
`android/app/src/main/res/drawable-nodpi/cinema_paradiso_backdrop.png`
was generated for this project with the built-in `image_gen` tool on
2026-10-26. It is original decorative artwork inspired by classic Italian
cinemas. No existing film still or poster files were used as source assets
for this backdrop.

## Native playback runtime

[`android/setup-mpv.ps1`](android/setup-mpv.ps1) fetches the official
[mpv-android 2026-09-17 release](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17)
and its matching tagged source archive. Both downloads are pinned by SHA-256 in
the script. The unmodified ARM64, ARMv7 and x86_64 libraries are extracted into
`android/app/src/main/jniLibs/`; archives, build recipes and generated digests are
retained locally under `android/runtime-source/`. The tagged mpv-android archive
contains wrapper/application sources and build recipes; it is not a complete
archive of the separately fetched mpv, FFmpeg and other native dependency sources.

The native binaries are downloaded during setup rather than included in this
source repository. Their libmpv and FFmpeg builds include GPL components. Anyone
redistributing a built APK must preserve the applicable licenses and notices and
provide access to the corresponding source and build information for that exact
runtime. The release's complete corresponding-source asset supplies those
separately fetched dependency and submodule sources alongside the recipes;
the small upstream wrapper ZIP alone does not. See
[`docs/NATIVE-SOURCES.md`](docs/NATIVE-SOURCES.md) for the pinned environment,
archive layout and restoration instructions. No native compilation or
byte-identical rebuild is claimed for the source collection process.

- [mpv source](https://github.com/mpv-player/mpv)
- [FFmpeg source](https://github.com/FFmpeg/FFmpeg)
- [Matching mpv-android build recipes](https://github.com/mpv-android/mpv-android/tree/2026-09-17/buildscripts)
- [Runtime provenance included by the app](android/app/src/main/assets/licenses/NATIVE-RUNTIME.txt)

The JNI declarations in `is.xyz.mpv.MPVLib` derive from mpv-android, copyright
2016 Ilya Zhuravlev and sfan5. The upstream MIT copyright and permission notice is preserved in
[`mpv-android-MIT.txt`](android/app/src/main/assets/licenses/mpv-android-MIT.txt).
The CA certificate bundle is downloaded from the same pinned official APK. The
upstream [curl / Mozilla CA bundle](https://curl.se/docs/caextract.html) is subject
to MPL-2.0. Its matching data, provenance and applicable license notice are part
of the release distribution materials. The NDK's redistributed
`libc++_shared.so` also retains its own license and notices.

## Subtitle components

The user provided their existing Shyni GSY Player and Plex Play Universal
projects as the source for subtitle parsing, cue timelines, Canvas rendering,
vertical typography and position preferences. Adaptation details and limits are
recorded in
[`android/PLEX_SUBTITLE_NOTICES.md`](android/PLEX_SUBTITLE_NOTICES.md).
These project names document provenance; they are not an independent grant of
third-party redistribution rights.

Plezy's public home and card layout informed the Continue Watching presentation.
Its source and artwork were not copied for that screen. Reference links are in
the [README](README.md#출처).

## Android and application dependencies

Dependencies are declared with their versions in
[`android/app/build.gradle.kts`](android/app/build.gradle.kts). Their upstream
licenses and notices remain applicable:

- [AndroidX / Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/)
- [AndroidX Media3](https://github.com/androidx/media), used for controls and the Player interface
- [Kotlin](https://github.com/JetBrains/kotlin) and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)
- [OkHttp / MockWebServer](https://github.com/square/okhttp)
- [Coil](https://github.com/coil-kt/coil)
- [JUnit 4](https://github.com/junit-team/junit4) and [JSON-java](https://github.com/stleary/JSON-java), used by tests
- [Gradle](https://github.com/gradle/gradle), whose Apache-2.0 wrapper scripts and
  `gradle-wrapper.jar` are included for reproducible builds;
  [matching wrapper source](https://github.com/gradle/gradle/tree/v8.13.0/platforms/core-runtime/wrapper)
  and [Gradle 8.13 source distribution](https://services.gradle.org/distributions/gradle-8.13-src.zip)

Media3's numeric control font is an upstream UI resource, not a bundled subtitle
font. The application offers the Android system font and user-imported fonts;
users remain responsible for the rights to fonts they import.

The APK's `assets/licenses/` retains dependency license texts and applicable
notices; the source bundle preserves the source archives' original license
files as well. Declaring an application license or listing upstream links alone
does not replace those notices. Redistributors must retain them and provide
the corresponding source for the exact binaries they distribute.

## Synthetic media fixtures

The small audio/video instrumentation fixtures are original FFmpeg-generated
tones and test patterns. Their generation commands and checksums are documented
under `android/app/src/androidTest/assets/media3/`. They do not contain films,
music recordings, account data or private server responses.
