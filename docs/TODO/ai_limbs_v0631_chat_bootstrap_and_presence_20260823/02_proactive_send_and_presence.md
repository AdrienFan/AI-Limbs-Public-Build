# 02 主动消息与在线状态

## 旧实现

- `ai_limbs.chat.reply` 必须依赖用户请求的 `request_id`。
- Session 没有绑定 AI Limbs Chat ID，无法可靠确定主动消息目的地。
- Badge 只判断 Bridge 是否在线。

## 新实现目标

- Bridge 聊天页把当前 Chat ID 绑定到开放 Session。
- `ai_limbs.chat.send` 使用稳定 `message_id` 实现重试幂等，并直接写入绑定的 Chat。
- 主动消息先持久化协议记录，再写聊天历史，最后标记已投递。
- Badge 使用 Bridge 与 Session 联合状态：绿为均在线，黄为仅 Bridge 在线，灰为 Bridge 离线。

## 正式主动消息调用

```json
{
  "name": "ai_limbs.chat.send",
  "parameters": {
    "session_id": "可选的开放 Session ID",
    "message_id": "建议由调用方生成并在重试时复用的稳定 ID",
    "content": "完整主动消息"
  }
}
```

主动消息不依赖 `request_id`。首次调用先保存 `PENDING` 协议记录及稳定 Chat 消息时间戳，再写入 Session 绑定的 Chat，成功后改为 `DELIVERED`。相同 `message_id`、Session 与正文的重试会更新同一条 Chat 消息；相同 ID 携带不同数据会被拒绝。

`ai_limbs.chat.status` 的协议版本为 2，新增 `bound_chat_id`、`proactive_pending_count`、`proactive_delivered_count` 和 `supports_proactive_send`。

## 三态显示

- 绿色 `0xFF00E676`：Bridge 在线且兰儿 Session 在线
- 黄色 `0xFFFFC107`：Bridge 在线但兰儿 Session 离线
- 灰色：Bridge 离线

[DONE]
