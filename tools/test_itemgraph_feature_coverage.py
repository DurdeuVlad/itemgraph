#!/usr/bin/env python3
"""Tests for registry-wide native ItemGraph feature-coverage reporting."""

from __future__ import annotations

import copy
import importlib.util
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "itemgraph_differential_report", ROOT / "tools" / "itemgraph_differential_report.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load differential report validator")
differential = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(differential)
sys.modules[SPEC.name] = differential

COVERAGE_SPEC = importlib.util.spec_from_file_location(
    "itemgraph_feature_coverage", ROOT / "tools" / "itemgraph_feature_coverage.py")
if COVERAGE_SPEC is None or COVERAGE_SPEC.loader is None:
    raise RuntimeError("cannot load feature coverage report")
coverage_module = importlib.util.module_from_spec(COVERAGE_SPEC)
COVERAGE_SPEC.loader.exec_module(coverage_module)


class ItemGraphFeatureCoverageTests(unittest.TestCase):
    def setUp(self) -> None:
        registry, fixture_hash = differential.current_profile()
        event = {
            "event_key": "replay-add-item-0",
            "sequence": 0,
            "action": "ADD_ITEM",
            "evidence_class": "observed",
            "quantity": 2,
            "item_id": "minecraft:dirt",
            "occurred_at_ms": 1790870400000,
            "dimension": "minecraft:overworld",
            "position": {"x": 0, "y": 64, "z": 0},
            "subject_id": None,
            "actor_ref": "actor:replay-0",
            "source_table": "ig_observations",
            "source_action_id": "ADD_ITEM",
            "compatibility_table": "containers",
            "compatibility_action_id": 1,
            "privacy_class": "replay_fixture_only",
            "unresolved_reason": None,
        }
        entity_event = copy.deepcopy(event)
        entity_event.update({
            "event_key": "replay-interact-entity-0",
            "sequence": 1,
            "action": "INTERACT_ENTITY",
            "quantity": None,
            "item_id": None,
            "occurred_at_ms": 1790870400001,
            "subject_id": "minecraft:cow",
            "source_table": "ig_audit_events",
            "source_action_id": "INTERACT_ENTITY",
            "compatibility_table": "blocks",
            "compatibility_action_id": None,
        })
        self.report = {
            "report_schema_version": differential.REPORT_SCHEMA_VERSION,
            "compatibility_version": registry["compatibility_version"],
            "source_profile_sha256": registry["source_profile"]["sha256"],
            "release_fixture_sha256": fixture_hash,
            "loader": "neoforge",
            "system": "itemgraph",
            "runtime_mode": "native_only",
            "scenario_id": "coverage.fixture.v1",
            "seed": 0,
            "events": [event, entity_event],
            "invariants": {
                "healthy": True,
                "total_observations": 1,
                "total_edges": 0,
                "total_allocations": 0,
                "total_transformations": 0,
                "over_allocated_observations": 0,
                "invalid_edge_allocations": 0,
                "invalid_edge_temporal": 0,
                "non_positive_quantities": 0,
                "orphaned_allocations": 0,
                "invalid_edge_nodes": 0,
                "status_mismatches": 0,
                "queue_health": {
                    "observation_waiting_depth": 0,
                    "transformation_waiting_depth": 0,
                    "audit_waiting_depth": 0,
                    "queue_capacity_each": 10_000,
                    "dropped_since_service_start": 0,
                },
            },
        }

    def test_classifies_every_registry_action_and_pins_profile(self) -> None:
        output = coverage_module.build_coverage(self.report)
        registry, fixture_hash = differential.current_profile()
        self.assertEqual(len(registry["actions"]), len(output["actions"]))
        self.assertEqual(registry["compatibility_version"], output["compatibility_version"])
        self.assertEqual(fixture_hash, output["release_fixture_sha256"])
        self.assertEqual("native_only", output["runtime_mode"])
        add_item = next(row for row in output["actions"] if row["action"] == "ADD_ITEM")
        self.assertEqual("observed-in-replay", add_item["coverage_status"])
        self.assertEqual(1, add_item["runtime_evidence_count"])
        self.assertTrue(all(row["coverage_status"] in {"observed-in-replay", "not-observed-in-replay"}
                            for row in output["actions"]))
        json.dumps(output)

    def test_writer_disposition_is_independent_of_replay_coverage(self) -> None:
        output = coverage_module.build_coverage(self.report)
        entity = next(row for row in output["actions"] if row["action"] == "INTERACT_ENTITY")
        ender = next(row for row in output["actions"] if row["action"] == "ADD_ITEM_ENDER")
        place = next(row for row in output["actions"] if row["action"] == "PLACE_BLOCK")
        self.assertEqual("unsupported-no-writer", entity["release_writer_status"])
        self.assertEqual("observed-in-replay", entity["coverage_status"])
        self.assertEqual(1, entity["runtime_evidence_count"])
        self.assertEqual("unsupported-no-writer", ender["release_writer_status"])
        self.assertEqual("not-observed-in-replay", ender["coverage_status"])
        self.assertEqual("pinned-source-only-action", entity["source_contract"])
        self.assertEqual("exact-release-enum-without-writer", ender["source_contract"])
        self.assertTrue(entity["itemgraph_extension"])
        self.assertTrue(ender["itemgraph_extension"])
        self.assertEqual("not-observed-in-replay", place["coverage_status"])
        self.assertEqual(31, place["owner_issue"])

    def test_classifies_all_release_table_families_without_claiming_absent_rows(self) -> None:
        output = coverage_module.build_coverage(self.report)
        fixture = differential.read_json(differential.FIXTURE_PATH)
        families = {row["table"]: row for row in output["table_families"]}
        self.assertEqual(set(fixture["database"]["tables"]), set(families))
        self.assertEqual(11, output["summary"]["release_table_count"])
        self.assertEqual(6, output["summary"]["release_table_families_not_observed_in_replay"])
        self.assertEqual("observed-in-replay", families["containers"]["coverage_status"])
        self.assertEqual("observed-in-replay", families["blocks"]["coverage_status"])
        self.assertEqual("not-observed-in-replay", families["sessions"]["coverage_status"])
        self.assertEqual("reference-data", families["usernames"]["table_kind"])
        self.assertIn("no separate native username-history table",
                      families["usernames"]["itemgraph_native_representation"])
        self.assertEqual("actor-reference-only", families["users"]["coverage_status"])
        self.assertEqual("not-observed-in-replay", families["entities"]["coverage_status"])
        self.assertEqual(2, output["summary"]["release_reference_tables_represented_in_replay"])
        self.assertEqual(1, output["summary"]["release_reference_tables_actor_reference_only"])
        self.assertTrue(all(row["owner_issue"] == 31 for row in families.values()))

    def test_reference_signals_require_action_specific_entity_and_block_subjects(self) -> None:
        entity_interaction = self.report["events"][1]
        self.assertFalse(coverage_module.reference_field_observed(entity_interaction, "entities"))
        self.assertFalse(coverage_module.reference_field_observed(entity_interaction, "materials"))

        block_event = copy.deepcopy(self.report["events"][0])
        block_event.update({
            "action": "PLACE_BLOCK",
            "item_id": None,
            "subject_id": "minecraft:stone",
        })
        self.assertTrue(coverage_module.reference_field_observed(block_event, "materials"))
        block_event.update({"action": "KILL_ENTITY", "subject_id": "minecraft:zombie"})
        self.assertTrue(coverage_module.reference_field_observed(block_event, "entities"))

    def test_table_family_report_redacts_event_identity_and_values(self) -> None:
        output = coverage_module.build_coverage(self.report)
        serialized = json.dumps(output)
        for forbidden in ("replay-add-item-0", "actor:replay-0", "minecraft:overworld", "minecraft:dirt",
                          "minecraft:cow"):
            self.assertNotIn(forbidden, serialized)

    def test_separate_chat_and_command_tables_are_classified_without_action_ids(self) -> None:
        output = coverage_module.build_coverage(self.report)
        chat = next(row for row in output["actions"] if row["action"] == "CHAT_MESSAGE")
        command = next(row for row in output["actions"] if row["action"] == "COMMAND_ATTEMPT")
        self.assertEqual("separate-release-table", chat["source_contract"])
        self.assertEqual("separate-release-table", command["source_contract"])
        self.assertEqual(["chats"], chat["compatibility_table"])
        self.assertEqual(["commands"], command["compatibility_table"])

    def test_output_does_not_copy_event_identity_or_private_coordinates(self) -> None:
        output = coverage_module.build_coverage(self.report)
        serialized = str(output)
        for forbidden in ("replay-add-item-0", "actor:replay-0", "minecraft:overworld", "position"):
            self.assertNotIn(forbidden, serialized)
        self.assertIn("runtime_evidence_count", serialized)

    def test_rejects_grieflogger_report_and_non_native_runtime(self) -> None:
        wrong_system = copy.deepcopy(self.report)
        wrong_system["system"] = "grieflogger"
        wrong_system["runtime_mode"] = "grieflogger_present"
        with self.assertRaisesRegex(differential.ReportError, "system must be itemgraph"):
            coverage_module.build_coverage(wrong_system)
        wrong_mode = copy.deepcopy(self.report)
        wrong_mode["runtime_mode"] = "grieflogger_present"
        with self.assertRaisesRegex(differential.ReportError, "runtime_mode=native_only"):
            coverage_module.build_coverage(wrong_mode)


if __name__ == "__main__":
    unittest.main()
