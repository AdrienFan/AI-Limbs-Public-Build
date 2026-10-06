---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/visual-permission-lifecycle
source_base: 2bc47457e77ea6ce067ab3aaae229d7063cf29d3
status: source-ready-build-paused
---

# build109 操作后共享屏反馈

原实现：界面操作和视觉取帧是独立调用。调用方需要额外请求截图；现有 ImageReader 还可能返回操作前排队的画面。

目标：已有共享屏会话时，明确操控手机界面的宿主工具执行结束后附一张新帧；保留原操作 success、error、result 和 events。反馈失败仅出现在 operation_feedback，不重放操作，不读取历史预览，不开启共享屏。

## 步骤与作用域

1. [DONE] Dispatcher 的宿主工具出口支持通用 Provider 反馈，覆盖 native 入口和 host.ui.automation@1 的无障碍操作入口
2. [DONE] Shell 增加可选布尔参数 screen_action，默认 false；命令正文不参与分类
3. [DONE] Host 会话 frame 的 fresh=true 只允许已有 READY 会话，复用现有授权与 VirtualDisplay，在操作后接入空 ImageReader Surface 并等待新帧
4. [DONE] 增加触发范围、原结果保留、失败隔离、旧帧拒绝、调用取消及 Provider 超时的回归源码，并纳入后续云端测试选择
5. 云编译和实机验收暂停，等待用户后续修改完成

在 build108 基础上保留此前 UI 命令截止时间及单次请求修复。build109 versionCode=204，稳定包身份不变。将来获准编译时只产出 assembleDebug 对应的一份可更新基座，不编译 clone。

## 通用反馈契约 v1

Provider 使用现有 InProcessCapabilityExecutor，不改变插件 ABI。规范 metadata 为 kind=operation_feedback、feedback_api=1、event=screen_interaction。基座从活跃 canonical registry 发现 Provider，使用实际登记 owner，调用前后检查登记仍有效，不硬编码任何视觉插件身份。

请求字段：schema=1、event、operation_id、tool、operation_success、completed_at_ms、completed_elapsed_ms、deadline_elapsed_ms。请求不包含原始 shell 命令、输入文字或其他操作参数。elapsed 时间均为 Android SystemClock.elapsedRealtime，避免跨进程时钟不一致及用户调整日期。

响应字段：schema=1、operation_id、status=INACTIVE/READY/FAILED。INACTIVE 不附图也不向原结果增加反馈字段。READY 含 captured_at_ms、freshness、一个 mcp_content 图像及图像元数据。FAILED 含独立 error_code 和 error，基座移除其图像字段。operation_id 不匹配、缺少新帧证明或格式错误也记为反馈失败。

freshness 包含 method=new_surface、requested_elapsed_ms、captured_elapsed_ms。基座核验请求新帧不早于操作完成，取得帧不早于请求。该证明表明取得的是操作后重新渲染的共享画面，不承诺应用动画或异步业务已全部完成。

## 触发范围

tap、long_press、click_element、swipe、set_input_text、press_key、start_app、stop_app、run_ui_subagent 默认触发。它们使用统一宿主工具出口，涵盖无障碍、调试器等已选后端。子代理单次调用结束时取最后画面，不给其内部每个动作重复加图。

get_page_info、截图查询、文件/进程/日志/网络查询及浏览器网页操作不触发。execute_shell 仅接受真正布尔类型的 screen_action；缺省或 false 不触发，字符串 true 无效且不会执行命令。

Shell 示例：`{"command":"input tap 300 500","screen_action":true}`。查询示例：`{"command":"logcat -d -t 40"}`。

## 资源与超时

Host fresh frame 不调用普通截图的授权申请入口，不创建第二个 VirtualDisplay；授权丢失、会话非 READY 或过期请求明确失败。取帧等待以 ImageReader 新图事件结束，最多等待 2 秒，没有固定动画等待、轮询或重试。停止时唤醒等待者并使结果无效，临时帧位于 owner 独立目录。

反馈 Provider 的跨进程 socket 读取预算为 6 秒，普通 Provider 仍使用原有业务预算。请求携带统一 deadline，Host 拒绝迟到请求和迟到结果。操作本身不受反馈结果改写。

视觉插件业务在插件内：活跃会话选择、JPEG 编码、预览发布、图像封装及清理。反馈只传一份 base64，JPEG 上限 512 KiB，最长边 1024，适配现有 1 MiB Worker 帧上限。桥现有递归 mcp_content 转发即可显示图像，本次不改桥。

## 验证状态

新增 AiLimbsOperationFeedbackTest 和 OperationFeedbackProviderPolicyTest。源码静态检查完成后记录提交；本轮未运行编译、构建或测试，未发起云任务。

后续实机验收需覆盖无障碍各动作、标记/未标记 Shell、共享屏关闭、系统停止共享、插件停止/卸载、普通查询无图、操作失败时保留原结果、反馈超时和图片通道上限。部署前不能声称这些运行用例已通过。

## Android 依据

- [VirtualDisplay.setSurface](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface))：现有虚拟显示可更换输出 Surface
- [Media projection](https://developer.android.com/media/grow/media-projection)：Android 14 同一个 MediaProjection 不能多次 createVirtualDisplay，本方案复用现有显示
