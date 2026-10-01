# ItemGraph Milestones

Document status: active
Last reviewed: 2026-10-01
Owner: Vlad Durdeu

Milestones describe outcomes and proof, not a list of implementation chores.

## M0: Evidence-first MVP

- Status: complete
- Outcome: Reconstruct a named item and ordinary stack flow without inventing
  identity or quantity.
- Scope boundary: server-side NeoForge 1.21.1 mod; GriefLogger read-only;
  bounded asynchronous ingestion and queries.
- Dependencies: GriefLogger 1.2.10 or compatible supported schema, Architectury
  API, SuperMartijn642's Config Lib, Java 21.
- Acceptance evidence: staging scenarios in docs/TEST_PLAN.md; automated tests
  for canonicalization, ingestion, correlation, quantity flow, persistence,
  explanations, transformations, item entities, and audits.
- Risk: source coverage and modded inventory behavior can leave flow unresolved.

## M1: 0.1.0 distribution

- Status: partial
- Outcome: A reproducible 0.1.0 release artifact and platform metadata are
  prepared, with Modrinth and CurseForge uploads completed; the official
  GitHub Release still requires a matching release tag and successful workflow
  run.
- Scope boundary: no claim of universal item identity or stable pre-1.0 API.
- Dependencies: release jar, platform project metadata, maintainer-only
  publishing credentials.
- Acceptance evidence: tag/version validation in
  .github/workflows/publish.yml; release artifact built by clean build; public
  Modrinth and CurseForge project pages; changelog. GitHub currently reports
  zero workflow runs and zero releases.
- Risk: external platform review and dependency availability can delay
  synchronization.

## M2: Open-source contribution baseline

- Status: complete in the current worktree; remote publication is pending
- Outcome: A new contributor can understand the project, build it, report a
  vulnerability privately, and submit a focused pull request without access to
  production systems or release secrets.
- Scope boundary: documentation, license, community policies, issue/PR
  templates, and non-publishing CI.
- Dependencies: GitHub repository settings and maintainer review.
- Acceptance evidence: LICENSE, CONTRIBUTING.md, SECURITY.md,
  CODE_OF_CONDUCT.md, Business.md, Decision.md, Collaboration.md, OSS.md,
  Milestones.md, and .github/workflows/ci.yml are present, linked, internally
  consistent, and the build/test workflow passes.
- Risk: governance and support expectations may need revision after real
  external contributions.

## M3: Safe external contribution loop

- Status: in progress
- Target horizon: after the first external pull request
- Outcome: Pull requests receive automatic build/test feedback and maintainers
  can merge changes with branch protection and required checks.
- Scope boundary: no automatic publishing from pull requests; no secrets in
  untrusted fork workflows.
- Dependencies: GitHub branch protection configuration and maintainer
  availability.
- Acceptance evidence: .github/workflows/ci.yml is configured for pull requests
  and pushes to main with contents: read only; a fork-originated PR and branch
  protection are still pending. The release workflow remains tag-only.
- Risk: GitHub repository settings are external state and must be audited
  separately from committed files.

## M4: Broader compatibility and operations

- Status: planned
- Outcome: Documented support boundaries for larger servers, retention,
  backups, permissions, modded inventories, and future Minecraft/NeoForge
  versions.
- Dependencies: staging measurements, operator feedback, and identified
  faction/inventory APIs.
- Acceptance evidence: updated architecture, security, test, and operations
  documents plus reproducible performance results.

## M5: GriefLogger-Independent Feature Parity (0.2.0)

- Status: implemented and merged in PR #6. The V11 live movement scenarios remain
  unverified; see `docs/TEST_PLAN.md` for the exact staging boundary and evidence gap.
- Outcome: ItemGraph boots and records its supported native observations without
  GriefLogger. When GriefLogger is installed, ItemGraph reads its SQLite database
  read-only and adds its observations as evidence. Confirmed source copies retain both
  raw rows but contribute one capacity; uncertain matches remain ambiguous. No claim is
  made that all item movement is observable.
- Scope boundary: native player ground drops/pickups and death drops after confirmed
  entity insertion; player container **session net deltas** over open/close (not click
  history); caller-unknown capability transfers through registered vanilla block
  `IItemHandler` providers; armor-stand and transformation observations. Excludes private
  ender chests and non-vanilla inventories without a supported adapter. Generic
  `IItemHandler` calls do not prove a hopper or automation cause.
