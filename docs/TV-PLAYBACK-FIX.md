# TV playback startup fix (1.0.68)

On SHIELD TV with 1.0.67 (version code 68), opening any video crashed in
`NativePlayerActivity.buildViews()` when binding `player_continuous`.
The television-qualified controller layout did not contain this required button.
This happens before media decoding, independently of which video is selected.

Remove the outdated `layout-television/shyniflix_controls.xml` override so both
device types use the current shared controller. This retains the continuous
playback control, rounded actions and horizontal scrolling for narrow viewports.
Device-specific remote handling remains in the Activity. A regression test
inflates the controller in both television and normal configurations and checks
the required controls; the existing tests exercise Activity startup, continuous
playback persistence and remote focus traversal.

The production package is `io.mirr.shyniflex`. Version 1.0.68 uses version code 69.
An update preserving installed accounts must be signed using the existing release
key. The installed SHIELD APK's public signing certificate SHA-256 is
`771172249ed6bd21eac6d024a2fe58dc0f18d46f5f84dd52e8406fede5aec8e7`.
The key is not available in this local workspace; the repository currently has
neither GitHub Actions workflows nor repository Actions secrets. Do not replace
the signing key or uninstall the production app to work around this requirement.
Reuse the original release-build environment and include the matching native
corresponding sources when publishing a signed release.

Validation is performed using the isolated `io.mirr.shyniflex.spoilerqa` package,
leaving the production app and its data unchanged.

Validation on SHIELD TV:

- 880 JVM unit tests passed, with no failures or skips.
- Both TV and normal controller resource configurations contain all required buttons.
- The real player Activity opens; continuous playback toggles and survives reopening.
- Remote focus traverses a narrow controller to its rightmost action.
- A real MPV video plays to EOF and returns successfully to its caller.
- Playback settings accept directional remote input.
- The subtitle shortcut opens with the remote and selecting Off preserves paused playback and audio selection.

These are six distinct device tests. The subtitle test uses DPAD input on TV and
retains touch input on phones; it waits for the selection to be applied before
asserting playback state. The isolated QA packages are removed after validation.
