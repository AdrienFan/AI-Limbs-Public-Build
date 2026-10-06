---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# 本轮相机视觉上下文

当前仅有手动 frame/status/preview。用户选择每条新消息由模型先取一张相机新帧，再结合画面回答。ChatGPT 原生发送事件没有公开入口；此迭代不声明能修改原用户消息的附件。

范围：基座通用 message_context Provider 协议与 Camera2 当前拍摄请求匹配；视觉插件私有 Provider；ChatGPT 桥的固定工具与服务器指令。ABI 不变。

[实现与验收](01-implementation.md)
