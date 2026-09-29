# ItemGraph Milestones

Document status: active
Last reviewed: 2026-09-29
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

- Status: planned; GitHub milestone [#4](https://github.com/DurdeuVlad/itemgraph/milestone/4).
- Outcome: ItemGraph keeps its own `/ig` and `/itemgraph` names while matching
  GriefLogger semantics for commands, filters, inspector behavior, native events,
  read-only historical import, SQLite plus MySQL/MariaDB storage, and operations.
  This is a behavioral replacement; `/gl` and `/grieflogger` aliases are not part
  of the contract.
- Current evidence: V13 native audit capture and bounded `/ig lookup` are implemented
  for the covered NeoForge and Fabric events; the 2026-09-29 Fabric replay verified
  native-only startup and representative audit/item rows. This is not yet 100% parity.
- Explicit gaps tracked by issues [#23](https://github.com/DurdeuVlad/itemgraph/issues/23)
  through [#31](https://github.com/DurdeuVlad/itemgraph/issues/31): command/filter and
  inspector semantics, a complete canonical action registry, item-flow and transformation
  lookup, all native event coverage, read-only import of every documented GriefLogger
  table, MySQL/MariaDB storage, configuration/retention controls, and differential
  cutover proof.
- Dependencies: NeoForge/Fabric 1.21.1 APIs, Java 21, GriefLogger source-schema fixtures,
  and disposable SQLite/MySQL/MariaDB CI services.
- Acceptance evidence: the parity matrix in `docs/GRIEFLOGGER_PARITY.md`, cross-loader
  command/event fixtures, source-database hash reports, backend query parity, and the
  native-only staging replay defined by issue #31. Its minimum gates are zero crashes or
  kicks, no silent event loss, existing 10,000-entry ingestion queues and 64-request
  query bound respected, the existing 100-row lookup limit, five-second entity-less
  query timeout, and zero quantity/temporal invariant violations.
- Risk: unsupported inventory types, undocumented GriefLogger schema variants, and
  differences between attempted and completed actions can leave evidence unresolved.

## M9: ItemGraph audit++ performance and extra events

- Status: planned; GitHub milestone [#5](https://github.com/DurdeuVlad/itemgraph/milestone/5).
- Outcome: extend the compatible replacement with measured throughput/backpressure,
  creative and administrative causes, modded inventory and automation adapters, world
  and entity causes, cross-loader integration contracts, and tamper-evident incident
  exports.
- Scope boundary: all work is staging/CI first; no production changes, GriefLogger writes,
  hidden-inventory disclosure, or unbounded scans.
- Issues: [#32](https://github.com/DurdeuVlad/itemgraph/issues/32) performance budgets,
  [#33](https://github.com/DurdeuVlad/itemgraph/issues/33) creative/admin mutations,
  [#34](https://github.com/DurdeuVlad/itemgraph/issues/34) modded inventory and automation,
  [#35](https://github.com/DurdeuVlad/itemgraph/issues/35) world/entity causes,
  [#36](https://github.com/DurdeuVlad/itemgraph/issues/36) cross-loader API fixtures, and
  [#37](https://github.com/DurdeuVlad/itemgraph/issues/37) tamper-evident exports.
- Dependencies: M8 action/storage contracts and measured staging budgets.
- Acceptance evidence: reproducible CI benchmarks, conservation reports, adapter fixtures,
  API compatibility tests, and export verification against altered copies.

