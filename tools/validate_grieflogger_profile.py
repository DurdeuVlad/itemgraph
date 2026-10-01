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
EXPECTED_ENTITY_INTERACTION_OUTCOME_MODEL = {
    "attempt_event": "INTERACT_ENTITY",
    "handled_method_result_event": "INTERACT_ENTITY_COMPLETED",
    "denied_event": "INTERACT_ENTITY_DENIED",
    "unresolved_event": "INTERACT_ENTITY_UNRESOLVED",
    "method_result_boundaries": ["ArmorStand.interactAt", "Entity.interact"],
    "method_result_target_predicate": "target instanceof ArmorStand",
    "unsupported_method_result_support": "callback_only",
    "unsupported_method_result_reason_code": "ENTITY_CLASS_UNSUPPORTED_FOR_RESULT",
    "callback_level_outcomes_remain_recordable": True,
    "equipment_or_item_movement_claimed": False,
}
EXPECTED_SOURCE_ACTION_IDS: dict[str, tuple[str, int, int | None]] = {
    "BREAK_BLOCK": ("BlockAction", 0, 0),
    "PLACE_BLOCK": ("BlockAction", 1, 1),
    "INTERACT_BLOCK": ("BlockAction", 2, 2),
    "KILL_ENTITY": ("BlockAction", 3, 3),
    "INTERACT_ENTITY": ("BlockAction", 4, None),
    "REMOVE_ITEM": ("ItemAction", 0, 0),
    "ADD_ITEM": ("ItemAction", 1, 1),
    "DROP_ITEM": ("ItemAction", 2, 2),
    "PICKUP_ITEM": ("ItemAction", 3, 3),
    "CRAFT_ITEM": ("ItemAction", 4, 4),
    "BREAK_ITEM": ("ItemAction", 5, 5),
    "CONSUME_ITEM": ("ItemAction", 6, 6),
    "THROW_ITEM": ("ItemAction", 7, 7),
    "SHOOT_ITEM": ("ItemAction", 8, 8),
    "ADD_ITEM_ENDER": ("ItemAction", 9, 9),
    "REMOVE_ITEM_ENDER": ("ItemAction", 10, 10),
    "JOIN": ("SessionAction", 0, 0),
    "QUIT": ("SessionAction", 1, 1),
}
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
    "THROW_ITEM": ("THROW_ITEM", "compatible", "observed", "observed_stack_count"),
    "SHOOT_ITEM": ("SHOOT_ITEM", "compatible", "observed", "observed_stack_count"),
    "PLACE_BLOCK": ("PLACE_BLOCK", "compatible", "observed", "none"),
    "BREAK_BLOCK": ("BREAK_BLOCK", "compatible", "observed", "none"),
    "INTERACT_BLOCK": ("INTERACT_BLOCK_ATTEMPT", "compatible", "observed", "none"),
    "KILL_ENTITY": ("KILL_ENTITY", "compatible", "observed", "none"),
    "INTERACT_ENTITY": ("INTERACT_ENTITY", "unsupported-no-writer", "unresolved", "none"),
    "JOIN": ("PLAYER_JOIN", "compatible", "observed", "none"),
    "QUIT": ("PLAYER_QUIT", "compatible", "observed", "none"),
    "CHAT": ("CHAT_MESSAGE", "compatible", "observed", "none"),
    "COMMAND": ("COMMAND_ATTEMPT", "compatible", "observed", "none"),
    "ADD_ITEM_ENDER": ("ADD_ITEM_ENDER", "unsupported-no-writer", "unresolved", "signed_delta"),
    "REMOVE_ITEM_ENDER": ("REMOVE_ITEM_ENDER", "unsupported-no-writer", "unresolved", "signed_delta"),
}
ENDER_NO_WRITER_REASON = "NO_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE"
ENTITY_NO_WRITER_REASON = "NO_ACTION_ID_OR_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE"
ENDER_EXTENSION_CONTRACT = {
    "source_type": "ITEMGRAPH_INTERNAL",
    "capture": "ender_inventory_session_net_delta",
    "compatibility_mapping": False,
}
ENTITY_EXTENSION_CONTRACT = {
    "source_type": "ITEMGRAPH_NATIVE_AUDIT",
    "capture": "entity_interaction_callbacks_and_armor_stand_method_results",
    "compatibility_mapping": False,
}
EXPECTED_EXTENSION_ACTION_CONTRACT: dict[str, tuple[str, str, str]] = {
    "SMELT": ("extended", "observed", "transformation"),
    "ANVIL_RENAME": ("extended", "observed", "transformation"),
    "ANVIL_REPAIR": ("extended", "observed", "transformation"),
    "HOPPER_INSERT": ("extended", "observed", "signed_delta"),
    "HOPPER_EXTRACT": ("extended", "observed", "signed_delta"),
}
REQUIRED_MILESTONE_ISSUES = {24, 25, 26, 27, 28, 29, 30, 31, 43, 54, 76}
M8_MILESTONE_TITLE = "M8: Drop-in GriefLogger parity"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


class ProfileError(RuntimeError):
    """A profile invariant failed."""


def reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def parse_json_document(source: str) -> Any:
    return json.loads(source, object_pairs_hook=reject_duplicate_json_keys)