- Dependencies: NeoForge 1.21.1, Java 21, JarJar-bundled `org.xerial:sqlite-jdbc`.
- Acceptance evidence:
  - Issues [#1](https://github.com/DurdeuVlad/itemgraph/issues/1)–[#5](https://github.com/DurdeuVlad/itemgraph/issues/5) are closed and PR #6 is merged.
  - `./gradlew clean build`: 234 tests passed, 0 failures, 0 skipped.
  - The loopback staging server migrated ItemGraph's database to V11. Live player-movement
    scenarios were not run in that session, and the GriefLogger database was not
    hash-verified; do not treat startup as proof of movement parity.
- Risk: unobserved or unsupported inventory changes remain unresolved; V11 live movement
  coverage still needs a staging run isolated from the existing GriefLogger database.

## M6: Moderator investigation without a custom client

- Status: complete; issue #7 documented standalone/co-installed behavior, and issues #8,
  #10, and #11 are merged through PRs #14, #15, and #16.
- Outcome: moderators can read the existing ItemGraph evidence through a vanilla-client
  GUI, a command-toggled container inspector, and complete command help.
- Scope boundary: read-only evidence browsing at permission level 2. No rollback,
  inventory mutation, custom client screen, or custom ItemGraph item.
- Dependencies: M5 is merged; the GUI (#8) precedes the inspector (#10), and the command
  reference (#11) follows both.
- Acceptance evidence: automated command/query/menu tests; dedicated NeoForge 1.21.1
  staging passed with MC Pilot on a real graphical client in GriefLogger-present and
  GriefLogger-absent modes. Menu interactions remained display-only and permission-checked.
- Risk: unsupported inventory types can still leave evidence unresolved; menu output remains
  read-only and does not claim complete world coverage.

## M7: Preview integration API for NeoForge mods

- Status: complete; issue #9 contract merged through PR #17 and issue #12 implementation
  merged through PR #18. Tracked by GitHub milestone #3.
- Outcome: a separate NeoForge mod can submit source-attributed observations and query
  bounded ItemGraph flows without database access.
- Scope boundary: server-side Java API in the main ItemGraph JAR, preview-only before 1.0.
  No GriefLogger API, JDBC exposure, web API, custom client protocol, or CustomNPCs integration.
- Dependencies: the API contract (#9) must be reviewed and accepted before implementation
  and consumer example (#12).
- Acceptance evidence: a separate sample consumer compiles against the main JAR and
  exercises observation submission and flow queries on a dedicated server with
  GriefLogger absent and present.
- Risk: external source identity, inventory identity, backpressure, and sensitive query
  results require explicit contracts and consumer-side permission checks.

## M8: Drop-in GriefLogger parity

- Status: in progress; GitHub milestone
  https://github.com/DurdeuVlad/itemgraph/milestone/4.
- Outcome: ItemGraph keeps `/ig` and `/itemgraph` while matching GriefLogger behavior
  for commands, filters, inspector, events, read-only history import, storage, and
  operations. `/gl` and `/grieflogger` are not aliases. ItemGraph owns the native audit
  evidence needed to retire GriefLogger as a runtime dependency.
- Delivered: V13 native audit ledger and bounded `/ig lookup` for NeoForge, shared
  Fabric capture for its supported audit events, asynchronous persistence, action/player/
  time/radius lookup, item-flow and transformation capture, read-only container
  inspection, and a per-player interactive lookup page session. Item-flow evidence stays
  in `ig_observations`; block, session, chat, command, and entity events use
  `ig_audit_events`.
- Delivered through issue #25: the unified filtered lookup now merges native audit,
  item-flow, transformation, and already-normalized `GRIEFLOGGER` observations with
  bounded paging, source IDs, and canonical action filters. The generic eleven-table
  historical ledger remains the immutable source of truth and its normalized event
  projection is delivered by #28. The pinned 26.2 source/profile authority is recorded
  in #43. PR #86 delivered the cross-loader entity-interaction slice for #75.
  Remaining open M8 issues are #24 (published-example vanilla-client replay and
  residual command output/error semantics), #27 (aggregate native action
  acceptance), #30 (configuration and operations controls), and #31
  (differential proof). Issue
  #24's shared command contract is implemented and covered by cross-loader tests;
  #76 establishes the exact-release Ender action no-writer result while separately
  labeling ItemGraph session deltas. PR #93 merged the `m8.8.0` registry and
  exact `1.2.10-1.21.1` action writer matrix for all 18 source actions; #27
  remains open for aggregate native acceptance and runtime evidence. The runtime
  matrix in `docs/GRIEFLOGGER_PARITY.md` records the exact current boundary.
  Issue #26 is closed, but its visible-client click matrix remains unverified
  because the maintainer explicitly asked to skip live clicks; do not count that
  client evidence as observed in #24 or #31.
- Issue #30 progress: PR #95 merged the config-application ordering fix and added
  MySQL plus MariaDB worker-heartbeat CI coverage. A local loopback-only runtime
  matrix on NeoForge 21.1.248 and Fabric Loader 0.16.9 confirmed representative
  startup-snapshot behavior: `/reload` kept the original queue/capture controls,
  while restart applied updated controls and page cap. `/ig status` reports the
  effective page cap, connection timeout, and index policy without endpoint or
  credential values. Full invalid-key coverage and GriefLogger queue-cadence
  differential evidence remain outstanding; #30 stays open.
- Proof: the compatibility registry, cross-loader tests, backend tests, source/schema
  fixtures, exact-release fixture, and native-only staging replay tracked by issues
  #24–#28, #30–#31, #43, #54, #75, and #76. Issue #29 is delivered in PR #79, including
  SQLite/MariaDB/MySQL storage and configurable optional indexes. The current
  registry is `m8.8.0`; all 18 source action IDs and exact-release writer statuses
  are recorded, with no action rows left `unresolved`. Three configuration
  mappings remain unresolved. `INTERACT_ENTITY` and Ender actions 9 and 10 are
  `unsupported-no-writer` for the exact release, and ItemGraph's entity and
  session net-delta rows are labeled as independent extensions. Query page size
  is now configurable; server-side mode is a true-only invariant, raw evidence
  retention is indefinite, and queue/hello cadence still needs staging evidence.
  Issue #27 is decomposed into #73 (projectile outcomes),
  #74 (block interaction outcomes), #75 (entity interaction outcomes, completed
  in PR #86), and #76 (Ender action writer determination). Compatibility artifacts remain supported
  until the M8 gate and the native-only cutover evidence pass.

## M9: ItemGraph audit++ performance and extra events

- Status: planned; GitHub milestone
  https://github.com/DurdeuVlad/itemgraph/milestone/5.
- Outcome: extend the compatibility surface with measured throughput, creative/admin
  causes, modded inventory and automation, world/entity causes, cross-loader integration,
  tamper-evident exports, first-class uncertainty, and component-aware/absolute-time
  investigation queries.
- Scope boundary: staging and CI proof only. Every extension preserves evidence classes,
  quantity conservation, privacy, bounded queues, and asynchronous database work.
- Dependencies: M8 compatibility profile and native proof precede the M9 extensions;
  #32–#37, #44, #45, and #55–#58 own the implementation slices. Issue #35 is the
  event-taxonomy tracker for #55, #56, and #57.
- Acceptance evidence: reproducible benchmark output, cross-loader fixtures, malformed and
  opaque evidence cases, export verification, and read-only auditor review.

## M10: Native-only cutover and release hardening

- Status: blocked on M8 parity; GitHub milestone
  https://github.com/DurdeuVlad/itemgraph/milestone/6.
- Outcome: after M8 parity and the M9 safety/operations evidence required for a
  safe cutover, ItemGraph becomes the only supported runtime artifact for the
  exact GriefLogger 1.2.10-1.21.1 replacement target.
- Scope boundary: staging and release verification only. The read-only
  GriefLogger importer and immutable source archive remain supported; no
  production change or source-database mutation is authorized by this milestone.
- Dependencies: #71 establishes the audited native-only approval gate; #72
  removes compatible build/publication variants only after #71 closes and an
  explicit version bump/tag is supplied. Optional M9 feature extensions can
  continue after cutover in the standard jar.
- Acceptance evidence: differential report, 24-hour native-only staging soak,
  artifact/dependency checks, release metadata, source-archive checksum, and
  rollback rehearsal reviewed by an independent read-only auditor.

