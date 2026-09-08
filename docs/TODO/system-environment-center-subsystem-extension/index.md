---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: feat/plugin-lab-system-environment-center
status: draft
---

# 系统环境中心子系统扩展拆分

## 原本状况

系统环境中心由 Ubuntu 0.3.7 复制而来，入口仍直接 mount Ubuntu，父插件同时持有 Ubuntu Runtime、终端 Display 与 Ubuntu 专属 capability。

## 意图与结果

把父插件改成可独立 mount 的通用机箱，发布类型化系统环境 Extension Point，并把 Ubuntu 封装为第一枚 .ailx 子插件。旧顶层 Ubuntu 0.3.7 保持不动。

## 作用域

仅修改 system-environment-center、新建 Ubuntu system extension、必要的通用 SDK/Host Contract、相关 manifest、文档与 CI target。禁止本地 Gradle。

## 步骤

- [01 Contract 与机制核查](01-contract.md)
- [02 通用机箱与 Display Slot](02-chassis.md)
- [03 Ubuntu .ailx](03-ubuntu-extension.md)
- [04 共存与静态检查](04-validation.md)
- [05 提交与云端构建](05-cloud-build.md)

- [06 正式基座 Contract 运行时打包修复](06-contract-runtime-packaging.md)
