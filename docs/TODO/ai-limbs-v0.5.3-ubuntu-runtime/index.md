---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
terminal_fork: https://github.com/AdrienFan/OperitTerminalCore
base: v0.5.2-stable-baseline
status: ready-for-ci
---

# AI Limbs V0.5.3 Ubuntu 运行时

## 原本状况

AI Limbs 的动态接入提示与工作手册把 Ubuntu `/root` 当作主存储。终端管理器在创建单例时自动建立本地会话，普通终端命令因此可以隐式启动 PRoot。终端欢迎页仍显示 Operit 标识，应用也没有独立的 Ubuntu 运行状态。

## 修改意图

- AI Limbs 文档改用应用私有目录作为唯一主存储
- Ubuntu 改为显式启动和显式停止，不再由普通进程工具隐式唤醒
- 保留 Operit 原有 rootfs 准备逻辑，不增加“部署 Ubuntu”产品流程
- 提供 `ubuntu.status`、`ubuntu.start`、`ubuntu.stop` 正式工具
- 终端欢迎页和运行控件使用 AI Limbs 品牌

## 期待结果

Ubuntu 停止时，AI Limbs 桥、健康检查、接入提示和工作手册仍可正常使用。普通 Linux `start_process` 明确要求先启动 Ubuntu；Android 与 Operit 工具路径不受影响。停止操作只关闭运行时管理器持有的会话和后台 Shell，不扫描或全局终止 PRoot。

## 作用域

- [AI Limbs 文档解耦](./01_documents.md)
- [Ubuntu 生命周期](./02_runtime_lifecycle.md)
- [终端界面与工具能力](./03_ui_and_tools.md)
- [版本隔离与云端交付](./04_cloud_delivery.md)

V0.5.2 固定在 `v0.5.2-stable-baseline`，本目录中的改动只进入 V0.5.3。

[DONE] 实现与静态复核已完成，等待 GitHub Actions 云端构建。
