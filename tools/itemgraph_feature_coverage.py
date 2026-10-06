#!/usr/bin/env python3
"""Emit a redacted registry-wide coverage inventory for a native ItemGraph report."""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path
from typing import Any

import itemgraph_differential_report as differential


COVERAGE_SCHEMA_VERSION = 2

REFERENCE_TABLE_REPRESENTATIONS = {
    "users": "actor_ref is present; replay identity values are redacted, so UUID/name equivalence is not checked",
    "usernames": "UUID-keyed native name history in ig_player_name_history, derived from PLAYER_JOIN evidence; replay counts join signals without exporting names, UUIDs, or history rows",
    "levels": "dimension resource IDs stored inline on native evidence rows",
    "materials": "item/block resource IDs stored inline on native evidence rows",
    "entities": "entity resource IDs stored inline as native evidence subjects",
}
CONTRACT_REQUIREMENT_ISSUES = (136, 137, 138, 140)
DURABILITY_REQUIREMENTS = (
    "transaction-boundaries",
    "queue-acceptance-vs-durable-persistence",
    "failure-retry-and-rejection",
    "shutdown-drain-and-flush",
    "restart-recovery",
)


def _feature_contract_rows(registry: dict[str, Any], fixture: dict[str, Any]) -> list[dict[str, Any]]:
    """Build source-versus-release rows for currently open replacement requirements.

    The exact 1.21.1 fixture is authoritative for release claims. Facts available
    only from the newer 26.2 source are deliberately marked source-only, and each
    open acceptance remains unresolved until a later reviewed change proves it.
    """
    source_commit = registry["audit_baseline"]["commit"]
    commands = fixture.get("commands", {})
    inspector = fixture.get("inspector", {})
    action_writers = fixture.get("action_writer_audit", {}).get("expected_action_field_access_classes", {})
    remove_item_writers = action_writers.get("ItemAction.REMOVE_ITEM", [])
    locales_in_fixture = fixture.get("locales")
    named_permissions_in_fixture = fixture.get("permissions", {}).get("named_nodes")
    chat_features_in_fixture = fixture.get("history_chat_interactions")

    rows: list[dict[str, Any]] = [
        {
            "requirement_id": "issue-136-localization",
            "owner_issue": 136,
            "feature": "offline-server-side-localization",
            "exact_release": {
                "status": "not-recorded-in-exact-release-fixture" if locales_in_fixture is None else "recorded",
                "locale_inventory": locales_in_fixture,
            },
            "pinned_source": {
                "status": "source-only",
                "commit": source_commit,
                "config_default": "en_us",
                "config_locales": ["en_us", "nl_nl", "zh_tw"],
                "packaged_locales": ["en_us", "nl_nl", "zh_cn", "zh_tw"],
                "zh_cn_configurable": False,
                "behavior": "mod-locales-fall-back-to-en_us; Minecraft locale may be downloaded-and-cached",
                "source_refs": [
                    "GriefLoggerConfig.java",
                    "LanguageManager.java",
                    "assets/grieflogger/lang/*.json",
                ],
            },
            "itemgraph_status": "implemented-with-separate-tests-not-in-current-native-replay",
            "itemgraph_evidence": ["ItemGraphConfigTest", "FabricItemGraphConfigTest", "QueryFormatterTest"],
            "replay_status": "not-exercised-in-current-native-replay",
            "acceptance_status": "unresolved",
        },
        {
            "requirement_id": "issue-137-named-permissions",
            "owner_issue": 137,
            "feature": "command-specific-permission-nodes",
            "exact_release": {
                "status": "named-nodes-not-recorded-in-exact-release-fixture"
                if named_permissions_in_fixture is None else "recorded",
                "permission_level": commands.get("permission_level"),
                "named_nodes": named_permissions_in_fixture,
            },
            "pinned_source": {
                "status": "source-only",
                "commit": source_commit,
                "permission_nodes": [
                    "grieflogger.command",
                    "grieflogger.command.lookup",
                    "grieflogger.command.inspect",
                    "grieflogger.command.page",
                ],
                "fallback": "GAMEMASTERS",
                "source_refs": ["GriefLoggerCommand.java"],
            },
            "itemgraph_status": "implemented-with-separate-tests-not-in-current-native-replay",
            "itemgraph_evidence": ["ItemGraphCommandsHelpTest", "FabricItemGraphCommandsParityTest"],
            "replay_status": "not-exercised-in-current-native-replay",
            "acceptance_status": "unresolved",
        },
        {
            "requirement_id": "issue-138-history-chat-interactions",
            "owner_issue": 138,
            "feature": "history-hover-page-and-dimension-aware-location-actions",
            "exact_release": {
                "status": "rich-interactions-not-recorded-in-exact-release-fixture"
                if chat_features_in_fixture is None else "recorded",
                "page_command_present": "page" in commands.get("subcommands", []),
                "inspector_result_delivery": inspector.get("result_delivery"),
                "history_chat_interactions": chat_features_in_fixture,
            },
            "pinned_source": {
                "status": "source-only",
                "commit": source_commit,
                "affordances": [
                    "clickable-previous-next-pages",
                    "timestamp-hover-with-full-date",
                    "item-and-block-hover-details",
                    "clickable-tp-x-y-z-without-dimension",
                ],
                "source_refs": [
                    "command/page/Page.java",
                    "model/BlockPosition.java",
                    "model/Time.java",
                    "model/history/ItemHistory.java",
                    "model/history/BlockHistory.java",
                ],
            },
            "itemgraph_status": "implemented-with-separate-tests-not-in-current-native-replay",
            "itemgraph_evidence": ["QueryDispatcherTest", "FlowBrowserMenuTest", "FabricItemGraphPageDispatchTest"],
            "replay_status": "not-exercised-in-current-native-replay",
            "acceptance_status": "unresolved",
        },
        {
            "requirement_id": "issue-140-player-broken-container-contents",
            "owner_issue": 140,
            "feature": "player-caused-container-break-contents-evidence",
            "exact_release": {
                "status": "writer-class-confirmed-details-unproven"
                if "com/daqem/grieflogger/event/block/BreakContainerEvent.class" in remove_item_writers
                else "not-established",
                "action": "ItemAction.REMOVE_ITEM" if remove_item_writers else None,
                "writer_class": "com/daqem/grieflogger/event/block/BreakContainerEvent.class"
                if "com/daqem/grieflogger/event/block/BreakContainerEvent.class" in remove_item_writers else None,
                "verified_loaders": fixture.get("action_writer_audit", {}).get("verified_loaders", []),
                "break-boundary-details": "not-proven-by-action-writer-inventory",
            },
            "pinned_source": {
                "status": "source-only",
                "commit": source_commit,
                "finding": "BreakBlockEvent.breakBlock-calls-BreakContainerEvent.before-block-removal",
                "source_refs": [
                    "event/block/BreakBlockEvent.java",
                    "event/block/BreakContainerEvent.java",
                ],
            },
            "itemgraph_status": "implemented-with-cross-loader-conformance-fixture-not-in-current-native-replay",
            "itemgraph_evidence": ["ContainerBreakConformanceFixture", "ContainerBreakGameTests", "ContainerBreakEvidenceLinks"],
            "replay_status": "not-exercised-in-current-native-replay",
            "acceptance_status": "unresolved",
        },
    ]
    return rows


