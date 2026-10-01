#!/usr/bin/env python3
"""Validate the exact published GriefLogger 1.2.10-1.21.1 release fixture.

The checked-in fixture is metadata only; no third-party jar is vendored.  The
validator always checks the pinned release facts and can additionally inspect
the official Modrinth bytes with ``--check-remote`` (or local jar paths).
Inspection is read-only and never loads or executes a mod.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "docs" / "grieflogger-fixtures" / "1.2.10-1.21.1.json"
PARITY_PATH = ROOT / "docs" / "GRIEFLOGGER_PARITY.md"
SOURCE_AUDIT_PATH = ROOT / "docs" / "GRIEFLOGGER_SOURCE_AUDIT.md"
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SHA512_RE = re.compile(r"^[0-9a-f]{128}$")


class FixtureError(RuntimeError):
    pass


def reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def parse_json_document(source: str) -> Any:
    return json.loads(source, object_pairs_hook=reject_duplicate_json_keys)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise FixtureError(message)


def load_fixture() -> dict[str, Any]:
    try:
        fixture = parse_json_document(FIXTURE_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise FixtureError(f"cannot read {FIXTURE_PATH.relative_to(ROOT)}: {exc}") from exc
    require(isinstance(fixture, dict), "fixture must be a JSON object")
    return fixture


def canonical_fixture(fixture: dict[str, Any]) -> bytes:
    unsigned = {key: value for key, value in fixture.items() if key != "fixture_sha256"}
    return json.dumps(unsigned, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def validate_static(fixture: dict[str, Any]) -> None:
    require(type(fixture.get("fixture_schema_version")) is int and fixture["fixture_schema_version"] == 2,
            "fixture_schema_version must be integer 2")
    digest = fixture.get("fixture_sha256")
    require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), "fixture_sha256 must be lowercase SHA-256")
    require(hashlib.sha256(canonical_fixture(fixture)).hexdigest() == digest, "fixture_sha256 does not match canonical fixture")

    project = fixture.get("project")
    require(isinstance(project, dict), "project must be an object")
    require(project.get("mod_id") == "grieflogger", "fixture mod_id must be grieflogger")
    require(project.get("version") == "1.2.10-1.21.1", "fixture version must be 1.2.10-1.21.1")
    require(project.get("minecraft") == "1.21.1", "fixture Minecraft version must be 1.21.1")

    source_tag = fixture.get("source_tag")
    require(isinstance(source_tag, dict), "source_tag must be an object")
    require(source_tag.get("ref") == "1.2.10-1.21.1", "source tag ref drifted")
    require(re.fullmatch(r"[0-9a-f]{40}", source_tag.get("commit", "")) is not None, "source tag commit must be a full SHA-1")
    require("published artifact metadata" in source_tag.get("authority", ""), "artifact authority rule is missing")

    artifacts = fixture.get("artifacts")
    require(isinstance(artifacts, dict) and set(artifacts) == {"fabric", "neoforge"}, "fixture must contain Fabric and NeoForge artifacts")
    for loader, artifact in artifacts.items():
        require(isinstance(artifact, dict), f"{loader} artifact must be an object")
        require(artifact.get("loader") == loader, f"{loader} artifact loader mismatch")
        require(artifact.get("filename", "").endswith(f"-{loader}.jar"), f"{loader} filename must identify its loader")
        require(
            artifact.get("url", "").startswith("https://cdn.modrinth.com/data/8oGVUFuX/versions/")
            and f"/versions/{artifact.get('modrinth_version_id')}/" in artifact["url"]
            and artifact["url"].endswith(artifact["filename"])
            and isinstance(artifact.get("modrinth_file_id"), str)
            and bool(artifact["modrinth_file_id"]),
            f"{loader} artifact URL must be the pinned official Modrinth CDN URL",
        )
        require(isinstance(artifact.get("size_bytes"), int) and artifact["size_bytes"] > 0, f"{loader} size_bytes must be positive")
        require(SHA1_RE.fullmatch(artifact.get("sha1", "")) is not None, f"{loader} sha1 must be lowercase SHA-1")
        require(SHA256_RE.fullmatch(artifact.get("sha256", "")) is not None, f"{loader} sha256 must be lowercase SHA-256")
        require(SHA512_RE.fullmatch(artifact.get("sha512", "")) is not None, f"{loader} sha512 must be lowercase SHA-512")
        require(artifact.get("java_major") == 21, f"{loader} artifact must target Java 21")
        require(artifact.get("class_count", 0) > 0, f"{loader} class_count must be positive")
        require(artifact.get("mixin_contract", {}).get("required") is True, f"{loader} mixins must remain required")
        require(artifact.get("mixin_contract", {}).get("default_require") == 1, f"{loader} mixin defaultRequire must remain 1")

    ender_audit = fixture.get("ender_action_writer_audit")
    require(isinstance(ender_audit, dict), "Ender action writer audit must be present")
    require(ender_audit.get("result") == "unsupported-no-writer", "Ender action writer result changed")
    require(ender_audit.get("enum_class") == "com/daqem/grieflogger/model/action/ItemAction.class",
            "Ender action enum class changed")
    require(ender_audit.get("actions") == {"ADD_ITEM_ENDER": 9, "REMOVE_ITEM_ENDER": 10},
            "Ender action IDs changed")
    require(ender_audit.get("expected_classfiles_containing_symbols") == [ender_audit["enum_class"]],
            "Ender action symbol classfile expectation changed")
    require(ender_audit.get("expected_classfiles_referencing_action_constants") == [ender_audit["enum_class"]],
            "Ender action constant reference expectation changed")
    require(ender_audit.get("expected_selector_callsites") == [
                "com/daqem/grieflogger/model/action/Actions.class#<clinit>->values",
                "com/daqem/grieflogger/model/action/ItemAction.class#fromId->values",
            ],
            "Ender action selector callsite expectation changed")
    require(ender_audit.get("expected_id_factory_callsites") == [
                "com/daqem/grieflogger/model/history/ItemHistory.class#<init>->fromId",
            ],
            "Ender action ID factory caller changed")
    require(ender_audit.get("source_audit_commit") == "d315098b3f37317a5cddfbd75086f4f912f16a83",
            "Ender action source audit commit changed")
    require("no writer references outside the enum" in ender_audit.get("source_audit_note", ""),
            "Ender no-writer source evidence is missing")

    actions = fixture.get("actions")
    require(isinstance(actions, dict), "actions must be an object")
    expected_actions = {
        "block": [("BREAK_BLOCK", 0), ("PLACE_BLOCK", 1), ("INTERACT_BLOCK", 2), ("KILL_ENTITY", 3)],
        "item": [("REMOVE_ITEM", 0), ("ADD_ITEM", 1), ("DROP_ITEM", 2), ("PICKUP_ITEM", 3), ("CRAFT_ITEM", 4), ("BREAK_ITEM", 5), ("CONSUME_ITEM", 6), ("THROW_ITEM", 7), ("SHOOT_ITEM", 8), ("ADD_ITEM_ENDER", 9), ("REMOVE_ITEM_ENDER", 10)],
        "session": [("JOIN", 0), ("QUIT", 1)],
    }
    for category, expected in expected_actions.items():
        actual = [(row.get("name"), row.get("id")) for row in actions.get(category, [])]
        require(all(type(row.get("id")) is int for row in actions.get(category, [])),
                f"{category} action IDs must be integers")
        require(actual == expected, f"{category} action IDs/names do not match the release fixture")

    action_writer_audit = fixture.get("action_writer_audit")
    require(isinstance(action_writer_audit, dict), "action writer audit must be present")
    require(action_writer_audit.get("verified_loaders") == ["fabric", "neoforge"],
            "action writer audit must cover Fabric and NeoForge")
    require(action_writer_audit.get("source_ref") == "26.2"
            and action_writer_audit.get("source_commit") == "d315098b3f37317a5cddfbd75086f4f912f16a83",
            "action writer audit source provenance changed")
    writer_references = action_writer_audit.get("expected_action_field_access_classes")
    expected_writer_keys = {
        f"{enum}.{name}"
        for enum, rows in expected_actions.items()
        for name, _action_id in rows
    }
    expected_writer_keys = {
        key.replace("block.", "BlockAction.").replace("item.", "ItemAction.").replace("session.", "SessionAction.")
        for key in expected_writer_keys
    }
    require(isinstance(writer_references, dict) and set(writer_references) == expected_writer_keys,
            "action writer audit must enumerate each exact-release enum action exactly once")
    for action_key, classfiles in writer_references.items():
        require(isinstance(classfiles, list) and classfiles == sorted(set(classfiles)),
                f"{action_key} writer class references must be unique and sorted")
        enum_name = action_key.split(".", 1)[0]
        enum_class = f"com/daqem/grieflogger/model/action/{enum_name}.class"
        require(enum_class not in classfiles,
                f"{action_key} writer class references must exclude the enum declaration")
        require(bool(classfiles) != action_key.endswith(("ADD_ITEM_ENDER", "REMOVE_ITEM_ENDER")),
                f"{action_key} writer presence conflicts with the Ender no-writer audit")
    absent_release_actions = action_writer_audit.get("absent_release_actions")
    require(absent_release_actions == {
        "INTERACT_ENTITY": {
            "source_enum": "BlockAction",
            "source_id": 4,
            "reason_code": "NO_ACTION_ID_OR_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE",
            "evidence_issue": 75,
        }
    }, "exact-release action absence evidence changed")
    require(not any(name == "INTERACT_ENTITY" for category in expected_actions.values() for name, _action_id in category),
            "INTERACT_ENTITY must remain absent from the exact-release action ID catalog")

    expected_tables = {
        "blocks": ["time", "user", "level", "x", "y", "z", "type", "action"],
        "containers": ["time", "user", "level", "x", "y", "z", "type", "data", "amount", "action"],
        "items": ["time", "user", "level", "x", "y", "z", "type", "data", "amount", "action"],
        "sessions": ["time", "user", "level", "x", "y", "z", "action"],
        "chats": ["time", "user", "level", "x", "y", "z", "message"],
        "commands": ["time", "user", "level", "x", "y", "z", "command"],
        "users": ["id", "name", "uuid"],
        "usernames": ["id", "time", "uuid", "name"],
        "levels": ["id", "name"],
        "materials": ["id", "name"],
        "entities": ["id", "name"],
    }
    require(fixture.get("database", {}).get("tables") == expected_tables, "database table/column fixture changed")
    require(fixture.get("database", {}).get("mutation_contract", "").startswith("ItemGraph reads"), "database mutation contract is missing")

    commands = fixture.get("commands", {})
    require(commands.get("root") == "/grieflogger" and commands.get("aliases") == ["/gl"], "command root or alias changed")
    require(commands.get("subcommands") == ["inspect", "lookup", "page"], "command subcommands changed")
    require(commands.get("maximum_filters") == 5 and commands.get("combination") == "AND", "lookup filter contract changed")

    keys = fixture.get("configuration", {}).get("keys", {})
    require(keys.get("useMysql") is False and keys.get("useIndexes") is True, "database configuration defaults changed")
    require(keys.get("maxPageSize") == 10 and keys.get("queueFrequency") == 20 and keys.get("helloFrequency") == 600, "operational configuration defaults changed")
    require(fixture.get("inspector", {}).get("suppresses_gameplay_actions") is True, "inspector suppression contract changed")

    comparison = fixture.get("source_comparison")
    require(isinstance(comparison, dict), "source_comparison must be an object")
    require(comparison.get("pinned_source_commit") == "d315098b3f37317a5cddfbd75086f4f912f16a83", "source comparison commit changed")
    differences = comparison.get("differences")
    require(isinstance(differences, list) and len(differences) == 2, "source comparison must retain both target differences")
    require(
        {(row.get("area"), row.get("status"), row.get("owner_issue")) for row in differences}
        == {("runtime_target", "unresolved", 54), ("behavioral_parity", "unresolved", 31)},
        "source comparison differences or owner issues changed",
    )


def validate_documentation_links(fixture: dict[str, Any]) -> None:
    try:
        documentation = "\n".join(
            [PARITY_PATH.read_text(encoding="utf-8"), SOURCE_AUDIT_PATH.read_text(encoding="utf-8")]
        )
    except OSError as exc:
        raise FixtureError(f"cannot read parity documentation for link validation: {exc}") from exc
    required_links = [
        fixture["project"]["release_pages"]["curseforge"],
        "https://api.modrinth.com/v2/project/8oGVUFuX/version",
        "https://github.com/DAQEM/GriefLogger/commit/d315098b3f37317a5cddfbd75086f4f912f16a83",
        "grieflogger-fixtures/1.2.10-1.21.1.json",
        "https://github.com/DurdeuVlad/itemgraph/issues/54",
    ]
    for link in required_links:
        require(link in documentation, f"documentation is missing authoritative link: {link}")


def classfile_action_references(
        bytecode: bytes, ender_audit: dict[str, Any], include_callsites: bool = True
        ) -> tuple[set[str], set[str], set[str], dict[str, set[str]]]:
    """Return action field accesses and enum selector/factory callsites."""
    if len(bytecode) < 10 or bytecode[:4] != b"\xca\xfe\xba\xbe":
        return set(), set(), set(), {}
    constant_pool_count = int.from_bytes(bytecode[8:10], "big")
    pool: list[tuple[int, Any] | None] = [None] * constant_pool_count
    cursor = 10
    index = 1
    while index < constant_pool_count:
        tag = bytecode[cursor]
        cursor += 1
        if tag == 1:
            length = int.from_bytes(bytecode[cursor:cursor + 2], "big")
            cursor += 2
            value = bytecode[cursor:cursor + length].decode("utf-8", errors="replace")
            cursor += length
            pool[index] = (tag, value)
        elif tag in (3, 4):
            cursor += 4
        elif tag in (5, 6):
            cursor += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            value = int.from_bytes(bytecode[cursor:cursor + 2], "big")
            cursor += 2
            pool[index] = (tag, value)
        elif tag in (9, 10, 11, 12, 17, 18):
            first = int.from_bytes(bytecode[cursor:cursor + 2], "big")
            second = int.from_bytes(bytecode[cursor + 2:cursor + 4], "big")
            cursor += 4
            pool[index] = (tag, (first, second))
        elif tag == 15:
            cursor += 3
        else:
            raise FixtureError(f"unsupported classfile constant-pool tag: {tag}")
        index += 1

    def utf8(cp_index: int) -> str:
        entry = pool[cp_index]
        return entry[1] if entry is not None and entry[0] == 1 else ""

    def class_name(cp_index: int) -> str:
        entry = pool[cp_index]
        return utf8(entry[1]) if entry is not None and entry[0] == 7 else ""

    def name_and_type(cp_index: int) -> tuple[str, str]:
        entry = pool[cp_index]
        if entry is None or entry[0] != 12:
            return "", ""
        return utf8(entry[1][0]), utf8(entry[1][1])

    def u2(offset: int) -> int:
        return int.from_bytes(bytecode[offset:offset + 2], "big")

    def u4(offset: int) -> int:
        return int.from_bytes(bytecode[offset:offset + 4], "big")

    enum_owner = ender_audit["enum_class"][:-6]
    action_constants = set(ender_audit["actions"])
    header_offset = cursor
    this_class = class_name(u2(header_offset + 2))
    cursor = header_offset + 6
    interfaces_count = u2(cursor)
    cursor += 2 + interfaces_count * 2

    action_field_reference_classes: set[str] = set()
    action_field_access_classes_by_name = {action: set() for action in action_constants}
    for entry in pool:
        if entry is None or entry[0] != 9:
            continue
        owner_index, name_and_type_index = entry[1]
        name, _descriptor = name_and_type(name_and_type_index)
        if class_name(owner_index) == enum_owner and name in action_constants:
            action_field_reference_classes.add(this_class + ".class")
            # The aggregate set is retained for the Ender enum-only check. The
            # per-action matrix below records executable field instructions.

    def skip_attributes(offset: int, count: int) -> int:
        for _ in range(count):
            length = u4(offset + 2)
            offset += 6 + length
        return offset

    def skip_members(offset: int, count: int) -> int:
        for _ in range(count):
            attribute_count = u2(offset + 6)
            offset = skip_attributes(offset + 8, attribute_count)
        return offset

    fields_count = u2(cursor)
    cursor = skip_members(cursor + 2, fields_count)
    methods_count = u2(cursor)
    cursor += 2
    selector_callsites: set[str] = set()
    id_factory_callsites: set[str] = set()
    for _ in range(methods_count):
        method_name = utf8(u2(cursor + 2))
        attribute_count = u2(cursor + 6)
        attribute_offset = cursor + 8
        for _ in range(attribute_count):
            attribute_name = utf8(u2(attribute_offset))
            attribute_length = u4(attribute_offset + 2)
            payload_offset = attribute_offset + 6
            if attribute_name == "Code":
                code_length = u4(payload_offset + 4)
                code_start = payload_offset + 8
                code = bytecode[code_start:code_start + code_length]
                pc = 0
                while pc < len(code):
                    opcode = code[pc]
                    if opcode in (0xB2, 0xB3, 0xB4, 0xB5) and pc + 2 < len(code):  # field access
                        reference_index = int.from_bytes(code[pc + 1:pc + 3], "big")
                        reference = pool[reference_index]
                        if reference is not None and reference[0] == 9:
                            owner_index, name_and_type_index = reference[1]
                            field_name, descriptor = name_and_type(name_and_type_index)
                            if (
                                opcode == 0xB2
                                and class_name(owner_index) == enum_owner
                                and field_name in action_constants
                                and descriptor == f"L{enum_owner};"
                            ):
                                action_field_access_classes_by_name[field_name].add(this_class + ".class")
                    if include_callsites and opcode == 0xB8 and pc + 2 < len(code):  # invokestatic
                        reference_index = int.from_bytes(code[pc + 1:pc + 3], "big")
                        reference = pool[reference_index]
                        if reference is not None and reference[0] in (10, 11):
                            owner_index, name_and_type_index = reference[1]
                            called_name, _descriptor = name_and_type(name_and_type_index)
                            if class_name(owner_index) == enum_owner and called_name in {"values", "valueOf"}:
                                selector_callsites.add(
                                    f"{this_class}.class#{method_name}->{called_name}"
                                )
                            elif class_name(owner_index) == enum_owner and called_name == "fromId":
                                id_factory_callsites.add(f"{this_class}.class#{method_name}->fromId")
                    if opcode == 0xAA:  # tableswitch
                        padding = (4 - ((pc + 1) % 4)) % 4
                        table = pc + 1 + padding
                        low = int.from_bytes(code[table + 4:table + 8], "big", signed=True)
                        high = int.from_bytes(code[table + 8:table + 12], "big", signed=True)
                        pc = table + 12 + max(0, high - low + 1) * 4
                    elif opcode == 0xAB:  # lookupswitch
                        padding = (4 - ((pc + 1) % 4)) % 4
                        table = pc + 1 + padding
                        pairs = int.from_bytes(code[table + 4:table + 8], "big", signed=True)
                        pc = table + 8 + max(0, pairs) * 8
                    elif opcode == 0xC4:  # wide
                        pc += 6 if code[pc + 1] == 0x84 else 4
                    else:
                        length = 1
                        if opcode in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                                      0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC):
                            length = 2
                        elif opcode in (0x11, 0x13, 0x14, 0x84,
                                        *range(0x99, 0xA9), *range(0xB2, 0xB9),
                                        0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7):
                            length = 3
                        elif opcode in (0xB9, 0xBA, 0xC8, 0xC9):
                            length = 5
                        elif opcode == 0xC5:
                            length = 4
                        pc += length
            attribute_offset = payload_offset + attribute_length
        cursor = attribute_offset
    return (
        action_field_reference_classes,
        selector_callsites,
        id_factory_callsites,
        action_field_access_classes_by_name,
    )


def verify_jar(loader: str, artifact: dict[str, Any], path: Path,
               fixture: dict[str, Any]) -> None:
    ender_audit = fixture["ender_action_writer_audit"]
    require(path.is_file(), f"{loader} jar does not exist: {path}")
    require(path.stat().st_size == artifact["size_bytes"], f"{loader} jar size does not match the release record")
    require(hashlib.sha1(path.read_bytes()).hexdigest() == artifact["sha1"], f"{loader} jar SHA-1 does not match the release record")
    digest256 = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest256.update(chunk)
    require(digest256.hexdigest() == artifact["sha256"], f"{loader} jar SHA-256 does not match the release record")
    digest = hashlib.sha512()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    require(digest.hexdigest() == artifact["sha512"], f"{loader} jar SHA-512 does not match the release record")

    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        class_count = sum(name.endswith(".class") for name in names)
        require(class_count == artifact["class_count"], f"{loader} class count does not match the release fixture")
        symbol_classes = set()
        enum_constant_reference_classes = set()
        action_audit = fixture.get("action_writer_audit", {})
        expected_action_references = action_audit.get("expected_action_field_access_classes", {})
        grouped_actions: dict[str, set[str]] = {}
        for action_key in expected_action_references:
            enum_name, action_name = action_key.split(".", 1)
            grouped_actions.setdefault(enum_name, set()).add(action_name)
        actual_action_references: dict[str, set[str]] = {key: set() for key in expected_action_references}
        dynamic_selector_callsites = set()
        id_factory_callsites = set()
        for name in names:
            if not name.endswith(".class"):
                continue
            bytecode = jar.read(name)
            field_references, dynamic_selectors, id_factory_callers, references_by_action = classfile_action_references(
                bytecode, ender_audit
            )
            if field_references:
                enum_constant_reference_classes.add(name)
            dynamic_selector_callsites.update(dynamic_selectors)
            id_factory_callsites.update(id_factory_callers)
            for enum_name, action_names in grouped_actions.items():
                enum_class = f"com/daqem/grieflogger/model/action/{enum_name}.class"
                enum_context = {"enum_class": enum_class, "actions": {action: 0 for action in action_names}}
                _all_references, _selectors, _factories, references_by_action = classfile_action_references(
                    bytecode, enum_context, include_callsites=False
                )
                for action_name, classfiles in references_by_action.items():
                    actual_action_references[f"{enum_name}.{action_name}"].update(
                        classfile for classfile in classfiles if classfile != enum_class
                    )
            if any(symbol.encode("ascii") in bytecode for symbol in ender_audit["actions"]):
                symbol_classes.add(name)
        require(
            sorted(symbol_classes) == ender_audit["expected_classfiles_containing_symbols"],
            f"{loader} Ender action symbol references are not limited to the enum: {sorted(symbol_classes)}",
        )
        require(sorted(enum_constant_reference_classes) == ender_audit["expected_classfiles_referencing_action_constants"],
                f"{loader} Ender action field references are not limited to the enum: {sorted(enum_constant_reference_classes)}")
        require(sorted(dynamic_selector_callsites) == ender_audit["expected_selector_callsites"],
                f"{loader} ItemAction.values/valueOf callsites changed: {sorted(dynamic_selector_callsites)}")
        require(sorted(id_factory_callsites) == ender_audit["expected_id_factory_callsites"],
                f"{loader} ItemAction.fromId callers changed: {sorted(id_factory_callsites)}")
        for action, expected_classes in expected_action_references.items():
            actual_classes = sorted(actual_action_references[action])
            require(
                actual_classes == expected_classes,
                f"{loader} {action} executable field-access classes changed: {actual_classes}",
            )
        require("com/daqem/grieflogger/model/action/BlockAction.class" in names, f"{loader} action classes are missing")
        require("com/daqem/grieflogger/database/Database.class" in names, f"{loader} database class is missing")
        require("com/mysql/cj/Constants.class" in names, f"{loader} embedded MySQL driver is missing")
        require("org/sqlite/JDBC.class" in names, f"{loader} embedded SQLite driver is missing")
        properties = jar.read("META-INF/maven/org.xerial/sqlite-jdbc/pom.properties").decode("utf-8")
        require("version=3.47.2.0" in properties, f"{loader} embedded SQLite version changed")
        constants = jar.read("com/mysql/cj/Constants.class")
        require(b"mysql-connector-j-8.4.0" in constants and b"8.4.0" in constants, f"{loader} embedded MySQL version changed")
        if loader == "fabric":
            metadata = json.loads(jar.read("fabric.mod.json"))
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
            require(metadata.get("id") == "grieflogger" and metadata.get("version") == "1.2.10-1.21.1", "Fabric mod metadata changed")
            require(metadata.get("depends") == artifact["dependencies"], "Fabric dependency metadata changed")
            require(metadata.get("entrypoints", {}).get("server") == artifact["entrypoints"], "Fabric server entrypoint changed")
            require(metadata.get("mixins") == artifact["mixins"], "Fabric mixin metadata changed")
            require("Fabric-Minecraft-Version: 1.21.1" in manifest and "Fabric-Loader-Version: 0.19.3" in manifest, "Fabric manifest target changed")
        else:
            metadata = jar.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
            require('modId = "grieflogger"' in metadata and 'version = "1.2.10-1.21.1"' in metadata, "NeoForge mod metadata changed")
            require('versionRange = "[1.21.1,)"' in metadata and 'modId = "supermartijn642configlib"' in metadata, "NeoForge dependency metadata changed")
        for mixin in artifact["mixins"]:
            mixin_data = json.loads(jar.read(mixin))
            require(mixin_data.get("required") is True and mixin_data.get("compatibilityLevel") == "JAVA_21", f"{loader} mixin contract changed: {mixin}")
            require(mixin_data.get("injectors", {}).get("defaultRequire") == 1, f"{loader} mixin defaultRequire changed: {mixin}")
        common = json.loads(jar.read("grieflogger-common.mixins.json"))
        require(common.get("mixins") == artifact["mixin_contract"]["common"], f"{loader} common mixin classes changed")
        specific_name = "grieflogger-fabric.mixins.json" if loader == "fabric" else "grieflogger-neoforge.mixins.json"
        specific = json.loads(jar.read(specific_name))
        require(specific.get("mixins") == artifact["mixin_contract"]["loader_specific"], f"{loader} loader-specific mixin classes changed")
    print(
        f"Verified {loader} release jar: sha256={artifact['sha256']} "
        f"classCount={artifact['class_count']} actionRowsVerified={len(expected_action_references)}"
    )


def verify_remote(fixture: dict[str, Any]) -> None:
    with tempfile.TemporaryDirectory(prefix="itemgraph-grieflogger-fixture-") as directory:
        for loader, artifact in fixture["artifacts"].items():
            destination = Path(directory) / artifact["filename"]
            request = urllib.request.Request(artifact["url"], headers={"User-Agent": "itemgraph-grieflogger-fixture-validator"})
            try:
                with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as stream:
                    stream.write(response.read())
            except OSError as exc:
                raise FixtureError(f"cannot download {loader} release fixture: {exc}") from exc
            verify_jar(loader, artifact, destination, fixture)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fabric-jar", type=Path)
    parser.add_argument("--neoforge-jar", type=Path)
    parser.add_argument("--check-remote", action="store_true")
    args = parser.parse_args()
    try:
        fixture = load_fixture()
        validate_static(fixture)
        validate_documentation_links(fixture)
        paths = {"fabric": args.fabric_jar, "neoforge": args.neoforge_jar}
        for loader, path in paths.items():
            if path is not None:
                verify_jar(loader, fixture["artifacts"][loader], path, fixture)
        if args.check_remote or os.environ.get("GRIEFLOGGER_RELEASE_FIXTURE_CHECK_REMOTE") == "1":
            verify_remote(fixture)
    except (FixtureError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        print(f"GriefLogger release fixture validation failed: {exc}", file=sys.stderr)
        return 1
    print(
        "GriefLogger release fixture valid: "
        f"{fixture['project']['version']} Minecraft={fixture['project']['minecraft']} "
        f"loaders={','.join(fixture['artifacts'])} sha256={fixture['fixture_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
