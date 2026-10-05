#!/usr/bin/env python3
"""Adversarial tests for the normalized replay comparator."""

from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
import copy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("itemgraph_differential_report", ROOT / "tools" / "itemgraph_differential_report.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load differential comparator")
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class DifferentialReportTests(unittest.TestCase):
    def setUp(self) -> None:
        registry, fixture_hash = report.current_profile()
        self.registry = registry
        base = {
            "report_schema_version": report.REPORT_SCHEMA_VERSION,
            "compatibility_version": registry["compatibility_version"],
            "source_profile_sha256": registry["source_profile"]["sha256"],
            "release_fixture_sha256": fixture_hash,
            "loader": "neoforge",
            "scenario_id": "projectile.seeded.v1",
            "seed": 781,
        }
        self.legacy = {**base, "system": "grieflogger", "runtime_mode": "grieflogger_present", "events": [self.event()]}
        self.native = {**base, "system": "itemgraph", "runtime_mode": "native_only", "events": [self.native_event()],
                       "invariants": self.healthy_invariants()}

    @staticmethod
    def healthy_invariants():
        return {
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
        }

    @staticmethod
    def event(**overrides):
        event = {
            "event_key": "snowball.throw.0",
            "sequence": 0,
            "action": "THROW_ITEM",
            "evidence_class": "observed",
            "quantity": 1,
            "item_id": "minecraft:snowball",
            "occurred_at_ms": 1790870400000,
            "dimension": "minecraft:overworld",
            "position": {"x": 0, "y": 64, "z": 0},
            "subject_id": None,
            "actor_ref": "actor:replay-alias-0",
            "source_table": "items",
            "source_action_id": 7,
            "compatibility_table": "items",
            "compatibility_action_id": 7,
            "privacy_class": "replay_fixture_only",
            "unresolved_reason": None,
        }
        event.update(overrides)
        action_row = next((row for row in report.current_profile()[0]["actions"]
                           if row.get("itemgraph") == event["action"]), None)
        if action_row is not None:
            if "compatibility_table" not in overrides and event["compatibility_table"] not in report.ACTION_TABLES[event["action"]]:
                event["compatibility_table"] = sorted(report.ACTION_TABLES[event["action"]])[0]
            if "compatibility_action_id" not in overrides:
                event["compatibility_action_id"] = action_row.get("release_action_id")
            if "source_action_id" not in overrides:
                event["source_action_id"] = action_row.get("release_action_id")
        return event

    @staticmethod
    def native_event(**overrides):
        event = DifferentialReportTests.event()
        action = overrides.get("action", event["action"])
        event["source_table"] = "ig_observations"
        event["source_action_id"] = action
        action_row = next((row for row in report.current_profile()[0]["actions"]
                           if row.get("itemgraph") == action), None)
        if action_row is not None:
            event["compatibility_table"] = next(iter(report.ACTION_TABLES[action]))
            event["compatibility_action_id"] = action_row.get("release_action_id")
        event.update(overrides)
        return event

    def test_identical_replay_events_compare_equal(self) -> None:
        result = report.compare_reports(self.legacy, self.native)
        self.assertTrue(result["equivalent"])
        self.assertTrue(result["gate_passed"])
        self.assertEqual([], result["differences"])
        self.assertEqual(self.registry["source_profile"]["sha256"], result["source_profile_sha256"])
        self.assertEqual(self.healthy_invariants(), result["itemgraph_invariants"])

    def test_missing_native_row_is_not_silently_accepted(self) -> None:
        self.native["events"] = []
        result = report.compare_reports(self.legacy, self.native)
        self.assertFalse(result["equivalent"])
        self.assertEqual("missing_native_event", result["differences"][0]["kind"])

    def test_duplicate_scenario_key_fails_validation(self) -> None:
        self.native["events"].append(self.native_event())
        try:
            report.compare_reports(self.legacy, self.native)
        except report.ReportError as error:
            self.assertIn("duplicate event_key", str(error))
            self.assertNotIn("snowball.throw.0", str(error))
        else:
            self.fail("duplicate event key should fail validation")

    def test_quantity_mismatch_is_reported(self) -> None:
        self.native["events"][0]["quantity"] = 2
        result = report.compare_reports(self.legacy, self.native)
        self.assertIn({"sequence": 0, "itemgraph_sequence": 0, "kind": "field_mismatch", "field": "quantity",
                       "action": "THROW_ITEM", "grieflogger": 1, "itemgraph": 2,
                       "classification": "unexplained"}, result["differences"])
        self.assertFalse(result["gate_passed"])
        self.assertEqual(1, result["unexplained_difference_count"])

    def test_timestamp_mismatch_is_unexplained(self) -> None:
        self.native["events"][0]["occurred_at_ms"] += 1

        result = report.compare_reports(self.legacy, self.native)

        self.assertFalse(result["gate_passed"])
        self.assertEqual("field_mismatch", result["differences"][0]["kind"])
        self.assertEqual("occurred_at_ms", result["differences"][0]["field"])
        self.assertEqual("unexplained", result["differences"][0]["classification"])

    def test_unsupported_exception_issue_must_match_profile_evidence_issue(self) -> None:
        changed = copy.deepcopy(self.registry)
        action = next(row for row in changed["actions"] if row.get("itemgraph") == "INTERACT_ENTITY")
        action["evidence_issue"] = 76

        with self.assertRaisesRegex(report.ReportError, "must match its evidence_issue"):
            report._validate_differential_exceptions(changed)

    def test_extension_exception_issue_does_not_use_evidence_issue(self) -> None:
        changed = copy.deepcopy(self.registry)
        action = next(row for row in changed["actions"] if row.get("itemgraph") == "HOPPER_INSERT")
        action.pop("evidence_issue", None)

        report._validate_differential_exceptions(changed)

        action["differential_exceptions"][0]["issue"] = 57
        with self.assertRaisesRegex(report.ReportError, "pinned action owner"):
            report._validate_differential_exceptions(changed)

    def test_transformation_exception_issue_is_pinned_to_issue_57(self) -> None:
        changed = copy.deepcopy(self.registry)
        action = next(row for row in changed["actions"] if row.get("itemgraph") == "SMELT_OUTPUT_UNRESOLVED")
        action["differential_exceptions"][0]["issue"] = 34

        with self.assertRaisesRegex(report.ReportError, "pinned action owner"):
            report._validate_differential_exceptions(changed)

    def test_profile_linked_native_extension_stays_visible_and_passes_gate(self) -> None:
        hopper = self.native_event(event_key="hopper.insert.0", action="HOPPER_INSERT", quantity=1,
                                   item_id="minecraft:iron_ingot", source_action_id="HOPPER_INSERT", sequence=1)
        self.native["events"].append(hopper)
        self.native["invariants"]["total_observations"] = 2

        result = report.compare_reports(self.legacy, self.native)

        self.assertFalse(result["equivalent"])
        self.assertTrue(result["gate_passed"])
        self.assertEqual(1, result["expected_difference_count"])
        self.assertEqual(0, result["unexplained_difference_count"])
        self.assertEqual({"kind": "unexpected_native_event", "action": "HOPPER_INSERT",
                          "classification": "issue_linked_expected", "issue": 34,
                          "issue_url": "https://github.com/DurdeuVlad/itemgraph/issues/34",
                          "reason_code": "NATIVE_HOPPER_INSERT_EXTENSION"},
                         {key: result["differences"][0][key] for key in (
                             "kind", "action", "classification", "issue", "issue_url", "reason_code")})

    def test_issue_linked_extension_with_wrong_source_table_fails_gate(self) -> None:
        hopper = self.native_event(event_key="hopper.insert.0", action="HOPPER_INSERT", quantity=1,
                                   item_id="minecraft:iron_ingot", source_action_id="HOPPER_INSERT",
                                   source_table="ig_item_transformations", sequence=1)
        self.native["events"].append(hopper)
        self.native["invariants"]["total_observations"] = 2

        result = report.compare_reports(self.legacy, self.native)

        self.assertFalse(result["gate_passed"])
        self.assertEqual("unexplained", result["differences"][0]["classification"])
        self.assertNotIn("issue", result["differences"][0])

    def test_every_profile_exception_is_an_issue_linked_unmatched_native_event(self) -> None:
        native_events = [self.native_event()]
        for action_row in self.registry["actions"]:
            exceptions = action_row.get("differential_exceptions", [])
            if not exceptions:
                continue
            action = action_row["itemgraph"]
            semantics = action_row["quantity"]
            quantity = (report.SIGNED_DELTA_SIGNS[action] * 2 if semantics == "signed_delta" else
                        2 if semantics in {"transformation", "observed_stack_count"} else None)
            source_table = ("ig_audit_events" if semantics == "none" or action == "SMELT_OUTPUT_UNRESOLVED" else
                            "ig_item_transformations" if semantics == "transformation" else "ig_observations")
            native_events.append(self.native_event(
                event_key=f"extension.{action.lower()}", action=action,
                evidence_class=action_row["evidence_class"], quantity=quantity,
                item_id=None if semantics == "none" else "minecraft:stone",
                source_table=source_table, source_action_id=action, sequence=len(native_events),
                unresolved_reason="NO_WRITER_IN_TARGET_RELEASE" if action_row["evidence_class"] == "unresolved" else None))
        self.native["events"] = native_events
        self.native["invariants"]["total_observations"] = sum(
            event["source_table"] == "ig_observations" for event in native_events)

        result = report.compare_reports(self.legacy, self.native)

        expected_count = sum(bool(row.get("differential_exceptions")) for row in self.registry["actions"])
        self.assertFalse(result["equivalent"])
        self.assertTrue(result["gate_passed"])
        self.assertEqual(expected_count, result["expected_difference_count"])
        self.assertEqual(0, result["unexplained_difference_count"])
        self.assertEqual(expected_count, len(result["differences"]))
        self.assertTrue(all(diff["classification"] == "issue_linked_expected"
                            and diff["issue_url"].startswith("https://github.com/DurdeuVlad/itemgraph/issues/")
                            for diff in result["differences"]))

    def test_quantity_must_follow_profile_semantics(self) -> None:
        for action, source_action_id, quantity, expected in (
            ("THROW_ITEM", 7, None, "positive observed stack count"),
            ("PLACE_BLOCK", 1, 99, "must be null"),
            ("ADD_ITEM", 1, -4, "positive signed delta"),
            ("REMOVE_ITEM", 0, 4, "negative signed delta"),
            ("CRAFT_OUTPUT_UNRESOLVED", 4, None, "positive observed stack count"),
        ):
            with self.subTest(action=action, quantity=quantity):
                invalid = self.native_event(action=action, quantity=quantity)
                if action == "CRAFT_OUTPUT_UNRESOLVED":
                    invalid["evidence_class"] = "unresolved"
                    invalid["unresolved_reason"] = "TRANSFORMATION_INPUTS_NOT_OBSERVED"
                self.native["events"] = [invalid]
                with self.assertRaisesRegex(report.ReportError, expected):
                    report.compare_reports(self.legacy, self.native)

    def test_timestamp_inversion_is_reported_even_when_event_sets_match(self) -> None:
        second = self.event(event_key="arrow.shoot.1", action="SHOOT_ITEM", item_id="minecraft:arrow",
                            source_action_id=8, sequence=1, occurred_at_ms=1790870401000)
        self.legacy["events"].append(second)
        native_first = self.native_event(event_key=second["event_key"], action="SHOOT_ITEM",
                                         item_id="minecraft:arrow", sequence=0,
                                         occurred_at_ms=1790870400000)
        native_second = self.native_event(sequence=1, occurred_at_ms=1790870401000)
        self.native["events"] = [native_first, native_second]
        self.native["invariants"]["total_observations"] = 2
        result = report.compare_reports(self.legacy, self.native)
        self.assertTrue(any(d["kind"] == "temporal_order_mismatch" for d in result["differences"]))

    def test_each_input_rejects_a_timestamp_that_runs_backwards(self) -> None:
        self.legacy["events"].append(self.event(event_key="later", sequence=1,
                                                 occurred_at_ms=1790870399000))
        with self.assertRaisesRegex(report.ReportError, "not chronological"):
            report.compare_reports(self.legacy, self.native)

    def test_unresolved_evidence_requires_reason_and_is_retained(self) -> None:
        event = self.event(evidence_class="unresolved", unresolved_reason="OPAQUE_SOURCE_PAYLOAD")
        self.legacy["events"] = [event]
        self.native["events"] = [self.native_event(evidence_class="unresolved",
                                                    unresolved_reason="OPAQUE_SOURCE_PAYLOAD")]
        self.assertTrue(report.compare_reports(self.legacy, self.native)["equivalent"])
        self.native["events"][0]["unresolved_reason"] = None
        with self.assertRaisesRegex(report.ReportError, "must keep its unresolved_reason"):
            report.compare_reports(self.legacy, self.native)

    def test_unresolved_reason_mismatch_does_not_echo_reason_values(self) -> None:
        self.legacy["events"] = [self.event(evidence_class="unresolved", unresolved_reason="PLAYER_ALICE")]
        self.native["events"] = [self.native_event(evidence_class="unresolved", unresolved_reason="BASE_AT_X123")]
        result = report.compare_reports(self.legacy, self.native)
        rendered = json.dumps(result)
        self.assertIn('"field": "unresolved_reason"', rendered)
        self.assertNotIn("PLAYER_ALICE", rendered)
        self.assertNotIn("BASE_AT_X123", rendered)

    def test_profile_drift_fails_before_comparison(self) -> None:
        self.native["source_profile_sha256"] = "0" * 64
        with self.assertRaisesRegex(report.ReportError, "source_profile_sha256"):
            report.compare_reports(self.legacy, self.native)

    def test_runtime_mode_must_match_source_system(self) -> None:
        self.native["runtime_mode"] = "grieflogger_present"
        with self.assertRaisesRegex(report.ReportError, "must declare runtime_mode=native_only"):
            report.compare_reports(self.legacy, self.native)

    def test_unknown_event_field_is_rejected(self) -> None:
        self.native["events"][0]["player_uuid"] = "must-not-leak"
        with self.assertRaisesRegex(report.ReportError, "fields do not match the report schema") as raised:
            report.compare_reports(self.legacy, self.native)
        self.assertNotIn("player_uuid", str(raised.exception))

    def test_unknown_top_level_field_is_rejected_without_echoing_name(self) -> None:
        self.native["player_name"] = "Alice"
        with self.assertRaisesRegex(report.ReportError, "fields do not match the report schema") as raised:
            report.compare_reports(self.legacy, self.native)
        self.assertNotIn("player_name", str(raised.exception))

    def test_nonzero_whole_graph_quantity_or_integrity_audit_fails_closed(self) -> None:
        for field in ("over_allocated_observations", "invalid_edge_allocations", "invalid_edge_temporal", "non_positive_quantities", "orphaned_allocations",
                      "invalid_edge_nodes", "status_mismatches"):
            with self.subTest(field=field):
                self.native["invariants"][field] = 1
                self.native["invariants"]["healthy"] = False
                with self.assertRaisesRegex(report.ReportError, "violations must be zero"):
                    report.compare_reports(self.legacy, self.native)
                self.native["invariants"] = self.healthy_invariants()

    def test_itemgraph_report_requires_audit_and_rejects_inconsistent_summary(self) -> None:
        del self.native["invariants"]
        with self.assertRaisesRegex(report.ReportError, "fields do not match the report schema"):
            report.compare_reports(self.legacy, self.native)
        self.native["invariants"] = self.healthy_invariants()
        self.native["invariants"]["healthy"] = False
        with self.assertRaisesRegex(report.ReportError, "disagrees"):
            report.compare_reports(self.legacy, self.native)

    def test_itemgraph_report_rejects_queue_overflow_and_capacity_drift(self) -> None:
        for path, value, message in (
            (("observation_waiting_depth",), 10_001, "exceeds its configured capacity"),
            (("queue_capacity_each",), 10_001, "must match the configured capacity"),
            (("dropped_since_service_start",), 1, "must not lose accepted events"),
        ):
            with self.subTest(path=path):
                self.native["invariants"]["queue_health"][path[0]] = value
                with self.assertRaisesRegex(report.ReportError, message):
                    report.compare_reports(self.legacy, self.native)
                self.native["invariants"] = self.healthy_invariants()

    def test_itemgraph_observation_total_covers_exported_observation_events(self) -> None:
        self.native["invariants"]["total_observations"] = 0
        with self.assertRaisesRegex(report.ReportError, "smaller than its exported observation events"):
            report.compare_reports(self.legacy, self.native)

    def test_raw_uuid_is_rejected_as_actor_identity(self) -> None:
        self.native["events"][0]["actor_ref"] = "actor:replay-123e4567-e89b-12d3-a456-426614174000"
        with self.assertRaisesRegex(report.ReportError, "replay-local opaque actor alias"):
            report.compare_reports(self.legacy, self.native)

    def test_actor_aliases_are_never_copied_to_output(self) -> None:
        self.native["events"][0]["actor_ref"] = "actor:replay-secret-alias"
        result = report.compare_reports(self.legacy, self.native)
        rendered = json.dumps(result)
        self.assertNotIn("replay-secret-alias", rendered)
        self.assertNotIn("actor:replay-alias-0", rendered)

    def test_coordinate_mismatch_is_reported_without_revealing_coordinates(self) -> None:
        self.native["events"][0]["position"] = {"x": 9000, "y": 64, "z": -12000}
        result = report.compare_reports(self.legacy, self.native)
        rendered = json.dumps(result)
        self.assertIn('"field": "position"', rendered)
        self.assertNotIn("9000", rendered)
        self.assertNotIn("-12000", rendered)

    def test_unknown_action_and_action_id_pair_are_rejected(self) -> None:
        for action, expected in (
            ("NOT_A_GRIEFLOGGER_ACTION", "not mapped"),
            ("THROW_ITEM", "compatibility_action_id"),
        ):
            with self.subTest(action=action):
                event = self.native_event(action=action)
                if action == "NOT_A_GRIEFLOGGER_ACTION":
                    event["source_action_id"] = action
                else:
                    event["compatibility_action_id"] = 999
                self.native["events"] = [event]
                with self.assertRaisesRegex(report.ReportError, expected):
                    report.compare_reports(self.legacy, self.native)

    def test_action_without_release_id_keeps_native_and_compatibility_identity_distinct(self) -> None:
        event = self.native_event(action="CHAT_MESSAGE", quantity=None, item_id=None)
        self.native["events"] = [event]
        self.assertEqual(self.native, report.validate_report(self.native, "itemgraph"))

    def test_opaque_unknown_action_is_retained_as_unresolved_without_quantity_claim(self) -> None:
        legacy_event = self.event(action=report.UNRESOLVED_SOURCE_ACTION, evidence_class="unresolved",
                                  quantity=None, item_id=None, source_action_id=999,
                                  compatibility_table="items", compatibility_action_id=None,
                                  unresolved_reason="UNKNOWN_SOURCE_ACTION_ID")
        native_event = self.native_event(action=report.UNRESOLVED_SOURCE_ACTION, evidence_class="unresolved",
                                         quantity=None, item_id=None, source_action_id="UNKNOWN_SOURCE_ACTION_ID",
                                         compatibility_table="items", compatibility_action_id=None,
                                         unresolved_reason="UNKNOWN_SOURCE_ACTION_ID")
        self.legacy["events"] = [legacy_event]
        self.native["events"] = [native_event]
        result = report.compare_reports(self.legacy, self.native)
        self.assertTrue(result["equivalent"])
        self.assertEqual(1, result["event_counts"]["grieflogger"])

    def test_action_table_and_evidence_class_must_match_profile(self) -> None:
        for overrides, expected in (
            ({"compatibility_table": "blocks"}, "table family"),
            ({"evidence_class": "inferred"}, "evidence class"),
        ):
            with self.subTest(overrides=overrides):
                self.native["events"] = [self.native_event(**overrides)]
                with self.assertRaisesRegex(report.ReportError, expected):
                    report.compare_reports(self.legacy, self.native)

    def test_boolean_cannot_impersonate_numeric_action_id(self) -> None:
        self.native["events"][0]["compatibility_action_id"] = True
        with self.assertRaisesRegex(report.ReportError, "must be an integer"):
            report.compare_reports(self.legacy, self.native)

    def test_boolean_cannot_impersonate_grieflogger_action_id_one(self) -> None:
        self.legacy["events"] = [self.event(action="ADD_ITEM", quantity=2,
                                             compatibility_table="items", source_action_id=True)]
        with self.assertRaisesRegex(report.ReportError, "source_action_id must be an integer"):
            report.compare_reports(self.legacy, self.native)

    def test_event_key_and_scenario_ids_are_hashed_in_diff_output(self) -> None:
        self.native["events"][0]["event_key"] = "alice-123e4567-e89b-12d3-a456-426614174000"
        self.native["events"][0]["quantity"] = 2
        result = report.compare_reports(self.legacy, self.native)
        rendered = json.dumps(result)
        self.assertNotIn("alice-123e4567-e89b-12d3-a456-426614174000", rendered)
        self.assertNotIn("123e4567-e89b-12d3-a456-426614174000", rendered)
        self.assertEqual(0, result["differences"][0]["sequence"])
        self.assertNotIn("event_key", result["differences"][0])
        self.assertEqual(24, len(result["scenario_id"]))

    def test_unknown_privacy_class_is_rejected(self) -> None:
        self.native["events"][0]["privacy_class"] = "player@example.org"
        with self.assertRaisesRegex(report.ReportError, "privacy_class must be one of"):
            report.compare_reports(self.legacy, self.native)

    def test_unresolved_reason_must_be_a_safe_reason_code(self) -> None:
        self.native["events"][0]["unresolved_reason"] = "payload contained secret@example.org"
        with self.assertRaisesRegex(report.ReportError, "stable uppercase reason code"):
            report.compare_reports(self.legacy, self.native)

    def test_cli_writes_a_profile_keyed_success_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            legacy_path, native_path, output_path = (directory / "legacy.json", directory / "native.json",
                                                     directory / "diff.json")
            legacy_path.write_text(json.dumps(self.legacy), encoding="utf-8")
            native_path.write_text(json.dumps(self.native), encoding="utf-8")
            result = report.main(["--grieflogger", str(legacy_path), "--itemgraph", str(native_path),
                                  "--output", str(output_path)])
            self.assertEqual(0, result)
            document = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertTrue(document["equivalent"])
            self.assertTrue(document["gate_passed"])
            self.assertEqual(24, len(document["scenario_id"]))

    def test_cli_mismatch_exits_nonzero_and_writes_diff(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.native["events"][0]["quantity"] = 2
            legacy_path, native_path, output_path = (directory / "legacy.json", directory / "native.json",
                                                     directory / "diff.json")
            legacy_path.write_text(json.dumps(self.legacy), encoding="utf-8")
            native_path.write_text(json.dumps(self.native), encoding="utf-8")
            result = report.main(["--grieflogger", str(legacy_path), "--itemgraph", str(native_path),
                                  "--output", str(output_path)])
            self.assertEqual(1, result)
            document = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertFalse(document["equivalent"])
            self.assertFalse(document["gate_passed"])
            self.assertEqual("quantity", document["differences"][0]["field"])

    def test_cli_issue_linked_difference_passes_without_claiming_equivalence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            legacy_path, native_path, output_path = (directory / "legacy.json", directory / "native.json",
                                                     directory / "diff.json")
            self.native["events"].append(self.native_event(event_key="hopper.insert.0", action="HOPPER_INSERT",
                                                            quantity=1, item_id="minecraft:iron_ingot",
                                                            source_action_id="HOPPER_INSERT", sequence=1))
            self.native["invariants"]["total_observations"] = 2
            legacy_path.write_text(json.dumps(self.legacy), encoding="utf-8")
            native_path.write_text(json.dumps(self.native), encoding="utf-8")
            result = report.main(["--grieflogger", str(legacy_path), "--itemgraph", str(native_path),
                                  "--output", str(output_path)])
            document = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(0, result)
            self.assertFalse(document["equivalent"])
            self.assertTrue(document["gate_passed"])

    def test_cli_validate_only_pins_native_loader_without_claiming_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "native.json"
            path.write_text(json.dumps(self.native), encoding="utf-8")
            result = report.main(["--validate-only", str(path), "--expected-system", "itemgraph",
                                  "--expected-loader", "neoforge"])
            self.assertEqual(0, result)
            wrong_loader = report.main(["--validate-only", str(path), "--expected-system", "itemgraph",
                                        "--expected-loader", "fabric"])
            self.assertEqual(2, wrong_loader)

    def test_cli_cannot_overwrite_an_input_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(self.legacy), encoding="utf-8")
            original = path.read_text(encoding="utf-8")
            result = report.main(["--grieflogger", str(path), "--itemgraph", str(path), "--output", str(path)])
            self.assertEqual(2, result)
            self.assertEqual(original, path.read_text(encoding="utf-8"))

    def test_cli_rejects_hard_linked_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source, linked_output, native = (directory / "legacy.json", directory / "linked.json",
                                             directory / "native.json")
            source.write_text(json.dumps(self.legacy), encoding="utf-8")
            native.write_text(json.dumps(self.native), encoding="utf-8")
            try:
                os.link(source, linked_output)
            except OSError as error:
                self.skipTest(f"hard links unavailable: {error}")
            original = source.read_text(encoding="utf-8")
            result = report.main(["--grieflogger", str(source), "--itemgraph", str(native),
                                  "--output", str(linked_output)])
            self.assertEqual(2, result)
            self.assertEqual(original, source.read_text(encoding="utf-8"))
            self.assertEqual(original, linked_output.read_text(encoding="utf-8"))

    def test_json_duplicate_keys_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "duplicate.json"
            path.write_text('{"events": [], "events": []}', encoding="utf-8")
            with self.assertRaisesRegex(report.ReportError, "duplicate JSON object key") as raised:
                report.read_json(path)
            self.assertNotIn('"events"', str(raised.exception))


if __name__ == "__main__":
    unittest.main()
