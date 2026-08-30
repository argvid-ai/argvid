# Using Argvid

Choose the amount of process that matches your goal. Reading, cloning, building, local modification, and forking for yourself require no Ready Issue or maintainer approval. This does not waive licenses, safety boundaries, or publication rights.

## Read, clone, and run checks

The repository is a pre-alpha framework, not a stable device API. See [README.md](../README.md) for available areas and their limitations. The root checks are runnable today; placeholders are not implemented features.

Install Git, Python 3.12+, Make, and a POSIX-compatible shell. From a terminal:

```bash
git clone https://github.com/argvid-ai/argvid.git
cd argvid
make doctor context-check project-check test
```

Reading public code and running these offline checks need no account or credentials. These commands are tested with macOS/POSIX tools; native Windows support is not claimed. Structure tests are not conformance, hardware validation, or safety certification. Unrun hardware-in-the-loop (HIL) checks remain pending.

## Experimental local-camera project

[Gen0 Camera for Android](../projects/gen0-android/README.md) is a self-contained experimental Android application with local-only capture and host verification. It does not define a canonical protocol, operate physical BLE hardware, or establish device acceptance. Follow its [project read chain](../projects/gen0-android/START_HERE.md), including the root [Agent guidance](../AGENTS.md) and [task handoff](TASK_HANDOFF.md), before changing it.

Its setup, exact host commands, warnings, and pending device checks are in the project [README](../projects/gen0-android/README.md) and [verification record](../projects/gen0-android/docs/verification.md). Android instrumentation and hardware-in-the-loop checks are not run by the root command above.

## Local modification

You may experiment locally or fork for your own use without upstream scope approval. Before asking an Agent to modify code, follow the technical read chain and handshake in [START_HERE.md](../START_HERE.md) and [AGENTS.md](../AGENTS.md). State that the work is local, the intended change, non-goals, and checks. A concise task description is sufficient; no Issue is required.

Keep deterministic safety boundaries intact. Do not present local experiments, changed protocol semantics, or copied project templates as accepted upstream projects or released compatibility. Local work does not authorize upstream placeholder activation. Run the applicable checks and record limitations.

A public fork is publication even when it is only for personal use. Review [publication policy](PUBLICATION_POLICY.md) before any public push, including branches, draft PRs, generated logs, and CI output. Do not copy confidential context, credentials, restricted media, or material without rights into public artifacts.

## Contribute upstream

Ordinary small fixes may directly prepare a PR. Larger features, security-sensitive changes, protocol changes, and new upstream projects need maintainer-confirmed scope via an Issue or maintainer-approved public-safe task brief before implementation. Shared-contract changes still use root RFCs. See [CONTRIBUTING.md](../CONTRIBUTING.md) for the full contribution and review rules.

For an assigned task, use [TASK_HANDOFF.md](TASK_HANDOFF.md). Assignment can happen off-platform, but only public-safe task content belongs in this workspace; full chats and Agent prompts need not be uploaded. Public docs/PRs retain the behavior, interface, build, and testing facts needed to reproduce the work.
