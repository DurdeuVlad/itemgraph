# ItemGraph Milestones

Document status: active
Last reviewed: 2026-09-17
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

- Status: complete
- Outcome: ItemGraph 0.2.0 operates fully without GriefLogger installed, capturing
  the complete item movement graph (player ground drops/pickups, container interactions,
  and automated hopper transfers) through native NeoForge event listeners and capability
  wrappers. When GriefLogger is present, it provides additive evidence with no duplicate rows.
- Scope boundary: Vanilla container block entities, player inventory interactions, and
  server-side automation. Excludes player-private ender chests and non-vanilla modded inventories.
- Dependencies: NeoForge 1.21.1, Java 21, JarJar-bundled `org.xerial:sqlite-jdbc`.
- Acceptance evidence:
  - Issue [#1](https://github.com/DurdeuVlad/itemgraph/issues/1): JarJar `sqlite-jdbc` bundling & optional GriefLogger boot.
  - Issue [#2](https://github.com/DurdeuVlad/itemgraph/issues/2): Native drop, toss, and pickup observations with V9 dedup.
  - Issue [#3](https://github.com/DurdeuVlad/itemgraph/issues/3): Player container transfer observation. Player GUI clicks mutate `Container` directly and never reach `IItemHandler`, so player transfers are observed by session diffs over `PlayerContainerEvent.Open`/`Close` (`ContainerSessionListener` + `ContainerInteractionTracker`), with capability-reported automation credits excluding concurrent machine traffic.
  - Issue [#4](https://github.com/DurdeuVlad/itemgraph/issues/4): Automated hopper/machine transfer observation via `ContainerCapabilityWrapper` on `Capabilities.ItemHandler.BLOCK`, registered at `EventPriority.HIGHEST` so providers precede NeoForge's vanilla ones.
  - Issue [#5](https://github.com/DurdeuVlad/itemgraph/issues/5): Source-agnostic correlation, log hygiene, and `/ig status` telemetry.
- Risk: Modded inventories that bypass `IItemHandler` will remain unobserved until dedicated adapters are built.

