#!/usr/bin/env python3
"""Compare normalized GriefLogger and ItemGraph replay reports.

This tool deliberately consumes exported JSON instead of opening either
database. The report contract is versioned and keyed to the checked-in
compatibility profile and exact-release fixture.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_PATH = ROOT / "docs" / "GRIEFLOGGER_COMPATIBILITY.json"
FIXTURE_PATH = ROOT / "docs" / "grieflogger-fixtures" / "1.2.10-1.21.1.json"
REPORT_SCHEMA_VERSION = 2
LOADERS = {"fabric", "neoforge"}
SYSTEMS = {"grieflogger", "itemgraph"}
RUNTIME_MODES = {"grieflogger_present", "native_only"}
PRIVACY_CLASSES = {"replay_fixture_only", "staging_restricted"}
ITEMGRAPH_SOURCE_TABLES = {"ig_observations", "ig_audit_events", "ig_item_transformations"}
SAFE_KEY_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z")
SAFE_RESOURCE_ID_RE = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")
SAFE_REASON_RE = re.compile(r"[A-Z][A-Z0-9_]{0,127}\Z")
UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\Z")
ACTION_TABLES = {
    "ADD_ITEM": {"items", "containers"}, "REMOVE_ITEM": {"items", "containers"},
    "DROP_ITEM": {"items"}, "PICKUP_ITEM": {"items"}, "CRAFT": {"items"},
    "BREAK_ITEM": {"items"}, "CONSUME_ITEM": {"items"}, "THROW_ITEM": {"items"},
    "SHOOT_ITEM": {"items"}, "PLACE_BLOCK": {"blocks"}, "BREAK_BLOCK": {"blocks"},
    "INTERACT_BLOCK_ATTEMPT": {"blocks"}, "KILL_ENTITY": {"blocks"},
    "INTERACT_ENTITY": {"blocks"}, "PLAYER_JOIN": {"sessions"}, "PLAYER_QUIT": {"sessions"},
    "CHAT_MESSAGE": {"chats"}, "COMMAND_ATTEMPT": {"commands"}, "SMELT": {"items"},
    "ANVIL_RENAME": {"items"}, "ANVIL_REPAIR": {"items"},
    "HOPPER_INSERT": {"containers"}, "HOPPER_EXTRACT": {"containers"},
    "ADD_ITEM_ENDER": {"items"}, "REMOVE_ITEM_ENDER": {"items"},
}
SIGNED_DELTA_SIGNS = {
    "ADD_ITEM": 1, "PICKUP_ITEM": 1, "ADD_ITEM_ENDER": 1, "HOPPER_INSERT": 1,
    "REMOVE_ITEM": -1, "DROP_ITEM": -1, "BREAK_ITEM": -1, "CONSUME_ITEM": -1,
    "THROW_ITEM": -1, "SHOOT_ITEM": -1, "REMOVE_ITEM_ENDER": -1, "HOPPER_EXTRACT": -1,
}
UNRESOLVED_SOURCE_ACTION = "UNRESOLVED_SOURCE_ACTION"
EVENT_FIELDS = (
    "sequence",
    "action",
    "evidence_class",
    "quantity",
    "item_id",
    "occurred_at_ms",
    "dimension",
    "position",
    "actor_ref",
    "source_table",
    "source_action_id",
    "compatibility_table",
    "compatibility_action_id",
    "privacy_class",
    "unresolved_reason",
)
COMPARABLE_EVENT_FIELDS = tuple(field for field in EVENT_FIELDS if field not in {"source_table", "source_action_id"})


class ReportError(ValueError):
    """Invalid or incomparable replay report."""


def _object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReportError("duplicate JSON object key")
        result[key] = value
    return result


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_object)
    except (OSError, UnicodeError, json.JSONDecodeError, ReportError) as exc:
        raise ReportError(f"cannot read {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ReportError(f"{path} must contain a JSON object")
    return value


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def current_profile() -> tuple[dict[str, Any], str]:
    registry = read_json(REGISTRY_PATH)
    profile = registry.get("source_profile")
    if not isinstance(profile, dict) or not isinstance(profile.get("sha256"), str):
        raise ReportError("compatibility registry has no source-profile SHA-256")
    baseline = registry.get("audit_baseline")
    if not isinstance(baseline, dict):
        raise ReportError("compatibility registry has no GriefLogger source baseline")
    source_profile = {
        "actions": sorted(profile.get("actions", [])),
        "commit": baseline.get("commit"),
        "ref": baseline.get("ref"),
        "source_files": sorted(baseline.get("source_files", [])),
        "tables": sorted(profile.get("tables", [])),
    }
    if hashlib.sha256(canonical_json(source_profile)).hexdigest() != profile["sha256"]:
        raise ReportError("source-profile SHA-256 does not match its canonical registry data")
    mapped_actions = {row.get("itemgraph") for row in registry.get("actions", []) if isinstance(row, dict)}
    if mapped_actions != set(ACTION_TABLES):
        raise ReportError("normalized action-to-table contract does not match the compatibility registry")
    fixture = read_json(FIXTURE_PATH)
    fixture_hash = fixture.get("fixture_sha256")
    if not isinstance(fixture_hash, str):
        raise ReportError("exact-release fixture has no fixture SHA-256")
    unsigned_fixture = {key: value for key, value in fixture.items() if key != "fixture_sha256"}
    actual_fixture_hash = hashlib.sha256(canonical_json(unsigned_fixture)).hexdigest()
    if actual_fixture_hash != fixture_hash:
        raise ReportError("exact-release fixture SHA-256 does not match its canonical JSON")
    return registry, fixture_hash


def _is_integer(value: Any) -> bool:
    return type(value) is int


def _validate_event(event: Any, index: int, registry: dict[str, Any], system: str) -> dict[str, Any]:
    if not isinstance(event, dict):
        raise ReportError(f"events[{index}] must be an object")
    expected = {"event_key", *EVENT_FIELDS}
    if set(event) != expected:
        raise ReportError(f"events[{index}] fields do not match the report schema")
    if (not isinstance(event["event_key"], str) or not SAFE_KEY_RE.fullmatch(event["event_key"])
            or UUID_RE.fullmatch(event["event_key"])):
        raise ReportError(f"events[{index}].event_key must be a safe scenario-local opaque key")
    if not isinstance(event["action"], str):
        raise ReportError(f"events[{index}].action must be a compatibility-profile action")
    action_row = next((row for row in registry["actions"] if row.get("itemgraph") == event["action"]), None)
    unknown_source_action = event["action"] == UNRESOLVED_SOURCE_ACTION
    if action_row is None and not unknown_source_action:
        raise ReportError(f"events[{index}].action is not mapped by the compatibility profile")
    classes = set(registry.get("evidence_classes", []))
    if not isinstance(event["evidence_class"], str) or event["evidence_class"] not in classes:
        raise ReportError(f"events[{index}].evidence_class is not in the compatibility profile")
    expected_class = "unresolved" if unknown_source_action else action_row["evidence_class"]
    if event["evidence_class"] == "unresolved" and event["unresolved_reason"] is None:
        raise ReportError(f"events[{index}] unresolved evidence must keep its unresolved_reason")
    if event["evidence_class"] != expected_class and not (
            event["evidence_class"] == "unresolved" and event["unresolved_reason"] is not None):
        raise ReportError(f"events[{index}].evidence_class does not match the mapped action evidence class")
    if not _is_integer(event["sequence"]) or event["sequence"] < 0:
        raise ReportError(f"events[{index}].sequence must be a non-negative integer")
    quantity = event["quantity"]
    if quantity is not None and not _is_integer(quantity):
        raise ReportError(f"events[{index}].quantity must be an integer or null")
    quantity_semantics = "none" if unknown_source_action else action_row["quantity"]
    if quantity_semantics == "none" and quantity is not None:
        raise ReportError(f"events[{index}].quantity must be null for an action with no quantity semantics")
    if quantity_semantics == "observed_stack_count" and (quantity is None or quantity <= 0):
        raise ReportError(f"events[{index}].quantity must be a positive observed stack count")
    if quantity_semantics == "signed_delta":
        expected_sign = SIGNED_DELTA_SIGNS.get(event["action"])
        if expected_sign is None:
            raise ReportError(f"events[{index}] has no signed quantity direction in the differential contract")
        if quantity is None or quantity * expected_sign <= 0:
            direction = "positive" if expected_sign > 0 else "negative"
            raise ReportError(f"events[{index}].quantity must be a {direction} signed delta for this action")
    if quantity_semantics == "transformation" and (quantity is None or quantity <= 0):
        raise ReportError(f"events[{index}].quantity must be a positive transformed result count")
    if quantity_semantics not in {"none", "observed_stack_count", "signed_delta", "transformation"}:
        raise ReportError(f"events[{index}] has unsupported quantity semantics in the compatibility profile")
    if not _is_integer(event["occurred_at_ms"]):
        raise ReportError(f"events[{index}].occurred_at_ms must be an integer Unix-millisecond timestamp")
    if event["dimension"] is not None and (not isinstance(event["dimension"], str)
                                            or not SAFE_RESOURCE_ID_RE.fullmatch(event["dimension"])):
        raise ReportError(f"events[{index}].dimension must be a namespaced dimension ID or null")
    position = event["position"]
    if position is not None:
        if not isinstance(position, dict) or set(position) != {"x", "y", "z"}:
            raise ReportError(f"events[{index}].position must be null or an x/y/z object")
        if any(not _is_integer(position[axis]) for axis in ("x", "y", "z")):
            raise ReportError(f"events[{index}].position coordinates must be integers")
    for field in ("item_id", "actor_ref", "source_table", "privacy_class", "unresolved_reason",
                  "compatibility_table"):
        if event[field] is not None and (not isinstance(event[field], str) or not event[field]):
            raise ReportError(f"events[{index}].{field} must be a non-empty string or null")
    if event["source_table"] is None:
        raise ReportError(f"events[{index}].source_table must name the system's source table")
    if event["compatibility_table"] not in registry["source_profile"]["tables"]:
        raise ReportError(f"events[{index}].compatibility_table is not a table in the pinned source profile")
    if not unknown_source_action and event["compatibility_table"] not in ACTION_TABLES[event["action"]]:
        raise ReportError(f"events[{index}].compatibility_table does not match the mapped action table family")
    if event["privacy_class"] not in PRIVACY_CLASSES:
        raise ReportError(f"events[{index}].privacy_class must be one of {sorted(PRIVACY_CLASSES)}")
    if event["item_id"] is not None and not SAFE_RESOURCE_ID_RE.fullmatch(event["item_id"]):
        raise ReportError(f"events[{index}].item_id must be a namespaced registry ID")
    actor_ref = event["actor_ref"]
    if actor_ref is not None and (not re.fullmatch(r"actor:replay-[A-Za-z0-9_-]+", actor_ref)
                                  or UUID_RE.search(actor_ref)):
        raise ReportError(f"events[{index}].actor_ref must be a replay-local opaque actor alias")
    expected_action_id = action_row.get("release_action_id") if not unknown_source_action else None
    source_action_id = event["source_action_id"]
    compatibility_action_id = event["compatibility_action_id"]
    if unknown_source_action:
        if compatibility_action_id is not None:
            raise ReportError(f"events[{index}].compatibility_action_id must be null for an unresolved action")
        if system == "grieflogger":
            if not _is_integer(source_action_id) or source_action_id < 0:
                raise ReportError(f"events[{index}].source_action_id for an unresolved GriefLogger action must be non-negative")
            if event["source_table"] not in registry["source_profile"]["tables"]:
                raise ReportError(f"events[{index}].source_table is not a table in the pinned source profile")
        elif (event["source_table"] not in ITEMGRAPH_SOURCE_TABLES
              or not isinstance(source_action_id, str) or not source_action_id):
            raise ReportError(f"events[{index}] unresolved ItemGraph source identity is invalid")
        if event["quantity"] is not None or event["item_id"] is not None:
            raise ReportError(f"events[{index}] unknown source actions cannot claim quantity or item identity")
    else:
        if expected_action_id is None:
            if compatibility_action_id is not None:
                raise ReportError(f"events[{index}].compatibility_action_id must be null when the release has no action ID")
        elif not _is_integer(compatibility_action_id):
            raise ReportError(f"events[{index}].compatibility_action_id must be an integer")
        elif compatibility_action_id != expected_action_id:
            raise ReportError(f"events[{index}].compatibility_action_id does not match the mapped release action ID")
        if system == "grieflogger":
            if event["source_table"] != event["compatibility_table"]:
                raise ReportError(f"events[{index}].source_table does not match its GriefLogger compatibility table")
            if expected_action_id is not None and not _is_integer(source_action_id):
                raise ReportError(f"events[{index}].source_action_id must be an integer, not a boolean or other value")
            if source_action_id != compatibility_action_id:
                raise ReportError(f"events[{index}].source_action_id does not match its GriefLogger action mapping")
        elif event["source_table"] not in ITEMGRAPH_SOURCE_TABLES or source_action_id != event["action"]:
            raise ReportError(f"events[{index}] ItemGraph raw source identity does not match its action")
    reason = event["unresolved_reason"]
    if reason is not None and not SAFE_REASON_RE.fullmatch(reason):
        raise ReportError(f"events[{index}].unresolved_reason must be a stable uppercase reason code")
    if event["evidence_class"] == "unresolved" and event["unresolved_reason"] is None:
        raise ReportError(f"events[{index}] unresolved evidence must keep its unresolved_reason")
    if unknown_source_action and (event["quantity"] is not None or event["item_id"] is not None):
        raise ReportError(f"events[{index}] unknown source actions cannot claim quantity or item identity")
    return event


def validate_report(report: dict[str, Any], expected_system: str | None = None) -> dict[str, Any]:
    registry, fixture_hash = current_profile()
    if type(report.get("report_schema_version")) is not int or report["report_schema_version"] != REPORT_SCHEMA_VERSION:
        raise ReportError(f"report_schema_version must be integer {REPORT_SCHEMA_VERSION}")
    if report.get("compatibility_version") != registry.get("compatibility_version"):
        raise ReportError("compatibility_version does not match the checked-in profile")
    if report.get("source_profile_sha256") != registry["source_profile"]["sha256"]:
        raise ReportError("source_profile_sha256 does not match the checked-in profile")
    if report.get("release_fixture_sha256") != fixture_hash:
        raise ReportError("release_fixture_sha256 does not match the checked-in exact-release fixture")
    if not isinstance(report.get("loader"), str) or report["loader"] not in LOADERS:
        raise ReportError("loader must be fabric or neoforge")
    if (not isinstance(report.get("system"), str) or report["system"] not in SYSTEMS
            or (expected_system and report["system"] != expected_system)):
        raise ReportError(f"system must be {expected_system}" if expected_system else "system must be grieflogger or itemgraph")
    required = {"report_schema_version", "compatibility_version", "source_profile_sha256", "release_fixture_sha256",
                "loader", "system", "runtime_mode", "scenario_id", "seed", "events"}
    if set(report) != required:
        raise ReportError("report fields do not match the report schema")
    if not isinstance(report["runtime_mode"], str) or report["runtime_mode"] not in RUNTIME_MODES:
        raise ReportError("runtime_mode must be grieflogger_present or native_only")
    expected_mode = "grieflogger_present" if report["system"] == "grieflogger" else "native_only"
    if report["runtime_mode"] != expected_mode:
        raise ReportError(f"{report['system']} report must declare runtime_mode={expected_mode}")
    if (not isinstance(report["scenario_id"], str) or not SAFE_KEY_RE.fullmatch(report["scenario_id"])
            or UUID_RE.fullmatch(report["scenario_id"])):
        raise ReportError("scenario_id must be a safe opaque scenario identifier")
    if not _is_integer(report["seed"]):
        raise ReportError("seed must be an integer")
    if not isinstance(report["events"], list):
        raise ReportError("events must be an array")
    seen: set[str] = set()
    seen_sequences: set[int] = set()
    prior_time: int | None = None
    prior_sequence: int | None = None
    for index, raw_event in enumerate(report["events"]):
        event = _validate_event(raw_event, index, registry, report["system"])
        key = event["event_key"]
        if key in seen:
            raise ReportError(f"duplicate event_key at events[{index}]")
        seen.add(key)
        if event["sequence"] in seen_sequences:
            raise ReportError(f"duplicate event sequence {event['sequence']}")
        seen_sequences.add(event["sequence"])
        if prior_sequence is not None and event["sequence"] <= prior_sequence:
            raise ReportError("events must be listed in strictly increasing sequence order")
        if prior_time is not None and event["occurred_at_ms"] < prior_time:
            raise ReportError(f"events are not chronological at sequence {event['sequence']}")
        prior_sequence = event["sequence"]
        prior_time = event["occurred_at_ms"]
    return report


def compare_reports(legacy: dict[str, Any], native: dict[str, Any]) -> dict[str, Any]:
    validate_report(legacy, "grieflogger")
    validate_report(native, "itemgraph")
    for field in ("compatibility_version", "source_profile_sha256", "release_fixture_sha256", "loader", "scenario_id", "seed"):
        if legacy[field] != native[field]:
            raise ReportError(f"reports disagree on {field}")
    old_rows = {row["event_key"]: row for row in legacy["events"]}
    new_rows = {row["event_key"]: row for row in native["events"]}
    differences: list[dict[str, Any]] = []
    for key in sorted(old_rows.keys() | new_rows.keys()):
        if key not in old_rows:
            differences.append({"sequence": new_rows[key]["sequence"], "kind": "unexpected_native_event", "field": None,
                                "grieflogger": None, "itemgraph": _safe_event(new_rows[key])})
            continue
        if key not in new_rows:
            differences.append({"sequence": old_rows[key]["sequence"], "kind": "missing_native_event", "field": None,
                                "grieflogger": _safe_event(old_rows[key]), "itemgraph": None})
            continue
        for field in COMPARABLE_EVENT_FIELDS:
            if old_rows[key][field] != new_rows[key][field]:
                differences.append({"sequence": old_rows[key]["sequence"],
                                    "itemgraph_sequence": new_rows[key]["sequence"],
                                    "kind": "field_mismatch", "field": field,
                                    "grieflogger": _safe_field(field, old_rows[key][field]),
                                    "itemgraph": _safe_field(field, new_rows[key][field])})
    legacy_order = [r["event_key"] for r in sorted(legacy["events"], key=lambda r: r["sequence"])]
    native_order = [r["event_key"] for r in sorted(native["events"], key=lambda r: r["sequence"])]
    common_keys = old_rows.keys() & new_rows.keys()
    common_legacy_order = [key for key in legacy_order if key in common_keys]
    common_native_order = [key for key in native_order if key in common_keys]
    if common_legacy_order != common_native_order:
        differences.append({"sequence": None, "kind": "temporal_order_mismatch", "field": "occurred_at_ms",
                            "grieflogger": [old_rows[key]["sequence"] for key in common_legacy_order],
                            "itemgraph": [new_rows[key]["sequence"] for key in common_native_order]})
    return {
        "report_schema_version": REPORT_SCHEMA_VERSION,
        "compatibility_version": legacy["compatibility_version"],
        "source_profile_sha256": legacy["source_profile_sha256"],
        "release_fixture_sha256": legacy["release_fixture_sha256"],
        "loader": legacy["loader"],
        "scenario_id": _opaque_key(legacy["scenario_id"]),
        "seed": legacy["seed"],
        "equivalent": not differences,
        "event_counts": {"grieflogger": len(legacy["events"]), "itemgraph": len(native["events"])},
        "differences": differences,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--grieflogger", type=Path, help="normalized GriefLogger replay JSON")
    parser.add_argument("--itemgraph", type=Path, help="normalized ItemGraph replay JSON")
    parser.add_argument("--output", type=Path, help="machine-readable comparison report JSON")
    parser.add_argument("--validate-only", type=Path, help="validate one report without claiming a cross-system comparison")
    parser.add_argument("--expected-system", choices=sorted(SYSTEMS), help="required with --validate-only")
    parser.add_argument("--expected-loader", choices=sorted(LOADERS), help="optionally pin --validate-only to one loader")
    args = parser.parse_args(argv)
    try:
        if args.validate_only is not None:
            if args.grieflogger is not None or args.itemgraph is not None or args.output is not None:
                raise ReportError("--validate-only cannot be combined with comparison inputs or output")
            if args.expected_system is None:
                raise ReportError("--expected-system is required with --validate-only")
            document = read_json(args.validate_only)
            validate_report(document, args.expected_system)
            if args.expected_loader is not None and document["loader"] != args.expected_loader:
                raise ReportError("report loader does not match --expected-loader")
            print(f"validated {document['system']} report ({document['loader']}): {len(document['events'])} events")
            return 0
        if args.grieflogger is None or args.itemgraph is None or args.output is None:
            raise ReportError("comparison requires --grieflogger, --itemgraph, and --output")
        if any(_paths_alias(args.output, source) for source in (args.grieflogger, args.itemgraph)):
            raise ReportError("output path must not overwrite either input report")
        result = compare_reports(read_json(args.grieflogger), read_json(args.itemgraph))
        _write_json_atomically(args.output, result)
    except (OSError, ReportError) as exc:
        print(f"differential report failed: {exc}", file=sys.stderr)
        return 2
    summary = "equivalent" if result["equivalent"] else f"{len(result['differences'])} unexplained difference(s)"
    print(f"{result['scenario_id']} ({result['loader']}): {summary}")
    return 0 if result["equivalent"] else 1


def _paths_alias(output: Path, source: Path) -> bool:
    if output.resolve() == source.resolve():
        return True
    try:
        return os.path.samefile(output, source)
    except FileNotFoundError:
        return False


def _opaque_key(value: str) -> str:
    return hashlib.sha256(("itemgraph-differential-v2:" + value).encode("utf-8")).hexdigest()[:24]


def _safe_event(event: dict[str, Any]) -> dict[str, Any]:
    return {key: _safe_field(key, value) for key, value in event.items() if key != "event_key"}


def _safe_field(field: str, value: Any) -> Any:
    if field in {"actor_ref", "position", "unresolved_reason"} and value is not None:
        return "[REDACTED]"
    return value


def _write_json_atomically(output: Path, result: dict[str, Any]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="\n", dir=output.parent,
                                         prefix=f".{output.name}.", suffix=".tmp", delete=False) as stream:
            temporary_path = Path(stream.name)
            stream.write(json.dumps(result, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
