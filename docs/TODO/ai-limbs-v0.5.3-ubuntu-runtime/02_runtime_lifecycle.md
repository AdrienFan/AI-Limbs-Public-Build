# Ubuntu 生命周期

## 旧实现

`TerminalManager` 的构造过程自动创建 `Local` 会话。会话启动脚本依次执行 rootfs 准备和 `login_ubuntu`，因此获取终端单例就可能启动 PRoot。`Terminal.isConnected()` 固定返回 `true`，无法表达真实状态。

## 修正

- 增加 `STOPPED`、`STARTING`、`RUNNING`、`STOPPING`、`ERROR` 状态流
- 移除单例构造时的默认会话副作用
- `startUbuntu` 复用现有 rootfs 准备与会话启动流程
- `stopUbuntu` 关闭可见会话、隐藏 Shell 与管理器持有的运行资源
- 本地终端会话和隐藏命令只允许在 `RUNNING` 状态创建

## 验收

用户停止 Ubuntu 后，普通 Linux 进程请求返回 `Ubuntu is stopped. Call ubuntu.start first.`，并且不会隐式恢复运行时。

[DONE]
