# 终端界面与工具能力

## 旧实现

欢迎页使用硬编码 Operit ASCII 标识；底部工具栏只有中断、环境配置和终端设置。环境配置页面会立即创建 `setup-check` 会话。

## 修正

- 欢迎页改为 AI LIMBS ASCII 标识
- 在“环境配置”左侧增加 Ubuntu 状态按钮
- 停止态不进入环境配置，也不创建 `setup-check` 会话
- 注册 `ubuntu.status`、`ubuntu.start`、`ubuntu.stop`
- AI 或 Bridge 调用继续经过 `ToolExecutionManager` 权限链

## 验收

界面状态与工具返回使用同一运行时状态源，启动和停止操作具备幂等性。

[DONE]
