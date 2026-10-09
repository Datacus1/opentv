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
3. Exact normalized name or reviewed semantic alias in its own provider feed.
4. Exact normalized name or reviewed semantic alias in enabled public feeds.

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
