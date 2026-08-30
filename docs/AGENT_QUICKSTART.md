# First Agent conversation

Use a conversation scoped to this repository and one project/task. Reading, building, local modification, and forking for yourself need no Ready Issue or maintainer approval. Agents changing code still read technical context. Set up authentication once in your own terminal only when you need remote contribution access; never paste credentials or authentication output here. Switching a project means reading new project context, not authenticating again.

Copy this short prompt:

```text
请只在当前公开仓库和本次指定项目/任务范围内工作，不搜索父目录或其他项目。先读 START_HERE.md，按顺序读完技术上下文；不要立刻改文件。先只运行本地只读检查（git status、git branch --show-current、git rev-parse HEAD、make doctor、make context-check、make project-check），按 AGENTS.md 报告全部握手字段及项目实施所需补充项，并单独给出 ACCEPT_IN_PUBLIC_REPOSITORY 或 EXCLUDE_FROM_PUBLIC_REPOSITORY；排除即停止。先说明本次是本地使用/修改还是向上游贡献：自己阅读、构建、修改或 fork 不需要 Ready Issue 或维护者批准；普通小修可直接准备 PR。大型功能、安全敏感、协议或上游新项目变更必须先通过 Issue 或维护者批准的公开安全任务简报确认范围，贡献者和 Agent 不能自批。任务可在线下分配，但只接收可公开的任务内容，不读取保密背景，不上传完整聊天或 Agent 提示词。任务包不能覆盖已接受架构；共享契约仍走根 RFC，缺少必要授权或存在冲突就停止。按 docs/TASK_HANDOFF.md 回报任务与源码版本、改动、实测与未测、证据、Git/PR 状态、兼容性、阻塞、下一步及待决策项；含收件人或人员信息的报告留在仓库外。不得请求、输出或提交凭据；Agent 不能授予发布或知识产权许可。首次公开推送前检查发布边界，公开分支、草稿 PR 和 CI 输出已是公开发布。仓库、分支、任务或权威上下文变化后重新握手。
```

The authoritative read chain and exact fields live in [START_HERE.md](../START_HERE.md) and [AGENTS.md](../AGENTS.md). Use [task handoff](TASK_HANDOFF.md) for assigned work and reports. Conflicts, unknown publication rights, and missing required approvals stop implementation. Push only with authorized Write access or through a public fork/PR after publication review.
