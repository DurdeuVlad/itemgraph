#!/usr/bin/env python3
"""Validate the pinned GriefLogger compatibility profile.

The validator is deliberately dependency-free and read-only.  It checks the
machine-readable registry, the checked-in parity document, and the milestone
plan against the audited GriefLogger 26.2 action/table vocabulary.  The source
profile hash is computed from a canonical JSON object so a source-version,
action, or table change cannot be hidden behind a documentation-only edit.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_PATH = ROOT / "docs" / "GRIEFLOGGER_COMPATIBILITY.json"
PARITY_PATH = ROOT / "docs" / "GRIEFLOGGER_PARITY.md"
MILESTONES_PATH = ROOT / "Milestones.md"

EXPECTED_SOURCE_ACTIONS = tuple(
    sorted(
        {
            "ADD_ITEM",
            "ADD_ITEM_ENDER",
            "BREAK_BLOCK",
            "BREAK_ITEM",
            "CONSUME_ITEM",
            "CRAFT_ITEM",
            "DROP_ITEM",
            "INTERACT_BLOCK",
            "INTERACT_ENTITY",
            "JOIN",
            "KILL_ENTITY",
            "PICKUP_ITEM",
            "PLACE_BLOCK",
            "QUIT",
            "REMOVE_ITEM",
            "REMOVE_ITEM_ENDER",
            "SHOOT_ITEM",
            "THROW_ITEM",
        }
    )
)

EXPECTED_SOURCE_TABLES = tuple(
    sorted(
        {
            "items",
            "containers",
            "blocks",
            "sessions",
            "chats",
            "commands",
            "users",
            "usernames",
            "levels",
            "materials",
            "entities",
        }
    )
)

SOURCE_COMMIT = "d315098b3f37317a5cddfbd75086f4f912f16a83"
SOURCE_BASE_URL = f"https://github.com/DAQEM/GriefLogger/blob/{SOURCE_COMMIT}/"
EXPECTED_SOURCE_FILE_PATHS = (
    "common/src/main/java/com/daqem/grieflogger/model/action/BlockAction.java",
    "common/src/main/java/com/daqem/grieflogger/model/action/ItemAction.java",
    "common/src/main/java/com/daqem/grieflogger/model/action/SessionAction.java",
    "common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java",
    "common/src/main/java/com/daqem/grieflogger/command/InspectCommand.java",
    "common/src/main/java/com/daqem/grieflogger/command/PageCommand.java",
    "common/src/main/java/com/daqem/grieflogger/command/page/Page.java",
    "common/src/main/java/com/daqem/grieflogger/command/argument/FilterArgument.java",
    "common/src/main/java/com/daqem/grieflogger/config/GriefLoggerConfig.java",
    "common/src/main/java/com/daqem/grieflogger/database/Database.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/Repository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/BlockRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/ContainerRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/ItemRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/SessionRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/ChatRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/CommandRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/UserRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/UsernameRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/LevelRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/MaterialRepository.java",
    "common/src/main/java/com/daqem/grieflogger/database/repository/EntityRepository.java",
    "common/src/main/java/com/daqem/grieflogger/event/block/InspectBlockEvent.java",
    "common/src/main/java/com/daqem/grieflogger/event/block/InspectContainerEvent.java",
    "common/src/main/java/com/daqem/grieflogger/event/block/InspectDoorEvent.java",
    "common/src/main/java/com/daqem/grieflogger/event/block/RemoveBlockInteractionsEvent.java",
    "common/src/main/java/com/daqem/grieflogger/event/block/RemoveDoorInteractionsEvent.java",
)
EXPECTED_SOURCE_FILES = tuple(sorted(SOURCE_BASE_URL + path for path in EXPECTED_SOURCE_FILE_PATHS))

# CHAT and COMMAND are published feature rows in the source database but are
# not members of the three audited source action enums.
EXPECTED_SOURCE_CAPABILITIES = set(EXPECTED_SOURCE_ACTIONS) | {"CHAT", "COMMAND"}
# The action rows are a compatibility contract, not merely a vocabulary list.
# Keep the expected mapping here so a registry edit cannot silently change the
# evidence or quantity semantics while retaining the same action names.
EXPECTED_ACTION_CONTRACT: dict[str, tuple[str, str, str, str]] = {
    "ADD_ITEM": ("ADD_ITEM", "compatible", "observed", "signed_delta"),
    "REMOVE_ITEM": ("REMOVE_ITEM", "compatible", "observed", "signed_delta"),
    "DROP_ITEM": ("DROP_ITEM", "compatible", "observed", "signed_delta"),
    "PICKUP_ITEM": ("PICKUP_ITEM", "compatible", "observed", "signed_delta"),
    "CRAFT_ITEM": ("CRAFT", "extended", "observed", "transformation"),
    "CONSUME_ITEM": ("CONSUME_ITEM", "compatible", "observed", "signed_delta"),
    "BREAK_ITEM": ("BREAK_ITEM", "compatible", "observed", "signed_delta"),
    "THROW_ITEM": ("THROW_ITEM", "unresolved", "observed", "observed_stack_count"),
    "SHOOT_ITEM": ("SHOOT_ITEM", "unresolved", "observed", "observed_stack_count"),
    "PLACE_BLOCK": ("PLACE_BLOCK", "compatible", "observed", "none"),
    "BREAK_BLOCK": ("BREAK_BLOCK", "compatible", "observed", "none"),
    "INTERACT_BLOCK": ("INTERACT_BLOCK_ATTEMPT", "unresolved", "observed", "none"),
    "KILL_ENTITY": ("KILL_ENTITY", "compatible", "observed", "none"),
    "INTERACT_ENTITY": ("INTERACT_ENTITY", "unresolved", "observed", "none"),
    "JOIN": ("PLAYER_JOIN", "compatible", "observed", "none"),
    "QUIT": ("PLAYER_QUIT", "compatible", "observed", "none"),
    "CHAT": ("CHAT_MESSAGE", "compatible", "observed", "none"),
    "COMMAND": ("COMMAND_ATTEMPT", "compatible", "observed", "none"),
    "ADD_ITEM_ENDER": ("ADD_ITEM_ENDER", "unresolved", "observed", "signed_delta"),
    "REMOVE_ITEM_ENDER": ("REMOVE_ITEM_ENDER", "unresolved", "observed", "signed_delta"),
}
EXPECTED_EXTENSION_ACTION_CONTRACT: dict[str, tuple[str, str, str]] = {
    "SMELT": ("extended", "observed", "transformation"),
    "ANVIL_RENAME": ("extended", "observed", "transformation"),
    "ANVIL_REPAIR": ("extended", "observed", "transformation"),
    "HOPPER_INSERT": ("extended", "observed", "signed_delta"),
    "HOPPER_EXTRACT": ("extended", "observed", "signed_delta"),
}
REQUIRED_MILESTONE_ISSUES = {24, 25, 26, 27, 28, 29, 30, 31, 43, 54}
M8_MILESTONE_TITLE = "M8: Drop-in GriefLogger parity"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


class ProfileError(RuntimeError):
    """A profile invariant failed."""


def fail(message: str) -> None:
    raise ProfileError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read {path.relative_to(ROOT)}: {exc}")
    require(isinstance(value, dict), f"{path.relative_to(ROOT)} must contain a JSON object")
    return value


def canonical_source_profile(registry: dict[str, Any]) -> bytes:
    baseline = registry["audit_baseline"]
    profile = {
        "actions": list(EXPECTED_SOURCE_ACTIONS),
        "commit": baseline["commit"],
        "ref": baseline["ref"],
        "source_files": list(EXPECTED_SOURCE_FILES),
        "tables": list(EXPECTED_SOURCE_TABLES),
    }
    return json.dumps(profile, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def validate_registry(registry: dict[str, Any]) -> None:
    require(registry.get("schema_version") == 1, "registry schema_version must be 1")
    require(registry.get("mod_id") == "itemgraph", "registry mod_id must be itemgraph")

    release_fixture = registry.get("release_fixture")
    require(isinstance(release_fixture, dict), "registry release_fixture must be an object")
    require(
        release_fixture.get("path") == "docs/grieflogger-fixtures/1.2.10-1.21.1.json"
        and release_fixture.get("version") == "1.2.10-1.21.1"
        and release_fixture.get("minecraft") == "1.21.1"
        and release_fixture.get("loaders") == ["fabric", "neoforge"]
        and release_fixture.get("owner_issue") == 54,
        "registry release fixture pointer changed",
    )

    baseline = registry.get("audit_baseline")
    require(isinstance(baseline, dict), "registry audit_baseline must be an object")
    require(baseline.get("ref") == "26.2", "audit baseline ref must be 26.2")
    require(
        baseline.get("commit") == SOURCE_COMMIT and COMMIT_RE.fullmatch(baseline["commit"]),
        "audit baseline commit must be a full 40-character SHA-1",
    )
    require(
        sorted(baseline.get("source_files", [])) == list(EXPECTED_SOURCE_FILES),
        "source_files do not match the audited GriefLogger 26.2 fixture",
    )
    require(baseline.get("owner_issue") == 43, "audit baseline owner_issue must be 43")

    source_profile = registry.get("source_profile")
    require(isinstance(source_profile, dict), "registry source_profile must be an object")
    require(source_profile.get("hash_algorithm") == "sha256", "source_profile hash_algorithm must be sha256")
    require(
        source_profile.get("canonicalization")
        == "json-sort-keys-compact; source files, actions, and tables sorted",
        "source_profile canonicalization contract changed",
    )
    require(source_profile.get("actions") == list(EXPECTED_SOURCE_ACTIONS), "source action fixture does not match GriefLogger 26.2")
    require(source_profile.get("tables") == list(EXPECTED_SOURCE_TABLES), "source table fixture does not match GriefLogger 26.2")
    expected_hash = hashlib.sha256(canonical_source_profile(registry)).hexdigest()
    require(source_profile.get("sha256") == expected_hash, "source profile SHA-256 does not match the canonical fixture")
    require(SHA256_RE.fullmatch(source_profile["sha256"]), "source profile SHA-256 must be lowercase hexadecimal")

    actions = registry.get("actions")
    require(isinstance(actions, list), "registry actions must be an array")
    source_capabilities = {
        row.get("grieflogger")
        for row in actions
        if isinstance(row, dict) and isinstance(row.get("grieflogger"), str)
    }
    require(source_capabilities == EXPECTED_SOURCE_CAPABILITIES, "registry source capability set is incomplete or has an extra value")
    source_action_counts = Counter(
        row.get("grieflogger")
        for row in actions
        if isinstance(row, dict) and row.get("grieflogger") in EXPECTED_SOURCE_ACTIONS
    )
    require(
        source_action_counts == Counter(EXPECTED_SOURCE_ACTIONS),
        "registry does not enumerate every audited source action exactly once",
    )
    extension_action_counts = Counter(
        row.get("itemgraph")
        for row in actions
        if isinstance(row, dict) and row.get("grieflogger") is None
    )
    require(
        extension_action_counts == Counter(EXPECTED_EXTENSION_ACTION_CONTRACT.keys()),
        "registry ItemGraph-only actions are incomplete, duplicated, or have an extra value",
    )
    allowed_statuses = set(registry.get("statuses", []))
    require(allowed_statuses == {"compatible", "extended", "unsupported", "unresolved"}, "registry status vocabulary changed")
    allowed_evidence_classes = set(registry.get("evidence_classes", []))
    require(
        allowed_evidence_classes == {"observed", "inferred", "ambiguous", "unresolved"},
        "registry evidence_class vocabulary changed",
    )
    for index, row in enumerate(actions):
        require(isinstance(row, dict), f"actions[{index}] must be an object")
        itemgraph = row.get("itemgraph")
        evidence_class = row.get("evidence_class")
        quantity = row.get("quantity")
        loaders = row.get("loaders")
        require(isinstance(itemgraph, str) and itemgraph, f"actions[{index}] itemgraph must be a non-empty string")
        require(row.get("status") in allowed_statuses, f"actions[{index}] has an unknown status")
        require(evidence_class in allowed_evidence_classes, f"actions[{index}] has an unknown evidence_class")
        require(isinstance(quantity, str) and quantity, f"actions[{index}] quantity must be a non-empty string")
        require(loaders == ["fabric", "neoforge"], f"actions[{index}] loaders must be exactly ['fabric', 'neoforge']")
        source_action = row.get("grieflogger")
        if source_action in EXPECTED_ACTION_CONTRACT:
            expected_itemgraph, expected_status, expected_evidence, expected_quantity = EXPECTED_ACTION_CONTRACT[source_action]
            require(
                (itemgraph, row["status"], evidence_class, quantity)
                == (expected_itemgraph, expected_status, expected_evidence, expected_quantity),
                f"actions[{index}] contract for {source_action} changed",
            )
            if expected_status == "unresolved":
                require(row.get("owner_issue") == 27, f"actions[{index}] unresolved {source_action} must remain owned by issue #27")
            else:
                require("owner_issue" not in row, f"actions[{index}] compatible/extended {source_action} must not claim an unresolved owner issue")
        elif source_action is None:
            expected_status, expected_evidence, expected_quantity = EXPECTED_EXTENSION_ACTION_CONTRACT[itemgraph]
            require(
                (row["status"], evidence_class, quantity)
                == (expected_status, expected_evidence, expected_quantity),
                f"actions[{index}] ItemGraph-only contract for {itemgraph} changed",
            )
            require("owner_issue" not in row, f"actions[{index}] ItemGraph-only action {itemgraph} must not claim an owner issue")
        if row.get("status") == "unresolved":
            require(isinstance(row.get("owner_issue"), int), f"actions[{index}] unresolved row needs owner_issue")

    storage = registry.get("storage")
    require(isinstance(storage, dict), "registry storage must be an object")
    require(
        sorted(storage.get("source_tables", [])) == list(EXPECTED_SOURCE_TABLES),
        "registry source_tables do not match the source fixture",
    )
    require(storage.get("grieflogger_database_mutation") is False, "GriefLogger database mutation must remain disabled")

    filters = registry.get("filters")
    require(isinstance(filters, dict), "registry filters must be an object")
    require(filters.get("owner_issue") == 25, "registry filters owner_issue must remain 25")


def validate_documents(registry: dict[str, Any]) -> None:
    parity = PARITY_PATH.read_text(encoding="utf-8")
    milestones = MILESTONES_PATH.read_text(encoding="utf-8")
    baseline = registry["audit_baseline"]
    source_profile = registry["source_profile"]

    required_parity_fragments = [
        registry["compatibility_version"],
        baseline["ref"],
        baseline["commit"],
        source_profile["sha256"],
        "source-profile hash",
        "11 tables",
        "18 actions",
        "1.2.10-1.21.1",
        "160f77435c9527304adba691388295ead00af40db482338fcbf87951d929e648",
        "issue #54",
    ]
    for fragment in required_parity_fragments:
        require(fragment in parity, f"parity document is missing required fragment: {fragment}")

    owner_issues = set()
    for section in (registry.get("actions", []), registry.get("configuration_controls", []), registry.get("inspector_behavior", [])):
        for row in section:
            if isinstance(row, dict) and isinstance(row.get("owner_issue"), int):
                owner_issues.add(row["owner_issue"])
    filters = registry.get("filters")
    require(isinstance(filters, dict) and filters.get("owner_issue") == 25, "filters owner_issue must remain 25")
    owner_issues.add(filters["owner_issue"])
    owner_issues.add(baseline["owner_issue"])
    for issue in sorted(owner_issues):
        require(f"#{issue}" in parity, f"parity document does not reference owner issue #{issue}")
    for issue in REQUIRED_MILESTONE_ISSUES:
        require(re.search(rf"(?<!\d)#{issue}(?!\d)", milestones) is not None, f"Milestones.md does not reference issue #{issue}")

    for source_file in baseline["source_files"]:
        require(source_file in parity, f"parity document does not cite source file {source_file}")


def validate_remote_milestones() -> None:
    """Check the live GitHub issue/milestone contract when CI requests it."""

    if os.environ.get("GRIEFLOGGER_PROFILE_CHECK_REMOTE") != "1":
        return
    repository = os.environ.get("GITHUB_REPOSITORY")
    require(repository, "GITHUB_REPOSITORY is required for the remote milestone check")
    api_root = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    token = os.environ.get("GITHUB_TOKEN")
    for issue_number in sorted(REQUIRED_MILESTONE_ISSUES):
        request = urllib.request.Request(
            f"{api_root}/repos/{repository}/issues/{issue_number}",
            headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": "itemgraph-grieflogger-profile-validator",
                **({"Authorization": f"Bearer {token}"} if token else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                issue = json.load(response)
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
            fail(f"cannot read GitHub issue #{issue_number}: {exc}")
        require(isinstance(issue, dict), f"GitHub issue #{issue_number} response must be an object")
        milestone = issue.get("milestone") or {}
        require(
            milestone.get("title") == M8_MILESTONE_TITLE,
            f"GitHub issue #{issue_number} is not assigned to {M8_MILESTONE_TITLE!r}",
        )
        require(
            "## Acceptance criteria" in issue.get("body", ""),
            f"GitHub issue #{issue_number} is missing its acceptance criteria section",
        )


def main() -> int:
    try:
        registry = load_json(REGISTRY_PATH)
        validate_registry(registry)
        validate_documents(registry)
        validate_remote_milestones()
    except (KeyError, TypeError, ProfileError, OSError) as exc:
        print(f"GriefLogger profile validation failed: {exc}", file=sys.stderr)
        return 1

    digest = registry["source_profile"]["sha256"]
    print(
        "GriefLogger profile valid: "
        f"{registry['compatibility_version']} ref={registry['audit_baseline']['ref']} "
        f"commit={registry['audit_baseline']['commit']} sourceProfileSha256={digest} "
        f"actions={len(EXPECTED_SOURCE_ACTIONS)} tables={len(EXPECTED_SOURCE_TABLES)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
