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
    idle_baseline = scenario in {"idle_sqlite_baseline", "idle_worker_mariadb_baseline", "idle_worker_mysql_baseline"}
    shutdown_saturation = scenario == "shutdown_saturation"
    correlation_burst = scenario == "correlation_burst"
    attempted = accepted + 1 if shutdown_saturation else accepted
    if idle_baseline:
        workload = {
            "idle_window_ms": 1_000,
            "accepted_events": 0,
            "persisted_counter_delta": 0,
            "dropped_counter_delta": 0,
            "durable_rows_delta": 0,
            "queue_remaining": 0,
            "database_heartbeat_delta": 0,
            "enqueue_samples": 0,
            "persistence_samples": 0,
            "query_samples": 0,
            "correlation_samples": 0,
            "heap_used_before_bytes": 1_000,
            "heap_used_after_bytes": 1_000,
        }
    elif correlation_burst:
        workload = {
            "accepted_events": accepted,
            "persisted_counter_delta": accepted,
            "dropped_counter_delta": 0,
            "durable_rows": accepted,
            "queue_remaining": 0,
            "correlation_pairs": 250,
            "correlation_passes": 5,
            "correlation_edges": 250,
            "elapsed_ms": 1_000,
        }
    elif backend_matrix:
        workload = {
            "accepted_events": accepted,
            "persisted_counter_delta": accepted,
            "dropped_counter_delta": 0,
            "durable_rows": accepted,
            "queue_remaining": 0,
            "concurrent_lookups": 20,
            "overlapping_lookups": 20,
            "registered_lookup_commands": 20,
            "registered_lookup_callbacks_completed": 20,
            "registered_lookup_callbacks_failed": 0,
            "registered_lookup_callbacks_completed_during_submissions": 1,
            "registered_lookup_dispatch_callback_total_ns": 20_000,
            "registered_lookup_dispatch_callback_max_ns": 1_000,
            "registered_lookup_dispatch_callback_p95_ns": 950,
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
        "schema_version": 2,
        "loader": loader,
        "scenario": scenario,
        "minecraft_version": "1.21.1",
        "backend": "mysql_mariadb" if backend_matrix or scenario in {
            "idle_worker_mariadb_baseline", "idle_worker_mysql_baseline",
        } else "sqlite",
        "grieflogger_runtime_state": (
            "unavailable" if loader == "neoforge" and scenario in {
                "backend_mariadb_matrix", "backend_mysql_matrix", "shutdown_saturation",
                "idle_worker_mariadb_baseline", "idle_worker_mysql_baseline",
            } else "absent"
        ),
        "workload": workload,
        "queue": {
            "depth": 0,
            "peak_depth": 0 if idle_baseline else accepted,
            "capacity_per_type": 10_000,
            "flush_every_ticks": 20,
            "max_batch_size": 100 if shutdown_saturation else 1_000,
            "rejected_items": 1 if shutdown_saturation else 0,
        },
        "latency": {
            name: {
                "count": 0 if idle_baseline else attempted if name == "enqueue" else (
                    5 if correlation_burst and name == "correlation" else (
                        1 if name == "persistence_commit" else 40 if backend_matrix and name == "query" else 0)),
                "failed": 1 if shutdown_saturation and name == "enqueue" else 0,
                "average_us": 0,
                "max_ns": 0,
                "p95_upper_bound_ns": 0,
                "p95_over_10s": False,
            }
            for name in ("enqueue", "persistence_commit", "query", "correlation")
        },
        "persistence": {
            "persisted_items": 0 if idle_baseline else accepted,
            "failed_batches": 0,
            "largest_batch": 0 if idle_baseline else min(accepted, 100 if shutdown_saturation else 1_000),
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

    def test_rejects_report_when_runtime_detects_grieflogger_installed(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["grieflogger_runtime_state"] = "present"
        with self.assertRaisesRegex(ReportError, "GriefLogger installed"):
            validate_report(candidate)

    def test_rejects_unavailable_grieflogger_state_for_loader_game_test(self) -> None:
        candidate = report("fabric", "queue_flush_durability", 32)
        candidate["grieflogger_runtime_state"] = "unavailable"
        with self.assertRaisesRegex(ReportError, "must record GriefLogger state as absent"):
            validate_report(candidate)

    def test_records_unavailable_grieflogger_state_for_neoforge_junit_probe(self) -> None:
        self.assertEqual(
            "unavailable",
            validate_report(report("neoforge", "shutdown_saturation", 10_000))["grieflogger_runtime_state"],
        )

    def test_accepts_empty_idle_baselines_for_both_loader_database_pairs(self) -> None:
        for loader in ("neoforge", "fabric"):
            for scenario in ("idle_sqlite_baseline", "idle_worker_mariadb_baseline", "idle_worker_mysql_baseline"):
                with self.subTest(loader=loader, scenario=scenario):
                    candidate = report(loader, scenario, 0)
                    self.assertEqual(scenario, validate_report(candidate)["scenario"])

    def test_rejects_idle_baseline_that_records_database_or_queue_work(self) -> None:
        candidate = report("neoforge", "idle_worker_mariadb_baseline", 0)
        candidate["workload"]["persistence_samples"] = 1
        with self.assertRaisesRegex(ReportError, "unexpected work"):
            validate_report(candidate)

    def test_rejects_idle_baseline_without_full_observation_window(self) -> None:
        candidate = report("fabric", "idle_worker_mysql_baseline", 0)
        candidate["workload"]["idle_window_ms"] = 999
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_idle_baseline_with_queue_peak(self) -> None:
        candidate = report("fabric", "idle_worker_mysql_baseline", 0)
        candidate["queue"]["peak_depth"] = 1
        with self.assertRaisesRegex(ReportError, "bounded empty-queue"):
            validate_report(candidate)

    def test_rejects_idle_heap_samples_above_reported_heap_maximum(self) -> None:
        candidate = report("fabric", "idle_sqlite_baseline", 0)
        candidate["workload"]["heap_used_after_bytes"] = 2_000
        candidate["memory"]["heap_used_bytes"] = 1_000
        candidate["memory"]["heap_max_bytes"] = 1_500
        with self.assertRaisesRegex(ReportError, "idle heap sample"):
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

    def test_directory_requires_complete_fifteen_report_artifact_set(self) -> None:
        scenarios = [
            ("itemgraph-neoforge-queue_burst.json", report("neoforge", "queue_burst", 8_000)),
            ("itemgraph-fabric-queue_flush_durability.json", report("fabric", "queue_flush_durability", 32)),
            ("itemgraph-neoforge-backend_mariadb_matrix.json", report("neoforge", "backend_mariadb_matrix", 512)),
            ("itemgraph-neoforge-backend_mysql_matrix.json", report("neoforge", "backend_mysql_matrix", 512)),
            ("itemgraph-fabric-backend_fabric_mariadb_matrix.json", report("fabric", "backend_fabric_mariadb_matrix", 512)),
            ("itemgraph-fabric-backend_fabric_mysql_matrix.json", report("fabric", "backend_fabric_mysql_matrix", 512)),
            ("itemgraph-neoforge-shutdown_saturation.json", report("neoforge", "shutdown_saturation", 10_000)),
            ("itemgraph-neoforge-correlation_burst.json", report("neoforge", "correlation_burst", 500)),
            ("itemgraph-fabric-correlation_burst.json", report("fabric", "correlation_burst", 500)),
            ("itemgraph-neoforge-idle_worker_mariadb_baseline.json", report("neoforge", "idle_worker_mariadb_baseline", 0)),
            ("itemgraph-neoforge-idle_worker_mysql_baseline.json", report("neoforge", "idle_worker_mysql_baseline", 0)),
            ("itemgraph-fabric-idle_worker_mariadb_baseline.json", report("fabric", "idle_worker_mariadb_baseline", 0)),
            ("itemgraph-fabric-idle_worker_mysql_baseline.json", report("fabric", "idle_worker_mysql_baseline", 0)),
            ("itemgraph-neoforge-idle_sqlite_baseline.json", report("neoforge", "idle_sqlite_baseline", 0)),
            ("itemgraph-fabric-idle_sqlite_baseline.json", report("fabric", "idle_sqlite_baseline", 0)),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for filename, contents in scenarios:
                (directory / filename).write_text(json.dumps(contents), encoding="utf-8")
            self.assertEqual(15, len(validate_directory(directory)))
            (directory / "itemgraph-fabric-correlation_burst.json").write_text(
                json.dumps(scenarios[0][1]), encoding="utf-8"
            )
            with self.assertRaisesRegex(ReportError, "pinned loader and scenario"):
                validate_directory(directory)
            (directory / "itemgraph-fabric-correlation_burst.json").write_text(
                json.dumps(scenarios[-1][1]), encoding="utf-8"
            )
            (directory / "itemgraph-fabric-backend_fabric_mysql_matrix.json").unlink()
            with self.assertRaises(ReportError):
                validate_directory(directory)
    def test_accepts_shutdown_saturation_report_with_one_explicit_rejection(self) -> None:
        candidate = report("neoforge", "shutdown_saturation", 10_000)
        candidate["workload"]["elapsed_ms"] = 999
        self.assertEqual("shutdown_saturation", validate_report(candidate)["scenario"])

    def test_rejects_shutdown_saturation_at_or_over_one_second(self) -> None:
        candidate = report("neoforge", "shutdown_saturation", 10_000)
        candidate["workload"]["elapsed_ms"] = 1_000
        with self.assertRaisesRegex(ReportError, "1,000 ms healthy-SQLite drain regression budget"):
            validate_report(candidate)

    def test_accepts_cross_loader_correlation_reports_with_five_measured_passes(self) -> None:
        for loader in ("neoforge", "fabric"):
            with self.subTest(loader=loader):
                self.assertEqual("correlation_burst",
                                 validate_report(report(loader, "correlation_burst", 500))["scenario"])

    def test_rejects_correlation_report_without_all_expected_edges(self) -> None:
        candidate = report("neoforge", "correlation_burst", 500)
        candidate["workload"]["correlation_edges"] = 249
        with self.assertRaisesRegex(ReportError, "quantity-conserving edges"):
            validate_report(candidate)

    def test_rejects_correlation_report_with_unmatched_pass_samples(self) -> None:
        candidate = report("fabric", "correlation_burst", 500)
        candidate["latency"]["correlation"]["count"] = 4
        with self.assertRaisesRegex(ReportError, "five successful measured correlation passes"):
            validate_report(candidate)

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
            ("neoforge", "correlation_burst", 500),
            ("fabric", "correlation_burst", 500),
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
        with self.assertRaisesRegex(ReportError, "raw-SQL and registered lookup"):
            validate_report(candidate)

    def test_rejects_network_backend_report_without_proven_lookup_overlap(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["workload"]["overlapping_lookups"] = 0
        with self.assertRaises(ReportError):
            validate_report(candidate)

    def test_rejects_network_backend_report_with_missing_registered_lookup_callback(self) -> None:
        candidate = report("neoforge", "backend_mysql_matrix", 512)
        candidate["workload"]["registered_lookup_callbacks_completed"] = 19
        with self.assertRaisesRegex(ReportError, "registered lookup command count"):
            validate_report(candidate)

    def test_rejects_network_backend_report_with_failed_registered_lookup_callback(self) -> None:
        candidate = report("fabric", "backend_fabric_mariadb_matrix", 512)
        candidate["workload"]["registered_lookup_callbacks_failed"] = 1
        with self.assertRaisesRegex(ReportError, "registered lookup command count"):
            validate_report(candidate)

    def test_rejects_network_backend_report_without_callback_completed_during_submissions(self) -> None:
        candidate = report("neoforge", "backend_mariadb_matrix", 512)
        candidate["workload"]["registered_lookup_callbacks_completed_during_submissions"] = 0
        with self.assertRaisesRegex(
                ReportError, "registered_lookup_callbacks_completed_during_submissions must be an integer >= 1"):
            validate_report(candidate)

    def test_rejects_network_backend_report_with_inconsistent_registered_lookup_latency(self) -> None:
        candidate = report("fabric", "backend_fabric_mysql_matrix", 512)
        candidate["workload"]["registered_lookup_dispatch_callback_p95_ns"] = 1_001
        with self.assertRaisesRegex(ReportError, "aggregate callback latency"):
            validate_report(candidate)


if __name__ == "__main__":
    unittest.main()
