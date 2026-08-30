"""Exercise checker CLIs on small, independent public candidate trees."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
READ_CHAIN = "START_HERE.md → AGENTS.md → PROJECT_CONTEXT.md → docs/ARCHITECTURE.md → BRIEF.md → nearest module/project context → RFC/ADR/Ready Issue"
LAYERS = ["L4 Experience", "L3 Decision", "L2 Contract", "L1.5 Orchestration", "L1 Adapter", "L0 Execution"]
PLANES = ["Media", "Transport", "Evaluation", "Data Governance"]
FIELDS = [
    "repository_identity", "workspace_type", "current_branch_and_commit",
    "authoritative_files_read", "task_goal", "non_goals", "affected_layers",
    "affected_cross_cutting_planes", "public_inclusion_basis",
    "data_and_license_classification", "planned_files", "required_tests",
    "architecture_conflicts", "approval_required",
]
MANIFEST = """project_id: example-project
status: proposed
license: Apache-2.0
protocol_version: pre-alpha
layers: []
cross_cutting_planes: []
entrypoints: []
verification: []
fixture_policy: synthetic-only
third_party_review: required
"""
PROJECT_FILES = ["README.md", "PROJECT_CONTEXT.md", "BRIEF.md", "architecture.md", "THIRD_PARTY.md", "src/README.md", "tests/README.md", "fixtures/README.md", "docs/README.md"]


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "candidate"
        self.root.mkdir()
        shutil.copytree(ROOT / "tools", self.root / "tools")
        for path in ["README.md", "START_HERE.md", "AGENTS.md", "PROJECT_CONTEXT.md", "BRIEF.md", "docs/ARCHITECTURE.md", "docs/context-map.md", "docs/REPOSITORY_SCOPE.md", "docs/PROJECTS.md", "docs/PUBLICATION_POLICY.md", "docs/CONTRIBUTOR_WORKFLOW.md", "docs/AGENT_QUICKSTART.md", "projects/README.md"]:
            self.put(path, "# Public candidate\n")
        for path in ["START_HERE.md", "AGENTS.md", "docs/context-map.md"]:
            self.put(path, "# Read order\n\n" + READ_CHAIN + "\n")
        self.append("AGENTS.md", "\n## Handshake fields\n\n" + "\n".join("- `" + field + "`" for field in FIELDS) + "\n")
        for path in ["PROJECT_CONTEXT.md", "docs/ARCHITECTURE.md"]:
            self.put(path, "# Layers\n\n" + "\n".join("- " + layer for layer in LAYERS) + "\n\n## Cross-cutting planes\n\n" + "\n".join("- " + plane for plane in PLANES) + "\n")
        self.put("docs/REPOSITORY_SCOPE.md", "# Placement outcomes\n\n- `ACCEPT_IN_PUBLIC_REPOSITORY`\n- `EXCLUDE_FROM_PUBLIC_REPOSITORY`\n")
        self.project("_template")

    def put(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def append(self, name, text):
        path = self.root / name
        path.write_text(path.read_text(encoding="utf-8") + text, encoding="utf-8")

    def replace(self, name, old, new):
        path = self.root / name
        content = path.read_text(encoding="utf-8")
        self.assertIn(old, content, "fixture mutation must change candidate")
        path.write_text(content.replace(old, new), encoding="utf-8")

    def project(self, name):
        for path in PROJECT_FILES:
            self.put(f"projects/{name}/{path}", "# Public documentation\n")
        manifest = MANIFEST
        if name != "_template":
            manifest = manifest.replace("example-project", name).replace("layers: []", "layers: [L4]").replace("entrypoints: []", "entrypoints: [README.md]").replace("verification: []", "verification: [\"make project-check\"]")
            self.append(f"projects/{name}/README.md", "\nVerification: `make project-check`\n")
        self.put(f"projects/{name}/project.yaml", manifest)
        self.put(f"projects/{name}/architecture.md", "# Architecture mapping\n\n| Area | Status | Rationale |\n|---|---|---|\n" + "\n".join(f"| {area} | {'implemented' if area == 'L4 Experience' and name != '_template' else 'not-applicable'} | Documentation-only proposal. |" for area in LAYERS + PLANES) + "\n")

    def run_check(self, tool, *args, expected=0, contains=None, cwd=None):
        checker = self.root / "tools" / tool
        self.assertTrue(checker.is_file(), f"missing checker: tools/{tool}")
        result = subprocess.run([str(checker), *args], cwd=cwd or self.root, capture_output=True, text=True, check=False, timeout=10)
        output = result.stdout + result.stderr
        self.assertEqual(expected, result.returncode, output)
        if contains:
            self.assertIn(contains, output)
        return result


class ContextCheckTests(CandidateTests):
    def test_valid_context_from_another_working_directory(self):
        self.run_check("context-check", cwd=self.root.parent)

    def test_missing_context(self):
        (self.root / "docs/ARCHITECTURE.md").unlink()
        self.run_check("context-check", expected=1, contains="docs/ARCHITECTURE.md")

    def test_wrong_read_order(self):
        self.replace("START_HERE.md", "AGENTS.md → PROJECT_CONTEXT.md", "PROJECT_CONTEXT.md → AGENTS.md")
        self.run_check("context-check", expected=1, contains="read order")

    def test_missing_or_renamed_layer(self):
        for path, old, new in [("PROJECT_CONTEXT.md", "L1.5 Orchestration", "L1.5 Scheduling"), ("docs/ARCHITECTURE.md", "L1.5 Orchestration", "")]:
            with self.subTest(path=path):
                self.replace(path, old, new)
                self.run_check("context-check", expected=1, contains="layer")
                self.replace(path, new or "- \n", old if new else "- " + old + "\n")

    def test_missing_plane(self):
        self.replace("docs/ARCHITECTURE.md", "Data Governance", "Governance")
        self.run_check("context-check", expected=1, contains="plane")

    def test_incomplete_placement_outcomes(self):
        self.replace("docs/REPOSITORY_SCOPE.md", "EXCLUDE_FROM_PUBLIC_REPOSITORY", "WAIT_FOR_REVIEW")
        self.run_check("context-check", expected=1, contains="placement")

    def test_extra_placement_outcome(self):
        self.append("docs/REPOSITORY_SCOPE.md", "- `WAIT_FOR_REVIEW`\n")
        self.run_check("context-check", expected=1, contains="placement")

    def test_plain_extra_placement_outcome(self):
        self.append("docs/REPOSITORY_SCOPE.md", "- WAIT_FOR_REVIEW\n")
        self.run_check("context-check", expected=1, contains="placement")

    def test_incomplete_handshake(self):
        self.replace("AGENTS.md", "- `approval_required`", "")
        self.run_check("context-check", expected=1, contains="approval_required")

    def test_broken_local_link(self):
        self.append("README.md", "\n[missing](docs/absent.md)\n")
        self.run_check("context-check", expected=1, contains="docs/absent.md")

    def test_escaping_local_link(self):
        outside = self.root.parent / "outside.md"
        outside.write_text("# Outside\n", encoding="utf-8")
        self.append("README.md", "\n[outside](../outside.md)\n")
        self.run_check("context-check", expected=1, contains="escape")

    def test_escaping_link_symlink(self):
        (self.root / "linked.md").symlink_to(self.root.parent)
        self.append("README.md", "\n[outside](linked.md)\n")
        self.run_check("context-check", expected=1, contains="escape")

    def test_unsupported_local_reference_syntax(self):
        for link in ["[guide][local]\n[local]: missing.md", "[guide](<docs/context-map.md>)", "[guide](docs/context-map.md \"title\")", "<a href=\"missing.md\">guide</a>", "[guide](docs/%63ontext-map.md)"]:
            with self.subTest(link=link):
                self.put("README.md", link)
                self.run_check("context-check", expected=1, contains="unsupported")

    def test_empty_label_nested_destinations_are_rejected(self):
        for link in ["![](missing(image).png)", "[](missing(page).md)"]:
            with self.subTest(link=link):
                self.put("README.md", link)
                self.run_check("context-check", expected=1, contains="unsupported")

    def test_empty_label_simple_destinations_are_validated(self):
        self.put("image.png", "synthetic fixture\n")
        self.put("README.md", "![](image.png) [](docs/context-map.md)\n")
        self.run_check("context-check")
        self.put("README.md", "![](missing.png)\n")
        self.run_check("context-check", expected=1, contains="missing.png")

    def test_empty_label_nested_code_examples_are_not_navigation(self):
        self.put("README.md", "```text\n![](missing(image).png)\n```\n\n`[](missing(page).md)`\n")
        self.run_check("context-check")

    def test_links_in_code_examples_are_not_navigation(self):
        self.append("README.md", "\n```text\n[example](not-a-real-target.md)\n```\n\n`[example](not-a-real-target.md)`\n")
        self.run_check("context-check")

    def test_existing_local_anchor_and_external_links(self):
        self.append("README.md", "\n[context](docs/context-map.md#priority) [web](https://example.org/a) [contact](mailto:help@example.org)\n")
        self.run_check("context-check")

    def test_root_scratch_excluded_but_project_documents_checked(self):
        self.put(".superpowers/scratch.md", "[broken](missing.md)\n")
        self.put(".git/scratch.md", "[broken](missing.md)\n")
        self.put("__pycache__/scratch.md", "[broken](missing.md)\n")
        self.run_check("context-check")
        self.put("projects/_template/docs/.superpowers/source.md", "[broken](missing.md)\n")
        self.run_check("context-check", expected=1, contains="missing.md")

    def test_context_rejects_arguments(self):
        self.run_check("context-check", "unexpected", expected=1, contains="usage")


class ProjectCheckTests(CandidateTests):
    def test_template_is_valid(self):
        self.run_check("project-check")

    def test_documentation_only_project_is_valid(self):
        self.project("guide")
        self.run_check("project-check")
        self.run_check("project-check", "projects/guide")

    def test_missing_template(self):
        shutil.rmtree(self.root / "projects/_template")
        self.run_check("project-check", expected=1, contains="_template")

    def test_missing_project_names_path(self):
        self.run_check("project-check", "projects/absent", expected=1, contains="projects/absent")

    def test_invalid_usage(self):
        self.run_check("project-check", "one", "two", expected=2, contains="usage")

    def test_project_argument_traversal(self):
        self.run_check("project-check", "projects/../projects/_template", expected=1, contains="traversal")

    def test_manifest_rejections(self):
        self.project("guide")
        path = "projects/guide/project.yaml"
        original = (self.root / path).read_text(encoding="utf-8")
        cases = [
            ("unknown layer", original.replace("[L4]", "[L9]"), "layers"),
            ("unknown plane", original.replace("cross_cutting_planes: []", "cross_cutting_planes: [Unknown]"), "planes"),
            ("duplicate key", original + "status: proposed\n", "duplicate"),
            ("unknown key", original + "extra: value\n", "unknown"),
            ("blank scalar", original.replace("status: proposed", "status:"), "status"),
            ("malformed line", original + "broken line\n", "malformed"),
            ("malformed list", original.replace("[L4]", "[L4,,L1]"), "list"),
            ("multiline list", original.replace("[L4]", "\n  - L4"), "list"),
            ("missing key", original.replace("license: Apache-2.0\n", ""), "license"),
            ("wrong id", original.replace("project_id: guide", "project_id: another"), "project_id"),
            ("empty layers", original.replace("[L4]", "[]"), "layers"),
            ("empty entrypoints", original.replace("[README.md]", "[]"), "entrypoints"),
            ("missing target", original.replace("[README.md]", "[missing.md]"), "missing.md"),
            ("traversal", original.replace("[README.md]", "[../README.md]"), "entrypoint"),
            ("absolute path", original.replace("[README.md]", "[/README.md]"), "entrypoint"),
            ("undocumented check", original.replace("make project-check", "make missing"), "verification"),
            ("unreviewed version", original.replace("protocol_version: pre-alpha", "protocol_version: v1.0.0"), "protocol_version"),
        ]
        for name, content, diagnostic in cases:
            with self.subTest(name=name):
                self.put(path, content)
                self.run_check("project-check", "projects/guide", expected=1, contains=diagnostic)

    def test_template_defaults_are_enforced(self):
        self.replace("projects/_template/project.yaml", "status: proposed", "status: released")
        self.run_check("project-check", expected=1, contains="template")

    def test_all_layer_and_plane_mappings_required(self):
        self.project("guide")
        for area in ["L1.5 Orchestration", "Data Governance"]:
            with self.subTest(area=area):
                self.replace("projects/guide/architecture.md", area, "Unknown")
                self.run_check("project-check", "projects/guide", expected=1, contains="architecture")
                self.replace("projects/guide/architecture.md", "Unknown", area)

    def test_mapping_status_and_manifest_must_agree(self):
        self.project("guide")
        self.replace("projects/guide/architecture.md", "| L4 Experience | implemented |", "| L4 Experience | reused |")
        self.run_check("project-check", "projects/guide", expected=1, contains="architecture")

    def test_missing_required_document(self):
        (self.root / "projects/_template/THIRD_PARTY.md").unlink()
        self.run_check("project-check", expected=1, contains="THIRD_PARTY.md")

    def test_escaping_entrypoint_symlink(self):
        self.project("guide")
        (self.root / "projects/guide/linked").symlink_to(self.root.parent)
        self.replace("projects/guide/project.yaml", "[README.md]", "[linked]")
        self.run_check("project-check", "projects/guide", expected=1, contains="escape")

    def test_escaping_project_symlink(self):
        (self.root / "projects/linked").symlink_to(self.root.parent)
        self.run_check("project-check", expected=1, contains="escape")

    def test_nested_schema_copy(self):
        self.project("guide")
        self.put("projects/guide/src/nested/protocol/schema/copied.json", "{}\n")
        self.run_check("project-check", "projects/guide", expected=1, contains="protocol/schema")

    def test_directory_symlink_cannot_alias_protocol_schema(self):
        self.project("guide")
        self.put("projects/guide/src/contracts/schema/copied.json", "{}\n")
        (self.root / "projects/guide/src/protocol").symlink_to("contracts", target_is_directory=True)
        self.run_check("project-check", "projects/guide", expected=1, contains="directory symlink")

    def test_directory_symlink_to_contained_directory_is_rejected(self):
        self.project("guide")
        (self.root / "projects/guide/docs/alias").symlink_to("../src", target_is_directory=True)
        self.run_check("project-check", "projects/guide", expected=1, contains="directory symlink")

    def test_directory_symlink_as_project_root_is_rejected(self):
        self.project("guide")
        self.replace("projects/guide/project.yaml", "project_id: guide", "project_id: alias")
        (self.root / "projects/alias").symlink_to("guide", target_is_directory=True)
        self.run_check("project-check", "projects/alias", expected=1, contains="directory symlink")

    def test_directory_symlink_escape_is_rejected(self):
        self.project("guide")
        (self.root / "projects/guide/docs/outside").symlink_to(self.root.parent, target_is_directory=True)
        self.run_check("project-check", "projects/guide", expected=1, contains="escape")

    def test_directory_symlink_cycles_fail_without_hanging(self):
        self.project("guide")
        link = self.root / "projects/guide/src/loop"
        link.symlink_to(".", target_is_directory=True)
        self.run_check("project-check", "projects/guide", expected=1)
        link.unlink()
        link.symlink_to("loop", target_is_directory=True)
        self.run_check("project-check", "projects/guide", expected=1)

    def test_plain_directories_and_contained_file_symlink_are_valid(self):
        self.project("guide")
        self.put("projects/guide/src/contracts/README.md", "# Public contract notes\n")
        self.run_check("project-check", "projects/guide")
        (self.root / "projects/guide/docs/linked.md").symlink_to("../src/contracts/README.md")
        self.replace("projects/guide/project.yaml", "[README.md]", "[docs/linked.md]")
        self.run_check("project-check", "projects/guide")

    def test_escaping_file_symlink_is_rejected(self):
        self.project("guide")
        self.put("outside.md", "# Outside project\n")
        (self.root / "projects/guide/docs/linked.md").symlink_to("../../../outside.md")
        self.run_check("project-check", "projects/guide", expected=1, contains="escape")

    def test_manifest_commands_are_never_executed(self):
        self.project("guide")
        command = "touch SHOULD_NOT_EXIST"
        self.replace("projects/guide/project.yaml", "make project-check", command)
        self.append("projects/guide/README.md", "\n`" + command + "`\n")
        self.run_check("project-check", "projects/guide")
        self.assertFalse((self.root / "SHOULD_NOT_EXIST").exists())
        self.assertFalse((self.root / "projects/guide/SHOULD_NOT_EXIST").exists())


class TestTargetTests(CandidateTests):
    def setUp(self):
        super().setUp()
        shutil.copyfile(ROOT / "Makefile", self.root / "Makefile")
        self.put("tests/test_candidate.py", "import unittest\nclass Candidate(unittest.TestCase):\n    def test_public_candidate(self):\n        self.assertTrue(True)\n")
        self.put("conformance/tests/README.md", "# Conformance pending\n")

    def test_empty_conformance_is_explicitly_pending(self):
        result = subprocess.run(["make", "test"], cwd=self.root, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("conformance: pending", result.stdout)

    def test_actual_conformance_failure_fails_test_target(self):
        self.put("conformance/tests/test_contract.py", "import unittest\nclass Contract(unittest.TestCase):\n    def test_contract(self):\n        self.fail('contract rejection evidence')\n")
        result = subprocess.run(["make", "test"], cwd=self.root, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("contract rejection evidence", result.stderr)


if __name__ == "__main__":
    unittest.main()
