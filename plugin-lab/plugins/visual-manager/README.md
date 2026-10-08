# AI Limbs 视觉工作台 0.2.12

本版修正点击后旋转换帧期间空缓存被误判为采集故障的路径，保留统一采样、动态策略、明确参数错误及帧与状态一致性验证。完整能力配套基座 0.8.0.19-build113；逻辑身份仍为 plugin.system.visual_manager，payload applicationId 为 v0212。ChatGPT 桥保持 0.0.30，各桥共用视觉能力。

## 0.2.12 旋转换帧间隔

0.2.11 五轮实测中，两轮正常 resize 后 18–44 ms 报 SCREEN_CAPTURE_UNAVAILABLE；同会话随后恢复取帧，采集日志没有停止或生产器异常。基座 resize 会清空旧缓存，而旧校验先把 frame_available=false 视为错误，再检查几何，因此健康的旋转间隔可能打断观察。

本版先严格验证会话、生产器及可见性。只有点击后的跟随模式、且状态几何已经改变时，空缓存和旧方向帧一样作为失效证据剔除，在原预算内等待新方向帧；不延长稳定窗口，不重放点击。相同几何无帧仍失败，standalone 等待仍拒绝方向变化。采集停止、生产器状态缺失、生产器异常、当前方向无帧分别给出明确错误信息。新增空缓存与故障边界回归用例，云端和部署后的实测结果仍待完成。详见 [修复记录](../../../../docs/TODO/visual-resize-gap-0.2.12/index.md)。

## 0.2.11 旋转期间的观察证据

0.2.10 实机启动横屏游戏时，点击成功后的观察一次报 SCREEN_GEOMETRY_CHANGED、一次正常跟随。原因是帧已取到、状态尚未查询时又发生旋转；状态验证仍严格要求同一方向，打断了允许跟随旋转的点击后观察。

点击后观察现在只接纳与活跃采集状态几何一致的帧。不一致的帧不参与变化和稳定判断，也不能作为超时后的返回图像；下一轮仍在原观察预算内采样。已有稳定窗口失效，经过状态验证的新方向帧建立新基准。visual_wait.superseded_frames 记录因此剔除的帧数，polls 包括这些采样轮，samples/observations 仍只计经过验证的证据。所有轮都无有效画面时明确返回观察失败。

点击前的旧帧和几何保护不变，standalone 等待仍拒绝方向改变。会话停止、内容不可见、同方向无帧或生产器异常仍明确失败；点击只注入一次。竞争状态验证与稳定窗口失效回归用例保留。0.2.11 部署后两次实测成功剔除旋转替换帧，另有空缓存误判路径由 0.2.12 修正。详见 [上一版修复记录](../../../../docs/TODO/visual-rotation-0.2.11/index.md)。

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

所有结果含 success。success=false 表示操作未完成，含 error_code、error 和 details；不可仅凭工具返回就弹成功提示。stop 部分失败会保留各来源的处理结果。图像内容只通过唯一的 mcp_content 附件传输，JSON 保留 mime_type 等元数据，不再含 data；UI 与兰儿使用同一份画面。

- status：真实会话、权限、操作状态、最新预览摘要和图像记录摘要
- sources：kind=screen/camera，列出可选来源
- permission：operation=check/request/open_settings，相机系统权限
- start：kind、source_id，启动并返回首帧和 session_id
- frame：kind、session_id、save=false/true，获取新画面
- capture：kind、source_id，单拍并保存；已有相机会话用 frame save=true
- stop：kind=screen/camera/all，可选 session_id
- preview.read / preview.save：kind，读取或保存当前预览
- images.list / images.read / images.delete / images.clear：图像记录管理
- page.inspect / page.text：即时固定页面快照、观察方式建议及全文分页
- observe：mode=auto/ui/visual，按当前页面证据选择节点或图像；include_image=true 可为 UI 补充一帧

示例，每步读取前一步结果后再调用，不把占位符当真实参数：

```json
{"kind":"camera"}
```

上例用于 sources。接着 permission 使用 {"operation":"request"}；成功后 start 使用 {"kind":"camera","source_id":"0"}，其中 0 必须来自 sources。frame 使用 start 返回的 session_id。stop 使用 {"kind":"all"}。

page.inspect 即时读取当前应用。查询 status.page.latest_snapshot，再按 snapshot_id 调用 page.text；has_more=true 时沿 next_offset 读取全文。直接在工作台调用 page.inspect 读取的就是当前工作台。快照保留最近四份、十分钟，UTF-16 偏移不会拆开表情字符。

## 实现边界与验证

Core 控制器负责业务状态、结果验证、临时预览及记录。presentation 仅调用本插件能力、渲染 JSON 图像和管理可见生命周期；不能访问设备或 Core 私有文件。Host 提供通用权限 token、设备占用与资源释放。

保留可用的完整页面读取器与 Unicode 分页用例。已有未授权启动、拒绝不重试、部分停止失败、取消截图、重复占用和即时状态发布的回归用例。本版新增反馈关闭时不访问设备、途中停止不重试及旧帧拒绝等用例。

0.2.7 已实机验收共享、预览恢复、存图、旋转映射及动态画面等待；0.2.8 修改通过云端工作流测试、编译和签名打包，安装后的新增行为仍需实机验收。

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

必须检查 wait_success 或 visual_wait.condition_met。TIMEOUT 可以带最后观察图像，但不表示稳定；success=true 仅代表返回结果。0.2.8 起，有效采集会话内重复的最新生产帧可累积观察稳定时间；每轮检查会话、采集错误、可见性和几何。几何改变或会话停止明确失败。点击执行后观察失败仍保留 action_success=true，automatic_reexecution=false。

