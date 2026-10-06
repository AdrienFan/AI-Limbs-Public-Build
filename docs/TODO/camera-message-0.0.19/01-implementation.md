# 实现与验收

新增 MCP 工具 `ai_limbs_message_context`，无参数，调用基座 `ai_limbs.message_context.read`。每条新用户消息开始时由模型调用一次，同轮后续工具步骤不重复。调用行为取决于上游模型遵守指令，服务端不能拦截或强制 ChatGPT 的发送事件。

私有 Provider 由元数据 kind=message_context/context_api=1/event=user_message 选择，基座不包含具体视觉插件名；调用前后校验同一 canonical mount，默认总预算 12 秒。Host 执行策略、收据和原有相机授权有效。

视觉插件仅查询自己已有相机会话并读取本次新帧；未开启返回 INACTIVE，不开镜头、不请求权限、不切换镜头；失败/忙碌不重复拍摄、不返回旧预览。JPEG 最长边 1024、最大 512 KiB，只发一份 image content，更新临时预览并清理宿主临时图，不入图像档案。停止会话使正在取帧的 generation 失效。

Camera2 通过本次 onCaptureStarted timestamp 与 Image.timestamp 相等验证拍摄归属，兼容两种回调先后顺序。时间戳只用于匹配，不与系统时钟比较。返回 elapsedRealtime 新鲜度与期限证明。

桥保留请求收据去重、业务并发限制和已知 Host 结果计数。重复投递仅发送已保存结果；不重复拍摄。新增工具需要在 ChatGPT 网页/PC 刷新 MCP 工具目录并开启新对话。手机端没有刷新按钮。

回归用例覆盖匹配旧帧、回调先后、未开相机、失败不重复、期限、上下文 identity 与载荷重复、桥固定路由和重复投递。云端工作流执行回归与打包；设备验收待安装后：关闭相机正常聊天；开启选定镜头后连续消息画面更新时间；停止后无图；原共享屏操作反馈仍有效。

[DONE] 源码与回归用例；云端检查结果以 Actions 为准，实机行为待安装验收。
