package com.itemgraph.command;

import com.itemgraph.query.AuditEventQueryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maintained help text for the live /itemgraph command tree. */
final class CommandHelp {

    private static final String PERMISSION_LINE = "[ItemGraph] Permission: level 2.";

    static final List<String> TOPIC_NAMES = List.of(
            "help", "commands", "status", "audit", "ingest", "ingest now", "ingest history", "event", "explain",
            "lookup", "lookup near", "lookup page", "lookup player", "lookup filters", "lookup provenance",
            "page",
            "trace", "trace item", "trace player", "trace container",
            "gui", "gui item", "gui player", "gui container", "inspect");

    private static final Map<String, List<String>> TOPICS = topics();

    private CommandHelp() {}

    static List<String> overviewLines() {
        return List.of(
                "[ItemGraph] Start with your question. Permission level 2 is required; history queries are read-only.",
                "[ItemGraph] Where did an item go? /ig trace item <query> prints its time-ordered flow in chat; /ig gui item <query> opens the menu.",
                "[ItemGraph] Help: /ig help trace item or /ig help gui item. A flow trace lists item events and links; the menu lets you select a flow entry.",
                "[ItemGraph] What did a player/container record? /ig trace player <name> prints a timeline; /ig gui container <dimension> <x> <y> <z> opens a menu.",
                "[ItemGraph] Help: /ig help trace player or /ig help gui container. Output is chat for trace and a vanilla menu for gui.",
                "[ItemGraph] What happened nearby? /ig lookup near minecraft:overworld 120 64 -30 32 BREAK_BLOCK 20 1440 lists recorded block breaks.",
                "[ItemGraph] Help: /ig help lookup near. For event types, use /ig help lookup.",
                "[ItemGraph] What was recorded where I look? Run /ig inspect on, then left-click for block history or right-click; containers open the flow menu.",
                "[ItemGraph] Help: /ig help inspect. What supports a conclusion? /ig event <observationId> shows a recorded row; /ig explain <edgeId> explains an inferred link.",
                "[ItemGraph] Help: /ig help event or /ig help explain. /ig status shows service health; /ig audit checks integrity. Details: /ig help status or /ig help audit.",
                "[ItemGraph] /ig help commands lists paths. OBSERVED=recorded; INFERRED=proposed link; AMBIGUOUS/UNRESOLVED=uncertain. Stored evidence may miss world events and does not identify permanent items.",
                "[ItemGraph] Guide: https://github.com/DurdeuVlad/itemgraph/blob/main/docs/ADMIN_QUICK_START.md");
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

    static String validTopicsText() {
        return String.join(", ", TOPIC_NAMES);
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
                "[ItemGraph] COMMAND HELP — /ig help shows task routes; /ig help <topic> shows exact syntax and an example.",
                "[ItemGraph] ITEM FLOW — /ig trace item <query> [limit] [sinceMinutes]; /ig gui item <query> [sinceMinutes].",
                "[ItemGraph] PLAYER/CONTAINER — /ig trace player <playerName> [limit] [sinceMinutes]; /ig trace container <x> <y> <z> [limit] [sinceMinutes].",
                "[ItemGraph] FLOW MENU — /ig gui player <playerName> [sinceMinutes]; /ig gui container <dimension> <x> <y> <z> [sinceMinutes].",
                "[ItemGraph] AUDIT — /ig lookup <eventType> [limit] [sinceMinutes]; /ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes].",
                "[ItemGraph] AUDIT FILTERS — /ig lookup player <playerName> <eventType> [limit] [sinceMinutes]; /ig lookup page <page> <eventType> [limit] [sinceMinutes].",
                "[ItemGraph] FILTER/PROVENANCE — /ig lookup <filters...>; /ig lookup filters <filters...>; /ig lookup provenance <sourceSha256> <table> <sourceKey> [limit].",
                "[ItemGraph] MORE — /ig page <page> [session]; /ig inspect [on|off|status]; /ig event <observationId>; /ig explain <edgeId>.",
                "[ItemGraph] HEALTH/INGEST — /ig status; /ig audit; /ig ingest now. Optional read-only GriefLogger import: /ig ingest history (requires configured source DB).",
                "[ItemGraph] HELP TOPICS — help, commands, status, audit, ingest [now|history], event, explain, page, inspect.",
                "[ItemGraph] HELP TOPICS — lookup [near|page|player|filters|provenance], trace [item|player|container], gui [item|player|container].",
                "[ItemGraph] Configuration, the preview API, imports, and all workflows: see the Admin Quick Start in the ItemGraph project documentation.",
                "[ItemGraph] Example: /ig trace item diamond",
                "[ItemGraph] /itemgraph is the full command root; /ig is its alias. Configuration and the preview API are documented features, not commands."));
        topics.put("status", List.of(
                "[ItemGraph] Syntax: /ig status",
                "[ItemGraph] Shows ItemGraph version, GriefLogger mode, database/schema state, checkpoints, queue totals, and last ingest/correlation results; database reads are asynchronous.",
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
                "[ItemGraph] Example: /ig lookup BREAK_BLOCK 50 120");
        topics.put("lookup", lookup);
        topics.put("lookup near", List.of(
                "[ItemGraph] Syntax: /ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] dimension: exact resource location; x/y/z: block coordinates; radius is clamped to 1..1024 blocks.",
                "[ItemGraph] Results are native OBSERVED audit events in the selected cube. The read-only query runs asynchronously.",
                "[ItemGraph] Example: /ig lookup near minecraft:overworld 120 64 -30 32 BREAK_BLOCK 50 1440"));
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
                "[ItemGraph] Syntax: /ig lookup <filter1> [filter2] [filter3] [filter4] [filter5]",
                "[ItemGraph] The explicit extension spelling /ig lookup filters <filter1> ... is also accepted.",
                "[ItemGraph] Filters use name.value: action, user, include, exclude, time (m/h/d/y), and radius.",
                "[ItemGraph] action values cover native audit, item-flow, and transformation evidence (join, quit, chat, command_attempt, place_block, break_block, drop_item, pickup_item, craft, smelt, anvil_rename, anvil_repair, and more).",
                "[ItemGraph] radius is required, uses the issuing player's current dimension and position, and searches a cube clamped to 1..1024 blocks.",
                "[ItemGraph] include and exclude cannot be combined; values may be comma-separated and unified evidence results default to 10 rows (maximum 100) with source and evidence IDs.",
                "[ItemGraph] Example: /ig lookup action.break_block include.diamond_ore time.1h radius.50"));
        topics.put("lookup provenance", List.of(
                "[ItemGraph] Syntax: /ig lookup provenance <sourceSha256> <table> <sourceKey> [limit]",
                "[ItemGraph] Opens one exact imported GriefLogger row from ItemGraph's read-only ledger, including reference and identity tables.",
                "[ItemGraph] Results are labeled PROVENANCE_ONLY or UNRESOLVED and never contribute item quantity.",
                "[ItemGraph] Example: /ig lookup provenance 0123abcd... usernames pk:7 20"));
        List<String> ingest = List.of(
                "[ItemGraph] Syntax: /ig ingest now",
                "[ItemGraph] Queues one complete ingest-and-correlate cycle on the bounded background worker.",
                "[ItemGraph] Example: /ig ingest now");
        topics.put("ingest", ingest);
        topics.put("ingest now", ingest);
        topics.put("ingest history", List.of(
                "[ItemGraph] Syntax: /ig ingest history",
                "[ItemGraph] Queues the eleven-table GriefLogger historical importer on the bounded background worker.",
                "[ItemGraph] The source database is opened read-only; ItemGraph stores source/schema fingerprints, checkpoints, opaque bytes, and unresolved reasons.",
                "[ItemGraph] Example: /ig ingest history"));
        topics.put("event", List.of(
                "[ItemGraph] Syntax: /ig event <observationId>",
                "[ItemGraph] observationId: positive long. Shows one raw OBSERVED row with source, endpoints, item, amount, timing, and correlation metadata.",
                "[ItemGraph] The query is read-only and asynchronous. Example: /ig event 633"));
        topics.put("explain", List.of(
                "[ItemGraph] Syntax: /ig explain <edgeId>",
                "[ItemGraph] edgeId: positive long. Shows one inferred edge, deterministic confidence, explanation, and every supporting observation ID.",
                "[ItemGraph] Inferred edges are explanations, not direct evidence. The query is read-only and asynchronous. Example: /ig explain 8"));
        topics.put("trace", List.of(
                "[ItemGraph] Syntax: /ig trace item <query> [limit] [sinceMinutes]",
                "[ItemGraph] Syntax: /ig trace player <playerName> [limit] [sinceMinutes]",
                "[ItemGraph] Syntax: /ig trace container <x> <y> <z> [limit] [sinceMinutes]",
                "[ItemGraph] limit: default 20, maximum 100. sinceMinutes: positive minutes; omitted means all recorded history.",
                "[ItemGraph] Trace queries are read-only and asynchronous. Output distinguishes OBSERVED rows from inferred edges and preserves ambiguous/UNKNOWN endpoints.",
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
                "[ItemGraph] sinceMinutes: positive minutes; omitted means all history. Candidate lists cap at 10; timelines cap at 45 entries per page.",
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
                "[ItemGraph] While enabled, left-click inspects that block; right-click a Container opens its flow browser; a right-click on a non-container opens paginated block history (ordinary blocks target the block on the clicked face).",
                "[ItemGraph] The click is consumed only after the read-only request is accepted, so held items are not used and inspection is not transfer evidence. State clears on logout and server stop.",
                "[ItemGraph] Example: /ig inspect on");
        topics.put("inspect", inspect);
        return Map.copyOf(topics);
    }
}
