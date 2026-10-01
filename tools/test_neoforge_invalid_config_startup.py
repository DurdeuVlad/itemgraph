#!/usr/bin/env python3
"""Verify NeoForge rejects invalid ItemGraph server config before DB startup."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROBES = (
    ("query.max_page_size", "max_page_size", "[query]\nmax_page_size = 101\n",
     "query.max_page_size must be between 1 and 100, got: 101"),
    ("general.database_port", "database_port", "[general]\ndatabase_port = 0\n",
     "general.database_port must be between 1 and 65535, got: 0"),
    ("general.database_path", "database_path", '[general]\ndatabase_path = ""\n',
     "general.database_path must not be blank"),
    ("general.grieflogger_database_path", "grieflogger_database_path",
     '[general]\ngrieflogger_database_path = ""\n',
     "general.grieflogger_database_path must not be blank"),
    ("general.database_backend", "database_backend",
     '[general]\ndatabase_backend = "jdbc:mysql://user:backend-secret@db/test"\n',
     "general.database_backend must be sqlite, mysql, mariadb, or mysql_mariadb"),
)


def toml_value(text: str, key: str) -> str | None:
    values = re.findall(rf"(?m)^\s*{re.escape(key)}\s*=\s*(.*?)\s*$", text)
    return values[0] if len(values) == 1 else None


def run_probe(name: str, key: str, toml: str, expected_error: str) -> bool:
    run_dir = Path(tempfile.mkdtemp(prefix=f"itemgraph-invalid-neoforge-{name}-"))
    config_dir = run_dir / "config"
    config_dir.mkdir()

    # This is a fresh isolated GameTest directory. It contains no player data
    # or existing ItemGraph database; the generated files are left for inspection.
    (config_dir / "itemgraph-server.toml").write_text(toml, encoding="utf-8")
    init_script = run_dir / "itemgraph-invalid-config.init.gradle"
    init_script.write_text(
        """gradle.projectsEvaluated {
    def project = gradle.rootProject.project(':neoforge')
    def runDirectory = System.getenv('ITEMGRAPH_INVALID_CONFIG_RUN_DIR')
    if (runDirectory == null) {
        throw new GradleException('Missing ITEMGRAPH_INVALID_CONFIG_RUN_DIR')
    }
    project.extensions.getByName('neoForge').runs.getByName('gameTestServer')
        .gameDirectory.set(new File(runDirectory))
}
""",
        encoding="utf-8",
    )

    env = os.environ.copy()
    env["ITEMGRAPH_INVALID_CONFIG_RUN_DIR"] = str(run_dir)
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), ":neoforge:runGameTestServer", "--init-script", str(init_script), "--no-daemon"]
    if os.name == "nt":
        command.insert(0, "cmd.exe")
        command.insert(1, "/d")
        command.insert(2, "/c")

    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=360,
            check=False,
        )
    except subprocess.TimeoutExpired as failure:
        print(f"NeoForge invalid-config startup check timed out; run directory: {run_dir}", file=sys.stderr)
        if failure.stdout:
            print(str(failure.stdout)[-12_000:], file=sys.stderr)
        if failure.stderr:
            print(str(failure.stderr)[-12_000:], file=sys.stderr)
        return False

    output = result.stdout + "\n" + result.stderr
    checks = {
        "precise validation error": expected_error in output,
        "failed GameTest server launch": "BUILD FAILED" in output,
        "database startup was not reached": "ItemGraph database initialized successfully" not in output,
        "invalid backend credentials were not logged": "backend-secret" not in output,
        "invalid TOML value was not rewritten": toml_value(
            (config_dir / "itemgraph-server.toml").read_text(encoding="utf-8"), key
        ) == toml_value(toml, key),
    }
    failed = [label for label, passed in checks.items() if not passed]

    if failed:
        print("NeoForge invalid-config startup check failed: " + ", ".join(failed), file=sys.stderr)
        print(f"Isolated run directory: {run_dir}", file=sys.stderr)
        print(output[-12_000:], file=sys.stderr)
        return False

    print(f"NeoForge rejected {name} before database startup: {expected_error}")
    print(f"Gradle process exit code: {result.returncode}; BUILD FAILED and the validation error are required above.")
    print(f"Isolated run directory retained for inspection: {run_dir}")
    return True


def main() -> int:
    failed = [name for name, key, toml, expected_error in PROBES
              if not run_probe(name, key, toml, expected_error)]
    if failed:
        print("NeoForge invalid-config startup probes failed: " + ", ".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
