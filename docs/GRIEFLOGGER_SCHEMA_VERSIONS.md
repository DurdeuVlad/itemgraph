# GriefLogger database schema across versions

Research for the automatic read-only import path: which GriefLogger versions
can ItemGraph safely detect and import without crashing or spamming the
server log. Sources are the upstream repository `DAQEM/GriefLogger` (per-tag
`CREATE TABLE` DDL and commit history), the Modrinth version API (project
`8oGVUFuX`), and the release-byte fixture
[`grieflogger-fixtures/1.2.10-1.21.1.json`](grieflogger-fixtures/1.2.10-1.21.1.json).

## Answer in one line

Every GriefLogger SQLite `database.db` ever released — v1.0 (2024-01-24)
through 21.1.7 (2026-09-13) — uses the **same 11-table schema with identical
columns**, so a single capability contract accepts all of them; the real
version hazards are the `data` blob contents, the MySQL backend option, WAL
file state, and file discovery — not the table layout.

## Version landscape

| Era | Versions | Minecraft | Loaders | Notes |
|---|---|---|---|---|
| 1.0.x | 1.0 – 1.0.2 | 1.20.1 | Fabric | First releases; schema born 2024-01-05..08 (`fa740e01a6`, `088fe9b64a`, `eb44439a2c`) |
| 1.1.x | 1.1 – 1.1.5 | 1.19.2, 1.20.1 | Fabric | `usernames`, `entities`, coordinate indexes added by 1.1.5 |
| 1.2.x | 1.2 – 1.2.10-* | 1.20.1 – 1.21.10 | Fabric + NeoForge | Dual-loader from `1.2-1.20.6`; `1.2.10-1.21.1` is the checksum-pinned fixture |
| 19.x | 19.0.0 – 19.1.1 | 1.21.11 | Fabric + NeoForge | Renumbering era; DDL unchanged |
| 20.x / 21.x | 20.1.0 – 21.1.7 | 26.1.2 / 26.2 | Fabric + NeoForge | Matches the pinned `26.2` source audit commit `d315098b` |

## Stable schema (verified at `1.19.2-1.1.5`, `1.2`, `1.2.10-1.21.1`, `19.1.1`, `21.1.7`, and the Jan-2024 birth commits)

| Table | Columns |
|---|---|
| `items` | `time, user, level, x, y, z, type, data, amount, action` |
| `containers` | `time, user, level, x, y, z, type, data, amount, action` |
| `blocks` | `time, user, level, x, y, z, type, action` |
| `sessions` | `time, user, level, x, y, z, action` |
| `chats` | `time, user, level, x, y, z, message` |
| `commands` | `time, user, level, x, y, z, command` |
| `users` | `id, name, uuid` |
| `usernames` | `id, time, uuid, name` |
| `levels` | `id, name` |
| `materials` | `id, name` |
| `entities` | `id, name` |

- Event tables join `user→users.id`, `level→levels.id`, `type→materials.id`
  (items/containers/blocks). `data` is a BLOB; `time` is a millisecond epoch.
- DDL differences across versions are cosmetic only: text-block vs
  string-concat SQL, and `integer` vs `bigint`/`text` vs `varchar(256)` type
  names that collapse to the same SQLite storage classes.
- `ItemAction` IDs 0–10, `SessionAction` 0–1, and `BlockAction` 0–3 are
  identical at every checked ref.
- `useIndexes` adds coordinate indexes; indexes never change the readable
  column contract.

## What actually varies — the real risks

1. **`data` blob contents.** `ItemRepository` stores
   `item.getTagBytes(level)` — `NbtIo`-serialized item metadata. The column is
   stable, but the blob's *meaning* depends on the server's Minecraft version
   (pre-1.20.5 `tag` NBT vs 1.20.5+ `components`). ItemGraph stores it
   opaquely in `payload_blob`, so no version-aware decode exists today; any
   future feature that parses it must handle both eras.
