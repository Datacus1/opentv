# Offline XMLTV diagnostic

This is a PC-only, explicitly invoked supplied-file diagnostic, not a provider
fetcher or a schedule fix. It never starts an app, calls ADB, opens HTTP, writes
the guide database, stages another feed copy or changes matching. The installed
Shield .46 remains unchanged. No diagnostic APK is produced by the runner.

## Run on a local XMLTV file

Prerequisites: PowerShell7, the existing JDK17/Android build cache and cached
Robolectric SDK34 jar. Both Gradle and Robolectric are forced offline; missing
dependencies fail rather than download. Input/output/build/runtime paths must be
trusted, hydrated local paths. UNC/mapped network paths are rejected before input
lookup; leaf links/offline files are rejected. This is NOT an adversarial
filesystem sandbox against ancestor reparse points, races or cloud hydration.

From the repository, for example:

```powershell
.\scripts\audit-local-guide.ps1 `
  -XmlPath 'C:\guide\provider.xml.gz' `
  -OutputPath 'C:\guide\diagnostic-new.json' `
  -GuideTime '2026-10-09T18:00:00Z' `
  -FeedId 6 -EnabledFeedCount 3 `
  -GuideIds 'CBSWIAT.us','CWWTTO.us'
```

Use the time corresponding to the supplied feed, not an arbitrary current time.
EnabledFeedCount determines the production fair-share selection budget
`min(150000, 200000/enabledFeedCount)`. Existing output files are refused and the
test runner uses atomic CREATE_NEW, so even a raced existing report is not
overwritten. Input is never modified. The original XML remains the caller's file;
no duplicate XML or programme bodies are saved. `.gz` is decompressed as a stream,
with the byte budget applied to decompressed input. Environment/PATH are restored.

The opt-in local-file regression executes only when explicitly supplied
`OPENTV_DIAGNOSTIC_XML_FILE`, time anchor and new output path. It is skipped in
normal unit tests. Gradle recompiles/reruns deliberately because environment and
external fixture contents are not ordinary task cache keys.

## Report interpretation

- Exact full raw IDs have SHA256 fingerprints; case/whitespace are NOT merged.
  Display labels are link-redacted/clamped and `lossyLabel` says when changed.
- `productionFingerprint` refers to the production512-character-truncated ID.
  `truncatedProductionId` and `productionIdentityCollision` expose lossy collisions.
  Collision-flagged selectedProductionKeys belong to the shared production ID:
  never sum them across raw rows as though they were distinct selected programmes.
- Per-ID counters distinguish encountered, parser-accepted, missing/invalid start,
  blank channel, nonpositive span, missing/invalid defaulted stop, current/future/
  past, outside retention, offered and selected production keys. Programme titles,
  descriptions, URLs, raw timestamp strings and exception messages are absent.
- Requested IDs appear even if they have no rows. Absence is meaningful only for
  that supplied complete file at that anchor, NOT proof about another feed/time,
  actual broadcast, identity equivalence or server behavior.
- COMPLETE requires one closed, structurally valid XMLTV `tv` root. Empty input,
  wrong/prefixed roots, multiple/unclosed roots/channels/programmes and trailing
  garbage are not complete. Diagnostic document validation is stricter than
  ordinary ingestion; ordinary timestamp/row acceptance and EOF behavior remain
  unchanged. This is NOT a production parser hardening fix.
- COMPLETE with selectorTruncated=true means parsing finished but selection lost
  information. BYTE/IDENTITY/PROGRAMME/TIME_LIMIT and PARSE_ERROR are partial;
  partial zeros must never be described as absent upstream. Cancellation supplies
  a partial CANCELLED callback and still propagates cancellation to the caller.
- This is one pass through one supplied file, NOT a replay of two changing HTTP
  responses, Room writes or cleanup. Comparing against cached metadata requires
  matching feed provenance and timestamps; old cache differences alone are not
  proof of a defect.

Default bounds:128MiB decompressed delivered bytes,40,000 observed/requested raw
identities,1,000,000 programmes,30s cooperative checks,20,000 selector programme
identities. Byte EOF detection consumes at most one look-ahead byte. These are PC
diagnostic limits, not changes to Shield reserves/caps. A blocked stream read is
not preempted by cooperative time checks; the parser can still materialize an
individual XML text node. Do NOT wire this to network/Shield startup without a
separate review of transport timeouts, text-node memory and background lifecycle.

## Imported coverage visibility

The uninstalled source also extends protected immutable identity snapshots with
an opt-in `coverage` page: feed-qualified programme IDs/counts independent of
aliases, exact fingerprints, hasAlias, label loss and source/enable provenance.
Export is capped at20,000 coverage identities with original total and explicit
coverageComplete. The existing ADB script's `-IncludeCoverage` requires that
future source build and is NOT part of this offline runner. Installed .46 cannot
export those new pages. No device command is needed for synthetic tests here.
The existing120s immutable snapshot lifetime can expire during a very large export;
such collection fails rather than representing partial metadata as complete.

This closes an observability blind spot only. Missing WIAT/WTTO listings still
require actual provider evidence; synthetic diagnostic success does not establish
their root cause. Obtain fresh user readiness before any later device update.

## Offline validation and review, October9

Full debug/release:590 discovered,586 passed,4 opt-in skips,zero failures/errors;
release-critical lint passed. All6,581 saved identities replay unchanged:
3,674 accepted,zero stored disagreements,6,445 safe groups,zero unresolved
borrowers. Wrapper smoke test produced COMPLETE for a synthetic4-programme/
4-identity file and distinguished exact-ID no rows, case-different orphan,
invalid start and out-of-window rows. Report excludes programme bodies. Existing
report refusal preserves its SHA; mocked filesystem lookup confirms UNC refusal
precedes input lookup. Independent reviewer approved diagnostic only after EOF/
wrapped-cancellation/document-completeness and file-safety findings were resolved.

No Shield/ADB/provider fetch/input/install/refresh/cache cleanup occurred. No APK
was built/saved or deployed; source versionCode remains65 as an uninstalled
development change. A future deployment must use a distinct version and new
readiness, not overwrite the known-good .46 APK. Raw provider XMLTV evidence is
still unavailable locally, so the station-specific cause remains unproven.
