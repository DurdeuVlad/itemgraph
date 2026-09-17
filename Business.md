# ItemGraph: Problem and Intended Value

Document status: complete for the 0.1.x scope
Last reviewed: 2026-09-17

## Problem

Minecraft server logs can show individual actions without reconstructing how
an item plausibly moved through time. Moderators need to investigate disputes
involving valuable or named items without pretending that incomplete logs prove
an exact per-item identity.

## Beneficiaries

- Server moderators investigating item-loss and transfer disputes.
- Server owners who need explainable, auditable evidence.
- Contributors building safer inventory and evidence integrations.
- Players who benefit from moderation decisions that can be explained and
  challenged against recorded evidence.

ItemGraph is non-commercial in this repository. Its intended value is better
forensic reconstruction and safer moderation, not a promise of revenue or
automated guilt determination.

## Solution shape

ItemGraph is a server-side NeoForge mod for Minecraft 1.21.1. It combines
read-only GriefLogger evidence with selected supplemental Minecraft event
observations and stores an explainable temporal directed multigraph:

1. raw observations are captured or imported;
2. item metadata is canonicalized into deterministic fingerprints;
3. inventories and locations become graph nodes;
4. compatible observations are correlated under time and quantity constraints;
5. queries return observed events, inferred edges, confidence, and supporting
   evidence separately.

## Actors and flow

- Server owner: installs the mod and controls deployment, permissions, and
  data retention.
- Moderator: runs privileged queries and evaluates the evidence.
- Player: may be affected by a moderation investigation; player-facing access
  must remain permission-scoped.
- GriefLogger: supplies an external, read-only evidence source.
- ItemGraph: owns supplemental observations, derived graph state, and
  explanations.
- Contributor: proposes code, tests, documentation, or integrations through a
  pull request.
- Maintainer: reviews changes, controls releases, and protects credentials.

The core usage flow is:

~~~text
authoritative evidence
    -> raw observation
    -> canonical item fingerprint
    -> temporal and quantity-constrained candidates
    -> observed/inferred/ambiguous result
    -> explanation with evidence IDs
~~~

## Rules

- Evidence and inference must never be presented as the same thing.
- A later observation cannot explain an earlier event.
- Attributed outgoing quantity cannot exceed compatible available quantity
  unless creation, transformation, or destruction evidence exists.
- A named item is distinctive evidence, not a guaranteed permanent identity.
- GriefLogger is read-only.
- Sensitive graph access is default-deny and permission-gated.
- Heavy queries and database work stay off the Minecraft server thread.

## Success paths

The 0.1.x vertical slice succeeds when staging can reconstruct:

~~~text
Chest A -> Player A -> Ground -> Player B -> Chest B
~~~

for a named armor item and for an ordinary stack quantity flow, while showing
timestamps, metadata, confidence, evidence IDs, and an explanation for every
inferred edge.

## Failure paths

ItemGraph must report an unresolved or ambiguous result when:

- one side of a transfer is missing;
- observations conflict in time or quantity;
- multiple candidates are materially indistinguishable;
- source data is unavailable or unsupported;
- the requester lacks the required permission.

It must not silently invent a path, identity, quantity, or authorization.

## Non-goals

- Universal per-item UUID assignment.
- Automated accusations or irreversible moderation decisions.
- Public exposure of hidden inventories, bases, faction storage, or unrelated
  player identities.
- Mutation, repair, or migration of the GriefLogger database.
- A client-side inventory tracker as the primary source of truth.

## Measurable outcomes

- Every inferred edge cites supporting observations.
- Confidence is deterministic and explainable.
- Quantity invariants remain satisfied by automated tests and /ig audit.
- Queries are bounded and permission-checked.
- Release builds pass the full automated test suite.
