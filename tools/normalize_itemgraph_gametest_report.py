#!/usr/bin/env python3
"""Pin and validate the native-only JSON emitted by shared loader GameTests."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

import itemgraph_differential_report as differential


RAW_SCHEMA_VERSION = 2
RAW_EVENT_FIELDS = {
    "event_key", "sequence", "action", "evidence_class", "quantity", "item_id", "occurred_at_ms",
    "dimension", "position", "actor_ref", "source_table", "source_action_id", "compatibility_table",
    "privacy_class", "unresolved_reason",
}
EXPECTED_ACTIONS = {"ADD_ITEM", "REMOVE_ITEM", "DROP_ITEM", "PICKUP_ITEM", "THROW_ITEM", "SHOOT_ITEM"}
SCENARIO_ID = "item-movement-projectile-replay"
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
    if len(raw["events"]) != len(EXPECTED_ACTIONS):
        raise differential.ReportError("raw report must contain exactly the six native replay events")
    registry, fixture_hash = differential.current_profile()
    differential._validate_itemgraph_invariants(raw["invariants"])
    normalized_events: list[dict[str, Any]] = []
    for index, raw_event in enumerate(raw["events"]):
        if not isinstance(raw_event, dict) or set(raw_event) != RAW_EVENT_FIELDS:
            raise differential.ReportError(f"events[{index}] fields do not match the ItemGraph GameTest schema")
        event = dict(raw_event)
        if not isinstance(event["action"], str) or event["action"] not in EXPECTED_ACTIONS:
            raise differential.ReportError(f"events[{index}].action is not part of the native replay fixture")
        if event["event_key"] != "replay-" + event["action"].lower():
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
    if {event["action"] for event in normalized_events} != EXPECTED_ACTIONS:
        raise differential.ReportError("raw report must contain each expected native replay action exactly once")
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
    return differential.validate_report(report, "itemgraph")


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
