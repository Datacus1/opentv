# Two-channel split screen

Installed test candidate: `0.11.7-commercial-break.33-menu-directions`, versionCode 52.
Development branch: `codex/two-channel-split-screen`. Installed in place on the
Shield on October 4 with the existing signer, app UID, and app data preserved.
The September 27 `.28` provider-guide changes are retained. Narrow live results
are below; this is not a completed production-regression sign-off.

The `.31` focus, screen-area, expansion, and picker-guide changes are included.
Final offline tests passed with 264 debug/release unit-test executions and zero
failures/errors; release-critical lint, R8, resource shrinking, and packaging passed.
Saved APK:
`downloads/OpenTV/opentv-v0.11.7-commercial-break.33-menu-directions.apk`
(relative to the parent Shield workspace), SHA-256
`9202BD7742F6EC39E2F00AD60C4E1B187204DB240E6ACD139C0321C259B890BA`.
Its signing certificate matches the installed custom builds.

October 4 live testing installed `.31` and verified the picker programme rows, bright
action focus/selected-picture decoration, idle toolbar hide, and wake-release guard.
It also exposed zoomed SurfaceView overflow: the native video could extend outside a
Compose half-screen clip, making one picture appear larger or draw over the other.
`.32-split-surface-clip` (versionCode 51) changes only split-screen output views to
TextureView with native parent clipping through `res/layout/split_player_view.xml`.
Ordinary guide/full-screen/PiP output views, decoders, buffering, and audio routing are
unchanged. Both pictures now occupy equal halves in Fill mode, without overlap.
Both expanded pictures visually fill the screen; Back restores the dual layout.

## Controls

1. Watch a channel normally, reveal the player controls, and choose **Split screen**.
   Entering split screen resumes the original channel if it was paused.
2. Choose **Choose second channel**. Browse All channels, Favourites, or a category,
   or search the saved channel catalogue. This can select a channel from another guide tab.
3. Use **Left audio** / **Right audio** to switch sound. Merely focusing a picture
   does not change sound. An **Audio** label identifies the audible pane.
4. Select a picture or choose **Expand picture** to expand it; **Two pictures** or
   Back restores the two panes. Expansion keeps both players and their buffers alive.
5. **Change second channel** retunes only the second player.
6. Focus either picture, then choose **Keep left/right channel full-screen** to retain
   that picture and close the other stream. The already-prepared second player can
   become the shared live session without resolving its URL or preparing it again.
   The action stays disabled until the selected second channel is ready.
   Channel-up/down then uses the second channel's picker list rather than the old
   picture's guide tab. Back restores the kept channel's guide selection.
7. **Exit split screen** releases the second player and returns to the original channel.
   Back from the two-picture view returns to the guide with that original channel
   selected and playing in the existing guide preview. Back from an expanded picture
   first restores the two-picture view. Back in the picker dismisses only the picker.
8. The picker retains category, search, favourites mode, scroll positions, and last
   focused channel for this split-screen session. All/Favourites stay visible outside
   the scrolling category list. Focus is requested only after placement, and a removed
   channel falls back safely. Dismissing the picker focuses the harmless Exit control.
9. Each picture's **Try again** resolves its provider link again, including a fresh
   temporary Stalker link, rather than just reusing an expired address. Lookup failures
   also have Retry. Changing channel or leaving the screen cancels an in-flight lookup.
10. In `.31`, the toolbar is an overlay, never a reserved strip below the videos.
    After a second channel is chosen, controls/labels/borders hide after five idle seconds.
    Select or a direction wakes them; that complete first press (including repeats and
    release) is consumed so it cannot also expand a picture. Down wakes directly onto
    the first enabled action. Up from the toolbar returns to the selected picture;
    Left/Right between pictures changes selection, not sound.
11. Only a focused action has bright fill and a thick white outline. The audible action
    has a separate check mark. The picture with remote focus has a white outline;
    the selected picture remains labelled Selected and outlined blue while the toolbar
    owns focus. These decorations disappear with the idle controls.
12. Each picture uses the full height of its half-screen. Fit preserves the full frame
    and can leave black bars. **Fill pictures (crop edges)** fills those tall halves
    by cropping; **Fit pictures (show whole picture)** undoes it. This choice is local
    to the split session. An expanded picture always uses the entire screen, fits its
    original aspect ratio, and immediately hides controls. Back restores both pictures.
13. The second-channel picker shows the current programme and local start/end times
    under each channel name, including the existing compact college-football matchup
    formatter. It reads the enabled-feed cache, uses populated override/provider/matched
    IDs and sibling quality-variant fallback, and refreshes its notion of Now every minute.
    The current-programme index is shared across rows and built off the UI thread;
    catalogue updates are observed. No feed refresh, schema change, or disk cache is added.
