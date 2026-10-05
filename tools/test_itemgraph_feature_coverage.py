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
        join_events = []
        for index in range(3):
            player_join = copy.deepcopy(event)
            player_join.update({
                "event_key": f"replay-player_join-{index}",
                "sequence": index + 2,
                "action": "PLAYER_JOIN",
                "quantity": None,
                "item_id": None,
                "occurred_at_ms": 1790870400002 + index,
                "source_table": "ig_audit_events",
                "source_action_id": "PLAYER_JOIN",
                "compatibility_table": "sessions",
                "compatibility_action_id": 0,
            })
            join_events.append(player_join)
        chat_event = copy.deepcopy(event)
        chat_event.update({
            "event_key": "replay-chat_message-0",
            "sequence": 5,
            "action": "CHAT_MESSAGE",
            "quantity": None,
            "item_id": None,
            "occurred_at_ms": 1790870400005,
            "source_table": "ig_audit_events",
            "source_action_id": "CHAT_MESSAGE",
            "compatibility_table": "chats",
            "compatibility_action_id": None,
        })
        command_event = copy.deepcopy(chat_event)
        command_event.update({
            "event_key": "replay-command_attempt-0",
            "sequence": 6,
            "action": "COMMAND_ATTEMPT",
            "occurred_at_ms": 1790870400006,
            "source_action_id": "COMMAND_ATTEMPT",
            "compatibility_table": "commands",
        })
        craft_event = copy.deepcopy(event)
        craft_event.update({
            "event_key": "replay-craft-0",
            "sequence": 8,
            "action": "CRAFT",
            "item_id": "minecraft:book",
            "occurred_at_ms": 1790870400007,
            "source_table": "ig_item_transformations",
            "source_action_id": "CRAFT",
            "compatibility_table": "items",
            "compatibility_action_id": 4,
        })
        kill_event = copy.deepcopy(entity_event)
        kill_event.update({
            "event_key": "replay-kill_entity-0",
            "sequence": 7,
            "action": "KILL_ENTITY",
            "occurred_at_ms": 1790870400007,
            "source_action_id": "KILL_ENTITY",
            "compatibility_action_id": 3,
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
            "events": [event, entity_event, *join_events, chat_event, command_event, kill_event, craft_event],
            "invariants": {
                "healthy": True,
                "total_observations": 1,
                "total_edges": 0,
                "total_allocations": 0,
                "total_transformations": 1,
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
        craft = next(row for row in output["actions"] if row["action"] == "CRAFT")
        self.assertEqual("observed-in-replay", craft["coverage_status"])
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
        self.assertEqual(0, output["summary"]["release_table_families_not_observed_in_replay"])
        self.assertEqual("observed-in-replay", families["containers"]["coverage_status"])
        self.assertEqual("observed-in-replay", families["blocks"]["coverage_status"])
        self.assertEqual("observed-in-replay", families["sessions"]["coverage_status"])
        self.assertEqual("observed-in-replay", families["chats"]["coverage_status"])
        self.assertEqual("observed-in-replay", families["commands"]["coverage_status"])
        self.assertEqual("reference-data", families["usernames"]["table_kind"])
        self.assertIn("ig_player_name_history",
                      families["usernames"]["itemgraph_native_representation"])
        self.assertEqual("represented-in-replay", families["usernames"]["coverage_status"])
        self.assertEqual(3, families["usernames"]["runtime_evidence_count"])
        self.assertIn("not a username-history row count",
                      families["usernames"]["coverage_basis"])
        self.assertEqual("actor-reference-only", families["users"]["coverage_status"])
        self.assertEqual("represented-in-replay", families["entities"]["coverage_status"])
        self.assertEqual(4, output["summary"]["release_reference_tables_represented_in_replay"])
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
        player_join = copy.deepcopy(self.report["events"][0])
        player_join.update({"action": "PLAYER_JOIN", "source_table": "ig_audit_events"})
        self.assertTrue(coverage_module.reference_field_observed(player_join, "usernames"))

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

    def test_feature_and_durability_contract_rows_keep_release_and_source_evidence_separate(self) -> None:
        output = coverage_module.build_coverage(self.report)
        features = {row["owner_issue"]: row for row in output["feature_requirements"]}
        self.assertEqual({136, 137, 138, 140}, set(features))
        self.assertEqual("not-recorded-in-exact-release-fixture", features[136]["exact_release"]["status"])
        self.assertEqual("source-only", features[136]["pinned_source"]["status"])
        self.assertEqual("named-nodes-not-recorded-in-exact-release-fixture",
                         features[137]["exact_release"]["status"])
        self.assertEqual("rich-interactions-not-recorded-in-exact-release-fixture",
                         features[138]["exact_release"]["status"])
        self.assertEqual("writer-class-confirmed-details-unproven",
                         features[140]["exact_release"]["status"])
        self.assertEqual("source-only", features[140]["pinned_source"]["status"])
        self.assertTrue(all(row["acceptance_status"] == "unresolved"
                            for row in output["feature_requirements"] + output["durability_requirements"]))
        self.assertEqual(5, len(output["durability_requirements"]))
        self.assertTrue(all(row["exact_release"]["status"] == "not-established-by-exact-release-fixture"
                            for row in output["durability_requirements"]))
        self.assertTrue(all(row["pinned_source"]["status"] == "source-only-research"
                            for row in output["durability_requirements"]))
        self.assertEqual(4, output["summary"]["open_feature_requirements_unresolved"])
        self.assertEqual(5, output["summary"]["durability_requirements_unresolved"])

    def test_contract_validator_rejects_missing_duplicate_malformed_misclassified_and_private_rows(self) -> None:
        output = coverage_module.build_coverage(self.report)
        cases = []

        missing = copy.deepcopy(output)
        missing["feature_requirements"].pop()
        cases.append((missing, "missing rows"))

        duplicate = copy.deepcopy(output)
        duplicate["feature_requirements"].append(copy.deepcopy(duplicate["feature_requirements"][0]))
        cases.append((duplicate, "duplicate requirement IDs"))

        malformed = copy.deepcopy(output)
        malformed["durability_requirements"][0]["exact_release"] = "unknown"
        cases.append((malformed, "malformed, misclassified"))

        misclassified = copy.deepcopy(output)
        misclassified["feature_requirements"][0]["pinned_source"]["status"] = "exact-release"
        cases.append((misclassified, "malformed, misclassified"))

        private = copy.deepcopy(output)
        private["feature_requirements"][0]["player_uuid"] = "private-player-id"
        cases.append((private, "malformed, misclassified"))

        for candidate, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(differential.ReportError, message):
                    coverage_module.validate_coverage(candidate)

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
