#!/usr/bin/env python3
"""Validate redacted ItemGraph performance reports emitted by CI GameTests."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


TOP_LEVEL_FIELDS = {
    "schema_version", "loader", "scenario", "minecraft_version", "backend",
    "grieflogger_runtime_state", "workload", "queue", "latency", "persistence",
    "components", "memory",
}
LOADERS = {"fabric", "neoforge"}
BACKENDS = {"sqlite", "mysql_mariadb", "unknown"}
WORKLOAD_FIELDS = {
    "accepted_events", "batch_count", "batch_size", "persisted_counter_delta",
    "dropped_counter_delta", "durable_rows", "queue_remaining",
    "max_server_thread_batch_ns", "end_tick_callbacks", "flush_every_ticks",
    "enqueue_total_ns", "concurrent_lookups", "overlapping_lookups",
    "registered_lookup_commands", "registered_lookup_callbacks_completed",
    "registered_lookup_callbacks_failed", "registered_lookup_callbacks_completed_during_submissions",
    "registered_lookup_dispatch_callback_total_ns", "registered_lookup_dispatch_callback_max_ns",
    "registered_lookup_dispatch_callback_p95_ns",
    "automation_events", "modded_inventory_events", "elapsed_ms", "attempted_events",
    "correlation_pairs", "correlation_passes", "correlation_edges",
}
QUEUE_FIELDS = {
    "depth", "peak_depth", "capacity_per_type", "flush_every_ticks",
    "max_batch_size", "rejected_items",
}
LATENCY_FIELDS = {"count", "failed", "average_us", "max_ns", "p95_upper_bound_ns", "p95_over_10s"}
LATENCY_METRICS = {"enqueue", "persistence_commit", "query", "correlation"}
SHUTDOWN_SATURATION_BUDGET_MS = 1_000


class ReportError(ValueError):
    """The report is malformed or fails a deterministic benchmark invariant."""


def _object(value: Any, name: str, fields: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise ReportError(f"{name} fields do not match the pinned report schema")
    return value


def _integer(value: Any, name: str, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise ReportError(f"{name} must be an integer >= {minimum}")
    return value


def validate_report(report: Any) -> dict[str, Any]:
    value = _object(report, "performance report", TOP_LEVEL_FIELDS)
    if type(value["schema_version"]) is not int or value["schema_version"] != 2:
        raise ReportError("schema_version must be integer 2")
    if value["loader"] not in LOADERS:
        raise ReportError("loader must be fabric or neoforge")
    if value["scenario"] not in {
        "queue_burst", "queue_flush_durability",
        "backend_mariadb_matrix", "backend_mysql_matrix", "backend_fabric_mariadb_matrix",
        "backend_fabric_mysql_matrix", "shutdown_saturation", "correlation_burst",
    }:
        raise ReportError("scenario is not a supported queue benchmark")
    if value["minecraft_version"] != "1.21.1":
        raise ReportError("minecraft_version must be 1.21.1")
    if value["backend"] not in BACKENDS:
        raise ReportError("backend is not a supported ItemGraph backend label")
    grief_logger_state = value["grieflogger_runtime_state"]
    if grief_logger_state not in {"absent", "present", "unavailable"}:
        raise ReportError("grieflogger_runtime_state must be absent, present, or unavailable")
    if grief_logger_state == "present":
        raise ReportError("isolated performance reports must not run with GriefLogger installed")
    no_loader_runtime = value["loader"] == "neoforge" and value["scenario"] in {
        "backend_mariadb_matrix", "backend_mysql_matrix", "shutdown_saturation",
    }
    expected_grief_logger_state = "unavailable" if no_loader_runtime else "absent"
    if grief_logger_state != expected_grief_logger_state:
        raise ReportError(
            f"{value['loader']} {value['scenario']} report must record GriefLogger state as "
            f"{expected_grief_logger_state}"
        )

    workload = value["workload"]
    if not isinstance(workload, dict) or not set(workload).issubset(WORKLOAD_FIELDS):
        raise ReportError("workload contains unsupported or potentially identifying fields")
    for key, item in workload.items():
        _integer(item, f"workload.{key}")
    accepted = _integer(workload.get("accepted_events"), "workload.accepted_events", 1)
    if "attempted_events" in workload and value["scenario"] != "shutdown_saturation":
        raise ReportError("attempted_events is only valid for the pinned shutdown_saturation probe")
    persisted = _integer(workload.get("persisted_counter_delta"), "workload.persisted_counter_delta")
    dropped = _integer(workload.get("dropped_counter_delta"), "workload.dropped_counter_delta")
    durable_rows = _integer(workload.get("durable_rows"), "workload.durable_rows")
    queue_remaining = _integer(workload.get("queue_remaining"), "workload.queue_remaining")
    expected_drop_count = 1 if value["scenario"] == "shutdown_saturation" else 0
    if persisted < accepted or dropped != expected_drop_count or queue_remaining != 0:
        raise ReportError("queue benchmark persistence, explicit loss count, or shutdown backlog is inconsistent")

    queue = _object(value["queue"], "queue", QUEUE_FIELDS)
    for key, item in queue.items():
        _integer(item, f"queue.{key}")
    if queue["capacity_per_type"] != 10_000 or not 1 <= queue["flush_every_ticks"] <= 100:
        raise ReportError("queue capacity or flush cadence does not match the documented operational contract")
    if not 1 <= queue["max_batch_size"] <= 1_000:
        raise ReportError("max_batch_size is outside the validated configuration range")
    if queue["depth"] != queue_remaining:
        raise ReportError("queue depth disagrees with the durable workload result")
    if (queue["rejected_items"] != expected_drop_count or queue["peak_depth"] < 1
            or queue["peak_depth"] > queue["capacity_per_type"] * 3):
        raise ReportError("isolated queue probe rejection count or recorded peak depth is inconsistent")

    latency = _object(value["latency"], "latency", LATENCY_METRICS)
    for metric_name, metric_value in latency.items():
        metric = _object(metric_value, f"latency.{metric_name}", LATENCY_FIELDS)
        count = _integer(metric["count"], f"latency.{metric_name}.count")
        failed = _integer(metric["failed"], f"latency.{metric_name}.failed")
        _integer(metric["average_us"], f"latency.{metric_name}.average_us")
        _integer(metric["max_ns"], f"latency.{metric_name}.max_ns")
        if failed > count or type(metric["p95_over_10s"]) is not bool:
            raise ReportError(f"latency.{metric_name} counters are inconsistent")
        bound = metric["p95_upper_bound_ns"]
        if metric["p95_over_10s"]:
            if bound is not None:
                raise ReportError(f"latency.{metric_name} overflow must not claim a numeric percentile bound")
        elif _integer(bound, f"latency.{metric_name}.p95_upper_bound_ns") > 10_000_000_000:
            raise ReportError(f"latency.{metric_name} percentile bound exceeds its declared histogram range")

    expected_enqueue_samples = accepted + expected_drop_count
    if latency["enqueue"]["count"] != expected_enqueue_samples:
        raise ReportError("enqueue latency samples do not match this scenario's attempted events")
    if latency["enqueue"]["failed"] != expected_drop_count:
        raise ReportError("enqueue latency failures do not match this scenario's explicit rejection count")
    if latency["persistence_commit"]["count"] == 0:
        raise ReportError("queue benchmark is missing persistence latency samples")

    persistence = _object(value["persistence"], "persistence", {
        "persisted_items", "failed_batches", "largest_batch",
    })
    for key, item in persistence.items():
        _integer(item, f"persistence.{key}")
    if persistence["failed_batches"] != 0 or persistence["persisted_items"] < accepted:
        raise ReportError("persistence metrics contradict the durable queue workload")
    if latency["persistence_commit"]["failed"] != persistence["failed_batches"]:
        raise ReportError("persistence latency failures disagree with the failed-batch counter")
    if accepted > 0 and persistence["largest_batch"] == 0:
        raise ReportError("positive persistence workload has no recorded batch size")
    if persistence["largest_batch"] > queue["max_batch_size"]:
        raise ReportError("largest persistence batch exceeds the configured maximum batch size")

    components = _object(value["components"], "components", {
        "decode_failure_cache_insertions", "negative_cache_hits",
    })
    for key, item in components.items():
        _integer(item, f"components.{key}")

    memory = _object(value["memory"], "memory", {"heap_used_bytes", "heap_max_bytes"})
    heap_used = _integer(memory["heap_used_bytes"], "memory.heap_used_bytes")
    heap_max = _integer(memory["heap_max_bytes"], "memory.heap_max_bytes", minimum=-1)
    if heap_max != -1 and heap_used > heap_max:
        raise ReportError("heap usage exceeds the reported JVM heap maximum")

    if value["scenario"] == "queue_burst":
        if value["loader"] != "neoforge" or accepted != 8_000 or value["backend"] != "sqlite":
            raise ReportError("queue_burst must be the pinned NeoForge SQLite 8,000-event workload")
        if durable_rows != accepted:
            raise ReportError("queue_burst ledger must contain exactly its accepted events")
        if _integer(workload.get("max_server_thread_batch_ns"), "workload.max_server_thread_batch_ns") >= 50_000_000:
            raise ReportError("queue_burst exceeded the existing 50 ms server-thread batch budget")
    if value["scenario"] == "queue_flush_durability":
        if value["loader"] != "fabric" or accepted != 32 or value["backend"] != "sqlite":
            raise ReportError("queue_flush_durability must be the pinned Fabric SQLite 32-event workload")
        if durable_rows != accepted:
            raise ReportError("queue_flush_durability ledger must contain exactly its accepted events")
        if _integer(workload.get("enqueue_total_ns"), "workload.enqueue_total_ns") >= 50_000_000:
            raise ReportError("Fabric queue submission exceeded the existing 50 ms server-thread budget")
    if value["scenario"] == "shutdown_saturation":
        if value["loader"] != "neoforge" or value["backend"] != "sqlite" or accepted != 10_000:
            raise ReportError("shutdown_saturation must be the pinned NeoForge SQLite bounded-queue workload")
        if durable_rows != accepted or dropped != 1:
            raise ReportError("shutdown_saturation must persist all 10,000 accepted events and count one rejected event")
        if _integer(workload.get("attempted_events"), "workload.attempted_events") != accepted + 1:
            raise ReportError("shutdown_saturation must record exactly one over-capacity submission")
        if queue["peak_depth"] != queue["capacity_per_type"] or queue["rejected_items"] != 1:
            raise ReportError("shutdown_saturation must fill the bounded audit queue and record its rejection")
        if _integer(workload.get("elapsed_ms"), "workload.elapsed_ms") >= SHUTDOWN_SATURATION_BUDGET_MS:
            raise ReportError(
                "shutdown_saturation exceeded the 1,000 ms healthy-SQLite drain regression budget"
            )
    if value["scenario"] == "correlation_burst":
        if value["loader"] not in LOADERS or value["backend"] != "sqlite" or accepted != 500:
            raise ReportError("correlation_burst must be the pinned 500-observation SQLite workload on either loader")
        if durable_rows != accepted:
            raise ReportError("correlation_burst must durably persist every accepted source observation")
        if (_integer(workload.get("correlation_pairs"), "workload.correlation_pairs") != 250
                or _integer(workload.get("correlation_passes"), "workload.correlation_passes") != 5
                or _integer(workload.get("correlation_edges"), "workload.correlation_edges") != 250):
            raise ReportError("correlation_burst must correlate five batches into exactly 250 quantity-conserving edges")
        if (latency["correlation"]["count"] != workload["correlation_passes"]
                or latency["correlation"]["failed"] != 0):
            raise ReportError("correlation_burst must report five successful measured correlation passes")
    network_scenarios = {"backend_mariadb_matrix", "backend_mysql_matrix",
                         "backend_fabric_mariadb_matrix", "backend_fabric_mysql_matrix"}
    if value["scenario"] in network_scenarios:
        is_fabric = value["scenario"].startswith("backend_fabric_")
        scenario_prefix = "backend_fabric_" if is_fabric else "backend_"
        expected_flavor = value["scenario"].removeprefix(scenario_prefix).removesuffix("_matrix")
        expected_loader = "fabric" if is_fabric else "neoforge"
        if value["loader"] != expected_loader or value["backend"] != "mysql_mariadb" or accepted != 512:
            raise ReportError("network backend matrix must be a 512-event workload on the pinned loader")
        if durable_rows != accepted:
            raise ReportError("network backend matrix must match its exact durable ledger row count")
        if _integer(workload.get("concurrent_lookups"), "workload.concurrent_lookups") != 20:
            raise ReportError("network backend matrix must complete 20 read-only lookups across four readers")
        overlap = _integer(workload.get("overlapping_lookups"), "workload.overlapping_lookups", 1)
        if overlap > 20:
            raise ReportError("overlapping_lookups cannot exceed the completed read-only lookup count")
        if _integer(workload.get("automation_events"), "workload.automation_events") != 170:
            raise ReportError("network backend matrix must include 170 synthetic automation events")
        if _integer(workload.get("modded_inventory_events"), "workload.modded_inventory_events") != 171:
            raise ReportError("network backend matrix must include 171 synthetic modded-inventory events")
        _integer(workload.get("enqueue_total_ns"), "workload.enqueue_total_ns", 1)
        _integer(workload.get("elapsed_ms"), "workload.elapsed_ms")
        registered_commands = _integer(
            workload.get("registered_lookup_commands"), "workload.registered_lookup_commands", 1)
        callbacks_completed = _integer(
            workload.get("registered_lookup_callbacks_completed"),
            "workload.registered_lookup_callbacks_completed")
        callbacks_failed = _integer(
            workload.get("registered_lookup_callbacks_failed"),
            "workload.registered_lookup_callbacks_failed")
        callbacks_during_submissions = _integer(
            workload.get("registered_lookup_callbacks_completed_during_submissions"),
            "workload.registered_lookup_callbacks_completed_during_submissions", 1)
        callback_total_ns = _integer(
            workload.get("registered_lookup_dispatch_callback_total_ns"),
            "workload.registered_lookup_dispatch_callback_total_ns", 1)
        callback_max_ns = _integer(
            workload.get("registered_lookup_dispatch_callback_max_ns"),
            "workload.registered_lookup_dispatch_callback_max_ns", 1)
        callback_p95_ns = _integer(
            workload.get("registered_lookup_dispatch_callback_p95_ns"),
            "workload.registered_lookup_dispatch_callback_p95_ns", 1)
        if (registered_commands != 20 or callbacks_completed != registered_commands
                or callbacks_failed != 0 or callbacks_during_submissions > registered_commands):
            raise ReportError("registered lookup command count, callback completion, or failure count is inconsistent")
        if callback_total_ns < callback_max_ns or callback_p95_ns > callback_max_ns:
            raise ReportError("registered lookup aggregate callback latency fields are inconsistent")
        if (latency["query"]["count"] != workload["concurrent_lookups"] + registered_commands
                or latency["query"]["failed"] != 0):
            raise ReportError("network backend matrix must report every successful raw-SQL and registered lookup query")
        if expected_flavor not in {"mysql", "mariadb"}:
            raise ReportError("network backend scenario must identify MySQL or MariaDB")

    return value


def validate_directory(directory: Path) -> list[dict[str, Any]]:
    expected = {
        "itemgraph-neoforge-queue_burst.json": ("neoforge", "queue_burst"),
        "itemgraph-fabric-queue_flush_durability.json": ("fabric", "queue_flush_durability"),
        "itemgraph-neoforge-backend_mariadb_matrix.json": ("neoforge", "backend_mariadb_matrix"),
        "itemgraph-neoforge-backend_mysql_matrix.json": ("neoforge", "backend_mysql_matrix"),
        "itemgraph-fabric-backend_fabric_mariadb_matrix.json": ("fabric", "backend_fabric_mariadb_matrix"),
        "itemgraph-fabric-backend_fabric_mysql_matrix.json": ("fabric", "backend_fabric_mysql_matrix"),
        "itemgraph-neoforge-shutdown_saturation.json": ("neoforge", "shutdown_saturation"),
        "itemgraph-neoforge-correlation_burst.json": ("neoforge", "correlation_burst"),
        "itemgraph-fabric-correlation_burst.json": ("fabric", "correlation_burst"),
    }
    actual = {path.name for path in directory.glob("*.json")}
    if actual != set(expected):
        raise ReportError("performance report directory must contain exactly the pinned benchmark reports")
    reports = []
    for name, (expected_loader, expected_scenario) in sorted(expected.items()):
        try:
            report = validate_report(json.loads((directory / name).read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError) as error:
            raise ReportError(f"could not read {name}: {error}") from error
        if (report["loader"], report["scenario"]) != (expected_loader, expected_scenario):
            raise ReportError(f"{name} does not contain its pinned loader and scenario report")
        reports.append(report)
    return reports


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        reports = validate_directory(args.input_dir)
    except (OSError, ReportError) as error:
        print(f"ItemGraph performance report failed: {error}", file=sys.stderr)
        return 2
    print(f"validated redacted ItemGraph performance reports: {len(reports)} scenarios")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
