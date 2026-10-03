# Two-channel split screen

Offline candidate: `0.11.7-commercial-break.30-split-hardened`, versionCode 49.
Development branch: `codex/two-channel-split-screen`. This candidate has not been
installed or tested on the Shield. The September 27 `.28` provider-guide changes
are retained in its source; FOX East's on-device guide verification remains pending.

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

If OneDrive makes generated files unreadable to Gradle, keep the source in place and
redirect only disposable build outputs to a fresh local temporary directory:

```powershell
$env:OPENTV_OFFLINE_BUILD_DIR = Join-Path $env:TEMP ('opentv-offline-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $env:OPENTV_OFFLINE_BUILD_DIR
.\gradlew.bat --offline --no-configuration-cache --no-watch-fs --init-script 'scripts\offline-output.init.gradle' test lintVitalRelease assembleRelease
```

These checks validate code and packaging. They cannot establish actual two-decoder
performance, provider connection allowance, or physical remote focus on this Shield.

## Live testing when the user is ready

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
