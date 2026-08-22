# 视觉与触控能力 [DONE]

- 原生 UI 工具不再捕获启动时的 `StandardUITools`，而是在每次执行时读取当前权限模式。
- `ai_limbs.ui.status` 汇总当前 UI 后端、无障碍 Provider、直接触控和视觉子代理状态。
- Bridge Center 提供显式的无障碍模式选择、Provider 安装/系统授权入口及视觉模型配置入口。
- `Automatic_ui_subagent` 在 V0.5.5 新安装中默认可见；只有配置支持图片的 `UI_CONTROLLER` 模型后才报告就绪。
- Android 系统无障碍授权必须由用户确认，应用不绕过系统授权。
