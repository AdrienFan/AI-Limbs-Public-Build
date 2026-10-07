# AI Limbs 视觉工作台 0.2.2

本版新增操作后共享屏反馈，需要基座 0.8.0.16-build109 或更新版本。保留 0.2.1 的连接错误处理和 0.2.0 的工作台能力。逻辑插件身份仍为 plugin.system.visual_manager。

工作台分为屏幕、摄像头、页面文字和图像记录。宽屏左侧操作、右侧画面；窄屏未获取画面时先显示操作，已有画面时先显示画面；状态和全部停止固定置顶。

## 运行与授权

用户点击界面按钮，经签名 presentation 的 owned UI direct 通道执行本插件操作，不向兰儿申请批准。兰儿的调用使用基座现有授权规则，插件不增加额外逐次 ASK。Android 相机权限、屏幕共享范围等系统同意由 Host 处理，插件不直接持有 Activity 或 CameraManager。

相机使用流程为 sources → permission request → start → frame → stop。授权被拒绝或取消会返回明确失败，需要手动处理，不自动反复弹窗。可用 permission open_settings 进入 AI Limbs 系统权限设置。系统已授权时无需再次弹出相机权限请求。

Host 用相机前台服务托管会话。首次开启时短暂显示自动获取前台运行条件的页面，没有确认按钮；开启后可切回 ChatGPT，通知栏提供停止入口。界面重新获得焦点时即时读取状态，不设置查询计时器。此生命周期链路仍需新版安装后实机验证。

start 只有取得首帧才返回成功。同一类在本插件同时一个会话；切换镜头前明确停止。frame 获取新画面，status 和 preview.read 只查询已有数据，不重新拍摄。画面标明获取时间，停止后最后一帧仍能查看，不能把历史画面当成正在拍摄。

功能即时调用，不设置倒计时、延迟执行或固定时间后的查询前置条件。兰儿在获得授权后按需自行开始、取图和停止。界面订阅 Core 的只读状态通道，收到变化后同步双方状态；不设置视觉插件自己的查询或自动取帧定时器。离开界面不擅自停止另一方的会话。取帧失败明确显示错误，不自动重试。停止可在其他操作期间发起，宿主实际释放资源后才报告成功。

临时预览最长边 1024，不自动归档。preview.save 保存当前预览；frame save=true 和 capture 保存原图。图像记录上限 60 张、64 MiB，超出后删除最旧记录。清空图像记录不停止会话，也不清除临时预览。

当前屏幕取图支持内置屏幕；sources 会给其他显示目标标注 capture_supported=false，界面不提供错误的取图入口。摄像头使用系统列出的实际镜头，不自动换到其他镜头。

## 操作后共享屏反馈

已有 READY 屏幕共享时，无障碍及其他已选手机后端的点击、长按、滑动、输入、按键、启动/停止应用等明确界面操作完成后，基座通过通用 Provider 请求一张新图。Shell 只有显式设置 screen_action=true 才附图；日志、进程、文件、网络或页面查询不附图，命令正文不用于猜测用途。

反馈只使用本插件已经开启的屏幕共享，不打开相机、不申请新授权，不返回旧预览。Host 在操作后为现有 VirtualDisplay 接入新 Surface，返回取帧时间和 elapsedRealtime 新帧证明。工作台同步更新预览；新帧不代表应用动画或异步任务已结束。

图片最长边 1024，单张 JPEG 不超过 512 KiB，仅传一份 base64。取图失败、忙碌、停止或过期在 operation_feedback 中说明；原操作结果保留，不重试操作或截图。反馈不自动归档，并清理临时帧。

## 能力与结果

所有结果含 success。success=false 表示操作未完成，含 error_code、error 和 details；不可仅凭工具返回就弹成功提示。stop 部分失败会保留各来源的处理结果。读取图像返回 data、mime_type 以及 mcp_content，UI 与兰儿使用同一份画面。

- status：真实会话、权限、操作状态、最新预览摘要和图像记录摘要
- sources：kind=screen/camera，列出可选来源
- permission：operation=check/request/open_settings，相机系统权限
- start：kind、source_id，启动并返回首帧和 session_id
- frame：kind、session_id、save=false/true，获取新画面
- capture：kind、source_id，单拍并保存；已有相机会话用 frame save=true
- stop：kind=screen/camera/all，可选 session_id
- preview.read / preview.save：kind，读取或保存当前预览
- images.list / images.read / images.delete / images.clear：图像记录管理
- page.inspect / page.text：即时固定页面快照及全文分页

示例，每步读取前一步结果后再调用，不把占位符当真实参数：

```json
{"kind":"camera"}
```

上例用于 sources。接着 permission 使用 {"operation":"request"}；成功后 start 使用 {"kind":"camera","source_id":"0"}，其中 0 必须来自 sources。frame 使用 start 返回的 session_id。stop 使用 {"kind":"all"}。

page.inspect 即时读取当前应用。查询 status.page.latest_snapshot，再按 snapshot_id 调用 page.text；has_more=true 时沿 next_offset 读取全文。直接在工作台调用 page.inspect 读取的就是当前工作台。快照保留最近四份、十分钟，UTF-16 偏移不会拆开表情字符。

## 实现边界与验证

Core 控制器负责业务状态、结果验证、临时预览及记录。presentation 仅调用本插件能力、渲染 JSON 图像和管理可见生命周期；不能访问设备或 Core 私有文件。Host 提供通用权限 token、设备占用与资源释放。

保留可用的完整页面读取器与 Unicode 分页用例。已有未授权启动、拒绝不重试、部分停止失败、取消截图、重复占用和即时状态发布的回归用例。本版新增反馈关闭时不访问设备、途中停止不重试及旧帧拒绝等用例。

本轮未运行 Gradle、编译、构建或测试。云端编译保持暂停，待后续修改完成；运行效果尚未实机验收。

## 每轮相机画面

基座 build110、视觉插件 0.2.3、ChatGPT 桥 0.0.19 新增 `ai_limbs_message_context`。相机由用户明确开启后，模型在每条新消息开始时调用一次，直接收到该镜头的新帧。未开相机返回 INACTIVE；失败不会返回旧图、重拍或请求权限。该调用是工具反馈，无法替原生 ChatGPT 用户消息加附件，也不能强制上游模型执行。更新后在 ChatGPT 网页/PC 刷新 MCP 工具目录并开启新对话。

## 0.2.4 单次编码反馈

与基座 build111 配套：操作反馈明确请求 RGBA8888 像素帧，在插件内一次缩放并编码为 JPEG，直接保存同一份字节并回传。基座不决定压缩质量、尺寸或批量操作策略。
自动操作反馈最长边 960，JPEG 质量仍为 82。小字需要额外细节时，用 `frame` 的 `max_edge: 2048` 重新取图；普通 frame 默认 1024 不变。
新路径不生成整屏 PNG，也不把自己刚编码的 Base64 再解码落盘。原始帧必须为紧密排列 RGBA8888，长度及步幅严格匹配，最多 64 MiB。没有 raw 支持时明确报错。
一张文字界面的 960 预览约 72 KB，1024 约 84 KB；这只是单张可读性对照，所有页面的小字和实际速度仍需安装后验证。
