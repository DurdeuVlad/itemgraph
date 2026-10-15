# Branding

## Name

**ItemGraph**

## Mod ID

```text
itemgraph
```

## Commands

```text
/itemgraph
/ig
```

## Tagline

**Trace item movement through time.**

## Positioning

ItemGraph is a technical moderation and forensic tool.

ItemGraph is the standalone replacement product for GriefLogger. Normal
operation must capture, retain, query, and reconstruct ItemGraph-owned evidence
without a GriefLogger mod, database, or process. The GriefLogger database reader
is an optional, read-only path for importing historical evidence during
migration; it is not part of native capture or storage. The GriefLogger mod
itself is declared incompatible on both loaders — ItemGraph and GriefLogger
are never installed together, and the standard ItemGraph artifact alone is the
only supported build.

ItemGraph should replace GriefLogger's useful operator workflows with
ItemGraph-owned implementations and provide explainable item-flow reconstruction
on top. Do not describe ItemGraph as a GriefLogger add-on or imply that the
GriefLogger code or runtime is required.

It should feel:

- precise
- calm
- evidence-driven
- infrastructure-oriented
- trustworthy

It should not feel:

- punitive
- theatrical
- surveillance-themed
- police-themed
- accusatory

## Product language

Preferred terms:

- observation
- evidence
- trace
- flow
- graph
- inferred
- ambiguous
- unresolved
- confidence
- explanation

Avoid terms such as:

- guilty
- thief
- suspect
- proof, unless something is truly directly proven
- caught
- surveillance

The software should describe evidence, not pass judgment.

## Status vocabulary

Use consistent labels:

```text
OBSERVED
INFERRED
AMBIGUOUS
UNRESOLVED
```

Potential confidence labels:

```text
VERY HIGH
HIGH
MEDIUM
LOW
```

Numeric scores may be available in detailed admin output, but human-readable labels are preferable in normal chat.

## Example chat style

```text
[ItemGraph] Trace: "Old Reliable"

[OBSERVED] 14:31:08 Chest A removed 1x item.
[OBSERVED] 14:31:08 Alice gained 1x matching item.
[INFERRED] Chest A -> Alice [VERY HIGH]

Use /ig explain 9931 for evidence.
```

## Principle

The brand should reinforce the engineering philosophy:

> ItemGraph does not claim omniscience.  
> It shows what was observed, what can be inferred, and why.