14. In ordinary full-screen playback, `.33` makes D-pad Up/Down reveal or keep the
    bottom control menu open, never change channel. Both key-event halves and repeats
    are consumed outside open pickers. Left/Right navigates the control buttons.
    Open lists and track/quality/aspect panels retain normal Up/Down navigation;
    guide browsing and split-picture navigation are unchanged. Dedicated CH+/CH-
    keys, if another remote has them, keep their separate channel shortcuts.

## Playback and resources

- Exactly two players maximum: the original shared live session plus one extra controller.
  Choosing a second channel is required before that extra controller is created.
- Audio routing mutes both players before enabling the selected one. There is never
  an intentional mix of the two channels. Expanding a picture uses that picture's audio.
- The second controller is released when the app loses resumed foreground state,
  enters PiP, exits split screen, or leaves the player route. Returning from an ordinary
  background pause can recreate the selected second stream.
- Entering Android PiP ends split-screen mode and retains only the original channel,
  muted according to the established SmartTube commercial-break behavior. Expanding
  PiP returns to ordinary single-channel playback.
- Expanding a split picture keeps the other channel streaming, so it still uses two
  connections. Recording uses additional provider connections. The app cannot create
  additional allowance on a provider account.
- The extra player cannot open while recording status is loading or any recording is
  active. Recording status is checked again before resolution and before playback.
  If a scheduled recording starts, the extra player is released and its selection
  cleared; the user must choose it again after the recording ends. Recording is not
  canceled automatically. This is conservative even for multi-connection accounts.
- The extra player disables DVR and tunneling, prefers a <=1080p channel variant,
  applies a 1080p adaptive-track preference, and uses a 32 MiB allocator target.
  A provider with only a fixed 4K stream cannot be downscaled at download/decode time
  by that preference; actual concurrent decoding capacity needs the Shield test.
- Both streams keep the existing adaptive HLS live-offset policy. No video disk cache,
  guide feed, database table, schema migration, or storage-retention change is introduced.
- Multiview can adjust its current live offset, but neither pane persists that learning.
  Returning to one picture does not untaint the existing tune; only a fresh, isolated
  tune can persist learning again. Existing learned offsets are still read normally.
- Promoting the second player preserves its stream and buffer, restores the unrestricted
  adaptive-track preference, and allows ordinary buffer sizing on the next track
  selection/tune. Saved DVR back-buffer policy resumes on a subsequent prepare; missing
  pre-promotion history cannot be reconstructed. The selected fixed channel variant
  remains unchanged unless the user chooses another variant through normal controls.
- The picker reads local data only. Hidden channels, disabled sources, and locked hidden
  categories are excluded. Quality variants of the original channel are excluded to
  prevent opening the same logical channel twice on one source.

## Offline validation

Run with the saved JDK and Android SDK:

```powershell
$env:JAVA_HOME = 'C:\Users\dchas\OneDrive\Documents\ChatGPT\NVIDIA Shield\tools\shield-build\jdk-extract\jdk-17.0.20+8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --offline test lintVitalRelease assembleRelease
```

`SplitScreenPolicyTest` exercises every focus/expansion/foreground combination for
exclusive audio, ordered mute-before-unmute handoffs, detachment before player release,
quality-variant deduplication, cross-source identity, favourite preservation, and the
lower-resolution preference for the extra pane. The existing guide, cache, recording,
and adaptive-HLS tests remain part of the build.
It also checks the fail-closed recording guard, filtered focus restoration, the terminal
audio-router shutdown, and multiview learning isolation. `SplitLoadControlTest` checks
the actual Media3 load-control policy before/after promotion.
`.31` adds regressions for the controls wake press/release tail, whole-frame expansion
even in Fill mode, programme-boundary rollover, empty-ID and sibling-variant fallback,
hidden-channel exclusion, and provider-isolated logical guide keys.
`.32` adds `SplitPlayerViewTest`, checking the actual inflated TextureView, native
clipping, disabled native controls, exact half-screen viewport in Zoom, and
whole-screen viewport in Fit.
`.33` adds `PlayerRemotePolicyTest`, covering repeated directional menu requests,
delegation to open pickers, dedicated channel keys, and untouched nonvertical controls.

If OneDrive makes generated files unreadable to Gradle, keep the source in place and
redirect only disposable build outputs to a fresh local temporary directory:

```powershell
$env:OPENTV_OFFLINE_BUILD_DIR = Join-Path $env:TEMP ('opentv-offline-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $env:OPENTV_OFFLINE_BUILD_DIR
.\gradlew.bat --offline --no-configuration-cache --no-watch-fs --init-script 'scripts\offline-output.init.gradle' test lintVitalRelease assembleRelease
```

These checks validate code and packaging. They cannot establish actual two-decoder
performance, provider connection allowance, or physical remote focus on this Shield.

## October 3 live results (partial)

- Installed with the same signer and app UID, retaining the provider and guide.
  Pre-install `/data` available space was 1,157,192 KiB. No data or cache was cleared.
