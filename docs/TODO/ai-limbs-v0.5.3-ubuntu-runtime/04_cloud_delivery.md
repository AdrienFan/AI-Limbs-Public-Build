# 版本隔离与云端交付

## 版本隔离

- V0.5.2 远端分支保持不变
- V0.5.2 基准标签为 `v0.5.2-stable-baseline`
- V0.5.3 使用独立功能分支
- 终端改动提交到 `AdrienFan/OperitTerminalCore`

## 交付

- 主仓固定新的终端子模块提交
- 调试包版本更新为 AI Limbs V0.5.3
- 只通过 GitHub Actions 构建 Android 包
- 推送并触发云端构建后结束本轮，不持续监控任务

[DONE] 代码与版本隔离已准备完毕，交付动作为推送后手动触发一次云端构建。
