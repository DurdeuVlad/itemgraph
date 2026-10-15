#!/usr/bin/env python3
"""Adversarial tests for native-only ItemGraph GameTest report normalization."""

from __future__ import annotations

import copy
import unittest

import normalize_itemgraph_gametest_report as normalizer
import itemgraph_differential_report as differential


def raw_event(action: str, *, sequence: int = 0, quantity: int = 2, item_id: str = "minecraft:dirt",
              source_table: str = "ig_observations", source_action_id: str | None = None,
              compatibility_table: str = "containers", subject_id: str | None = None,
              occurrence: int = 0, position: dict | None = None) -> dict:
    return {
        "event_key": f"replay-{action.lower()}-{occurrence}",
        "sequence": sequence,
        "action": action,
        "evidence_class": "observed",
        "quantity": quantity,
        "item_id": item_id,
        "occurred_at_ms": 1790870400000 + sequence,
        "dimension": "minecraft:overworld",
        "position": position or {"x": 0, "y": 64, "z": 0},
        "subject_id": subject_id,
        "actor_ref": "actor:replay-mover",
        "source_table": source_table,
        "source_action_id": source_action_id or action,
        "compatibility_table": compatibility_table,
        "privacy_class": "replay_fixture_only",
        "unresolved_reason": None,
    }


def expected_events() -> list[dict]:
    events = [
        raw_event("ADD_ITEM", sequence=0, quantity=2, item_id="minecraft:dirt", compatibility_table="containers"),
        raw_event("REMOVE_ITEM", sequence=1, quantity=3, item_id="minecraft:cobblestone", compatibility_table="containers"),
        raw_event("DROP_ITEM", sequence=2, quantity=4, item_id="minecraft:diamond", compatibility_table="items"),
        raw_event("PICKUP_ITEM", sequence=3, quantity=4, item_id="minecraft:diamond", compatibility_table="items"),
        raw_event("THROW_ITEM", sequence=4, quantity=1, item_id="minecraft:snowball", compatibility_table="items"),
        raw_event("SHOOT_ITEM", sequence=5, quantity=1, item_id="minecraft:arrow", compatibility_table="items"),
        raw_event("CONSUME_ITEM", sequence=6, quantity=1, item_id="minecraft:apple", compatibility_table="items"),
        raw_event("BREAK_ITEM", sequence=7, quantity=1, item_id="minecraft:wooden_sword", compatibility_table="items"),
    ]
    audit_actions = [
        ("PLAYER_JOIN", None), ("PLAYER_JOIN", None), ("PLAYER_JOIN", None),
        ("PLAYER_QUIT", None),
        ("BREAK_BLOCK", "minecraft:water"),
        ("PLACE_BLOCK", "minecraft:diamond_block"),
        ("INTERACT_BLOCK_ATTEMPT", "minecraft:chest"),
        ("KILL_ENTITY", "minecraft:cow"),
        ("INTERACT_ENTITY", "minecraft:cow"), ("INTERACT_ENTITY", "minecraft:armor_stand"),
        ("INTERACT_ENTITY", "minecraft:armor_stand"),
        ("INTERACT_ENTITY_COMPLETED", "minecraft:armor_stand"),
        ("INTERACT_ENTITY_COMPLETED", "minecraft:armor_stand"),
        ("INTERACT_ENTITY_UNRESOLVED", "minecraft:armor_stand"),
    ]
    occurrences: dict[str, int] = {}
    for action, subject_id in audit_actions:
        occurrence = occurrences.get(action, 0)
        occurrences[action] = occurrence + 1
        events.append(raw_event(action, sequence=len(events), quantity=None, item_id=None,
                                source_table="ig_audit_events",
                                compatibility_table={
                                    "PLAYER_JOIN": "sessions",
                                    "PLAYER_QUIT": "sessions",
                                    "CHAT_MESSAGE": "chats",
                                    "COMMAND_ATTEMPT": "commands",
                                }.get(action, "blocks"),
                                subject_id=subject_id, occurrence=occurrence,
                                position=normalizer.EXPECTED_BLOCK_AUDIT_POSITIONS.get(action)))
    for action, compatibility_table in (("CHAT_MESSAGE", "chats"), ("COMMAND_ATTEMPT", "commands")):
        occurrence = occurrences.get(action, 0)
        occurrences[action] = occurrence + 1
        events.append(raw_event(action, sequence=len(events), quantity=None, item_id=None,
                                source_table="ig_audit_events", compatibility_table=compatibility_table,
                                occurrence=occurrence))
    craft = raw_event("CRAFT_OUTPUT_UNRESOLVED", sequence=len(events), quantity=1,
                      item_id="minecraft:book", source_table="ig_audit_events",
                      compatibility_table="items", subject_id="minecraft:book")
    craft["evidence_class"] = "unresolved"
    craft["unresolved_reason"] = "TRANSFORMATION_INPUTS_NOT_OBSERVED"
    events.append(craft)
    return events


class ItemGraphReplayNormalizerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.raw = {
            "raw_schema_version": 5,
            "loader": "neoforge",
            "scenario_id": "item-movement-projectile-block-entity-audit-replay",
            "seed": 0,
            "events": expected_events(),
            "invariants": {
                "healthy": True,
                "total_observations": 8,
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

    def test_pins_profile_and_native_only_runtime(self) -> None:
        report = normalizer.normalize(self.raw, "neoforge")
        registry, fixture_hash = differential.current_profile()
        self.assertEqual(differential.REPORT_SCHEMA_VERSION, report["report_schema_version"])
        self.assertEqual("itemgraph", report["system"])
        self.assertEqual("native_only", report["runtime_mode"])
        self.assertEqual(registry["compatibility_version"], report["compatibility_version"])
        self.assertEqual(registry["source_profile"]["sha256"], report["source_profile_sha256"])
        self.assertEqual(fixture_hash, report["release_fixture_sha256"])
        self.assertEqual(self.raw["invariants"], report["invariants"])

    def test_signed_delta_actions_normalize_positive_persisted_counts(self) -> None:
        for action, expected in (("ADD_ITEM", 2), ("REMOVE_ITEM", -3)):
            with self.subTest(action=action):
                raw = copy.deepcopy(self.raw)
                report = normalizer.normalize(raw, "neoforge")
                event = next(event for event in report["events"] if event["action"] == action)
                self.assertEqual(expected, event["quantity"])
                self.assertEqual("ig_observations", event["source_table"])
                self.assertEqual(action, event["source_action_id"])

    def test_throw_and_shoot_keep_observed_positive_stack_counts(self) -> None:
        report = normalizer.normalize(self.raw, "neoforge")
        for action in ("THROW_ITEM", "SHOOT_ITEM"):
            with self.subTest(action=action):
                event = next(event for event in report["events"] if event["action"] == action)
                self.assertEqual(1, event["quantity"])

    def test_report_contains_only_the_real_bucket_pickup_break_event(self) -> None:
        report = normalizer.normalize(self.raw, "neoforge")
        break_events = [event for event in report["events"] if event["action"] == "BREAK_BLOCK"]
        self.assertEqual(25, len(report["events"]))
        self.assertEqual(1, len(break_events))
        self.assertEqual("minecraft:water", break_events[0]["subject_id"])

    def test_block_audit_pairs_and_positions_are_pinned(self) -> None:
        report = normalizer.normalize(self.raw, "neoforge")
        for action, subject_id in (("PLACE_BLOCK", "minecraft:diamond_block"),
                                   ("INTERACT_BLOCK_ATTEMPT", "minecraft:chest"),
                                   ("KILL_ENTITY", "minecraft:cow")):
            event = next(event for event in report["events"] if event["action"] == action)
            self.assertEqual(subject_id, event["subject_id"])
            self.assertIsNone(event["quantity"])
            self.assertIsNone(event["item_id"])
            self.assertEqual(normalizer.EXPECTED_BLOCK_AUDIT_POSITIONS[action], event["position"])

        swapped = copy.deepcopy(self.raw)
        placed = next(event for event in swapped["events"] if event["action"] == "PLACE_BLOCK")
        interacted = next(event for event in swapped["events"] if event["action"] == "INTERACT_BLOCK_ATTEMPT")
        placed["subject_id"], interacted["subject_id"] = interacted["subject_id"], placed["subject_id"]
        with self.assertRaisesRegex(differential.ReportError, "action/subject pairs"):
            normalizer.normalize(swapped, "neoforge")

        misplaced = copy.deepcopy(self.raw)
        placed = next(event for event in misplaced["events"] if event["action"] == "PLACE_BLOCK")
        placed["position"] = {"x": 0, "y": 64, "z": 0}
        with self.assertRaisesRegex(differential.ReportError, "position does not match"):
            normalizer.normalize(misplaced, "neoforge")

    def test_bad_loader_schema_and_source_identity_are_rejected(self) -> None:
        with self.assertRaisesRegex(differential.ReportError, "loader"):
            normalizer.normalize(self.raw, "fabric")
        malformed = copy.deepcopy(self.raw)
        malformed["events"] = []
        with self.assertRaisesRegex(differential.ReportError, "pinned native replay event count"):
            normalizer.normalize(malformed, "neoforge")
        malformed = copy.deepcopy(self.raw)
        malformed["events"][0]["raw_data"] = "must not be exported"
        with self.assertRaisesRegex(differential.ReportError, "fields do not match"):
            normalizer.normalize(malformed, "neoforge")
        malformed = copy.deepcopy(self.raw)
        malformed["events"][0]["source_table"] = "containers"
        with self.assertRaisesRegex(differential.ReportError, "ItemGraph raw source identity"):
            normalizer.normalize(malformed, "neoforge")

    def test_scenario_seed_and_action_set_are_pinned(self) -> None:
        malformed = copy.deepcopy(self.raw)
        malformed["scenario_id"] = "another-scenario"
        with self.assertRaisesRegex(differential.ReportError, "scenario_id"):
            normalizer.normalize(malformed, "neoforge")
        malformed = copy.deepcopy(self.raw)
        malformed["seed"] = True
        with self.assertRaisesRegex(differential.ReportError, "seed"):
            normalizer.normalize(malformed, "neoforge")
        malformed = copy.deepcopy(self.raw)
        malformed["events"][0]["action"] = "UNKNOWN_REPLAY_ACTION"
        with self.assertRaisesRegex(differential.ReportError, "not part of the native replay fixture"):
            normalizer.normalize(malformed, "neoforge")

    def test_audit_subject_is_preserved_and_invalid_resource_id_rejected(self) -> None:
        report = normalizer.normalize(self.raw, "neoforge")
        cow = next(event for event in report["events"] if event["action"] == "INTERACT_ENTITY")
        self.assertEqual("minecraft:cow", cow["subject_id"])
        malformed = copy.deepcopy(self.raw)
        next(event for event in malformed["events"] if event["action"] == "INTERACT_ENTITY")["subject_id"] = "private uuid"
        with self.assertRaisesRegex(differential.ReportError, "subject_id must be a namespaced resource ID"):
            normalizer.normalize(malformed, "neoforge")
        malformed = copy.deepcopy(self.raw)
        malformed["events"][0]["sequence"] = 1
        with self.assertRaisesRegex(differential.ReportError, "contiguous"):
            normalizer.normalize(malformed, "neoforge")

    def test_non_array_events_are_rejected_as_schema_errors(self) -> None:
        malformed = copy.deepcopy(self.raw)
        malformed["events"] = None
        with self.assertRaisesRegex(differential.ReportError, "events must be an array"):
            normalizer.normalize(malformed, "neoforge")

    def test_nonzero_whole_graph_conservation_or_integrity_count_fails(self) -> None:
        for field in ("over_allocated_observations", "invalid_edge_allocations", "invalid_edge_temporal", "non_positive_quantities", "orphaned_allocations",
                      "invalid_edge_nodes", "status_mismatches"):
            with self.subTest(field=field):
                malformed = copy.deepcopy(self.raw)
                malformed["invariants"][field] = 1
                malformed["invariants"]["healthy"] = False
                with self.assertRaisesRegex(differential.ReportError, "violations must be zero"):
                    normalizer.normalize(malformed, "neoforge")

    def test_boolean_counts_and_inconsistent_healthy_flag_are_rejected(self) -> None:
        malformed = copy.deepcopy(self.raw)
        malformed["invariants"]["total_edges"] = True
        with self.assertRaisesRegex(differential.ReportError, "non-negative integer"):
            normalizer.normalize(malformed, "neoforge")
        malformed = copy.deepcopy(self.raw)
        malformed["invariants"]["healthy"] = False
        with self.assertRaisesRegex(differential.ReportError, "disagrees"):
            normalizer.normalize(malformed, "neoforge")

    def test_replay_queue_overflow_and_over_capacity_depth_are_rejected(self) -> None:
        for field, value, message in (
            ("dropped_since_service_start", 1, "must not lose accepted events"),
            ("observation_waiting_depth", 10_001, "exceeds its configured capacity"),
            ("queue_capacity_each", 9_999, "must match the configured capacity"),
        ):
            with self.subTest(field=field):
                malformed = copy.deepcopy(self.raw)
                malformed["invariants"]["queue_health"][field] = value
                with self.assertRaisesRegex(differential.ReportError, message):
                    normalizer.normalize(malformed, "neoforge")


if __name__ == "__main__":
    unittest.main()
