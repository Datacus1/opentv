# Cached-first guide

## Verified release

`.53-cached-first-guide` (versionCode72), application source07a5acf, was installed
in place on October10 with the original UID/data retained. Both full test variants
passed:662 tests passed/four opt-in skips, plus release lint/R8/shrinking/package
and signature checks. The saved6,581-channel fixture retained all6,445 safe groups,
focus keys and guide choices. Visual live samples passed in All/Entertainment/
Sports/Locals, including preview and settled full-screen/Back navigation; the user
accepted testing and asked to stop. No further device commands were sent.

APK SHA256: `931F34982127F445DEE8BAEEA79EA4D64F38299DF93FDF345EB4379612949CDB`.
Saved workspace recovery APK:
`downloads/OpenTV/opentv-v0.11.7-commercial-break.53-cached-first-guide.apk`.
Initial app-bootstrap splash remains; this change replaces the guide-row loader,
not every startup screen. Audible audio, physical remote, reboot, zero-frame
handoff and exhaustive live broadcast accuracy were not established by these
tests. No post-stop storage/identity audit was run. Earlier .51/.52 artifacts remain
available locally for recovery. Voice testing remains paused.

## Design

The guide prepares selectable rows from the saved channel catalogue before waiting
for the shared Room programme window. A compact, non-focusable notice replaces the
former whole-guide loader. This is not an HTTP sync and does not add disk storage.

`GuideRowPlan` prepares the strict source/region/approved-identity keys once per
channel snapshot. Programme updates populate copies with identical keys and
variants, preserving focus. Existing visible listings remain during a replacement
query; they are reused across a return to the same view only when all channel
metadata and order are unchanged. A changed filter clears the old scope, and
`flatMapLatest` plus cooperative cancellation prevents obsolete results returning.
Unknown category metadata is treated as an empty scope, never All channels.

Loaded-empty and failed are separate from loading. Programme failures retain
selectable saved channels and report the failure without an endless spinner.
Search/manager consumers share the same row plan without adding a programme query.
An already-open held-select menu receives updated listings without changing its
release guard or selecting an action automatically.

No identity rules, provider credentials, guide ingestion, reserve thresholds,
retention, schema, player ownership, Netflix mapping or boot scripts change.
Saved-channel reading and identity preparation still take finite time; the UI does
not promise instant startup or manufacture listings for genuinely empty identities.

Regression tests use virtual delayed queries, empty scopes, errors, cancellation,
filter replacement, stable focus keys, unchanged-view seeds and the opt-in saved
6,581-channel metadata fixture. See the workspace
`test-results/cached-first-guide-2026-10-10/RESULTS.md` for actual release and live
verification, including its limitations.
