# 01 Contract 与机制核查

## 旧实现

父插件直接 import 并 mount UbuntuSubsystem，没有独立的系统环境 Extension Point。

## 修改意图

核对开发说明、Bridge、Extension Hub 与 InProcess API，沿用现有 ChildExtensionEntry、ChildExtensionBinder 和 Shared UI 机制。

## 期待结果

形成独立 typed Contract、稳定 Extension Point ID 与父级 Subsystem Registry；不复制 Extension Hub 职责。


[DONE]

结论：扩展点定为 `ai_limbs.system_environment.subsystem@1`。父级只维护 active/foreground 分离的 registry、通用外壳和能力路由；子级发布 typed contribution。为支持带资源与 JNI 的 `.ailx`，给 ChildExtensionHost 增加 runtimeEntryFile、nativeRuntime 与 createExtensionContext 三项通用能力。
