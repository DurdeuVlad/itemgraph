# Issue #24 Fabric MC Pilot evidence

**Run date:** 2026-10-04

**Server:** Minecraft 1.21.1, Fabric Loader 0.16.9, Fabric API, ItemGraph schema 21

**Client:** MC Pilot 0.15.0, client mod 0.9.1, Fabric Loader 0.16.14; no ItemGraph client mod

**Network:** isolated local server bound to `127.0.0.1:25565`

This is an ItemGraph-only run. GriefLogger was not loaded and its database was not opened. Server and client logs/world remain in the ignored local test directory; this committed summary and screenshots preserve the bounded visible-client evidence without publishing those raw files.

## Command replay and database checks

MC Pilot sent the 15 published lookup examples under `/ig`, then the same 15 under `/itemgraph`. A read-only SQLite query of `ig_audit_events` scoped to **2026-10-04 14:17:47.192–14:18:00.642 UTC** found exactly 15 `COMMAND_ATTEMPT` rows beginning `ig lookup` and 15 beginning `itemgraph lookup`. The first root's rows span 14:17:47.192–14:17:53.834 UTC; the second root's rows span 14:17:54.315–14:18:00.642 UTC. MC Pilot recorded 30 rendered query headers.

Additional connected actions verified invalid `page 0`, positive/empty lookups, double-chest inspection, stone-break suppression while inspect mode was on, and disconnects. Read-only `PRAGMA integrity_check` returned `ok`; the database contains two `PLAYER_JOIN` and two `PLAYER_QUIT` events. During the recorded stone-click window, read-only queries found no audit event at the target and no new item observation. The server log records `Starting minecraft server version 1.21.1`, ItemGraph schema 21 initialization, server stop, ingestion shutdown, and database connection closure.

## Rendered page controls

`fabric-page-1-next-control.png` shows the rendered `[Next]` chat control. After a physical MC Pilot click on that control, the client displayed page 2 with `[Previous] [Next]`; `fabric-page-2-controls.png` retains that result. A subsequent physical click on `[Previous]` returned page 1 during the run. Screenshots are retained at their original pixels; they include only the disposable local QA world and synthetic QA queries.

SHA-256:

- `fabric-page-1-next-control.png`: `1753868ce745bf424d52e7fb2b5478dd6da3e1b53fc393f88175ea758b9a92ed`
- `fabric-page-2-controls.png`: `fbf9984803e30011970bc2033c021957cc398c689bd52c79a98a02d3d3b8dc43`

The clients were stopped, the QA operator was removed, temporary loopback RCON was disabled, and the server shut down cleanly. The clients were instrumented MC Pilot clients, not unmodified vanilla clients. These observations do not establish a live GriefLogger differential run or the complete issue #26 click matrix.
