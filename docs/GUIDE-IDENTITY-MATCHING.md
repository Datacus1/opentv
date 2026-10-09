# Trustworthy guide identities

The October 9, 2026 change separates **identity** from **schedule availability**.
A current programme is not evidence that it belongs to the selected channel.

## Root causes reproduced from the Shield catalogue

- The old global normalized index conflated literal XMLTV IDs and display names.
  A populated `CuriosityNOW.us` could be hidden by `Curiosity.NOW.us2` after lossy
  normalization. Literal, case-sensitive IDs now have a separate index; terminal
  country/feed-revision normalization is accepted only when its result is unique.
- Bidirectional prefix matching treated uniqueness as identity. It assigned USA
  Network to unrelated Arabic/Asian streams and German DAZN 1/2 to UK/Canadian
  event slots. Prefix matching is removed, not made more permissive.
- The provider reused IDs for unrelated channels. Examples include `COVID19`,
  `LogoTV.us`, `BBCOne.uk` and `TVA-CarletonSurMer-CHAU.ca`. An alias label may be
  merely the last victim of that reuse. Conflicts are detected within each source
  after quality normalization and reviewed name equivalence; the entire conflicted
  ID is quarantined, including name fallback through its alias.
- `PLUS` was stripped as stream decoration. It is now retained as channel identity.
- The UI tried unchecked raw provider IDs ahead of validated matches. Consumers
  now use the explicit manual override followed by the persisted matcher result.
  The raw ID remains preserved as input evidence, not a programme lookup shortcut.

## Matching order and boundaries

1. Literal or unique canonical provider ID in the channel's own provider feed.
2. Literal or unique canonical provider ID in enabled public feeds.
3. Exact request-group-qualified short-guide ID in its own provider feed (whitelist only).
4. Exact normalized name or reviewed semantic alias in its own provider feed.
5. Exact normalized name or reviewed semantic alias in enabled public feeds.

Other providers' aliases are not fallback candidates. Name fallback respects
country hints, Caribbean/Latin-American groups, numbered feeds, `PLUS`, direction,
and 4K event identity. A rejected ID cannot supply a country hint. California-style
`CA | city | station` is a U.S. local-station label, not a Canadian country prefix.
Exact declared IDs may identify foreign rebroadcast networks; this still depends
on provider metadata. No algorithm here verifies the actual broadcast content.

Conflicted IDs are deliberately quarantined globally after source-local conflict
detection. Programme consumers currently join by guide ID, not `(feedId, guideId)`;
allowing a clean feed with the same ID could still mix schedules. This can sacrifice
coverage. A future feed-qualified consumer migration is required before relaxing
that rule. Manual overrides remain explicit user choices and are not auto-deleted.

`runMatcher` recomputes from preserved raw names at startup and guide import, so
in-place upgrades do not require a provider reset or a catalogue refresh. Existing
storage/write guards still apply: below the soft reserve, recomputation is deferred.
Old stored matches can therefore persist until eligible; cold channels with no
validated match do not bypass the guard through their raw ID. The audit exposes
stored matches that disagree with the current resolver. No schema, retention,
row-cap, cleanup, player, Netflix-button or boot changes accompany this patch.

