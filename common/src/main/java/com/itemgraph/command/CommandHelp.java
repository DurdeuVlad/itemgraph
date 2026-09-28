package com.itemgraph.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maintained help text for the live /itemgraph command tree. */
final class CommandHelp {

    private static final String PERMISSION_LINE = "[ItemGraph] Permission: level 2.";

    static final List<String> TOPIC_NAMES = List.of(
            "help", "status", "audit", "ingest", "ingest now", "event", "explain",
            "trace", "trace item", "trace player", "trace container",
            "gui", "gui item", "gui player", "gui container", "inspect");

    private static final Map<String, List<String>> TOPICS = topics();

    private CommandHelp() {}

    static List<String> overviewLines() {
        return List.of(
                "[ItemGraph] /itemgraph is the full command root; /ig is its alias. Permission level 2 is required.",
                "[ItemGraph] /ig help [topic] — show all commands or one topic.",
                "[ItemGraph] /ig status — show mod, GriefLogger, database, ingestion, and inference state.",
                "[ItemGraph] /ig audit — verify ItemGraph database invariants asynchronously.",
                "[ItemGraph] /ig ingest now — queue one complete ingest and correlate cycle.",
                "[ItemGraph] /ig event <observationId> — show one raw observed evidence row.",
                "[ItemGraph] /ig explain <edgeId> — show one inferred edge, confidence, and cited evidence.",
                "[ItemGraph] /ig trace item <query> [limit] [sinceMinutes]",
                "[ItemGraph] /ig trace player <playerName> [limit] [sinceMinutes]",
                "[ItemGraph] /ig trace container <x> <y> <z> [limit] [sinceMinutes]",
                "[ItemGraph] /ig gui item <query> [sinceMinutes]",
                "[ItemGraph] /ig gui player <playerName> [sinceMinutes]",
                "[ItemGraph] /ig gui container <dimension> <x> <y> <z> [sinceMinutes]",
                "[ItemGraph] /ig inspect [on|off|status] — toggle in-world container inspection.",
                "[ItemGraph] Defaults: limit=20, capped at 100; sinceMinutes is omitted for all history and uses minutes when present.",
                "[ItemGraph] Historical reads are asynchronous and read-only. OBSERVED rows stay distinct from inferred edges, ambiguity, UNKNOWN endpoints, and confidence.");
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
                "[ItemGraph] Lists the live command tree or one detailed topic. /itemgraph is the full root; /ig is its alias.",
                "[ItemGraph] Example: /ig help trace item"));
        topics.put("status", List.of(
                "[ItemGraph] Syntax: /ig status",
                "[ItemGraph] Shows ItemGraph version, GriefLogger mode, database/schema state, checkpoints, queue totals, and last ingest/correlation results; database reads are asynchronous.",
                "[ItemGraph] Example: /ig status"));
        topics.put("audit", List.of(
                "[ItemGraph] Syntax: /ig audit",
                "[ItemGraph] Runs a read-only invariant audit off the server thread: conservation, positivity, relational integrity, and allocation state.",
                "[ItemGraph] Example: /ig audit"));
        List<String> ingest = List.of(
                "[ItemGraph] Syntax: /ig ingest now",
                "[ItemGraph] Queues one complete ingest-and-correlate cycle on the bounded background worker.",
                "[ItemGraph] Example: /ig ingest now");
        topics.put("ingest", ingest);
        topics.put("ingest now", ingest);
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
                "[ItemGraph] While enabled, right-click a supported block-entity Container to open its read-only flow browser for the exact dimension and coordinates.",
                "[ItemGraph] No wand or ItemGraph item is registered. The click does not consume the held item or become transfer evidence; state clears on logout and server stop.",
                "[ItemGraph] Example: /ig inspect on");
        topics.put("inspect", inspect);
        return Map.copyOf(topics);
    }
}