这是像素稳定启发式检测，不能保证网络请求或业务加载已完成，也可能错过采样点之间的细小变化。静止加载页仍可能满足像素稳定，持续动态游戏可能超时。完整参数、边界和验证见 [观察接口说明](../../../../docs/TODO/visual-wait-0.2.6/index.md)。

## 0.2.8 自动观察与静止页面修复

`observe {"mode":"auto"}` 先固定完整 UI 快照。根据可读节点、可操作节点及 SurfaceView/TextureView/GLSurfaceView 面积选择观察方式：普通可读页面优先返回 page；大面积自绘且语义稀疏时返回图像和简短 page_evidence，全文仍可按 snapshot_id 读取。visual_mode 附 policy_version、原因及节点/面积统计；这是启发式判断，可指定 ui 或 visual。明确 visual 不查询 UI 树。自绘组件也可能暴露完整虚拟无障碍节点，因此不按游戏包名或类名单独断言。

UI 模式无需共享屏；include_image=true 或 visual 模式必须已经开启 READY 共享屏。该能力不启动会话、不请求授权、不归档；UI 读取错误直接失败，不暗中切换成功路径。页面和图像先后采集，atomic_page_and_frame=false，不能视作同一时刻。get_frame 保持直接取图，不额外遍历页面。

原静止页面不重绘时 samples 一直为 1，quietMs 无法增长。现在稳定窗口使用真实单调观察时间；samples 保持不同生产帧数量，observations/reused_observations 单独报告有效重复观察。帧编号、采集时间和 freshness 不变。同一帧若时间或像素发生不一致则报错。每轮读取已有会话状态并验证采集仍有效、有帧、未报告 producer_error、共享内容未明确不可见、几何未改变。旧平台可见性未知时不宣称可见。采集状态并不是生产器心跳证明，静止画面的像素稳定不能证明渲染器持续提交帧或业务完成。

后续屏幕帧、预览恢复和存图携带同一会话的 last_operation，包含 operation_id、完成时间及可得的动作参数。age_ms 是返回时距离完成的时间；frame_after_action 由真实采集时间判断，未知时为 null。停止或新会话不套用上一会话动作，较旧动作反馈不覆盖较新记录。等待结果 visual_wait 同步保存到预览元数据。该记录只覆盖本插件点击及宿主反馈通知的动作，不是全设备审计日志。

page.text 的公开 limit 参数现在实际控制分页（1..12000，默认 12000）；节点可指定 field=text/content_description。UTF-16 页边界不拆表情；limit=1 遇到双字符表情时返回完整字符以保证前进。快照数量、容量、节点数和深度均有上限。

官方依据：[ImageReader 新图像回调](https://developer.android.com/reference/android/media/ImageReader.OnImageAvailableListener)、[自绘组件与虚拟无障碍层级](https://developer.android.com/guide/topics/ui/accessibility/views/custom-views)。本插件中的观察时间规则和自动模式阈值是项目实现，不是 Android 保证。

详情及安装后验收清单见 [0.2.8 补齐记录](../../../../docs/TODO/visual-completion-0.2.8/index.md)。本轮不新增连续视频流，不修改共享权限或把手机唤醒作为后台工作条件。


## 0.2.10 正确性与动作闭环

屏幕基准帧和观察帧都从 RGBA 字节读取 64×64 RGB 网格，不再将 Bitmap.getPixel 的解释与原始 RGBA 解释混用。PNG 存图路径保留原始尺寸采样后再缩放预览；原始路径和解码路径都读取 native RGBA 通道。preview.sampling 包含 input、bitmap_difference_ratio 和 bitmap_exact_difference_ratio，用于验证同一帧两种解释是否一致。历史暂停画面 51.4% 误报的确切像素原因仍需新版实机诊断，不声明已经证实。

scene_profile=ui 保持 stable_ratio=0 默认行为，dynamic 默认 0.01。change_ratio 默认 0.02，显式参数覆盖策略，错误阈值直接返回说明，不自动修正。动态策略不是场景识别，也不自动屏蔽动画。可使用 region_left/top/width/height 选择有意义的区域。

tap_on_frame 默认 show_touch_feedback=false，关闭点击圆环但仍返回动作坐标与历史。Host 提供通用 show_touch_feedback 参数，原生 tap 默认仍显示反馈。screen_feedback 控制自动回图，与圆环控制相互独立。

点击前仍校验最新 frame_id 和当前几何。点击成功后的观察允许跟随旋转或尺寸变化，建立新几何基准并重启稳定窗口，visual_wait.geometry_changes 记录每次变化。几何变化本身计为变化，baseline_change_ratio 只描述当前几何段的像素变化；change_basis 明确两者。standalone wait_for_visual_change/wait_until_stable 仍严格绑定原几何。所有路径仅注入一次点击，TIMEOUT 或停止不重放操作。

new_frame 只要求一帧，不保证动画、网络加载或业务完成。页面跳转需要显式使用 stable/change_then_stable 与有意义的区域。action_timings_ms 来自 Host 的真实分段，完整耗时仍包含图片上传和远端处理，不能以本地优化承诺实时游戏反应。

Chat 的图片接收诊断保持标准单份 MCP 图片附件：以相同 media_id 在 Chat 与 Work 对照 content 图片块、mime_type、字节数和实际识图结果。retrieved:true 是读取回执，不是没有图片的证据。确认我们返回边界失误才修改桥；网络及上游处理波动不增加重试或降低默认画质。

详见 [本次迭代记录](../../../../docs/TODO/visual-loop-0.2.10/index.md)。云端 JVM 测试和编译通过后，静止游戏、旋转、反馈层及性能收益仍需部署验收。
