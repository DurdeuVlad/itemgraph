# ItemGraph Decision Log

Document status: partial; active decisions are recorded, open questions remain
visible
Last reviewed: 2026-09-17
Owner: Vlad Durdeu

This log records durable project decisions. Facts describe the current system;
decisions describe what the project intentionally does; open questions are not
quietly converted into assumptions.

## Active decisions

### D-001: Model item flow as a temporal directed multigraph

- Status: accepted
- Context: Individual log rows do not fully describe long-lived movement.
- Decision: Represent inventories and locations as nodes, raw observations as
  evidence, and compatible movements as time-ordered edges.
- Alternatives considered: per-item UUID tracking; a flat event report; a
  universal inventory snapshot.
- Rationale: The graph preserves partial evidence, stack flow, ambiguity, and
  explanations without claiming impossible identity.
- Consequences: Correlation, quantity allocation, and query explanations are
  first-class features.
- Revisit when: A future source provides authoritative identity and the graph
  model demonstrably prevents required use cases.

### D-002: Keep observed evidence separate from inference

- Status: accepted
- Context: A plausible bridge is not a direct log fact.
- Decision: Store and render observed, inferred, ambiguous, and unresolved
  states distinctly.
- Rationale: Moderation decisions must be auditable and challengeable.
- Consequences: Every inference needs evidence references and deterministic
  scoring.
- Revisit when: Never for the separation itself; presentation may evolve.

### D-003: Preserve quantity conservation

- Status: accepted
- Context: Stack splits, merges, and missing observations are normal.
- Decision: Use a quantity-allocation ledger and never attribute more outgoing
  quantity than compatible evidence supports.
- Alternatives considered: binary matching; permanent UUID per item.
- Rationale: Quantity errors are materially worse than unresolved flow.
- Consequences: Partial and ambiguous outcomes are expected.
- Revisit when: A stronger authoritative source changes the evidence model.

### D-004: Treat GriefLogger as read-only

- Status: accepted
- Context: It is an external evidence source owned by another mod.
- Decision: Use stable APIs or read-only database access; ItemGraph owns its own
  storage and migrations.
- Rationale: Prevents corruption and keeps ownership boundaries clear.
- Consequences: Unsupported source schemas produce a visible integration
  limitation instead of a silent repair.
- Revisit when: GriefLogger publishes a supported integration contract.

### D-005: Keep runtime work asynchronous and bounded

- Status: accepted
- Context: Full scans and historical correlation can block a live server.
- Decision: Capture minimal immutable event data on the server thread and
  perform ingestion, writes, and bounded queries on workers.
- Rationale: Server tick safety and predictable resource use.
- Consequences: Results are asynchronous and queues require backpressure.
- Revisit when: Measured workloads show a safer, simpler design.

### D-006: Target NeoForge 1.21.1 with Java 21

- Status: accepted for 0.1.x
- Context: The current mod and published artifact target this runtime.
- Decision: Build and document against Minecraft 1.21.1, NeoForge, and Java 21.
- Rationale: Matches the tested staging environment and release metadata.
- Consequences: Compatibility changes require an explicit milestone and
  release metadata update.
- Revisit when: A new supported Minecraft/NeoForge line has test coverage and
  a migration plan.

### D-007: Use MIT licensing and maintainer-controlled releases

- Status: accepted
- Context: The project is being prepared for open-source contributions.
- Decision: Publish source under MIT; accept contributions through pull
  requests; keep release credentials and version tags maintainer-controlled.
- Rationale: Low-friction reuse with a clear safety boundary around publishing
  and sensitive data.
- Consequences: External contributors can propose changes but cannot publish to
  the official distribution projects.
- Revisit when: Governance, ownership, or release automation changes.

## Open questions

- Which permission API should provide the final server-specific access
  integration?
- Which faction/team mod APIs need a supported integration boundary?
- What retention and export policy should operators use for sensitive graph data?
- Which performance thresholds should become mandatory release gates for large
  servers?
- When should the first stable public API or schema compatibility guarantee
  exist?
