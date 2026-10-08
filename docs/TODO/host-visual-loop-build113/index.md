---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: implementation
---

# build113 通用 Host 调用与触控反馈

build112 的 Host component 执行循环每轮固定等待 350 ms，多次串行操作会累加该等待。视觉插件记录单次点击 Host 阶段 1852–1961 ms，状态查询约 350 ms；确切改善幅度需新版部署对照。队列及进程间机制是基座通用能力，不按视觉插件或桥身份分支。

## 请求队列

Core 发布请求时增加唤醒版本并通知等待者。Host component_poll 可明确携带 wait_ms，范围 0..2000；队列有请求立即返回，空队列等待通知后返回。组件执行循环不再增加固定 350 ms 等待；presentation 快照循环保持原间隔。

generation 信号防止空队列检查与等待之间丢通知；socket 的控制期限保持 5 秒。Host instance/generation 检查保留，并在长等待后认领前重新验证。主机替换或取消唤醒等待者，不重新执行动作。请求包括 created_elapsed_ms、claimed_elapsed_ms，结果带 queue_wait 与 host_execution。保留已有生命周期隔离、授权和错误处理。

## 触控与反馈

host.ui.automation@1/tap 支持 show_touch_feedback=true/false，默认 true，控制操作圆环；screen_feedback 控制回图，两者独立。无障碍、Debugger 与 Root 后端在注入前校验该参数。视觉插件明确传 false。此前反馈仍可移除，避免旧圆环留在新动作观察中。

反馈层存储屏幕绝对坐标，在布局时读取实际 ComposeView 屏幕原点转换到本地坐标，去除固定状态栏高度扣减。适用于横竖屏及实际窗口偏移，部署后仍需位置验收。

无障碍点按、长按、滑动等待 GestureResultCallback。终态 completed/cancelled/rejected/timeout 不相互覆盖，只有 completed 返回成功；等待在 Binder/IO 线程，不能阻塞主 Looper。没有回调不能被当成成功，不增加动作重放。传统 Boolean AIDL 契约保留，失败终态记录到日志，成功工具结果标记 gesture_completed；其它后端标记 backend_returned，不宣称应用已经处理完成。

## 计时与路由

UIActionResultData 新增可选 backend、completionState、timingsMs。Host 结果记录后端选择、presentation_begin、backend_execution、presentation_end 与 total。共存路由按原有顺序惰性探测，选择可用后端后不再探测无用的 Root/Debugger；不是改变既有路由或增加回退。

选定屏幕缓冲帧与 copyStarted 时间戳放入同一 producer 锁，修复帧年龄偶发负数。计时不强制归零，不改网络、图片编码策略或视觉业务。

## 云端验证

保留既有云端测试，新增唤醒竞态、闲置中断、按路由懒探测和手势终态回归。云编译产物为 0.8.0.19-build113、versionCode 208，稳定 applicationId 不变。需和视觉 0.2.10 配套部署后验证。

[DONE] implementation；构建及实机验收另行记录。
