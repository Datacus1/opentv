# Explicit live guide diagnostics

These are diagnostic-only tools, not automatic guide repairs. Obtain a suitable
live window before installing, launching or sending TV input. Never uninstall or
clear data. Preserve signed rollback APKs. Do not interrupt Plex playback.
Current device state and actual findings belong in the Shield runbook, not here.

## Protected metadata snapshot (.47+)

`scripts/audit-guide-identities.ps1 -IncludeCoverage -OutputPath <new-report>`
collects one immutable channel/alias/coverage snapshot. Coverage is feed-qualified,
independent of aliases, capped at20,000 identities, with original total and explicit
completeness. SHA256 fingerprints retain exact raw identity despite redacted labels.
No provider credentials, URLs, programme bodies or category IDs are exported.

## Exact-stream short guide (.48+)

`scripts/probe-guide-streams.ps1 -ChannelIds 2124,2125,2126,2127 -OutputPath <new-report>`
explicitly starts one four-channel maximum asynchronous job. Channel/source/stream
resolution stays on-device. Enabled Xtream source and enabled own-provider feed
are required; callers cannot supply URLs or credentials. Four sequential requests:
3s connect/5s read/10s call, no HTTP retries,45s outer deadline. Response limit512KiB
after HTTP content decoding, JSON nesting64, advisory panel limit20.

DUMP permission protects START/POLL/CANCEL. START acknowledges before DB/HTTP,
never holds a foreground broadcast open. Cancellation cancels the owned OkHttp call
and closes its response. One process-local session, five-minute read expiry;
process loss means UNKNOWN_TOKEN, not completion. COMPLETE means orchestration
finished: inspect each row's status and counts. Serialized defaults were omitted
in .48 (`counts:{}` means COMPLETE withzero rows); .49+ includes explicit defaults.

Counts distinguish invalid objects/time/span/title, duplicate starts, accepted,
current/future/past and retention-window usability. Overflow times are explicitly
unsafe, not usable schedules. Positive short data proves only that exact stream's
endpoint returned usable listings at that time—not bulk absence or broadcast accuracy.
Empty short data proves neither absence from all sources nor an importer defect.

## Bulk dry run (.49+, allowance increased in .50)

`scripts/probe-guide-feed.ps1 -FeedId 6 -OutputPath <new-report>` explicitly scans
one enabled feed without guide writes, matching, cache reclaim, manual refresh,
schema/settings changes, file staging or automatic retries. Public/provider URLs
remain internal. Keep OpenTV foreground if Android retires the background process;
UNKNOWN_TOKEN or incomplete results must not be interpreted as absence.

One DUMP-protected process-local job: immediate START, POLL pages<=100, CANCEL;
55s job deadline,3s connect/8s read/50s owned call deadline, no HTTP retries.
Cancellation watchdog closes blocking execute/sniff/body reads, and cancellation-
induced IOException is converted back to coroutine cancellation. Reports expire
automatically afterfive minutes with generation guards; new starts cancel old expiry.
Cancel affects only RUNNING scans. Process death loses evidence, not user data.

Limits:256MiB delivered transport stream (after any OkHttp content decoding),
256MiB decompressed XML,45s cooperative parser checks,40,000 identities,
1,000,000 programmes and production fair-share selector<=200,000 keys/20,000 channels.
.49 initially had64MiB transport/128MiB XML; its partial response hit the transport
cap. Increasing streamed bytes in .50 does not increase stored guide/cache caps.

The diagnostic-only256KiB delivered-byte interval between completed channel/
programme callbacks bounds giant XML text/attributes/unknown sections, plus reader
lookahead. It stops with RECORD_LIMIT; normal parser/ingestion behavior is unchanged.
DOCDECL processing is disabled for diagnostics; custom-entity expansion is rejected.
Normal timestamp/row/selection rules remain those of production. No full description
or XML is retained after each row. Stream SHA256 covers delivered decompressed bytes:
it is a prefix digest on partial results, a full digest only on COMPLETE.

Metadata includes fixed capture time, prior bulk success, raw/parser/window/selector
counts, exact ID fingerprints and explicit completeness/transport-limit flag.
FINISHED means a report exists, not that parsing completed. Transport limits surface
as PARSE_ERROR plus transportLimitReached=true. Never infer absence from partial zeros.
Even COMPLETE raw-present/cache-empty can reflect changed feeds/times: compare
provenance/anchors and alternate IDs before blaming import. No two-pass write replay
or actual broadcast accuracy is established. Never copy a city sibling's schedule.

## Checks

Regression tests cover normal row rules, response/memory caps, gzip inflation,
custom entities, blocked-read cancellation/body closure, pure session immediate ACK,
busy/invalid/token/process-loss/deadline handling, paging, automatic expiry and
generation guards. Real Room/fake HTTP tests prove channels/aliases/programmes/feed
settings survive read-only probes, including critically low simulated storage.
Collector reports use CREATE_NEW; existing reports are not overwritten. All outputs
are allowlisted metadata; no HTTP body, source model, raw headers or exception message.
