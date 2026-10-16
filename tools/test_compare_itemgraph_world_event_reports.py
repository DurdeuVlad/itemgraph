#!/usr/bin/env python3
"""Regression tests for redacted cross-loader world-event replay reports."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("compare_itemgraph_world_event_reports.py")
SPEC = importlib.util.spec_from_file_location("compare_itemgraph_world_event_reports", SCRIPT)
assert SPEC and SPEC.loader
world_reports = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(world_reports)


def event(sequence: int, event_type: str) -> dict:
    unresolved = event_type == "WORLD_EFFECT_UNRESOLVED"
    cause = {
        "EXPLOSION_BLOCK_CHANGE": "explosion_destroy",
        "PISTON_BLOCK_MOVE": "piston_extend",
        "PISTON_BLOCK_ATTEMPT": "piston_extend",
        "FLUID_BLOCK_CHANGE": "fluid_spread",
        "FIRE_BLOCK_CHANGE": "fire_tick_set",
        "ENDERMAN_BLOCK_MOVE": "enderman_take",
        "FALLING_BLOCK_CHANGE": "falling_land",
        "WORLD_EFFECT_UNRESOLVED": "fluid",
    }[event_type]
    return {
        "sequence": sequence,
        "event_type": event_type,
        "evidence_class": "UNRESOLVED" if unresolved else "OBSERVED",
        "source_reliability": "UNRESOLVED_CAUSE" if unresolved else "DIRECT_STATE_DELTA",
        "quantity_semantics": "NONE",
        "outcome": "UNRESOLVED" if unresolved else "CONFIRMED_CHANGE",
        "reason_code": "WORLD_EFFECT_PARTIAL" if unresolved else None,
        "cause": cause,
        "privacy_class": "SENSITIVE_LOCATION",
        "dimension": "minecraft:overworld",
        "position": {"x": sequence, "y": 1, "z": 2},
        "subject_id": "minecraft:stone",
        "before_state": "minecraft:stone" if not unresolved else None,
        "after_state": "minecraft:air" if not unresolved else None,
        "cause_ref": "cause-0",
    }


def report(loader: str, scenario: str, types: list[str]) -> dict:
    return {
        "schema_version": 1,
        "loader": loader,
        "scenario": scenario,
        "invariants": {
            "healthy": True,
            "over_allocated_observations": 0,
            "invalid_edge_allocations": 0,
            "invalid_edge_temporal": 0,
            "non_positive_quantities": 0,
            "orphaned_allocations": 0,
            "invalid_edge_nodes": 0,
            "status_mismatches": 0,
        },
        "events": [event(index, event_type) for index, event_type in enumerate(types)],
    }


class WorldEventReportTests(unittest.TestCase):
    def test_cross_loader_reports_validate_and_write_sanitized_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as source, tempfile.TemporaryDirectory() as output:
            source_dir = Path(source)
            expected = {
                "explosion": ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
                "piston": ["PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"],
                "environment": ["FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                                "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
            }
            for scenario, types in expected.items():
                for loader in ("fabric", "neoforge"):
                    (source_dir / f"itemgraph-world-{loader}-{scenario}.json").write_text(
                        json.dumps(report(loader, scenario, types)), encoding="utf-8")
            count = world_reports.compare(source_dir, Path(output))
            self.assertEqual(20, count)
            self.assertEqual(6, len(list(Path(output).glob("*.json"))))

    def test_rejects_quantity_claim(self) -> None:
        candidate = report("fabric", "explosion", ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"])
        candidate["events"][0]["quantity_semantics"] = "SIGNED_DELTA"
        with self.assertRaisesRegex(world_reports.ReportError, "claims item quantity"):
            world_reports.validate(candidate, "fabric", "explosion")

    def test_rejects_unhealthy_conservation_report(self) -> None:
        candidate = report("fabric", "piston", ["PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT",
                                                   "WORLD_EFFECT_UNRESOLVED"])
        candidate["invariants"]["over_allocated_observations"] = 1
        with self.assertRaisesRegex(world_reports.ReportError, "must be zero"):
            world_reports.validate(candidate, "fabric", "piston")

    def test_rejects_missing_unresolved_path(self) -> None:
        candidate = report("fabric", "environment", ["FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE",
                                                        "ENDERMAN_BLOCK_MOVE", "FALLING_BLOCK_CHANGE"])
        with self.assertRaisesRegex(world_reports.ReportError, "missing required event families"):
            world_reports.validate(candidate, "fabric", "environment")

    def test_rejects_uuid_leak(self) -> None:
        candidate = report("fabric", "explosion", ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"])
        candidate["events"][0]["before_state"] = "entity 00000000-0000-0000-0000-000000000001"
        with self.assertRaisesRegex(world_reports.ReportError, "runtime UUID"):
            world_reports.validate(candidate, "fabric", "explosion")

    def test_rejects_duplicate_durable_event_row(self) -> None:
        candidate = report("fabric", "explosion", ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"])
        duplicate = dict(candidate["events"][0], sequence=2)
        candidate["events"].append(duplicate)
        with self.assertRaisesRegex(world_reports.ReportError, "duplicates an earlier durable replay row"):
            world_reports.validate(candidate, "fabric", "explosion")

    def test_rejects_unredacted_cause(self) -> None:
        candidate = report("fabric", "explosion", ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"])
        candidate["events"][0]["cause"] = "FireBlock.tick.setBlock"
        with self.assertRaisesRegex(world_reports.ReportError, "redacted world-event alias"):
            world_reports.validate(candidate, "fabric", "explosion")

    def test_rejects_cross_loader_event_order_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as source:
            directory = Path(source)
            scenarios = {
                "explosion": ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
                "piston": ["PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"],
                "environment": ["FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                                "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
            }
            for scenario, types in scenarios.items():
                for loader in ("fabric", "neoforge"):
                    current = list(types)
                    if scenario == "explosion" and loader == "neoforge":
                        current.reverse()
                    (directory / f"itemgraph-world-{loader}-{scenario}.json").write_text(
                        json.dumps(report(loader, scenario, current)), encoding="utf-8")
            with self.assertRaisesRegex(world_reports.ReportError, "replay evidence differs"):
                world_reports.compare(directory)

    def test_environmental_random_outcomes_compare_by_contract_not_location(self) -> None:
        with tempfile.TemporaryDirectory() as source:
            directory = Path(source)
            scenarios = {
                "explosion": ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
                "piston": ["PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"],
                "environment": ["FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                                "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
            }
            for scenario, types in scenarios.items():
                for loader in ("fabric", "neoforge"):
                    (directory / f"itemgraph-world-{loader}-{scenario}.json").write_text(
                        json.dumps(report(loader, scenario, types)), encoding="utf-8")
            neo_path = directory / "itemgraph-world-neoforge-environment.json"
            neo = json.loads(neo_path.read_text(encoding="utf-8"))
            extra = event(len(neo["events"]), "FIRE_BLOCK_CHANGE")
            extra["position"] = {"x": 99, "y": 5, "z": -12}
            neo["events"].append(extra)
            neo_path.write_text(json.dumps(neo), encoding="utf-8")
            self.assertGreater(world_reports.compare(directory), 0)

    def test_environmental_comparison_preserves_deterministic_multiplicity(self) -> None:
        with tempfile.TemporaryDirectory() as source:
            directory = Path(source)
            scenarios = {
                "explosion": ["EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
                "piston": ["PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"],
                "environment": ["FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                                "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"],
            }
            for scenario, types in scenarios.items():
                for loader in ("fabric", "neoforge"):
                    (directory / f"itemgraph-world-{loader}-{scenario}.json").write_text(
                        json.dumps(report(loader, scenario, types)), encoding="utf-8")
            neo_path = directory / "itemgraph-world-neoforge-environment.json"
            neo = json.loads(neo_path.read_text(encoding="utf-8"))
            fluid = next(item for item in neo["events"] if item["event_type"] == "FLUID_BLOCK_CHANGE")
            fluid["position"] = {"x": 77, "y": 1, "z": -3}
            neo_path.write_text(json.dumps(neo), encoding="utf-8")
            with self.assertRaisesRegex(world_reports.ReportError, "deterministic environment replay differs"):
                world_reports.compare(directory)


if __name__ == "__main__":
    unittest.main()
