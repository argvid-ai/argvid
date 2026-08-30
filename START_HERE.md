# Start here

This is the entry point for a repository clone, human contributor, or project-scoped Agent conversation. The repository identity is `argvid-ai/argvid`; it can be understood and checked using only its public contents.

## Choose your route

- **Read, clone, or run:** follow [USING.md](docs/USING.md). No Ready Issue, maintainer approval, or GitHub account is needed to read or run local checks.
- **Modify locally or fork for yourself:** follow [local modification](docs/USING.md#local-modification). No upstream scope approval is needed. Agents changing code still read the context below and preserve technical safety boundaries.
- **Contribute upstream:** follow [CONTRIBUTING.md](CONTRIBUTING.md). Ordinary small fixes may directly prepare a PR. Larger features, security-sensitive changes, protocol changes, and new upstream projects require maintainer-confirmed scope through an Issue or a maintainer-approved public-safe task brief.

A local experiment is not an accepted upstream project or contract. Shared-contract changes still require root RFC review. Public forks, branches, draft PRs, and CI output are publication; approval to work is not permission to publish.

## Required context order

START_HERE.md → AGENTS.md → PROJECT_CONTEXT.md → docs/ARCHITECTURE.md → BRIEF.md → nearest module/project context → applicable RFC/ADR → task scope (Issue or task brief)

Before modifying code, read [Agent instructions](AGENTS.md), [project context](PROJECT_CONTEXT.md), [architecture](docs/ARCHITECTURE.md), and [current brief](BRIEF.md). For modules, read the nearest README and local AGENTS if present. For a project, read its README, PROJECT_CONTEXT, BRIEF, project.yaml, architecture, THIRD_PARTY, and relevant docs. Then read applicable RFCs/accepted ADRs and the task scope. For a local change or ordinary small fix, a concise task description is enough; no Issue is mandatory. For reserved upstream changes, record maintainer confirmation via an Issue or approved public-safe task brief. Missing required authority or conflicts stop implementation; a task packet cannot override accepted architecture.

## Human setup — once per computer/account

Reading public code, cloning, and running the checks need no GitHub account. Install Git, Python 3.12+, Make, and a POSIX-compatible shell, then clone the public repository and run:

```bash
git clone https://github.com/argvid-ai/argvid.git
cd argvid
make doctor context-check project-check test
```

These commands are tested with macOS/POSIX tools and Python 3.12+; CI uses its configured environment. They do not claim native Windows support or prescribe installation procedures. To contribute remotely, use an authentication route available to your account: GitHub CLI is optional and separately installed, while a credential manager or IDE authorization remains an alternative. Check GitHub CLI authentication locally with `gh auth status` when using that route. Enable two-factor authentication. Never paste credentials, authentication output, tokens, or private keys into Agent chat.

Pushing to this repository requires authorized Write access. Otherwise use a public fork and pull request. Neither authentication nor Write access grants publication or intellectual-property permission. An Agent cannot grant either.

## First conversation and every task

Use the short [Chinese first-conversation prompt](docs/AGENT_QUICKSTART.md). Keep the conversation scoped to this repository and one project/task. Do not search parent directories or other projects for context. Do not use repository remote output as a substitute for reading public context.

Complete the exact handshake in [AGENTS.md](AGENTS.md), including a separate placement result. Before project implementation, also complete its project-only handshake supplement. `EXCLUDE_FROM_PUBLIC_REPOSITORY` stops the work: do not copy, upload, push, or open a public issue with excluded material.

Re-handshake after repository, branch, task, or authoritative-context changes. A project switch requires that project's files, not repeated credential setup.

## Work cycle

Choose local work or upstream contribution → read context and task scope → confirm authority when required → context handshake → small independent branch → tests and implementation → publication review before first push → PR/CI/review when contributing → evidence and handoff.

Public branches, draft PRs, fixtures, and CI output are public before merge. Run [publication review](docs/PUBLICATION_POLICY.md) before the first push and repeat it for later additions. Task assignment may happen off-platform; no chat transcript or full Agent prompt needs uploading. Keep durable behavior, interfaces, build steps, and testing facts in public docs/PRs, and use [TASK_HANDOFF.md](docs/TASK_HANDOFF.md) for a concise brief/report. See [contributor workflow](docs/CONTRIBUTOR_WORKFLOW.md) for app/device/integration collaboration and [project rules](docs/PROJECTS.md) for activating the template.
