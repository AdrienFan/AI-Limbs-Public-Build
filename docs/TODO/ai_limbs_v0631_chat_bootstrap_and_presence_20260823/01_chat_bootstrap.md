# 01 首个 Chat 初始化

## 旧实现

- `checkIfShouldCreateNewChat()` 只有定义，没有调用。
- 方法在 `currentChatId == null` 时返回 false，不能承担首聊创建判断。
- 输入区在 Chat 不存在时仍显示，发送动作随后静默返回。

## 新实现目标

- 使用互斥创建流程保证并发入口最多创建一个 Chat。
- 页面进入后自动创建，发送动作再次调用同一正式流程。
- 创建完成前不展示可操作输入区；创建失败明确显示错误。

## 已实现

- `AIChatScreen` 在配置初始化完成、连接首页关闭且当前 Chat 为空时调用 `ensureCurrentChat()`。
- `ChatHistoryDelegate` 使用共享 Mutex 串行化首聊创建，并等待 DataStore 当前 Chat ID 完成传播。
- 发送入口在执行 Hook 与消息管理器前再次调用相同方法，并使用已经确认的 Chat ID。
- 当前 Chat 为空时，底部只显示“正在创建首个对话”，不再暴露可输入、可点击但无法发送的控件。
- 创建过程失败或超时会记录异常并显示明确错误，不再静默返回。

[DONE]
