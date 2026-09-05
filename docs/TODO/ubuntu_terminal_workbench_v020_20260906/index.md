---
repository: Plugin-Lab
branch: feat/plugin-lab-ubuntu-v020
status: ready-for-ci
---

# Ubuntu 命令终端 v0.2.0：全屏工作台复刻

## 根因

v0.1.0 只向 Plugin Center 提交了 `dynamic_panel`。该组件的契约是通用纵向表单，
固定渲染为卡片、状态行、有限高度控制台和全宽按钮，因此插件即使调整 JSON 顺序，也无法
复刻 0.6.4.7.8 的全屏终端工作台。

## 修复边界

- Plugin Center 1.3.12 新增私有组件 `terminal_workbench` 和 `edge_to_edge` 页面布局。
- Ubuntu 插件只提交状态和事件，不持有 Activity、NavController 或 Compose Renderer。
- 不修改 AI Limbs Stable Kernel，不扩张 Host ABI。
- 只使用既有 `host.process@1` 与 `host.ubuntu.runtime@1`。

## 工作台行为

- 顶部为 Local、Ubuntu2… 与“兰儿共享”标签，支持新增、选择和关闭。
- 中部终端屏幕占满剩余空间并持续滚动到最新 PTY 输出。
- 底部保留 Ctrl+C、Ubuntu 启停、空闲策略、命令输入与发送。
- 启动 Ubuntu 后自动创建当前 Local PTY，不再要求用户依次点击多个大按钮。
- “兰儿共享”是只读虚拟标签；插件的 `plugin.ubuntu.command` 使用独立 PTY，
  执行期间点亮眼睛并把输出同步到共享标签，不伪造 RDC 在线状态。
- 共享标签选中时，PTY 输入、Ctrl+C、Ubuntu 停止和环境配置均禁用。

## 构建

- Plugin Center 与 Ubuntu 插件分别使用独立分支和独立云端任务。
- Ubuntu 专用工作流只编译 `:ubuntu-terminal-plugin:assembleDebug`。
- 本地不执行 Gradle 构建，只做 JSON、源码与 Git 静态检查。