def _durability_contract_rows(registry: dict[str, Any], fixture: dict[str, Any]) -> list[dict[str, Any]]:
    source_commit = registry["audit_baseline"]["commit"]
    source_facts = {
        "transaction-boundaries": {
            "detail": "Database.executeQueue-serializes-a-drained-list-under-one-lock-then-commits;SQLException-rolls-back",
            "source_refs": ["database/Database.java"],
        },
        "queue-acceptance-vs-durable-persistence": {
            "detail": "Queue.add-returns-void-and-adds-to-unbounded-ConcurrentLinkedQueue;acceptance-is-not-a-durable-ack",
            "source_refs": ["database/queue/IQueue.java", "database/queue/Queue.java"],
        },
        "failure-retry-and-rejection": {
            "detail": "Queue.execute-polls-items-before-write;Database.executeQueue-rolls-back-on-SQLException-without-requeue;no-bounded-admission-or-retry-is-established-in-reviewed-paths",
            "source_refs": ["database/queue/Queue.java", "database/Database.java"],
        },
        "shutdown-drain-and-flush": {
            "detail": "ServerStoppedEvent-executes-both-queues-before-ThreadManager.shutdown;executor-and-scheduler-each-await-up-to-five-seconds-before-shutdownNow",
            "source_refs": ["event/ServerStoppedEvent.java", "thread/ThreadManager.java"],
        },
        "restart-recovery": {
            "detail": "no-durable-queue-recovery-proof-in-exact-fixture;reviewed-26.2-queue-paths-do-not-establish-a-restart-spool",
            "source_refs": ["database/queue/Queue.java", "event/ServerStoppedEvent.java"],
        },
    }
    itemgraph_evidence = {
        "transaction-boundaries": ["InternalObservationService.runTransaction", "InternalObservationServiceTest"],
        "queue-acceptance-vs-durable-persistence": [
            "InternalObservationService.submitAll", "InternalObservationServiceTest.testWorkerThreadDrainsAndPersistsInBackground",
        ],
        "failure-retry-and-rejection": [
            "InternalObservationService.requeueAuditBatch", "InternalObservationServiceTest.failedTransformationBatchIsSpooledAndReplayedAfterDatabaseReturns",
        ],
        "shutdown-drain-and-flush": [
            "InternalObservationService.stop", "InternalObservationServiceTest.saturatedQueueDrainsOnWorkerDuringShutdownAndReportsExplicitRejection",
        ],
        "restart-recovery": [
            "InternalObservationService.restorePendingEvidence", "InternalObservationServiceTest.startupRecoveryIoRunsOffCallerAndPersistsSavedEventsBeforeNewEvents",
        ],
    }
    return [
        {
            "requirement_id": f"issue-31-durability-{kind}",
            "owner_issue": 31,
            "feature": kind,
            "exact_release": {
                "status": "not-established-by-exact-release-fixture",
                "fixture_evidence": "fixture-describes-artifacts-schema-and-action-writers-not-transaction-queue-or-recovery-semantics",
            },
            "pinned_source": {
                "status": "source-only-research",
                "commit": source_commit,
                **source_facts[kind],
            },
            "itemgraph_status": "implemented-with-separate-tests-not-in-current-native-replay",
            "itemgraph_evidence": itemgraph_evidence[kind],
            "replay_status": "not-exercised-in-current-native-replay",
            "acceptance_status": "unresolved",
        }
        for kind in DURABILITY_REQUIREMENTS
    ]


