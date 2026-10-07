package com.itemgraph.command;

import com.itemgraph.i18n.ItemGraphLanguage;

import com.itemgraph.query.AuditEventQueryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maintained help text for the live /itemgraph command tree. */
public final class CommandHelp {

    private static final String PERMISSION_LINE = "[ItemGraph] Every /ig command needs itemgraph.command; Unset grants use level 2.";

    static final List<String> TOPIC_NAMES = List.of(
            "help", "commands", "permissions", "guide", "status", "audit", "ingest", "ingest now", "ingest history", "event", "explain", "journeys",
            "journeys inspect", "journeys trace", "journeys near", "journeys filters",
            "goto",
            "lookup", "lookup near", "lookup page", "lookup player", "lookup filters", "lookup provenance",
            "lookup admin", "lookup lifecycle", "lookup transformations",
            "page",
            "trace", "trace item", "trace player", "trace container",
            "gui", "gui item", "gui player", "gui container", "inspect");

    private static final Map<String, List<String>> TOPICS = topics();

    private CommandHelp() {}

    static List<String> overviewLines() {
        return List.of(
                "[ItemGraph] Trace: /ig trace item <query>; commands: /ig help commands.",
                "[ItemGraph] Nearby: /ig help lookup near; tasks: /ig help journeys.",
                "[ItemGraph] Inspect: /ig inspect on (click blocks/containers).",
                "[ItemGraph] Evidence: /ig event <id>; inferred edge: /ig explain <id>.",
                "[ItemGraph] Required: itemgraph.command; permissions: /ig help permissions.");
    }

    /** Registers every static help sentence so catalog keys can be validated before database startup. */
    public static void initializeMessages() {
        ItemGraphLanguage.sourceText("[ItemGraph] ");
        overviewLines().forEach(ItemGraphLanguage::sourceText);
        List.of("Evidence", "Item", "Canonical metadata fingerprint", "Event", "UTC time", "Origin",
                "Destination", "Previous", "Next").forEach(ItemGraphLanguage::sourceText);
        for (String topic : TOPIC_NAMES) {
            List<String> lines = topicLines(topic);
            if (lines != null) lines.forEach(ItemGraphLanguage::sourceText);
        }
    }

    static List<String> topicLines(String topic) {
        List<String> lines = TOPICS.get(normalize(topic));
        if (lines == null) {
            return null;
        }
        List<String> withPermission = new ArrayList<>(lines.size() + 1);
        withPermission.add(PERMISSION_LINE);
        withPermission.addAll(lines);
        return List.copyOf(withPermission);
    }

    /**
     * Returns up to five topic names nearest to the typed value — substring matches first,
     * then names within a small edit distance — so an unknown-topic message can point at
     * recovery candidates instead of dumping every topic.
     */
    static String closestTopicsText(String topic) {
        String normalized = normalize(topic);
        if (normalized.isEmpty()) {
            return "";
        }
        List<String> substring = TOPIC_NAMES.stream()
                .filter(name -> name.contains(normalized) || normalized.contains(name))
                .limit(5)
                .toList();
        if (!substring.isEmpty() || normalized.length() < 4) {
            return String.join(", ", substring);
        }
        return TOPIC_NAMES.stream()
                .map(name -> Map.entry(name, editDistance(name, normalized)))
                .filter(entry -> entry.getValue() <= 3)
                .sorted(Map.Entry.comparingByValue())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static int editDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            costs[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int previous = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int current = costs[j];
                costs[j] = Math.min(Math.min(costs[j - 1] + 1, costs[j] + 1),
                        previous + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                previous = current;
            }
        }
        return costs[b.length()];
    }

