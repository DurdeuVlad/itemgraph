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
| GriefLogger source path | `general.grieflogger_database_path` | `grieflogger_database_path` | string / `database.db` | Non-empty relative or absolute path; source remains read-only | Restart |
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
| Native capture | `capture.enabled` | `capture_enabled` | boolean / `true` | `true` or `false`; false suppresses new ItemGraph-native records and does not stop GriefLogger read-only ingestion | Restart |
| Raw evidence retention | `retention.raw_evidence` | `raw_evidence_retention` | string / `indefinite` | `indefinite`; no automatic purge is implemented | Restart |

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
loss. During server shutdown, ItemGraph waits for active worker and importer writes
to finish before it flushes the queues and closes the database. There is no hard
deadline for this wait: a stalled JDBC operation can delay shutdown. For the
healthy local SQLite saturation profile, five isolated NeoForge runs drained a
full 10,000-event audit queue in 626–666 ms. CI enforces a 1,000 ms regression
budget for that exact workload (`max_batch_size=100`, one explicit over-capacity
rejection, all accepted rows durable). This is a deterministic fixture budget,
not a production latency claim or a hard timeout; the worker still waits for
durability if a JDBC operation stalls. The measured profile and limits are in
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
decode-failure cache insertion/hit totals, and current JVM heap use. These
counters contain no item payloads, player names, UUIDs, coordinates, or database
credentials. They reset when the ingestion service starts.

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
