#!/usr/bin/env python3
"""Focused input and invariant checks for ItemGraph performance reports."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_itemgraph_performance_report import ReportError, validate_directory, validate_report  # noqa: E402


def report(loader: str, scenario: str, accepted: int) -> dict:
    backend_matrix = scenario in {"backend_mariadb_matrix", "backend_mysql_matrix"}
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
            "max_batch_size": 1_000,
            "rejected_items": 0,
        },
        "latency": {
            name: {
                "count": accepted if name == "enqueue" else (
                    1 if name == "persistence_commit" else 20 if backend_matrix and name == "query" else 0),
                "failed": 0,
                "average_us": 0,
                "max_ns": 0,
                "p95_upper_bound_ns": 0,
                "p95_over_10s": False,
            }
            for name in ("enqueue", "persistence_commit", "query", "correlation")
        },
        "persistence": {"persisted_items": accepted, "failed_batches": 0, "largest_batch": accepted},
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

    def test_directory_requires_complete_four_scenario_artifact_set(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaises(ReportError):
                validate_directory(directory)

    def test_accepts_mysql_and_mariadb_backend_matrix_reports(self) -> None:
        for flavor in ("mariadb", "mysql"):
            candidate = report("neoforge", f"backend_{flavor}_matrix", 512)
            self.assertEqual("mysql_mariadb", validate_report(candidate)["backend"])

    def test_rejects_network_backend_report_without_concurrent_lookup_samples(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["latency"]["query"]["count"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_network_backend_report_without_proven_lookup_overlap(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["workload"]["overlapping_lookups"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)


if __name__ == "__main__":
    unittest.main()
