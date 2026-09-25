# AI Limbs 视觉管理（v0.1.2）

视觉管理是独立的 `android_inprocess` 系统插件。它不直接操作 MediaProjection、CameraManager 或 Activity；所有屏幕和摄像头资源都经 AI Limbs Host Primitive 获取和释放。

v0.1.2 的边界：

- 使用 `host.screen.capture@1` 做单次截图，并管理 `host.screen.session@1` 的目标枚举、开始、状态、取帧和停止。
- 管理 `host.camera.session@1`：来源枚举、开始、状态、取帧、配置、停止。
- 使用 `host.camera.capture@1` 做单帧拍摄。
- 将成功取到的帧复制进插件自己的视觉缓存，并允许列表、单项删除和清空。
- 插件停止/禁用时主动停止自己拥有的屏幕和摄像头会话。
- 同一套能力同时开放给插件页面和兰儿调用，因此任一方启动的视觉会话都能由另一方看到和关闭。

暂不包含 OCR、目标识别、视觉推理、定时采样和长期视觉记忆；这些属于后续视觉能力层。
