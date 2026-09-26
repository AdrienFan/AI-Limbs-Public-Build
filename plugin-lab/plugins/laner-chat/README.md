# AI Limbs Laner Chat Plugin（v0.1.0）

这是 LanerChat 从 AI Limbs 基座抽离的第一阶段“影子插件”。

当前目标不是立即接管主聊天室，而是先把现有 LanerChat 的确定性业务核心原样迁出：

- Durable mailbox
- Session 生命周期
- request_id / seq
- HIGH / NORMAL / LOW 优先级
- Notification / Inbox
- Assistant Turn claim / reply / resolve / cancel / resume
- 幂等 reply
- Proactive message 状态
- Queue changed snapshot
- 插件独立持久化

v0.1.0 与基座旧 LanerChat 并存，数据目录彼此隔离，不会接管或修改现有聊天记录。

完成独立编译、安装和状态机验收后，第二阶段才会把基座旧 `ai_limbs.chat.*`
入口与主聊天室 LanerChat 硬引用逐步替换为插件契约；在插件验证前不删除旧实现。
