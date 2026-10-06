#!/usr/bin/env python3
"""Pin and validate the native-only JSON emitted by shared loader GameTests."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

import itemgraph_differential_report as differential


RAW_SCHEMA_VERSION = 5
RAW_EVENT_FIELDS = {
    "event_key", "sequence", "action", "evidence_class", "quantity", "item_id", "occurred_at_ms",
    "dimension", "position", "subject_id", "actor_ref", "source_table", "source_action_id", "compatibility_table",
    "privacy_class", "unresolved_reason",
}
EXPECTED_ACTION_COUNTS = {
    "PLAYER_JOIN": 3,
    "PLAYER_QUIT": 1,
    "CHAT_MESSAGE": 1,
    "COMMAND_ATTEMPT": 1,
    "ADD_ITEM": 1,
    "REMOVE_ITEM": 1,
    "DROP_ITEM": 1,
    "PICKUP_ITEM": 1,
    "THROW_ITEM": 1,
    "SHOOT_ITEM": 1,
    "CONSUME_ITEM": 1,
    "BREAK_ITEM": 1,
    "CRAFT_OUTPUT_UNRESOLVED": 1,
    "BREAK_BLOCK": 1,
    "PLACE_BLOCK": 1,
    "INTERACT_BLOCK_ATTEMPT": 1,
    "KILL_ENTITY": 1,
    "INTERACT_ENTITY": 3,
    "INTERACT_ENTITY_COMPLETED": 2,
    "INTERACT_ENTITY_UNRESOLVED": 1,
}
EXPECTED_AUDIT_ACTION_SUBJECT_COUNTS = {
    ("BREAK_BLOCK", "minecraft:water"): 1,
    ("PLACE_BLOCK", "minecraft:diamond_block"): 1,
    ("INTERACT_BLOCK_ATTEMPT", "minecraft:chest"): 1,
    ("KILL_ENTITY", "minecraft:cow"): 1,
    ("INTERACT_ENTITY", "minecraft:cow"): 1,
    ("INTERACT_ENTITY", "minecraft:armor_stand"): 2,
    ("INTERACT_ENTITY_COMPLETED", "minecraft:armor_stand"): 2,
    ("INTERACT_ENTITY_UNRESOLVED", "minecraft:armor_stand"): 1,
    ("CRAFT_OUTPUT_UNRESOLVED", "minecraft:book"): 1,
}
EXPECTED_BLOCK_AUDIT_POSITIONS = {
    "PLACE_BLOCK": {"x": 14, "y": 2, "z": 2},
    "INTERACT_BLOCK_ATTEMPT": {"x": 12, "y": 1, "z": 2},
    "KILL_ENTITY": {"x": 16, "y": 1, "z": 2},
}
SCENARIO_ID = "item-movement-projectile-block-entity-audit-replay"
SCENARIO_SEED = 0


def normalize(raw: dict[str, Any], expected_loader: str) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise differential.ReportError("raw ItemGraph report must be a JSON object")
    if type(raw.get("raw_schema_version")) is not int or raw["raw_schema_version"] != RAW_SCHEMA_VERSION:
        raise differential.ReportError(f"raw_schema_version must be integer {RAW_SCHEMA_VERSION}")
    if raw.get("loader") != expected_loader:
        raise differential.ReportError("raw report loader does not match the requested loader")
    if set(raw) != {"raw_schema_version", "loader", "scenario_id", "seed", "events", "invariants"}:
        raise differential.ReportError("raw report fields do not match the ItemGraph GameTest schema")
    if raw["scenario_id"] != SCENARIO_ID:
        raise differential.ReportError("raw report scenario_id does not match the native replay fixture")
    if type(raw["seed"]) is not int or raw["seed"] != SCENARIO_SEED:
        raise differential.ReportError("raw report seed does not match the native replay fixture")
    if not isinstance(raw["events"], list):
        raise differential.ReportError("raw report events must be an array")
    expected_event_count = sum(EXPECTED_ACTION_COUNTS.values())
    if len(raw["events"]) != expected_event_count:
        raise differential.ReportError("raw report must contain exactly the pinned native replay event count")
    registry, fixture_hash = differential.current_profile()
    differential._validate_itemgraph_invariants(raw["invariants"])
    normalized_events: list[dict[str, Any]] = []
    action_occurrences: dict[str, int] = {}
    actual_action_counts: dict[str, int] = {}
    actual_audit_pairs: dict[tuple[str, str], int] = {}
    for index, raw_event in enumerate(raw["events"]):
        if not isinstance(raw_event, dict) or set(raw_event) != RAW_EVENT_FIELDS:
            raise differential.ReportError(f"events[{index}] fields do not match the ItemGraph GameTest schema")
        event = dict(raw_event)
        if not isinstance(event["action"], str) or event["action"] not in EXPECTED_ACTION_COUNTS:
            raise differential.ReportError(f"events[{index}].action is not part of the native replay fixture")
        occurrence = action_occurrences.get(event["action"], 0)
        action_occurrences[event["action"]] = occurrence + 1
        actual_action_counts[event["action"]] = actual_action_counts.get(event["action"], 0) + 1
        if event["source_table"] == "ig_audit_events" and event["subject_id"] is not None:
            pair = (event["action"], event["subject_id"])
            actual_audit_pairs[pair] = actual_audit_pairs.get(pair, 0) + 1
        if event["event_key"] != f"replay-{event['action'].lower()}-{occurrence}":
            raise differential.ReportError(f"events[{index}].event_key does not match its native replay action")
        if type(event["sequence"]) is not int or event["sequence"] != index:
            raise differential.ReportError("native replay event sequences must be contiguous and start at zero")
        action_row = next((row for row in registry["actions"] if row.get("itemgraph") == event["action"]), None)
        if action_row is None and event["action"] != differential.UNRESOLVED_SOURCE_ACTION:
            raise differential.ReportError(f"events[{index}].action is not mapped by the compatibility profile")
        if action_row is None:
            event["compatibility_action_id"] = None
        else:
            event["compatibility_action_id"] = action_row.get("release_action_id")
            if action_row["quantity"] == "signed_delta":
                sign = differential.SIGNED_DELTA_SIGNS.get(event["action"])
                if sign is None:
                    raise differential.ReportError(f"events[{index}] has no signed quantity direction")
                if type(event["quantity"]) is not int or event["quantity"] <= 0:
                    raise differential.ReportError(f"events[{index}].quantity must be a positive raw item count")
                event["quantity"] *= sign
        normalized_events.append(event)
    if actual_action_counts != EXPECTED_ACTION_COUNTS:
        raise differential.ReportError("raw report action counts do not match the pinned native replay fixture")
    report = {
        "report_schema_version": differential.REPORT_SCHEMA_VERSION,
        "compatibility_version": registry["compatibility_version"],
        "source_profile_sha256": registry["source_profile"]["sha256"],
        "release_fixture_sha256": fixture_hash,
        "loader": raw["loader"],
        "system": "itemgraph",
        "runtime_mode": "native_only",
        "scenario_id": raw["scenario_id"],
        "seed": raw["seed"],
        "events": normalized_events,
        "invariants": raw["invariants"],
    }
    validated = differential.validate_report(report, "itemgraph")
    if actual_audit_pairs != EXPECTED_AUDIT_ACTION_SUBJECT_COUNTS:
        raise differential.ReportError("raw report audit action/subject pairs do not match the native replay fixture")
    for index, event in enumerate(normalized_events):
        expected_position = EXPECTED_BLOCK_AUDIT_POSITIONS.get(event["action"])
        if event["source_table"] == "ig_audit_events" and expected_position is not None \
                and event["position"] != expected_position:
            raise differential.ReportError(
                f"events[{index}] {event['action']} position does not match the native replay target")
    return validated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", required=True, choices=sorted(differential.LOADERS))
    parser.add_argument("--input", required=True, type=Path, help="raw ItemGraph GameTest report")
    parser.add_argument("--output", required=True, type=Path, help="profile-pinned normalized report")
    args = parser.parse_args(argv)
    try:
        if differential._paths_alias(args.output, args.input):
            raise differential.ReportError("output path must not overwrite the raw input report")
        report = normalize(differential.read_json(args.input), args.loader)
        differential._write_json_atomically(args.output, report)
    except (OSError, differential.ReportError) as exc:
        print(f"ItemGraph GameTest report failed: {exc}", file=sys.stderr)
        return 2
    print(f"normalized {args.loader} native-only ItemGraph report: {len(report['events'])} events")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
