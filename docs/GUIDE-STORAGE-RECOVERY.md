# Guide storage recovery (.39 / .40 / .41)

This candidate is based on installed `.38` / source `4096400`, not the uninstalled
`.37` voice build. It does not change players, Netflix mappings, boot scripts,
SmartTube, Termux, provider credentials or database schema 12.

## Policy

- Start guide downloads/ingestion only with **1.25 GiB physically free**. Stop ingestion below
  **1.10 GiB**. Unknown storage measurements fail closed. Android's allocation
  estimate is never treated as actual free space.
- When idle and below the starting reserve, use Coil's own disk-cache owner to
  clear only disposable images, then ask Android (API 26+) to reclaim eligible
  caches using `StorageManager.allocateBytes(UUID, min(totalReserve, allocatable))`.
  `.40` also attempts a smaller positive platform allowance instead of skipping
  all reclamation when it cannot satisfy the full goal. No large
  reservation file is created. Recheck physical free space afterwards.
- Attempts are throttled hourly with one small timestamp outside the guide DB.
  Ordinary failures are contained and logged by class only; cancellation propagates.
  No app data, provider settings, recordings or arbitrary cache directories are cleared.
- Keep one past day and two future days. The guide may browse today/tomorrow.
  Cleanup is independent of network/download success, runs while idle every
  30 minutes, and uses a shared maximum of **eight 1,000-row transactions** per pass.
  Pending deleted-feed tombstones resume offline. Idle/storage guards are rechecked
  between batches and before atomic alias/feed finalization.
- `.41` fixes the recovery deadlock where low space blocked expiry maintenance too.
  Below the download reserve, idle expired-only cleanup requires the unchanged hard
  floor plus **32 MiB headroom**, with **four 100-row transactions** maximum per
  invocation. Check idle state then remeasure free space before each batch. Only
  programmes older than the one-past-day retention are retired; no current rows,
  feed metadata, aliases or settings are reconciled by this recovery tier.
  Idle WAL truncation no longer requires the download reserve, but both passive
  and idle checkpoints reserve logical database extension plus 32 MiB working space.
  Below hard floor nothing in this tier mutates the database; more disposable
  storage may still be necessary. A 400-row pass is deliberately small, not instant
  bulk repair. Healthy-reserve maintenance retains its larger eight-batch budget.
- SQL deletion makes database pages reusable; it does **not** promise that the main
  database shrinks on disk. No `VACUUM`, unbounded programme deletion or data reset.

## Fair ingestion and hard row budgets

XMLTV is streamed twice. First pass keeps compact identities/times, not complete
programmes, descriptions, a saved playlist/XML file or a second staging database.
Within the per-feed selection budget, reserve a current and nearest-next identity
for up to `min(20,000, budget/2)` guide channels; remaining slots prioritize nearby
future listings over history. One-row test budgets prefer current playback.
Programme natural keys are deduplicated. The per-feed selection budget is shared
conservatively across enabled feeds instead of letting the first feed consume all
200,000 rows.

The second pass writes only selected identities in small batches. All selected
identities must be seen before aliases/success metadata can be committed as a
complete replacement. Changed, empty, malformed, failed or canceled feeds retain
the working cache; partial updates may remain, but stale-cache reconciliation is
not performed on an incomplete/truncated snapshot.

Before writes, indexed natural-key queries count which identities already exist.
Queries use at most 250 complete key predicates / 750 bind parameters, including
feed ID in each indexed term. New identities may not grow the cache beyond
**150,000 rows/feed or 200,000 total**, including disabled feeds. Existing identities
can still be updated at capacity. Verified FOX East short-EPG fallback observes
the same limits. At capacity, new listings wait for idle maintenance; current
cache is not erased to force room.

Costs/limits: two streamed HTTP requests use extra bandwidth and parsing time.
Selection and alias counts are bounded; feeds beyond those limits cannot promise
coverage for every guide ID. Actual Shield peak memory, decoder interaction and
latency remain live-test requirements.

## Retry and truthful status

Guide-only retries survive process death/reboot through WorkManager, coalesce
with KEEP, require connectivity/storage-not-low, and use 15-minute initial delay
and exponential backoff. Every run also checks our higher reserve. Separate
maintenance has no network or Android-low-storage constraint and never writes
below the repository's guards. A detected blocked-to-ready transition queues a
separate immediate constrained retry without canceling an active request.
Android may delay background work; this is not a real-time scheduling guarantee.

