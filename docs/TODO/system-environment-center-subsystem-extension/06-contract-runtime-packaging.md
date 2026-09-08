# 正式基座共享 Contract 与 Child Runtime ABI 修复

## 现象与根因

系统环境中心 v0.2.0 安装到 AI Limbs 0.8.0.4-build2 后，`android_inprocess` mount 无法解析 `SystemEnvironmentCapabilityIds`。进一步核对发现，Work 把 `system-environment-contract` 和新的 Child Runtime ABI 只加进了 Plugin-Lab Alt Host，而没有同步到真正的 AI Limbs 基座。

`SystemEnvironmentSubsystemContribution` 不是普通常量，而是父 `.ailp` 与子 `.ailx` 直接传递的共享类型。父、子各自嵌入 Contract 会产生不同 ClassLoader 类型身份，最终在 `binding.payload as? SystemEnvironmentSubsystemContribution` 处失败。

## 正确边界

真正 AI Limbs Host 持有唯一的 `system-environment-contract@1` 类型身份，并补齐通用 Child Runtime ABI：`runtimeEntryFile`、`nativeRuntime`、`createExtensionContext()` / `createRuntimeContext()`。系统环境中心与 Ubuntu `.ailx` 均以 `compileOnly` 引用 Contract；Host 只拥有共享 ABI，不拥有 Ubuntu Runtime、PTY、rootfs 或 Display 实现。

## 版本与构建

系统环境中心保持 v0.2.1，用新版本避开已安装 v0.2.0 的 digest 冲突。`system-environment` 快捷 target 继续只构建父插件，用于父插件单独修复验证。完整实机链路还需要安装匹配的正式 AI Limbs 0.8.0.4-build3 与 Extension Hub 1.5.0，再通过系统环境中心安装 Ubuntu `.ailx`。
