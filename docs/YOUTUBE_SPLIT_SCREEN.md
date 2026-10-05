# Native YouTube picture — offline candidate

Candidate: `0.11.7-commercial-break.36-youtube-split-offline`, versionCode 55,
branch `codex/youtube-split`, based on `.35` commit `a5db0af`.
This candidate is **not installed**. The Shield and its existing Button 12 mapping
still use `.35`. Do not treat PC tests as verification of Shield voice, decoding,
physical remote input, audio, PiP, or Netflix restoration.

Saved APK (relative to the parent Shield workspace):
`downloads/OpenTV/opentv-v0.11.7-commercial-break.36-youtube-split-offline.apk`,
7,116,759 bytes. SHA-256:
`49037EF8F43857ADFB2B71BE258CD3EB4C29F8452DE2253930FD4E30A05F2E4E`.
APK signature verification passed and the certificate matches `.35`.
Final full offline verification passed: 298 executed debug/release unit tests,
zero failures/errors, two skipped opt-in network checks; release-critical lint,
R8, desugaring, resource shrinking and packaging passed. A separate opt-in PC run
passed all eight extraction/layout/codec tests, including the real network probe.

## Design and controls

The Shield reports Android app-to-app split screen unsupported. Launching the
installed SmartTube app cannot make its activity an ordinary half of OpenTV.
Instead OpenTV uses the open-source NewPipeExtractor library to search and resolve
public YouTube videos, and plays the chosen video inside an existing split player.
SmartTube's F-Droid installation and login are unchanged. This is not embedded
SmartTube UI and does not inherit its account, subscriptions, settings or SponsorBlock.

1. Open the existing Split screen mode and select either picture.
2. Select opens the guarded picture menu. Choose **YouTube voice search**.
   Android's installed speech-recognition activity opens automatically. A successful
   result searches YouTube; choose one of up to 24 video results to replace that
   picture. Canceling leaves both channels unchanged. Typed search is available if
   recognition is unavailable; **Speak again** retries voice input.
3. YouTube uses the same equal-half/Fit/Fill geometry, selection, audio-follow,
   explicit **Audio select**, **Expand picture**, and Back-to-two-pictures behavior
   as TV. No third player is created. Only one picture is audible.
4. The first YouTube replacement saves both previous TV choices and the selected,
   audible, expanded and Fill/Fit layout in process memory. Repeated searches or
   moving YouTube to the other picture do not overwrite that original snapshot.
5. With the candidate Netflix script deployed, Netflix restores that TV snapshot.
   A TV picture not replaced by YouTube keeps its existing stream/buffer. A replaced
   picture must reopen its TV stream: this is layout restoration, not a promise of
   retaining a third hidden TV player or its old buffer. The secondary TV stream also
   reopens after leaving OpenTV for another app.
6. **Change channel** replaces only the selected picture with TV. Netflix restoration
   remains available until used or split mode exits. Keep-channel-full-screen is
   disabled while a YouTube restoration snapshot exists; Exit/guide restore TV first.

The ordinary guide, provider matching, storage policies, recordings, and existing
adaptive HLS targets are retained. YouTube requests are recorded-video requests with
no TV channel ID, so they do not alter learned live-channel offsets. Starting a
recording restores TV and removes the secondary picture under the existing recording
guard. YouTube replacement is disabled while recordings are loading or active.

## Limits and privacy

- First version: public, signed-out recorded videos only. No YouTube live streams,
  paid/private/age-restricted account content, login, subscriptions, playlists,
  SponsorBlock, autoplay or in-pane seek controls. Search/playback can break when
  YouTube changes; extraction errors offer retry/another video without logging URLs.
- Progressive AVC/H.264 or VP9 only, at most 1080p/60 fps; prefer AVC adaptive video
  over VP9 and merge separate audio when needed. Exclude AV1, unknown codecs and
  larger/faster formats to avoid accidental Shield software decoding. This is a
  format policy, not a completed hardware-decoder/performance verification.
- Search/extraction uses a separate no-cookie/no-disk-cache/no-logging OkHttp client,
  serialized extractor access, bounded 16 MiB metadata/JavaScript responses and
  network timeouts. Playback uses the existing no-cookie streaming data source.
  Video/audio can use separate HTTP requests but share one Media3 player per picture.
  No extra disk DVR or guide storage is introduced.
- Search text goes to YouTube. Voice goes to Android's configured recognizer, which
  may use its provider's cloud service; OpenTV adds no microphone permission and
  stores no voice recordings or search history. Recognition temporarily disables
  commercial-break PiP and foreground loss mutes/releases the secondary player.
