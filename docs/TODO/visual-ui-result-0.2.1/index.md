---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: source-complete-cloud-validation-pending
---

# 视觉工作台连接错误结果处理

## 现场与根因

2026-10-06 22:06:48.472 开始屏幕授权，22:06:51.732 授权成功，22:06:52.506 已取得截图，22:06:53.447 UI transport 记录 Connection refused。第二次 22:07:15.389 开始授权、22:07:19.437 成功取图，22:07:19.559 又出现同样 UI 错误。当前 status 也保留了成功取屏的预览元数据。

Resident UI 请求统一使用 5 秒截止时间，既用于短轮询，也用于需要系统授权和取首帧的插件命令。已知 session 请求超时后又转到 legacy socket，产生 Connection refused 并掩盖原始失败，且可能重放已执行动作。连接异常返回 ok=false/error_code/error；视觉 UI 直接读取 success，导致错误被替换为 No value for success。

## 作用域

视觉 UI 校验业务 success 之前先识别正式 presentation transport 的 ok=false，保留原始 error_code/error/details。缺少业务状态返回明确 VISUAL_RESULT_INVALID，取消或拒绝继续使用业务原始错误。正常图像和状态对象原样返回，不虚构 success。

## 验证与版本

视觉插件 0.2.1、versionCode 7、独立 v021 applicationId。新增 5 项连接失败、缺少状态、用户拒绝、正常图像及矛盾结果回归。

源码静态审阅完成。云端测试与编译待完成；部署后应延迟数秒确认屏幕授权，核对首帧、会话状态与停止。连接错误应展示真实原因，不应伪装成字段缺失或操作成功。

[DONE] 代码、用例与版本更新。

[TODO] 云端与部署验收。
