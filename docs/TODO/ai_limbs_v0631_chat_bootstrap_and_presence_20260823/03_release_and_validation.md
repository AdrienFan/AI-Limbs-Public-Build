# 03 版本与验证

## 版本目标

- 独立包名后缀 `.ailimbs.v0631`
- 版本后缀 `-ai-limbs-v0.6.3.1-build1`
- 应用名 `AI Limbs v0.6.3.1`

## 验证目标

- Dispatcher 与 Capability Registry 的 Laner Chat 工具集合完全一致。
- 新增资源在中文和英文基础资源中成对存在。
- Git 差异通过空白与冲突标记检查。
- 不执行本地 Gradle 构建；推送后只触发 GitHub `assembleDebug`。

## 静态验证记录

- Dispatcher 与 Capability Registry 均包含 8 个完全相同的 `ai_limbs.chat.*` 工具。
- 新增中文、英文资源键均各存在一份且引用可解析。
- 旧 `checkIfShouldCreateNewChat` 已无残留定义或调用。
- `git diff --check` 无空白错误，修改文件无冲突标记。
- `terminal` 子模块仍为 `04f4db0cf4039828d1134c76d700e6b59e0f097b`。
- 按项目约定未执行本地 Gradle 构建或测试。

[DONE]
