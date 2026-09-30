# AI Limbs 视觉管理（v0.1.3）

视觉管理是独立的 `android_inprocess` 系统插件。它不直接操作 MediaProjection、CameraManager 或 Activity；所有屏幕和摄像头资源都经 AI Limbs Host Primitive 获取和释放。

v0.1.3 的边界：

- 使用 `host.screen.capture@1` 做单次截图，并管理 `host.screen.session@1` 的目标枚举、开始、状态、取帧和停止。
- 管理 `host.camera.session@1`：来源枚举、开始、状态、取帧、配置、停止。
- 使用 `host.camera.capture@1` 做单帧拍摄。
- 将成功取到的帧复制进插件自己的视觉缓存，并允许列表、单项删除和清空。
- 插件停止/禁用时主动停止自己拥有的屏幕和摄像头会话。
- 同一套能力同时开放给插件页面和兰儿调用，因此任一方启动的视觉会话都能由另一方看到和关闭。

暂不包含 OCR、目标识别、视觉推理、定时采样和长期视觉记忆；这些属于后续视觉能力层。

页面阅读通过 host.ui.automation@1 获取当前应用暴露的完整节点数据。page.inspect 返回固定 snapshot_id 和节点摘要；page.text 读取整页或指定节点全文，按 next_offset 续读直到 has_more=false。偏移按 UTF-16 字符计数，续读边界不会拆开表情字符。

插件页面提供“读取当前页面”和“查看最近页面全文”，与 AI 能力共享同一份快照。最近四份快照保留十分钟，每份原始数据不超过 4 MiB，卸载时释放；不会读取应用未加载或未暴露的文字。

云端流程增加完整节点文字、快照固定性和 Unicode 分页回归用例。本地只核对源码和声明，未运行编译或测试。
