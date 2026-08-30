# First Agent conversation

Use a conversation scoped to this repository and one project. Reading public code needs no account. Set up authentication once in your own terminal only when you need remote contribution access; never paste credentials or authentication output here. Switching a project means reading new project context, not authenticating again.

Copy this short prompt:

```text
请只在当前公开仓库和本次指定项目范围内工作，不搜索父目录或其他项目。先读 START_HERE.md，严格按其顺序完成上下文阅读；不要立刻改文件。只运行本地只读检查（git status、git branch --show-current、git rev-parse HEAD、make doctor、make context-check、make project-check）。按 AGENTS.md 列出全部握手字段，并单独给出 ACCEPT_IN_PUBLIC_REPOSITORY 或 EXCLUDE_FROM_PUBLIC_REPOSITORY；排除即停止。若在项目工作区实施前，还要补充项目握手：覆盖层、根模块复用点、新协议需求（或无）、一致性影响、公开依赖完整性（身份和路径，且没有缺失或不可访问需求）、具体验证入口、可提升为通用能力的内容（或无）；每项的无或不适用都说明原因。说明目标、非目标、公开纳入依据、数据与许可证分类、影响层与平面、计划文件、验证和待批准事项。只实现已批准 Ready Issue 的范围。不得请求、输出或提交凭据；Agent 不能授予公开发布或知识产权许可。首次推送前先检查发布边界，公开分支、草稿 PR 和 CI 输出也已经公开。仓库、分支、任务或权威上下文改变后重新握手；切换项目时读该项目的完整上下文，不重复配置凭据。
```

The authoritative read chain and exact fields live in [START_HERE.md](../START_HERE.md) and [AGENTS.md](../AGENTS.md). Conflicts, unknown publication rights, and missing approvals stop implementation. Push only with authorized Write access or through a public fork/PR after publication review.
