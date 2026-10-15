#!/usr/bin/env python3
"""Keep first-use docs, command help, and in-world inspection behavior aligned."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"admin UX docs: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


help_java = read("common/src/main/java/com/itemgraph/command/CommandHelp.java")
commands_java = read("common/src/main/java/com/itemgraph/command/ItemGraphCommands.java")
readme = read("README.md")
query_model = read("docs/QUERY_MODEL.md")
quick_start = read("docs/ADMIN_QUICK_START.md")
architecture = read("docs/ARCHITECTURE.md")
security = read("docs/SECURITY_AND_PERMISSIONS.md")
listing = read("docs/branding/CURSEFORGE_LISTING.md")

topic_list = re.search(r"TOPIC_NAMES\s*=\s*List\.of\((.*?)\);", help_java, re.S)
if not topic_list:
    fail("could not read the live help topic list")
topics = re.findall(r'"([^"]+)"', topic_list.group(1))
topic_map = set(re.findall(r'topics\.put\("([^"]+)"', help_java))
topic_map.add("ingest now")  # This topic shares the documented ingest-now entry.
for topic in topics:
    if topic not in topic_map:
        fail(f"live help topic {topic!r} has no detailed topic entry")
    if topic not in quick_start:
        fail(f"admin quick start omits live help topic {topic!r}")
for topic in topic_map:
    if topic not in topics:
        fail(f"detailed help topic {topic!r} is not discoverable through suggestions")

feature_map = quick_start.split("## Feature map: currently available surfaces", 1)
if len(feature_map) != 2:
    fail("admin quick start is missing its feature map")
feature_rows = [line for line in feature_map[1].splitlines() if line.startswith("| ")]
feature_names = [row.split("|", 2)[1].strip() for row in feature_rows[2:]]
if len(feature_names) != len(set(feature_names)):
    fail("admin quick start feature map contains duplicate feature rows")

registration_markers = {
    "lookup near": "buildNearLookupCommand",
    "lookup page": "buildPagedLookupCommand",
    "lookup player": 'lookup.then(Commands.literal("player")',
    "lookup filters": 'lookup.then(Commands.literal("filters")',
    "lookup provenance": 'literal("provenance")',
    "trace item": 'Commands.literal("trace")',
    "gui container": 'Commands.literal("gui")',
    "inspect": "InspectionService",
}
for topic, marker in registration_markers.items():
    if marker not in commands_java:
        fail(f"{topic} help topic no longer matches the command implementation")
    if topic not in query_model:
        fail(f"{topic} is missing from the command reference")

inspect_docs = (readme, query_model, quick_start)
quick_start_rows = [line for line in quick_start.splitlines() if line.startswith("|")]
if any(
    "furnace" in row.lower() and "audit-history query" in row.lower()
    for row in quick_start_rows
):
    fail("admin quick start routes a container furnace to block audit history")
if not any(
    "furnace" in row.lower() and "container" in row and "flow browser" in row.lower()
    for row in quick_start_rows
):
    fail("admin quick start must identify a furnace as a container flow-browser target")

for relative, document in zip(
    ("README.md", "docs/QUERY_MODEL.md", "docs/ADMIN_QUICK_START.md"), inspect_docs
):
    required_route_patterns = (
        r"right.?click.{0,240}Container.{0,240}flow browser|Container.{0,240}right.?click.{0,240}flow browser",
        r"left.?click.{0,160}(block|position).{0,120}(history|audit)|left.?click.{0,120}.*audit history",
        r"other right.?clicks?.{0,140}(block history|audit.history)|non.container.{0,140}(block history|audit.history)",
        r"ordinary.{0,80}blocks?.{0,320}(adjacent|clicked face)|(adjacent|clicked face).{0,320}ordinary.{0,80}blocks?",
        r"double chests?.{0,180}(either half|both halves).{0,180}canonical anchor|either half.{0,180}double chest.{0,180}canonical anchor",
    )
    for pattern in required_route_patterns:
        if not re.search(pattern, document, re.I | re.S):
            fail(f"{relative} omits or contradicts inspection behavior: {pattern}")

overview = re.search(
    r"static List<String> overviewLines\(\)\s*\{\s*return List\.of\((.*?)\);",
    help_java,
    re.S,
)
if not overview:
    fail("could not read the in-game first-use overview")
overview_lines = re.findall(r'"(\[ItemGraph\].*?)"', overview.group(1))
if len(overview_lines) > 5:
    fail(f"first-use in-game overview has {len(overview_lines)} messages; maximum is 5")
if "guide" not in help_java.split("TOPIC_NAMES = List.of(", 1)[1].split(");", 1)[0]:
    fail("the /ig help guide route is missing from the advertised topic set")
guide_help = re.search(r'topics\.put\("guide", List\.of\((.*?)\)\);', help_java, re.S)
if not guide_help or "ADMIN_QUICK_START.md" not in guide_help.group(1):
    fail("the in-game guide topic must link to the admin quick start")

filters_help = re.search(
    r'topics\.put\("lookup filters", List\.of\((.*?)\)\);', help_java, re.S
)
if not filters_help or not re.search(r"Player-only", filters_help.group(1), re.I):
    fail("filtered lookup help must say that its player-relative radius is player-only")
if not re.search(r"From console.{0,80}/ig lookup near", filters_help.group(1), re.I | re.S):
    fail("filtered lookup help must provide the coordinate-based console alternative")

permission_nodes = ("itemgraph.command", "itemgraph.gui", "itemgraph.audit")
for relative, document in (("README.md", readme), ("docs/ADMIN_QUICK_START.md", quick_start),
                           ("docs/branding/CURSEFORGE_LISTING.md", listing)):
    if re.search(r"all commands require permission level 2|all commands require level 2",
                 document, re.I):
        fail(f"{relative} overstates the permission-level-2 fallback")
for required in permission_nodes:
    if required not in readme or required not in security:
        fail(f"README/security permission contract is missing {required!r}")
if ("explicit permission-provider denial" not in readme
        or "explicit `false` denies" not in security
        or "explicit permission-provider denial" not in listing
        or "itemgraph.gui" not in listing):
    fail("CurseForge listing must describe named permission checks and explicit denial")
if ("Run `/ig help permissions` for the exact access nodes" not in readme
        or "`/ig help permissions` explains access nodes" not in listing):
    fail("README and CurseForge must point admins to the help topic that documents permission nodes")

for required in ("ADMIN_ITEM_COMMAND_EFFECT", "ADMIN_ITEM_COMMAND_ATTEMPT",
                "ADMIN_ITEM_COMMAND_FAILURE", "ADMIN_ITEM_COMMAND_UNRESOLVED",
                "CREATIVE_SLOT_EFFECT", "CREATIVE_BLOCK_RESULT",
                '/ig trace item "<item-id>" 50 1440',
                '/ig trace item "id:<fingerprint-id>" 50 1440',
                "action.creative_item_create", "action.creative_item_remove",
                "CONTAINER_BREAK_COMPLETED", "CONTAINER_BREAK_UNRESOLVED",
                "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED",
                "CONTAINER_SNAPSHOT_INCOMPLETE", "itemgraph.command.lookup",
                "itemgraph.trace", "itemgraph.audit",
                "no destination or recipient is established"):
    if required not in quick_start:
        fail(f"admin quick start is missing the moderator workflow detail {required!r}")
if "itemgraph.command.trace" in quick_start:
    fail("admin quick start names a trace permission node that does not exist")
if (not re.search(r"does not\s+associate (?:them|the item) with a specific command or actor", quick_start)
        or "choose the matching fingerprint ID" not in quick_start
        or not re.search(r"does not\s+combine component variants", quick_start)):
    fail("admin quick start must state staff trace attribution and fingerprint-selection limits")
for required in ("For `observation#42`, pass only `42` to", "`audit#42` row is already inline evidence",
                "transformation and imported", "source SHA-256, table, and exact source key",
                "`itemgraph.explain` plus `itemgraph.audit`", "`itemgraph.trace` plus `itemgraph.audit`"):
    if required not in quick_start:
        fail(f"admin quick start is missing source-ID or follow-up grant guidance {required!r}")
for obsolete in ("The whole tree requires permission level 2",
                 "Permission level 2 is rechecked for menu validity",
                 "requires permission level 2 on both command and click"):
    if obsolete in query_model:
        fail(f"query model contains permission guidance that contradicts the live nodes: {obsolete!r}")

if not re.search(r"NeoForge.{0,80}primary", listing, re.I | re.S) or "Fabric" not in listing:
    fail("CurseForge listing loader support must agree with the primary/supported loader contract")
if re.search(r"Zero Server Lag|zero lag", listing, re.I):
    fail("CurseForge listing must not promise zero server lag")

if quick_start.index("## 0. Choose the server file and confirm access") > quick_start.index("/ig status"):
    fail("install and access instructions must precede first-use command examples")
for limit in ("explosion", "Enderman", "moving blocks", "zero-net", "backpacks"):
    if limit.lower() not in quick_start.lower():
        fail(f"admin quick start omits the concrete evidence coverage limit {limit!r}")
if not re.search(r"not a\s+calibrated probability", quick_start, re.I):
    fail("admin quick start must explain that deterministic confidence is not probability")
gui_claims = [line for line in readme.splitlines() if "/ig gui" in line]
if not gui_claims or any(node not in " ".join(gui_claims) for node in permission_nodes):
    fail("README /ig gui guidance must name command, GUI, and audit permissions together")
for required in ("general.grieflogger_integration_enabled=true",
                "grieflogger_integration_enabled=true", "grieflogger_database_path",
                "`/ig ingest history`", "`/ig status`",
                "GriefLogger database stays", "ItemGraph writes imported rows"):
    if required not in quick_start:
        fail(f"admin quick start is missing a safe historical-import step: {required}")
for required in ("itemgraph-pending-evidence.json", "itemgraph-pending-evidence.json.overflow",
                "logs/latest.log", "beside the SQLite database", "Do not edit the JSON"):
    if required not in quick_start:
        fail(f"admin quick start is missing pending-evidence recovery guidance: {required}")
for required in ("[API contract](API.md)", "[compiling example](../examples/api-consumer)",
                "NeoForge server-mod API preview; not a player command"):
    if required not in quick_start:
        fail(f"admin quick start is missing preview API discovery context: {required}")

architecture_routes = (
    r"right.?click.{0,240}Container.{0,240}FlowBrowserService\.openContainer",
    r"Left.clicks?.{0,160}(bounded|exact.position).{0,160}(audit.history|query)|left.?click.{0,200}audit.history",
    r"Non.container\s+right.?clicks?.{0,200}query block history",
    r"ordinary blocks?.{0,180}adjacent block on the clicked face",
    r"either half.{0,180}double chest.{0,180}canonical anchor",
)
for pattern in architecture_routes:
    if not re.search(pattern, architecture, re.I | re.S):
        fail(f"docs/ARCHITECTURE.md omits or contradicts inspection behavior: {pattern}")

parser_corpus_path = "docs/test-evidence/m9-admin-ux/quick-start-command-corpus.txt"
parser_corpus = read(parser_corpus_path)
example_tests = (
    "neoforge/src/test/java/com/itemgraph/command/ItemGraphCommandsHelpTest.java",
    "fabric/src/test/java/com/itemgraph/fabric/FabricItemGraphCommandsParityTest.java",
)
for relative in example_tests:
    example_test = read(relative)
    if ("guide-command-examples:start" not in quick_start
            or "guide-command-examples:end" not in quick_start
            or parser_corpus_path not in example_test
            or "documentedGuideAndParserCorpusCommandsParseAgainstRegisteredCommandTree" not in example_test):
        fail(f"{relative} must parse the short guide route and the shared parser-only corpus")
if not parser_corpus.startswith("# PARSER-ONLY TEST INPUT — NEVER paste or execute"):
    fail("the command corpus must say it is syntax-only and must not be pasted or executed")
walkthrough = re.search(r"## 4\. Run a first read-only investigation(.*?)## 5\.", quick_start, re.S)
if not walkthrough:
    fail("quick start must have a bounded first-investigation walkthrough")
for stateful in ("/ig inspect on", "/ig ingest history", "00000000-0000-0000-0000-000000000000"):
    if stateful in walkthrough.group(1):
        fail(f"first read-only walkthrough contains unrelated or stateful command: {stateful}")

for relative in (
    "neoforge/src/main/java/com/itemgraph/command/InspectionListener.java",
    "fabric/src/main/java/com/itemgraph/fabric/FabricNativeAuditEventListener.java",
):
    implementation = read(relative)
    if "instanceof Container" not in implementation or "BlockInspectionTargets.canonicalPosition(level, target)" not in implementation:
        fail(f"{relative} must route containers through their canonical anchor")

targets_java = read("common/src/main/java/com/itemgraph/command/BlockInspectionTargets.java")
if "ChestBlock.getConnectedDirection(state)" not in targets_java or "isMatchingPartner(level, state, partner)" not in targets_java:
    fail("canonical double-chest target must validate its connected partner")

for stale in (
    "Unsupported blocks retain vanilla behavior",
    "Unsupported right-click targets keep normal Minecraft interaction",
    "Unsupported blocks and inactive/disabled inspection return",
    "all right-click requests open the same exact-position timeline",
):
    if any(stale in document for document in (*inspect_docs, architecture, security)):
        fail(f"stale inspection description remains: {stale}")

if (
    not re.search(r"permission\s+level\s+2", quick_start, re.I)
    or not re.search(r"permission\s+level\s+2", security, re.I)
    or "exact node" not in quick_start
    or not re.search(r"explicit `false` denies", security, re.I)
):
    fail("admin permission docs must state exact named-node checks, explicit deny, and the level-2 fallback")

print("admin UX docs: command topics, permission contracts, import/recovery steps, API boundary, and inspection routes are aligned")
