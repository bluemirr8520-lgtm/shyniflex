# Plex Play subtitle implementation provenance

The subtitle parser, cue timeline, horizontal/vertical Canvas views, vertical
typography, and position preferences were reused at the user's request from
their existing Plex Play Universal 1.0.44 project.

Original implementation files are `PlayerScreen.kt`, `SubtitleTimeline.kt`,
`VerticalSubtitleTypography.kt`, and `SubtitlePositionPreferences.kt` under
`app/src/main/java/io/mirr/plexplay/ui`. Original sources were read only.

The original Shyni GSY Player adapter changes the package, removes the unused
Plex metadata argument from the parser, and exposes the existing Canvas views
through a native FrameLayout. shyniflex reuses these same parser, timeline,
typography and Canvas implementations from the user's existing Shyni GSY Player
project. These component names record their development provenance without
publishing the user's workstation paths or server addresses.

This provenance record does not establish or grant third-party redistribution
rights. Any applicable original component terms remain in force; the repository
license does not replace separately applicable third-party terms.

libmpv now owns embedded subtitle decoding. Its `sub-text` property supplies
plain timed text to the existing horizontal/vertical Canvas through the Media3
Player compatibility interface. `MpvSubtitleController` hides mpv's text drawing
while keeping decoding active, so font, size, punctuation, writing direction
and positioning continue to use the original Shyni implementation. Bitmap
subtitles such as PGS, VobSub and DVB stay in mpv's native renderer; their glyphs
cannot be converted into vertical text by the Canvas. Sidecar SRT, WebVTT,
ASS/SSA, TTML and SAMI still use the existing plain-text parser and timeline.
ASS author effects and animations are outside this plain-text Canvas path.

The `cinema.ttf` and `asia_cinema_b/m/l.ttf` resources have been removed from the
application's main resources. Subtitle font options provide only the Android
system font and fonts imported by the user into app-private storage.
Existing imported fonts are retained; removed built-in selections fall back to
the system font.

Media3's small `roboto_medium_numbers` resource remains for numeric rewind and
fast-forward control labels loaded by `PlayerControlView`.
It is a UI dependency and is not offered as a subtitle font.

Font-import instrumentation tests use a separate test fixture to exercise
document import, validation and persistence. The fixture belongs only to the
instrumentation test APK. Its generation and licensing record must accompany
the test asset; this provenance record does not grant redistribution rights for
legacy cinema fonts or fonts imported by an application user.

Subtitle HTTP loading remains the new application's responsibility. Plex token
query injection and the old Plex network loader were not copied. Only scoped
Shyni subtitle URLs should be passed to this parser; URLs and tokens should not
be logged. Render a selected sidecar in one overlay and suppress simultaneous
embedded text rendering to avoid duplicate subtitles.
