# Incident bundles

## Command contract

`/ig export <filename> <filters>` writes a redacted JSON bundle to
`<world>/itemgraph/exports/<filename>.json`. `filename` is 1–48 ASCII letters,
digits, `_`, or `-`; the command adds `.json` and refuses to overwrite a file.
Filters use the `/ig lookup` `name.value` grammar and require `radius`; the
player's current dimension and position are captured before asynchronous work
starts. At most 100 observed evidence rows and 100 linked inferred edges are
written (200 chain records total), and the finished file may not exceed 4 MiB.
Canonical record bytes are counted as records are built; export stops at the
byte ceiling before adding another record to the in-memory JSON array. The full
manifest and envelope are checked against 4 MiB before any file is published.

`/ig export full <filename> <filters>` uses the same bounds and requires command
permission level 4. `/ig export verify <filename>` verifies a bundle in the
same directory. `/ig export cancel <jobId>` cancels an active export or verify
job; the issuing player or a level-4 operator may cancel it. At most four such
jobs may be active. Database work and file verification run on the bounded
query worker, and command replies return to the server thread.

## Schema version 1

The UTF-8 JSON envelope contains `manifest`, `manifest_sha256`, and ordered
`records`. Each record contains `payload`, `payload_sha256`, `previous_hash`,
and `chain_hash`. The payload's `record_type` is either `OBSERVED_EVIDENCE` or
`INFERRED_EDGE`; the type is included in the payload hash. The manifest records
the normalized query, radius, time window, redaction profile, record counts,
truncation status, payload hashes, genesis hash, final hash, and schema version.
The full profile also records the captured dimension and center; redacted
manifests leave those fields null and replace identity/item filter values with
boolean indicators that such filters were applied.

The chain uses SHA-256:

```text
payload_sha256 = SHA-256(canonical UTF-8 JSON payload)
chain_hash     = SHA-256(previous_hash_hex || payload_sha256_hex)
```

The genesis value is 64 zeroes. Hash inputs use compact Gson JSON with HTML
escaping disabled and the schema's stable field insertion order. Redaction
rules are deterministic; `exported_at` is generated at export time, so separate
exports of the same incident are not byte-for-byte identical. The verifier checks the manifest
hash, payload hashes, predecessor links, record order, declared record types
and counts, chain hashes, and final hash. It reports malformed, modified,
reordered, missing, and extra records as invalid.
The verifier first decodes strict UTF-8 and parses the complete JSON structure
with duplicate-key rejection at every object depth and a maximum nesting depth
of 64. This prevents different JSON consumers from assigning different values
to the same hashed payload and bounds parser recursion for malformed bundles.

These hashes detect later changes when the manifest or final hash is retained
in a trusted place. They do not authenticate who created a bundle: an editor
who can rewrite the entire file can recompute the unkeyed hashes. Version 1 has
no signature or external timestamp authority.

Export writes a temporary file, takes an operating-system lock for the target
filename, rejects an existing output, atomically closes the cancellation gate,
then publishes the complete file with an atomic no-replace hard link. Export
fails without creating the destination if that filesystem does not support hard
links. The hidden
zero-byte `.filename.json.lock` sidecar is retained because deleting a lock file
after releasing it can let another process lock a different file object at the
same path. A crashed process releases its OS lock automatically; the harmless
sidecar can be reused. A completed bundle is never reported as cancelled after
the cancellation gate has closed. Temporary-file cleanup failure after the hard
link is published is logged as a warning while the valid destination is still
reported as committed.

## Evidence and privacy

Raw rows use record type `OBSERVED_EVIDENCE`; their `evidence_class` is copied
from the source and can itself be `UNRESOLVED` or unclassified. Inferred edges
use record type `INFERRED_EDGE` and carry `evidence_class=INFERRED`. Each edge
payload carries its stored confidence, amount, time span,
state, origin/destination node IDs, supporting observation IDs, and whether the
evidence list was truncated. The redacted profile removes player labels,
coordinates, owner UUIDs, external keys, item IDs, custom names, fingerprint
hashes, raw detail, and GriefLogger source keys. It retains database/source
hashes, table names, bundle-local evidence/node/fingerprint references, and
scoring factors. Supporting observations not included in the top-level filtered
page are embedded inside the inferred-edge payload, so the recorded evidence
relationship remains reviewable. Bundle-local references preserve relationships
within one export without exposing ItemGraph's sequential database IDs. The
redacted payload also retains privacy-safe structured scoring factors parsed
from the stored explanation: allocation and residual quantities, correlation
window, candidate counts, competing-candidate time gaps, and confidence
multipliers. Names, coordinates, and UUIDs are not copied into these fields.
Candidate IDs use bundle-local `evidence#N` references when included as top-level
records and `support#N` when included inside an inferred-edge payload. Otherwise
the redacted payload uses `external-candidate#N`, an opaque reference to an
observation outside the bundle with no observation payload included. Full
exports retain exact `observation#ID` candidate references.

The full profile includes the stored explanation and exact identity/location
fields. The explanation is read from the persisted inference row; it is never
recomputed during export. `source_raw_payload_sha256` hashes retained GriefLogger
raw-byte payloads and native ItemGraph observation/audit `raw_data` blobs when
present. It is not a hash of every normalized source row. The manifest's
`payload_sha256` independently commits to every normalized exported record,
including rows without retained raw payload bytes. Native observations and
audit rows include a raw payload hash only when their `raw_data` is present;
transformation rows do not have a raw payload hash in schema version 1.

Version 1 exports rows returned by the bounded unified lookup. Ambiguous and
unresolved classifications use the shared query/export evidence vocabulary from
issue #44. Missing observation fingerprints export as
`ITEM_FINGERPRINT_UNRESOLVED` with zero quantity impact. For inferred edges,
`candidate_evidence_available` distinguishes a known empty candidate set from a
legacy edge whose competing candidates were not persisted; the latter reports
`CORRELATION_CANDIDATES_UNAVAILABLE`. A bounded
export that reaches its evidence or edge cap reports its counts and edge
truncation in the manifest rather than implying completeness.

## Prior art comparison

CoreProtect's published lookup accepts user, time, radius, action, include, and
exclude filters, and its API advises callers to run synchronous database
lookups asynchronously. ItemGraph reuses the useful bounded-filter and
off-thread-query patterns. CoreProtect documents lookup and rollback/restore,
but its public command/API documentation does not define a redacted
tamper-evident incident bundle. ItemGraph adds an explicit evidence/inference
distinction, source hashes, redaction, a versioned manifest, and a verifiable
hash chain; this remains an ItemGraph extension rather than a compatibility
claim.

References: [CoreProtect commands](https://docs.coreprotect.net/commands/),
[CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/).