- The snapshot survives temporary foreground/PiP changes while PlayerScreen remains
  alive, not process death, app exit or reboot. Signed playback links are resolved
  afresh, not written to settings/database/docs. Never enable raw HTTP/extractor logs.

## Netflix deployment — pending live verification

`scripts/shield-netflix-toggle.sh` is the candidate command body, **not deployed**.
Keep exactly one Key Mapper Button 12 / Any input device rule. After the candidate
APK is installed in place with its matching signer, copy this script through the
existing documented ADB/Termux workflow and point that one rule to it. Do not add
Select/Back mappings, replace the boot monitor, uninstall Netflix, or clear app data.
Read the parent workspace runbook before any Shield change.

The script restores a pinned OpenTV task first. If OpenTV is foreground it sends
`app.opentv.action.NETFLIX_TOGGLE`; the dynamically registered receiver requires the
existing privileged `android.permission.DUMP` permission. With a saved YouTube layout
it signals restoration; otherwise it launches the existing F-Droid SmartTube activity.
From other apps it launches SmartTube as before. Only the matching candidate knows
this new action; do not deploy the script ahead of the APK.

Rollback: restore the exact prior Button 12 command from the runbook and install
the matching-signer `.35` recovery APK with downgrade allowed, preserving app data.
No schema or provider change is required by `.36`.

## Rebuild and verification

Use JDK 17 and the existing Android SDK/signing configuration. For a first build,
allow Gradle to download the pinned dependencies; later builds can be offline.
The optional `scripts/offline-output.init.gradle` puts generated outputs outside
OneDrive when `OPENTV_OFFLINE_BUILD_DIR` is set to a disposable build directory.

```powershell
.\gradlew.bat --no-configuration-cache --no-watch-fs test lintVitalRelease assembleRelease
# Explicit PC-only network check; ordinary tests skip this check.
$env:OPENTV_YOUTUBE_SMOKE='1'
.\gradlew.bat testDebugUnitTest --tests app.opentv.YoutubeExtractionSmokeTest
Remove-Item Env:OPENTV_YOUTUBE_SMOKE
```

PC smoke testing searches Blender Big Buck Bunny, resolves a public video and reads
only a 1 KiB range from each video/audio resource. It verifies extraction/network
access, not Android decoding, lip sync, ad absence, or the physical microphone.
Memory regression tests cover both original TV channels, repeated searches, moving
YouTube between panes, and layout/audio restoration. Codec-policy tests reject AV1,
unknown codecs, high resolution/frame rate and unknown resolution.

Before installing, check Shield free space and APK signer; use an in-place upgrade,
never uninstall/data-clear. The last recorded free space was below guide ingestion
thresholds: do not force guide refresh or cache cleanup during the test.

Pending live tests, only when the user is ready:

1. Start two channels from different tabs, verify both programmes/audio, then search
   by physical remote voice and typed input; cancel/zero-results/network failure must
   leave the TV choices intact. Confirm focus navigation after the recognizer/keyboard.
2. Play YouTube in each half, verify video advancement, lip sync, exclusive audio,
   audio override, both expansions, equal-half restoration and hidden controls.
3. Press the physical Netflix button: restore both previous TV channels/layout/audio;
   repeat after selecting another YouTube result, moving panes, and changing back to TV.
4. Test repeated searches, finished videos/retry, decoder/memory pressure, failed and
   expired media URLs, and starting/scheduled recording. Never log provider credentials
   or signed playback URLs. Confirm no third player or mixed audio.
5. Retest the ordinary SmartTube/PiP Netflix path, returning to the saved split layout,
   Home/Plex cleanup and reboot persistence without changing the boot monitor.

## Source and licensing

OpenTV and NewPipeExtractor are GPL-3.0-or-later. Preserve existing OpenTV notices
and provide the matching modified source with any distributed APK.

- [NewPipeExtractor v0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5)
  (`com.github.teamnewpipe:NewPipeExtractor:v0.26.5`, GPL-3.0-or-later).
- Its transitive nanojson dependency is pinned upstream to
  `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996`; Rhino is 1.8.1.
  Keep those artifacts and their published licenses with the build dependencies.
- Android NIO desugaring: `com.android.tools:desugar_jdk_libs_nio:2.1.3`, required by
  the extractor below Android API 33. Shield is API 30.
- [Extractor JavaScript path](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/utils/JavaScript.java)
  explicitly uses Rhino interpreted mode with safe standard objects, not desktop
  javax.script/dynalink engines. Optional desktop references caused the first R8
  failure; targeted rules follow
  [NewPipe's Android rules](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/proguard-rules.pro),
  with timeago reflection and protobuf fields retained. Android minified playback
  still needs the live check; a passing desktop extraction test alone cannot prove it.
