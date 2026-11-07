---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/privacy-audit-v010
date: 2026-11-07
status: source-validated-cloud-build-requested
---

# AI Limbs 隐私安全审计 0.1.0

目标是在现有 AI Limbs 插件架构内，以可核查的系统证据帮助用户发现值得调查的行为。无启动入口、系统身份或后台形态都不直接增加风险分。

本次新增独立 `.ailp` 父插件，不修改基座、当前已安装插件、VPN、系统权限或手机应用的启用状态。代码来源是 `ail-source` 解析到的现有 Plugin Lab SDK，开发工作区由 Source Registry Manager 登记；云构建使用隔离的 Gradle 项目。

范围：本地周期采样、优先级调度、证据解释、覆盖报告、历史、一致性校验、用户确认的普通应用停用与恢复、系统设置入口、主动导出。具体设计见 [可行性与方案](01-design.md)，验证事实及尚未完成的验收见 [验证记录](02-validation.md)。

插件身份 `plugin.privacy_audit`，版本 `0.1.0`，payload applicationId `com.ai.limbs.payload.privacyaudit.v010`。宿主推荐当前实测的 build121；安装时需要批准 `host.process@1`、`host.attention@1`、`host.notification@1` scopes。持续审计默认为关闭；系统通知另行选择，仍受宿主权限策略控制。

阶段：架构检查、设计、实现、规则测试、只读手机采集验证、独立源码类型检查、云端签名打包接线。提交后仅触发云端任务；不等待或轮询构建结果，不安装未验证包。

[DONE] 本轮源码交付；云端构建成功与 Host/Resident 安装运行验收另行确认。
