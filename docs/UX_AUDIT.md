# UX audit — `/itemgraph` (`/ig`)

Flux-UX audit of ItemGraph's admin-facing experience: the command tree, in-game
help, asynchronous query feedback, chat result formatting, the read-only flow
browser menu, in-world inspection, permission denials, paging/location actions,
localization, and the operator documentation set.

Method: user-knowledge and journey modelling per the flux-ux experience model
(prior knowledge → visible evidence → safe inference → decision → feedback →
recovery), plus a state inventory (loading, empty, ambiguous, unresolved,
denied, error) for each surface. All findings cite the implementing source so
each recommendation is traceable.

## 1. Actors and knowledge state

| Actor | Starting knowledge | Surfaces they touch |
| --- | --- | --- |
| Server admin / moderator (in-game player) | Knows an incident occurred; may not know ItemGraph command syntax or evidence terminology | `/ig` tree, help topics, inspect clicks, flow browser, chat output, `[Go to ...]` links |
| Console operator | Same; no entity, no GUI, no click affordances | `/ig` tree on the console; results go to chat or the log |
| Regular player | Nothing — the mod is server-side and permission-gated | None; all nodes require `itemgraph.*` grants or level 2 |

The design constraint that shapes everything: **inference must never be
mistaken for observed evidence.** Every finding below is evaluated against
that, not against general usability alone.

## 2. Surface inventory

| Surface | Implementation | States covered |
| --- | --- | --- |
| Command tree `/itemgraph`, `/ig` | `ItemGraphCommands.register` (`common/.../ItemGraphCommands.java`) | Unauthorized nodes hidden via Brigadier `requires` (progressive disclosure) |
| In-game help | `CommandHelp` — 5-line overview, ~35 topics, `journeys *` task groupings | Unknown topic → full topic list + failure line |
| Async query pipeline | `QueryDispatcher` — single worker, queue of 64, 5 s statement timeout | Accepted (console only), queued, timed out, denied, DB down |
| Chat output | `QueryFormatter` — labelled `[OBSERVED]` / `[INFERRED conf=x]` / `UNRESOLVED` / `PROVENANCE_ONLY` rows | Empty, truncated, ambiguous candidates, superseded edges |
| Flow browser | `FlowBrowserService` + `FlowBrowserMenu` — vanilla 9×6 chest menu, 9 rows/page, chat companion | Loading (chat), empty, ambiguous candidate pages, stale timeline, detail view, denied |
| In-world inspection | `InspectionListener` + `InspectionService` + `BlockInspectionTargets` | Enabled/disabled/status, container vs non-container routing, canonical double-chest anchor |
| Paging + navigation | `AuditPageSession` (8/player, 30 min TTL), `/ig page`, `/ig goto` one-use 2-min tokens | Expired session, revoked permission, invalid token |
| Localization | `ItemGraphLanguage` — `en_us`, `nl_nl`, `zh_tw`, per-key English fallback | All 211 catalog keys present in all three locales |
| Docs | `README.md`, `docs/ADMIN_QUICK_START.md`, `docs/QUERY_MODEL.md`, `docs/SECURITY_AND_PERMISSIONS.md` | Kept in sync by `tools/validate_admin_ux_docs.py` |

## 3. Journey assessment

- **First use:** `/ig` prints a bounded 5-line overview pointing to
  `/ig help commands`, `/ig help journeys`, and `/ig help permissions`
  (`CommandHelp.overviewLines`). Topics are tab-completable and normalized
  (`-`, `_`, `.` accepted as separators). `guide` links to the admin quick
  start. Good progressive disclosure.
- **Item investigation:** `/ig trace item <query>` → ambiguous matches produce
  a candidate list instead of a silent pick → `"id:<fingerprint>"` refinement →
  hop lines label evidence class → `/ig event <id>` opens raw observations,
  `/ig explain <id>` opens inferred edges with their cited evidence →
  `[Go to ...]` links teleport with one-use permission-rechecking tokens.
  Recovery paths exist at every decision point.
- **Nearby incident:** `/ig inspect on` → left-click = block history,
  right-click container = flow browser, right-click non-container = paged
  history. Clicks are consumed once the read-only request is accepted, on
  permission revocation, and on missing-grant denials; transient rejections
  and rejected container right-clicks still fall through to vanilla.
- **Operations:** `/ig status` prints an ACTION line, a summary, then
  DIAGNOSTIC counters; the help topic teaches how to read them. Deliberately
  dense but documented.

## 4. Findings

Severity: **M**edium (user-visible gap with a wrong-action or confusion risk),
**L**ow (polish/inconsistency), **I**nfo (accepted trade-off worth recording).

