#!/usr/bin/env python3
"""Adversarial tests for the normalized replay comparator."""

from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
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
            "report_schema_version": 1,
            "compatibility_version": registry["compatibility_version"],
            "source_profile_sha256": registry["source_profile"]["sha256"],
            "release_fixture_sha256": fixture_hash,
            "loader": "neoforge",
            "scenario_id": "projectile.seeded.v1",
            "seed": 781,
        }
        self.legacy = {**base, "system": "grieflogger", "runtime_mode": "grieflogger_present", "events": [self.event()]}
        self.native = {**base, "system": "itemgraph", "runtime_mode": "native_only", "events": [self.event()]}

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
            "actor_ref": "actor:replay-alias-0",
            "source_table": "items",
            "source_action_id": 7,
            "privacy_class": "replay_fixture_only",
            "unresolved_reason": None,
        }
        event.update(overrides)
        return event

    def test_identical_replay_events_compare_equal(self) -> None:
        result = report.compare_reports(self.legacy, self.native)
        self.assertTrue(result["equivalent"])
        self.assertEqual([], result["differences"])
        self.assertEqual(self.registry["source_profile"]["sha256"], result["source_profile_sha256"])

    def test_missing_native_row_is_not_silently_accepted(self) -> None:
        self.native["events"] = []
        result = report.compare_reports(self.legacy, self.native)
        self.assertFalse(result["equivalent"])
        self.assertEqual("missing_native_event", result["differences"][0]["kind"])

    def test_duplicate_scenario_key_fails_validation(self) -> None:
        self.native["events"].append(self.event())
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
                       "grieflogger": 1, "itemgraph": 2}, result["differences"])

    def test_quantity_must_follow_profile_semantics(self) -> None:
        for action, source_action_id, quantity, expected in (
            ("THROW_ITEM", 7, None, "positive observed stack count"),
            ("PLACE_BLOCK", 1, 99, "must be null"),
            ("ADD_ITEM", 1, -4, "positive signed delta"),
            ("REMOVE_ITEM", 0, 4, "negative signed delta"),
            ("CRAFT", 4, None, "positive transformed result count"),
        ):
            with self.subTest(action=action, quantity=quantity):
                invalid = self.event(action=action, source_action_id=source_action_id, quantity=quantity)
                self.native["events"] = [invalid]
                with self.assertRaisesRegex(report.ReportError, expected):
                    report.compare_reports(self.legacy, self.native)

    def test_timestamp_inversion_is_reported_even_when_event_sets_match(self) -> None:
        second = self.event(event_key="arrow.shoot.1", action="SHOOT_ITEM", item_id="minecraft:arrow",
                            source_action_id=8, sequence=1, occurred_at_ms=1790870401000)
        self.legacy["events"].append(second)
        native_first = dict(second, occurred_at_ms=1790870400000, sequence=0)
        native_second = dict(self.event(sequence=1, occurred_at_ms=1790870401000))
        self.native["events"] = [native_first, native_second]
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
        self.native["events"] = [dict(event)]
        self.assertTrue(report.compare_reports(self.legacy, self.native)["equivalent"])
        self.native["events"][0]["unresolved_reason"] = None
        with self.assertRaisesRegex(report.ReportError, "must keep its unresolved_reason"):
            report.compare_reports(self.legacy, self.native)

    def test_unresolved_reason_mismatch_does_not_echo_reason_values(self) -> None:
        self.legacy["events"] = [self.event(evidence_class="unresolved", unresolved_reason="PLAYER_ALICE")]
        self.native["events"] = [self.event(evidence_class="unresolved", unresolved_reason="BASE_AT_X123")]
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
        for action, action_id, expected in (
            ("NOT_A_GRIEFLOGGER_ACTION", 999, "not mapped"),
            ("THROW_ITEM", 999, "does not match"),
        ):
            with self.subTest(action=action, action_id=action_id):
                self.native["events"] = [self.event(action=action, source_action_id=action_id)]
                with self.assertRaisesRegex(report.ReportError, expected):
                    report.compare_reports(self.legacy, self.native)

    def test_action_without_release_id_requires_null_source_action_id(self) -> None:
        event = self.event(action="CHAT_MESSAGE", source_table="chats", source_action_id=0,
                           quantity=None, item_id=None)
        self.native["events"] = [event]
        with self.assertRaisesRegex(report.ReportError, "must be null when the release has no action ID"):
            report.compare_reports(self.legacy, self.native)

    def test_opaque_unknown_action_is_retained_as_unresolved_without_quantity_claim(self) -> None:
        event = self.event(action=report.UNRESOLVED_SOURCE_ACTION, evidence_class="unresolved",
                           quantity=None, item_id=None, source_action_id=999,
                           unresolved_reason="UNKNOWN_SOURCE_ACTION_ID")
        self.legacy["events"] = [event]
        self.native["events"] = [dict(event)]
        result = report.compare_reports(self.legacy, self.native)
        self.assertTrue(result["equivalent"])
        self.assertEqual(1, result["event_counts"]["grieflogger"])

    def test_action_table_and_evidence_class_must_match_profile(self) -> None:
        for overrides, expected in (
            ({"source_table": "blocks"}, "table family"),
            ({"evidence_class": "inferred"}, "evidence class"),
        ):
            with self.subTest(overrides=overrides):
                self.native["events"] = [self.event(**overrides)]
                with self.assertRaisesRegex(report.ReportError, expected):
                    report.compare_reports(self.legacy, self.native)

    def test_boolean_cannot_impersonate_numeric_action_id(self) -> None:
        self.native["events"][0]["source_action_id"] = True
        with self.assertRaisesRegex(report.ReportError, "must be an integer"):
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
            self.assertEqual("quantity", document["differences"][0]["field"])

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