Catalog imports are guarded before growth. Storage/failure notices are display-only
and replace empty-listing placeholders, not valid cached programme blocks. A
failed feed in cooldown does not falsely clear the warning. Short-guide fallback
errors/cap deferrals request retries, while a successful alternate quality does not
leave a false failure warning. Cancellation clears only the active-refresh state.

## Every-channel diagnostics

An ADB/system-only `android.permission.DUMP`-protected broadcast returns safe
metadata, not Channel/Source/EpgFeed objects. It does not expose URLs, credentials,
headers, stream IDs, programme descriptions, feed bodies or exception text.
URI-shaped accidental names/IDs are redacted. There is no new HTTP listener.

After an approved install, request the first ordered broadcast:

```text
adb -s 192.168.1.186:5555 shell am broadcast -a app.opentv.GUIDE_AUDIT -n app.opentv/app.opentv.data.repo.GuideAuditReceiver --ei offset 0 --ei limit 200
```

Subsequent requests add `--es snapshot <returned-token>` and advance offset by
200 until the returned total is reached. Each immutable in-memory snapshot expires
after two minutes; start again if expired rather than joining inconsistent pages.
Diagnostics have an eight-second timeout. Do not export the app database or provider
models as a shortcut. A release receiver's DUMP permission must be checked from
the packaged manifest before use.

`.39` timed out on the real catalog. `.40` takes a short consistent Room snapshot
without waiting behind the entire network-sync mutex, then classifies outside the
transaction. Prefix lookup is indexed; quality-variant hits and misses are cached,
CPU phases observe cancellation, and pagination copies only its requested rows.
Numeric-only snapshot/classification timings aid hardware verification. These do
not expose provider URLs, identities or exception text. A Room read transaction
can briefly serialize with a database writer; this is not a lock-free snapshot.

Every raw channel/quality variant is classified: current listing, upcoming-only,
expired cache, useful name match not applied, disabled feed, ambiguous, unresolved.
Logical sibling coverage is separate so duplicate quality variants do not falsely
say a logical channel has no guide. This audit anchors a 12-hour live window, not
a user's manually scrolled historical timeline. **Unresolved is not proof that the
provider has no schedule.** Verify fresh feeds and conservative aliases before
calling a missing listing genuine. Main-guide selection now considers all quality
variants before UI deduplication and prefers a current listing, preserving populated
manual overrides. Semantic `+` and country/feed-revision distinctions remain guarded.

## Deployment and rollback

`.41-storage-diagnostics` versionCode 60 adds the above recovery gate and numeric-only
storage inspector, using the same DUMP-protected receiver:

```text
adb -s 192.168.1.186:5555 shell am broadcast -a app.opentv.STORAGE_AUDIT -n app.opentv/.data.repo.GuideAuditReceiver --receiver-foreground
```

It reports actual DB/WAL size, page/freelist counts, trusted table row counts, anonymous
feed age counts and bounded owned directory sizes, not contents/paths/private settings.
Per-table allocated bytes are null if Android lacks `dbstat`. A first cold-process
request may return no data; don't treat that as an audit or combine invented results.
On October 8 `.41` was installed in place, UID/data preserved, with 474 offline tests
passed. Actual DB97.13MiB/WAL0.50MiB and247,736 programme rows (99.5% expired)
confirm stranded over-cap data. Older stored-row caps depended on post-import idle
maintenance; `.39/.40` pre-write guards already prevent further over-cap growth.
Final device free ~1.042GiB is still below hard; neither actual expiry retirement nor
successful fresh ingestion is proved. Root runbook and `storage-root-cause-41/RESULTS.md`
are authoritative for exact evidence, artifact hashes and unresolved whole-partition
space-loss attribution. This does not authorize other-app or recording deletion.

`.39-guide-recovery` versionCode 58 was installed October 8 after a fresh affirmative;
UID/data were retained, but low space still prevented refresh. `.40-guide-audit-fix`
versionCode 59 adds the audit/partial-reclamation follow-up. Consult the runbook for
whether this newer build is installed and its actual live results. Preserve `.38`
recovery APK and UID/data.
Do not deploy `.37`, merge voice changes, uninstall Termux, clear NVIDIA stats data,
delete settings or reboot to test this guide fix without additional authorization.
One-time removal of an unused temporary installer is distinct from automatic cache
reclamation and must be explicitly scoped/verified before deletion.

Use debug/release tests, release-critical lint, R8, resource shrinking and signature
verification before deployment. Offline/fake tests do not prove physical cache
reclamation, live guide coverage or a real remote/playback test. Record device free
space before/after, new crash logs, guide update outcome and a full paginated audit
only in an approved test window. Do not claim installation or live recovery based
on this document or an APK build alone.
