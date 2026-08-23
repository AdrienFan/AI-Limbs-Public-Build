---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: feat/v0.6.3-laner-chat-bridge
baseline: bedeebd
status: ready-for-ci
---

# AI Limbs V0.6.3：兰儿桥接聊天

## 目标

- 在 AI 对话首页并列提供 API 聊天与兰儿桥接聊天入口，两个配置互不覆盖。
- 两种聊天模式都能返回首页并重新选择连接方式。
- 复用现有消息列表、持久化、流式气泡、取消和输入界面，不复制第二套聊天页面。
- 通过 AI Limbs Dispatcher 暴露通知、收件、回复与会话生命周期能力。
- 通知接口只暴露数量与序号，只有明确读取收件箱时才返回用户消息正文。
- 使用独立 applicationId，让 V0.6.3 与 V0.6.2 并存验证。

## 边界

- 不启动 HTTP 服务，不监听网络端口，不保留原型网页或 Python 服务。
- 不把兰儿桥接聊天伪装成 OpenAI、DeepSeek 或任意远端模型请求。
- 不改变既有 RDC Bridge Provider、工具权限链和普通 API 聊天行为。
- 不加入自动降级；Bridge 路由失效时必须明确报错。

## 实现步骤

1. [消息协议与持久化（已完成）](01_protocol_and_storage.md)
2. [聊天界面与插件接管（已完成）](02_chat_ui_and_plugin.md)
3. [能力注册与云端交付（已完成）](03_registry_and_delivery.md)

## 验收

- 首页可选择 API 聊天或兰儿桥接聊天。
- 从任一聊天模式均可返回首页，切换后仍保留上次 API 配置。
- Bridge 消息产生唯一 `request_id` 和递增 `seq`，可查询、读取、回复和取消。
- `notification.check` 与 `notification.wait` 的返回内容不含消息正文。
- 重复回复同一 `request_id` 不产生第二条回答。
- Bridge 模式不会创建任何网络模型请求，也不会出现普通模型配置菜单。
- 新增调用名同时存在于 Dispatcher 与 Capability Registry。
- 正式构建仅由 GitHub Actions 执行，本地不运行 Gradle。

## 实际实现与原方案的调整

- 正式版只实现 `Notification -> Inbox -> Reply` 主通路，没有发布可选的
  `ai_limbs.chat.exchange`。这样不会形成一条“直接把正文推入兰儿上下文”的第二协议。
- Bridge 配置以稳定 ID 存入 DataStore，但不加入普通模型配置列表；API 配置 ID 单独记忆，切换
  Bridge 不会覆盖 API Key、Endpoint 或模型选择。
- 首页同时显示 RDC、Laner Bridge、兰儿 Session 和待回复数；Session 在线状态由最近一次正式聊天
  调用刷新，并使用 60 秒可见窗口。
- 返回首页在回答流进行中会暂时禁用，避免把全局 Provider 切换到另一条传输链后再让旧回答落入错误
  会话。
- `notification.wait` 严格限制为 30 秒以内，并对消息恰好到达检查/订阅间隙的竞态做了二次序号
  检查。
- 精确 `request_id` 拉取不受当前活动 Session 误过滤；重复回复、重复显式关闭会话及取消都不会
  产生第二次可见结果。

## 已知后续可靠性边界

- 消息、会话、投递状态、回复正文与递增序号均可跨 App 重启恢复；但 App 进程已重启时，旧内存
  `Stream<String>` 不存在，已持久化回复不会自动补写回重启前尚未完成的聊天气泡。该恢复注入与
  Agent Lease 一并留在 Reliability 阶段实现。
- 本版不实现多兰儿接收者的 Agent Lease，也不做 50/100 轮长时间压力结论；这些不应在没有实测
  前伪装成已完成能力。

## 版本与构建

- `applicationIdSuffix = .ailimbs.v063`
- `versionNameSuffix = -ai-limbs-v0.6.3-build1`
- App 名称为 `AI Limbs v0.6.3`，可与 V0.6.2 并存安装。
- 本地仅执行源码、XML 资源引用、Registry/Dispatcher 对称性与 Git whitespace 静态检查；正式
  Android 编译交给 GitHub Actions。
