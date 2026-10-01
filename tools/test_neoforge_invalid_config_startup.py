#!/usr/bin/env python3
"""Verify NeoForge rejects invalid ItemGraph server config before DB startup."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_ERROR = "query.max_page_size must be in [1,100]"


def main() -> int:
    run_dir = Path(tempfile.mkdtemp(prefix="itemgraph-invalid-neoforge-config-"))
    config_dir = run_dir / "config"
    config_dir.mkdir()

    # This is a fresh isolated GameTest directory. It contains no player data
    # or existing ItemGraph database; the generated files are left for inspection.
    (config_dir / "itemgraph-server.toml").write_text(
        "[query]\nmax_page_size = 101\n",
        encoding="utf-8",
    )
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
        return 1

    output = result.stdout + "\n" + result.stderr
    checks = {
        "precise validation error": EXPECTED_ERROR in output,
        "failed GameTest server launch": "BUILD FAILED" in output,
        "database startup was not reached": "ItemGraph database initialized successfully" not in output,
    }
    failed = [label for label, passed in checks.items() if not passed]

    if failed:
        print("NeoForge invalid-config startup check failed: " + ", ".join(failed), file=sys.stderr)
        print(f"Isolated run directory: {run_dir}", file=sys.stderr)
        print(output[-12_000:], file=sys.stderr)
        return 1

    print(f"NeoForge rejected max_page_size=101 before database startup: {EXPECTED_ERROR}")
    print(f"Gradle process exit code: {result.returncode}; BUILD FAILED and the validation error are required above.")
    print(f"Isolated run directory retained for inspection: {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
