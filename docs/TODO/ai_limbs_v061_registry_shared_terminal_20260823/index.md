---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: feat/v0.6.1-capability-shared-terminal
baseline: 68f72ee
status: ready-for-ci
---

# AI Limbs V0.6.1：能力注册表与 Ubuntu 共享窗口

## 目标

- 修复 V0.6 Capability Resolver 对已注册 Ubuntu 能力返回空结果的问题。
- 让 AI Limbs Core、Tool Dispatcher 与 Ubuntu 生命周期成为可搜索的一等能力。
- 在 Ubuntu 命令终端加入只读的兰儿操作共享窗口。
- 使用独立 applicationId，允许 V0.6 与 V0.6.1 同时安装验证。

## V0.6 漏检根因

V0.6 已调用 `AIToolHandler.registerDefaultTools()`，Ubuntu 生命周期工具也确实存在于运行时
Registry；因此问题不是“Ubuntu 没有初始化注册”。真实缺陷在 Resolver 的反向映射：

1. 搜索前把 `ubuntu.status` 的显示名语义化为“查询 Ubuntu 状态”；
2. `catalogIdentity` 当时把可变的 `displayName` 作为身份键一部分；
3. 搜索完成后用语义化记录的身份键反查原定义；
4. 新旧显示名不同，反查失败，匹配结果被 `mapNotNull` 丢弃。

V0.6.1 的修复是在同一批“已语义化搜索记录”上建立反向索引。显示文案以后再变化，也不会
破坏匹配结果。

Core 和 Dispatcher 还有一个独立问题：V0.6 只在 Resolver 内维护少量硬编码核心条目，没有
正式的第一方注册契约。V0.6.1 新增 `AiLimbsCoreCapabilityRegistry`，启动时集中登记：

- Capability Resolver；
- AI Limbs Core 状态；
- AI Limbs Tool Dispatcher 状态；
- 接入文档与 UI 状态；
- Operit Registry 查询；
- Ubuntu 生命周期与空闲策略。

## 强制开发规则

以后新增任何 AI Limbs 自有可调用功能时，必须在同一次修改中完成两件事：

1. 在 `AiLimbsOperitDispatcher`（或其正式下游执行器）实现调用路由；
2. 在 `AiLimbsCoreCapabilityRegistry` 注册能力名称、调用地址、描述、参数与检索关键词。

只增加 Dispatcher 分支、不增加 Registry 条目，等同于功能对兰儿不可发现，不得作为完成状态。
Registry 初始化会检查重复调用名；运行时目录与核心目录合并时不得按 `targetToolName` 粗暴去重，
因为 ToolPkg / MCP activation 可能共享 `use_package` 调用名。

## Ubuntu 共享窗口

终端隐藏执行器原本已经逐块接收 stdout，但只在命令结束后返回完整结果。V0.6.1 在不改变执行
语义的前提下增加只读观察回调：

- `LocalTerminalProvider` 每收到一块输出就生成去除内部 marker 的当前输出快照；
- `TerminalManager` 只在内存中保存最近命令和最多 64,000 个输出字符，不写磁盘；
- 顶部标签栏在 `+` 左侧绘制 `👁` 等价的眼睛图标；
- 隐藏 Ubuntu 操作活跃时图标为蓝色，空闲时为灰色；
- 点击后打开全屏只读窗口，页面没有输入框、PTY 输入、Ctrl+C 或命令发送入口；
- SSH 隐藏执行不冒充 Ubuntu 实时流；它只在完成后提供结果快照。
- `ai_limbs.ubuntu.share.status` 已同步登记，可查询共享通道是否活跃，但不会回传命令正文或输出。

共享窗口展示的是 AI 工具通过隐藏 Ubuntu 执行器发出的命令和输出，不是远程桌面录屏，也不会
捕获用户自己在普通终端标签页中的键盘输入。

## 界面与版本

- 工具箱名称和页面标题改为“Ubuntu命令终端”。
- `applicationIdSuffix = .ailimbs.v061`。
- `versionNameSuffix = -ai-limbs-v0.6.1-build1`。
- 正式构建只由 GitHub Actions `assembleDebug` 执行，本地只做静态预检。

## 验收清单

- `capability.search` 搜索 `Ubuntu` 能返回 Ubuntu 生命周期能力。
- 搜索 `AI Limbs Core` 能返回 `ai_limbs.core.status`。
- 搜索 `dispatcher` 能返回 `ai_limbs.dispatcher.status`。
- `capability.describe` 能给出上述能力的调用地址与权限信息。
- 隐藏 Ubuntu 命令执行期间眼睛图标变蓝，结束后变灰。
- 共享窗口持续看到命令与逐块输出，且无法向终端输入内容。
- V0.6.1 能与 V0.6 并存安装。
