#!/usr/bin/env python3
"""Focused input and invariant checks for ItemGraph performance reports."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_itemgraph_performance_report import ReportError, validate_directory, validate_report  # noqa: E402


def report(loader: str, scenario: str, accepted: int) -> dict:
    backend_matrix = scenario in {"backend_mariadb_matrix", "backend_mysql_matrix", "backend_fabric_mariadb_matrix", "backend_fabric_mysql_matrix"}
    shutdown_saturation = scenario == "shutdown_saturation"
    attempted = accepted + 1 if shutdown_saturation else accepted
    if backend_matrix:
        workload = {
            "accepted_events": accepted,
            "persisted_counter_delta": accepted,
            "dropped_counter_delta": 0,
            "durable_rows": accepted,
            "queue_remaining": 0,
            "concurrent_lookups": 20,
            "overlapping_lookups": 20,
            "automation_events": 170,
            "modded_inventory_events": 171,
            "enqueue_total_ns": 20_000_000,
            "elapsed_ms": 300,
        }
    elif shutdown_saturation:
        workload = {
            "accepted_events": accepted,
            "attempted_events": attempted,
            "persisted_counter_delta": accepted,
            "dropped_counter_delta": 1,
            "durable_rows": accepted,
            "queue_remaining": 0,
            "elapsed_ms": 100,
        }
    else:
        workload = {
            "accepted_events": accepted,
            "persisted_counter_delta": accepted,
            "dropped_counter_delta": 0,
            "durable_rows": accepted,
            "queue_remaining": 0,
            **({"max_server_thread_batch_ns": 12_000_000} if loader == "neoforge" else {
                "enqueue_total_ns": 1_000_000,
            }),
        }
    return {
        "schema_version": 1,
        "loader": loader,
        "scenario": scenario,
        "minecraft_version": "1.21.1",
        "backend": "mysql_mariadb" if backend_matrix else "sqlite",
        "grieflogger_installed": False,
        "workload": workload,
        "queue": {
            "depth": 0,
            "peak_depth": accepted,
            "capacity_per_type": 10_000,
            "flush_every_ticks": 20,
            "max_batch_size": 100 if shutdown_saturation else 1_000,
            "rejected_items": 1 if shutdown_saturation else 0,
        },
        "latency": {
            name: {
                "count": attempted if name == "enqueue" else (
                    1 if name == "persistence_commit" else 20 if backend_matrix and name == "query" else 0),
                "failed": 1 if shutdown_saturation and name == "enqueue" else 0,
                "average_us": 0,
                "max_ns": 0,
                "p95_upper_bound_ns": 0,
                "p95_over_10s": False,
            }
            for name in ("enqueue", "persistence_commit", "query", "correlation")
        },
        "persistence": {
            "persisted_items": accepted,
            "failed_batches": 0,
            "largest_batch": min(accepted, 100 if shutdown_saturation else 1_000),
        },
        "components": {"decode_failure_cache_insertions": 0, "negative_cache_hits": 0},
        "memory": {"heap_used_bytes": 1, "heap_max_bytes": -1},
    }

class PerformanceReportValidationTest(unittest.TestCase):
    def test_accepts_pinned_loader_reports_and_unbounded_heap_max(self) -> None:
        self.assertEqual("neoforge", validate_report(report("neoforge", "queue_burst", 8_000))["loader"])
        self.assertEqual("fabric", validate_report(report("fabric", "queue_flush_durability", 32))["loader"])

    def test_rejects_mismatched_durable_row_count(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["workload"]["durable_rows"] = 31
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_unknown_fields_that_could_leak_identity(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["workload"]["player_name"] = "private"
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_latency_over_budget(self) -> None:
        candidate = report("neoforge", "queue_burst", 8_000)
        candidate["workload"]["max_server_thread_batch_ns"] = 50_000_000
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_backend_that_the_probe_did_not_measure(self) -> None:
        candidate = report("neoforge", "queue_burst", 8_000)
        candidate["backend"] = "mysql_mariadb"
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_queue_rejections_in_isolated_probe(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["queue"]["rejected_items"] = 1
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_missing_queue_depth_measurement(self) -> None:
        candidate = report("neoforge", "queue_burst", 8_000)
        candidate["queue"]["peak_depth"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_missing_enqueue_or_persistence_samples(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["latency"]["enqueue"]["count"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_enqueue_samples_from_another_scenario(self) -> None:
        candidate = report("neoforge", "queue_burst", 8_000)
        candidate["latency"]["enqueue"]["count"] += 25
        with self.assertRaisesRegex(ReportError, "attempted events"):
            validate_report(candidate)

    def test_rejects_attempted_events_override_for_queue_burst(self) -> None:
        candidate = report("neoforge", "queue_burst", 8_000)
        candidate["workload"]["attempted_events"] = 8_001
        candidate["latency"]["enqueue"]["count"] = 8_001
        with self.assertRaisesRegex(ReportError, "only valid.*shutdown_saturation"):
            validate_report(candidate)

    def test_rejects_enqueue_failures_that_disagree_with_explicit_rejections(self) -> None:
        candidate = report("neoforge", "queue_burst", 8_000)
        candidate["latency"]["enqueue"]["failed"] = 1
        with self.assertRaisesRegex(ReportError, "explicit rejection count"):
            validate_report(candidate)

    def test_directory_requires_complete_seven_report_artifact_set(self) -> None:
        scenarios = [
            ("itemgraph-neoforge-queue_burst.json", report("neoforge", "queue_burst", 8_000)),
            ("itemgraph-fabric-queue_flush_durability.json", report("fabric", "queue_flush_durability", 32)),
            ("itemgraph-neoforge-backend_mariadb_matrix.json", report("neoforge", "backend_mariadb_matrix", 512)),
            ("itemgraph-neoforge-backend_mysql_matrix.json", report("neoforge", "backend_mysql_matrix", 512)),
            ("itemgraph-fabric-backend_fabric_mariadb_matrix.json", report("fabric", "backend_fabric_mariadb_matrix", 512)),
            ("itemgraph-fabric-backend_fabric_mysql_matrix.json", report("fabric", "backend_fabric_mysql_matrix", 512)),
            ("itemgraph-neoforge-shutdown_saturation.json", report("neoforge", "shutdown_saturation", 10_000)),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for filename, contents in scenarios:
                (directory / filename).write_text(json.dumps(contents), encoding="utf-8")
            self.assertEqual(7, len(validate_directory(directory)))
            (directory / "itemgraph-fabric-backend_fabric_mysql_matrix.json").unlink()
            with self.assertRaises(ReportError):
                validate_directory(directory)
    def test_accepts_shutdown_saturation_report_with_one_explicit_rejection(self) -> None:
        candidate = report("neoforge", "shutdown_saturation", 10_000)
        self.assertEqual("shutdown_saturation", validate_report(candidate)["scenario"])

    def test_rejects_shutdown_saturation_report_when_accepted_rows_are_not_durable(self) -> None:
        candidate = report("neoforge", "shutdown_saturation", 10_000)
        candidate["workload"]["durable_rows"] = 9_999
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_persistence_batch_over_configured_cap_for_every_scenario(self) -> None:
        scenarios = (
            ("neoforge", "queue_burst", 8_000),
            ("fabric", "queue_flush_durability", 32),
            ("neoforge", "backend_mariadb_matrix", 512),
            ("neoforge", "backend_mysql_matrix", 512),
            ("fabric", "backend_fabric_mariadb_matrix", 512),
            ("fabric", "backend_fabric_mysql_matrix", 512),
            ("neoforge", "shutdown_saturation", 10_000),
        )
        for loader, scenario, accepted in scenarios:
            with self.subTest(scenario=scenario):
                candidate = report(loader, scenario, accepted)
                candidate["queue"]["max_batch_size"] = 10
                candidate["persistence"]["largest_batch"] = 11
                with self.assertRaisesRegex(ReportError, "largest persistence batch"):
                    validate_report(candidate)

    def test_rejects_missing_persistence_batch_size_for_accepted_events(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["persistence"]["largest_batch"] = 0
        with self.assertRaisesRegex(ReportError, "no recorded batch size"):
            validate_report(candidate)

    def test_accepts_mysql_and_mariadb_backend_matrix_reports_on_both_loaders(self) -> None:
        cases = (
            ("neoforge", "backend_mariadb_matrix"),
            ("neoforge", "backend_mysql_matrix"),
            ("fabric", "backend_fabric_mariadb_matrix"),
            ("fabric", "backend_fabric_mysql_matrix"),
        )
        for loader, scenario in cases:
            candidate = report(loader, scenario, 512)
            self.assertEqual(loader, validate_report(candidate)["loader"])
            self.assertEqual("mysql_mariadb", validate_report(candidate)["backend"])

    def test_rejects_network_backend_report_without_concurrent_lookup_samples(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["latency"]["query"]["count"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_network_backend_report_with_cumulative_lookup_samples(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["latency"]["query"]["count"] += 20
        with self.assertRaisesRegex(ReportError, "successful read-only lookups"):
            validate_report(candidate)

    def test_rejects_network_backend_report_without_proven_lookup_overlap(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["workload"]["overlapping_lookups"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)


if __name__ == "__main__":
    unittest.main()
