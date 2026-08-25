# 05 对齐 RDC Realtime 传输

## 现象

V0.6.3.7 已将 Android 图片整理为干净的 MCP `text + image`，但外部 ChatGPT 仍只收到图片引用，无法描述未知图片的真实像素。

## 已排除

图片文件、ImagePool、Base64、MIME 和 MCP `type=image` 结构已经与 Desktop Commander 模型侧实现对齐，不继续修改这些已验证环节。

## 本轮意图

将 AI Limbs 自研 RDC 设备传输从数据库轮询迁移到 Desktop Commander 当前 remote-device 使用的 Supabase Realtime 私有通道：

- 私有 `user:<user_id>` channel
- Presence 以 device ID 为 key
- Presence 成功后才声明 `transport_broadcast_v1`
- `new_call` doorbell 取代 pending call 轮询
- 结果写入数据库后发送 `result` doorbell
- 图片结果离开 Android 前只记录 MIME、内容类型和 Base64 长度，不记录 Base64 正文

不加入 OCR、元数据推断或视觉替代路径。

## 验收

安装后使用 RDC 读取一张兰儿事先不知道内容的 Android 图片。只有兰儿能直接描述至少 2 至 4 个具体视觉事实，才算 External Vision 真正通过；图片引用或“multimodal input”提示均不算成功。

当前仅完成源码实现与静态预检，尚未进行 APK 实机验收。

[DONE]
