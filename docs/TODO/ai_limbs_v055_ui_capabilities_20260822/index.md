---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: feat/v0.5.5-ui-capabilities
baseline: 6293831
status: ready-for-ci
---

# AI Limbs V0.5.5 文档、Ubuntu 策略与 UI 能力

## 现状

- Ubuntu 空闲自动关机策略只有界面入口，AI 无法正式读取或修改。
- 接入管理仍显示保护说明与工具手册编辑器。
- 新安装只会把缺失文档当作空内容读取，不保证三个命名文件已经落盘。
- UI 工具注册时缓存权限后端；启动时选中 `STANDARD` 后，即使后来切换权限，页面结构、元素点击和按键仍可能继续使用旧后端。
- `Automatic_ui_subagent` 已随源码提供，但默认不启用，也没有统一的就绪状态。

## 意图

- 增加 `ubuntu.idle.get` 与 `ubuntu.idle.set`，继续经过现有工具权限链。
- 接入管理只展示自定义接入提示和工作手册，不显示解释行与工具手册。
- 首次初始化时创建三个命名正确的空文档，同时保留已有内容与迁移逻辑。
- UI 工具每次执行时动态解析当前权限后端。
- Bridge Center 展示视觉、触控、无障碍 Provider 和 UI 子代理状态，并提供显式授权与模型配置入口。
- 增加 `ai_limbs.ui.status`，让 AI 在操作界面前自行确认能力边界。
- V0.5.5 使用独立调试包名，不覆盖 V0.5.4。

## 作用域

- [文档界面与文件不变量](./01_documents.md) [DONE]
- [Ubuntu 空闲策略工具](./02_ubuntu_idle_tools.md) [DONE]
- [视觉与触控能力](./03_ui_capabilities.md) [DONE]
- [静态核验与 GitHub 云端构建](./04_verification.md) [READY FOR CI]
