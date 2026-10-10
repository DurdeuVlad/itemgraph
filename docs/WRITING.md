# ItemGraph documentation writing contract

This file records how ItemGraph's reader-facing documentation is written so that
successive edits stay comprehensible instead of accumulating precision without
order. It is a style contract, not a behavior specification.

## Audiences

Every reader-facing doc names its primary audience in its first screen.

| Audience | Reads first | Knows | Does not know |
| --- | --- | --- | --- |
| First-time server admin | `README.md`, then [admin quick start](ADMIN_QUICK_START.md) | Minecraft server admin basics, jar files, permission plugins | ItemGraph's evidence model, commands, or coverage limits |
| Moderator / investigator | [admin quick start](ADMIN_QUICK_START.md), [query model](QUERY_MODEL.md), [security and permissions](SECURITY_AND_PERMISSIONS.md) | The incident they are investigating | Which command answers which question; what evidence labels mean |
| Operator migrating from GriefLogger | `README.md` migration section, [GriefLogger integration](GRIEFLOGGER_INTEGRATION.md) | Their existing GriefLogger install | Which import path is read-only, which source version is tested |
| Mod integrator | [Preview integration API](API.md), `examples/api-consumer` | Java, NeoForge modding | ItemGraph's preview boundary and submission rules |
| Contributor / maintainer | [architecture](ARCHITECTURE.md), [test plan](TEST_PLAN.md), `AGENTS.md`/`CLAUDE.md` | The codebase context they are working in | Where each contract lives and what is forbidden |

## Rules

1. **Name the audience early.** The first screen says who the doc is for.
2. **Define before use.** A project term (`observation`, `inferred edge`,
   `fingerprint`, `GROUND` endpoint, `canonical anchor`, evidence class) is
   defined or linked to its definition at first use, not three sections later.
3. **Context before dependent detail.** Install before configure; what a command
   returns before its flags; the evidence-vs-inference distinction before any
   interpretation advice.
4. **Task first, reference second.** Lead with the reader's question ("what
   happened to this item?"), then the command, then the caveats. Reference
   tables stay, but they follow the framing that makes them legible.
5. **One idea per paragraph.** Do not braid contract, caveat, and history into
   a single paragraph. If a sentence needs three caveats, split it.
6. **Uncertainty is labeled, never smoothed.** "Not implemented", "planned",
   "unknown", and "unresolved" states stay explicit. A doc must not claim a
   feature, coverage, or guarantee the code does not provide.
7. **Precision is preserved.** The charter's "facts and direct names" rule
   applies: exact command syntax, config keys, permission nodes, event IDs,
   and version numbers stay exact. Rewriting reorders and structures; it must
   not silently drop a technical fact.
8. **Tables for lookup, prose for reasoning.** Use tables when the reader is
   answering "which X for my Y"; use prose when the reader needs a model or a
   sequence.
9. **Link, don't repeat.** Each rule lives in one doc; other docs link to it.
   Two copies of a rule will drift.

## Pinned literals

`tools/validate_admin_ux_docs.py` enforces literal phrases and structure in
`README.md`, `docs/ADMIN_QUICK_START.md`, `docs/QUERY_MODEL.md`,
`docs/ARCHITECTURE.md`, `docs/SECURITY_AND_PERMISSIONS.md`, and
`docs/branding/CURSEFORGE_LISTING.md` (permission semantics, inspection routes,
import/recovery steps, section headings, feature-map rows). Before rewording any
of those files, run the validator's required strings as a checklist: every
enforced phrase must survive the edit verbatim. The validator is the authority;
this paragraph is only the warning that it exists.

## Protected evidence ledgers

These files record measured results, parity matrices, audits, or historical
evidence. Do not paraphrase, summarize, or compress their data prose — the
record is the value. Adding a heading, a "how to read this" preamble, or a
table of contents is allowed; rewriting the recorded content is not.

- `docs/GRIEFLOGGER_PARITY.md`
- `docs/GRIEFLOGGER_SOURCE_AUDIT.md`
- `docs/FEATURE_PARITY_INVENTORY.md`
- `docs/PHASE0_RECON_REPORT.md`
- `docs/UX_AUDIT.md`
- `docs/GRIEFLOGGER_COMPATIBILITY.json`
- `docs/GRIEFLOGGER_SCHEMA_VERSIONS.md` (data tables)
- `docs/test-evidence/` (all files)
- `CHANGELOG.md` and `Milestones.md` (entries are records; preamble may be edited)
- `docs/TEST_PLAN.md` and `docs/IMPLEMENTATION_PLAN.md` (verbatim evidence
  sections inside them are protected; navigation and framing are not)

## Verify

```text
python3 tools/validate_admin_ux_docs.py
python3 tools/validate_grieflogger_profile.py
```

Run both after any doc edit that touches a validated file.
