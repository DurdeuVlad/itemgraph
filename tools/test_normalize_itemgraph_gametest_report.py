#!/usr/bin/env python3
"""Adversarial tests for native-only ItemGraph GameTest report normalization."""

from __future__ import annotations

import copy
import unittest

import normalize_itemgraph_gametest_report as normalizer
import itemgraph_differential_report as differential


def raw_event(action: str, *, sequence: int = 0, quantity: int = 2, item_id: str = "minecraft:dirt",
              source_table: str = "ig_observations", source_action_id: str | None = None,
              compatibility_table: str = "containers") -> dict:
    return {
        "event_key": f"replay-{action.lower()}",
        "sequence": sequence,
        "action": action,
        "evidence_class": "observed",
        "quantity": quantity,
        "item_id": item_id,
        "occurred_at_ms": 1790870400000 + sequence,
        "dimension": "minecraft:overworld",
        "position": {"x": 0, "y": 64, "z": 0},
        "actor_ref": "actor:replay-mover",
        "source_table": source_table,
        "source_action_id": source_action_id or action,
        "compatibility_table": compatibility_table,
        "privacy_class": "replay_fixture_only",
        "unresolved_reason": None,
    }


def expected_events() -> list[dict]:
    return [
        raw_event("ADD_ITEM", sequence=0, quantity=2, item_id="minecraft:dirt", compatibility_table="containers"),
        raw_event("REMOVE_ITEM", sequence=1, quantity=3, item_id="minecraft:cobblestone", compatibility_table="containers"),
        raw_event("DROP_ITEM", sequence=2, quantity=4, item_id="minecraft:diamond", compatibility_table="items"),
        raw_event("PICKUP_ITEM", sequence=3, quantity=4, item_id="minecraft:diamond", compatibility_table="items"),
        raw_event("THROW_ITEM", sequence=4, quantity=1, item_id="minecraft:snowball", compatibility_table="items"),
        raw_event("SHOOT_ITEM", sequence=5, quantity=1, item_id="minecraft:arrow", compatibility_table="items"),
    ]


class ItemGraphReplayNormalizerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.raw = {
            "raw_schema_version": 1,
            "loader": "neoforge",
            "scenario_id": "item-movement-projectile-replay",
            "seed": 0,
            "events": expected_events(),
        }

    def test_pins_profile_and_native_only_runtime(self) -> None:
        report = normalizer.normalize(self.raw, "neoforge")
        registry, fixture_hash = differential.current_profile()
        self.assertEqual(2, report["report_schema_version"])
        self.assertEqual("itemgraph", report["system"])
        self.assertEqual("native_only", report["runtime_mode"])
        self.assertEqual(registry["compatibility_version"], report["compatibility_version"])
        self.assertEqual(registry["source_profile"]["sha256"], report["source_profile_sha256"])
        self.assertEqual(fixture_hash, report["release_fixture_sha256"])

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

    def test_bad_loader_schema_and_source_identity_are_rejected(self) -> None:
        with self.assertRaisesRegex(differential.ReportError, "loader"):
            normalizer.normalize(self.raw, "fabric")
        malformed = copy.deepcopy(self.raw)
        malformed["events"] = []
        with self.assertRaisesRegex(differential.ReportError, "exactly the six"):
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
        malformed["events"][0]["action"] = "CHAT_MESSAGE"
        with self.assertRaisesRegex(differential.ReportError, "not part of the native replay fixture"):
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


if __name__ == "__main__":
    unittest.main()
