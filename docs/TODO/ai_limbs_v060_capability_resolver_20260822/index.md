---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: feat/v0.6-capability-resolver
baseline: f568250
status: ready-for-ci
---

# AI Limbs V0.6 Capability Resolver

## 现状

- AI Limbs 已有原生工具注册表、ToolPkg 管理器、MCP 工具缓存和 CLI 隐藏工具搜索，但没有供 Bridge 直接调用的统一能力导航协议。
- 接入 AI 需要事先知道具体工具名；未知截图、页面结构、Ubuntu 或插件调用地址时只能猜测或人工翻查源码。
- 现有 CLI 搜索目录只在 CLI Tool Mode 内开放，无法由 AI Limbs Bridge 复用。

## 意图

- 把 CLI 的工具目录构建与关键词排序抽成 Provider 中立的共享目录。
- 对 AI Limbs 暴露只读的 `capability.search` 与 `capability.describe`，不新增绕过权限链的执行入口。
- 合并 Native、运行时注册工具、ToolPkg、Skill 激活记录和 MCP 缓存元数据。
- 返回稳定 `capability_id`、`provider`、`invoke_id`、Schema、权限、前置条件、可用状态与 `source_locator`。
- 搜索无结果时只刷新现有 Provider 元数据缓存，不为发现能力而启动 Ubuntu 或 MCP。
- 在编译进系统的 access policy 中固定注入 Resolver 名称与两个调用地址。
- V0.6 使用独立调试包名，不覆盖 V0.5.5。

## 作用域

- [共享 Capability Catalog](./01_shared_catalog.md) [DONE]
- [Resolver 协议与 Bootstrap](./02_resolver_and_bootstrap.md) [DONE]
- [可用性、Provider 与权限](./03_availability_and_providers.md) [DONE]
- [静态核验与 GitHub 云端构建](./04_verification.md) [READY FOR CI]