### F1 — M — In-game async queries give no acceptance feedback

`QueryDispatcher.dispatch` registers `future.whenComplete(...)` and returns
`1` for player sources without sending anything
(`QueryDispatcher.java`, the `future.whenComplete` tail of `dispatch`).
The console path sends `query.accepted_log` ("Query accepted; completed
results will be written to the server log"), and the flow browser sends
`flow.browser.loading` — but a player running `/ig trace`, `/ig lookup`,
`/ig event`, `/ig explain`, or clicking a block under `/ig inspect` sees
silence until results arrive (up to the 5 s timeout, longer under queue
contention on the single-worker/64-deep executor).

*Recommendation:* send one bounded "query running" acknowledgment to
interactive sources, or send a notice only when completion exceeds a
threshold. Match the existing `query.accepted_log` pattern.

### F2 — M — Player timeout surfaces internal cancellation text

When `PLAYER_QUERY_TIMEOUT_MS` (5 s) fires, `QueryCancellation.cancel()`
interrupts the JDBC layer; the resulting `SQLException` text
("query cancelled before execution began", "... statement creation",
"... before completion", or the driver's own interrupt message) reaches the
player verbatim as `Query failed: <internal text>` via `deliver()` →
`callerFailureMessage` (`QueryDispatcher.java`). The friendly
`query.timed_out` / `query.interrupted` catalog keys exist but are only used
in the entity-less blocking branch — the path players never hit. The timeout
also includes queue wait time, which the message does not explain.

*Recommendation:* map `QueryFailure` caused by cancellation to a user-facing
"query timed out after 5 s; narrow the window, filters, or limit and retry"
in the async delivery path.

### F3 — M — Inspection mode silently disables on permission loss; the triggering click then mutates the world

When a player's `itemgraph.command.inspect` grant is revoked while inspection
is active, `InspectionListener.onRightClickBlock` /
`onLeftClickBlock` call `inspections.clear(player.getUUID())` and return
without cancelling the event (`InspectionListener.java`, both handlers).
The player is told nothing, and the click that detected the revocation falls
through to vanilla: a left click **breaks the block the admin was trying to
inspect**. The same fall-through happens when an admin holds
`itemgraph.command.inspect` but lacks `itemgraph.audit`: block history is
denied with a permission message, the event is not cancelled, and the block
still breaks.

*Recommendation:* on revocation send an explicit "inspection disabled —
permission revoked" message; consider consuming the current click so a
denied inspection never mutates the evidence scene. Document the chosen
behavior in `docs/SECURITY_AND_PERMISSIONS.md` and `docs/QUERY_MODEL.md`
(the validator asserts exact routing text there).

*Resolved (issue #173):* both listeners now send the localized
`inspect.disabled_revoked` notice and consume the revocation-detecting
click on both loaders; missing-grant denials on block-history clicks are
consumed, while transient rejections and rejected container right-clicks
still fall through to vanilla.

### F4 — M — Revoked-permission feedback is inconsistent across async paths

`QueryDispatcher.deliver` silently drops output when `authorizedFor` fails at
delivery time (the early `return` in the `server.execute` block), while the
off-thread blocking branch and `dispatchDataAuthorized` both send
`permission.result_revoked` ("You no longer have permission to view this
ItemGraph result."). Same condition, two different behaviors across three
async paths.

*Recommendation:* unify on the explicit message (it is already authored and
translated); silent-drop is defensible as an anti-confirmation measure, but
the inconsistency itself confuses admins debugging access grants.

### F5 — L — Flow-browser page turns have no visible loading state

`BrowserSession.loading` guards re-entrant clicks and `showPage` computes a
`browser.loading_suffix` (" (loading)") title — but `showPage` only runs
after `session.loading = false`, so the suffix is unreachable and the
rendered controls are built with `!session.loading` always true. During a
page turn or detail open the user sees the old page with live-looking
controls that silently ignore clicks until the new menu opens.

*Recommendation:* either render a pending state before dispatching (set the
page label / disable controls in the current menu) or drop the dead
`loading_suffix` branch; a chat "loading page…" line per turn is a
lower-effort alternative.

### F6 — L — `/ig inspect on` omits the disable hint present in the toggle path

`inspect.enabled_full` (toggle path) ends with "use /ig inspect off to
disable"; `inspect.enabled` (deterministic `on` path) does not
(`ItemGraphCommands.inspectSet` vs `inspectToggle`; both strings in
`en_us.properties`, `nl_nl`, `zh_tw`).

*Recommendation:* align the two strings.

### F7 — L — `[Go to ...]` links do not disclose the consequence at the affordance

`sendLocationActions` renders `[Go to <dimension> <x> <y> <z>]` with
`RUN_COMMAND /ig goto <token>` and no hover text (`QueryDispatcher.java`).
The help topic explains it teleports only the clicker, but the link itself
does not say the click teleports.

*Recommendation:* add hover text such as "Teleports you (only you) to this
recorded location" on the link component.

### F8 — L — Large pages flood chat with one message per row

Each result row is its own `sendSuccess`/`sendSystemMessage`: a `limit=100`
lookup produces ~101 chat lines plus up to 8 `[Go to ...]` link lines plus
the `[Previous]/[Next]` action row (`sendFormattedLines`,
`sendLocationActions`, `sendActions`). Defaults (20 rows) keep this
reasonable, but there is no compact mode.

*Recommendation:* acceptable for now; if it becomes a complaint, a summary
mode (count + top N + "page for more") is the natural knob — do not remove
per-row hovers, they carry evidence identity.

### F9 — L — Help topics are English-only in localized catalogs

`CommandHelp.initializeMessages` registers all topic bodies through
`ItemGraphLanguage.sourceText`, which resolves `source.<sha256>` catalog
keys. Only 9 such keys exist (the hover labels: Evidence, Item, Canonical
metadata fingerprint, Event, UTC time, Origin, Destination, Previous,
Next). All ~35 help topic bodies, the overview lines, and the permission
recipes ship English under `nl_nl`/`zh_tw`. The `sourceFallbackInventory`
mechanism already exists to measure this gap.

*Recommendation:* this is a defensible scope choice (fallback stays readable
English, clients need no assets) — record it as a known localization limit
in `docs/CONFIGURATION.md` and consider translating the overview and
`journeys`/`permissions` topics first if coverage is ever extended.

### F10 — I — Unknown-help-topic error dumps ~35 topics on one line

`help.unknown_topic` appends `CommandHelp.validTopicsText()` — the entire
topic list in one comma-separated chat line. It is a correct recovery path
but visually heavy.

*Recommendation:* point to `/ig help commands` + list the closest matches
instead of the whole set.

### F11 — I — Deliberate design confirmations (preserve these)

- Transient inspect-request rejections fall through to vanilla, and
  rejected container right-clicks still open the chest normally even when
  `itemgraph.gui` is missing (`InspectionListener` + `FlowBrowserService.open`).
- `eventUuidNotFound` distinguishes malformed input from a well-formed but
  unmatched UUID and suggests the correct syntax (`QueryFormatter`).
- `/ig goto` tokens are one-use, 2-minute, player-bound, and re-check the
  originating query permission (`QueryDispatcher.consumeLocationGrant`).
- Page sessions are per-player, bounded to 8, and expire after 30 minutes;
  `/ig page` without a session names the recovery command.
- Every movement row carries an evidence-class label; confidence prints on
  every inferred line; superseded edges render a distinct header.
- The flow browser is read-only end to end: `quickMoveStack`,
  `canDragTo`, `canTakeItemForPickAll`, `clickMenuButton` all deny, and
  every click re-checks `itemgraph.gui` + `itemgraph.audit`.
- Brigadier `requires` hides nodes the source cannot use, so unauthorized
  subcommands do not appear in client tab-completion.
- `tools/validate_admin_ux_docs.py` enforces help/docs/code agreement;
  any change adopting F1–F6 must keep it green.

## 5. Assumptions and evidence gaps

- No live-server session was run; findings derive from source, catalogs, and
  the doc validator. F1/F2/F5 are confidence-high from code paths; perceived
  severity should be confirmed with one in-game pass (`/ig inspect on` +
  right-click chest, `/ig lookup` with a cold queue).
- Actor model assumes modded-permission deployments (explicit `false`
  denies) are in use; under the pure level-2 fallback F3/F4 are unreachable
  because grants never change mid-session.
- UX for the preview integration API (`ApiQueryBridge`) was out of scope —
  it is a server-mod boundary, not an admin journey; see `docs/API.md`.

## 6. Suggested follow-up backlog

Ordered by severity; each is a bounded change except F3, which touches
listener semantics and needs a documented decision on click consumption.

1. ~~F3 — inspect revocation notice + click-consumption decision~~ (resolved, #173).
2. F2 — user-facing timeout/cancellation message on the async path.
3. F1 — acceptance feedback for interactive queries.
4. F4 — unify revoked-permission result behavior.
5. F5 — visible in-menu page loading (or remove the dead suffix).
6. F6/F7/F10 — copy and affordance polish.
