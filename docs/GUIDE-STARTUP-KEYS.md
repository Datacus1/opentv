# Build-local guide identity reuse

The first live-guide row build previously recalculated identity keys during initial
grouping, guide-candidate filtering and row construction. A large saved catalogue
therefore normalized the same channel names repeatedly. The existing "Loading ...
channels" status describes this local build, not a provider catalogue download.

`ChannelGrouping.PreparedKeys` computes the unchanged exact key once per input
channel object. Main-guide grouping and `chooseGuideCandidate` reuse those keys;
the latter still filters every variant against the primary's exact identity. A
copied/updated or otherwise unknown object falls back to the ordinary key function.
The index never inserts on a miss, is bounded by its input object set, and becomes
unreachable when that build finishes. There is no global cache, stored-channel
mutation, disk cache, schema change or time-based invalidation policy.

Each `ChannelsViewModel.Row` retains only its own `PreparedKey`, not the complete
index. The binding checks object identity before reuse, so `Row.copy(primary = ... )`
cannot carry a stale identity onto a changed channel. Structurally identical
bindings have equal values, preserving row equality across unchanged builds.

Source, region, exact accepted guide IDs, manual overrides, unknown/no-ID streams,
UHD distinctions, PLUS semantics and conflict quarantine are unchanged. The normal
three-argument candidate API remains available and uses the original uncached
path; the prepared overload changes only how identical keys are obtained.

`GuideStartupKeysTest` covers all boundaries above, same-ID replacement objects,
bounded misses, independent builds, mixed-identity rejection, stable quality ties,
manual/current/upcoming/past/empty guide selection and safe row copies/equality.
Set `OPENTV_GUIDE_IDENTITY_FIXTURE` to an allowlisted saved metadata snapshot to
enable exhaustive per-channel/group/key/candidate replay. Its seven-run alternating
PC benchmark includes index construction and compares identical output sequences;
there is deliberately no timing assertion that would make CI hardware-dependent.
Programme markers are synthetic. Do not provide credentials, raw provider models
or programme bodies to this fixture.

Offline timings do not establish Android/Room/Compose or actual Shield startup
latency, broadcast accuracy or future cache reliability. Deployment requires an
approved viewing window; offline implementation does not authorize installation.

## October 10 offline validation

Both debug and release suites discovered321 tests each:319 passed/2 optional tests
skipped each,638 passed/4 skipped combined,zero failures/errors. The opt-in catalogue
replay ran in both variants against all6,581 saved channel records/6,445 safe groups.
Every exact key, group membership, per-group guide choice at past/current/upcoming/
expired marker times, and row key matched the uncached path. The existing consumer
and identity replays also passed:3,674 accepted identities,zero unresolved borrowers.
The skipped tests require a supplied actual XMLTV file or a public YouTube request;
neither was needed or allowed for this offline change.

Seven-run alternating PC stage medians (index creation included):

| Variant | Uncached | Prepared keys |
| --- | ---: | ---: |
| Debug | 303.7426 ms | 86.3006 ms |
| Release test classes | 415.8446 ms | 119.5032 ms |

These cover grouping, synthetic guide-candidate selection and row-key construction,
not actual TV startup. Release tests execute JVM test classes, not the installed R8
APK. Differences between debug/release rows reflect separate PC runs and workloads;
do not interpret them as installed release/debug performance comparisons.

The full-page `HomeScreen.LoadingState` behavior was inspected but NOT modified.
It replaces the guide area when rows are empty; existing populated rows remain
usable during ordinary background updates. A cached-channel-first/progressive-guide
UI is a separate potential improvement, not part of this calculation optimization.
