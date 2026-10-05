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
            "help", "status", "audit", "ingest", "ingest now", "ingest history", "event", "explain",
            "lookup", "lookup player", "lookup filters", "export", "export verify", "export cancel",
            "page",
            "trace", "trace item", "trace item-filtered", "trace player", "trace container",
            "gui", "gui item", "gui item-filtered", "gui player", "gui container", "inspect");

    private static final Map<String, List<String>> TOPICS = topics();

    private CommandHelp() {}

    static List<String> overviewLines() {
        return List.of(
                "[ItemGraph] /itemgraph is the full command root; /ig is its alias. Permission level 2 is required.",
                "[ItemGraph] /ig help [topic] — show all commands or one topic.",
                "[ItemGraph] /ig status — show mod, GriefLogger, database, ingestion, and inference state.",
                "[ItemGraph] /ig audit — verify ItemGraph database invariants asynchronously.",
                "[ItemGraph] /ig export <filename> <filters> — write a redacted, bounded, hash-chained incident bundle.",
                "[ItemGraph] /ig export full <filename> <filters> — write a full-detail bundle (permission level 4).",
                "[ItemGraph] /ig export verify <filename> — verify a bundle's manifest and evidence chain.",
                "[ItemGraph] /ig export cancel <jobId> — cancel an active export or verification job.",
                "[ItemGraph] /ig page <page> — continue the issuing player's active lookup session.",
                "[ItemGraph] /ig lookup <eventType> [limit] [sinceMinutes] — query native audit events.",
                "[ItemGraph] /ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes] — bound results to a location.",
                "[ItemGraph] /ig lookup page <page> <eventType> [limit] [sinceMinutes] — continue a bounded audit result page.",
                "[ItemGraph] /ig lookup player <playerName> <eventType> [limit] [sinceMinutes] — filter native audit events by player.",
                "[ItemGraph] /ig lookup <filter1> [filter2] ... — action/user/include/exclude/time/radius/state and exact item-metadata lookup (maximum 12; radius required; default page size 10).",
                "[ItemGraph] /ig lookup filters <filter1> [filter2] ... — explicit ItemGraph spelling for the same filtered lookup; item metadata filters require permission level 4.",
                "[ItemGraph] /ig lookup provenance <sourceSha256> <table> <sourceKey> [limit] — exact read-only lookup of an imported GriefLogger row, including reference and identity tables.",
                "[ItemGraph] /ig ingest now — queue one complete ingest and correlate cycle.",
                "[ItemGraph] /ig ingest history — queue a bounded, read-only import of all present GriefLogger history tables.",
                "[ItemGraph] /ig event <observationId> — show one raw observed evidence row.",
                "[ItemGraph] /ig explain <edgeId> — show one inferred edge, confidence, and cited evidence.",
                "[ItemGraph] /ig trace item <query> [limit] [sinceMinutes]",
                "[ItemGraph] /ig trace item-filtered <query> <metadata/time filters> (permission level 4)",
                "[ItemGraph] /ig trace player <playerName> [limit] [sinceMinutes]",
                "[ItemGraph] /ig trace container <x> <y> <z> [limit] [sinceMinutes]",
                "[ItemGraph] /ig gui item <query> [sinceMinutes]",
                "[ItemGraph] /ig gui item-filtered <query> <metadata/time filters> (permission level 4)",
                "[ItemGraph] /ig gui player <playerName> [sinceMinutes]",
                "[ItemGraph] /ig gui container <dimension> <x> <y> <z> [sinceMinutes]",
                "[ItemGraph] /ig inspect [on|off|status] — toggle in-world block, functional-block, and container inspection.",
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
        topics.put("export", List.of(
                "[ItemGraph] Syntax: /ig export <filename> <filters>",
                "[ItemGraph] Writes a default-redacted JSON bundle under <world>/itemgraph/exports; filters use the same name.value syntax as /ig lookup and radius is required.",
                "[ItemGraph] Each raw evidence payload has a SHA-256 hash and each record links to its predecessor; the manifest hashes the ordered chain. Existing filenames are never overwritten.",
                "[ItemGraph] Exports are capped at 100 observed rows, 100 linked inferred edges (200 chain records total), and 4 MiB. Jobs run off-thread, report progress, and can be cancelled by their owner or a level-4 operator.",
                "[ItemGraph] Full details require permission level 4: /ig export full <filename> <filters>.",
                "[ItemGraph] Examples: /ig export incident-2026-10 action.break_block radius.50 time.1h | /ig export full incident-2026-10 action.break_block radius.50 time.1h"));
        topics.put("export verify", List.of(
                "[ItemGraph] Syntax: /ig export verify <filename>",
                "[ItemGraph] Verifies the manifest hash, every payload hash, chain order, record count, and final hash for a JSON bundle in <world>/itemgraph/exports.",
                "[ItemGraph] Example: /ig export verify incident-2026-10"));
        topics.put("export cancel", List.of(
                "[ItemGraph] Syntax: /ig export cancel <jobId>",
                "[ItemGraph] Cancels an active export or bundle verification. Only its issuing player or a permission-level-4 operator may cancel it.",
                "[ItemGraph] Example: /ig export cancel 1A"));
        topics.put("page", List.of(
                "[ItemGraph] Syntax: /ig page <page>",
                "[ItemGraph] Continues the issuing player's last lookup with the same filters and bounded page size.",
                "[ItemGraph] Page state is per-player, expires after 30 minutes, and is read-only; generated buttons carry a session token so older results stay bound to their query.",
                "[ItemGraph] Example: /ig page 2"));
        List<String> lookup = List.of(
                "[ItemGraph] Syntax: /ig lookup <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] eventType: all, PLAYER_JOIN, PLAYER_QUIT, CHAT_MESSAGE, COMMAND_ATTEMPT, COMMAND_EXECUTED, PLACE_BLOCK, BREAK_BLOCK, INTERACT_BLOCK, INTERACT_BLOCK_ATTEMPT, INTERACT_ENTITY, INTERACT_ENTITY_COMPLETED, INTERACT_ENTITY_DENIED, INTERACT_ENTITY_UNRESOLVED, KILL_ENTITY, THROW_ITEM, SHOOT_ITEM, or PROJECTILE_SPAWN_ACCEPTED.",
                "[ItemGraph] Results are native OBSERVED evidence from ig_audit_events; limit defaults to 20 and is capped at 100.",
                "[ItemGraph] /ig lookup near clamps radius to 1..1024 blocks and requires an exact dimension id.",
                "[ItemGraph] Pages are 1-based; offsets are capped at 10,000 rows.",
                "[ItemGraph] Example: /ig lookup BREAK_BLOCK 50 120");
        topics.put("lookup", lookup);
        topics.put("lookup player", List.of(
                "[ItemGraph] Syntax: /ig lookup player <playerName> <eventType> [limit] [sinceMinutes]",
                "[ItemGraph] playerName is an exact stored player name; eventType uses the same values as /ig lookup.",
                "[ItemGraph] Example: /ig lookup player Alex COMMAND_ATTEMPT 50 1440"));
        topics.put("lookup filters", List.of(
                "[ItemGraph] Syntax: /ig lookup <name.value> ... (maximum 12 filters)",
                "[ItemGraph] The explicit extension spelling /ig lookup filters <filter1> ... is also accepted.",
                "[ItemGraph] Filters: action, user, include, exclude, time (m/h/d/y), radius, state (observed, inferred, ambiguous, unresolved), after, before, between, item, fingerprint, name, damage, trim, enchantment, lore, and component.",
                "[ItemGraph] Absolute time uses UTC ISO-8601 with exactly three fractional digits: after.<UTC>, before.<UTC>, or between.<startUTC>,<endUTC>. after and before combine; between is exclusive with them.",
                "[ItemGraph] Metadata filters require permission level 4. component.<registry_id>=<JSON> matches a canonical persistent component value; unknown or incomplete component indexes remain labeled unresolved.",
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
        topics.put("trace item filtered", List.of(
                "[ItemGraph] Syntax: /ig trace item-filtered <query> <metadata/time filters>",
                "[ItemGraph] Requires permission level 4. Accepts item, fingerprint, name, damage, trim, enchantment, lore, component, time, after, before, or between filters.",
                "[ItemGraph] Incomplete component indexes stay labeled COMPONENT_FILTER_UNRESOLVED and are possible candidates only.",
                "[ItemGraph] Example: /ig trace item-filtered diamond_sword damage.4 enchantment.minecraft:sharpness:5"));
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
        topics.put("gui item filtered", List.of(
                "[ItemGraph] Syntax: /ig gui item-filtered <query> <metadata/time filters>",
                "[ItemGraph] Requires permission level 4. Supports item metadata filters plus time, after, before, and between.",
                "[ItemGraph] Incomplete component indexes remain labeled COMPONENT_FILTER_UNRESOLVED and are only possible candidates.",
                "[ItemGraph] Example: /ig gui item-filtered diamond_sword damage.4 enchantment.minecraft:sharpness:5"));
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
                "[ItemGraph] While enabled, left-click any block or right-click a block/container to open read-only history for the exact dimension and coordinates.",
                "[ItemGraph] Container clicks open the flow browser; block clicks open paginated audit history. The click does not consume the held item or become transfer evidence; state clears on logout and server stop.",
                "[ItemGraph] Example: /ig inspect on");
        topics.put("inspect", inspect);
        return Map.copyOf(topics);
    }
}
