# Two-channel split screen

Offline candidate: `0.11.7-commercial-break.29-split-screen`, versionCode 48.
Development branch: `codex/two-channel-split-screen`. This candidate has not been
installed or tested on the Shield. The September 27 `.28` provider-guide changes
are retained in its source; FOX East's on-device guide verification remains pending.

## Controls

1. Watch a channel normally, reveal the player controls, and choose **Split screen**.
2. Choose **Choose second channel**. Browse All channels, Favourites, or a category,
   or search the saved channel catalogue. This can select a channel from another guide tab.
3. Use **Left audio** / **Right audio** to switch sound. Focusing a video pane also
   chooses its audio. An **Audio** label identifies the audible pane.
4. Select a picture or choose **Expand picture** to expand it; **Two pictures** or
   Back restores the two panes. Expansion keeps both players and their buffers alive.
5. **Change second channel** retunes only the second player.
6. **Exit split screen** releases the second player and returns to the original channel.
   Back from the two-picture view returns to the guide with that original channel
   selected and playing in the existing guide preview. Back from an expanded picture
   first restores the two-picture view. Back in the picker dismisses only the picker.

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
- The extra player disables DVR and tunneling, prefers a <=1080p channel variant,
  applies a 1080p adaptive-track preference, and uses a 32 MiB allocator target.
  A provider with only a fixed 4K stream cannot be downscaled at download/decode time
  by that preference; actual concurrent decoding capacity needs the Shield test.
- Both streams keep the existing adaptive HLS live-offset policy. No video disk cache,
  guide feed, database table, schema migration, or storage-retention change is introduced.
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

These checks validate code and packaging. They cannot establish actual two-decoder
performance, provider connection allowance, or physical remote focus on this Shield.

## Live testing when the user is ready

1. Install in place with app data preserved; confirm adequate free storage first.
2. Start a channel, open split screen, choose a second from another category (for
   example SPORTS, LOCALS, and MLB), and confirm both videos advance.
3. Switch audio repeatedly and expand each picture, confirming no mixed audio and
   no stream reopen on expansion/collapse. Change the second channel repeatedly.
4. Test picker search, favourites, held Up/Down, no keyboard on initial focus, Back
   dismissal, and exit-to-player focus.
5. Exit split screen and return to the guide; verify the original stream stays audible
   in the preview and the correct original row receives focus.
6. Test Netflix to SmartTube/PiP and back, then Home and Plex. Confirm the extra
   decoder/connection closes and neither split audio nor a hidden player leaks.
7. Observe sustained playback with representative codecs/resolutions and simultaneous
   recording only if the account permits it. Record dropped frames, rebuffers, decoder
   errors, memory use, and connection counts without retaining private provider URLs.

Do not install, launch, or send remote input while the user is watching.
