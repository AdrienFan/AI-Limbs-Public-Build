# 03 · 验收与云端构建

## 静态验收

- 协议解析覆盖 JSON、Base64URL、非法 args 与 Dispatcher 失败透传
- Transport next_action 生成的 parameters 必须能被协议解码
- 远程路由覆盖 core 直达、Resolver 目录命中的 ToolPkg/host 包装与未知工具统一错误路径
- 执行 `git diff --check`
- 检查提交只包含 V0.6.4.7.6 相关源码、测试与文档

## 构建分流

本阶段不在手机 Ubuntu 内执行 Gradle 编译或测试。提交推送至 GitHub 后，由云端工作流完成编译。

## 安装后 parity

- Ping / Pong
- capability.search / capability.describe
- 小型普通文件与 Work Manual 读取
- Ubuntu 状态、生命周期与并发保护
- 普通 ALLOW、ASK 确认与 FORBID 拦截
- 重复 request_id 与冲突 request_id

[STATIC PREFLIGHT PASSED · PENDING CLOUD BUILD]
