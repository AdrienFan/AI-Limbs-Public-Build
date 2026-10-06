---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-cloud-validation-pending
---

# build107 宿主绑定校验同步

build106 云任务 37459557505 在 Validate runtime capability registry 阶段失败，尚未运行 Gradle。该任务对应 bc45562886ee58f2a63a9ebc5554cac75beb9052。

## 根因与修复

build105 为 host.permission@1、host.camera.capture@1、host.camera.session@1 启用了 Android Host-framework 操作绑定，但 check_runtime_capability_registry.py 的显式白名单仍只有 screen.capture、chat、window.overlay，因此当前正确绑定被旧预期拒绝。

白名单同步加入三项；集合相等检查与重复项检查继续保留。再检查三项绑定必须为 HOST_FRAMEWORK、必须启用 enforceAffinity、必须保留各自完整的 kernel 操作集合。错误信息同时列出预期值与实际值。没有关闭、跳过或放宽到任意能力通过。

新增三个 JVM 回归验证权限申请、单帧相机和相机会话的全部操作均要求 Android 宿主。现有云任务已包含 HostPrimitiveAffinityRoutingTest，无需改工作流。

## 版本与验证

保留 build105 的视觉宿主生命周期和 build106 的搜索索引优化，运行时实现不改。版本改为 0.8.0.16-build107、versionCode 202；稳定包身份不变。云端仍只执行 assembleDebug，产出一个可更新 APK。

已静态审阅修改及关联断言。本轮不执行本地检查脚本、JVM 测试或构建；完整原有 CI 检查及新增用例由新的云任务执行。编译完成和真机效果仍待验收。

[DONE] 修复与回归用例完成，云端结果待记录。
