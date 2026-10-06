#!/usr/bin/env python3
"""Validate and normalize the redacted #140 cross-loader conformance report."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


class ReportError(ValueError):
    pass


def validate(report: Any, expected_loader: str) -> dict[str, Any]:
    if not isinstance(report, dict):
        raise ReportError("report must be a JSON object")
    allowed_report_keys = {
        "raw_schema_version", "loader", "scenario_id", "world_scope", "events", "invariants"
    }
    if set(report) != allowed_report_keys:
        raise ReportError("report fields do not match the redacted schema")
    if report.get("raw_schema_version") != 1:
        raise ReportError("raw_schema_version must be 1")
    if report.get("loader") != expected_loader:
        raise ReportError(f"loader must be {expected_loader!r}")
    if report.get("scenario_id") != "player-container-break-contents":
        raise ReportError("unexpected scenario_id")
    if report.get("world_scope") != "isolated_game_test":
        raise ReportError("world_scope must be isolated_game_test")
    events = report.get("events")
    if not isinstance(events, list):
        raise ReportError("events must be a list")
    invariants = report.get("invariants")
    if invariants != {
        "canceled_break_rows": 0,
        "slot_observations": 29,
        "source_quantity": 386,
        "destination_type": "UNKNOWN",
        "drop_links_fabricated": False,
        "queue_rejections": 0,
        "actor_unavailable_rows": 1,
    }:
        raise ReportError("reported invariants do not match the #140 acceptance fixture")

    completions: dict[str, dict[str, Any]] = {}
    unresolved: dict[str, list[dict[str, Any]]] = {}
    actor_unavailable: list[dict[str, Any]] = []
    slots: dict[str, list[dict[str, Any]]] = {}
    event_refs: set[str] = set()
    relative_positions = {(3, 1, 2), (5, 1, 2), (7, 1, 2), (9, 1, 2), (13, 1, 2)}
    for event in events:
        if not isinstance(event, dict):
            raise ReportError("each event must be an object")
        evidence_ref = event.get("evidence_ref")
        if not isinstance(evidence_ref, str) or not re.fullmatch(r"[0-9a-f]{64}", evidence_ref):
            raise ReportError("evidence_ref must be a redacted SHA-256 identifier")
        if evidence_ref in event_refs:
            raise ReportError("evidence_ref values must be unique")
        event_refs.add(evidence_ref)
        action = event.get("action")
        source = event.get("source")
        destination = event.get("destination")
        if not isinstance(source, dict) or set(source) != {"type", "dimension", "position"}:
            raise ReportError("event source fields do not match the redacted endpoint schema")
        if source.get("dimension") != "minecraft:overworld":
            raise ReportError("fixture source dimension is not the isolated overworld")
        position = source.get("position")
        if not isinstance(position, dict) or set(position) != {"x", "y", "z"}:
            raise ReportError("source position must contain only relative x/y/z fields")
        if any(type(position.get(axis)) is not int for axis in ("x", "y", "z")):
            raise ReportError("relative source coordinates must be integers")
        if (position["x"], position["y"], position["z"]) not in relative_positions:
            raise ReportError("source position is outside the fixture's approved relative coordinates")
        if not isinstance(destination, dict) or set(destination) != {"type"}:
            raise ReportError("destination fields do not match the redacted endpoint schema")
        if destination.get("type") != "UNKNOWN":
            raise ReportError("container-break fixture destination must remain UNKNOWN")
        if action == "CONTAINER_BREAK_COMPLETED":
            if set(event) != {
                "evidence_ref", "action", "evidence_class", "source", "destination",
                "actor_ref", "break_event_ref", "stack_slots", "total_items",
            }:
                raise ReportError("completed event fields do not match the redacted schema")
            if event.get("evidence_class") != "OBSERVED":
                raise ReportError("completed container break must be OBSERVED")
            if not isinstance(event.get("break_event_ref"), str) or not re.fullmatch(
                    r"[0-9a-f]{64}", event["break_event_ref"]):
                raise ReportError("completed event must link a redacted standard BREAK_BLOCK identity")
            completions[evidence_ref] = event
        elif action == "REMOVE_ITEM":
            if set(event) != {
                "evidence_ref", "parent_ref", "action", "evidence_class", "source", "destination",
                "actor_ref", "slot", "item_id", "fingerprint_ref", "quantity",
            }:
                raise ReportError("slot event fields do not match the redacted schema")
            parent_ref = event.get("parent_ref")
            if event.get("evidence_class") != "OBSERVED" or event.get("actor_ref") != "player-1":
                raise ReportError("slot removal must be observed and retain the actor alias")
            if source.get("type") != "CONTAINER" or destination.get("type") != "UNKNOWN":
                raise ReportError("slot removal must route CONTAINER to UNKNOWN")
            if destination.get("position") is not None:
                raise ReportError("unknown sink must not invent a position")
            if not isinstance(parent_ref, str):
                raise ReportError("slot removal must link to its parent event")
            if not re.fullmatch(r"[0-9a-f]{64}", parent_ref):
                raise ReportError("slot parent_ref must be a redacted SHA-256 identifier")
            if not isinstance(event.get("slot"), int) or event["slot"] < 0:
                raise ReportError("slot must be a non-negative integer")
            if not isinstance(event.get("quantity"), int) or event["quantity"] < 1:
                raise ReportError("slot quantity must be positive")
            if not isinstance(event.get("item_id"), str) or not isinstance(event.get("fingerprint_ref"), str):
                raise ReportError("slot removal must retain item ID and redacted fingerprint")
            if not re.fullmatch(r"[0-9a-f]{64}", event["fingerprint_ref"]):
                raise ReportError("fingerprint_ref must be redacted")
            slots.setdefault(parent_ref, []).append(event)
        elif action == "CONTAINER_BREAK_UNRESOLVED":
            if event.get("evidence_class") != "UNRESOLVED":
                raise ReportError("container-break unresolved event must be UNRESOLVED")
            if "parent_ref" not in event:
                if set(event) != {
                    "evidence_ref", "action", "evidence_class", "source", "destination",
                    "actor_status", "reason_code",
                }:
                    raise ReportError("actor-unavailable event fields do not match the redacted schema")
                if source.get("type") != "CONTAINER" or position != {"x": 13, "y": 1, "z": 2}:
                    raise ReportError("actor-unavailable event must identify its approved fixture container")
                if event.get("actor_status") != "UNKNOWN":
                    raise ReportError("actor-unavailable event must retain UNKNOWN actor status")
                if event.get("reason_code") != "CONTAINER_BREAK_ACTOR_UNAVAILABLE":
                    raise ReportError("actor-unavailable event requires its stable unresolved reason")
                actor_unavailable.append(event)
            else:
                if set(event) != {
                    "evidence_ref", "parent_ref", "action", "evidence_class", "source", "destination",
                    "actor_ref", "reason_code",
                }:
                    raise ReportError("unresolved event fields do not match the redacted schema")
                parent_ref = event.get("parent_ref")
                if not isinstance(parent_ref, str) or not parent_ref:
                    raise ReportError("unresolved drop outcome must link to a parent event")
                if event.get("reason_code") != "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED":
                    raise ReportError("drop outcome requires its stable unresolved reason")
                if event.get("actor_ref") != "player-1":
                    raise ReportError("unresolved drop outcome must retain the actor alias")
                unresolved.setdefault(parent_ref, []).append(event)
        else:
            raise ReportError(f"unsupported event action: {action!r}")

    if len(completions) != 4:
        raise ReportError("report must contain four completed break events")
    empty_parents = {ref for ref, event in completions.items() if event.get("stack_slots") == 0}
    if empty_parents.intersection(unresolved):
        raise ReportError("an empty break must not have an unresolved drop-link outcome")
    if len(unresolved) != 3:
        raise ReportError("report must contain one unresolved drop-link outcome for each nonempty break")
    if len(actor_unavailable) != 1:
        raise ReportError("report must contain one actor-unavailable unresolved event")
    if set(unresolved) - set(completions):
        raise ReportError("each unresolved drop outcome must link to one completed parent")
    if set(slots) - set(completions):
        raise ReportError("slot removal references a missing completed parent")
    observed_slot_count = 0
    observed_total = 0
    per_break_slot_counts: list[int] = []
    for parent_ref, parent in completions.items():
        source = parent.get("source")
        destination = parent.get("destination")
        if not isinstance(source, dict) or source.get("type") != "CONTAINER":
            raise ReportError("completed break source must be CONTAINER")
        if not isinstance(destination, dict) or destination.get("type") != "UNKNOWN":
            raise ReportError("completed break destination must be UNKNOWN")
        if destination.get("position") is not None:
            raise ReportError("completed break must not invent an unknown sink position")
        if parent.get("actor_ref") != "player-1":
            raise ReportError("completed break must retain the player actor alias")
        children = slots.get(parent_ref, [])
        if any(child.get("parent_ref") != parent_ref for child in children):
            raise ReportError("slot removal parent reference does not match its completed break")
        expected_unresolved = 0 if parent.get("stack_slots") == 0 else 1
        if len(unresolved.get(parent_ref, [])) != expected_unresolved:
            raise ReportError("only nonempty breaks may have an unresolved drop-link record")
        if parent.get("stack_slots") != len(children):
            raise ReportError("parent stack_slots does not match its observed children")
        total = sum(child["quantity"] for child in children)
        if parent.get("total_items") != total:
            raise ReportError("parent total_items does not match its observed child quantities")
        observed_slot_count += len(children)
        observed_total += total
        per_break_slot_counts.append(len(children))
    if sorted(per_break_slot_counts) != [0, 1, 1, 27]:
        raise ReportError("fixture must prove empty, one-stack, and full single-container cases")
    if observed_slot_count != 29 or observed_total != 386:
        raise ReportError("slot observations must conserve the fixture's 386 items")

    forbidden_keys = {
        "player_uuid", "player_name", "uuid", "source_event_id", "row_id", "raw_data",
        "absolute_position", "world_coordinates", "database_id",
    }

    def check_redaction(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key.lower() in forbidden_keys:
                    raise ReportError(f"sensitive field is forbidden in report: {key}")
                check_redaction(child)
        elif isinstance(value, list):
            for child in value:
                check_redaction(child)

    check_redaction(report)
    return {
        "schema_version": 1,
        "loader": expected_loader,
        "scenario_id": report["scenario_id"],
        "events": events,
        "invariants": invariants,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--loader", required=True, choices=("fabric", "neoforge"))
    args = parser.parse_args()
    try:
        source = json.loads(args.input.read_text(encoding="utf-8"))
        normalized = validate(source, args.loader)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(normalized, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        else:
            print(json.dumps(normalized, indent=2, sort_keys=True))
    except (OSError, json.JSONDecodeError, ReportError) as error:
        print(f"invalid ItemGraph container-break report: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
