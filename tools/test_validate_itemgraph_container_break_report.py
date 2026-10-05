#!/usr/bin/env python3
"""Regression tests for the redacted #140 conformance report validator."""

from __future__ import annotations

import unittest

from validate_itemgraph_container_break_report import ReportError, validate


def fixture_report(loader: str = "fabric") -> dict:
    events: list[dict] = []
    parent_refs = [f"{index:064x}" for index in range(1, 5)]
    slot_counts = (0, 27, 1, 1)
    positions = ((5, 1, 2), (3, 1, 2), (7, 1, 2), (9, 1, 2))
    next_event_id = 10
    for index, (parent_ref, slot_count, (x, y, z)) in enumerate(zip(parent_refs, slot_counts, positions)):
        events.append({
            "evidence_ref": parent_ref,
            "action": "CONTAINER_BREAK_COMPLETED",
            "evidence_class": "OBSERVED",
            "actor_ref": "player-1",
            "break_event_ref": f"{100_000 + index:064x}",
            "stack_slots": slot_count,
            "total_items": 0 if slot_count == 0 else (5 if slot_count == 1 and x == 9
                                                        else (3 if slot_count == 1 else 378)),
            "source": {"type": "CONTAINER", "dimension": "minecraft:overworld",
                       "position": {"x": x, "y": y, "z": z}},
            "destination": {"type": "UNKNOWN"},
        })
        for slot in range(slot_count):
            events.append({
                "evidence_ref": f"{next_event_id:064x}",
                "parent_ref": parent_ref,
                "action": "REMOVE_ITEM",
                "evidence_class": "OBSERVED",
                "actor_ref": "player-1",
                "source": {"type": "CONTAINER", "dimension": "minecraft:overworld",
                           "position": {"x": x, "y": y, "z": z}},
                "destination": {"type": "UNKNOWN"},
                "slot": slot,
                "quantity": slot + 1 if slot_count == 27 else (5 if x == 9 else (3 if slot_count == 1 else 1)),
                "item_id": "minecraft:paper",
                "fingerprint_ref": f"{next_event_id:064x}",
            })
            next_event_id += 1
        if slot_count > 0:
            events.append({
                "evidence_ref": f"{next_event_id:064x}",
                "parent_ref": parent_ref,
                "action": "CONTAINER_BREAK_UNRESOLVED",
                "evidence_class": "UNRESOLVED",
                "actor_ref": "player-1",
                "reason_code": "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED",
                "source": {"type": "CONTAINER", "dimension": "minecraft:overworld",
                            "position": {"x": x, "y": y, "z": z}},
                "destination": {"type": "UNKNOWN"},
            })
            next_event_id += 1
    events.append({
        "evidence_ref": f"{999:064x}",
        "action": "CONTAINER_BREAK_UNRESOLVED",
        "evidence_class": "UNRESOLVED",
        "actor_status": "UNKNOWN",
        "reason_code": "CONTAINER_BREAK_ACTOR_UNAVAILABLE",
        "source": {"type": "CONTAINER", "dimension": "minecraft:overworld",
                   "position": {"x": 13, "y": 1, "z": 2}},
        "destination": {"type": "UNKNOWN"},
    })
    return {
        "raw_schema_version": 1,
        "loader": loader,
        "scenario_id": "player-container-break-contents",
        "world_scope": "isolated_game_test",
        "events": events,
        "invariants": {
            "canceled_break_rows": 0,
            "slot_observations": 29,
            "source_quantity": 386,
            "destination_type": "UNKNOWN",
            "drop_links_fabricated": False,
            "queue_rejections": 0,
            "actor_unavailable_rows": 1,
        },
    }


