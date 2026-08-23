---
title: AI Limbs V0.6.3.1 chat bootstrap and presence fixes
status: ready-for-ci
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/v0.6.3.1-chat-bootstrap-presence
---

# V0.6.3.1 聊天初始化与在线状态修复

## 原始状况

V0.6.3 已具备 Laner Chat Bridge 的请求、通知、收件箱和回复链路，但新安装环境没有当前 Chat 时，输入界面仍显示为可操作状态，发送动作却会在 UI 层直接返回。既有首聊检查函数没有调用点，而且空值判断方向与其意图相反。

现有协议只允许通过 `request_id` 回复用户消息，不能创建不依赖请求的 AI 主动消息。聊天页右下角状态 Badge 只依据 Bridge 传输状态着色，无法区分兰儿 Session 是否在线。

## 修正意图

- 配置完成并进入聊天页后自动创建首个 Chat
- 发送前再次确保 Chat 已创建，并等待当前 Chat 状态可用
- 首聊创建期间隐藏可操作输入区，避免假可用界面
- 新增正式、幂等的 `ai_limbs.chat.send` 主动消息调用
- 将聊天状态显示为 Bridge 与 Session 联合判断的绿、黄、灰三态
- 保持 V0.6.3 的请求、回复和持久化数据向前兼容

## 作用域

- ChatViewModel 与 AIChatScreen 的首聊初始化
- Laner Chat 会话绑定与主动消息持久化
- Dispatcher、Capability Registry 与 System Access Policy
- V0.6.3.1 独立安装包版本
- 静态一致性检查与 GitHub `assembleDebug` 工作流

## 实现结果

- 删除无调用且判断方向错误的旧首聊检查函数。
- 首聊创建由共享 `ChatHistoryDelegate` 的 Mutex 串行化，页面初始化和发送入口复用同一能力。
- Session 持久化绑定 Chat ID，主动消息不会写入当前打开的 API Chat。
- Laner Chat 协议版本升至 2，并公开主动消息支持与投递计数。
- V0.6.3 的已有 Session、Request 与回复数据字段均保留；新增序列化字段提供默认值。

[DONE]
