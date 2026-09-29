# GriefLogger replacement parity

This document defines the compatibility contract for using ItemGraph as the
GriefLogger replacement. Compatibility is behavioral and evidence-preserving;
ItemGraph keeps its own command names and storage.

## Branding contract

- Supported commands are `/ig` and `/itemgraph`.
- `/gl` and `/grieflogger` are not ItemGraph commands.
- GriefLogger databases are read-only sources. ItemGraph never repairs, migrates,
  writes, deletes, indexes, vacuums, or purges a GriefLogger database.
- ItemGraph must label direct observations, inferred movement, ambiguous candidates,
  and unresolved events separately.

## Registry

The normative machine-readable mapping is
[`GRIEFLOGGER_COMPATIBILITY.json`](GRIEFLOGGER_COMPATIBILITY.json). It records:

- canonical ItemGraph action names and accepted GriefLogger spellings;
- compatibility status (`compatible`, `extended`, `unsupported`, or `unresolved`);
- evidence class and quantity semantics;
- loader and storage support;
- lookup filter spellings and AND semantics;
- permission, paging, inspector, and configuration mappings;
- the GitHub issue responsible for each incomplete mapping.

The registry version changes when a mapping, status, evidence/quantity meaning,
loader, or backend contract changes. Documentation-only clarifications are patch
changes; additive mappings with existing behavior are minor changes; renamed,
removed, or incompatible mappings are major changes. The registry, this document,
and the owning issue must change together.

## Published source surface

The contract is based on GriefLogger's published block actions, item usage, player
sessions, chat, commands, inspector, filtered lookup, pagination, and SQLite/MySQL
storage documentation:

- https://daqem.com/projects/grieflogger
- https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage
- https://daqem.com/projects/grieflogger/wiki/player-actions/block-interactions
- https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions
- https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands
- https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command
- https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command
- https://daqem.com/projects/grieflogger/wiki/getting-started/configuration

## Implementation boundary

The remaining implementation work is tracked by GitHub milestone M8:
https://github.com/DurdeuVlad/itemgraph/milestone/4. The registry deliberately
marks incomplete or semantically different behavior as unresolved or extended;
this document does not claim 100% runtime parity until the owning issues provide
cross-loader tests and staging evidence.
