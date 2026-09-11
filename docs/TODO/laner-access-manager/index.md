---
For_Agent: 兰儿接入管理插件化复刻计划
---
# 兰儿接入管理插件化复刻

现状：V0.6.4.7.8 的工具箱内置 `AiLimbsAccessManagerScreen`，直接访问 `AiLimbsDocumentProvider` 管理自定义接入提示和工作手册。

目标：在 V0.8.0.5-build13 的插件平台中，以独立 android_inprocess `.ailp` 复刻该组件，并通过插件 Home Tile 自动进入工具箱。

边界：数据、历史快照、恢复、Work Manual 保护头仍由 Host 持有。插件只调用 `host.custom_access_prompt@1` 与 `host.work_manual@1`。

文件导入暂不迁移，因为 `host.content@1` 当前未 runtime-bound；禁止插件绕过 Host 直接持有 URI 权限。
