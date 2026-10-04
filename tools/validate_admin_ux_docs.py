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

example_test = read("neoforge/src/test/java/com/itemgraph/command/ItemGraphCommandsHelpTest.java")
if ("executable-command-examples:start" not in quick_start
        or "executable-command-examples:end" not in quick_start
        or "documentedQuickStartExamplesParseAgainstRegisteredCommandTree" not in example_test):
    fail("quick-start executable examples must be parsed against the registered command tree")

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

if not re.search(r"permission level 2", quick_start, re.I) or not re.search(r"permission-level-2 gate", security, re.I):
    fail("current permission-level-2 behavior must be stated until named nodes land")

print("admin UX docs: command topics, inspection routes, and current permissions are aligned")
