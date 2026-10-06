---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-cloud-validation-pending
---

# Resident UI 命令截止时间修复

## 现场与根因

2026-10-06 22:06:48.472 开始屏幕授权，22:06:51.732 授权成功，22:06:52.506 已取得截图，22:06:53.447 UI transport 记录 Connection refused。第二次 22:07:15.389 开始授权、22:07:19.437 成功取图，22:07:19.559 又出现同样 UI 错误。当前 status 也保留了成功取屏的预览元数据。

Resident UI 请求统一使用 5 秒截止时间，既用于短轮询，也用于需要系统授权和取首帧的插件命令。已知 session 请求超时后又转到 legacy socket，产生 Connection refused 并掩盖原始失败，且可能重放已执行动作。连接异常返回 ok=false/error_code/error；视觉 UI 直接读取 success，导致错误被替换为 No value for success。

## 作用域

基座通用 UI wire：command 使用 180 秒业务截止时间，覆盖已有 120 秒 ActivityResult 授权窗口；attach/snapshot/events/component 控制请求仍为 5 秒。已知 session 只访问该 session 的精确 socket，保留原始异常，不进行 legacy 重放。认证、UID/PID/session/host generation 校验保持不变。不加入视觉插件身份特判。

## 验证与版本

基座 0.8.0.16-build108、versionCode 203；延续固定 stable 包名，仅云编译单个可更新 debug APK。新增命令预算及控制预算用例，云端回归列表同步加入。

源码静态审阅完成。云端测试与编译待完成；部署后应延迟数秒确认屏幕授权，核对首帧、会话状态与停止。连接错误应展示真实原因，不应伪装成字段缺失或操作成功。

[DONE] 代码、用例与版本更新。

[TODO] 云端与部署验收。
