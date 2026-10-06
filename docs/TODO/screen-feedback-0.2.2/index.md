---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/visual-workbench-v02
source_base: 2babc4c7c6831b0fa4ac5e773e219258b1a022e8
status: source-ready-build-paused
---

# 视觉工作台 0.2.2 操作后共享屏反馈

原实现：取帧只由 frame 等能力显式调用。目标是在已有屏幕共享时响应基座的通用操作完成通知，返回一张操作后新图，并更新工作台预览。

逻辑身份仍为 plugin.system.visual_manager；安装载荷版本 0.2.2、versionCode=8。操作后反馈需基座 build109，ChatGPT 桥无需变更。本轮保留 0.2.1 的连接错误结果处理。

## 实现步骤

1. [DONE] 注册 operation_feedback Provider，使用现有 InProcessCapabilityExecutor 传输协议及 canonical ownership
2. [DONE] 仅查询本插件的屏幕会话，不查询相机、不申请授权、不创建新会话
3. [DONE] READY 会话请求 Host frame fresh=true，传入操作 deadline，检查新帧证明及操作标识
4. [DONE] 单次事件传一张 JPEG，最长边 1024，上限 512 KiB；不自动归档、同步工作台预览，清理 owner 临时帧
5. [DONE] 未开启共享返回 INACTIVE；忙碌、停止、超时或坏结果独立返回 FAILED，不重试、不返回历史画面
6. [DONE] 增加未开启不触发设备、途中停止不重试和新帧契约回归源码
7. 云编译与部署验收暂停，等待后续修改完成

原 15 个公开视觉能力继续存在，新增 Provider 不作为额外公开 AI 工具。声明权限集合不变，业务取帧仍经 Host Gateway；Core 不直接持有 Android 设备 API。

停止不排队等待取帧锁，现有 generation 使未完成请求失效。反馈遇到屏幕操作忙碌时明确失败；原手机操作不重做。相机、日志、进程、文件、网络与页面读取不自动附屏幕图。

request/response 使用基座 docs/TODO/screen-feedback-build109 中的 operation feedback v1 契约。响应携带 operation_id、session_id、target_id、captured_at_ms、freshness、image 元数据及唯一 mcp_content 图像。取得新帧不代表应用动画完成；需要继续观察时由调用方明确发起下一次取帧。

## 验证状态

ScreenFeedbackContractTest 覆盖请求后取帧和旧帧拒绝。VisualManagerControllerTest 新增未开启、投影授权丢失、取帧途中停止等用例，确保不打开设备、不重试、不返回旧预览。

本轮只做源码与静态检查，没有运行 Gradle、构建或测试，没有触发云编译。新 Surface 在实机上的新帧事件、跨进程图片大小、停止并发和六秒反馈预算需部署后验收。
