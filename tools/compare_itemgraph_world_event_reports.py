#!/usr/bin/env python3
"""Validate and compare redacted Fabric/NeoForge world-event replay reports."""

from __future__ import annotations

import argparse
from collections import Counter
import json
import re
import sys
from pathlib import Path
from typing import Any


SCENARIOS = {
    "explosion": {"EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"},
    "piston": {"PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"},
    "environment": {"FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                    "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"},
}
LOADERS = {"fabric", "neoforge"}
EVIDENCE_CLASSES = {"OBSERVED", "INFERRED", "AMBIGUOUS", "UNRESOLVED"}
RELIABILITIES = {"AUTHORITATIVE_GAME_RESULT", "GAME_CALLBACK_ATTEMPT", "DIRECT_STATE_DELTA",
                 "CORRELATED_EVIDENCE", "UNRESOLVED_CAUSE"}
OUTCOMES = {"CONFIRMED_CHANGE", "CANDIDATE", "CANCELLED", "UNCHANGED", "PARTIAL", "UNRESOLVED"}
EVENT_FIELDS = {
    "sequence", "event_type", "evidence_class", "source_reliability", "quantity_semantics", "outcome",
    "reason_code", "cause", "privacy_class", "dimension", "position", "subject_id", "before_state",
    "after_state", "cause_ref",
}
INVARIANT_FIELDS = {
    "healthy", "over_allocated_observations", "invalid_edge_allocations", "invalid_edge_temporal",
    "non_positive_quantities", "orphaned_allocations", "invalid_edge_nodes", "status_mismatches",
}
RESOURCE_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")
REASON_CODE = re.compile(r"[A-Z][A-Z0-9_]{0,127}\Z")
CAUSE_REF = re.compile(r"cause-[0-9]{1,6}\Z")
CAUSE_ALIASES = {
    "explosion_destroy", "explosion_keep", "piston_extend", "fluid", "fluid_spread", "fluid_exception",
    "fire_burnout_remove", "fire_burnout_set", "fire_tick_set", "enderman_take", "enderman_place",
    "falling_block", "falling_spawn", "falling_exception", "falling_land",
}
UUID = re.compile(r"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


class ReportError(ValueError):
    pass


def validate(document: Any, expected_loader: str, expected_scenario: str) -> dict[str, Any]:
    if not isinstance(document, dict) or set(document) != {
            "schema_version", "loader", "scenario", "invariants", "events"}:
        raise ReportError("report root does not match the redacted world-event schema")
    if type(document["schema_version"]) is not int or document["schema_version"] != 1:
        raise ReportError("schema_version must be integer 1")
    if document["loader"] != expected_loader or expected_loader not in LOADERS:
        raise ReportError("report loader does not match the requested loader")
    if document["scenario"] != expected_scenario or expected_scenario not in SCENARIOS:
        raise ReportError("report scenario does not match the requested world-event family")

    invariants = document["invariants"]
    if not isinstance(invariants, dict) or set(invariants) != INVARIANT_FIELDS:
        raise ReportError("invariants do not match the ItemGraph whole-graph audit schema")
    if invariants["healthy"] is not True:
        raise ReportError("ItemGraph whole-graph audit must be healthy")
    for name in INVARIANT_FIELDS - {"healthy"}:
        if type(invariants[name]) is not int or invariants[name] != 0:
            raise ReportError(f"ItemGraph invariant {name} must be zero")

    events = document["events"]
    if not isinstance(events, list) or not events or len(events) > 512:
        raise ReportError("events must contain between 1 and 512 bounded replay rows")
    counts: dict[str, int] = {}
    seen_events: set[str] = set()
    for index, event in enumerate(events):
        if not isinstance(event, dict) or set(event) != EVENT_FIELDS:
            raise ReportError(f"events[{index}] contains fields outside the redacted replay schema")
        if type(event["sequence"]) is not int or event["sequence"] != index:
            raise ReportError("event sequence must be contiguous and start at zero")
        event_signature = json.dumps({key: value for key, value in event.items() if key != "sequence"},
                                     sort_keys=True, separators=(",", ":"))
        if event_signature in seen_events:
            raise ReportError(f"events[{index}] duplicates an earlier durable replay row")
        seen_events.add(event_signature)
        event_type = event["event_type"]
        if event_type not in SCENARIOS[expected_scenario]:
            raise ReportError(f"event type {event_type!r} is outside scenario {expected_scenario}")
        counts[event_type] = counts.get(event_type, 0) + 1
        if event["evidence_class"] not in EVIDENCE_CLASSES:
            raise ReportError(f"events[{index}].evidence_class is unsupported")
        if event["source_reliability"] not in RELIABILITIES:
            raise ReportError(f"events[{index}].source_reliability is unsupported")
        if event["quantity_semantics"] != "NONE":
            raise ReportError(f"events[{index}] claims item quantity for a world event")
        if event["outcome"] not in OUTCOMES:
            raise ReportError(f"events[{index}].outcome is unsupported")
        reason = event["reason_code"]
        if reason is not None and (not isinstance(reason, str) or not REASON_CODE.fullmatch(reason)):
            raise ReportError(f"events[{index}].reason_code is not a stable reason code")
        if event["evidence_class"] == "UNRESOLVED" and reason is None:
            raise ReportError(f"events[{index}] unresolved evidence has no reason code")
        if event["cause"] not in CAUSE_ALIASES:
            raise ReportError(f"events[{index}].cause is not a redacted world-event alias")
        if event["privacy_class"] != "SENSITIVE_LOCATION":
            raise ReportError(f"events[{index}] lost its sensitive-location privacy class")
        if not isinstance(event["dimension"], str) or not RESOURCE_ID.fullmatch(event["dimension"]):
            raise ReportError(f"events[{index}].dimension is not a resource identifier")
        position = event["position"]
        if (not isinstance(position, dict) or set(position) != {"x", "y", "z"}
                or any(type(position[axis]) is not int or abs(position[axis]) > 512 for axis in ("x", "y", "z"))):
            raise ReportError(f"events[{index}].position must be bounded template-relative coordinates")
        subject = event["subject_id"]
        if subject is not None and (not isinstance(subject, str) or not RESOURCE_ID.fullmatch(subject)):
            raise ReportError(f"events[{index}].subject_id is not a resource identifier")
        for key in ("before_state", "after_state"):
            value = event[key]
            if value is not None and (not isinstance(value, str) or not value or len(value) > 512):
                raise ReportError(f"events[{index}].{key} must be null or a bounded block-state description")
        cause_ref = event["cause_ref"]
        if cause_ref is not None and (not isinstance(cause_ref, str) or not CAUSE_REF.fullmatch(cause_ref)):
            raise ReportError(f"events[{index}].cause_ref must be a redacted cause alias")
        if UUID.search(json.dumps(event, sort_keys=True)):
            raise ReportError(f"events[{index}] leaks a runtime UUID")

    required = {
        "explosion": {"EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"},
        "piston": {"PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"},
        "environment": {"FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                         "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"},
    }[expected_scenario]
    if not required.issubset(counts):
        raise ReportError(f"scenario is missing required event families: {sorted(required - set(counts))}")
    if UUID.search(json.dumps(document, sort_keys=True)):
        raise ReportError("report leaks a runtime UUID")
    return document


def compare(input_dir: Path, output_dir: Path | None = None) -> int:
    normalized: dict[tuple[str, str], dict[str, Any]] = {}
    for scenario in SCENARIOS:
        pair = []
        for loader in sorted(LOADERS):
            path = input_dir / f"itemgraph-world-{loader}-{scenario}.json"
            if not path.is_file():
                raise ReportError(f"required {loader} {scenario} report is missing: {path}")
            report = validate(json.loads(path.read_text(encoding="utf-8")), loader, scenario)
            normalized[(loader, scenario)] = report
            pair.append(report)
        if scenario == "environment":
            random_families = {"FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE"}
            # Fire spread and Enderman goal selection consume loader/world RNG
            # differently. Compare their shared contract, while preserving the
            # full event multiset (including position and state) for scripted
            # fluid, falling-block, and unresolved paths.
            comparable = lambda event: tuple(event[key] for key in (
                "event_type", "evidence_class", "source_reliability", "quantity_semantics", "outcome",
                "reason_code", "cause", "privacy_class", "dimension"))
            random_contracts = [
                {comparable(event) for event in report["events"] if event["event_type"] in random_families}
                for report in pair
            ]
            if random_contracts[0] != random_contracts[1]:
                raise ReportError("Fabric and NeoForge random environment evidence contracts differ")
            deterministic_events = [
                Counter(json.dumps({key: value for key, value in event.items()
                                    if key not in {"sequence", "cause_ref"}},
                                   sort_keys=True, separators=(",", ":"))
                        for event in report["events"] if event["event_type"] not in random_families)
                for report in pair
            ]
            if deterministic_events[0] != deterministic_events[1]:
                raise ReportError("Fabric and NeoForge deterministic environment replay differs")
        elif pair[0]["events"] != pair[1]["events"]:
            raise ReportError(f"Fabric and NeoForge {scenario} replay evidence differs")
    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)
        for (loader, scenario), report in normalized.items():
            destination = output_dir / f"itemgraph-world-{loader}-{scenario}.json"
            temporary = destination.with_suffix(destination.suffix + ".tmp")
            temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            temporary.replace(destination)
    return sum(len(report["events"]) for report in normalized.values())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, type=Path,
                        help="directory containing three raw reports for each loader")
    parser.add_argument("--output-dir", type=Path, help="optional directory for normalized redacted reports")
    args = parser.parse_args(argv)
    try:
        count = compare(args.input_dir, args.output_dir)
    except (OSError, json.JSONDecodeError, ReportError) as exc:
        print(f"world-event replay comparison failed: {exc}", file=sys.stderr)
        return 2
    print(f"validated Fabric/NeoForge world-event replay and conservation reports ({count} total rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
