---
feature: SentinelX Native Ops v0.1.9
repo: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
status: ready-for-ci
---

# SentinelX Native Ops v0.1.9

## 目标
在不复制 AI Limbs 权限、Ubuntu 业务逻辑或插件注册表的前提下，把 SentinelX 上游常用标准 op 薄适配到现有 Host / System Environment capability。通用 `AIL_SENTINEL_BRIDGE_V1` 保留为扩展能力入口。

## 已完成
- [DONE] `read / list / search` 原生只读 op。
- [DONE] Android / Linux 执行目标语义：显式 `target/environment` 优先，标准工具走固定路径命名空间兼容。
- [DONE] 所有业务调用仍经 `BridgeRemoteIngress -> Dispatcher / Policy Engine`。
- [DONE] `edit`：replace / regex / replace-block / append / prepend / write，危险或无法忠实映射的高级参数明确失败。
- [DONE] `script_run`：一次性 bash/python3 与后台 process，复用 System Environment。
- [DONE] `service / restart`：经 System Environment 执行，不在 child 内实现服务管理器。
- [DONE] `capabilities / help / state` 报告真实原生 op、执行目标和权限归属。
- [DONE] 通用 exec 隧道保留，不再承担普通 read/list/search。
- [DONE] 稳定错误码补充：target_not_running / capability_not_found / permission_denied / timeout 等。
- [DONE] 原有 exec 分页、媒体附件和 file_export 通道保持不变。
- [DONE] 新增路由、glob、编辑 helper、UTF-8 与 capability 声明回归测试。
- [DONE] 版本同步到 0.1.9 / versionCode 10 / applicationId v019。
- [PENDING CI] GitHub Actions SentinelX 专用构建、单测、签名与产物上传。

## 不变量
- 子插件不得维护静态 AI Limbs capability allowlist。
- 不修改基座架构，不把 Ubuntu 业务实现复制进 SentinelX。
- 不添加 silent fallback；能力或参数无法忠实映射时返回明确错误。
- RDC 的持续交互 terminal/session 不在本轮复制；SentinelX 只复用已有后台 process 能力。
- 源码身份以 `ail-source dev "SentinelX"` 为准。


## 0.1.9 实机部署回归
- [FOUND] native op 已进入 Linux/Ubuntu；read/list/search/script_run 不再 unsupported。
- [FOUND] AI Limbs 成功响应仍含空 error 字段，0.1.9 误把字段存在当成失败，外层表现为 ok=false / error=null。
- [FIXED IN SOURCE] 仅非空、非 JSON null、非字符串 null 的 error 判失败。
- [FIXED IN SOURCE] 新增空 error 回归测试；补丁版本升到 0.1.10。
- [PENDING CI/DEPLOY] 编译并部署 0.1.10 后重跑 read/list/search/script_run/edit。


## 0.1.10 -> 0.1.11 实机回归
- [PASS] Linux read/list/search/script_run。
- [PASS] Linux persistent-path edit；写后 read 验证 alpha/gamma。
- [PASS] Android list。
- [FOUND] JSON null 的 reason_code 被 optString 变成字符串 null，破坏错误码。
- [FIXED] 统一 jsonTextOrNull；错误码回退恢复 not_found 等稳定语义。
- [FOUND] 成功 script_run 的空 error 被上游呈现为字符串 null。
- [FIXED] 成功时省略空 error 字段。
- [FOUND] Hub background job + child internal background process 形成双重后台语义，旧 job 变 orphaned。
- [FIXED] child 改回同步 System Environment command；Hub 独占 background/job/notifications 语义。
- [FOUND] 当前 Ubuntu 非 systemd init，service/restart 无法忠实映射。
- [FIXED] 0.1.11 不再 advertise service/restart。
- [NEXT] 云端编译 0.1.11 后部署复测。