def fail(message: str) -> None:
    raise ProfileError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = parse_json_document(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
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
    require(type(registry.get("schema_version")) is int and registry["schema_version"] == 1,
            "registry schema_version must be integer 1")
    require(registry.get("mod_id") == "itemgraph", "registry mod_id must be itemgraph")

    index_policy = next((row for row in registry.get("configuration_controls", [])
                         if isinstance(row, dict) and row.get("grieflogger") == "useIndexes"), None)
    require(
        isinstance(index_policy, dict)
        and index_policy.get("itemgraph") == "storage.use_indexes"
        and index_policy.get("status") == "compatible"
        and index_policy.get("implementation_state") == "implemented"
        and index_policy.get("owner_issue") == 29,
        "useIndexes must map to the implemented storage.use_indexes policy owned by issue #29",
    )

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
    release_fixture = load_json(ROOT / "docs" / "grieflogger-fixtures" / "1.2.10-1.21.1.json")
    release_action_writer_audit = release_fixture.get("action_writer_audit")
    require(isinstance(release_action_writer_audit, dict),
            "release fixture action_writer_audit must be present")
    release_writer_references = release_action_writer_audit.get("expected_action_field_access_classes")
    require(isinstance(release_writer_references, dict),
            "release fixture action writer references must be an object")
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
    entity_interaction = next(row for row in actions if row.get("grieflogger") == "INTERACT_ENTITY")
    require(
        entity_interaction.get("itemgraph_outcome_model") == EXPECTED_ENTITY_INTERACTION_OUTCOME_MODEL,
        "INTERACT_ENTITY outcome model must distinguish attempts/results and retain the stable unsupported-target reason",
    )
    allowed_statuses = set(registry.get("statuses", []))
    require(allowed_statuses == {"compatible", "extended", "unsupported", "unsupported-no-writer", "unresolved"},
            "registry status vocabulary changed")
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
            if source_action in EXPECTED_SOURCE_ACTION_IDS:
                expected_enum, expected_source_id, expected_release_id = EXPECTED_SOURCE_ACTION_IDS[source_action]
                require(row.get("source_enum") == expected_enum,
                        f"actions[{index}] {source_action} source enum changed")
                require(type(row.get("source_id")) is int,
                        f"actions[{index}] {source_action} source ID must be an integer")
                require(row.get("source_id") == expected_source_id,
                        f"actions[{index}] {source_action} pinned source ID changed")
                release_id = row.get("release_action_id")
                require(release_id is None or type(release_id) is int,
                        f"actions[{index}] {source_action} release action ID must be an integer or null")
                require(row.get("release_action_id") == expected_release_id,
                        f"actions[{index}] {source_action} exact release action ID changed")
                writer_status = "unsupported-no-writer" if expected_release_id is None or source_action in {
                    "ADD_ITEM_ENDER", "REMOVE_ITEM_ENDER"
                } else "present"
                require(row.get("release_writer_status") == writer_status,
                        f"actions[{index}] {source_action} exact release writer status changed")
                if expected_release_id is None:
                    require(source_action in release_action_writer_audit.get("absent_release_actions", {}),
                            f"actions[{index}] {source_action} needs exact-release absence evidence")
                else:
                    writer_key = f"{expected_enum}.{source_action}"
                    writer_classes = release_writer_references.get(writer_key)
                    require(isinstance(writer_classes, list),
                            f"actions[{index}] {source_action} needs binary writer-reference evidence")
                    require(bool(writer_classes) == (writer_status == "present"),
                            f"actions[{index}] {source_action} writer status conflicts with the binary matrix")
            if expected_status == "unsupported-no-writer" and source_action == "INTERACT_ENTITY":
                require(row.get("evidence_issue") == 75,
                        f"actions[{index}] INTERACT_ENTITY needs the closed #75 evidence")
                require(row.get("reason_code") == ENTITY_NO_WRITER_REASON,
                        f"actions[{index}] INTERACT_ENTITY needs the exact-release no-action reason")
                require(row.get("itemgraph_extension") == ENTITY_EXTENSION_CONTRACT,
                        f"actions[{index}] INTERACT_ENTITY native capture must remain an extension")
                require("owner_issue" not in row,
                        f"actions[{index}] INTERACT_ENTITY must not remain open under #27")
            elif expected_status == "unsupported-no-writer":
                require(row.get("evidence_issue") == 76,
                        f"actions[{index}] unsupported-no-writer {source_action} needs evidence from issue #76")
                require(row.get("reason_code") == ENDER_NO_WRITER_REASON,
                        f"actions[{index}] unsupported-no-writer {source_action} needs a stable reason code")
                require(row.get("itemgraph_extension") == ENDER_EXTENSION_CONTRACT,
                        f"actions[{index}] {source_action} must label the independent ItemGraph extension")
                require("owner_issue" not in row,
                        f"actions[{index}] unsupported-no-writer {source_action} must not remain open under #27")
            elif expected_status != "unresolved":
                require("owner_issue" not in row and "evidence_issue" not in row,
                        f"actions[{index}] compatible/extended {source_action} must not claim an unresolved owner issue")
            else:
                require(row.get("owner_issue") == 27,
                        f"actions[{index}] unresolved {source_action} must remain owned by issue #27")
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
        if isinstance(row.get("evidence_issue"), int):
            expected_evidence_issue = 75 if source_action == "INTERACT_ENTITY" else 76
            require(row["evidence_issue"] == expected_evidence_issue,
                    f"actions[{index}] evidence_issue must refer to the owning closed child issue #{expected_evidence_issue}")

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
        "d8181c2af8ba8eccb289bf0e6678be2d3a75ada4e0d51c0d459ba257afaf0853",
        "issue #54",
    ]
    for fragment in required_parity_fragments:
        require(fragment in parity, f"parity document is missing required fragment: {fragment}")

    owner_issues = set()
    for section in (registry.get("actions", []), registry.get("configuration_controls", []), registry.get("inspector_behavior", [])):
        for row in section:
            if isinstance(row, dict):
                if isinstance(row.get("owner_issue"), int):
                    owner_issues.add(row["owner_issue"])
                if isinstance(row.get("evidence_issue"), int):
                    owner_issues.add(row["evidence_issue"])
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