class ContainerBreakReportValidatorTest(unittest.TestCase):
    def test_valid_fabric_and_neoforge_reports(self) -> None:
        for loader in ("fabric", "neoforge"):
            with self.subTest(loader=loader):
                result = validate(fixture_report(loader), loader)
                self.assertEqual(loader, result["loader"])
                self.assertEqual(37, len(result["events"]))

    def test_accepts_actor_unavailable_event_without_player_alias(self) -> None:
        report = fixture_report()
        normalized = validate(report, "fabric")
        self.assertEqual(37, len(normalized["events"]))

    def test_rejects_report_without_actor_unavailable_case(self) -> None:
        report = fixture_report()
        report["events"] = [event for event in report["events"] if "actor_status" not in event]
        with self.assertRaisesRegex(ReportError, "one actor-unavailable unresolved event"):
            validate(report, "fabric")

    def test_rejects_actor_unavailable_identity_or_wrong_status_and_reason(self) -> None:
        for key, value, message in (
            ("actor_ref", "player-1", "actor-unavailable event fields"),
            ("actor_status", "PLAYER", "UNKNOWN actor status"),
            ("reason_code", "OTHER", "stable unresolved reason"),
        ):
            with self.subTest(key=key):
                report = fixture_report()
                event = next(e for e in report["events"] if e.get("actor_status") == "UNKNOWN")
                event[key] = value
                with self.assertRaisesRegex(ReportError, message):
                    validate(report, "fabric")

    def test_rejects_missing_unresolved_outcome(self) -> None:
        report = fixture_report()
        report["events"] = [
            event for event in report["events"]
            if event["action"] != "CONTAINER_BREAK_UNRESOLVED"
        ]
        with self.assertRaisesRegex(ReportError, "each nonempty break"):
            validate(report, "fabric")

    def test_rejects_unresolved_drop_link_for_empty_break(self) -> None:
        report = fixture_report()
        empty_parent = next(event for event in report["events"]
                            if event["action"] == "CONTAINER_BREAK_COMPLETED" and event["stack_slots"] == 0)
        report["events"].append({
            "evidence_ref": f"{1000:064x}",
            "parent_ref": empty_parent["evidence_ref"],
            "action": "CONTAINER_BREAK_UNRESOLVED",
            "evidence_class": "UNRESOLVED",
            "actor_ref": "player-1",
            "reason_code": "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED",
            "source": empty_parent["source"],
            "destination": {"type": "UNKNOWN"},
        })
        with self.assertRaisesRegex(ReportError, "empty break must not"):
            validate(report, "fabric")

    def test_rejects_completed_event_without_standard_break_link(self) -> None:
        report = fixture_report()
        next(event for event in report["events"]
             if event["action"] == "CONTAINER_BREAK_COMPLETED").pop("break_event_ref")
        with self.assertRaisesRegex(ReportError, "completed event fields"):
            validate(report, "fabric")

    def test_rejects_missing_parent_and_malformed_endpoints_without_traceback(self) -> None:
        report = fixture_report()
        next(event for event in report["events"] if event["action"] == "REMOVE_ITEM")["parent_ref"] = "missing"
        with self.assertRaises(ReportError):
            validate(report, "fabric")

        report = fixture_report()
        next(event for event in report["events"] if event["action"] == "REMOVE_ITEM").pop("destination")
        with self.assertRaises(ReportError):
            validate(report, "fabric")

    def test_rejects_fabricated_unknown_sink_position(self) -> None:
        report = fixture_report()
        parent = next(event for event in report["events"]
                      if event["action"] == "CONTAINER_BREAK_COMPLETED")
        parent["destination"]["position"] = {"x": 0, "y": 64, "z": 0}
        with self.assertRaisesRegex(ReportError, "destination fields do not match"):
            validate(report, "fabric")

    def test_rejects_unlisted_sensitive_fields_and_absolute_coordinates(self) -> None:
        report = fixture_report()
        report["events"][0]["username"] = "must-not-leak"
        with self.assertRaisesRegex(ReportError, "completed event fields"):
            validate(report, "fabric")

        report = fixture_report()
        source = report["events"][0]["source"]
        source["position"] = {"x": 5_376_846, "y": -60, "z": -1_413_724}
        with self.assertRaisesRegex(ReportError, "approved relative coordinates"):
            validate(report, "fabric")

    def test_rejects_raw_identity_fields_and_duplicate_evidence_refs(self) -> None:
        report = fixture_report()
        report["events"][0]["player_uuid"] = "must-not-leak"
        with self.assertRaisesRegex(ReportError, "completed event fields"):
            validate(report, "fabric")

        report = fixture_report()
        report["events"][1]["evidence_ref"] = report["events"][0]["evidence_ref"]
        with self.assertRaisesRegex(ReportError, "unique"):
            validate(report, "fabric")


if __name__ == "__main__":
    unittest.main()
