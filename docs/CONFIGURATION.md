# ItemGraph configuration reference

All ItemGraph settings are read while the server starts. Restart the server after
editing either the NeoForge server config or Fabric's
`config/itemgraph.properties`; `/reload` does not reload ItemGraph settings.
Invalid ItemGraph operational settings fail server startup with the key and
accepted value or range in the error. NeoForge validates the original TOML
value and fails startup on invalid types or ranges; it does not clamp invalid
database or correlation settings into a different accepted value.
Network database validation names the corresponding `general.*` key and never
includes the configured password in its error message. Network database logs
and connection-failure diagnostics omit the host, database name, username,
password, and raw JDBC exception text. SQL failures retain only the SQL state;
initialization logs also identify the selected backend.

## Common storage and capture settings

| ItemGraph setting | NeoForge TOML path | Fabric properties key | Type / default | Accepted values | Reload |
|---|---|---|---|---|---|
| Server message language | `general.language` | `language` | string / `en_us` | `en_us`, `nl_nl`, `zh_tw` | Restart |
| SQLite path | `general.database_path` | `database_path` | string / `itemgraph/itemgraph.db` | Non-empty path; relative paths resolve from the game directory | Restart |
| Storage backend | `general.database_backend` | `database_backend` | string / `sqlite` | `sqlite`, `mysql`, `mariadb`, `mysql_mariadb` | Restart |
| Network DB host | `general.database_host` | `database_host` | string / `127.0.0.1` | Required and non-blank for MySQL/MariaDB | Restart |
| Network DB port | `general.database_port` | `database_port` | integer / `3306` | `[1,65535]` | Restart |
| Network DB name | `general.database_name` | `database_name` | string / `itemgraph` | Required and non-blank for MySQL/MariaDB | Restart |
| Network DB username | `general.database_username` | `database_username` | string / `itemgraph` | Required and non-blank for MySQL/MariaDB | Restart |
| Network DB password | `general.database_password` | `database_password` | string / empty | Any string; never shown by `/ig status` | Restart |
| Network DB TLS mode | `general.database_ssl_mode` | `database_ssl_mode` | string / `disable` | `disable`, `trust`, `verify-ca`, `verify-full` | Restart |
| Network DB timeout | `general.database_connection_timeout_ms` | `database_connection_timeout_ms` | integer / `5000` | `[250,120000]` milliseconds | Restart |
| Optional indexes | `storage.use_indexes` | `use_indexes` | boolean / `true` | `true` or `false`; migration-owned unique and required foreign-key indexes remain | Restart |
| GriefLogger source integration | `general.grieflogger_integration_enabled` | `grieflogger_integration_enabled` | boolean / `false` | `false` keeps native-only operation and does not probe a source database; `true` enables read-only migration sync/import | Restart |
| GriefLogger source path | `general.grieflogger_database_path` | `grieflogger_database_path` | string / `database.db` | Required and non-empty when source integration is enabled; ignored in native-only mode; source remains read-only | Restart |
| Ground bridge window | `correlation.ground_bridge_max_seconds` | `ground_bridge_max_seconds` | integer / `300` | `[1,86400]` seconds | Restart |

## Operations and forensic retention

| ItemGraph setting | NeoForge TOML path | Fabric properties key | Type / default | Accepted values | Reload |
|---|---|---|---|---|---|
| Query page cap | `query.max_page_size` | `max_page_size` | integer / `10` | `[1,100]`; applies to command query rows and trace/browser pages, subject to the GUI's separate 45-slot ceiling | Restart |
| Server-only operation | `operations.server_side_only` | `server_side_only` | boolean / `true` | `true`; `false` fails startup because client operation is unsupported | Restart |
| Queue idle poll interval | `ingestion.poll_interval_ms` | `poll_interval_ms` | integer / `250` | `[10,5000]` milliseconds; worker's maximum wait while all three native queues are empty | Restart |
| Queue flush cadence | `ingestion.queue_frequency_ticks` | `queue_frequency_ticks` | integer / `20` | `[1,100]` server ticks between scheduled evidence flushes; default and range match GriefLogger `queueFrequency` | Restart |
| Maximum batch size | `ingestion.max_batch_size` | `max_batch_size` | integer / `100` | `[1,1000]` records drained per queue per worker pass | Restart |
| Network database keepalive | `operations.database_heartbeat_interval_ms` | `database_heartbeat_interval_ms` | integer / `30000` | `[1000,3600000]` milliseconds; best-effort validation of the shared MySQL/MariaDB connection on the ItemGraph worker; SQLite does not send heartbeats | Restart |
| Native capture | `capture.enabled` | `capture_enabled` | boolean / `true` | `true` or `false`; false suppresses new ItemGraph-native records and does not change the separately configured GriefLogger migration bridge | Restart |
| Raw evidence retention | `retention.raw_evidence` | `raw_evidence_retention` | string / `indefinite` | `indefinite`; no automatic purge is implemented | Restart |

