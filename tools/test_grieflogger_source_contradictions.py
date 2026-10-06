#!/usr/bin/env python3
"""Keep researched GriefLogger source/documentation conflicts machine-readable."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "docs/grieflogger-fixtures/source-contradictions.json"
EXPECTED_CASES = {
    "lookup-radius-doc-vs-source",
    "chat-command-storage-vs-lookup",
    "ender-enum-vs-release-writer",
}
EXPECTED_DECISIONS = {
    "lookup-radius-doc-vs-source": (
        25,
        "ItemGraph requires radius and clamps it to 1..1024 blocks to bound the query.",
    ),
    "chat-command-storage-vs-lookup": (
        27,
        "ItemGraph exposes its stored chat and command evidence through permission-checked native audit lookup and labels it as an ItemGraph extension.",
    ),
    "ender-enum-vs-release-writer": (
        76,
        "Use the exact 1.2.10-1.21.1 bytecode as release authority; classify both actions unsupported-no-writer and keep ItemGraph Ender deltas as a native extension.",
    ),
}
EXPECTED_CLAIMS = {
    "lookup-radius-doc-vs-source": [
        ("published_documentation", "https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/filters",
         "A radius filter is required for a bounded lookup."),
        ("pinned_source_26_2", "https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java",
         "The command accepts a lookup with no radius filter."),
    ],
    "chat-command-storage-vs-lookup": [
        ("pinned_source_26_2", "https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ChatRepository.java",
         "Chat records are stored in the source chats table."),
        ("pinned_source_26_2", "https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/CommandRepository.java",
         "Command records are stored in the source commands table."),
        ("pinned_source_26_2", "https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java",
         "The GriefLogger in-game lookup merge omits chat and command tables."),
    ],
    "ender-enum-vs-release-writer": [
        ("pinned_source_26_2", "https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/ItemAction.java",
         "The newer source enum declares ADD_ITEM_ENDER and REMOVE_ITEM_ENDER."),
        ("exact_1_21_1_release_bytes", "docs/grieflogger-fixtures/1.2.10-1.21.1.json#ender_action_writer_audit",
         "Neither exact-release jar contains an Ender action writer."),
    ],
}
EXPECTED_VERIFICATION = {
    "lookup-radius-doc-vs-source": [
        ("neoforge/src/test/java/com/itemgraph/command/ItemGraphCommandsHelpTest.java", "java_method",
         "malformedLookupFiltersReturnExactFailuresOnBothRootsAndForms"),
    ],
    "chat-command-storage-vs-lookup": [
        ("neoforge/src/test/java/com/itemgraph/query/AuditEventQueryServiceTest.java", "java_method",
         "migrationCreatesNativeAuditLedgerAndQueriesFilters"),
        ("neoforge/src/test/java/com/itemgraph/command/QueryDispatcherTest.java", "java_method",
         "testTextQueryRechecksPermissionBeforeDelivery"),
        ("docs/GRIEFLOGGER_PARITY.md", "text", "ItemGraph's unified lookup intentionally extends"),
    ],
    "ender-enum-vs-release-writer": [
        ("tools/validate_grieflogger_release_fixture.py", "text", "Ender action writer audit must be present"),
        ("docs/grieflogger-fixtures/1.2.10-1.21.1.json", "text", "\"ender_action_writer_audit\""),
    ],
}


class GriefLoggerSourceContradictionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_pins_source_and_release_profile(self) -> None:
        profile = self.fixture["source_profile"]
        self.assertEqual(1, self.fixture["schema_version"])
        self.assertEqual("1.2.10-1.21.1", profile["release"])
        self.assertEqual("26.2", profile["source_ref"])
        self.assertEqual("d315098b3f37317a5cddfbd75086f4f912f16a83", profile["source_commit"])
        self.assertEqual("docs/grieflogger-fixtures/1.2.10-1.21.1.json", profile["release_fixture"])
        self.assertTrue((ROOT / "docs/grieflogger-fixtures/1.2.10-1.21.1.json").is_file())

    def test_each_conflict_has_two_claims_a_resolution_owner_and_live_verification_anchor(self) -> None:
        cases = self.fixture["cases"]
        self.assertEqual(EXPECTED_CASES, {case["case_id"] for case in cases})
        self.assertEqual(len(cases), len({case["case_id"] for case in cases}))
        for case in cases:
            with self.subTest(case=case["case_id"]):
                self.assertGreaterEqual(len(case["claims"]), 2)
                self.assertEqual(EXPECTED_DECISIONS[case["case_id"]][0], case["owner_issue"])
                self.assertEqual(EXPECTED_DECISIONS[case["case_id"]][1], case["decision"])
                self.assertEqual(EXPECTED_CLAIMS[case["case_id"]], [
                    (claim["authority"], claim["reference"], claim["claim"])
                    for claim in case["claims"]
                ])
                self.assertEqual(EXPECTED_VERIFICATION[case["case_id"]], [
                    (anchor["path"], anchor["kind"], anchor["symbol"])
                    for anchor in case["verification"]
                ])
                for claim in case["claims"]:
                    reference = claim["reference"]
                    self.assertTrue(reference and claim["claim"])
                    parsed = urlparse(reference)
                    if parsed.scheme:
                        self.assertEqual("https", parsed.scheme)
                        self.assertTrue(parsed.netloc)
                    else:
                        source_path = reference.split("#", 1)[0]
                        self.assertTrue((ROOT / source_path).is_file(), reference)
                self.assertTrue(case["decision"])
                self.assertIs(type(case["owner_issue"]), int)
                self.assertTrue(case["verification"])
                for anchor in case["verification"]:
                    source = (ROOT / anchor["path"]).read_text(encoding="utf-8")
                    if anchor["kind"] == "java_method":
                        self.assertRegex(source, rf"\bvoid\s+{re.escape(anchor['symbol'])}\s*\(")
                    elif anchor["kind"] == "text":
                        self.assertIn(anchor["symbol"], source)
                    else:
                        self.fail(f"unsupported verification anchor kind: {anchor['kind']}")


if __name__ == "__main__":
    unittest.main()
