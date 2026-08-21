# 静态检查与编译验证

## 检查范围

- Manager 不直接引用 `RdcBridgeProvider`
- Registry 能创建 active Profile 对应 Provider
- active Provider 能持久化并切换
- External Process 只有 Profile，没有后台 shell 实现
- 通知和 Tool caller 使用中性 Bridge 语义
- 现有权限链保持不变

## 验证顺序

先审查差异和引用关系，再执行目标模块的 Kotlin 编译验证。

不改写 V0.5.1 基线，不执行与本次改造无关的全量构建。

## 验证记录 [DONE]

- `git diff --check` 通过，约束项和引用关系已静态复核。
- 本地 Gradle 在依赖准备阶段按用户指示停止，不记录编译通过结论。
- 代码推送后以 GitHub Actions 的公有仓库构建结果为准。