`general.language` selects server-rendered ItemGraph message resources. The
NeoForge server config rejects unsupported values while validating its config
spec; Fabric rejects them while loading `config/itemgraph.properties`. Both
validate before `DatabaseManager.initialize`, and the diagnostic names
`general.language`. ItemGraph resolves message text on the server and sends
literal components, so vanilla clients do not need ItemGraph or language files.
The bundled locale inventory is `en_us`, `nl_nl`, and `zh_tw`; absent keys use
the English source string. `zh_cn` is not supported by ItemGraph. It is present
only in the pinned GriefLogger 26.2 source research, not in ItemGraph's shipped
locale set. The exact GriefLogger 1.2.10-1.21.1 release fixture has no locale
inventory, so no exact-release locale compatibility claim is made.

Localization coverage is deliberately partial (recorded scope). All 217 catalog
keys are present and validated in each of `en_us`, `nl_nl`, and `zh_tw`, but the
nine `source.<sha256>` hover/action-label keys (Evidence, Item, Canonical metadata
fingerprint, Event, UTC time, Origin, Destination, Previous, Next) are the only
translated `sourceText` phrases: the 36 `/ig help` topic bodies, the five-line
command overview, and the permission recipes resolve to their English source
strings under `nl_nl`/`zh_tw`. This is intentional — fallback stays readable
English and clients need no assets. `ItemGraphLanguage.sourceFallbackInventory`
returns the exact untranslated phrase set if coverage is ever extended; the
overview plus the `journeys` and `permissions` topics are the natural first
candidates.

The raw evidence retention value is a safety invariant, not a purge scheduler.
ItemGraph preserves raw observations and audit events indefinitely. During
legacy upgrades, migrations V3–V5 retain the old observation columns, raw
payloads, and referenced fingerprint values in `ig_legacy_observation_evidence` before clearing rows with obsolete
endpoint semantics from the active `ig_observations` projection. The archive is
not included in current graph queries; it remains available for forensic review.
The V17 migration creates the archive table for databases already at schema
version 16. It cannot recover rows erased by V3–V5 before this preservation fix.
V18 adds nullable, unique queue-event UUID columns to the observation,
transformation, and audit ledgers. New queued records carry one UUID across
retries; existing records remain readable with a null UUID.
Keep database backups under the server operator's backup policy. ItemGraph does
not delete raw rows after exporting or archiving them.

## Bounded queue behavior

Each of the three native ingestion queues is bounded to 10,000 entries. A
Queue submission wakes the ItemGraph worker but does not access the database or
block the Minecraft server thread. The worker flushes on the configured
`queue_frequency_ticks` callback at server end tick, matching GriefLogger's
`queueFrequency` tick-based schedule. It drains at most `max_batch_size` records
from each queue per worker pass; if a backlog remains, additional bounded passes
continue on the worker until the queues are empty. `poll_interval_ms` is only the
maximum idle wait used for worker housekeeping and database heartbeat deadlines.
A failed transformation write is retried through its bounded queue with backoff.
If the queue fills while re-queuing, or a producer submits after shutdown closes
admission, the dropped count is incremented and the server log reports evidence
loss. Shutdown waits up to 5 seconds for each database worker, interrupts it,
then waits up to 5 more seconds. If the internal evidence worker remains blocked,
ItemGraph snapshots its queued and in-flight observations, transformations, and
audit events into `itemgraph-pending-evidence.json` beside the SQLite database
(or under `./itemgraph/` for a network database). Snapshot serialization and
file I/O run on a daemon writer; the lifecycle callback waits at most 5 seconds
for it. The writer uses a temporary file, forces it to disk, and atomically
replaces the recovery file where the filesystem supports it. JDBC connection
close also has a 1-second deadline. If the recovery-file write misses its
deadline or fails, ItemGraph logs a critical error and counts the outstanding
records as at risk; the daemon may finish a late write only if the process stays
alive and the filesystem returns. The callback does not wait indefinitely on
JDBC, file I/O, or connection close.

### Pending-evidence recovery

