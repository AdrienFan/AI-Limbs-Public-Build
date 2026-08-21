---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: v0.5.2-dev
baseline: b4fbfd0
status: ready-for-ci
---

# AI Limbs V0.5.2 Bridge Provider

## 现状

`AiLimbsBridgeManager` 直接构造 RDC Provider。Provider 接口已经存在，但核心仍依赖具体桥协议。

## 意图

把 RDC 变成可注册、可选择的 Bridge Provider，并持久化当前 Profile。核心只消费通用状态与动作。

## 作用域

- [核心契约与注册表](./01_core_contracts.md) [DONE]
- [RDC Provider 接入](./02_rdc_provider_integration.md) [DONE]
- [通用动作与 Bridge Center](./03_bridge_center_and_actions.md) [DONE]
- [静态检查与云端编译验证](./04_verification.md) [DONE]
- [通知控制面板与品牌图标](./05_notification_panel_and_branding.md) [READY FOR CI]

External Process 只定义 Profile，不实现进程 Provider。工具执行继续沿用现有权限链。

V0.5.1 由 `v0.5.1-local-baseline` 与提交 `b4fbfd0` 保持可恢复。

## 完成状态

- 核心实现与文档已完成，分支保持在 `v0.5.2-dev`。
- 本地 Gradle 已按用户指示停止，没有将未完成编译写成成功。
- 推送后由 GitHub Actions 承担正式编译验证。
