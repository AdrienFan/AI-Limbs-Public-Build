# 01 · 协议与幂等

## 旧实现

TRIGGERcmd Command 只识别纯文本 `Ping`。其他参数固定返回未接线错误，Transport 无法表达工具名、参数和请求身份。

## 新实现

- 参数接受 `AIL_TRIGGER_BRIDGE_V1` JSON，或 `b64:Base64URL(JSON)`
- 请求字段为 `protocol`、`request_id`、`tool` 与对象类型 `args`
- 响应携带相同 `protocol`、`request_id`、`tool`、`status`、`ok` 与 Dispatcher 原始结果
- 进程内保留最近 128 个完成结果；相同 request_id 与签名返回缓存，不同签名返回 `REQUEST_ID_CONFLICT`
- 请求串行进入执行区，避免重试与权限确认产生交错结果
- 纯文本 `Ping` 继续返回 `Pong`

## 请求示例

```json
{"protocol":"AIL_TRIGGER_BRIDGE_V1","request_id":"req-20260830-001","tool":"capability.search","args":{"query":"Ubuntu","limit":5}}
```

[DONE]