The on-device follow-up found eighteen raw whole-word `PLUS` channels still had
old saved grouping fields. Five collided with their base networks, and a word-PLUS
Fox Soccer variant did not group with its equivalent `+` variant. Matcher startup
now repairs only those raw whole-word-PLUS rows using the ordinary catalogue
normalizer (not the resolver's additional identity-prefix rules). One per-row
atomic update changes the derived display/group/quality fields and matched ID;
custom names, favourites, hidden flags, manual overrides, provider/playback fields
and IDs are preserved. Reserve checks apply before each actual write. Repeated
passes are idempotent; no catalogue refresh or schema migration is required.

Scoped indexes now reuse immutable, once-prepared normalized alias keys. Literal,
canonical and exact-name collision rules are unchanged. This addresses the first
hardware combined audit exceeding its eight-second deadline; the deadline is not
extended. The exhaustive replay compares every optimized decision with the saved
pre-optimization result. First-pass summary counts use the updated match, not a
withdrawn old match. The diagnostic script validates alias uniqueness case-sensitively
because distinct XMLTV IDs can differ only by case.

## Exhaustive metadata audit

`GuideAuditReceiver` requires Android's `DUMP` permission. The immutable, process-local
snapshots expire after two minutes; restart a failed audit rather than combining
pages from different snapshots. Maximum page size is 100. Diagnostic DTOs allow
only channel names, guide IDs, source/feed numeric IDs, match reasons and programme
counts; they exclude URLs, credentials, headers and programme contents. Names/IDs
are redacted, and names are redacted before normalization to avoid flattening URLs.

From the repository directory, use a local, non-versioned results path:

```powershell
.\scripts\audit-guide-channels.ps1 -OutputPath ..\test-results\coverage.json
.\scripts\audit-guide-identities.ps1 -OutputPath ..\test-results\identities.json
```

Only run device commands in an authorized window. To replay the safe identity
snapshot offline, set `OPENTV_GUIDE_IDENTITY_FIXTURE` to its absolute path and
optionally `OPENTV_GUIDE_IDENTITY_REPORT` to a local output path, then run
`testDebugUnitTest testReleaseUnitTest`. The opt-in replay otherwise skips. Do not
commit the user's actual catalogue snapshot or export the provider/database models.
Set `OPENTV_GUIDE_IDENTITY_BASELINE` to a previous replay report to assert complete
case-sensitive decision equality while testing a performance-only change.

The actual 6,581-variant replay accepted 3,674 identities, quarantined 372 conflicts,
and left 2,535 without a populated trustworthy identity. Compared with stored
matches: 104 gained a match, 37 changed IDs and 450 lost an unsafe match. An
independent reviewer checked every decision and individually reviewed all 107
public-ID/name/alias acceptances. This is metadata-level review, not certification
of 6,581 broadcasts. Many unresolved IDs have no imported usable-window rows;
that does not prove the upstream provider has no schedule. Leave them unresolved
until trustworthy evidence is available instead of attaching a plausible schedule.

## Safe logical-channel consumers (.46 offline candidate)

The later offline audit found a second boundary: correct individual matches could
still be merged by bare `groupKey` in the guide and split picker. US/Australian ESPN
collapsed, and quarantined Caribbean Cartoon Network borrowed the US schedule.
This was a consumer identity bug, not evidence of cache overwrite failure.

`ChannelGrouping` is now the shared runtime policy for guide rows/keys, split choices
and listings, quality variants, audit availability, and recording provider selection:

- UI groups include source, reviewed semantic name, explicit region/tag, exact
  accepted match and manual override. Country/feed-revision/case variants of IDs
  are not newly equated here; only IDs already resolved to the same literal identity
  can merge. Different IDs may be legitimate equivalents, but require additional
  verified evidence before merging. This deliberately can show more rows.
- Unmatched channels cannot share an approved schedule. Unknown/no-ID streams
  remain individually reachable by stable channel ID; exact declared-ID twins can
  group only within their own source/name/region/UHD boundary and have no listing
  until an identity is accepted. Numbered, PLUS, direction and UHD event distinctions
  remain conservative. User renames do not affect identity.
- A mixed-list call to `chooseGuideCandidate` fails closed to the primary channel's
  group. Populated manual overrides remain authoritative; an empty override may
  fall back only to that same channel's approved match. Split listings follow this
  policy rather than an independent candidate-walking implementation.
- Candidate SQL fetches a superset by old name key or exact guide/provider ID;
  shared in-memory filtering is mandatory. This includes reviewed aliases whose
  old stored keys differ. Blank IDs do not broaden the query. Cross-provider
  recording intentionally ignores source only when both full approved identities
  agree; an unresolved or foreign-region alternate is never substituted.
- Rows retain all genuine category/quality duplicates for Back focus and favourite/
  hide updates, while quality menus still deduplicate identical quality signatures.
  Lazy-list keys are saveable strings, computed once per row. Audit preparation
  reuses operation-local normalized names; no mutable global cache is added.

Short-guide requests use an independent source/name/region/declared-ID/UHD key,
including stable channel ID when no declared ID exists. SHA-256-qualified synthetic
IDs stay stable across matcher/override updates; aliases retain raw regional names.
Legacy and new synthetic aliases are excluded from ordinary name fallback. Only
the exact populated request-group identity from the same source can be accepted,
preventing another opaque sibling's successful request from lighting up this one.
The resolver decision cache includes the complete short key. Legacy rows remain
readable through existing persisted matches until recomputation and age under the
ordinary retention policy; no migration delete or unbounded cleanup is added.
Catalogue churn cannot accumulate new aliases without limit: the existing
20,000-alias/feed cap is checked before short-guide HTTP and again atomically
before programme/alias writes. Exact existing `(feedId, epgId)` updates still work
at or above the cap. New identities defer with the existing bounded retry policy;
no aliases or working listings are deleted to make room. Other feeds' aliases do
not consume this feed's budget.

No schema, channel IDs, user-field migration, reserves, retention/caps, player,
Netflix/boot or provider configuration change is needed. The installed .45 remains
unchanged until a freshly authorized in-place update. .46 is based on .45/.42/.38,
not the uninstalled .37 voice candidate.

Offline validation: 560 tests passed across debug/release, 2 opt-in tests skipped;
release-critical lint, R8 and packaging passed. An independent reviewer checked
all 6,581 saved matcher decisions against the baseline (zero changes), all consumer
results (6,445 groups; zero unresolved borrowers/stale disagreements), US/AU ESPN,
Caribbean rejection, short-guide provenance and recording fallback. Real Room/fake
HTTP tests cover legacy-cache coexistence, opaque siblings, region separation,
candidate superset queries and actual simulated recording-clash scheduling.
Repeated request-identity churn stops at the unchanged alias cap with zero HTTP
for new identities; existing identities refresh at/above it, and caps are per feed.

`ChannelGroupingTest` replays the same safe local fixture as the matcher. Optional
`OPENTV_GUIDE_GROUPING_REPORT` writes only the metadata consumer result. Actual
catalogue JSON remains outside Git. These are historical metadata/synthetic-marker
checks, not live broadcasts, per-category completeness, physical navigation/audio,
or Shield latency. The unchanged eight-second diagnostic deadline still needs a
live check; PC timings are not Shield timings. Fresh readiness is required before
installing, launching, input or otherwise interrupting the device.
