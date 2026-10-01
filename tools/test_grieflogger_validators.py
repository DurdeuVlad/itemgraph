#!/usr/bin/env python3
"""Regression tests for GriefLogger JSON and action-ID validation."""

from __future__ import annotations

import hashlib
import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load validator module {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


profile = load_module("validate_grieflogger_profile", ROOT / "tools" / "validate_grieflogger_profile.py")
fixture_validator = load_module(
    "validate_grieflogger_release_fixture", ROOT / "tools" / "validate_grieflogger_release_fixture.py"
)


class GriefLoggerValidatorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = profile.load_json(ROOT / "docs" / "GRIEFLOGGER_COMPATIBILITY.json")
        self.fixture = fixture_validator.load_fixture()

    def reseal_fixture(self) -> None:
        self.fixture["fixture_sha256"] = hashlib.sha256(
            fixture_validator.canonical_fixture(self.fixture)
        ).hexdigest()

    def test_profile_rejects_boolean_source_action_id(self) -> None:
        row = next(row for row in self.registry["actions"] if row.get("grieflogger") == "ADD_ITEM")
        row["source_id"] = True
        with self.assertRaisesRegex(profile.ProfileError, "source ID must be an integer"):
            profile.validate_registry(self.registry)

    def test_profile_rejects_float_release_action_id(self) -> None:
        row = next(row for row in self.registry["actions"] if row.get("grieflogger") == "ADD_ITEM")
        row["release_action_id"] = 1.0
        with self.assertRaisesRegex(profile.ProfileError, "release action ID must be an integer or null"):
            profile.validate_registry(self.registry)

    def test_fixture_rejects_boolean_action_id_even_when_digest_is_valid(self) -> None:
        row = next(row for row in self.fixture["actions"]["item"] if row["name"] == "ADD_ITEM")
        row["id"] = True
        self.reseal_fixture()
        with self.assertRaisesRegex(fixture_validator.FixtureError, "action IDs must be integers"):
            fixture_validator.validate_static(self.fixture)

    def test_fixture_rejects_float_action_id_even_when_digest_is_valid(self) -> None:
        row = next(row for row in self.fixture["actions"]["block"] if row["name"] == "BREAK_BLOCK")
        row["id"] = 0.0
        self.reseal_fixture()
        with self.assertRaisesRegex(fixture_validator.FixtureError, "action IDs must be integers"):
            fixture_validator.validate_static(self.fixture)

    def test_fixture_rejects_float_schema_version(self) -> None:
        self.fixture["fixture_schema_version"] = 2.0
        self.reseal_fixture()
        with self.assertRaisesRegex(fixture_validator.FixtureError, "fixture_schema_version must be integer 2"):
            fixture_validator.validate_static(self.fixture)

    def test_both_json_loaders_reject_duplicate_object_keys(self) -> None:
        duplicate_key_document = '{"BlockAction.BREAK_BLOCK": [], "BlockAction.BREAK_BLOCK": []}'
        for module in (profile, fixture_validator):
            with self.subTest(module=module.__name__):
                with self.assertRaisesRegex(ValueError, "duplicate JSON object key"):
                    module.parse_json_document(duplicate_key_document)


if __name__ == "__main__":
    unittest.main()