def _validate_contract_rows(coverage: dict[str, Any], registry: dict[str, Any], fixture: dict[str, Any]) -> None:
    """Reject incomplete, duplicated, altered, or privacy-expanding contract rows."""
    expected_features = _feature_contract_rows(registry, fixture)
    expected_durability = _durability_contract_rows(registry, fixture)
    for field, expected in (("feature_requirements", expected_features), ("durability_requirements", expected_durability)):
        rows = coverage.get(field)
        if not isinstance(rows, list):
            raise differential.ReportError(f"coverage {field} is missing rows or has an invalid shape")
        ids = [row.get("requirement_id") if isinstance(row, dict) else None for row in rows]
        if any(not isinstance(identifier, str) for identifier in ids):
            raise differential.ReportError(f"coverage {field} contains a malformed requirement ID")
        expected_ids = [row["requirement_id"] for row in expected]
        if len(set(ids)) != len(ids):
            raise differential.ReportError(f"coverage {field} contains duplicate requirement IDs")
        if len(rows) != len(expected):
            raise differential.ReportError(f"coverage {field} is missing rows or has an invalid shape")
        if set(ids) != set(expected_ids):
            raise differential.ReportError(f"coverage {field} has missing or unexpected requirement IDs")
        expected_by_id = {row["requirement_id"]: row for row in expected}
        for row in rows:
            if row != expected_by_id[row["requirement_id"]]:
                raise differential.ReportError(
                    f"coverage {field} row {row['requirement_id']} is malformed, misclassified, or contains unapproved data")


def validate_coverage(coverage: dict[str, Any]) -> dict[str, Any]:
    """Validate sidecar identity and all pinned, redacted requirement rows."""
    if not isinstance(coverage, dict):
        raise differential.ReportError("coverage must be an object")
    registry, fixture_hash = differential.current_profile()
    if coverage.get("coverage_schema_version") != COVERAGE_SCHEMA_VERSION:
        raise differential.ReportError("coverage_schema_version is unsupported")
    if coverage.get("system") != "itemgraph" or coverage.get("runtime_mode") != "native_only":
        raise differential.ReportError("coverage must identify native-only ItemGraph")
    if coverage.get("compatibility_version") != registry["compatibility_version"]:
        raise differential.ReportError("coverage compatibility version is not pinned to the current profile")
    if coverage.get("source_profile_sha256") != registry["source_profile"]["sha256"]:
        raise differential.ReportError("coverage source profile hash is not pinned to the current profile")
    if coverage.get("release_fixture_sha256") != fixture_hash:
        raise differential.ReportError("coverage release fixture hash is not pinned to the current fixture")
    fixture = differential.read_json(differential.FIXTURE_PATH)
    _validate_contract_rows(coverage, registry, fixture)
    return coverage


