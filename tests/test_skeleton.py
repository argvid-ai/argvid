from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

REQUIRED_FILES = {
    ".github/CODEOWNERS",
    ".github/ISSUE_TEMPLATE/bug_report.yml",
    ".github/ISSUE_TEMPLATE/feature_request.yml",
    ".github/pull_request_template.md",
    ".github/workflows/skeleton.yml",
    ".python-version",
    "AGENTS.md",
    "BRIEF.md",
    "CHANGELOG.md",
    "CONTRIBUTING.md",
    "GOVERNANCE.md",
    "LICENSE",
    "Makefile",
    "PROJECT_CONTEXT.md",
    "README.md",
    "SECURITY.md",
    "START_HERE.md",
    "docs/ARCHITECTURE.md",
    "docs/context-map.md",
    "docs/REPOSITORY_SCOPE.md",
    "docs/PUBLICATION_POLICY.md",
    "docs/CONTRIBUTOR_WORKFLOW.md",
    "docs/AGENT_QUICKSTART.md",
    "docs/PROJECTS.md",
    "hardware/gen05-lamp/bom.csv",
    "protocol/CHANGELOG.md",
    "protocol/VERSIONING.md",
    "pyproject.toml",
    "tools/context-check",
    "tools/doctor",
    "tools/project-check",
    "projects/README.md",
    "projects/_template/project.yaml",
}

PLACEHOLDER_READMES = {
    "adapters/README.md",
    "adapters/gimbal-gen05/README.md",
    "adapters/onvif-ptz/README.md",
    "adapters/phone-ios/README.md",
    "adapters/replay/README.md",
    "bench/README.md",
    "conformance/README.md",
    "conformance/tests/README.md",
    "reference-director/README.md",
    "docs/decisions/README.md",
    "docs/rfcs/README.md",
    "examples/replay/README.md",
    "firmware/gen05-gimbal/README.md",
    "hardware/gen05-lamp/README.md",
    "hardware/gen05-lamp/cad/README.md",
    "hardware/gen05-lamp/tests/README.md",
    "media/README.md",
    "protocol/README.md",
    "protocol/fixtures/invalid/README.md",
    "protocol/fixtures/valid/README.md",
    "protocol/schema/README.md",
}

PLACEHOLDER_SECTIONS = {
    "Responsibility",
    "Non-goals",
    "Inputs and outputs",
    "Dependencies",
    "Invariants",
    "Start condition",
    "Ownership and review",
    "Verification",
    "Public use cases",
    "License and data",
}

TEXT_SUFFIXES = {"", ".md", ".py", ".toml", ".yml", ".yaml", ".csv"}
CREDENTIAL_MARKERS = {
    "github_pat_",
    "ghp_",
    "-----BEGIN PRIVATE KEY-----",
}
ANDROID_GENERATED_ROOT = ROOT / "projects/gen0-android/src"
ANDROID_GENERATED_DIRECTORY_NAMES = {".gradle", ".kotlin", "build"}


def public_paths():
    """Exclude root tool scratch, never similarly named project source/docs."""
    for directory, names, files in os.walk(ROOT):
        parent = Path(directory)
        names[:] = [name for name in names if name != ".git" and not (
            parent == ROOT and name in {".superpowers", ".agents", ".codex", ".venv", "build", "dist"}
        ) and not (name == "__pycache__" and not parent.is_relative_to(ROOT / "projects"))]
        yield from (parent / name for name in names + files if name != ".git")


def is_android_generated_directory(directory: Path) -> bool:
    return directory.is_relative_to(ANDROID_GENERATED_ROOT) and any(
        part in ANDROID_GENERATED_DIRECTORY_NAMES
        for part in directory.relative_to(ANDROID_GENERATED_ROOT).parts
    )


def empty_directories() -> list[str]:
    empty = []
    for directory in public_paths():
        if not directory.is_dir():
            continue
        if is_android_generated_directory(directory):
            continue
        if not any(directory.iterdir()):
            empty.append(str(directory.relative_to(ROOT)))
    return sorted(empty)


class SkeletonTests(unittest.TestCase):
    def test_empty_directory_scan_keeps_source_directories(self) -> None:
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary_directory:
            empty_source = Path(temporary_directory) / "source-empty"
            empty_source.mkdir()
            self.assertIn(str(empty_source.relative_to(ROOT)), empty_directories())

    def test_required_files_exist(self) -> None:
        missing = sorted(path for path in REQUIRED_FILES if not (ROOT / path).is_file())
        self.assertEqual([], missing, f"Missing required files: {missing}")

    def test_placeholder_modules_are_documented(self) -> None:
        missing = sorted(path for path in PLACEHOLDER_READMES if not (ROOT / path).is_file())
        self.assertEqual([], missing, f"Missing module README files: {missing}")

        incomplete: dict[str, list[str]] = {}
        for relative_path in sorted(PLACEHOLDER_READMES):
            text = (ROOT / relative_path).read_text(encoding="utf-8")
            absent = sorted(section for section in PLACEHOLDER_SECTIONS if section not in text)
            if absent:
                incomplete[relative_path] = absent
        self.assertEqual({}, incomplete, f"Incomplete module boundary docs: {incomplete}")

    def test_repository_has_no_empty_directories(self) -> None:
        empty = empty_directories()
        self.assertEqual([], empty, f"Empty directories are not allowed: {empty}")

    def test_public_tree_has_no_credential_markers(self) -> None:
        findings: dict[str, list[str]] = {}
        for path in public_paths():
            if not path.is_file() or path.suffix not in TEXT_SUFFIXES:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            matches = sorted(marker for marker in CREDENTIAL_MARKERS if marker in text)
            if matches and path != Path(__file__):
                findings[str(path.relative_to(ROOT))] = matches
        self.assertEqual({}, findings, f"Credential markers found: {findings}")

    def test_actions_are_pinned_to_full_commit_shas(self) -> None:
        workflow = ROOT / ".github/workflows/skeleton.yml"
        self.assertTrue(workflow.is_file(), "Workflow must exist before SHA pinning can be checked")
        uses_lines = [line.strip() for line in workflow.read_text(encoding="utf-8").splitlines() if "uses:" in line]
        self.assertTrue(uses_lines, "Workflow must use at least one action")
        for line in uses_lines:
            self.assertRegex(line, re.compile(r"uses:\s+[^@\s]+@[0-9a-f]{40}(?:\s+#.*)?$"))
        text = workflow.read_text(encoding="utf-8")
        self.assertIn("permissions:\n  contents: read", text)
        self.assertNotRegex(text, r"\b(?:write-all|write)\b")

    @unittest.skipUnless(sys.version_info < (3, 12), "recovery guidance is only exercised on older Python")
    def test_doctor_explains_how_to_install_required_python(self) -> None:
        result = subprocess.run(
            [str(ROOT / "tools/doctor")],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode)
        self.assertIn("pyenv install 3.12", output)
        self.assertIn("make doctor", output)


if __name__ == "__main__":
    unittest.main()
