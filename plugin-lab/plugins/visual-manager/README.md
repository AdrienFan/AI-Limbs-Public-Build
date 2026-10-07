# AI Limbs 视觉工作台 0.2.7

本版修复开启共享后及重新进入工作台时的预览崩溃，保留通用画面变化与区域稳定检测，需要基座 0.8.0.18-build112。逻辑插件身份仍为 plugin.system.visual_manager，安装包 payload applicationId 更新为 v027。

工作台分为屏幕、摄像头、页面文字和图像记录。宽屏左侧操作、右侧画面；窄屏未获取画面时先显示操作，已有画面时先显示画面；状态和全部停止固定置顶。

## 运行与授权

用户点击界面按钮，经签名 presentation 的 owned UI direct 通道执行本插件操作，不向兰儿申请批准。兰儿的调用使用基座现有授权规则，插件不增加额外逐次 ASK。Android 相机权限、屏幕共享范围等系统同意由 Host 处理，插件不直接持有 Activity 或 CameraManager。

相机使用流程为 sources → permission request → start → frame → stop。授权被拒绝或取消会返回明确失败，需要手动处理，不自动反复弹窗。可用 permission open_settings 进入 AI Limbs 系统权限设置。系统已授权时无需再次弹出相机权限请求。

Host 用相机前台服务托管会话。首次开启时短暂显示自动获取前台运行条件的页面，没有确认按钮；开启后可切回 ChatGPT，通知栏提供停止入口。界面重新获得焦点时即时读取状态，不设置查询计时器。此生命周期链路仍需新版安装后实机验证。

start 只有取得首帧才返回成功。同一类在本插件同时一个会话；切换镜头前明确停止。frame 获取新画面，status 和 preview.read 只查询已有数据，不重新拍摄。画面标明获取时间，停止后最后一帧仍能查看，不能把历史画面当成正在拍摄。

兰儿在获得授权后按需自行开始、取图、等待变化及停止。界面订阅 Core 的只读状态通道，收到变化后同步双方状态，不设置常驻取帧计时器。新等待能力仅在一次显式调用期间进行有上限的像素采样。离开界面不擅自停止另一方的会话。取帧失败明确显示错误，不自动重试。停止可在其他操作期间发起，宿主实际释放资源后才报告成功。

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

0.2.7 通过云端工作流执行测试、编译和签名打包；安装后的共享、重进工作台及等待与动画表现仍需实机验收。

## 0.2.7 图片附件与预览读取修复

日志中的 `JSONException: No value for data` 来自预览渲染：Core 已将图片内容移至唯一的 `mcp_content` 图片附件并从元数据移除 `data`，UI 仍读取元数据的旧字段。共享授权和虚拟显示已成功，重新进入工作台时读取已有预览也会触发相同异常。

Core 与 UI 现在使用同一附件契约。屏幕、相机、恢复预览、图像记录和缩略图在操作协程内读取附件并验证元数据，再发布不可变的预览状态；Compose 渲染不再读取图片 JSON 字段。缺失、空白、重复或格式不一致的附件显示读取错误；位图解码继续在 IO 协程内处理错误。不回退至旧字段、不增加第二份 Base64、不读取 Core 私有文件，也不需要清空已有预览或图像记录。

新增七项契约回归测试，覆盖实际附件写入函数与 UI 读取函数间的序列化边界、首次共享、重新进入、相机、存图、缩略图及异常响应。详情见 [修复记录](../../../../docs/TODO/visual-preview-0.2.7/index.md)。

## 每轮相机画面

基座 build110、视觉插件 0.2.3、ChatGPT 桥 0.0.19 新增 `ai_limbs_message_context`。相机由用户明确开启后，模型在每条新消息开始时调用一次，直接收到该镜头的新帧。未开相机返回 INACTIVE；失败不会返回旧图、重拍或请求权限。该调用是工具反馈，无法替原生 ChatGPT 用户消息加附件，也不能强制上游模型执行。更新后在 ChatGPT 网页/PC 刷新 MCP 工具目录并开启新对话。

## 0.2.5 单次编码反馈

与基座 build111 配套：操作反馈明确请求 RGBA8888 像素帧，在插件内一次缩放并编码为 JPEG，直接保存同一份字节并回传。基座不决定压缩质量、尺寸或批量操作策略。
自动操作反馈最长边 960，JPEG 质量仍为 82。小字需要额外细节时，用 `frame` 的 `max_edge: 2048` 重新取图；普通 frame 默认 1024 不变。
新路径不生成整屏 PNG，也不把自己刚编码的 Base64 再解码落盘。原始帧必须为紧密排列 RGBA8888，长度及步幅严格匹配，最多 64 MiB。没有 raw 支持时明确报错。
一张文字界面的 960 预览约 72 KB，1024 约 84 KB；这只是单张可读性对照，所有页面的小字和实际速度仍需安装后验证。

## 通用帧与坐标

基座 build112 提供旋转同步和帧几何信息。get_frame 与 tap_on_frame 在本插件注册，所有已授权桥通过同一能力目录调用。帧图像只走附件；JSON 保留编号、原始和预览尺寸、映射及计时。点击完成与观察完成分开报告。单应用共享缺少屏幕偏移时不提供猜测坐标。

## 0.2.6 画面变化与稳定

新增 wait_for_visual_change 与 wait_until_stable，参数 frame_id 必须为最新屏幕帧；会话、几何和未压缩像素基准绑定该帧。不会自行开启共享屏或重放动作。

tap_on_frame 保持原来的 observe_mode=new_frame 默认行为。页面跳转可显式选择 change_then_stable；只观察最终页面稳定时可选择 stable。等待默认上限 5000 毫秒、稳定窗口 250 毫秒、采样间隔 100 毫秒。可通过 region_left/top/width/height 指定图片比例区域，排除持续动画。内部使用 64×64 RGB 网格，最终回图时才编码一次 JPEG。

必须检查 wait_success 或 visual_wait.condition_met。TIMEOUT 可以带最后观察图像，但不表示稳定；success=true 仅代表返回结果。重复缓存帧不累积稳定时间，几何改变或会话停止明确失败。点击执行后观察失败仍保留 action_success=true，automatic_reexecution=false。

这是像素稳定启发式检测，不能保证网络请求或业务加载已完成，也可能错过采样点之间的细小变化。静止加载页仍可能满足像素稳定，持续动态游戏可能超时。完整参数、边界和验证见 [观察接口说明](../../../../docs/TODO/visual-wait-0.2.6/index.md)。
