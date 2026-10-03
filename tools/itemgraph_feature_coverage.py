#!/usr/bin/env python3
"""Emit a redacted registry-wide coverage inventory for a native ItemGraph report."""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from typing import Any

import itemgraph_differential_report as differential


COVERAGE_SCHEMA_VERSION = 1


def build_coverage(report: dict[str, Any]) -> dict[str, Any]:
    report = differential.validate_report(report, "itemgraph")
    if report["runtime_mode"] != "native_only":
        raise differential.ReportError("feature coverage requires a native-only ItemGraph report")

    registry, fixture_hash = differential.current_profile()
    event_counts = Counter(event["action"] for event in report["events"])
    coverage_rows: list[dict[str, Any]] = []
    seen_actions: set[str] = set()

    for action in registry["actions"]:
        name = action.get("itemgraph")
        if not isinstance(name, str) or not name:
            raise differential.ReportError("compatibility action is missing its ItemGraph action name")
        if name in seen_actions:
            raise differential.ReportError(f"duplicate ItemGraph action in compatibility registry: {name}")
        seen_actions.add(name)

        runtime_count = event_counts.get(name, 0)
        writer_status = action.get("release_writer_status")
        coverage_status = "observed-in-replay" if runtime_count else "not-observed-in-replay"

        related_issues = [entry.get("issue") for entry in action.get("differential_exceptions", [])
                          if isinstance(entry, dict) and isinstance(entry.get("issue"), int)]
        owner_issue = action.get("evidence_issue")
        if not isinstance(owner_issue, int) and related_issues:
            owner_issue = min(related_issues)
        if not isinstance(owner_issue, int) and coverage_status == "not-observed-in-replay":
            owner_issue = 31

        if writer_status == "present":
            source_contract = "exact-release-action-writer"
        elif writer_status == "unsupported-no-writer" and action.get("release_action_id") is not None:
            source_contract = "exact-release-enum-without-writer"
        elif action.get("source_enum"):
            source_contract = "pinned-source-only-action"
        elif action.get("grieflogger"):
            source_contract = "separate-release-table"
        else:
            source_contract = "itemgraph-extension"

        coverage_rows.append({
            "action": name,
            "grieflogger_action": action.get("grieflogger"),
            "source_enum": action.get("source_enum"),
            "source_action_id": action.get("source_id"),
            "release_action_id": action.get("release_action_id"),
            "release_writer_status": writer_status,
            "source_contract": source_contract,
            "compatibility_status": action.get("status"),
            "itemgraph_extension": action.get("itemgraph_extension"),
            "compatibility_note": action.get("compatibility_note"),
            "evidence_class": action.get("evidence_class"),
            "quantity_semantics": action.get("quantity"),
            "compatibility_table": sorted(differential.ACTION_TABLES[name]),
            "runtime_evidence_count": runtime_count,
            "coverage_status": coverage_status,
            "owner_issue": owner_issue,
        })

    unknown_actions = sorted(set(event_counts) - seen_actions)
    if unknown_actions:
        raise differential.ReportError("native report contains actions missing from the compatibility registry")

    statuses = Counter(row["coverage_status"] for row in coverage_rows)
    release_writer_statuses = Counter(row["release_writer_status"] for row in coverage_rows)
    return {
        "coverage_schema_version": COVERAGE_SCHEMA_VERSION,
        "compatibility_version": report["compatibility_version"],
        "source_profile_sha256": report["source_profile_sha256"],
        "release_fixture_sha256": fixture_hash,
        "loader": report["loader"],
        "system": "itemgraph",
        "runtime_mode": "native_only",
        "scenario_id": report["scenario_id"],
        "seed": report["seed"],
        "replay_event_count": len(report["events"]),
        "summary": {
            "registry_action_count": len(coverage_rows),
            "actions_observed_in_replay": sum(row["runtime_evidence_count"] > 0 for row in coverage_rows),
            "not_observed_in_replay": statuses.get("not-observed-in-replay", 0),
            "release_actions_without_writer": release_writer_statuses.get("unsupported-no-writer", 0),
        },
        "actions": coverage_rows,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="profile-pinned normalized ItemGraph report")
    parser.add_argument("--output", required=True, type=Path, help="redacted registry-wide coverage JSON")
    args = parser.parse_args(argv)
    try:
        if differential._paths_alias(args.input, args.output):
            raise differential.ReportError("coverage output path must not overwrite the normalized event report")
        coverage = build_coverage(differential.read_json(args.input))
        differential._write_json_atomically(args.output, coverage)
    except (OSError, differential.ReportError) as exc:
        print(f"ItemGraph feature coverage failed: {exc}", file=sys.stderr)
        return 2
    print(f"classified {coverage['summary']['registry_action_count']} ItemGraph actions; "
          f"{coverage['summary']['actions_observed_in_replay']} observed, "
          f"{coverage['summary']['not_observed_in_replay']} not observed, "
          f"{coverage['summary']['release_actions_without_writer']} without an exact-release writer")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