- Remote-equivalent ADB D-pad/Select inputs opened split screen and its local picker.
  Fox Business Network and ABC EAST both displayed live video.
- AudioFlinger showed the original audio session 713 at 0 dB and secondary session
  721 at negative infinity with Left audio selected. Right audio reversed those
  gains. Moving focus to the other picture did not change the audible session.
- Expanding either picture and collapsing with Back preserved both audio session
  identities. Expansion selected that picture's audio and muted the other.
- Exit split screen removed the secondary audio track and retained the original
  session 713 at 0 dB. No new primary tune was logged during expansion/collapse/exit.
- App PSS during dual playback was about 193 MiB. The primary feed had repeated
  rebuffer events in this run, including before the second stream started; this
  test does not establish sustained smoothness or the cause of those stalls.
- An unexpected outer-rail focus / Leave dialog was observed during the subsequent
  guide navigation. It needs an isolated physical-remote reproduction before a
  cause is assigned. The user took over testing, and automated input stopped.
- Keep-right promotion, repeated entry/exit, scheduled recording, PiP/Home/Plex,
  codec stress, and physical-remote ergonomics remain unverified in this live run.
  An audible listening confirmation was requested; audio telemetry is not a claim
  that the tester could hear the TV directly.

Screenshots and a credential-free report are saved in the workspace under
`test-results/split-screen-live-2026-10-03/`. The test paused with the user in control;
do not send remote inputs while the user is testing or watching unless requested.

## October 4 live results (partial)

- CBS EAST and ABC EAST both advanced in dual playback. In Fill mode the corrected
  `.32` screenshot shows the division at x=960 on a 1920x1080 screen, with each
  picture occupying its half and no reserved bottom strip or video overflow.
- Both left and right expansion displayed a whole-screen picture with decorations
  hidden. Back collapsed without replacing either audio session. The right pane's
  accessibility focus bounds were exactly 1920x1080; the left pane's semantics
  reported width 1872 despite visually filling the screen. That semantics discrepancy
  is not evidence of unequal video geometry, but remains worth checking with the remote.
- Idle controls hid after five seconds. A wake Select did not expand the picture;
  a subsequent deliberate Select did. Bright action focus and a separate selected
  picture outline were visible. All-channel picker rows showed cached programmes
  and local times. Initial blank CBS guide data populated after cold-cache loading.
- Primary session 1009 and secondary session 1017 persisted during expansion/collapse.
  AudioFlinger gains were 0 dB for the expanded pane and negative infinity for the
  other. Exit removed the secondary track and retained original session 1009 at
  0 dB with no new primary tune. These are telemetry checks, not direct listening.
- CBS EAST was restored to ordinary full-screen playback and automated inputs stopped.
  Only the two disposable UI-dump/screenshot files created on the Shield were removed.
- Final measured free space was 1,069,824 KiB. Existing credential-safe repository
  telemetry reported 247,736 cached programmes, above the documented 200,000 total
  cap. This is a separate follow-up concern; no guide refresh, deletion, storage-policy
  change, or data clear was forced during playback.

Evidence: parent workspace `test-results/split-screen-live-2026-10-04/RESULTS.md`.

## Remaining live checklist

The October 4 remote-equivalent checks cover the reported layout bug, both expansions,
action/picture decoration, idle hide/wake, and All-channel programme rows. Physical
remote ergonomics and programme rows in Favourites/other categories still need testing.
The broader checks below remain pending unless explicitly covered above.

1. Install in place with app data preserved; confirm adequate free storage first.
2. Start a channel, open split screen, choose a second from another category (for
   example SPORTS, LOCALS, and MLB), and confirm both videos advance.
3. Switch audio repeatedly and expand each picture, confirming no mixed audio and
   no stream reopen on expansion/collapse. Change the second channel repeatedly.
   Verify focus movement alone leaves audio unchanged. Enter split screen from a
   paused original channel. Use Keep full-screen for both panes, then Back to the
   guide and select another channel; verify correct row/variant controls and no retune
   during promotion. Test Keep immediately after changing the second channel.
4. Test picker search, favourites, held Up/Down, no keyboard on initial focus, Back
   dismissal, and exit-to-player focus.
   Reopen the picker to confirm retained filters/scroll/focus; filter to zero results
   and remove a focused row. Test lookup failure and an expired temporary-link retry.
5. Exit split screen and return to the guide; verify the original stream stays audible
   in the preview and the correct original row receives focus.
6. Test Netflix to SmartTube/PiP and back, then Home and Plex. Confirm the extra
   decoder/connection closes and neither split audio nor a hidden player leaks.
7. Observe sustained playback with representative codecs/resolutions and simultaneous
   scheduled recording: the second pane must close without canceling the recording,
   and should not reopen automatically afterwards. Record dropped frames, rebuffers, decoder
   errors, memory use, and connection counts without retaining private provider URLs.

Do not install, launch, or send remote input while the user is watching.
