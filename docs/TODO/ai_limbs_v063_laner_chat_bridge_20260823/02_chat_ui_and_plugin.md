# 聊天界面与插件接管（已完成）

## 旧实现

AI 对话在 API 配置完成后直接进入聊天页，无法回到连接方式入口。消息只能交给普通 AI Service
处理，模型下拉菜单也始终按 API Provider 展示。

## 修改

- 将初始化页扩展为连接方式首页。
- 记住最后使用的普通 API 配置，Bridge 配置使用稳定的独立 ID。
- 在顶栏提供返回聊天首页的动作。
- 注册 `LanerChatMessageProcessingPlugin`，仅匹配 Bridge Provider ID。
- Bridge 模式隐藏普通模型设置并展示桥连接状态。
- Bridge Provider Stub 只负责配置可用性，若消息绕过插件则明确失败。
- Bridge 模式关闭本地群组编排、Thinking、自动总结、记忆自动更新和模型生成标题。
- 首页显示 RDC、Bridge Core、兰儿 Session 和待回复消息状态。

## 期待结果

两种聊天方式复用相同聊天记录与渲染链，但传输链完全隔离；Bridge 模式不会调用模型 API。

[DONE]