2. **`materials.name` normalization.** GL strips the `minecraft:` namespace
   when writing material names (seen at 1.1.5 and 1.2.10); modded entries keep
   their namespace. Imported names are preserved verbatim — a cosmetic
   identity difference, never a crash.
3. **`blocks` action 4.** `BlockAction.INTERACT_ENTITY(4)` was added after
   1.1.x. Only relevant if `blocks` is ever imported; the current importer
   reads only `items` + `containers` plus the five reference tables.
4. **MySQL backend.** `useMysql` (default `false`) moves GL storage to
   MySQL/MariaDB — no local `database.db` exists at all. Discovery must report
   "no SQLite source" cleanly rather than fail.
5. **File location.** GL writes `config/grieflogger/database.db` on every
   version (`Platform.getConfigFolder()/grieflogger`). ItemGraph's
   `grieflogger_database_path` default `database.db` resolves elsewhere and
   misses it — discovery should probe `config/grieflogger/database.db` first.
6. **WAL state.** A `database.db` copied while the source server was running
   may have uncheckpointed rows in `database.db-wal`. `?mode=ro` needs the
   `-wal`/`-shm` files alongside, and `?immutable=1` on a hot copy silently
   reads stale data. Import a post-shutdown checkpoint or copy all three
   files. Since the GL mod is now declared incompatible with ItemGraph, the
   source file is always cold — but possibly still WAL-formatted.
7. **No version marker.** GriefLogger writes no `user_version` or settings
   table, so the producing version cannot be sniffed from the file.
   Capability detection (`PRAGMA table_info`) is the only correct gate — which
   is what `GriefLoggerAdapter.isSupportedSchemaAvailable()` already does,
   logging at DEBUG and returning `false` cleanly.

## Current importer safety posture (already correct)

- Strictly read-only: `?mode=ro`, `PRAGMA query_only`, 5 s busy timeout;
  ItemGraph never writes, indexes, or migrates the source.
- Schema gate requires the column contract above; a foreign or truncated
  SQLite file is rejected before any ingestion, surfaced as **one** INFO line
  (`glUnavailableLoggedOnce`) instead of per-cycle warnings.
- Work is stepwise off the server thread: the ingestion worker batches 500
  rows per `fetchEvents` page, checkpoints by source `rowid`, falls back to an
  ordinal/hash checkpoint for `WITHOUT ROWID` tables, and resumes from
  committed rows after a failure.
- Raw rows land immutably in `ig_grieflogger_rows` (`payload_json` +
  `payload_blob`), preserving source PKs and the NBT blob for later review.

## Gaps for fully automatic import

1. **Discovery** — probe ordered candidates when the bridge is enabled:
   configured `grieflogger_database_path` → `config/grieflogger/database.db`
   (GL's canonical location) → `database.db` (legacy/manual placement).
   Log the resolved file once; if none exists, say so once.
2. **Tiered gate** — keep `items`, `containers`, `users`, `levels`,
   `materials` mandatory; degrade `usernames`/`entities` gracefully (present
   since the schema's birth, but a hand-edited DB could lack them).
3. **Source hygiene docs** — tell operators to import from a stopped-server
   copy, or to copy `database.db` together with `-wal`/`-shm`; never pass
   `immutable=1` to a live or WAL-pending file.
4. **MySQL source** — out of scope for file import; if auto-detect ever reads
   GL's own config, `useMysql=true` should produce a clear "network source not
   supported" report rather than a missing-file error.
5. **Coverage is deliberate** — `blocks`, `sessions`, `chats`, `commands` are
   stable and importable, but outside item-flow scope; import only if a future
   requirement appears (native ItemGraph capture already covers equivalents).

## Recommendation

Keep capability detection, not version sniffing: the column contract already
accepts every released GriefLogger schema and fails closed on anything else.
The remaining work for "find the database and import it automatically" is
small and bounded: the discovery probe order (5.1), optional-reference-table
degradation (5.2), and the WAL/copy guidance (5.3). No importer rewrite is
needed — batching, checkpoints, resume, and one-line reporting already exist.