    private static String normalize(String topic) {
        return topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT)
                .replace('.', ' ').replace('-', ' ').replace('_', ' ')
                .replaceAll("\\s+", " ");
    }

    private static Map<String, List<String>> topics() {
        Map<String, List<String>> topics = new LinkedHashMap<>();
        topics.put("help", List.of(
                "[ItemGraph] Syntax: /ig help [topic]",
                "[ItemGraph] Start with an admin question; use /ig help commands to find a command or /ig help <topic> for exact syntax.",
                "[ItemGraph] Example: /ig help trace item"));
        topics.put("commands", List.of(
                "[ItemGraph] ITEM: /ig trace item diamond; /ig help trace item.",
                "[ItemGraph] GUI: /ig gui item stone; /ig help gui; /ig gui container.",
                "[ItemGraph] PLAYER: /ig trace player <playerName>; /ig help trace player.",
                "[ItemGraph] CONTAINER: /ig trace container; /ig help trace container.",
                "[ItemGraph] AUDIT: /ig lookup near; /ig help lookup; /ig inspect.",
                "[ItemGraph] EVIDENCE: /ig event <observationId>; /ig explain <edgeId>.",
                "[ItemGraph] PAGES: /ig page <page>; /ig goto <token> is click-only.",
                "[ItemGraph] OPS: /ig status; /ig audit; /ig ingest history; /ig ingest now.",
                "[ItemGraph] Example: /ig help trace item; /ig help <topic>; /ig help guide."));
        topics.put("permissions", List.of(
                "[ItemGraph] All commands need itemgraph.command; status/help need only it.",
                "[ItemGraph] Example lookup: itemgraph.command + itemgraph.command.lookup.",
                "[ItemGraph] Protected lookup adds itemgraph.audit.",
                "[ItemGraph] For provenance chats/commands, add itemgraph.audit to lookup.",
                "[ItemGraph] Other provenance tables: itemgraph.command.lookup only.",
                "[ItemGraph] /ig audit: itemgraph.command + itemgraph.audit.",
                "[ItemGraph] Saved pages: add itemgraph.command.page to lookup grants.",
                "[ItemGraph] Trace: itemgraph.command + itemgraph.trace + itemgraph.audit.",
                "[ItemGraph] Event detail: itemgraph.command + itemgraph.event + itemgraph.audit.",
                "[ItemGraph] Explain: itemgraph.command + itemgraph.explain + itemgraph.audit.",
                "[ItemGraph] Inspect on/off: itemgraph.command + itemgraph.command.inspect.",
                "[ItemGraph] Block history in Inspect also needs itemgraph.audit.",
                "[ItemGraph] GUI in Inspect: add itemgraph.gui + itemgraph.audit.",
                "[ItemGraph] Direct GUI: itemgraph.command + itemgraph.gui + itemgraph.audit.",
                "[ItemGraph] /ig ingest now: itemgraph.command + itemgraph.ingest.",
                "[ItemGraph] Import: itemgraph.command + itemgraph.ingest + itemgraph.import.",
                "[ItemGraph] Explicit false denies; unset nodes use level 2.",
                "[ItemGraph] Dotted nodes do not inherit; grant each named permission.",
                "[ItemGraph] Matrix/provider behavior: docs/SECURITY_AND_PERMISSIONS.md."));
        topics.put("journeys", List.of(
                "[ItemGraph] Inspect: /ig help journeys inspect; trace: /ig help journeys trace.",
                "[ItemGraph] Nearby: /ig help journeys near; filtered: /ig help journeys filters.",
                "[ItemGraph] Trace observation IDs use /ig event <observationId>.",
                "[ItemGraph] Inferred edge IDs use /ig explain <edgeId>.",
                "[ItemGraph] Admin/creative outcome UUIDs use /ig event event:<uuid>.",
                "[ItemGraph] Player paging: /ig page 2; console near returns one page.",
                "[ItemGraph] Direct event pages have no nearby scope: /ig lookup page.",
                "[ItemGraph] Roles: /ig help guide; exact nodes: /ig help permissions.",
                "[ItemGraph] Example: /ig help journeys"));
        topics.put("journeys inspect", List.of(
                "[ItemGraph] Start: /ig inspect on; left-click blocks or right-click containers.",
                "[ItemGraph] History is raw audit events; some rows are UNRESOLVED.",
                "[ItemGraph] Copy an event type to query: /ig lookup <eventType>.",
                "[ItemGraph] Container rows label OBSERVED or INFERRED evidence.",
                "[ItemGraph] OBS ID: /ig event <observationId>; edge: /ig explain <edgeId>.",
                "[ItemGraph] Perm: itemgraph.command + itemgraph.command.inspect.",
                "[ItemGraph] History needs itemgraph.audit; containers also need itemgraph.gui.",
                "[ItemGraph] Empty: check capture time, supported event, target, and grants.",
                "[ItemGraph] Also check dimension and time range; empty is not proof of no event.",
                "[ItemGraph] Finish: /ig inspect off; logout/server stop also clears it.",
                "[ItemGraph] Example: /ig help journeys inspect"));
        topics.put("journeys trace", List.of(
                "[ItemGraph] Start: /ig trace item <query> follows an item or item type.",
                "[ItemGraph] OBS ID: /ig event <observationId> opens raw evidence.",
                "[ItemGraph] INFERRED edge: /ig explain <edgeId> shows its evidence and reason.",
                "[ItemGraph] Perm: itemgraph.command + itemgraph.trace + itemgraph.audit.",
                "[ItemGraph] Detail adds itemgraph.event or itemgraph.explain, respectively.",
                "[ItemGraph] Ambiguous matches are candidates; choose the matching fingerprint.",
                "[ItemGraph] Filter one: /ig trace item \"id:<fingerprintId>\".",
                "[ItemGraph] Empty: check capture, item or target, time window, and grants.",
                "[ItemGraph] Also confirm that the event type is supported; empty is not proof.",
                "[ItemGraph] Example: /ig help journeys trace"));
        topics.put("journeys near", List.of(
                "[ItemGraph] Start: /ig lookup near; syntax: /ig help lookup near.",
                "[ItemGraph] Raw audit rows; some can be UNRESOLVED.",
                "[ItemGraph] Type: /ig lookup <eventType>.",
                "[ItemGraph] Grant: itemgraph.command.lookup.",
                "[ItemGraph] Protected rows also need itemgraph.audit.",
                "[ItemGraph] Pages: /ig page 2 + itemgraph.command.page.",
                "[ItemGraph] Console near is one page; it saves no coordinates.",
                "[ItemGraph] /ig lookup page is not nearby-scoped.",
                "[ItemGraph] Empty? Check capture, type, coordinates, radius, and grants.",
                "[ItemGraph] No match does not prove no event occurred.",
                "[ItemGraph] Example: /ig help journeys near"));
        topics.put("journeys filters", List.of(
                "[ItemGraph] Start: /ig lookup <filters...>; see /ig help lookup filters.",
                "[ItemGraph] OBSERVED: observation#N -> /ig event N; others stay inline.",
                "[ItemGraph] Detail: itemgraph.event + itemgraph.audit.",
                "[ItemGraph] INFERRED: proposal; see /ig help journeys trace.",
                "[ItemGraph] AMBIGUOUS: choose a candidate; see /ig help journeys trace.",
                "[ItemGraph] UNRESOLVED: event:<uuid> only if the row prints it.",
                "[ItemGraph] PROVENANCE_ONLY: imported history, not movement.",
                "[ItemGraph] Lookup: itemgraph.command.lookup; protected: itemgraph.audit.",
                "[ItemGraph] GUI: player + itemgraph.gui + itemgraph.audit.",
                "[ItemGraph] Pages: itemgraph.command.page + original lookup grants.",
                "[ItemGraph] Radius needs player; console uses /ig lookup near; empty ≠ proof."));
        topics.put("guide", List.of(
                "[ItemGraph] Admin quick start: https://github.com/DurdeuVlad/itemgraph/blob/main/docs/ADMIN_QUICK_START.md",
                "[ItemGraph] Example: open the guide before configuring access or running the first capture check."));
        topics.put("status", List.of(
                "[ItemGraph] Syntax: /ig status",
                "[ItemGraph] ACTION reports DB/capture state and a safe next step when known.",
                "[ItemGraph] No reported stop still does not prove event coverage.",
                "[ItemGraph] DIAGNOSTIC counters follow; they do not form a health score.",
                "[ItemGraph] RUNNING means the worker started, not database health or capture success. Check captureState, database, dropped, lastCycle, and lastPass.",
                "[ItemGraph] Status shows facts, not calibrated health thresholds. See the admin quick start for units, reset windows, and recovery next steps.",
                "[ItemGraph] Example: /ig status"));
        topics.put("audit", List.of(
                "[ItemGraph] Syntax: /ig audit",
                "[ItemGraph] Runs a read-only invariant audit off the server thread: conservation, positivity, relational integrity, and allocation state.",
                "[ItemGraph] Example: /ig audit"));
        topics.put("page", List.of(
                "[ItemGraph] Syntax: /ig page <page> [session]",
                "[ItemGraph] Continues the issuing player's last lookup with the same filters and bounded page size.",
                "[ItemGraph] Page state is per-player and expires after 30 minutes. Generated buttons carry the optional session token so older results stay bound to their query.",
                "[ItemGraph] Example: /ig page 2"));
        List<String> lookup = List.of(
                "[ItemGraph] Syntax: /ig lookup <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] eventType: " + String.join(", ", AuditEventQueryService.EVENT_TYPES) + ".",
                "[ItemGraph] Results are native OBSERVED evidence from ig_audit_events; limit defaults to 20 and is capped at 100.",
                "[ItemGraph] /ig lookup near clamps radius to 1..1024 blocks and requires an exact dimension id.",
                "[ItemGraph] Pages are 1-based; offsets are capped at 10,000 rows.",
                "[ItemGraph] Focused help: /ig help lookup admin; /ig help lookup lifecycle.",
                "[ItemGraph] Transformations: /ig help lookup transformations.",
                "[ItemGraph] Example: /ig lookup BREAK_BLOCK 50 120");
        topics.put("lookup", lookup);
        topics.put("lookup near", List.of(
                "[ItemGraph] Syntax: /ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] dimension: exact resource location; x/y/z: block coordinates; radius is clamped to 1..1024 blocks.",
                "[ItemGraph] Results are native OBSERVED audit events in the selected cube. The read-only query runs asynchronously.",
                "[ItemGraph] Example: /ig lookup near minecraft:overworld 120 64 -30 32 BREAK_BLOCK 50 1440"));
        topics.put("lookup admin", List.of(
                "[ItemGraph] Query ADMIN_ITEM_COMMAND_EFFECT; use near for coordinates.",
                "[ItemGraph] Other outcomes: ADMIN_ITEM_COMMAND_ATTEMPT,",
                "[ItemGraph] ADMIN_ITEM_COMMAND_FAILURE, ADMIN_ITEM_COMMAND_UNRESOLVED.",
                "[ItemGraph] Perm: itemgraph.command + itemgraph.command.lookup +",
                "[ItemGraph] itemgraph.audit.",
                "[ItemGraph] Records do not expose command arguments. Near syntax:",
                "[ItemGraph] /ig help lookup near.",
                "[ItemGraph] Trace follow-up needs itemgraph.trace + itemgraph.audit.",
                "[ItemGraph] Follow an item separately with /ig trace item \"id:<fingerprintId>\".",
                "[ItemGraph] Example: /ig lookup ADMIN_ITEM_COMMAND_EFFECT 50 1440"));
        topics.put("lookup lifecycle", List.of(
                "[ItemGraph] Query KILL_ENTITY, THROW_ITEM, SHOOT_ITEM, or",
                "[ItemGraph] PROJECTILE_SPAWN_ACCEPTED.",
                "[ItemGraph] Syntax: /ig lookup <eventType> [limit] [sinceMinutes].",
                "[ItemGraph] Perm: itemgraph.command + itemgraph.command.lookup +",
                "[ItemGraph] itemgraph.audit; results include exact locations.",
                "[ItemGraph] Throw/shoot are attempts; accepted spawn does not prove impact.",
                "[ItemGraph] Death-drop trace needs itemgraph.trace + itemgraph.audit.",
                "[ItemGraph] A death-drop trace does not identify the killer or cause.",
                "[ItemGraph] Example: /ig lookup THROW_ITEM 50 1440"));
        topics.put("lookup transformations", List.of(
                "[ItemGraph] Craft/smelt output only; input is unknown; no lineage edge.",
                "[ItemGraph] Types: CRAFT_OUTPUT_UNRESOLVED, SMELT_OUTPUT_UNRESOLVED.",
                "[ItemGraph] Example: /ig lookup CRAFT_OUTPUT_UNRESOLVED 50 1440",
                "[ItemGraph] Detail: /ig event event:<evidence-uuid>.",
                "[ItemGraph] Filters: action.craft, action.smelt, action.anvil_rename/repair.",
                "[ItemGraph] Rename/repair: /ig trace item <item-id>; /ig help journeys trace.",
                "[ItemGraph] Lookup: itemgraph.command + itemgraph.command.lookup +",
                "[ItemGraph] itemgraph.audit.",
                "[ItemGraph] Trace follow-up needs itemgraph.trace + itemgraph.audit.",
                "[ItemGraph] Legacy CRAFT/SMELT is unresolved; trades are not captured yet:",
                "[ItemGraph] enchanting, brewing, smithing, grindstone, and loot."));
        topics.put("lookup page", List.of(
                "[ItemGraph] Syntax: /ig lookup page <page> <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] page is 1-based; limit defaults to 20 and is capped at 100; sinceMinutes is omitted for all history.",
                "[ItemGraph] This directly queries a bounded audit result page; /ig page <page> continues the issuing player's saved lookup session.",
                "[ItemGraph] Example: /ig lookup page 2 BREAK_BLOCK 50 1440"));
        topics.put("lookup player", List.of(
                "[ItemGraph] Syntax: /ig lookup player <playerName> <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] playerName is an exact stored player name; eventType uses the same values as /ig lookup.",
                "[ItemGraph] Example: /ig lookup player Alex COMMAND_ATTEMPT 50 1440"));
        topics.put("lookup filters", List.of(
                "[ItemGraph] Start: /ig lookup <filters...>; grant itemgraph.command.lookup.",
                "[ItemGraph] Filters: action, user, include, exclude, time, radius; see guide.",
                "[ItemGraph] Alias: /ig lookup filters.",
                "[ItemGraph] Player-only: radius uses your position/dimension; max 1024.",
                "[ItemGraph] From console: /ig lookup near; syntax: /ig help lookup near.",
                "[ItemGraph] Include/exclude are exclusive; values may be comma-separated.",
                "[ItemGraph] Results: 10 default, 100 max; source and evidence IDs included.",
                "[ItemGraph] observation#N -> /ig event N; other source IDs stay inline.",
                "[ItemGraph] Event detail: itemgraph.event + itemgraph.audit.",
                "[ItemGraph] Protected: itemgraph.audit. Pages: itemgraph.command.page.",
                "[ItemGraph] Provenance: /ig help lookup provenance."));
        topics.put("lookup provenance", List.of(
                "[ItemGraph] Syntax: /ig lookup provenance <sourceSha256> <table> <sourceKey> [limit]",
                "[ItemGraph] Opens one exact imported GriefLogger row from ItemGraph's read-only ledger, including reference and identity tables.",
                "[ItemGraph] Results are labeled PROVENANCE_ONLY or UNRESOLVED and never contribute item quantity.",
                "[ItemGraph] Example: /ig lookup provenance 0123abcd... usernames pk:7 20"));
        List<String> ingest = List.of(
                "[ItemGraph] Syntax: /ig ingest now",
                "[ItemGraph] Optional GriefLogger source sync; it queues ingest and correlation work.",
                "[ItemGraph] Native event capture runs automatically when enabled; this command is not required.",
                "[ItemGraph] Enable the GriefLogger source integration in config before using it.",
                "[ItemGraph] Example: /ig ingest now");
        topics.put("ingest", ingest);
        topics.put("ingest now", ingest);
        topics.put("ingest history", List.of(
                "[ItemGraph] Syntax: /ig ingest history",
                "[ItemGraph] Queues the eleven-table GriefLogger historical importer on the bounded background worker.",
                "[ItemGraph] The source database is opened read-only; ItemGraph stores source/schema fingerprints, checkpoints, opaque bytes, and unresolved reasons.",
                "[ItemGraph] Example: /ig ingest history"));
        topics.put("goto", List.of(
                "[ItemGraph] /ig goto <token> is the private action behind a displayed [Go to ...] link; do not type a token manually.",
                "[ItemGraph] Tokens are bound to the requesting player, expire after two minutes, work once, and recheck the originating query permission.",
                "[ItemGraph] Example: click a displayed [Go to ...] result link."));
        topics.put("event", List.of(
                "[ItemGraph] Syntax: /ig event <observationId> or /ig event event:<uuid>",
                "[ItemGraph] Requires itemgraph.event and itemgraph.audit because raw item evidence may contain administrative or creative-inventory records.",
                "[ItemGraph] Use a positive observation ID, or prefix a related observation, transformation, or unresolved-event UUID from an admin/creative outcome with event: to open that raw evidence.",
                "[ItemGraph] Hover a result line for bounded item fingerprint, evidence class, event kind, UTC time, and endpoint details; click [Go to ...] to move only yourself to a recorded location. The link is one-use and expires after two minutes.",
                "[ItemGraph] The query is read-only and asynchronous. Example: /ig event 633 or /ig event event:123e4567-e89b-12d3-a456-426614174000"));
        topics.put("explain", List.of(
                "[ItemGraph] Syntax: /ig explain <edgeId>",
                "[ItemGraph] Requires itemgraph.explain and itemgraph.audit because an inferred edge can cite protected observations.",
                "[ItemGraph] edgeId: positive long. Shows one inferred edge, deterministic confidence, explanation, and every supporting observation ID.",
                "[ItemGraph] The edge summary hover shows bounded item fingerprint, confidence class, UTC time, and endpoints; cited observation lines are not mislabeled as inferred. Location links recheck permissions when clicked.",
                "[ItemGraph] Inferred edges are explanations, not direct evidence. The query is read-only and asynchronous. Example: /ig explain 8"));
        topics.put("trace", List.of(
                "[ItemGraph] Syntax: /ig trace item <query> [limit] [sinceMinutes]",
                "[ItemGraph] Syntax: /ig trace player <playerName> [limit] [sinceMinutes]",
                "[ItemGraph] Syntax: /ig trace container <x> <y> <z> [limit] [sinceMinutes]",
                "[ItemGraph] limit: default 20, maximum 100. sinceMinutes: positive minutes; omitted means all recorded history.",
                "[ItemGraph] Requires itemgraph.trace and itemgraph.audit; protected-only candidates and inferred edges are hidden by denying the whole query before lookup.",
                "[ItemGraph] Trace queries are read-only and asynchronous. Output distinguishes OBSERVED rows from inferred edges and preserves ambiguous/UNKNOWN endpoints.",
                "[ItemGraph] Hover a result row for canonical fingerprint hash, event kind, exact UTC time, and endpoints. Click [Go to ...] to move only yourself; the one-use link rechecks the exact trace permission.",
                "[ItemGraph] Examples: /ig trace item stone | /ig trace player PlayerA 50 60 | /ig trace container -39 112 -8 20 120"));
        topics.put("trace item", List.of(
                "[ItemGraph] Syntax: /ig trace item <query> [limit] [sinceMinutes]",
                "[ItemGraph] query: registry ID, custom-name text, numeric fingerprint candidate, or quoted \"id:<fingerprintId>\".",
                "[ItemGraph] limit: default 20, maximum 100. sinceMinutes: positive minutes; omitted means all history.",
                "[ItemGraph] Ambiguous matches list candidates instead of choosing one. The query is read-only and asynchronous. Example: /ig trace item netherite_boots 20 120"));
        topics.put("trace player", List.of(
                "[ItemGraph] Syntax: /ig trace player <playerName> [limit] [sinceMinutes]",
                "[ItemGraph] playerName: exact stored player label; duplicate stored nodes are listed rather than silently selected.",
                "[ItemGraph] limit: default 20, maximum 100. sinceMinutes: positive minutes; omitted means all history.",
                "[ItemGraph] The query is read-only and asynchronous. Example: /ig trace player PlayerA 50 60"));
        topics.put("trace container", List.of(
                "[ItemGraph] Syntax: /ig trace container <x> <y> <z> [limit] [sinceMinutes]",
                "[ItemGraph] x/y/z: block coordinates. Multiple matching nodes or dimensions are listed rather than silently selected.",
                "[ItemGraph] limit: default 20, maximum 100. sinceMinutes: positive minutes; omitted means all history.",
                "[ItemGraph] The query is read-only and asynchronous. Example: /ig trace container -39 112 -8 20 120"));
        topics.put("gui", List.of(
                "[ItemGraph] Syntax: /ig gui item <query> [sinceMinutes]",
                "[ItemGraph] Syntax: /ig gui player <playerName> [sinceMinutes]",
                "[ItemGraph] Syntax: /ig gui container <dimension> <x> <y> <z> [sinceMinutes]",
                "[ItemGraph] Player-only read-only vanilla six-row browser; no custom item, screen, packet, or inventory movement. Timeline lookups are asynchronous.",
                "[ItemGraph] Requires itemgraph.gui and itemgraph.audit because a timeline can include protected evidence; the menu rechecks both on every click.",
                "[ItemGraph] sinceMinutes: positive minutes; omitted means all history. Pages show up to nine rows (also capped by query.max_page_size) with matching numbered chat labels; candidate lists cap at 10 and use a separate page.",
                "[ItemGraph] Rows label observed, inferred, ambiguous, transformation, or unresolved states; missing item identities use an explicit fallback.",
                "[ItemGraph] Examples: /ig gui item stone | /ig gui player PlayerA | /ig gui container minecraft:overworld -39 112 -8"));
        topics.put("gui item", List.of(
                "[ItemGraph] Syntax: /ig gui item <query> [sinceMinutes]",
                "[ItemGraph] Uses the same item resolver as /ig trace item and opens candidates or a read-only timeline in the vanilla browser; lookup is asynchronous.",
                "[ItemGraph] Example: /ig gui item stone 120"));
        topics.put("gui player", List.of(
                "[ItemGraph] Syntax: /ig gui player <playerName> [sinceMinutes]",
                "[ItemGraph] Uses the exact stored player-node resolver and opens candidates or a read-only timeline in the vanilla browser; lookup is asynchronous.",
                "[ItemGraph] Example: /ig gui player PlayerA 120"));
        topics.put("gui container", List.of(
                "[ItemGraph] Syntax: /ig gui container <dimension> <x> <y> <z> [sinceMinutes]",
                "[ItemGraph] dimension: exact resource location such as minecraft:overworld; x/y/z: exact block coordinates; lookup is asynchronous.",
                "[ItemGraph] Example: /ig gui container minecraft:overworld -39 112 -8 120"));
        List<String> inspect = List.of(
                "[ItemGraph] Syntax: /ig inspect [on|off|status]",
                "[ItemGraph] The command and each supported click enforce the same permission check. /ig inspect toggles; /ig inspect on enables; /ig inspect off disables; /ig inspect status reports without changing.",
                "[ItemGraph] Toggle, status, and click recognition require itemgraph.command.inspect. Block history reads eventType=all and also requires itemgraph.audit; the container flow browser separately requires itemgraph.gui and itemgraph.audit.",
                "[ItemGraph] While enabled, left-click inspects that block; right-click a Container opens its flow browser; a right-click on a non-container opens paginated block history (ordinary blocks target the block on the clicked face).",
                "[ItemGraph] An accepted request consumes the click. A request denied for a missing grant, and a click that detects a revoked inspect permission, is also consumed — denied clicks never break or toggle blocks — while transient rejections and rejected container right-clicks keep vanilla behavior. Inspection is not transfer evidence. State clears on logout and server stop.",
                "[ItemGraph] Example: /ig inspect on");
        topics.put("inspect", inspect);
        return Map.copyOf(topics);
    }
}