def build_coverage(report: dict[str, Any]) -> dict[str, Any]:
    report = differential.validate_report(report, "itemgraph")
    if report["runtime_mode"] != "native_only":
        raise differential.ReportError("feature coverage requires a native-only ItemGraph report")

    registry, fixture_hash = differential.current_profile()
    release_fixture = differential.read_json(differential.FIXTURE_PATH)
    event_counts = Counter(event["action"] for event in report["events"])
    table_event_counts = Counter(event["compatibility_table"] for event in report["events"])
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

    action_tables = {
        table
        for tables in differential.ACTION_TABLES.values()
        for table in tables
    }
    table_families: list[dict[str, Any]] = []
    for table, columns in release_fixture["database"]["tables"].items():
        if table in action_tables:
            runtime_count = table_event_counts.get(table, 0)
            table_families.append({
                "table": table,
                "table_kind": "action-event",
                "release_column_count": len(columns),
                "coverage_basis": "normalized replay events mapped to this exact-release action table",
                "itemgraph_native_sources_observed": sorted({
                    event["source_table"]
                    for event in report["events"]
                    if table == event["compatibility_table"]
                }),
                "runtime_evidence_count": runtime_count,
                "coverage_status": "observed-in-replay" if runtime_count else "not-observed-in-replay",
                "owner_issue": 31,
            })
        else:
            representation = REFERENCE_TABLE_REPRESENTATIONS.get(table)
            if representation is None:
                raise differential.ReportError(f"release table has no ItemGraph disposition: {table}")
            reference_count = sum(
                1 for event in report["events"]
                if reference_field_observed(event, table)
            )
            table_families.append({
                "table": table,
                "table_kind": "reference-data",
                "release_column_count": len(columns),
                "coverage_basis": reference_coverage_basis(table),
                "itemgraph_native_representation": representation,
                "runtime_evidence_count": reference_count,
                "coverage_status": (
                    "actor-reference-only" if table == "users" and reference_count
                    else "represented-in-replay" if reference_count
                    else "not-observed-in-replay"
                ),
                "owner_issue": 31,
            })

    fixture_tables = set(release_fixture["database"]["tables"])
    classified_tables = {row["table"] for row in table_families}
    if classified_tables != fixture_tables:
        raise differential.ReportError("coverage report does not classify every exact-release database table")

    statuses = Counter(row["coverage_status"] for row in coverage_rows)
    release_writer_statuses = Counter(row["release_writer_status"] for row in coverage_rows)
    fixture = differential.read_json(differential.FIXTURE_PATH)
    output = {
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
            "release_table_count": len(table_families),
            "release_table_families_not_observed_in_replay": sum(
                row["coverage_status"] == "not-observed-in-replay"
                for row in table_families
            ),
            "release_event_tables_observed": sum(
                row["table_kind"] == "action-event" and row["runtime_evidence_count"] > 0
                for row in table_families
            ),
            "release_reference_tables_represented_in_replay": sum(
                row["coverage_status"] == "represented-in-replay"
                for row in table_families
            ),
            "release_reference_tables_actor_reference_only": sum(
                row["coverage_status"] == "actor-reference-only"
                for row in table_families
            ),
            "open_feature_requirements_unresolved": len(CONTRACT_REQUIREMENT_ISSUES),
            "durability_requirements_unresolved": len(DURABILITY_REQUIREMENTS),
        },
        "actions": coverage_rows,
        "table_families": table_families,
        "feature_requirements": _feature_contract_rows(registry, fixture),
        "durability_requirements": _durability_contract_rows(registry, fixture),
    }
    return validate_coverage(output)


def reference_field_observed(event: dict[str, Any], table: str) -> bool:
    if table == "users":
        return event["actor_ref"] is not None
    if table == "usernames":
        return event["action"] == "PLAYER_JOIN" and event["source_table"] == "ig_audit_events"
    if table == "levels":
        return event["dimension"] is not None
    if table == "materials":
        return event["item_id"] is not None or (
            event["action"] in {"PLACE_BLOCK", "BREAK_BLOCK", "INTERACT_BLOCK_ATTEMPT"}
            and event["subject_id"] is not None
        )
    if table == "entities":
        return event["action"] == "KILL_ENTITY" and event["subject_id"] is not None
    return False


def reference_coverage_basis(table: str) -> str:
    return {
        "users": "normalized replay events with an actor_ref; the identity value is redacted",
        "usernames": "normalized PLAYER_JOIN events signal UUID/name-history indexing; this event count is not a username-history row count and contains no names or UUIDs",
        "levels": "normalized replay events with a dimension value",
        "materials": "normalized replay events with an item_id or block-material subject_id",
        "entities": "KILL_ENTITY events with an entity subject_id",
    }[table]


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
    print(f"classified {coverage['summary']['registry_action_count']} ItemGraph actions and "
          f"{coverage['summary']['release_table_count']} release table families; "
          f"{coverage['summary']['actions_observed_in_replay']} observed, "
          f"{coverage['summary']['not_observed_in_replay']} not observed, "
          f"{coverage['summary']['release_actions_without_writer']} action writers absent from the exact release, "
          f"{coverage['summary']['release_table_families_not_observed_in_replay']} table families unobserved")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
