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


def require(condition: bool, message: str) -> None:
    if not condition:
        raise FixtureError(message)


def load_fixture() -> dict[str, Any]:
    try:
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise FixtureError(f"cannot read {FIXTURE_PATH.relative_to(ROOT)}: {exc}") from exc
    require(isinstance(fixture, dict), "fixture must be a JSON object")
    return fixture


def canonical_fixture(fixture: dict[str, Any]) -> bytes:
    unsigned = {key: value for key, value in fixture.items() if key != "fixture_sha256"}
    return json.dumps(unsigned, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def validate_static(fixture: dict[str, Any]) -> None:
    require(fixture.get("fixture_schema_version") == 1, "fixture_schema_version must be 1")
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

    actions = fixture.get("actions")
    require(isinstance(actions, dict), "actions must be an object")
    expected_actions = {
        "block": [("BREAK_BLOCK", 0), ("PLACE_BLOCK", 1), ("INTERACT_BLOCK", 2), ("KILL_ENTITY", 3)],
        "item": [("REMOVE_ITEM", 0), ("ADD_ITEM", 1), ("DROP_ITEM", 2), ("PICKUP_ITEM", 3), ("CRAFT_ITEM", 4), ("BREAK_ITEM", 5), ("CONSUME_ITEM", 6), ("THROW_ITEM", 7), ("SHOOT_ITEM", 8), ("ADD_ITEM_ENDER", 9), ("REMOVE_ITEM_ENDER", 10)],
        "session": [("JOIN", 0), ("QUIT", 1)],
    }
    for category, expected in expected_actions.items():
        actual = [(row.get("name"), row.get("id")) for row in actions.get(category, [])]
        require(actual == expected, f"{category} action IDs/names do not match the release fixture")

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


def verify_jar(loader: str, artifact: dict[str, Any], path: Path) -> None:
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
            verify_jar(loader, artifact, destination)


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
                verify_jar(loader, fixture["artifacts"][loader], path)
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