On the next start, ItemGraph replays the recovery file on its evidence worker,
not on the loader lifecycle callback. New evidence remains bounded in the
normal queues while recovery runs; recovered records are placed ahead of those
new records before persistence. A malformed or unsupported file is preserved,
then intake closes and any records accepted during validation are saved in the
adjacent `.overflow` file. Intake stays closed until the primary recovery file
is repaired.
`ingest_event_uuid` keeps replay idempotent when a commit succeeded but the JDBC
acknowledgement was lost. The file remains until every recovered record has a
confirmed database commit, then ItemGraph removes it. Recovery files are limited to 256 MiB; a larger
existing file is preserved and capture remains disabled. If recovery encounters
already-accepted events while that primary file cannot be read, ItemGraph
preserves them in the adjacent `itemgraph-pending-evidence.json.overflow` file
without replacing the primary file. Both files replay after the primary recovery
file is repaired; ItemGraph removes them only after every record commits. If a
shutdown snapshot cannot be written within the size limit or because storage
fails, ItemGraph logs a critical error and reports the outstanding count as at risk.

For the healthy local SQLite saturation profile, five isolated NeoForge runs drained a
full 10,000-event audit queue in 626–666 ms. CI enforces a 1,000 ms regression
budget for that exact workload (`max_batch_size=100`, one explicit over-capacity
rejection, all accepted rows durable). This is a deterministic fixture budget,
not a production latency claim or a shutdown timeout. The measured profile and limits are in
the [performance test plan](TEST_PLAN.md#measured-local-performance-profile).
Failures before transaction start and failures followed by confirmed rollback count as
definite loss. If commit and rollback both leave the durable result uncertain, ItemGraph
increments the separate persistence-outcome-unknown count. `database_connection_timeout_ms`
limits connection establishment, not an already-running write.

GriefLogger's `helloFrequency` is a database connection keepalive, not a status
message. ItemGraph maps it to `operations.database_heartbeat_interval_ms` and
uses JDBC `Connection.isValid(5)` from the background ItemGraph worker for
MySQL/MariaDB. The default 30,000 ms matches GriefLogger's documented 600 ticks.
The worker shortens its idle poll to meet the configured interval, but a slow
batch write or retry can delay a heartbeat because both use the same worker and
connection. Failed validation invalidates the connection; the next heartbeat
retries initialization. SQLite does not need a network heartbeat. Network
database heartbeat behavior is covered by the CI integration tests against
disposable MariaDB and MySQL services. Local SQLite tests do not exercise that
network path.

## Operational metrics

`/itemgraph status` (alias `/ig status`) reports aggregate enqueue, persistence,
query, and correlation counts, failures, and bounded p95 latency upper bounds. It
also reports queue peak/rejected-item totals, persisted item and batch totals,
unresolved component-payload decode failures, decode-failure cache insertion/hit
totals, and current JVM heap use. The unresolved-payload counter includes every
failed decode encounter, including repeats returned from the negative cache; it
does not count other unresolved evidence classes. These counters contain no item
payloads, player names, UUIDs, coordinates, or database credentials. They reset
when the ingestion service starts. See the [performance test plan](TEST_PLAN.md#performance-test-plan)
for the exact CI workloads, enforced fixture limits, and budgets that remain
unmeasured.

CI stores redacted JSON reports for the NeoForge SQLite 8,000-event burst, the
Fabric SQLite 32-event tick-flush probe, NeoForge and Fabric network probes
against disposable MySQL and MariaDB services, and a NeoForge shutdown
saturation probe. Each network probe submits 512 synthetic audit events and runs
20 read-only ledger count queries across four workers, requiring at least one
query to overlap the remaining submission window. The shutdown probe accepts
10,000 audit events, explicitly rejects the next event, then verifies all
accepted rows reached SQLite through worker-owned batches before shutdown
returns. No database operation runs in the loader lifecycle callback.

The NeoForge and Fabric server-thread submission limits remain 50 ms per probe
and are enforced by CI. These are operational safeguards, not staging-derived
production budgets. CI exercises the registered `/ig lookup` handler against
disposable MySQL and MariaDB databases with mocked server/player objects; live
client delivery, real server tick impact, actual modded-inventory adapters, an
idle baseline, and staging latency/memory budgets remain unverified. The queue
remains capped at 10,000 entries per queue, flush cadence remains 1–100 ticks,
and SQL batches remain capped at 1,000 records.

## Secret-safe status

`/ig status` reports the active backend identifier, ItemGraph schema version,
effective query page cap, database connection timeout, index policy, and queue
idle poll, flush tick cadence, and batch size. It omits database paths, hosts,
usernames, passwords, and raw exception text. Connection diagnostics with
private endpoint details remain in the server log.
Candidate-resolution queries and `/ig explain` evidence retain their stricter
fixed internal caps; `max_page_size` does not raise those forensic safety bounds.
`capture_enabled=false` suppresses native listener records only. Public ItemGraph
API submissions and read-only GriefLogger ingestion continue.
